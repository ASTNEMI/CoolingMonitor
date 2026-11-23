package dev.cooling.monitor

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val Context.configData by preferencesDataStore("configuration")

enum class ThemeChoice(val label: String) { System("跟随系统"), Light("浅色"), Dark("深色") }
enum class InterfaceStyle(val label: String) { ColorOS("ColorOS"), LiquidGlass("Liquid Glass") }
enum class Motion(val label: String) { Full("完整"), Balanced("平衡"), Reduced("低功耗") }

data class Config(
    val theme: ThemeChoice = ThemeChoice.System,
    val accent: Int = 0,
    val interfaceStyle: InterfaceStyle = InterfaceStyle.ColorOS,
    val customAccent: Int? = null,
    val motion: Motion = Motion.Balanced,
    val refresh: Int = 0,
    val days: Int = 30,
    val reconnect: Boolean = true,
    val raw: Boolean = false
)

class SettingsStore(private val context: Context, private val scope: CoroutineScope) {
    private val prefs = context.getSharedPreferences("devices", Context.MODE_PRIVATE)
    val layouts = LayoutStore(context)

    private object Keys {
        val theme = stringPreferencesKey("theme")
        val motion = stringPreferencesKey("motion")
        val accent = intPreferencesKey("accent")
        val style = stringPreferencesKey("interface_style")
        val legacyGlass = booleanPreferencesKey("glass")
        val customAccent = intPreferencesKey("custom_accent")
        val refresh = intPreferencesKey("refresh")
        val days = intPreferencesKey("days")
        val reconnect = booleanPreferencesKey("reconnect")
        val raw = booleanPreferencesKey("raw")
    }

    val config = context.configData.data.map { p ->
        Config(
            theme = ThemeChoice.entries.firstOrNull { it.name == p[Keys.theme] } ?: ThemeChoice.System,
            accent = (p[Keys.accent] ?: 0).coerceIn(0, 3),
            interfaceStyle = InterfaceStyle.entries.firstOrNull { it.name == p[Keys.style] }
                ?: if (p[Keys.legacyGlass] == true) InterfaceStyle.LiquidGlass else InterfaceStyle.ColorOS,
            customAccent = p[Keys.customAccent],
            motion = Motion.entries.firstOrNull { it.name == p[Keys.motion] } ?: Motion.Balanced,
            refresh = p[Keys.refresh]?.takeIf { it == 0 || it in 200..5_000 } ?: 0,
            days = (p[Keys.days] ?: 30).coerceIn(1, 90),
            reconnect = p[Keys.reconnect] ?: true,
            raw = p[Keys.raw] ?: false
        )
    }.stateIn(scope, SharingStarted.Eagerly, Config())

    private fun <T> write(key: Preferences.Key<T>, value: T) {
        scope.launch { context.configData.edit { it[key] = value } }
    }

    fun theme(value: ThemeChoice) = write(Keys.theme, value.name)
    fun accent(value: Int) {
        scope.launch {
            context.configData.edit {
                it[Keys.accent] = value
                it.remove(Keys.customAccent)
            }
        }
    }
    fun customAccent(value: Int) = write(Keys.customAccent, value or 0xff000000.toInt())
    fun interfaceStyle(value: InterfaceStyle) = write(Keys.style, value.name)
    fun motion(value: Motion) = write(Keys.motion, value.name)
    fun refresh(value: Int) = write(Keys.refresh, value)
    fun days(value: Int) = write(Keys.days, value)
    fun reconnect(value: Boolean) = write(Keys.reconnect, value)
    fun raw(value: Boolean) = write(Keys.raw, value)

    suspend fun autoReconnectEnabled() = context.configData.data.first()[Keys.reconnect] ?: true

    fun saved(): Device? = prefs.getString("last_address", null)?.let { Device(it, CoolerProtocol.name) }
    fun save(device: Device) { prefs.edit().putString("last_address", device.address).apply() }
    fun forget() { prefs.edit().remove("last_address").apply() }
}
