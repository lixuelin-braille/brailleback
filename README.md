# brailleback盲文

安卓无障碍**盲文辅助**软件，含专业中文盲文排版。
"林"+"夕"相叠为"梦"，寓意让视障朋友"看见"世界、实现梦想。

> 📚 **完整文档见 [`docs/`](./docs/README.md)**
>
> 当前重点：**[07-稳定性改进清单](./docs/07-稳定性改进清单.md)**
> 项目方针：**不迁移到 TalkBack，专注现有项目的稳定性**

## 技术架构
- 基础：**Google BrailleBack**（eyes-free 开源）定制
  - ⚠️ 注意：不是 TalkBack。TalkBack fork 在另一个目录 `/d/软件/林夕读屏`，
    是"未来可能的迁移目标"，但**当前决策是不迁移**（见 `docs/98-存档-…`）
- 核心价值：自研中文盲文引擎
  - `JiebaDict` 自实现 jieba 分词（DAG + 最大概率，零第三方依赖）
  - `BrailleSegmenter` 规则引擎（书名号/数字/化学式保护、虚词分写、2+2 语节分写）
  - 阳光强制分写词库（1,439 条）+ 专名词表
  - 通用盲文全字符表（zhcn-cbs-full，含生僻字同音覆盖）
  - 避头点 + 避尾点（国标排版规则）
- 语言：Java + Kotlin 混编（分词引擎纯 Java/Kotlin）；
  liblouis（盲文转换）与 brltty（点显器驱动）通过 JNI 调用

## 模块
| 模块 | 说明 |
|:---|:---|
| `brailleback` | 主应用（无障碍服务、导航、规则、折行、音效、输入法、UI） |
| `service` | 盲文服务（**自研中文分词引擎** + liblouis JNI + brltty/HID 点显器驱动 + 盲文表/词库） |
| `client` | 客户端库（AIDL 契约与数据类） |
| `libraries/utils` | 通用工具（无障碍节点工具、标签管理、MIDI） |
| `libraries/compatutils` | Android 各版本兼容封装 |

## 构建
环境：AGP 9.2.0 + Gradle 9.4.1，JDK 17+，Android SDK 35，NDK 28.2（AGP 默认）。

```bash
# 推荐：用系统 gradle 并强制重跑（wrapper 会从网络下载；不加强制可能全 UP-TO-DATE 不出包）
gradle :brailleback:assembleDebug --rerun-tasks

# 或官方方式（wrapper 已配腾讯镜像）
./gradlew assembleDebug
```

产物：`brailleback/build/outputs/apk/debug/brailleback-debug.apk`（debug 实测约 33.4 MB，arm64-v8a + armeabi-v7a 双架构）

> ⚠️ 已知小问题：`local.properties` 的 `ndk.dir` 指向 NDK 29，与 AGP 默认的 28.2 不一致，
> 会报 `CXX1104` 且 `.so` 未 strip（不影响运行，仅 APK 偏大）。详见 `docs/00`。

## 持续集成

`.github/workflows/ci.yml` 两个 job：

- **单元测试**：push / PR 自动跑 `:brailleback:testDebugUnitTest`（53 套件 / 538 用例），
  输出用例总数并上传 XML 报告。
- **lint 门禁**：`:brailleback:lintDebug :service:lintDebug`，`abortOnError=true`——
  error 级问题（`NewApi`、`RegisterReceiverFlag`、`WrongConstant` 等）直接让 CI 变红。
  该 job 会装 NDK `28.2.13676358`（app 依赖 addon 的 ndk-build）。

本地等价命令：

```bash
./gradlew :brailleback:testDebugUnitTest --rerun-tasks --no-build-cache
./gradlew :brailleback:lintDebug :service:lintDebug
```

CI 里额外传 `-x verifyNativeLibs`：那个任务比对「jni 源码 mtime vs `jniLibs/*.so` mtime」，
只有本地编辑顺序下才有意义，CI 全新 checkout 会误报。

`MissingTranslation` 在两个模块的 `lint.xml` 里降级为 warning：仓库有 52 个语言目录，
新增字符串只保证简体，缺译是已知状态，但不该让构建整片变红。

## 文档

| 文档 | 内容 |
|:---|:---|
| [docs/README.md](./docs/README.md) | **文档索引** |
| [00-项目总览与架构](./docs/00-项目总览与架构.md) | 架构、数据流、构建、资源 |
| [01~04 各模块文档](./docs/README.md) | brailleback / service / client / libraries |
| [05-中文盲文引擎详解](./docs/05-中文盲文引擎详解.md) | 分词规则、常量表、质量改进 |
| [06-下一步开发指引](./docs/06-下一步开发指引.md) | 路线、TODO、扩展点、调试 |
| **[07-稳定性改进清单](./docs/07-稳定性改进清单.md)** | **当前重点：5 个真问题及改法** |
