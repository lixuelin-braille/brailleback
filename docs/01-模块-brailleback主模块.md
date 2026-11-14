# 模块文档 · `:brailleback`（主应用）

> 目录：`brailleback/`
> 规模：Java 19 + Kotlin 27 = **46 个源文件，约 10,500 行**
> 职责：无障碍读屏主服务、导航、节点盲文化、排版折行、音效、UI、盲文输入法

---

## 一、模块定位

`:brailleback` 是**唯一产出 APK 的模块**（`com.android.application`）。
它是一个 `AccessibilityService`，把 Android 界面内容翻译成盲文送到点显器，
并把点显器输入解释成导航操作。

与 `:service` **同进程**（service 以 AAR 形式打进来），通过 AIDL 接口调用。

---

## 二、文件清单（按行数降序）

| 行数 | 文件 | 职责 |
|---:|:---|:---|
| 1398 | `DisplayManager.java` | **显示管理器**：内容→翻译→折行→输出的调度中枢 |
| 891 | `DefaultNavigationMode.java` | 默认导航模式（焦点/滚动/pan） |
| 838 | `BrailleIME.java` | 盲文输入法（点显器 6 键输入中文/英文） |
| 681 | `BrailleBackService.java` | **主服务入口**（AccessibilityService） |
| 549 | `SearchNavigationMode.java` | 搜索导航模式 |
| 502 | `wrapping/WrapStrategy.kt` | **折行基类：避头点/避尾点**国标排版 |
| 500 | `IMENavigationMode.java` | 输入法导航模式 |
| 480 | `OverlayDisplay.java` | 屏幕叠加显示（模拟点显器） |
| 479 | `BrailleBackPreferencesActivity.kt` | 设置主界面 |
| 451 | `TreeDebugNavigationMode.java` | 节点树调试模式 |
| 396 | `BrailleMenuNavigationMode.java` | 菜单导航模式 |
| 338 | `BrailleView.java` | 模拟点显器视图（软件点显） |
| 300 | `TranslatorManager.kt` | 翻译服务客户端管理（绑定/表管理） |
| 293 | `SelfBrailleService.java` | 自建盲文内容服务（外部 App 写点显器） |
| 242 | `IMESetupWizardActivity` | 输入法设置向导 |
| 241 | `DraggableOverlay.java` | 可拖拽浮层 |
| 229 | `NodeBrailler.kt` | **节点→盲文文本**（遍历+规则） |
| 198 | `SearchTutorialActivity` | 搜索教程 |
| 182 | `KeyBindingsActivity` | 按键绑定设置 |
| 181 | `FocusFinder.kt` | 焦点查找 |
| 175 | `DisplaySpans.kt` | 显示用文本 + Span（焦点/选中标记） |
| 154 | `sound/SoundSchemeManager.kt` | 音效方案管理 |
| 149 | `OverlayTutorialActivity` | 浮层教程 |
| 132 | `utils/BrailleKeyBindingUtils.kt` | 键位绑定工具 |
| 127 | `rule/DefaultBrailleRule.kt` | 默认节点盲文规则 |
| 120 | `FeedbackManager.kt` | 反馈管理（音效/震动） |
| 111 | `ModeSwitcher.kt` | 导航模式切换 |
| 110 | `SearchView.kt` | 搜索视图 |
| 105 | `sound/SoundEvent.kt` | 音效事件定义 |
| 84 | `FocusTracker.kt` | 焦点跟踪 |
| 81 | `SearchOverlay.java` | 搜索浮层 |
| 80 | `IMEHelper.kt` | 输入法辅助 |
| 64 | `utils/PreferenceUtils.kt` | 偏好工具 |
| 62 | `utils/AccessibilityEventUtils.kt` | 无障碍事件工具 |
| 55 | `utils/StringUtils.kt` | 字符串工具 |
| 52 | `SelfBrailleManager.kt` | SelfBraille 客户端封装 |
| 50 | `utils/LabelingUtils.kt` | 标签工具 |
| 49 | `WebViewDialog.java` | 内嵌网页对话框（帮助） |
| 48 | `rule/VerticalContainerBrailleRule.kt` | 纵向容器规则 |
| 37 | `NavigationMode.java` | **导航模式接口** |
| 33 | `rule/BrailleRuleRepository.kt` | 规则仓库（选规则） |
| 31 | `sound/SoundScheme.kt` | 音效方案数据类 |
| 28 | `wrapping/WordWrapStrategy.kt` | 按空 cell 折行 |
| 27 | `rule/BrailleRule.kt` | **盲文规则接口** |
| 21 | `ServiceUtil.kt` | 服务工具 |
| 17 | `wrapping/SimpleWrapStrategy.kt` | 按字符折行 |

---

## 三、核心类详解

### 3.1 `BrailleBackService`（681 行）—— 主服务

继承 `AccessibilityService`，是整个 App 的**中枢**。

**关键方法**
| 方法 | 说明 |
|:---|:---|
| `onServiceConnected()` | 服务连接，调 `initializeDependencies()` |
| `onAccessibilityEvent(event)` | 接收系统无障碍事件 → 分发给 FocusTracker / NavigationMode |
| `onMappedInputEvent(BrailleInputEvent)` | 点显器输入事件入口 |
| `handleGlobalCommands(event)` | 全局命令（帮助/返回/主页/最近任务…） |
| `onConnectionStateChanged(state)` | 点显器连接状态变化 |
| `onPanLeftOverflow/RightOverflow(Content)` | 平移到头的溢出处理 |
| `onKeyEvent` / `onVolumeKeyDown/Up` | 物理按键、音量键手势映射 |
| `startSearchMode()` / `onSearchStarted/Finished` | 搜索模式 |
| `initializeNavigationMode()` | 初始化导航模式栈 |
| `runHelp()` | 帮助 |
| `invalidateNode(node)` | 节点失效（刷新） |

**依赖初始化链**（`initializeDependencies()`）
```
initializeLabelManager()     自定义标签（libraries/utils labeling）
initializeDisplayManager()   DisplayManager + TranslatorManager
initializeNavigationMode()   各 NavigationMode
updateServiceInfo()          AccessibilityServiceInfo 配置
```
`onDestroy()` → `shutdownDependencies()`

**音量键手势**：`readVolumeAction(gesture)` 读偏好，支持自定义动作（无/上翻/下翻/帮助…）。

---

### 3.2 `DisplayManager`（1398 行）—— 显示调度中枢

模块内最大的类。负责把「内容」变成「点显器上的一行行盲文」。

**内部类 `Content`**（`DisplayManager.java` 内）
| 成员 | 说明 |
|:---|:---|
| `Spanned mText` | 待显示文本（含 Span） |
| `getPanStrategy()` | 平移策略 |
| `getContractionMode()` | 简写/全写模式（盲文等级） |
| `isSplitParagraphs()` | 是否分段 |
| `isEditable()` | 是否可编辑（影响光标等行为） |
| `recycle()` | 回收 |

**关键流程**
```
setContent(Content)  →  内部类 Handler（自有 Looper 线程，见 onLooperPrepared）
   ├─ handleSetContent()   markSelection / markFocus → 翻译 → 折行 → 输出
   ├─ handleRetranslate()  表变化/设置变化时重译
   ├─ handlePulse()        光标闪烁脉冲
   ├─ schedulePulse() / cancelPulse()
   └─ handleStop()
```
- 有独立 Looper 线程（避免阻塞主线程）
- `onInputEvent(BrailleInputEvent)` → `sendMappedEvent()` 回传给 `BrailleBackService`
- `onConnectionStateChanged(state)` / `onTablesChanged()`
- `panLeft()` / `panRight()` 平移
- `isSimulatedDisplay()` 是否为模拟点显器

---

### 3.3 `NavigationMode`（37 行）—— 导航模式接口

策略模式：不同场景下对点显器输入的解释方式不同。

| 实现 | 行数 | 场景 |
|:---|---:|:---|
| `DefaultNavigationMode` | 891 | 默认：焦点移动、滚动、pan、点击 |
| `IMENavigationMode` | 500 | 输入法激活时 |
| `SearchNavigationMode` | 549 | 搜索模式 |
| `BrailleMenuNavigationMode` | 396 | 盲文菜单 |
| `TreeDebugNavigationMode` | 451 | 节点树调试（开发用） |

`ModeSwitcher`（111 行）负责在模式间切换。

> **扩展点**：新增导航模式 = 实现 `NavigationMode` + 在 `BrailleBackService.initializeNavigationMode()` 注册 + 在 `ModeSwitcher` 加入切换逻辑。

---

### 3.4 节点盲文化：`NodeBrailler` + `BrailleRule`

```
AccessibilityNodeInfo 树
   → NodeBrailler.kt (229 行) 遍历
   → BrailleRuleRepository.find(node) 选规则
   → BrailleRule.format(result, context, node) 写入 Editable
   → DisplaySpans (175 行) 文本 + 焦点/选中 Span
   → DisplayManager.Content
```

**`BrailleRule` 接口**（27 行）
```kotlin
fun accept(context, node): Boolean                 // 是否匹配该节点
fun format(result: Editable, context, node)        // 格式化写入
fun includeChildren(node, context): Boolean        // 是否继续追加子节点
```

**现有规则**
| 类 | 行数 | 作用 |
|:---|---:|:---|
| `DefaultBrailleRule` | 127 | 默认：文本 + 状态（勾选/展开等） |
| `VerticalContainerBrailleRule` | 48 | 纵向容器（如列表） |

> **扩展点**：加新规则 = 实现 `BrailleRule` + 在 `BrailleRuleRepository.find()` 里注册（注意顺序，先匹配的优先）。

#### 无障碍长文本的段落排版（`wrapping/ParagraphIndent.kt`）

点显器上没有“行”的概念，整段文字是一串连续的格；现行盲文表还把换行
定义成 0 方，所以微信读书这类阅读器的段落会直接粘在一起。现在对
无障碍路径上“单个长文本 + 空行分段”的内容启用排版：

1. `NodeBrailler` 生成内容后调用 `ParagraphIndent.shouldFormat()`：
   长度 ≥ 80 字，且存在“两个及以上换行（中间可夹空格）”的空行。
2. 命中时 `Content.setParagraphIndent(true)`。
3. `DisplayManager.translateCurrentContent()` 在**翻译之后**调用
   `ParagraphIndent.apply()`，在每个段首插入 **2 方空白**
   （GB/T 15720-2008 7.2 正文抬头空两方），并同步平移
   `textToBraillePositions` / `brailleToTextPositions` 与光标。
4. `WrapStrategy` 把段首登记为强制换行点（位置在缩进之前），
   下一段从行首开始，不会接着上一段的余下格。

约束与取舍：

- **第一段不缩进**：分页阅读的页首可能是上一段的续行，缩进会伪造段首。
- **单个换行不算段落**：只把空行当段边界，避免把软换行的每个视觉行都缩进。
- 短节点、按钮/菜单、自带 .brf 阅读器与 `BrailleSpan` 原文盲文不受影响。
- 标题居中由 `HeadingRules` 的语义/文字规则处理；微信读书正文节点本身
  不提供 heading 语义，章节名需命中“第X章”等规则才会居中。

实测微信读书正文：1 个 `FrameLayout` 节点、`content-desc` 274 字、
4 段，段间为“空格 + `\n\n`”。旧逻辑只剩 1 方空白；新逻辑得到 3 个
段首，各空 2 方。

#### 公众号正文：每段一个节点（`wrapping/ParagraphBlock.kt`）

微信公众号文章的正文结构与微信读书不同（2026-11-14 实测，页面共 364 个
节点）：

- 每个段落是独立的 `android.view.View`（可聚焦、可点击），文字在
  `content-desc`；子 `TextView` 的 `text` 与父节点完全相同。
- 可见正文 26 个段块，段长 4–59 字（平均 30.8 字），节点里**没有换行符**。
- 标题是 `resource-id=activity-name` 的 `TextView`，作者 `js_author_name`，
  `publish_time` 是时间，均无 heading 语义。

因此新增 `ParagraphBlock`：

1. 判定“WebView 祖先 + 整屏宽（≥60%）+ 文字 ≥10 字 + 非标题”的节点为
   正文段块，按钮、图片、作者/时间等窄节点不参与。
2. `NodeBrailler` 命中时设置 `setParagraphIndent(true)` 与
   `setFirstParagraphIndent(true)`：整块内容就是一段，下标 0 也补两方。
3. 父节点 `content-desc` 与子节点文本相同时跳过子节点，避免同一段被拼
   两遍（实测 26 个段块全部是这种父子同文结构）。

限制：段落内含行内链接时会被拆成多个 `TextView`，只要外层容器整体满足
条件仍会缩进；标题居中由 `HeadingRules` 的 `activity-name` 规则处理。

#### 原生阅读页：知乎回答（每段一个 TextView）

知乎回答不在 WebView 里（2026-11-14 实测，页面共 110 个节点）：

- 正文是 3 个独立 `TextView`（165 / 34 / 221 字），宽度 990/1080，
  节点里没有换行符。
- 每段的结构是 `FrameLayout(可点击) → ViewGroup(id=holder_text) →
  TextView(可聚焦、不可点击、无 view id)`，外层是 `RecyclerView`。
- 问题标题同样整屏宽，但 `clickable=true`；作者简介 523/1080、时间栏
  302/1080，均低于宽度阈值。

`ParagraphBlock.shouldIndentNative()` 的判定：`TextView` 或
`android.view.View`（Compose/自定义控件的文本语义节点）+ 可聚焦 +
不可点击 + 无 view id + 宽度 ≥60% + ≥10 字，并且向上 4 层内存在一个
装着 ≥2 个阅读块的容器。单条长文本、没有同构邻居的页面不缩进，避免
误伤聊天页和设置页。

#### 天坦百宝箱：可点击段容器 + 重复 view id

`net.tatans.tools`（天坦百宝箱）的 `BookReadingActivity` 是另一种结构
（2026-11-14 只读快照，页面共 47 个节点）：

- 每段是 `FrameLayout`（可聚焦、**可点击**），正文在 `content-desc`；
  子节点 `TextView(id=text1)` 的文本与父节点完全相同。
- 15 段、3165 字，宽度 1024/1080，节点内无换行、无段首空格。
- 底部是“目录/读书笔记/进度/更多/听书”5 个按钮，均带 view id。

为此补了两条规则，避免放宽整个判定：

1. **可点击段容器**：`FrameLayout`/`LinearLayout`/`RelativeLayout`/
   `ViewGroup`/`View` + 可聚焦 + 宽度 ≥60% + ≥10 字，并且必须有一个
   子节点文本与父节点完全相同（`isParagraphContainerShape()`）。
2. **重复 view id 的正文模板**：正文节点带 view id 时，只有同一个 id
   在向上 4 层的容器里出现 ≥2 次才放行（`isRepeatedViewIdAllowed()`）。
   天坦每段都是 `id/text1`，因此通过；页面装饰的唯一 id 仍被排除。

#### 控件覆盖范围与边界

当前排版不绑定具体 App 或具体控件类，按“节点是否把文字暴露给无障碍”
统一判定，因此可覆盖以下结构：

- WebView 阅读器：整页一个长文本（空行分段），或每段一个
  `View`/`TextView`（公众号）；
- 原生阅读器：每段一个 `TextView`（知乎、多数阅读 App）；
- Compose / 自定义控件：只要文本节点是 `android.view.View` 且带
  `text` 或 `content-desc`，同样按原生段块处理；
- 父子同文结构：父 `content-desc` 与子文本相同则只保留一份。

无法覆盖的情况：

- 文字直接画在 Canvas/图片上、没有无障碍文本节点（读屏本身也读不到）；
- 扫描件、DRM 保护页面；
- 标题既不上报 `isHeading`、又不匹配章节文字规则时，不居中；
- 整页只有一个文本节点且只用单个 `\n` 分段时，不缩进（避免把软换行
  当成段落）；需要该结构时按“空行分段”或“每段一个节点”适配。

#### 统一标题识别与居中（`wrapping/HeadingRules.kt`、`HeadingLayout.kt`）

Android 9（API 28）起 `AccessibilityNodeInfo.isHeading()` 可以被读屏读取：
WebView 把 `h1`~`h6` 映射为 heading，原生控件可设置
`android:accessibilityHeading="true"`。识别顺序：

1. `node.isHeading`：覆盖大多数现代网页和 App；
2. `resource-id=activity-name`：微信公众号文章标题；
3. 文字规则兜底：第X章/节/回/卷/篇/部/集、序/序言/跋、前言/后记/尾声/
   引言/结语/目录、Chapter/Part/Section N。

命中且整块内容只有一个节点、长度 ≤60 字时，`Content.setHeading(true)`；
`DisplayManager` 在翻译后调用 `HeadingLayout.center()`，按点显器实际方数
左侧补 `(显示方数 - 标题方数) / 2` 方空白。标题放不下时保持原样，交给
换行策略处理。

限制：不上报 heading、又不匹配文字规则的标题（例如部分原生 App 的问题
标题）不会居中；当前不区分标题级别（h1/h2 一律居中）。

---

### 3.5 排版折行：`wrapping/` —— 国标避头点/避尾点

`WrapStrategy.kt`（502 行）是**基类**，含国标排版规则实现：
- **避头点**：行首不得留标点
- **避尾点**：行尾不得留开括号（`（《` 等）

**断点模型**（基类注释明确说明）
- *removable break point*：可断，若在行尾则被**删除**（如空格）
- *unremovable break point*：可断，但该 cell **保留**（如连字符后的位置）
- 算法隐含规则：连续 removable 断点后的第一个位置，隐含一个 unremovable 断点

| 子类 | 行数 | 策略 |
|:---|---:|:---|
| `SimpleWrapStrategy` | 17 | 超长就按字符切断（词会被切开） |
| `WordWrapStrategy` | 28 | 在**空盲文 cell** 处断行（词不断开） |

> 位置由 `DisplayManager.Content.getPanStrategy()` 选择具体策略。

---

### 3.6 音效：`sound/`

| 类 | 行数 | 说明 |
|:---|---:|:---|
| `SoundEvent.kt` | 105 | 音效事件枚举/定义 |
| `SoundScheme.kt` | 31 | 方案数据类：`(key, name, variant, isUserScheme)` |
| `SoundSchemeManager.kt` | 154 | 方案切换与管理 |

> ⚠️ `SoundScheme.kt` 注释明确：**自定义方案功能已被移除**
> （"Only built-in schemes remain after the custom-scheme feature was removed; there are no per-event overrides."）
> 但 git 历史里有 `93bf97e feat: 音效方案系统 - 多套音效方案切换与自定义`。
> 即：自定义能力后来被砍掉了，现在只剩**内置方案整体切换**。
> 若要恢复"逐事件自定义音效"，需重新设计 `SoundSchemeManager` 与偏好存储。

底层播放依赖 `libraries/utils` 的 `MidiUtils` + `com.leff.midi`（38 个文件的 MIDI 库）。

---

### 3.7 输入法：`BrailleIME` + `IMENavigationMode`

`BrailleIME.java`（838 行）是 `InputMethodService`，让用户用点显器的
6 键（盲文点位）+ 导航键输入文字（中文/英文）。
- `IMESetupWizardActivity`（242 行）首次启用引导
- `IMEHelper.kt`（80 行）辅助（启用检测/切换）

---

### 3.8 SelfBraille：外部 App 写点显器

| 类 | 行数 | 说明 |
|:---|---:|:---|
| `SelfBrailleService.java` | 293 | 服务端，接收外部写入 |
| `SelfBrailleManager.kt` | 52 | 客户端封装（供本 App 自己用） |

AIDL 在 `:client` 模块：`ISelfBrailleService.aidl` / `WriteData.aidl`。
能力：其他 App（或本 App）可主动向点显器写内容 —— 比如配合**盲文图书馆**显示电子书。

---

### 3.9 `TranslatorManager`（300 行）

管理与 `TranslatorService` 的绑定、盲文表选择、表变化监听。
`DisplayManager` 通过它拿到翻译能力。

---

### 3.10 模拟点显器（开发/教学用）

| 类 | 行数 | 说明 |
|:---|---:|:---|
| `BrailleView.java` | 338 | 软件模拟的点显器视图（画盲文点位） |
| `OverlayDisplay.java` | 480 | 屏幕叠加显示 |
| `DraggableOverlay.java` | 241 | 可拖拽浮层 |
| `SearchOverlay.java` / `SearchView.kt` | 81 / 110 | 搜索浮层 |

---

## 四、扩展点速查

| 想加什么 | 改哪里 |
|:---|:---|
| 新导航模式 | 实现 `NavigationMode`，在 `BrailleBackService.initializeNavigationMode()` + `ModeSwitcher` 注册 |
| 新节点盲文规则 | 实现 `BrailleRule`，在 `BrailleRuleRepository.find()` 注册 |
| 新折行策略 | 继承 `WrapStrategy`，实现 `calculateBreakPoints()` |
| 新音效事件 | `SoundEvent.kt` + `SoundSchemeManager` |
| 恢复自定义音效 | 重做 `SoundSchemeManager`（现仅内置方案整体切换） |
| 新设置项 | `BrailleBackPreferencesActivity.kt`（479 行）+ 偏好常量 |
| 自定义标签 | 依赖 `libraries/utils` 的 `labeling` 包（15 个类） |
| 全局命令 | `BrailleBackService.handleGlobalCommands()` |
| 音量键手势 | `readVolumeAction()` / `performVolumeAction()` |
