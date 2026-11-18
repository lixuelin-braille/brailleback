# 39 - Compose 组件级交互测试

> 2026-11-18。仪器测试从「页面能起」走到「点下去有反应」。

## 为什么能做了

`androidx.compose.ui:ui-test-junit4` 此前引不进来：本机缓存里只有 **1.10.2**，
与当时的 Compose 1.7.6 不同版，混用会在运行期版本冲突。依赖解冻（docs/38）后
Compose 升到 1.12.1，缓存里也有了同版测试库，于是可以接线：

```kotlin
androidTestImplementation libs.compose.ui.test.junit4
debugImplementation libs.compose.ui.test.manifest   // createComposeRule 的空宿主 Activity
```

## 五例怎么选

七个设置屏全部复用 `SettingsComponents` 这套底座，所以**补底座等于补所有屏的骨架**。
组件是纯展示层（状态走参数、动作走回调），`createComposeRule` 直接 `setContent`
渲染，不启动 Activity、不碰 `TranslatorManager` 与点显器，模拟器上稳定可跑。

| 用例 | 断言 |
|---|---|
| `switchRowClickReportsNewValue` | 关着的开关行，点标题（整行可点）上报 `true` |
| `switchRowWhenCheckedReportsFalse` | 开着的开关行点一下上报 `false` |
| `disabledSwitchRowIsNotClickable` | 禁用态 `assertIsNotEnabled`，且不上报 |
| `listRowClickFiresOnce` | 选择行点一次只回调一次 |
| `actionRowClickFires` | 动作行点击回调 |

挡的是**不会崩、只会「点了没反应」**的那一类：整行 `toggleable` 被误改成只有
`Switch` 可点、`semantics(mergeDescendants)` 被拆掉导致读屏只念半行、
禁用态仍上报、点击穿透触发两次。

## 结果

模拟器 `brailleback_api35`：`OK (19 tests)`，122 秒（原 14 + 新 5）。
门禁：单测 548 + 172、lint 双门禁、`detektCheck` 全过。

## 坑：桌面 ANR 抢焦点导致的假失败

第一次跑整包 `ImportFlowSmokeTest` 报「书架上没找到导入按钮」，单跑也失败。
查 logcat 才看到 `mCurrentFocus=Window{... Application Not Responding:
com.google.android.apps.nexuslauncher}`——模拟器桌面进程 ANR，弹窗抢走焦点，
`BookshelfActivity` 被挤到 `PAUSED → STOPPED → DESTROYED`，UiAutomator 只看得到系统弹窗。

处置（已写进 docs/19 与 `tools/run-androidtest.md`）：

```powershell
adb -s emulator-5554 shell settings put global hide_error_dialogs 1
adb -s emulator-5554 shell am force-stop com.google.android.apps.nexuslauncher
adb -s emulator-5554 shell input keyevent KEYCODE_HOME
```

这不是代码问题，**不要靠调大测试超时掩盖**。
