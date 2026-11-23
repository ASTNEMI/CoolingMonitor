# 两项设计 Skill 使用记录

查阅和改版日期为 2026 年 11 月 22–23 日（亚洲台北时区）。

| 来源 | 版本 | 读取内容与落地 |
|---|---|---|
| Anthropic Frontend Design | `e4bb5d960d87126ff3b3a8af1a104b7db67560ba` | `skills/frontend-design/SKILL.md` 与同目录许可。先在 `DESIGN.md` 定义色彩、数字字形、布局和产品数据主轴。设计先定位仪表重心，再去掉通用卡片式分区。 `Design.kt` 建立语义颜色与字阶，`Dashboard.kt` 使用统一坐标和真实空态。 |
| Impeccable | `b49177d32788f2dc585c42831d845dacf1ebed2c`，`skill-v4.10.0` | 仓库 README、Codex 的 `SKILL.md`、`android.md`、`new-work.md`、`mode-operate.md`、`craft-floor.md`、`audit.native.md`、`critique.md` 与评估规则、`polish.md`。以 Operate 为原生产品方向。从本地 launcher 运行 context，由安装器安装 project scope 的 Codex Skill。网页检测 hook 和浏览器探针不适用于 Compose。 |

仓库地址 [Anthropic Frontend Design](https://github.com/anthropics/skills/tree/e4bb5d960d87126ff3b3a8af1a104b7db67560ba/skills/frontend-design) · [Impeccable](https://github.com/pbakaus/impeccable/tree/b49177d32788f2dc585c42831d845dacf1ebed2c)

## Android 审查与修正

- **层级。** 保留主温度和单一深色图表，数据缺失时显示明确的下一步与未填充坐标网格。使用 Barlow Condensed 与 tabular figures 稳定数值。
- **恢复路径。** 双重入口恢复穿透，通知可停止监控。锁定后仍可折叠与展开。画布的图层、尺寸和位置可用显式控件操作。
- **原生适配。** Compact 版使用系统底部导航，宽屏用导航轨、分栏及最大内容宽度。窗口和 Compose 页面分别处理 Insets，键盘进入时保持表单可滚动。
- **性能和动效。** 用设置控制屏幕刷新与短过渡，按 Android 动画设置限制吸边动作。玻璃关闭时不创建 Backdrop 图层，开启时在全局背景、导航、仪表盘、设备、历史、诊断、编辑器和应用弹窗使用上传源码的模糊和折射，悬浮窗使用自己的背景图层。
- **信息可信度。** 区分未连接、订阅完成、读数过期与真实数据到达。未知报文只统计及按需记录。
- **原生验证证据。** 界面所见、截图与交互结果见 `test-report.md`。物理设备的 TalkBack、各厂商分屏及实际帧时间继续核对。

按明确产品需求直接采用原生仪表方向，未引入网页交互审查界面或额外审批环节。

用户后续明确要求去掉原绿色方向。改版以 ColorOS 中性色分组为默认，采用 Metric 资源中的蓝色，保留回路自己的图表、导航适配和业务。风格设置从局部玻璃开关提升到互斥的全局样式选项，颜色模式与强调色分别配置。
