# 142-移除点位 JSON 导出格式

## 一、结论（前置）

状态：完成。工作树 `D:\bb-wt\jsonexp`，分支 `chore/remove-json-export`，基于 main `39c62df`。未 push、未 rebase、未合 main，合并归守门人。

三行结论：

1. **删了什么**：移除阅读器导出链路里的点位 JSON 格式——枚举项、分发分支、手写序列化/解析模型、SAF launcher 注册、两条字符串及中英翻译；BRF / BRL / Text 三种格式与「点位↔原文」定位基础设施原样保留。
2. **数字**：删除文件 2 个（`ExportDocument.kt` 源码、`ExportDocumentTest.kt` 测试）；改动主源码 4 个、androidTest/单测 5 个、字符串目录 2 个；拆除失效形参 2 个（`cellSources`、`title`），调整生产调用点 1 个；删测试用例 8 条；删后 `brailleback` 1058 例（基线 1066，−8）、`service` 240 例（持平）；双模块 lint 0 error，UnusedResources 无新增孤儿字符串。
3. **Brailix 兼容性**：选 **A**——删除字段级兼容用例，与 Brailix `cells.py` 的点位 JSON 字段级兼容已放弃；BRF/NABCC 字节级兼容仍由 `BrailixCompatibilityTest` 四条用例守住。下一步归守门人合并；若未来出现能消费点位结构文件的刻印软件或脚本链路，再由 ANDROID 配合 ENGINE 评估恢复结构化导出。

## 二、为什么删

- 该 JSON 把每格点位、行列、角色、`source_span`/`source_text` 与文档元信息写成一份纯数据文件。现存点显器（HID/SPP 上行显示盲文行）和刻印机（走 BRF/NABCC ASCII）都不能直接打开它；字符串原文也注明 “No display or embosser can open it”。
- 没有真实消费者：导出清单里这一项对读屏用户是一条死路，留在弹窗只增加朗读负担与维护面。
- 手写 JSON 写入器加递归下降解析器是整份死代码；保留它只为一条字段名断言不划算。

## 三、删了什么（逐条）

### 源码

- `BrailleExporter.kt`
  - 枚举项 `JSON("json", "application/json")` 删除。
  - `when` 分发分支 `BrailleExportFormat.JSON -> exportJson(...)` 删除。
  - `exportJson` 函数及其 KDoc 删除。
  - `export(...)` 形参 `cellSources: List<CellSource>?` 删除（仅 JSON 分支消费）；顺带拆掉同样只剩 JSON 使用的 `title: String` 形参——删后没有任何分支消费它，不留空转参数。
- `ExportDocument.kt` 整文件删除：纯 JVM 模型 `ExportDocument / ExportCell / ExportJson` 手写写入器 + 递归下降解析器，全部只服务 JSON 通路。
- `ReaderExport.kt`
  - 标题映射、说明映射两条 `BrailleExportFormat.JSON` 分支删除。
  - `jsonExportLauncher` 分支删除。
  - 导出调用去掉 `mCellSpans` 实参（`mCellSpans` 字段本身保留，仍供目录、翻译对齐与屏幕取字使用）。
- `BrailleReaderActivity.kt`：`jsonExportLauncher` 的 `registerForActivityResult` 注册删除；`mCellSpans` 注释去掉 JSON 提法。
- `res/values/strings.xml`、`res/values-zh-rCN/strings.xml`：`braille_reader_export_fmt_json`、`braille_reader_export_fmt_json_desc` 两键删除；其余 `values-*` 目录本就没有这两条键，无孤儿翻译。

### 测试

- `ExportDocumentTest.kt` 整文件删除（6 例，全为 JSON 模型、往返、字段名、字符计数断言）。
- `SourceSpanMapperTest.kt`：删 2 例 JSON 往返与空源区间断言；保留 3 例 mapper 本身契约（位置有效逐字映射、无效位置回挂段落、段间不跨段）。
- `ExportFormatCatalogTest.kt`：目录项数断言 4→3；删 JSON 条目断言，其余语义不变。
- `ExportFormatDialogTest.kt`（androidTest）：弹窗标签/说明去掉 JSON 行；点按断言由第 4 项（索引 3）改为第 3 项（索引 2），序号语义同步。
- `ExportGeometryDialogTest.kt`（androidTest）：断言未改；仅补齐 main HEAD 上既有的 API 漂移（见第六节口径说明）。
- `BrailixCompatibilityTest.kt`：四条 BRF/NABCC 字节级用例全部保留；头注去掉 `cells.py` 字段对齐提法，注明字段级兼容已随 JSON 移除。

## 四、保留了什么（已 grep 复核引用面）

- 定位基础设施原样保留：`SourceSpan.kt`、`ReaderSourceModel.kt`、`CellSource`、`SourceSpanMapper`。复核结果：`ReaderFileLoader`、`ReaderTocBookmarks`、`ReaderTranslation`、`BrailleReaderActivity`、`ReaderPageNavigator` 用它做目录定位、翻译对齐、屏幕取字，不被 JSON 独占。本轮只删 JSON 这一条输出通路，定位表本身一行未动。
- BRF（NABCC ASCII + CRLF + FF 分页）、BRL（Unicode 盲文）、Text（Unicode 纯文本）三种格式：编码器、折行、避头尾点口径、版面几何弹窗全部未改。
- 未触碰：brltty 驱动、HID/SPP、`keymap/`、`ime/`、`DisplayManager` 内部逻辑、`service/` 模块整体；设置页字符串未改。

## 五、Brailix 兼容性判断题（选 A）

原题：存在对着点位 JSON 与 Brailix `cells.py` 字段的兼容断言；格式删除后这条字段级兼容失去对象，二选一。

- **结论：A——删除用例，并写明「与 Brailix 的字段级兼容已放弃」。**
- 理由：
  1. JSON 通路无消费者。选项 B（保留一个不对外暴露的内部序列化仅供测试）等于把死格式的写读解析器留在构建里只为一条字段名断言，增加维护面；该序列化不进任何对外通路，没有真实回归价值。
  2. 与 Brailix 的兼容分两层：BRF/NABCC 字节级（64 掩码索引表、CRLF、满 25 行插 FF、末页不挂尾 FF、掩码往返）仍由 `BrailixCompatibilityTest` 四条用例守住，未放弃；放弃的只是 JSON 字段命名对齐这一层。
  3. 若未来出现能消费点位结构文件的刻印软件或脚本链路，再由 ANDROID 配合 ENGINE 评估恢复结构化导出，并重新对齐字段口径。

## 六、门禁证据

证据根目录：`D:\bb-wt\jsonexp\verify\`。

1. **构建**：`:brailleback:assembleDebug --rerun-tasks` BUILD SUCCESSFUL。日志 `gate1-assemble.log`。
2. **单测**：`:brailleback:testDebugUnitTest` 1058 例、失败 0、错误 0；`:service:testDebugUnitTest` 240 例、失败 0、错误 0。日志 `gate2-brailleback-test.log`、`gate2-service-test.log`；结果 XML 在各模块 `build/test-results/testDebugUnitTest/`。
3. **lint**：`:brailleback:lintDebug :service:lintDebug` 0 error；UnusedResources 清单中无 `braille_reader_export_fmt_json*` 孤儿键（其余告警为基线存量）。日志 `gate3-lint.log`。
4. **AVD 冒烟**（`brailleback_api35`，emulator-5554）：安装本轮 debug 包后跑仪器测试 `ExportFormatDialogTest` OK（2 例）——弹窗三项标签与三条说明同屏渲染、顶部建议在；点按第 1 项报索引 0、点按第 3 项报索引 2。`ExportGeometryDialogTest` 回归 OK（2 例）。日志 `gate4-format-dialog-instrument.txt`、`gate4-geometry-dialog-instrument.txt`；阅读器界面树 `gate4-reader-tree.xml`。
   - 口径说明：shell 直接以 `file://` 拉起阅读器受存储权限拦截，未在设备界面走完 SAF 建文件流程；保留三种格式的实际写盘路径由 JVM 导出器单测覆盖。
   - 另：androidTest 两处既有 API 漂移（`ReaderScrollState`、`ReaderSegment`、`ReaderLabels` 新增字段）在 main HEAD 上已导致测试集编译失败；本轮只做最小调用点补齐，未改其断言口径。

## 七、回滚

- 恢复方法：对本轮单独提交执行 `git revert 3f881ca`（完整 SHA `3f881cacb519de9e3f1b54a015620d443ba8273d`），即可把枚举、模型、launcher、字符串与测试整体还原；在主仓库工作树或对应 worktree 执行均可。
- 本轮未合 main、未 push、未 rebase、未改写历史；`verify/` 证据目录不进 git。

## 八、逐条测试删除理由

| 用例 | 所属文件 | 删除理由 |
|---|---|---|
| `documentCarriesSchemaTitleGeometryAndPageCount` | ExportDocumentTest | 只断言 JSON 文档元信息结构，模型已删 |
| `cellFieldsDotsMaskRoleAndOneBasedCoordinates` | ExportDocumentTest | 只断言 JSON 格字段，模型已删 |
| `coordinatesWrapByGeometryColumns` | ExportDocumentTest | 只走 JSON 模型的行列包装，模型已删 |
| `jsonRoundTripPreservesAllFields` | ExportDocumentTest | JSON 序列化/解析往返，解析器已删 |
| `jsonContainsBrailixAlignedFieldNames` | ExportDocumentTest | 与 `cells.py` 字段名对齐，字段级兼容已放弃（选 A） |
| `jsonExportCountsPrintCharactersDropped` | ExportDocumentTest | JSON 导出字符计数通路，函数已删 |
| `jsonRoundTripCarriesSourceSpanAndText` | SourceSpanMapperTest | 经 JSON 模型往返校验 span，模型已删；mapper 本身契约由保留用例覆盖 |
| `missingCellSourcesStayNull` | SourceSpanMapperTest | 经 JSON 模型断言空源区间，模型已删；无独立留存对象 |
