# 42 - 键盘帮助屏 Compose 化

> 8 个残留 XML 布局里的第一个：`key_bindings.xml`（16 行，`TextView + ListView`）。
> 这屏只读、不碰 native、不写偏好，是主阅读器之前最安全的一块试验田。

---

## 1. 这屏到底在干什么

`KeyBindingsActivity` 回答一个很具体的问题：**当前插着的这块点显器，每个键默认是什么功能**。

数据不是常量表，而是「命令表 × 点显器键位」的叉乘：

```
help_supportedCommands[]  ──┐
                            ├─→ BrailleInputEvent.stringToCommand
help_commandDescriptions[] ─┘        │
                                     ├─→ BrailleKeyBindingUtils.getBrailleKeyBindingForCommand
displayProperties.friendlyKeyNames ──┘        │
                                              └─→ getFriendlyKeyNamesForCommand → 「动作 + 键位」
```

所以只有 `Display.STATE_CONNECTED` 时才有内容 —— 没连接就是一张只有说明文字的空白页。这一点在两代实现里都保留。

## 2. 改了什么

| | 旧 | 新 |
|---|---|---|
| 基类 | `android.app.Activity` | `ComponentActivity` |
| 界面 | `key_bindings.xml`：`TextView` + `ListView` + `ArrayAdapter` | `compose/KeyBindingsScreen.kt`：`Text` + `LazyColumn` |
| 标题与返回 | ActionBar + `onOptionsItemSelected(android.R.id.home)` | `SettingsScreenScaffold` 的 TopAppBar + 返回箭头回调 |
| 数据装配 | `populateListView()` 直接塞 adapter | `loadBindings()` 返回 `List<DisplayBinding>`，喂给 `mutableStateOf` |

**没动的部分**：命令/描述数组的遍历、`LABELING_ENABLED` 对 `CMD_TOGGLE_BRAILLE_MENU` 的过滤、
键位排序与友好键名解析、`DisplayClient` 的 start/stop 生命周期、返回时回设置主屏的 `CLEAR_TOP` 语义。

## 3. 两个必须记住的细节

### 3.1 主题决定了基类只能选 `ComponentActivity`

`BraillebackTheme` 的父主题是 `@android:style/Theme.DeviceDefault`，不是 `Theme.AppCompat` 家族。
`AppCompatActivity` 在这样的主题下会直接抛「You need to use a Theme.AppCompat theme」。
所以凡是沿用 `BraillebackTheme` 的界面，基类只能是 `ComponentActivity`（`docs/40` 里主屏也是这条）。
用 `AppCompatActivity` 的只有走 `BraillebackSettingsTheme` 的那些设置子屏。

### 3.2 读屏语义：两行文字要并成一个焦点

旧实现借 `android.R.layout.simple_list_item_2` 的两行 Item 布局，读屏一次念「动作，键位」。
Compose 里如果写成两个 `Text`，会变成两个节点，读屏要么念两次、要么只念到第一行。
所以行容器要 `Modifier.semantics(mergeDescendants = true) {}`，把两行合成一条朗读内容。

## 4. 验证

| 项 | 结果 |
|---|---|
| 单元测试 | brailleback **556** 例（新增 `ComposeMigrationContractTest` 3 例）、service 172 例，全绿 |
| lint ×2 / detekt | 通过 |
| 仪器测试（AVD `brailleback_api35`） | **27 例全绿**（新增 2 例：屏能到 `RESUMED`、说明文案能解析） |
| 布局 | `res/layout/key_bindings.xml` 已删除，`R.layout.key_bindings` 不再被引用 |

新增契约（`ComposeMigrationContractTest`）：

1. `keyBindingsScreenIsComposeAndXmlLayoutIsGone` —— 必须 `setContent`、不许 `setContentView`、不许 `import android.widget.ListView`、
   `key_bindings.xml` 必须不存在（防止「布局偷偷回来、又变成两份维护」）；
2. `keyBindingsKeepsBindingAssemblyLogic` —— 六个取键位的符号一个都不能少；
3. `keyBindingsStaysReadOnly` —— 帮助页不许写偏好（它是唯一没人验证的写入路径）。

## 5. 剩下的布局（按迁移难度）

| 布局 | 现状 | 难度 |
|---|---|---|
| `braille_reader.xml`（86 行） | 主阅读器，906 行 Activity | 高（要真机验点显器交互） |
| `bookshelf.xml`（29 行） | 书架列表 | 中 |
| `braille_ime.xml`（33 行） | IME 键盘自绘 Canvas | 低价值（Compose 化要重画触控区） |
| `overlay.xml` / `search_tutorial_activity.xml` / `overlay_tutorial_activity.xml`（44/35/55 行） | `WindowManager` 悬浮窗 | 中（脱离 Activity 树） |
| `activity_licenses.xml`（6 行） | 只是一个 `WebView` 容器 | 不建议（WebView 无法 Compose 化） |
