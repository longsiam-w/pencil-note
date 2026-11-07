# PENCIL

面向安卓平板与触控笔的原生 Kotlin 笔记应用，支持无限画布、PDF 标注与 AI 辅助学习。目前处于 Beta 阶段。

## 功能

- 手写笔迹、压感、笔刷与擦除工具，双指平移和缩放。
- PDF 导入、标注、单双页显示及页码偏移。
- 矩形/套索框选、笔迹边界切分、移动、缩放与富内容剪贴板。
- 章节文件夹与结构模板、快捷访问、草稿纸、错题本和位置跳转。
- 多页合并与笔记/PDF 导出。
- OpenAI 兼容 API：选区提问、笔迹识别、可选上下文、流式回答、停止与末轮重新生成。
- 本地 Markdown/LaTeX 渲染、AI 回答区域截图复制。
- JSON 本地存储、原子写入、备份校验与恢复前快照。

## 技术栈

Kotlin、Android View/WebView、OpenGL ES 2.0 与 Android Canvas、Android PdfRenderer、协程、OkHttp、markdown-it、KaTeX。

- Android 8.0 / API 26 及以上；推荐安卓平板和触控笔。
- compileSdk / targetSdk：36；JVM 编译目标：17。
- 历史开发环境：Gradle 8.14.3、JDK 23.0.1。仓库当前不包含 Gradle Wrapper。

## 构建

安装 Android SDK 36，并在 Android Studio 中打开项目；SDK 路径由本机 `local.properties` 或 `ANDROID_HOME` 提供，不提交到仓库。

使用本机 Gradle：

```sh
gradle assembleDebug testDebugUnitTest --no-daemon
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`。

## AI 配置与数据安全

在应用设置中填写兼容的 API 地址、模型名称和 API Key；使用截图或上下文时，相关内容会发送给所配置的服务商，请自行确认其隐私政策。

备份有意排除 API Key。恢复会替换本地资料库，操作前请额外保留独立备份。恢复前快照回退目前不包含偏好设置的一体化回退；文件资料库与 SharedPreferences 尚非完整跨存储事务。

## 验证状态

项目包含几何切分、章节迁移、页码导航、SSE 与文件安全等 JVM 测试。已有构建和单元测试成功记录，但近期功能尚未完成全面真机回归；这不代表所有设备兼容、断电/低空间恢复或大规模资料库性能已验证。

## 文档

- [架构](docs/ARCHITECTURE.md)
- [数据格式](docs/DATA_FORMAT.md)
- [开发指南](docs/CONTRIBUTING.md)

部分技术文档来自较早版本，细节以当前源码为准。

## 开源协议

项目自有代码以 [MIT License](LICENSE) 发布。第三方代码及字体保留各自许可证，详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

本仓库不包含聊天导出、求职留痕、原始素材包、本机构建日志、安装包或交付 ZIP。
