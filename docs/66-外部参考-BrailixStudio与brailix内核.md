# 66 · 外部参考：Brailix Studio 与 brailix 内核

> 更新时间：2026-11-24
> 起因：核对电脑端已装的盲文翻译软件，判断哪些能力可以直接借、哪些只能抄设计。
> 关联：[65-产品团队与下一步功能脑暴](./65-产品团队与下一步功能脑暴.md)（N1 体检单 / N3 预检 / N5 校对 / N10 触觉图形）

---

## 1 装的是什么

| 项 | 实际值 |
|:---|:---|
| 软件名 | **Brailix Studio** 0.1.35（win64） |
| 形态 | Python 3.13 + PySide6（Qt6）桌面程序，`.pyd` 打包，`BrailixStudio.exe` 39 MB |
| 安装位置 | `D:\软件\brailleback盲文\.tmp-brailix-analysis\extracted\BrailixStudio\` |
| 快捷方式 | 开始菜单「Brailix Studio 盲文」，**直接指向上面 `.tmp` 里的 exe** |
| 自带文档 | `BrailixStudio 使用说明.txt`（62 KB，最详尽）、`更新日志.txt`（281 KB） |
| 开源内核 | `.tmp-brailix-analysis\brailix-repo`（**Apache-2.0**，`pyproject.toml` + `tests/` + 5 篇 docs + `ARCHITECTURE.md`） |
| 附带依赖 | `jieba`、`pypinyin`、`g2pM`、`hanlp` 相关模型、`latex2mathml`、`lxml`、`PIL`、`numpy`、`resvg_py` |

⚠️ **隐患**：软件本体装在项目仓库的临时分析目录里，快捷方式指过去。`.tmp-*` 在 `.gitignore` 里，且这类目录随时可能被清理——**清一次 `.tmp` 就等于把已装软件打断**。要么把 `extracted\BrailixStudio` 整个挪到 `D:\软件\BrailixStudio` 再改快捷方式，要么把 `BrailixStudio-0.1.35-win64.zip` 单独留档。

另一套在用的：**彗光盲文软件 1.7.11**（`C:\Program Files (x86)\CometLight\EmbosserService`，含 `CLBrailleEditor.exe` 编辑工具与 `print_service.exe` 刻印服务）——这才是能真正驱动刻印机的那一端。

---

## 2 它到底能做什么（按我们关心程度排序）

### 2.1 触觉图形（最值钱，正好对上我们的 N10）

- 入口四选一：**直接写/粘 SVG**、**生成图表**（柱状/折线/数轴/坐标轴/表格，填「每行一条」的表单）、**引一张图片**（PNG/JPG/BMP）、**图元规格**。
- 图片转点阵的可调参数：**模式**（明暗二值 / 灰度 / 边缘）、**明暗分界**（threshold）、**反转凸起**（invert）、**宽高（毫米）**。默认「深色处做成凸起」，适合线条图与带字的图；灰度模式保留层次，供高度调制打印机。
- 极性约定与刻印机一致：**凸起 = 深色**（内部存 raise level 0–255，输出时取 `255 - level`）。
- 导出：触觉图 **BMP（8 位灰度，母版）/ BMP（单色）/ PNG（明眼参考）/ PDF（明眼参考）**；文档级「**导出触觉版面页**」把正文描成真盲文点 + 图排在版面上，出 BMP/PNG/PDF，多页合成一个 PDF。
- 纸张：A4（generic）与美式 Letter，几何一律按毫米、与设备无关。
- 诊断窗格会提醒触觉可读性问题：**两个要素离太近可能摸不出是分开的**、**盲文标签压在图形上点读不出来**。

**这套参数名与语义可以直接照搬**：`path / mode / threshold / invert / width_mm / height_mm`。

### 2.2 翻译与校对

- 规范两套随时切：`cn_current`（现行盲文）、`cn_ncb`（国家通用盲文）；另有 `ja_current`。
- 输入：`.txt/.md/.blx`、`.docx/.docm/.doc`、MusicXML（`.musicxml/.xml/.mxl`）、MIDI、ABC。
- 内容：正文、数学公式（LaTeX）、化学式 `\ce{}`、英语音标 `/kæt/`、乐谱。
- 输出：`unicode`（Unicode 盲文）、`brf`（NABCC 字节）、`cells`（JSON，含点位/角色/**源区间**）、`layout`（排好版）。
- **`proofread_json()`**：每个盲方格带**源文本区间**，供下游高亮、回跳、批量纠正——与我们 `e49bd4e`「per-cell source_span」同构，可以直接对拍。
- 校对闭环：IR 树里双击改拼音、右键「在此切分 / 合并为一词」、诊断窗格「认领/评论/提交/通过」状态机、改写历史可一键恢复。

### 2.3 明确的空白（和我们一样）

- **不直接驱动物理打印机**：只能出 BRF / BMP / PDF，再交给印厂或刻印软件。
- 图片转触觉图「照片这类复杂图需要自己核对，不保证一键完美」。
- 复杂 SVG（渐变/滤镜/裁剪）走「图片设置」会被**栅格化**，图里的文字按图形画、不转盲文；想文字转盲文必须存「文字不转曲线」的 SVG 并贴进源文。

---

## 3 和我们项目的对位

| 我们的编号 | 对应它的能力 | 结论 |
|:---|:---|:---|
| **N10 图片→触觉图形** | `translate_graphic(svg, tactile_profile="generic")` → `bmp/png/pdf/tactile_preview`；`brailix[graphics]` 读位图 | **不自己写栅格化**：要么 PC 侧调它出 BMP，要么把参数与极性照抄进 Kotlin |
| **N3 打印预检** | 「导出触觉版面页」+ 诊断里的间距/压字告警 | 它的告警口径可以当我们预检规则的现成清单 |
| **N1 转译体检单** | 诊断窗格（未覆盖字符、低置信读音、未转的图片） | 校验项基本一致，严重度分级可复用 |
| **N5 逐行对照校对** | `proofread_json()` 的「格↔源区间」 | 我们已有 cell→source 映射，缺的是界面 |
| **N2 真值对语料** | `tests/` 目录 + 三种 run mode（strict/normal/lenient） | `--mode strict` 把首条警告变错误、非零退出——**正好是我们 CI 门禁想要的行为** |
| **N8 版面预设** | `tactile_profile` 命名页面几何（尺寸/分辨率/最小可抬升特征） | 「按 profile 名切纸」的抽象值得抄 |

**关键判断**：它的「翻译层 / IR / 渲染层」三段分离，和 [65 号文档 §2](./65-产品团队与下一步功能脑暴.md) 里 BrailleBlaster 的结论一致。我们现在的 `PageGeometry` + 导出器已经在同一条路上，不必重构。

---

## 4 今天就能跑通的一条链

彗光编辑工具**本身就支持图片转点图**（更新日志原文：「图片和转换的点图增加占用行列数估计，插入的点图水平居中」、「增加图像转点图点间距设置」），并能选 Index 刻印机的纸张类型、自定义纸张尺寸。

于是整条链**没有空白**：

```
手机里的一张图 / 一段文字
   ↓（PC 侧，可选）
Brailix：图片 → 触觉图 BMP（8 位灰度母版）
   ↓
彗光 CLBrailleEditor：打开图片 → 转点图（点间距可调）→ 设定纸张
   ↓
print_service.exe → 刻印机出纸
```

**这说明 N10 的 MVP 可以只做「出 BMP」，把「栅格化到点」交给彗光**——比自己在安卓里实现一遍阈值、重采样、点距映射省掉大半工作量。

---

## 5 三档复用方案（按成本从低到高）

| 档 | 做法 | 成本 | 代价 |
|:---|:---|:---|:---|
| **A 只借参数** | 把 `mode / threshold / invert / width_mm / height_mm` 与「凸起=深色」写进我们 Kotlin 的 `tactile/`，自己实现二值化与重采样 | 小 | 边缘/灰度算法要自己调，效果未必追平 |
| **B PC 侧借内核** | `pip install brailix[graphics]`，脚本把图转 BMP，再喂彗光 | 很小 | 只能在 PC 上做，手机端出不了图 |
| **C 打包进 App** | 把 Python 内核塞进安卓 | **不可行** | CPython + PySide6 打包进 APK 不现实，且许可证要在 App 里保留 NOTICE |

**建议**：先走 B 验证「摸得懂」这条唯一重要的指标，再按 A 把参数落进 Kotlin。C 不做。

### 许可证边界（Apache-2.0）

- 可以改、可以商用、可以闭源分发衍生作品；
- 必须保留 `LICENSE` 与 `NOTICE`，标明改动；
- **不能**使用它的商标名暗示背书。
- 若只「照抄参数语义、自己写实现」，不构成衍生作品，不受这些约束。

---

## 6 待办（进 backlog，不动主链）

1. 把 `extracted\BrailixStudio` 迁出 `.tmp`，修快捷方式（防一次性丢失）。
2. 用 `brailix[graphics]` 跑 3 张样图（线条图 / 带字示意图 / 照片轮廓），记录 BMP 母版与彗光转点图的实测效果与点间距取值。
3. 把彗光「图像转点图」的点间距档位抄成我们的 `TactileProfile` 预设表。
4. 用 `--mode strict` 的退出码约定，给我们的导出体检设计 CI 门禁（对应 N2/N4）。
