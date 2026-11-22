# 55 - addon 六模块与 client Kotlin 化迁移复盘（commit 676a6f2）

> 复盘对象：`676a6f2`（提交信息：addon 六模块与 client 全量 Java→Kotlin）。
> 证据口径：当前 HEAD `a4f6f69` 源码路径；与迁移前原件的对照用 `git show 676a6f2~1:<path>`。
> `.tmp-java-originals/` 仅作比对副本，本文所有引用一律指向当前源码路径。
> 范围外：其后的 `72e8fee`（点位回显拆分）、`a5a009a`（拼音→词索引）改动不计入本结论。

## 结论速览

| # | 结论 | 依据 |
|:--|:--|:--|
| 1 | 提交信息与实测不符：`676a6f2` 的 diff **不含任何 `client/` 文件**，client 的 `.kt` 最早由 `87963e5` 引入；本次实际迁移面只有 addon 六模块 | `git show --name-only 676a6f2` |
| 2 | 336 个文件变更（+31477 / −31234），addon main 现存 `.kt` 193 个、`.java` 11 个；残留 Java 分五类保留理由，无一处是漏迁 | 见第一节清单 |
| 3 | 十类语义等价性风险中，**七项核对通过**（整数运算、equals/hashCode、默认参数、checked 异常、synchronized、内部类回调、SAM）；**三项有残留缺口**：可空性平台类型、反射构造器约束、命令名表登记不全 | 第二节 |
| 4 | 迁移提交夹带了两处非等价改动：TalkBack 桥缺失时新增 TTS 兜底分支；候选光标新增词绑定表。盲用户感知为「断桥不再静默」「选字播报绑定关系变化」 | `BrailleCommonTalkBackSpeaker.kt:34-36,72-76`；`CandidateCursor.kt:55,92-96` |
| 5 | 最大回归盲区：CI 单测门禁只跑 `:brailleback:testDebugUnitTest`，addon/service 的 14 个 Java 测试不进门禁；addon main 残留 **670 次** `!!`（按子串出现次数，口径见 2.1）未按 22 号文档口径清扫 | `.github/workflows/ci.yml:45` |
| 6 | 可立即清理：1 个空壳类、2 个 `.bak` 备份；AIDL 桩副本与 `@JvmStatic` 需先核对调用点再动 | 第四节 |
| 7 | 阻塞发布项仅 1 个：翻译失败路径的 null 缺口（R2）；其余为不阻塞的加固与清理 | 第五节 |

---

## 一、迁移范围实测

### 1.1 模块矩阵（main / test，已排除 `build/`、`.gradle/`）

| 模块 | main `.kt` | main `.java` | test `.java` | test `.kt` | 备注 |
|:--|--:|--:|--:|--:|:--|
| `addon/braillecommon` | 82 | 2 | 6 | 0 | 中文引擎、候选播报、偏好 |
| `addon/brailleime` | 58 | 1 | 1 | 0 | 键盘、手势、对话框 |
| `addon/brailleimeanalytics` | 1 | 0 | 0 | 0 | 空实现桩 |
| `addon/tbcompat` | 26 | 0 | 0 | 0 | 兼容工具层 |
| `addon/brailleinterfaces` | 11 | 1 | 0 | 0 | 跨模块接口 |
| `addon/brailletranslate` | 15 | 2 | 0 | 0 | 翻译工厂与 liblouis 桥 |
| `client` | 13 | 5 | 0 | 0 | 本次提交未改；迁移早于 `676a6f2` |
| `service`（范围外） | 0 | 2 | 7 | 21 | 未在本次提交 diff 内 |
| `brailleback`（范围外） | 0 | 0 | 0 | 58 | 主模块，CI 门禁对象 |

### 1.2 生产代码残留 Java 清单（11 个，逐条保留理由）

| 文件 | 保留理由 | 关键行 |
|:--|:--|:--|
| `addon/braillecommon/.../common/TalkBackSpeaker.java` | 接口含 `default` 方法与嵌套枚举 `AnnounceType`；Kotlin 实现类只覆写三参 `speak`，默认方法由 Java 侧提供 | 枚举 `29-31`；默认方法 `40-42` |
| `addon/braillecommon/.../translate/EditBufferStub.java` | 兜底 `EditBuffer` 实现，被语言选择分支引用，不是兼容壳 | 类声明 `30`；引用点 `BrailleLanguages.kt:1089` |
| `addon/brailleime/.../BrailleInputOptions.java` | `@AutoValue` 抽象类 + Builder；`brailleime/build.gradle` 仍挂 `annotationProcessor libs.autovalue` | `@AutoValue 23-24`；Builder `34-50` |
| `addon/brailleinterfaces/.../BrailleDisplayForBrailleIme.java` | 接口内嵌套 `@AutoValue abstract class ResultForDisplay`，整文件随 AutoValue 保留 | 接口 `23`；嵌套 AutoValue `37-38` |
| `addon/brailletranslate/.../TranslationResult.java` | `@AutoValue` + `@UsedByNative("louis_translation.cc")`；字段访问契约被 native 侧依赖 | `@AutoValue 57`；`@UsedByNative 58`；Builder `80-107` |
| `addon/brailletranslate/.../liblouis/LouisTranslation.java` | JNI 契约：`native` 方法、静态加载 `liblouiswrap_tb.so`、`classInitNative`；改 Kotlin 会改变 JNI 符号命名与静态初始化顺序 | native 方法 `108-117`；静态块 `119-125`；`TranslationMode 42-61` |
| `client/.../display/IBrailleService.java` | AIDL 生成桩，与 `IBrailleService.aidl` 并存；契约见 03 号文档 | 同目录 5 个桩之一 |
| `client/.../display/IBrailleServiceCallback.java` | 同上 | — |
| `client/.../selfbraille/ISelfBrailleService.java` | 同上 | — |
| `client/.../translate/ITranslatorService.java` | 同上 | — |
| `client/.../translate/ITranslatorServiceCallback.java` | 同上 | — |

JNI 契约有测试锁死：`brailleback/src/test/kotlin/.../NativeBridgeContractTest.kt:151-183` 校验插件侧包名、`louiswrap_tb` 库名与 `Java_com_google_android_accessibility_braille_translate_liblouis_LouisTranslation_##fn` 前缀；`LouisTranslation.java` 不得改语言。

### 1.3 测试目录残留 Java（14 个）及跟迁判断

**addon/braillecommon/src/test/java/.../translate/（6 个，直接测迁移后的 Kotlin 类）**

- `CandidateAnnouncerTest.java` — 静态调用 `CandidateAnnouncer.window(...)`
- `CandidateCursorTest.java` — 无参构造 + 静态方法
- `ChineseBrailleSignNamesTest.java` — 静态调用 `ChineseBrailleSignNames.describe(...)`
- `ChineseBrailleTableIndexTest.java` — 静态调用 `ChineseBrailleTableIndex.parse(...)`
- `ChineseDotStreamSegmenterTest.java` — 读 `translationtables.zip` 做切分断言
- `SyllableSplitterTest.java` — 匿名实现 Kotlin 接口 `BrailleTranslator`

**addon/brailleime/src/test/java/.../input/（1 个）**

- `DotUndoPolicyTest.java` — 静态调用 `DotUndoPolicy.shouldUndoDot(...)`

**service/src/test/java/（7 个，范围外模块）**

- `BluetoothDiscoveryPolicyTest.java`、`BondedDeviceFilterTest.java`、`HidConnectionLifecycleTest.java`、`HidInputReportNormalizerTest.java`、`UsbBrailleDeviceTest.java`、`WujieBrailleFrameCodecTest.java`、`WujieKeyEventCodecTest.java`

跟迁判断：

1. 这 7 个 addon 测试是迁移后 Kotlin 逻辑**唯一的纯 JVM 回归网**，但当前不进 CI（见第五节 R5、`.github/workflows/ci.yml:45`）；优先级应是**先纳入门禁**，语言跟迁是机械性收尾（S）。
2. Java 测试能编译通过的前提：被调静态入口保留 `@JvmStatic`（已核对：`CandidateAnnouncer.kt:51,80`、`ChineseBrailleTableIndex.kt:242,253`、`DotUndoPolicy.kt:39`、`ChineseBrailleSignNames.kt:131`），无参构造器保留公开可见性。
3. service 的 7 个 Java 测试随 service 模块另议，不在本次迁移结论内。

---

## 二、语义等价性逐项核对

每项口径：在本仓库是否成立（通过 / 部分成立 / 不成立）+ `file:line` + 盲用户视角影响。

### 2.1 可空性与平台类型 —— 部分成立（有残留缺口）

- **接口契约与实现不一致**：`BrailleTranslator.kt:37` 的 `translate` 声明返回非空 `TranslationResult`，但同文件注释注明失败返回 `null`；实现 `LibLouisTranslator.kt:76-81` 直接回传 `LouisTranslation.translate(...)` 的结果；而 `LouisTranslation.java:83-87` 在 native 失败时返回 `null`。Kotlin 调用方按非空使用，native 一旦返回 null，会在解引用点抛 NPE，而不是走翻译失败兜底。盲用户感知：偶发输入时键盘/点显器链路崩一下，而不是显示空表或报错提示。
- **`!!` 残留规模**（统计口径，可复现）：
  - 路径范围：`addon/**/src/main/**/*.kt`，剔除 `build/`、`.gradle/`、`.tmp-*` 副本；**不剔除注释与字符串字面量**；按子串 `!!` 的**出现次数**统计（不是「含 `!!` 的行数」）。
  - HEAD `a4f6f69`：193 个文件，合计 **670 次**；`676a6f2` 同口径也是 **670 次**（出现次数在两次提交间无增量）。早前文档里的 **626** 是旧口径「含 `!!` 的行数」（HEAD 与 `676a6f2` 均为 626 行），两种口径之差 44 来自同一行出现多次 `!!`，不是 `72e8fee` 之后新增的代码。
  - 复现命令（PowerShell / git）：
    ```powershell
    # 出现次数（新口径，= 670）
    git grep -o -F '!!' a4f6f69 -- 'addon/**/src/main/**/*.kt' |
      Measure-Object | Select-Object -ExpandProperty Count
    # 含 !! 的行数（旧口径，= 626）
    git grep -c -F '!!' a4f6f69 -- 'addon/**/src/main/**/*.kt' |
      ForEach-Object { ($_ -split ':')[-1] } | Measure-Object -Sum |
      Select-Object -ExpandProperty Sum
    ```
  - 热点文件（按出现次数，HEAD）：`BrailleIme.kt` 172、`BrailleImeGestureCommandActivity.kt` 110、`KeyboardView.kt` 48、`BrailleInputPlane.kt` 32、`BrailleImePreferencesActivity.kt` 31、`BrailleImeActor.kt` 29（其后 `BrailleInputView.kt` 24、`BrailleLanguagesActivity.kt` 15、`TutorialView.kt` 13）。这些点把 Java 平台类型当非空用，是 NPE 的直接落点。
- **已守住的点**：`BrailleCharacter.kt:27` 构造器 `vararg dotNumbers: Int?` 内部 `map { it!! }`，与原 Java 的 null 校验行为一致；`TranslatorClient.kt:297-302` 初始化失败先置空 listener 再回调，避免旧连接回写（与 23 号文档记录的静默失败教训一致，本文不重复方案）。

### 2.2 整数除法与移位 —— 不成立（已核对等价）

- `BrailleCharacter.kt:167-176` `toMirror()`：先 `.toInt()` 再 `shl/shr`，掩码 `0b00111000`、`0b00000111` 只保留目标位；与迁移前 Java 原件（`676a6f2~1` 同名方法）的字节符号扩展 + 掩码写法等价，符号位被掩码剔除，无差异。
- 退避延迟移位：`DisplayClient.kt:253`、`SelfBrailleClient.kt:179` `REBIND_DELAY_MILLIS shl mNumFailedBinds`；失败次数上限 5，最大 `500 << 4 = 8000ms`，无 int 溢出；与 Java `<<` 语义等价。
- 全仓扫描无 `>>>`（无符号右移）残留；现存 `/2` 均为偶数点阵的布局/点数折半，无奇数除法语义需要复核。

### 2.3 equals/hashCode 语义变化（Map key / 去重）—— 不成立（成对保留）

- `BrailleCharacter.kt:207-217`：`equals` 比较 `dotSet`，`hashCode` 用 `dotSet.hashCode()`，与 Java 原件逐行一致。
- `BrailleWord.kt:181-194`：`equals` 比较字符列表；`hashCode` 用 `Objects.hashCode(list)`，对非 null 列表等价于 `list.hashCode()`。
- 翻译器去重：`LibLouisTranslator.kt:83-95` 按 `tableName` 判等与散列；`TranslatorClient.kt:242-254` 同构，用于翻译实例缓存去重。
- 其余成对保留：`SelectionRange`、各 translator 与手势绑定类。
- **观察项（暂不阻塞）**：`client/.../translate/TranslationResult.kt:29` 是 `data class`，`ByteArray`/`IntArray` 字段的 equals 走引用语义；全仓检索无把它当 Map key 或 `==` 比较的点，`DisplayManager.kt:1096` 只比较 `cursorPosition`。若今后拿两个结果对象做去重，需改用数组内容比较。

### 2.4 static / 伴生对象与静态初始化顺序 —— 基本成立，一处需盯

- 静态入口全部转 `companion object` + `@JvmStatic`：`CandidateAnnouncer.kt:31,51,80`、`ChineseBrailleTableIndex.kt:166,242,253`、`BrailleImeAnalytics.kt:198-207`、`EditBufferUtils.kt:35-80` 等。
- 伴生 `init` 块：`BrailleInputEvent.kt:190-232` 命令名表在类首次访问时填充，与 Java 静态初始化顺序一致；表内登记缺口见 3.1。
- **既有竞态形状未被迁移改变**：`BrailleImeAnalytics.kt:199-206`、`BrailleCommonTalkBackSpeaker.kt:55-60` 的单例是非同步懒加载，实例字段未 `@Volatile`；与迁移前 Java 原件同形，迁移没有放大也没有修复这个窗口。

### 2.5 字段直接访问（@JvmField / @JvmStatic / 反射 / JNI）—— 成立，三处硬契约已守住

- **Parcelable `CREATOR` 全部 `@JvmField`**：`BrailleDisplayProperties.kt:59-60`、`BrailleKeyBinding.kt:57-62`、`WriteData.kt:97-109`、`client/.../TranslationResult.kt:59-60`。框架/Java 侧按字段名反射取 `CREATOR`，丢 `@JvmField` 会读不到，Parcel 反序列化直接失败。
- **公共字段直读保留**：`ImeConnection.kt:34-36` 三个字段 `@JvmField`，对应迁移前 Java 的 `public final` 字段（`676a6f2~1 ImeConnection.java` 核对）；Java 调用点直读 `inputConnection`/`editorInfo`/`announceType` 不受影响。
- **反射加载翻译器**：`TranslatorFactory.kt:47-57` 按全限定类名 `forName` + 无参构造器实例化；`LibLouis.kt:24` 是隐式公开无参构造器，当前可加载。风险点：今后给翻译器实现类加带默认参数的构造器而不加 `@JvmOverloads`，反射拿不到无参构造器，该语言翻译表整体加载失败——盲用户感知为切到该语言后点显器/输入无翻译结果。
- **JNI 侧**：见 1.2，`LouisTranslation.java`、`TranslationResult.java` 留在 Java；`BrailleKeyBinding.kt:56-62` 的四参构造器是 native/旧契约保留入口，未被精简。

### 2.6 默认参数与重载解析 —— 不成立

- addon/client main 公共函数全量扫描无默认参数（`fun ...: T = 默认值` 无命中）；无 `@JvmOverloads` 需求；Java 测试与 Java 生产代码调用的重载签名未变。
- 反例排查：`ChineseBrailleTableIndex.kt:242,253` 两个 `parse` 重载是显式双签名，不是默认参数生成。

### 2.7 异常传播与 checked exception 消失 —— 不成立（@Throws 保留）

- 迁移后 Kotlin 方法仍保留 `@Throws`（如 `BrailleTypoFinder.kt:72`）；跨进程序列化相关异常在 client 侧局部 catch：`SelfBrailleClient.kt:104`（`RemoteException`）、`120`（`NameNotFoundException`）、`126`（`NoSuchAlgorithmException`）。
- 残留注意：Kotlin 不强制 checked 捕获，Java 侧调用 Kotlin 方法时编译器不再检查 `throws`；当前跨语言调用点均为局部 catch，未发现漏捕获路径。

### 2.8 synchronized 与 @Synchronized —— 不成立

- 迁移前 Java 侧即无方法级 `synchronized`（`676a6f2~1` 全仓 grep 仅块级）；迁移后块级锁保留：`SelfBrailleClient.kt:162,177`，`DisplayClient.kt` 内部锁对象与原实现一致。
- 未发现把 `synchronized` 方法改成普通 Kotlin 方法导致锁丢失的情况。

### 2.9 内部类与匿名类回调 —— 不成立

- 持外部引用的内部类保留 `inner`：`DisplayClient.kt` 的 `Connection`/`ServiceCallback`/`DisplayHandler`、`SelfBrailleClient.kt:147,175`、`TutorialView.kt:143,183,253`。
- Java 测试匿名实现 `BrailleTranslator`（`SyllableSplitterTest.java`）对 Kotlin 普通接口 `BrailleTranslator.kt:24` 仍然可用；匿名类捕获外部实例的语义与 Java 一致。

### 2.10 SAM 转换 —— 基本成立，一处类型需注意

- 回调提供者接口改为 `fun interface`：`TalkBackForBrailleIme.kt:29`、`SpeechController.kt:74`、`KeyboardView.kt:563`；Kotlin 调用点传 lambda 正常解析。
- 注意：`fun interface` 的 SAM 合成只在 Kotlin 侧完整；Java 旧调用点以匿名类实现不受影响。当前未发现 Java 代码向 Kotlin `fun interface` 直接传 lambda 的反例；若今后新增此类调用，需按 Java 8 目标编译并实测。

---

## 三、对盲用户的实际影响

### 3.1 朗读文案顺序与时机

- **候选播报顺序保留**：`CandidateAnnouncer.kt:51-72` 先播候选字、再播「还有更多候选」；与 Java 原件一致，未调换先后。
- **夹带改动 A（行为变化，非等价翻译）**：`BrailleCommonTalkBackSpeaker.kt:34-36,72-76` 新增 `FallbackSpeaker` 兜底分支；迁移前 Java 原件在 TalkBack 桥为空时直接 `return`、不发声。迁移后桥缺失时改走 TTS 兜底。盲用户感知：TalkBack 桥断开的场景从「静默」变为「兜底朗读」，发声时机与音色都变了。
- **夹带改动 B（行为变化）**：`CandidateCursor.kt:55,92-96` 新增 `words` 绑定表与 `boundWordAt`；迁移前 Java 无此结构。候选字与词的绑定关系影响选字/翻候选时播报的内容。
- **命令名反查缺口**：`BrailleInputEvent.kt:275-376` 的 BRLTTY 6.7 新增命令未登记进 `190-232` 的 `CMD_NAMES`/`NAMES_TO_CMDS`。`commandToString` 对这些命令返回 `(unknown)`，`stringToCommand` 反查不到。盲用户按到这些键时，听到的不是命令名而是未知提示；自定义手势映射也无法按名绑定。
- 静态描述入口保留：`ChineseBrailleSignNames.kt:131` `describe` 带 `@JvmStatic`，点名列描述不受语言迁移影响。

### 3.2 点显器写入字节与节流节奏

- **Parcel 字段顺序保留**（跨进程写入字节顺序未变，逐条与 Java 原件核对）：
  - `BrailleDisplayProperties.kt:42-59`：文本格数 → 状态格数 → 键位绑定 → 友好键名；
  - `WriteData.kt:97-109`：节点 → 文本 → 属性；
  - `BrailleKeyBinding.kt:64-76`：命令 → 论点 → 名称；
  - `client/.../TranslationResult.kt:51-56`：cells → 双向位置表 → 光标位。
- **节流与退避节奏保留**：`DisplayClient.kt:253`、`SelfBrailleClient.kt:179` 指数退避；`DisplayClient.kt:297-302` 在 shutdown 后丢弃输入事件，不再向已关闭的连接写点。
- **翻译字节路径缺口**：`LibLouisTranslator.kt:76-81` 正常失败走 `createUnknown` 兜底；但 2.1 所述 native 返回 null 的缺口一旦命中，点显器拿到的不是兜底空表，而是 NPE 中断。

### 3.3 断连后的状态恢复

- `DisplayClient.kt:283-287`：仅在连接状态真正变化时通知监听者；显示属性无论状态如何都先缓存。`onServiceDisconnected` → 上报 `NOT_CONNECTED` 并调度重绑（`253` 附近）。
- `TranslatorClient.kt:278-288`：断连后重绑；`297-302` 初始化回调前先置空 listener，避免旧连接把结果回写到新会话。
- `SelfBrailleClient.kt:167-172`：断连清空 `mService` 并重绑；`116-145` 签名校验失败直接解绑不恢复，防止连到伪造服务。
- `ImeConnection.kt:34-36`：连接信息字段 `@JvmField` 保留，Java 侧直读链路未断。
- 未发现迁移导致「重连后候选/光标/ holdings 状态残留」的新路径；既有状态恢复逻辑与 Java 原件同构。

---

## 四、现在可以删掉的桥接层与死代码

| # | 对象 | 删除条件 | 成本 |
|:--|:--|:--|:--|
| D1 | `addon/braillecommon/.../common/FeatureFlagReader.kt:5` —— 空私有构造壳，全仓检索零调用（所有调用方用的是 `addon/brailleime/.../FeatureFlagReader.kt:7`） | 确认无反射/Manifest 引用（已 grep 全仓，仅定义处命中）后删除 | S |
| D2 | `brailleback/.../wrapping/WrapStrategy.java.bak-avoidhead` | `.bak` 后缀不进编译；git 历史可回溯后删除 | S |
| D3 | `service/.../translate/BrailleSegmenter.java.bak-math` | 同上 | S |
| D4 | `client` 5 个 AIDL `.java` 桩（见 1.2 清单）与 10 个 `.aidl` 并存 | 先逐方法核对 AGP 生成桩与提交副本一致、确认构建不依赖 `src/main/java` 副本；核对通过前**不要动** | M |
| D5 | 纯 Kotlin 调用点上的 `@JvmStatic` | 待 Java 残留清零后逐点复核：被 Java 生产代码/Java 测试/反射调用的必须留（`CandidateAnnouncer.kt`、`ChineseBrailleTableIndex.kt`、`DotUndoPolicy.kt:39`、`BrailleUserPreferences.kt` 各静态入口等）；仅 Kotlin 内部使用的可移除 | M |

**不可误删清单**（迁移有意保留，不是兼容壳）：

- `LouisTranslation.java` —— JNI 符号与静态加载契约（`NativeBridgeContractTest.kt:151-183` 锁死）；
- `TranslationResult.java` —— AutoValue + `@UsedByNative`；
- `BrailleInputOptions.java` —— AutoValue，`brailleime/build.gradle` 的 `annotationProcessor autovalue` 依赖它；
- `BrailleDisplayForBrailleIme.java` —— 嵌套 AutoValue `ResultForDisplay`；
- `TalkBackSpeaker.java` —— `default` 方法 + 嵌套枚举；
- `EditBufferStub.java` —— `BrailleLanguages.kt:1089` 兜底分支引用。

---

## 五、修复成本与发布阻塞分级

成本口径：**S** ≤1 天；**M** 2–4 天；**L** 1–2 周。

| 编号 | 事项 | 对应风险 | 成本 | 阻塞发布 |
|:--|:--|:--|:--|:--|
| R1 | addon 670 次 `!!` 分批清扫（口径见 2.1），先处理 2.1 列出的 6 个热点文件；每处补可空判断或改可空类型 | 2.1 | M | 否（但 NPE 路径需真机覆盖） |
| R2 | 修正 `BrailleTranslator.translate` 的空契约：返回类型改可空或沿用 23 号文档的 Result 类型；native null 走 `createUnknown` 兜底，禁止裸 NPE 上链路 | 2.1、3.2 | M | **是**（翻译失声/崩溃路径） |
| R3 | `TranslatorFactory.kt:47-57` 反射加载加构造器守卫与失败日志，加载失败时明确报错而不是静默失声 | 2.5 | S | 否 |
| R4 | `BrailleInputEvent.kt:275-376` 新增命令补登记进 `CMD_NAMES`/`NAMES_TO_CMDS`（`190-232`），并补命令名反查单测 | 3.1 | S | 否（影响按键播报名与自定义绑定） |
| R5 | 把 addon/common、addon/ime、service 的 `testDebugUnitTest` 纳入 CI 门禁（当前 `.github/workflows/ci.yml:45` 只跑 brailleback）；先保回归网进门禁，再谈测试语言跟迁 | 1.3、2.1 | M | **是**（迁移回归网不进门禁） |
| R6 | 清理 D1–D3 死代码与备份 | 四 | S | 否 |
| R7 | D4 AIDL 桩副本与生成代码逐方法核对，决定去留 | 四 | M | 否 |
| R8 | D5 `@JvmStatic` 调用点复核与批量精简 | 四 | M | 否 |

---

## 六、交叉引用与复核边界

- `docs/03-模块-client客户端库.md`：三组 AIDL 契约与 Parcelable 清单，本文 1.2、3.2 的字段顺序复核与其对照。
- `docs/22-Kotlin空安全清理.md`：`!!` 分批清零的三条硬规矩；本文 2.1 的 670 次残留（出现次数口径）不在 22 号文档已覆盖的主模块/service 范围内，是 addon 侧的补充口径。
- `docs/23-Kotlin结果类型替代null.md`：R2 的 Result 类型方案沿用其设计，本文不重复展开。
- `docs/25-Kotlin集合与标准库替换.md`：集合/数组的标准库替换口径；本文 2.3 只复核 equals/hashCode 与去重语义，不重复集合迁移细节。
- `docs/45-源码布局与工程结构现代化.md`：源码布局、`.tmp-java-originals/` 副本说明与契约测试路径。
- 复核边界：本文结论基于 `676a6f2~1` 与当前 HEAD 的源码对照；`client` 模块的 Kotlin 化发生在更早提交，其历史等价性不在本次复盘内；service、brailleback 的 Java 残留与测试另见 02、01 号文档。
