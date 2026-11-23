# 环境与附件审计

| 附件 | 类型 | 可复核范围 |
|---|---|---|
| 尼精彩_1.2.3.apk | 已签名 Android APK | AAPT Manifest、DEX 指令、JADX 调用链、原生库名与 ABI |
| Metric_2.2.2-release.apk | 已签名 Android APK | 资源、随包文档、清单与版本。与公开仓库当前发布 APK 交叉对照 |
| AndroidLiquidGlass-2.0.1.zip | 源码与上游演示应用 | 读取并集成 Backdrop Android 和通用源码，保留 Apache 2.0 许可 |
| 已粘贴的文本.txt | 本项目需求 | 项目目标、阶段、约束与交付清单 |

```text
39bf738148eea2c4c58b184b737db1d349ea521f33753e92a0fb02b26f51a603  尼精彩_1.2.3.apk
3e822c1e324174af5b62a4cdd8e0210c33c530418e0aedf322298e3dcae3e767  Metric_2.2.2-release.apk
87a786ec599b0b847551368d8ce0c7c11d5f729d7baae09067f41c409d2b5bd8  AndroidLiquidGlass-2.0.1.zip
```

从空目录建立项目。构建采用 JDK 21、Gradle 9.6.0、AGP 9.3.2、Kotlin 2.4.10 与 Compose Foundation 1.12.0。SDK Manager 中平台包标识为 `platforms;android-37.0`。初次构建遇到 Gradle 直连 Maven 不可达，沿用环境 HTTP 代理和系统 CA 后解析成功。代理参数仅使用在构建环境，源码保持标准 Maven Central 与 Google Maven 配置。

材料审计与协议研究先于 UI 开发。协议映射建立后编写解析器和串行 GATT 客户端，再接入服务、数据存储和界面。实机通信与持续监控的结果见独立验证报告。
