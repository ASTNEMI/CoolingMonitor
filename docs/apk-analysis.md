# 尼精彩 APK 分析

| 项 | 结果 |
|---|---|
| 包名 | `com.nijingcai.jingcai` |
| 版本 | 1.2.3，版本代码 123 |
| SDK | min 28，target / compile 35 |
| 签名 | APK Signature Scheme v2，证书 DN `CN=Ma, OU=Graves, L=wuhan` |
| 证书 SHA-256 | `cfeb16dd79b853381c4015d740929099e6b5170656c2dc9c36537e62b4193f72` |
| DEX | `classes.dex`、`classes2.dex`，通信类及首页在 `classes2.dex` |
| Native | `libBugly_Native.so`，ABI 为 arm64-v8a、armeabi-v7a、armeabi |
| SDK 及依赖证据 | AndroidX、Kotlin、Hilt、Retrofit、OkHttp、Bugly、Glide、EasyPermissions、PictureSelector、AppUpdate |
| 混淆和反编译 | 核心包与成员名可辨识，JADX 1.5.3 全量分析报 4 处反编译错误。对首页的轮询和数据分支使用 DEX 指令补核。 |

## Manifest

- 启动与导航包括 `SplashActivity`、`WelcomeActivity`、`MainActivity`、`ChoiceDevActivity`、`DeviceActivity`。
- 监控与设备设置包括 `MonitorSettingActivity`、`LocalLogActivity`、`EnergySetActivity`、`LightSetActivity`、`SkinSettingsActivity`、`MoreSetActivity`、`ShowImageActivity`。
- 其他业务界面包括 `LoginWithPwdActivity`、`LogoutActivity`、`VipActivity`、`AboutUsActivity`、`ProblemSolveActivity`、`ProtocolActivity` 与 `ProtectRuleActivity`。
- 声明的两个 Service 来自 PictureSelector（`com.luck.picture.lib.service.ForegroundService`）及 AppUpdate（`com.azhon.appupdate.service.DownloadService`）。声明的 Receiver 为 `androidx.profileinstaller.ProfileInstallReceiver`。首页通过 `registerReceiver` 注册蓝牙状态广播。
- Provider 来自 PictureSelector、AppUpdate、AnyLayer 和 AndroidX Startup。
- Manifest 声明蓝牙 Scan / Connect、旧版 Bluetooth / Admin、粗略与精确定位、Nearby Wi‑Fi、网络与网络状态，另有媒体与存储、相机、系统设置、通知、前台服务、音频设置、安装包、进程及应用内部接收器权限。新应用仅声明 BLE、前台服务、通知与悬浮窗相关权限。
- Retrofit / OkHttp 与业务 API 共存。散热器读数有独立的 Bluetooth GATT 回调链。目标路径未找到经典蓝牙 Socket 或 USB 入口。

## 复查

```bash
aapt dump badging '尼精彩_1.2.3.apk'
aapt dump xmltree '尼精彩_1.2.3.apk' AndroidManifest.xml
apksigner verify --print-certs '尼精彩_1.2.3.apk'
jadx -j 4 --no-res -d official '尼精彩_1.2.3.apk'
unzip -p '尼精彩_1.2.3.apk' classes2.dex > classes2.dex
dexdump -d classes2.dex
```

在 DEX 中检查 `com.nijingcai.jingcai.utils.BleHelper`、`BleHelper$gattCallback$1`、`DeviceOrderInfo$Companion` 与 `IndexFragment$setupDataCallbacks$2$invoke$$inlined$runOnUiThread$1`。
