# 📚 文档索引

> 项目：**brailleback盲文**（原「盲文迁移项目」/ 原林夕读屏）
> 文档生成：2026-08-29（基于代码逐行审查）
> 项目方针：**不迁移到 TalkBack，专注现有项目稳定性**

---

## 主文档

| 文档 | 内容 | 什么时候看 |
|:---|:---|:---|
| **[00-项目总览与架构](./00-项目总览与架构.md)** | 架构图、模块地图、双向数据流、构建环境、数据资源 | 第一次接触项目 |
| **[01-模块-brailleback主模块](./01-模块-brailleback主模块.md)** | 46 个源文件清单 + 核心类详解（服务/导航/规则/折行/音效/IME） | 改主应用 |
| **[02-模块-service盲文服务](./02-模块-service盲文服务.md)** | 翻译 + 点显器两包详解（含未提交的 Unicode 直通特性） | 改翻译或点显器 |
| **[03-模块-client客户端库](./03-模块-client客户端库.md)** | 三组 AIDL 契约（display / translate / selfbraille） | 改 IPC 接口 |
| **[04-模块-libraries工具库](./04-模块-libraries工具库.md)** | utils(83) + compatutils(23) | 找工具方法 |
| **[05-中文盲文引擎详解](./05-中文盲文引擎详解.md)** | 分词流水线、8 条规则、常量表、质量水平与改进 | 调分词质量 |
| **[06-下一步开发指引](./06-下一步开发指引.md)** | 状态体检、开发路线、18 条 TODO、扩展点速查、调试速查 | 规划开发 |
| **[07-稳定性改进清单](./07-稳定性改进清单.md)** | **5 个真问题 + 3 个次要项**，含 file:line 与改法 | **当前重点** |
| **[15-运行时并发迁移清单](./15-运行时并发迁移清单.md)** | `DisplayManager.kt` 的 Handler/volatile 触点、迁移写法、分批与真机验收 | 动刷新链路前必读 |
| **[16-发布工程差距](./16-发布工程差距.md)** | release 签名接入、targetSdk 36 的阻塞项、实测产物大小 | 发版前必读 |
| **[17-保活与权限适配](./17-保活与权限适配.md)** | 电池优化/厂商后台限制/通知与蓝牙权限自检屏、Android 17 行为变更、厂商自启动组件 | 排查「点显器不动了」必读 |
| **[18-连接方式自动选择](./18-连接方式自动选择.md)** | 新增「自动」选项：插着点显器走 USB、没插走蓝牙；四处判断的连带改动 | 改连接逻辑前必读 |
| **[19-仪器测试](./19-仪器测试.md)** | androidTest 冒烟 4 例、离线依赖怎么选、为什么不用 connectedDebugAndroidTest | 改 UI 或资源数组时跑一次 |
| **[20-依赖与镜像](./20-依赖与镜像.md)** | 阿里/腾讯镜像实测、补进缓存的依赖清单、哪些接了哪些不接、ktlint 与 detekt 试跑结论 | 要加新依赖时先读 |
| **[21-静态检查与协程升级](./21-静态检查与协程升级.md)** | detekt baseline（284 条）与门禁自检、coroutines 1.9.0 升级与验证边界 | 改大段代码或升依赖前读 |

| **[22-Kotlin空安全清理](./22-Kotlin空安全清理.md)** | 非空断言改法与三条硬规矩、脚本改文件的两次踩坑、281 到 0 的五批记录 | 改空安全相关代码前必读 |
| **[23-Kotlin结果类型替代null](./23-Kotlin结果类型替代null.md)** | 用 sealed 结果类型替掉「null 表示失败」、`Failure` 子集让 `when` 穷尽、debug APK 体积真相 | 改解析或协议返回前读 |
| **[24-DisplayService拆分](./24-DisplayService拆分.md)** | 1763 → 1317 行：27 个连接编排函数搬成同包扩展函数、三份源码契约的连带改动、detekt ReturnCount 拦一次 | 改点显器连接流程前读 |
| **[25-Kotlin集合与标准库替换](./25-Kotlin集合与标准库替换.md)** | `java.util` import 89 → 52、`Arrays`/`Collections` 换标准库、ArrayDeque 两次踩坑 | 动集合或排序代码前读 |

---

## 存档（不执行）

| 文档 | 说明 |
|:---|:---|
| [98-存档-TalkBack迁移方案（不执行）](./98-存档-TalkBack迁移方案（不执行）.md) | 迁移到 Google TalkBack 的方案。**项目决策：不迁移**，仅存档备查 |
| [99-存档-项目说明](./99-存档-项目说明.md) | 原项目说明（名字寓意、技术架构、状态） |

---

## 当前重点：稳定性

项目方针是**不增加新功能，以提高稳定性为主**。
优先处理 [07-稳定性改进清单](./07-稳定性改进清单.md) 中的：

| 编号 | 问题 | 改动量 |
|:---|:---|:---:|
| **S1** | `FeedbackManager` 从未关闭 → 资源泄漏 | 3 行 |
| **S2** | `HidBridge` 静态 controller 不清理 → 泄漏+陈旧引用 | 1 行 |
| **S4** | 显示位置映射 `-1` 未防护 → 越界/错位 | 3 行 |
| S3 | `TranslatorService` native 内存未释放 | 需改 C 层 |

---

## 快速命令

```bash
# 构建（--rerun-tasks 必须有，否则可能全 UP-TO-DATE 不出包）
gradle :brailleback:assembleDebug --rerun-tasks

# 安装
adb install -r brailleback/build/outputs/apk/debug/brailleback-debug.apk

# 关键日志
adb logcat -s SegDebug          # 分词前后对比
adb logcat -s JiebaDict         # 词典加载
adb logcat -s BrailleSegmenter  # 分词器就绪
adb logcat -s DisplayService    # 点显器连接
```
