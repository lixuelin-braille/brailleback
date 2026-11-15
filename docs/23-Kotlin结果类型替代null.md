# Kotlin 结果类型替代 null

## 为什么做这一层

`!!` 清零解决的是「显式说我在这儿冒险」。还有一层更隐蔽：**用 null 表示失败**。

`fun parse(bytes): String?` 的 null 至少可能是三件事——文件空、不是这个格式、内容不合格。
调用方拿到 null 只能给一句话：「不是有效的文档」。对盲人用户尤其致命：他看不到界面，
就靠这一句话判断问题出在哪，而这句话什么都没说。

null 不是「没有值」，是「值被扔掉了」。

## 这一层有多大

全仓 `): X?` 形式的返回 168 处。不能一轮全改，也不该乱改：

- 有些 null 是**正常的缺席**：`getActiveInstance()` 服务没跑、`getCurrentNode()` 没有焦点、
  `getTranslator()` 没选表。这类保持 null 是对的，改成 Result 只是把简单事写复杂。
- 真正该改的是**同一个返回值承担多种失败语义**的：解析器、协议握手、Provider 查询。

本批先动最容易验证的一处：`.bdo/.bdoc` 解码链。它是国内盲文图书馆/出版社的主力格式，
出错频率最高，原因也最值得说清。

## 改动

### 1. `BdoCArchiveParser.parse`

```kotlin
// 前：null 的含义是「文件空 / 没记录 / 盲文太少」三选一，调用方无从分辨
fun parse(bytes: ByteArray): String?

// 后
sealed interface ParseResult {
    data class Decoded(val text: String, val cellCount: Int) : ParseResult
    data object EmptyInput : ParseResult
    data object NoRecords : ParseResult
    data class TooFewCells(val cellCount: Int) : ParseResult
}
fun parse(bytes: ByteArray): ParseResult
```

顺带把 `cellCount` 带出来：原来这个数算完就扔了，只在阈值判断里用一次，
现在它是提示语的一部分（「只有 50 方盲文」）。

### 2. `BrailleFileDecoder.decodeDocument`

```kotlin
sealed interface DecodedDocument {
    data class Decoded(val text: String) : DecodedDocument

    sealed interface Failure : DecodedDocument {
        data object EmptyFile : Failure
        data object NotBwordArchive : Failure
        data class TooFewBrailleCells(val cellCount: Int) : Failure
    }
}
fun decodeDocument(bytes: ByteArray, fileName: String?): DecodedDocument
```

**为什么三种失败要收进 `Failure` 子接口**：调用方只关心两件事——成功、或「为什么失败」。
`Failure` 自成封闭集之后，`fun loadFailureMessage(failure: Failure)` 的 `when`
三支穷尽，不需要 `else` 兜底；以后多加一种失败，编译器会在所有处理点直接报错，
而不是悄悄落进 else 被吞掉。

这是 sealed 最实际的收益：**漏处理变成编译错误，而不是运行时的又一次「不是有效的文档」**。

### 3. 阅读器把原因念出来

`BrailleReaderActivity.loadBytes` 原来：

```kotlin
val text = BrailleFileDecoder.decodeDocument(bytes, name)
    ?: throw IllegalStateException(getString(R.string.braille_reader_bdo_fail))
```

现在：

```kotlin
val text = when (val document = BrailleFileDecoder.decodeDocument(bytes, name)) {
    is BrailleFileDecoder.DecodedDocument.Decoded -> document.text
    is BrailleFileDecoder.DecodedDocument.Failure ->
        throw IllegalStateException(loadFailureMessage(document))
}
```

新增三条提示（`values` 与 `values-zh-rCN` 各一份）：

| 原因 | 中文提示 |
|:--|:--|
| `EmptyFile` | 这个文件是空的 |
| `NotBwordArchive` | 这个 .bdo/.bdoc 文件里找不到 Bword 记录 |
| `TooFewBrailleCells` | 这个 .bdo/.bdoc 文档只有 N 方盲文，内容太少，读不了 |

原来的 `braille_reader_bdo_fail` 已无代码引用（新的三条把它取代了），一并删除。
不留无引用的文案：留着会让人以为还有第四条失败路径。

## 验证

| 项 | 结果 |
|:--|:--|
| 编译 | 通过 |
| 主模块单测 | 50 套件 / 520 用例 全绿 |
| service 单测 | 23 套件 / 164 用例 全绿 |
| detekt（baseline 外） | 0 条新问题 |
| lint | error = 0 |
| 真机冒烟 | 连续五轮 `Crashed services:{}`，主进程与 `:service` 都在，无 `FATAL EXCEPTION` |
| 无障碍清单 | 冒烟后已恢复原三项 |

测试断言也换了口径：原来 3 个 `assertNull` 改成断言**具体子类型**
（`assertEquals(ParseResult.EmptyInput, ...)`、`TooFewCells(50)`），
`decodeDocument` 的分派测试用数据类相等比较（`assertEquals(Decoded(...), ...)`）。
以后这几种失败谁被改错，测试直接指出是哪一个，而不是「反正都是 null」。

## 顺带查清的 debug APK 体积

本轮三次打包，同一个仓库出了三个体积：39.49 MB（增量）、44.04 MB（增量）、28.95 MB（`--rerun-tasks` 全量）。

翻了包内结构：

- 28.95 MB 的包：13 个 dex，`BrailleBackService`、Compose、Kotlin 符号全在；
  `classes.dex` 16.19 MB + `classes11.dex` 9.72 MB（3066 个 `Landroidx/compose/` 符号）+ `classes12.dex` 3.67 MB
- 44.04 MB 的包：dex 条目与 28.95 的**一模一样**，只是 zip 里多出约 15 MB 无法归属的空洞
- 资源侧固定开销：`resources.arsc` 4.56 MB、`assets/jiebadt.txt` 4.84 MB、
  `res/raw/translationtables.zip` 2.15 MB、native 库 4.78 MB

结论：**28.95 MB 才是真实体积**。其余是 AGP 增量打包写出的畸形包——
条目齐全、能装能跑，但 zip 尾部残留旧数据，文件虚胖。装机冒烟用的是 28.95 的包，五轮零崩溃。

这同时解释了挂账很久的「debug 33.34 → 39.49 有 6 MB 差异」：对比的都是不同时刻的增量产物，
本来就没有可比性。

规矩：**量体积一律 `clean` 或 `assembleDebug --rerun-tasks`**。
`docs/README.md` 快速命令里早写着 `--rerun-tasks`，本轮才明白它不只是「为了出包」，
还是「为了出对的包」。
## 下一批候选

按「null 吞掉了什么」的价值排序：

1. `LabelProviderClient`（`insertLabel` / `getAllLabels` / `getPackageSummary`）：现在 null 同时表示
   「provider 没连上」「查询抛异常」「结果为空」三种，而这三者对用户是三种不同的事
   （前者要重启服务，中者要看权限，后者要重新添加标签）。
2. `TranslatorClient`（`getTables` / `getTranslator`）：服务未绑定、初始化未完成、表不存在混在一起。
   翻译失败现在是静默的，用户只知道「没反应」。
3. `TranslatorManager.getTranslator()`：没有可用表 vs 表加载失败。前者是可配置状态，后者是缺陷。

不动的：`getActiveInstance()`、`getCurrentNode()`、`getLabelManager()` 这类「对象本来就可能不存在」的
查询，null 是准确的表达，不收进 Result。