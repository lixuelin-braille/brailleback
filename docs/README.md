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
