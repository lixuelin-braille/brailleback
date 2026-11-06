# 模块文档 · `:libraries`（工具库）

> 两个库模块，均为 `:brailleback` 和 `:service` 的公共依赖
> - `:libraries:utils` —— Java **83** 个文件
> - `:libraries:compatutils` —— Java **23** 个文件

---

# 第一部分：`:libraries:utils`

## 包结构

| 包 | 文件数 | 说明 |
|:---|---:|:---|
| `com.googlecode.eyesfree.utils` | 27 | 无障碍/通用工具（核心） |
| `com.googlecode.eyesfree.labeling` | 15 | 自定义标签管理 |
| `com.googlecode.eyesfree.widget` | 3 | 自定义控件 |
| `com.leff.midi.*` | 38 | **第三方 MIDI 库**（音效播放用） |

## `eyesfree/utils`（27 个类）

| 类 | 用途 |
|:---|:---|
| `AccessibilityEventListener` | 无障碍事件监听抽象 |
| `AccessibilityEventUtils` | 事件类型/文本提取 |
| `AccessibilityNodeInfoRef` | 节点引用（自动回收） |
| `AccessibilityNodeInfoUtils` | 节点遍历/属性工具 |
| `AutomationUtils` | 执行自动化操作（点击/滚动） |
| `BasePackageMonitor` | 安装包变化监听 |
| `ClassLoadingManager` | 动态类加载 |
| `ContrastSwatch` / `ContrastUtils` | 对比度计算（视觉相关） |
| `ExploreByTouchHelper` / `ExploreByTouchObjectHelper` | 触摸探索辅助 |
| `FeedbackController` | 反馈控制（TTS/震动/音效） |
| `LogUtils` | 日志 |
| `MidiUtils` | **MIDI 播放封装**（音效方案依赖它） |
| `MotionEventUtils` | 手势事件工具 |
| `NodeFilter` | 节点过滤器 |
| `NodeFocusFinder` | 节点焦点查找（brailleback 的 `FocusFinder.kt` 基于它） |
| `PackageManagerUtils` | 包管理 |
| `ScreenDimensionUtils` | 屏幕尺寸 |
| `ScreenshotUtils` | 截图 |
| `SharedPreferencesUtils` | 偏好读写 |
| `SparseIterableArray` | 可迭代稀疏数组 |
| `StringBuilderUtils` | 字符串构建 |
| `TreeDebug` | 节点树调试输出（`TreeDebugNavigationMode` 用） |
| `TtsEngineUtils` | TTS 引擎 |
| `WeakReferenceHandler` | 弱引用 Handler（防内存泄漏） |
| `WebInterfaceUtils` | WebView 接口 |

## `eyesfree/labeling`（15 个类）

**自定义标签**功能：允许视障用户为**未标注的控件**自己加标签（比如一个没有文字描述的图标按钮），
盲文/语音朗读时用用户自己的标签。这是无障碍领域的重要能力。

`brailleback/utils/LabelingUtils.kt`（50 行）是本模块对 labeling 的封装，
`BrailleBackService.initializeLabelManager()` 会初始化它。

## `com.leff.midi`（38 个类，第三方）

纯 Java MIDI 库（`MidiUtils` 的底层）。项目用它播放 earcon（听觉图标/音效）。

> 注意：这是**较老的第三方库**，如果音效方案要升级（比如换成 AudioTrack/Oboe 或现代音频 API），
> 需要评估替换成本。

---

# 第二部分：`:libraries:compatutils`

## 定位

Android 各 API 版本的**兼容封装层**（23 个类）。
项目 minSdk 23、targetSdk 35，跨越十几个 Android 版本，
需要针对不同版本调用不同 API —— 这个库把 `if (SDK_INT >= X)` 的判断集中封装。

## 类清单（按领域）

| 领域 | 类 |
|:---|:---|
| 通用 | `CompatUtils`, `util/ArrayUtils`, `util/SparseLongArray` |
| 无障碍服务 | `accessibilityservice/AccessibilityServiceCompatUtils` |
| 无障碍 | `view/accessibility/AccessibilityEventCompatUtils`<br>`view/accessibility/AccessibilityManagerCompatUtils`<br>`view/accessibility/AccessibilityNodeInfoCompatUtils`<br>`view/accessibility/AccessibilityServiceInfoCompatUtils` |
| 蓝牙 | `bluetooth/BluetoothAdapterCompat`<br>`bluetooth/BluetoothDeviceCompat`<br>`bluetooth/BluetoothHeadsetCompat`<br>`bluetooth/BluetoothProfileCompat`<br>`bluetooth/BluetoothProfileCompatHoneycomb` |
| 语音 | `speech/SpeechRecognizerCompatUtils`<br>`speech/tts/TextToSpeechCompatUtils` |
| 音频/反馈 | `media/AudioManagerCompatUtils`<br>`media/AudioSystemCompatUtils`<br>`os/VibratorCompatUtils` |
| 输入/视图 | `view/InputDeviceCompatUtils`<br>`view/MotionEventCompatUtils`<br>`view/SurfaceControlCompatUtils` |
| 系统 | `app/AppOpsManagerCompatUtils`<br>`provider/SettingsCompatUtils` |

## 使用注意

- 蓝牙相关 compat 类（`BluetoothDeviceCompat` 等）与**点显器蓝牙连接**强相关，
  改动前先确认 `DeviceFinder` 的调用点
- `AccessibilityNodeInfoCompatUtils` 被 `NodeBrailler` / `BrailleRule` 大量使用
- 这是 2015 年前后的 eyes-free 老代码，部分 compat 类在现代 AndroidX 已有官方替代
  （如 `androidx.core.view.accessibility`），**可考虑逐步迁移以减少维护面**

---

# 第三部分：两个库的现状评估

| 项 | 评估 |
|:---|:---|
| 与业务耦合度 | 低（纯工具），可独立维护 |
| 风险点 | `com.leff.midi` 第三方老库；compatutils 部分已被 AndroidX 覆盖 |
| 建议 | 新代码优先用 AndroidX；老 compat 类按需逐步替换，不必一次性重构 |
