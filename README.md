# 回路 · 尼伸水冷监控

Android 原生监控应用，面向官方 APK 中名为 `NiShen-CX2-pro` 的尼伸水冷散热器。只发送遥测查询。应用独立构建，支持 Android 9 或更新版本。Liquid Glass 折射需要 Android 13 或更新版本。

## 安装和开始监控

1. 安装随项目交付的 Debug APK。也可用 `adb install -r CoolingMonitor-debug.apk`。
2. 为散热器通电，在「监控 → 搜索散热器」授予附近设备权限。Android 9–11 还需开启系统定位。
3. 点击搜索结果的「连接」，等待「监控中」和温度读数。若同时运行官方 App 后连接失败，先在官方 App 断开，再搜索。
4. 在「悬浮窗」选择预设，点击「应用布局」，然后授予在其他应用上层显示的权限。
5. 在「历史」按设备与日期筛选并保存 CSV。在「设置 → 通信诊断」查看 GATT 服务、通知计数与日志。
6. 从「设备」、设置或通知点「停止监控」可释放 GATT 并关闭悬浮窗。触摸穿透模式可通过应用内按钮或通知恢复。

## 外观

在「设置 → 外观 → 界面风格」选择 ColorOS 或 Liquid Glass。风格覆盖各页面的导航、面板、分组和应用弹窗。浅色、深色、跟随系统和强调色独立于界面风格。悬浮窗默认跟随同一设置，窗口编辑中可保留单独的材质与颜色。

Liquid Glass 使用附件提供的 Backdrop 源码。应用窗口与系统悬浮窗各自用本窗口的图层渲染折射。系统管理的权限对话框和其他 App 的画面由 Android 自行绘制与合成。

## 构建

需要 JDK 21、Android SDK Platform 37.0、Android Build Tools 37.0.0。Wrapper 固定 Gradle 9.6.0。安装 SDK 后设定 `ANDROID_HOME`，或在工程根目录的 `local.properties` 添加 `sdk.dir`。

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:assembleRelease
```

Debug APK 位于 `app/build/outputs/apk/debug/`，首次构建由 Android Gradle Plugin 生成标准调试密钥。Release APK 位于 `app/build/outputs/apk/release/`，需要用长期发布密钥签名后再安装。应用 ID 是 `dev.cooling.monitor`。

## 技术文档

- 环境与附件在 `docs/environment-audit.md`。
- 官方 APK 结构和协议字段在 `docs/apk-analysis.md` 与 `docs/protocol-analysis.md`。
- 连接、数据、后台服务与图表在 `docs/architecture.md`。
- 悬浮窗手势、编辑和布局 JSON 在 `docs/overlay-editor.md`。
- Metric 与两项设计技能在 `docs/metric-design-analysis.md` 与 `docs/design-skills-report.md`。
- 测试证据、实机步骤和限制在 `docs/test-report.md`、`docs/device-validation.md` 与 `docs/known-issues.md`。
