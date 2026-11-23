# 构建与验证报告

验证日期为 2026 年 11 月 22–23 日（亚洲台北时区）。应用版本 0.2.0，包名 `dev.cooling.monitor`。

## 构建和静态证据

| 检查 | 结果 |
|---|---|
| `./gradlew --version` | Wrapper 9.6.0，JDK 21 |
| `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:assembleRelease` | Gradle 构建通过。生成 Debug 和 Release APK |
| Debug 签名 | `apksigner verify` 通过，APK Signature Scheme v2，Android Debug 证书 |
| Release 签名 | 构建件未签名。需用分发者签名后安装 |
| Android 版本 | min 28，target 36，compile 37 |
| 协议单测 | 8 通过，0 失败，0 跳过 |
| 外观单测 | 4 通过，覆盖原生蓝色、独立主题解析、对比色和悬浮窗默认色映射 |
| 解析器证据 | 单测覆盖官方温度查询常量、八条只读请求、高位温度、功率、风扇与开关范围、坏包、入站信封及过期读数 |
| 代码扫描 | 官方 APK 的扫描、通知、读取温度与 UI 转换均在 DEX 调用链核对。Release lint vital 无问题，完整 Debug Lint 0 错误、12 项警告 |

Debug Lint 警告包括 Android 9–10 的 Insets 兼容退路、目标 SDK 36 的版本提示和 KTX 写法提示。细则与厂商系统验证见已知问题。

测试响应由官方静态映射构造。未获得实物散热器上行帧、真实通知时间或硬件样本。上表的协议结果是静态与单元验证。

## 运行验证

Android API 34 x86_64 模拟器通过 ADB 注册。此环境不提供 KVM，使用软件 CPU 模拟。实机连线、ColorOS 17、游戏遮盖、长时功耗、实际温度、温度传感器位置、帧丢失与官方 App 同时连接的测试待补。详细步骤见 `device-validation.md`。

第一次模拟器启动进入 Android 系统服务初始化，ADB 能看到设备。系统启动属性尚未完成，窗口服务和安装命令超时，截图为空。未从这次尝试取得 app 实际显示或跨应用悬浮窗的通过证据。

改版模拟器使用 Android 14 x86_64、软件 CPU 模拟与软件渲染。已尝试降低虚拟机内存并重新连接 ADB，测试窗口内 ADB 保持 offline，未执行应用启动、页面风格切换或系统悬浮窗的运行验收。编译、单测与 Lint 结果见上表。
