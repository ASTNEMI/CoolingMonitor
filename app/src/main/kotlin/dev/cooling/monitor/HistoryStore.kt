package dev.cooling.monitor

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.UUID

data class HistoryRow(val session: String, val device: String, val time: Long, val metric: Metric, val value: Int)
data class HistoryDevice(val id: String, val name: String)
data class HistoryPage(val rows: List<HistoryRow>, val count: Int, val sessions: Int, val min: Int?, val max: Int?, val average: Double?)

class HistoryStore(context: Context, scope: CoroutineScope) : SQLiteOpenHelper(context, "monitor.db", null, 1) {
    private val writes = Channel<SQLiteDatabase.() -> Unit>(Channel.UNLIMITED)

    init {
        setWriteAheadLoggingEnabled(true)
        scope.launch(Dispatchers.IO) {
            for (operation in writes) {
                runCatching { writableDatabase.operation() }.onFailure {
                    android.util.Log.e("CoolingHistory", "Unable to write monitoring history", it)
                }
            }
        }
    }

    override fun onConfigure(db: SQLiteDatabase) = db.setForeignKeyConstraintsEnabled(true)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE sessions(id TEXT PRIMARY KEY, device TEXT NOT NULL, name TEXT NOT NULL, start INTEGER NOT NULL, end INTEGER)")
        db.execSQL("CREATE TABLE readings(session TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, at INTEGER NOT NULL, metric TEXT NOT NULL, value INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX reading_time ON readings(at)")
        db.execSQL("CREATE INDEX reading_session ON readings(session)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun begin(device: Device, time: Long): String {
        val id = UUID.randomUUID().toString()
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(device.address.toByteArray())
            .take(6).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        writes.trySend {
            insertOrThrow("sessions", null, ContentValues().apply {
                put("id", id)
                put("device", fingerprint)
                put("name", device.name)
                put("start", time)
            })
        }
        return id
    }

    fun insert(session: String, sample: Sample) {
        writes.trySend {
            insertWithOnConflict("readings", null, ContentValues().apply {
                put("session", session)
                put("at", sample.time)
                put("metric", sample.metric.name)
                put("value", sample.value)
            }, SQLiteDatabase.CONFLICT_IGNORE)
        }
    }

    fun end(session: String, time: Long) {
        writes.trySend { execSQL("UPDATE sessions SET end=? WHERE id=?", arrayOf<Any>(time, session)) }
    }

    suspend fun devices(): List<HistoryDevice> = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT device, name FROM sessions GROUP BY device, name ORDER BY MAX(start) DESC", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(HistoryDevice(cursor.getString(0), cursor.getString(1))) }
        }
    }

    private fun filter(from: Long, to: Long, device: String?): Pair<String, Array<String>> =
        if (device == null) "r.at BETWEEN ? AND ?" to arrayOf("$from", "$to")
        else "r.at BETWEEN ? AND ? AND s.device=?" to arrayOf("$from", "$to", device)

    private fun android.database.Cursor.historyRow() =
        HistoryRow(getString(0), getString(1), getLong(2), Metric.valueOf(getString(3)), getInt(4))

    suspend fun page(from: Long, to: Long, device: String?): HistoryPage = withContext(Dispatchers.IO) {
        val (where, args) = filter(from, to, device)
        val db = readableDatabase
        val rows = db.rawQuery(
            "SELECT r.session,s.name,r.at,r.metric,r.value FROM readings r JOIN sessions s ON s.id=r.session WHERE $where ORDER BY r.at DESC LIMIT 160",
            args
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.historyRow()) } }
        db.rawQuery(
            "SELECT COUNT(*),COUNT(DISTINCT r.session),MIN(CASE WHEN r.metric='Temperature' THEN r.value END),MAX(CASE WHEN r.metric='Temperature' THEN r.value END),AVG(CASE WHEN r.metric='Temperature' THEN r.value END) FROM readings r JOIN sessions s ON s.id=r.session WHERE $where",
            args
        ).use {
            it.moveToFirst()
            HistoryPage(rows, it.getInt(0), it.getInt(1), if (it.isNull(2)) null else it.getInt(2),
                if (it.isNull(3)) null else it.getInt(3), if (it.isNull(4)) null else it.getDouble(4))
        }
    }

    suspend fun csv(from: Long, to: Long, device: String?, output: java.io.Writer) = withContext(Dispatchers.IO) {
        val (where, args) = filter(from, to, device)
        output.write("session,device,time_utc,metric,value,unit\n")
        readableDatabase.rawQuery(
            "SELECT r.session,s.name,r.at,r.metric,r.value FROM readings r JOIN sessions s ON s.id=r.session WHERE $where ORDER BY r.at",
            args
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val row = cursor.historyRow()
                val name = row.device.replace("\"", "\"\"")
                output.write("${row.session},\"${name}\",${java.time.Instant.ofEpochMilli(row.time)},${row.metric.name},${row.value},${row.metric.unit}\n")
            }
        }
    }

    suspend fun delete(from: Long, to: Long, device: String?) = withContext(Dispatchers.IO) {
        val (where, args) = filter(from, to, device)
        writableDatabase.execSQL("DELETE FROM readings WHERE rowid IN (SELECT r.rowid FROM readings r JOIN sessions s ON s.id=r.session WHERE $where)", args)
        writableDatabase.execSQL("DELETE FROM sessions WHERE end IS NOT NULL AND id NOT IN (SELECT session FROM readings)")
    }

    fun prune(days: Int) {
        val limit = System.currentTimeMillis() - days * 86_400_000L
        writes.trySend {
            execSQL("DELETE FROM readings WHERE at < ?", arrayOf(limit))
            execSQL("DELETE FROM sessions WHERE start < ? AND id NOT IN (SELECT session FROM readings)", arrayOf(limit))
        }
    }
}
