# 31 · Word 文档支持

> 2026-11-15。阅读器新增 `.docx` / `.docm`：从 OOXML 容器取正文，
> 交给现有「明眼文字 → 盲文」翻译链路。旧版 `.doc` 给出明确提示。

## 1. 为什么只做 .docx

| 格式 | 结构 | 处理 |
|:---|:---|:---|
| `.docx` / `.docm` | zip（OOXML），正文在 `word/document.xml` | **支持**，取纯文字 |
| `.doc`（97-2003） | OLE 复合文档，二进制流 | 不支持；提示「另存为 .docx」 |

`.doc` 不是 zip，硬解只会得到乱码；把「先另存为 .docx」直接告诉用户，
比一句「不支持」更可行动。

## 2. 提取方式

新增在 `EbookTextExtractor` 里（同一个容器分派，不另起一套）：

1. **先认 zip**：DOCX 头两字节是 `PK`；不是 zip 直接判 `NotWord`
   （挡住改了后缀的旧 `.doc` 与损坏文件）。
2. **只解 `word/document.xml`**：`readNamedEntry` 流式扫描 zip，不把
   文档里的图片读进内存；单条目上限 16 MB。
3. **正文标记**：
   - `<w:t>` 里的文字保留并解码 XML 实体（`&amp;` `&lt;` `&gt;` `&quot;`
     与 `&#xNN;`）；
   - `</w:p>` 断段，`<w:br/>` 断行，`<w:tab/>` 变空格；
   - 只认 `<w:t>`，因此 `w:delText`（修订删除）与 `w:instrText`（域代码）
     不会读出来；
   - 行尾空白清理，连续 3 个以上换行压成 2 个。
4. **编码**：复用 `BrailleFileDecoder.decode()`（BOM → 严格 UTF-8 → GBK 回退）。

## 3. 阅读器接线

- `supports()` 后缀集合加 `docx` / `docm`；
- `loadBytes()` 与 EPUB/HTML/MD 同一分支：提取 → `detectKind()` →
  翻译；
- `UNSUPPORTED_EXT` 移除 `docx`，保留 `doc`；`checkSupportedFormat()`
  对 `doc` 单独抛「旧版 .doc 请另存为 .docx」；
- 书架类型标签新增「Word 文档」。

## 4. 用户听到的失败提示

| 原因 | 文案 |
|:---|:---|
| `NotWord` | 不是有效的 Word 文档（缺少 word/document.xml） |
| `NoText` | 文件里没有可读的文字（可能只有图片） |
| `TooLarge` | 文件体积过大，已停止解压（上限 128 MB） |
| 旧版 `.doc` | 旧版 .doc 暂不支持，请用 Word 另存为 .docx 后再打开 |

`NoText` / `TooLarge` 两条文案已从「电子书」改成「文件」，EPUB 与 Word 共用。

## 5. 验证

- `EbookTextExtractorTest` 增至 **9 例**，新增 3 例：
  DOCX 段落/多 run 拼接、tab/br、实体解码；缺 `word/document.xml`
  与「不是 zip」判 `NotWord`；只有图片判 `NoText`；
  后缀白名单含 `docx`/`docm`、不含 `doc`；
- 门禁：`detektCheck` + 主模块单测 + service 单测 + lint 全绿；
- 测试基线：主模块 **52 套件 / 534 用例**、service **25 套件 / 172 用例**；
- 真机（Redmi K80，Android 17）：安装成功；无障碍服务五轮冒烟
  `Crashed services:{}`，`brailleback` 与 `brailleback:service` 双进程存活，
  `logcat -b crash` 无记录；冒烟后无障碍清单已恢复原值。

## 6. 边界

- 页眉、页脚、脚注、尾注、批注、文本框以外的绘图对象、图片里的文字都不提取；
- 表格只按单元格里的段落断行，不还原列结构；
- 样式、加粗、字号、缩进、分页符不进入盲文输出；
- 加密/密码保护的 DOCX 是 OLE 容器而非 zip，会报 `NotWord`；
- 单个 `document.xml` 超过 16 MB 会报 `TooLarge`（纯文字长篇通常远小于此）；
- `.rtf` / `.odt` / `.wps` 仍不支持。

## 7. 改了哪些文件

- `EbookTextExtractor.kt`：`extractWord`、`readNamedEntry`、`wordXmlToText`、
  失败类型 `NotWord`、后缀集合；
- `BrailleReaderActivity.kt`：失败提示分支、`doc` 专用提示、
  `UNSUPPORTED_EXT` 调整；
- `BookshelfActivity.kt`：`typeLabel` 增 `docx`/`docm`；
- `brailleback/res/values{,-zh-rCN}/strings.xml`：Word 失败文案、旧版 doc 提示、
  书架类型、总的不支持格式提示补全支持列表；
- `EbookTextExtractorTest.kt`：新增 3 例。
