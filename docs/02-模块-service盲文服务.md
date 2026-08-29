# 模块文档 · `:service`（盲文服务）

> 目录：`service/`
> 规模：Java 13 + Kotlin 2（源码约 3,936 行），JNI 原生层 700 个 .c/.h
> 身份：**项目自研价值的核心载体** —— 中文盲文引擎 + 点显器驱动

---

## 一、模块职责

`:service` 承担两件完全不同的事，被打包成 AAR 供 `:brailleback` 使用（**同进程**）：

1. **翻译（translate）** —— 文本 → 盲文点位。**自研中文分词规则引擎在这里**。
2. **显示（display）** —— 盲文点位 → 点显器硬件。brltty 6.7 + Braille HID。

---

## 二、包结构

```
service/src/com/googlecode/eyesfree/braille/
├── service/
│   ├── translate/          ← 翻译（自研核心）
│   │   ├── BrailleSegmenter.kt   418 行  中文盲文分词规则引擎 ★★★
│   │   ├── JiebaDict.kt          207 行  自实现 jieba 分词器（DAG+最大概率）★★
│   │   ├── TranslatorService.java 424 行 AIDL 服务入口（翻译中枢）★
│   │   ├── LibLouisWrapper.java   95 行  JNI 封装 → liblouis
│   │   └── TableList.java        168 行  盲文表清单（解析 res/xml/tablelist.xml）
│   ├── display/           ← 点显器连接
│   │   ├── DisplayService.java   713 行  连接服务主类 ★
│   │   ├── DeviceFinder.java     653 行  设备发现（蓝牙/USB/HID）★
│   │   ├── DriverThread.java     257 行  驱动线程（喂数据给 brltty）
│   │   ├── ReadThread.java       231 行  读线程（从 brltty 收输入事件）
│   │   ├── BrlttyWrapper.java    235 行  JNI 封装 → brltty
│   │   ├── HidDisplayConnection.java 271 行  Braille HID 连接实现 ★
│   │   ├── BrlttyHidNativeHelper.java 109 行  HID 数据桥（JNI 回调）
│   │   ├── HidBridge.java         67 行  跨组件传 BrailleDisplayController ★
│   │   └── UsbHelper.java         88 行  USB 设备辅助
│   └── utils/
│       └── ZipResourceExtractor.java  异步解压 raw zip 到私有目录
├── assets/
│   ├── jiebadt.txt          5.07 MB  词库（34.9 万词）
│   └── sunshine_split.txt   38 KB    阳光强制分写词库（1,439 条）
├── res/raw/
│   ├── translationtables.zip  2.26 MB / 131 张表（含 zhcn-cbs-full.ctb）
│   └── keytables.zip          146 KB / 196 张键位表
└── jni/
    ├── brlttywrapper/   third_party/brltty (6.7) + driver.mk + Android.mk
    └── liblouiswrapper/ liblouis (2.6.0) + tables (485 张)
```

---

## 三、`translate` 包详解

### 3.1 `TranslatorService`（424 行）—— 翻译中枢

`Service`，实现 `ITranslatorService.Stub`，被 `:brailleback` 通过 `TranslatorClient` 绑定调用。

**生命周期**
```
onCreate()
  ├─ mTableList = new TableList(getResources())
  ├─ extractDataFiles()      异步解压 translationtables.zip → getDir("translator")
  │                          完成后 LibLouisWrapper.setTablesDir()，回调 pending callbacks
  └─ startSegmenterInit()    后台线程初始化分词器（不阻塞主线程）
```

`startSegmenterInit()` 在名为 `BrailleSegmenterInit` 的线程里：
1. `new JiebaDict()` → `init(getAssets().open("jiebadt.txt"))`
2. `new BrailleSegmenter(mJiebaDict)` → `loadSplitDict(getAssets().open("sunshine_split.txt"))`
3. 置 `mSegmenterReady = true`

> 设计要点：分词器初始化是**异步**的。在它 Ready 之前，中文表会**降级**走纯 liblouis（不分词），
> 不会崩溃，但盲文分词质量差。这是个可观测的启动竞态。

**翻译分发逻辑**（`ServiceImpl.translate()`）

```
translate(text, tableId, cursorPosition, computerBrailleAtCursor)
  │
  ├─ 参数校验 + checkDataFiles()
  ├─ mTableList.getFileName(tableId) → tableName
  │
  ├─ ① containsBrailleUnicode(text) ？
  │      是 → translateWithBraillePassThrough()   【电子盲文文档直通，见 3.2】
  │
  ├─ ② mSegmenterReady && isChineseTable(tableName) ？
  │      是 → translateWithSegment()              【中文分词路径，见 3.3】
  │
  └─ ③ 兜底 → LibLouisWrapper.translate()          【其他语言/分词器未就绪】
```

`isChineseTable()`：表名小写以 `"zh"` 开头即视为中文表。

**AIDL 接口**（`ITranslatorService`）
| 方法 | 说明 |
|:---|:---|
| `setCallback(ITranslatorServiceCallback)` | 注册初始化回调（数据文件解压完成时通知） |
| `TableInfo[] getTableInfos()` | 可用盲文表清单 |
| `boolean checkTable(String tableId)` | 校验表能否加载 |
| `TranslationResult translate(String, String, int, boolean)` | 文本 → 盲文点位 |
| `String backTranslate(byte[] cells, String tableId)` | 盲文点位 → 文本（反翻译） |

---

### 3.2 Unicode 盲文直通（⚠️ 未提交的新特性，+112 行）

> **当前状态：工作区未提交**（`git status` 显示 `M TranslatorService.java`）
> 提交记录里还没有它，是最新开发中的功能。

**解决什么问题**：标准电子盲文文档（如 BRF 盲文电子书）里的字符本身就是盲文点位
（Unicode `U+2800–U+28FF`，每个字符的低 8 位＝一个 cell 的点位）。
如果把这些字符当普通文本送去 liblouis 翻译，盲文表里没有对应规则，**会翻译成乱码**。

**实现**
```java
isBrailleCellChar(char c)        →  c >= 0x2800 && c <= 0x28FF
containsBrailleUnicode(String)   →  是否含盲文字符

translateWithBraillePassThrough(text, tableName, cursor, computerBraille):
    把 text 切成「盲文段 / 普通段」交替的 run：
      盲文 run  → 逐字符 cell = codepoint & 0xFF，1:1 直通，不动
      普通 run  → 按原逻辑翻译（中文表走分词，否则直接 liblouis）
    拼装时维护 cellOffset，并重建 ttb / btt 位置映射 + 光标位置
```

**意义**：这是打通「盲文图书馆」（`/d/软件/盲文图书馆`）的关键 —— 让 App 能正确显示现成盲文电子书。

**待办**：这段改动尚未 commit，建议先补单元测试（盲文/普通混排的位置映射正确性）再提交。

---

### 3.3 `translateWithSegment()` —— 中文分词路径

```java
BrailleSegmenter.Result seg = mSegmenter.segment(text);   // ① 分词，带空格
int segCursor = seg.origToSeg[cursorPosition];            // ② 光标位置换算
TranslationResult tr = LibLouisWrapper.translate(seg.text, tableName, segCursor, ...);  // ③ 翻译
// ④ 用 seg.origToSeg / seg.segToOrig 把 liblouis 返回的位置映射「还原」回原文坐标系
newTtb[i] = ttb[ seg.origToSeg[i] ]
newBtt[c] = seg.segToOrig[ btt[c] ]
```

> 关键设计：分词会给文本**插入空格**（词间分隔），导致字符下标偏移。
> 所以必须做「原文 ↔ 分词文本」的双向位置映射修正，否则**光标定位会错位**。
> 异常时 catch 后降级为直接 `LibLouisWrapper.translate()`。

---

### 3.4 `BrailleSegmenter`（418 行）—— 中文盲文分词规则引擎 ★核心

**输入**：`JiebaDict`；**输出**：`Result { text, origToSeg[], segToOrig[] }`

**初始化（`init` 块）对词典做增删**：
| 操作 | 词表 | 效果 |
|:---|:---|:---|
| `dict.delWord(w)` | `SPLIT4` | 删掉应分写的整体词（如"风声鹤唳"），让它被切开 |
| `dict.addWord(w, 100000)` | `CONNECT` | 强制连写（孩子们/艺术家/分数/复合单位） |
| `dict.addWord(w, 100000)` | `NAMESET` | 专名保护（人名/音译/机构） |
| `dict.addWord(w, 100000)` | `SPLIT4_INNER` | 分写后需要的内部双字词 |

**`segment()` 主流程**
```
① 《书名号》整体保护    BOOK_RE = 「《[^》]*》」→ 《 + 书名(整体) + 》
② addPlainTokens
   ├─ NUM_PAT   数字整体（-?\d{1,3}(,\d{3})+(\.\d+)?%? | -?\d+(\.\d+)?%?）
   ├─ LETTER_RE 连续 ASCII 字母整体（BrailleBack / HID 不逐字母切）
   └─ addJiebaTokens
        ├─ 阳光强制分写词库命中 → 按词库切分
        ├─ 后附成分合并（SUFFIX：们子儿头性者员家手化度法学论品）
        ├─ 虚词分写（4字词，FUNC 在第2位→1+1+2；第3位→2+1+1）
        └─ 四字词 2+2 语节分写（前2后2各自成词时）
③ mergeChemFormulas   化学式保护（H2O / CO2 / H2SO4，118 元素表校验）
④ 拼装带空格文本 + 双向位置映射
```

**规则常量一览**

| 常量 | 内容 | 作用 |
|:---|:---|:---|
| `SPLIT4_ARR` | 风声鹤唳/草木皆兵/中华人民共和国/无缝钢管/劳动模范/… | 应从词典删除（强制分写） |
| `CONNECT_ARR` | 孩子们/艺术家/… + 分数连写(二分之一…六分之五) + 复合单位(米每秒/千米每小时/…) | 强制连写 |
| `SPLIT4_INNER_ARR` | 风声/鹤唳/草木/皆兵/中华/人民/共和国/… | 分写后的内部词 |
| `NAMES_ARR` | 塞维利亚/德沃夏克/霍金/马克思/习近平/… 约 50 条 + 中国共产党/人民代表大会/无界点显器(HID) | 专名保护 |
| `SUFFIX_STR` | `们子儿头性者员家手化度法学论品` | 后附成分（合并到前词） |
| `FUNC_STR` | `之而所如即若亦俱尚犹且及焉哉乎耳` | 强虚词（触发 4 字分写） |
| `ELEMENTS_ARR` | 118 个化学元素（H…Og） | 化学式校验 |
| `PUNCT_STR` | `，。、；：？！""''（）《》〈〉—…··,.;:!?()[]{} ` | 标点 |
| `NUM_PAT` / `BOOK_RE` / `LETTER_RE` | 正则 | 数字 / 书名号 / ASCII 字母 |

**`loadSplitDict()`** — 读 `sunshine_split.txt`，每行 `词\t分1 分2 分3`，
命中该词的 jieba 结果时直接按词库切分（对标阳光读屏 1,439 条决策）。加载失败不阻塞主流程。

---

### 3.5 `JiebaDict`（207 行）—— 自实现 jieba 分词器

**零第三方依赖**，算法等同 jieba（`HMM=false`）：词典词频 → DAG → 动态规划最大概率路径。

| 成员 | 说明 |
|:---|:---|
| `freqMap: HashMap<String, Int>` | 词 → 词频。**前缀也写入（值 0）**，供 DAG 前缀遍历 |
| `logTotal` | 全部词频之和取 log，用于概率计算 |
| `init(InputStream)` | 读 `word freq [pos]` 格式词典 |
| `addWord(word, freq)` | 加自定义词（连写词/专名用，freq 传 100000） |
| `delWord(word)` | 删词（让应分写的词被切开） |
| `isWord(word)` | 是否为完整词（freq > 0） |
| `buildDag(sentence)` | 每个位置 k → 可到达的结束位置列表 |
| `segmentWithPos(sentence)` | 分词并返回 `[word, start, end]`（**BrailleSegmenter 用这个**） |
| `segment(sentence)` | 只要词列表 |

评分公式：`score = log(freq) - logTotal + score[x+1]`，从后往前 DP。

> 注意：词典加载是 **O(n·len)** 建前缀表 + 全量读入内存（5 MB 词典 / 34.9 万词），
> 在移动端有启动耗时与内存开销，这也是为什么要放到后台线程初始化。

---

### 3.6 `LibLouisWrapper`（95 行）

JNI 封装，静态方法调用 native liblouis 2.6.0：
- `setTablesDir(String)` — 设置解出来的表目录
- `translate(text, tableName, cursorPos, computerBraille)` → `TranslationResult`
- `backTranslate(cells, tableName)` → `String`
- `checkTable(tableName)` → `boolean`

对应 native 库：`liblouiswrap.so`（arm64-v8a 177 KB）。

### 3.7 `TableList`（168 行）

解析 `res/xml/tablelist.xml`，维护「表 ID ↔ 文件名 ↔ 显示名」映射，供 `getTableInfos()` 和 `getFileName(tableId)` 使用。

---

## 四、`display` 包详解

### 4.1 三条硬件通路

| 通路 | 类 | 条件 | 说明 |
|:---|:---|:---|:---|
| **Braille HID** | `HidDisplayConnection` + `BrlttyHidNativeHelper` + `HidBridge` | Android 15+（API 35 `VANILLA_ICE_CREAM`） | 系统 `BrailleDisplayController`（usage page 0x41），驱动 brltty 的 `hid` 驱动 |
| **Bluetooth RFCOMM** | `DeviceFinder` + `DisplayService` | 传统 | 蓝牙串口 |
| **USB** | `UsbHelper` + `DeviceFinder` | USB-OTG | USB 设备 |

### 4.2 `HidBridge`（67 行）—— 关键胶水

`BrailleDisplayController` **只能从 `AccessibilityService` 获取**，
但驱动 HID 的 `DisplayService` 拿不到。由于二者**同进程**，用一个静态 holder 传递：

```java
HidBridge.setController(controller)              // BrailleBackService 侧调用
HidBridge.setControllerChangeListener(listener)   // DisplayService 注册监听
HidBridge.getController()
HidBridge.isAvailable()   // SDK_INT >= 35 && controller != null
```

> 系统掉线重连后可能发新的 controller，所以有 change listener 让 DisplayService 重新连接。
> 这正是上次提交 `b0460fd HID 输出修复与 Android 15/16 Braille HID 协议完善` 涉及的部分。

### 4.3 `DisplayService`（713 行）

点显器连接服务主类：设备发现 → 建立连接 → 启动 `DriverThread`（写）/ `ReadThread`（读）→
维护连接状态 → 通过 `IBrailleServiceCallback` 把状态与输入事件回传给 `:brailleback`。

### 4.4 `DeviceFinder`（653 行）

枚举并匹配已配对蓝牙设备 / USB 设备 / HID 设备，供用户选择或自动连接。
含 `BluetoothDeviceEntry` / `UsbDeviceEntry`（POJO，在 `:client` 模块定义）。

---

## 五、扩展点（下一步开发）

| 目标 | 改动位置 |
|:---|:---|
| 补充中文分词规则/新词 | `BrailleSegmenter.kt` 的 `CONNECT_ARR` / `SPLIT4_ARR` / `NAMES_ARR` / `FUNC_STR` / `SUFFIX_STR` |
| 扩充阳光分写词库 | `service/assets/sunshine_split.txt`（不改代码） |
| 加/换盲文表 | 放进 `translationtables.zip` + 更新 `TableList` 的 `tablelist.xml` |
| 电子盲文文档支持 | 完善 `translateWithBraillePassThrough`（当前未提交/未测试） |
| 新硬件通路 | `DeviceFinder` 加发现逻辑 + `DisplayService` 加连接分支 |
| HID 协议问题 | `HidDisplayConnection` / `BrlttyHidNativeHelper` / native `hid_android.c` |
| 翻译性能 | `JiebaDict` 词典加载策略（懒加载 / 裁剪 / 预编译二进制） |
