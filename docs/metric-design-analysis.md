# Metric 设计参考

上传件的包名是 `com.itos.metric.monitor`，版本为 `2.2.2-release`，版本代码 1126。通过 DEX 内的更新 URL 定位到公开仓库 `ItosEO/Metric`。公开仓库默认分支提交 `709193821d79a500aeb6ef9684a088cd77776789` 提供 README 和更新文件。最新发布 `v2.3.0-release`，版本代码 1139。该提交的文件树没有 App 源码或 README 提到的设计文档，因此使用双方 APK 资源、内置说明和发布海报作为证据。 [仓库](https://github.com/ItosEO/Metric) · [发布记录](https://github.com/ItosEO/Metric/releases/tag/v2.3.0-release)

| 证据 | 可确认特征 | 回路的实现 |
|---|---|---|
| APK 字符串 `theme_mode_*`、`theme_settings_title` | 模式选择集中在外观内，有浅色、深色、跟随系统等选项 | 设置先呈现颜色模式和强调色，主题与监控选项分组 |
| `coui_icon_tint_*`、日夜资源 | 中性图标变种在浅色为黑色、深色为白色。蓝色浅 #0080FF、深 #1A8CFF，绿色浅 #00BD13、深 #24B232 | 默认选用 Metric 的日间与夜间蓝色。浅色背景使用白与灰，深色背景使用中性深灰，文字与描边按可读性调整 |
| `settings_bottom_bar_style_coui`、COUI 图标资源 | 存在 COUI 顶层导航选项与一致的图标命名 | Metric 的底栏资源显示 COUI、Liquid Glass 和 Material 三个选择。回路提供全局 ColorOS 与 Liquid Glass 两种风格，手机使用浮起的导航栏，宽屏使用导航轨 |
| 随包说明 | 主题、更新、数据采样、日志等按任务分组，未满足连接条件时显示实际状态 | 监控空态直接提供设备入口，外观和保留策略分组，原始帧记录可选 |
| 发布海报及更新说明 | 浅色灰绿层次、字重区分、圆角条目和短段落。说明提到配置 Sheet 与悬浮窗缩放 | 按用户反馈采用白 / 灰中性色分组和蓝色强调，主要图表保留深蓝灰连续绘图区域 |
| `settings_enhanced_animation` 与性能设置资源 | 存在增强动画与动画配置入口 | 独立动效级别用于页面和吸边，数值位置稳定 |

APK 中的动画资源名可以证明存在入口，无法确定实时过渡曲线、圆角和控件间距。回路的 48 dp 触控目标、字级与 16 / 22 / 28 dp 圆角来自原生 Android 审查和本项目设计。业务 daemon、Root 逻辑、品牌、美术和 UI 实现均独立于 Metric。
