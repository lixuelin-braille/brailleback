# 📚 文档索引

> 项目：**brailleback盲文**（原「盲文迁移项目」/ 原林夕读屏）
> 文档生成：2026-08-29（基于代码逐行审查）
> 项目方针：**不迁移到 TalkBack，专注现有项目稳定性**
>
> ⚠️ **路径对照**：00–44 号文档里写的源码路径是 2026-11-20 之前的旧布局
> （模块根 `src/`、`res/`、`AndroidManifest.xml`、`test/`）。对照规则：
> `xxx/src/com/...` → `xxx/src/main/java/com/...`；`xxx/res/...` → `xxx/src/main/res/...`；
> `xxx/AndroidManifest.xml` → `xxx/src/main/AndroidManifest.xml`；`xxx/test/` → `xxx/src/test/`。
> 详见 [45-源码布局与工程结构现代化](./45-源码布局与工程结构现代化.md)。

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
| **[26-裸线程与执行器收编](./26-裸线程与执行器收编.md)** | 单线程执行器 → `limitedParallelism(1)`、USB 停机线程 → 协程；日志/`synchronized` 两项否决原因 | 再动线程或日志前读 |
| **[27-DefaultNavigationMode拆分](./27-DefaultNavigationMode拆分.md)** | 1302 → 937 行：滚动与可见性 15 个函数搬成同包扩展函数、6 字段 3 方法放宽到 internal、detekt 拦一次循环跳转 | 改导航或滚动逻辑前读 |
| **[28-targetSdk37](./28-targetSdk37.md)** | 11 模块 compileSdk/targetSdk 35 → 37、Android 17 变更逐条核对、真机五轮与 install 假信号 | 再提 targetSdk 或改打包配置前读 |
| **[29-点显器连接前台保活](./29-点显器连接前台保活.md)** | 无障碍服务未开启时连接跟着界面走、`onUnbind` 客户端归零即断；connectedDevice 前台保活 + `onUnbind` 保护、真机验证与边界 | 排查「退出界面点显器就不动」或改连接生命周期前必读 |
| **[30-电子书格式支持](./30-电子书格式支持.md)** | 阅读器新增 EPUB/HTML/Markdown：zip→container→OPF spine 取文字、HTML 清洗与实体解码、失败原因分级、6 例单测与真机冒烟 | 改阅读器格式分派或加新电子书格式前必读 |
| **[31-Word文档支持](./31-Word文档支持.md)** | 阅读器新增 DOCX/DOCM：只解 word/document.xml、段落/换行/制表符与实体处理、旧版 .doc 专用提示、3 例新增单测 | 改 Word 提取或文件格式提示前必读 |
| **[32-结果回传API统一](./32-结果回传API统一.md)** | 6 处旧 `startActivityForResult`/`requestPermissions` 换 launcher、基类选 `ComponentActivity` 的主题理由、4 例契约测试与 4 例仪器测试 | 改文件选择或权限申请前必读 |
| **[33-版本目录](./33-版本目录.md)** | 11 个模块的依赖与 `compileSdk/minSdk/targetSdk` 收进 `gradle/libs.versions.toml`、两条契约测试、刻意没进目录的三类版本 | 加依赖或升版本前必读 |
| **[34-线程与Handler盘点](./34-线程与Handler盘点.md)** | 54 处 Handler / 19 个 HandlerThread / 12 个裸线程的分类、为什么驱动线程不能换协程、剩下的收敛空间评估 | 想改线程模型前必读 |
| **[35-minSdk提升到29](./35-minSdk提升到29.md)** | minSdk 23（2015）→ 29（Android 10）、随之删掉的 10 个文件的分支、debug APK 因 dex 不压缩变大到 49 MB | 改安装下限或纠结包体积前必读 |
| **[36-界面层协程化](./36-界面层协程化.md)** | 113 行 Handler 命中的逐类判定、真正改了哪三处（引导页两个自调度 Runnable + IME 向导 300ms）、为什么驱动链/手势时序不动 | 想继续协程化线程模型前必读 |
| **[37-服务层协程化](./37-服务层协程化.md)** | `BrailleImeHostBridge` 一个作用域管三种延迟、`PassthroughProbe` 作用域随 install/uninstall、顺手补上的两处取消漏窗 | 改服务生命周期里的定时逻辑前必读 |
| **[38-依赖解冻](./38-依赖解冻.md)** | 网络恢复后把 Compose 1.7.6→1.12.1、activity→1.13.0、material3→1.4.0、coroutines→1.11.0 等一次抬齐；`material-icons-core` 停更要单钉、Compose 1.12 新 lint 抓到的非可观察 Locale | 动依赖版本前必读 |
| **[39-Compose组件级交互测试](./39-Compose组件级交互测试.md)** | 接上同版 `ui-test-junit4`、五例设置组件点击闭环（开关取反/禁用态/选择行只回调一次）、模拟器桌面 ANR 抢焦点导致的假失败 | 加 UI 交互测试前读 |
| **[40-设置主屏Compose化](./40-设置主屏Compose化.md)** | 最后一处 `PreferenceFragmentCompat` 退场：目录数据表 + 无 back stack 导航、六个契约测试怎么跟、detekt baseline 因搬家失效的坑、为什么这是 DataStore 的前置 | 改设置主屏或要迁 DataStore 前必读 |
| **[41-设置存储DataStore化](./41-设置存储DataStore化.md)** | 默认偏好改 DataStore 唯一写源 + 内存快照同步读、`chinese_segmentation` 为什么留在旧文件、幂等迁移、初始化锁与快照锁必须分开（否则设置页卡死） | 改存储层或往 `:service` 加读键前必读 |
| **[42-键盘帮助屏Compose化](./42-键盘帮助屏Compose化.md)** | 8 个残留布局里的第一个：`TextView+ListView` → `LazyColumn`；`Theme.DeviceDefault` 决定基类只能 `ComponentActivity`；两行文字要 `mergeDescendants` 才不会被读屏拆焦点；剩余布局的迁移难度排序 | 继续迁 XML 布局前必读 |
| **[43-书架Compose化](./43-书架Compose化.md)** | 第二个布局退场：长按菜单/删除确认/清空确认三道误删护栏怎么钉；书架偏好文件的编码是「用户书籍 ABI」不许动；`internal` 类型不能出现在 public override 里（改用匿名实现）；Compose 控件没有资源 id 后仪器测试怎么找按钮 | 改书架或继续迁布局前必读 |
| **[44-规范连写词表](./44-规范连写词表.md)** | 第一次动分词结果本身：分写库 6,782 条里 44.5% 拆出了单字片段（分钟、一瞬/间、一丘/之/貉），新增 `segment_connect.txt` 2,702 条压过阳光库；为什么只做「两套口径都同意」的部分；2+2 成语仍缺一张成语表 | 动分词、词库或汉盲翻译前必读 |
| **[45-源码布局与工程结构现代化](./45-源码布局与工程结构现代化.md)** | 11 个模块迁到 AGP 标准目录、sourceSets 覆写降到 1 处、36 个契约测试的路径改写与"扫整棵树"陷阱、增量资源合并陈旧与相对路径截断两个坑 | 找源码文件、加模块或改目录结构前必读 |
| **[46-界面层遗留与保留理由](./46-界面层遗留与保留理由.md)** | 剩下 6 个 XML 布局与最后一处 `PreferenceFragmentCompat` 的逐个判定：哪些是高重绘/IME 启动路径上必须留的 View，迁移要付什么代价、验收要过哪六关 | 有人（或未来的自己）再提"把所有 XML 都 Compose 化"时先读 |
| **[47-Gradle10兼容性清零空格赋值](./47-Gradle10兼容性清零空格赋值.md)** | 106 行空格赋值改等号：`compileSdk`/`namespace`/`abortOnError`/`url` 等 21 个属性和方法调用的区分白名单、`settings.gradle` 内联 `url` 为什么会被正则漏掉、为什么刻意留 1 条 AGP 警告 | 动构建脚本、升 Gradle 或接 CI 前必读 |

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
