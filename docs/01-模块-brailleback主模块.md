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
