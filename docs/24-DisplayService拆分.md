# 24-DisplayService 拆分（1763 → 1317）

> 日期：2026-11-15
> 目标：把全仓唯一超过 1400 行的源文件拆开
> 结果：`DisplayService.kt` 1763 → **1317** 行，新增 `DisplayServiceConnectionFlow.kt` 492 行

---

## 为什么只拆这一个文件

全仓 ≥700 行的文件有 11 个，只有 `service/.../display/DisplayService.kt` 超过 1400：

| 文件 | 行数（拆前） | 是否超标 |
|:---|--:|:---|
| `DisplayService.kt` | 1763 | **是** |
| `DefaultNavigationMode.kt` | 1302 | 否 |
| `DisplayManager.kt` | 1180 | 否（有 `< 1100` 非空行契约，已达标） |
| `AccessibilityNodeInfoUtils.kt` | 1073 | 否 |

阈值只有一个数：1400。拆完主文件 1317，扩展文件 492，两边都不再是「千行大类」。

---

## 拆走了什么

搬出 27 个函数，按职责分四组：

| 组 | 函数 | 说明 |
|:---|:---|:---|
| 失败文案 | `getHidFailureMessage`、`getUsbHidFailureMessage` | HID 位掩码 → 三条具体提示；USB -7/-8 分别说「没插」和「没授权」 |
| 协议判定 | `getConnectionProtocol`、`isBluetoothProtocol`、`isUsbProtocol`、`isAutoProtocol`、`isWiredProtocol`、`isWiredOrAutoProtocol`、`isHidRequested`、`isUsbBrailleDeviceAttached`、`resetConnectionAttemptFlags` | 读偏好 + 查 brltty VID/PID 表，纯查询 |
| 连接入口与重连 | `autoConnectBraille`、`startExplicitConnect`、`retryWhenBluetoothDeviceAppears`、`retryHidWhenInputDeviceAppears`、`prepareAutomaticRetry`、`isConnectionActive`、`scheduleAutoReconnectIfNeeded`、`shouldAutoReconnect`、`cancelAutoReconnect` | 指数退避、屏幕交互检查、有客户端的判断 |
| 发现窗口 | `isScreenInteractive`、`hasBluetoothScanPermission`、`shouldStartDiscovery`、`startDiscoveryWindow`、`startDiscoveryInternal`、`handleDiscoveryFinished`、`handleDiscoveredDevice`、`stopDiscoverySession` | 30 秒发现窗、到期续窗、发现已知设备后重置标志 |

主文件只剩：生命周期（`onCreate` / `onDestroy` / `onBind`）、IPC 回调、两个广播接收器、`MainHandler` 接线、遥测、`ServiceImpl`、USB/武界连接本体。

---

## 为什么用扩展函数，而不是「持有 service 引用的类」

两种写法对比：

| 写法 | 方法体改动 | 风险 |
|:---|:---|:---|
| `class DisplayConnectionFlow(val service: DisplayService)` | 每个 `mXxx` 要改成 `service.mXxx`，几百处 | 大面积改文本，等于重写一遍，出错点无法逐个复核 |
| `fun DisplayService.xxx()` 同包扩展函数 | **一个字不改** | 只改声明前缀 |

选了后者。搬运后的编译结果是「一次通过 + 只报 Deprecated 警告」，说明方法体确实没被改坏。

代价是 4 个状态字段从 `private` 放宽到 `internal`：

- `mPendingBluetoothAddress`
- `mDiscoverySessionActive`
- `mDiscoveryStartedByUs`
- `mDiscoverySessionDeadline`

另有两个常量从 `private const` 放宽到 `internal const`：`DISCOVERY_SESSION_DURATION`、`DISCOVERY_RESTART_DELAY`。扩展函数看不到 companion 的 private 成员。

---

## 契约测试的连带改动

这个文件不是「没人管的大文件」，它被三个**源码文本契约**锁着方法体。拆之前先读了它们，避免拆完断测试：

| 契约 | 原来锁哪里 | 改动 |
|:---|:---|:---|
| `WujieAutoConnectContractTest` | `DisplayService.kt` 单文件，`private fun handleDiscoveredDevice(` | 增加 `flowSource`；该用例改读 Flow 文件，签名改为 `fun DisplayService.handleDiscoveredDevice(` |
| `UsbHidWiredContractTest` | `displayServiceSources` 五文件拼接 | 列表加 `DisplayServiceConnectionFlow.kt`；`isUsbBrailleDeviceAttached` / `isWiredOrAutoProtocol` / `isWiredProtocol` / `getUsbHidFailureMessage` / `retryHidWhenInputDeviceAppears` 的查找签名加 `DisplayService.` 前缀 |
| `ConnectionFailureLoggingContractTest` | 五文件拼接 | 列表加 Flow 文件（日志安装契约仍成立） |

新增 `DisplayServiceSplitContractTest`（service 模块，4 个用例）当护栏：

- `DisplayService.kt` 非空行 ≤ 1500（2026-11-26 口径；旧预算 1300）
- 7 个关键签名必须在 Flow 文件里
- Flow 文件不许变成 `class`（防止有人改回「持有引用」写法）
- 搬走的 6 个签名不许在主文件出现第二份
- Flow 文件非空行 ≤ 1500（2026-11-26 口径；旧预算 600），防止它自己长成新怪兽

> 行数预算 600/1300 → 1500，用户 2026-11-26 决定：单文件非空行上限放宽到 1500，
> 超过 1500 必须先拆再写；既有拆分成果不回退、不合并大文件。

---

## detekt 拦了一次

搬完之后 detekt 报：

```
DisplayServiceConnectionFlow.kt:293:33: Function shouldAutoReconnect has 9 return statements
which exceeds the limit of 6. [ReturnCount]
```

原因不是新代码，是**搬文件让 baseline 失配**：baseline 按「文件路径 + 签名」记录，函数换了文件就不再命中。

按项目老规矩，重构而不是抑制：把 9 个 return 收成 3 个——

```kotlin
val blocked = mAutoReconnectSuppressed || !haveClients()
        || mDataFileState != DisplayService.FILES_EXTRACTED
val busy = isConnectionActive() || mConnectPending
        || mWaitingForHidController || !isScreenInteractive()
if (blocked || busy) {
    return false
}
if (isWiredProtocol()
        || (isAutoProtocol() && isUsbBrailleDeviceAttached())) {
    return true
}
```

不能直接并成一个七条件 `if`：`ComplexCondition` 上限是 5。先算成两个局部布尔，两条规则都不碰。

---

## 验证

| 项 | 结果 |
|:---|:---|
| `:service:compileDebugKotlin` | 通过（只有既有的 Deprecated 警告） |
| 主模块单测 | **50 套件 / 520 用例 / 0 失败** |
| service 单测 | **24 套件 / 168 用例 / 0 失败**（新护栏测试 +1 套件 / +4 用例） |
| `detektCheck` | 0 个 baseline 外新问题 |
| `:brailleback:lintDebug` | **0 errors / 536 warnings**（与拆分前一致） |
| debug APK | **28.95 MB**（`assembleDebug --rerun-tasks` 全量包，与 `2a28d71` 实测一致） |
| 真机冒烟（Redmi K80） | 5 轮 `Crashed services:{}`、两进程在（`brailleback` + `:service`）、无 FATAL；无障碍清单已恢复 |

冒烟时碰到一个**假信号**，记下来免得下次误判：`install -r` 覆盖安装会先杀掉已绑定的无障碍服务，系统因此在 `Crashed services` 里记一条 brailleback。关掉再启用一轮后列表回到 `{}`，日志里是 `I/DisplayService: Service started.` 和 `dots stats: calls=2 (+2) route=readthread ... read=true`，说明服务与读线程都正常。判断真崩要看 `AndroidRuntime` / `FATAL EXCEPTION`，不能只看那一条。

行数账：

```
DisplayService.kt              1763 → 1317   （-446）
DisplayServiceConnectionFlow    新增 492
净变化                                      （-446 主文件）
```

顺带清掉 `onDestroy` 里两处 `if (mBroadcastReceiver != null)`：这两个是 `val`，判断恒真，编译器一直报 `Condition is always 'true'`。改成直接 `unregisterReceiver(...)`。

---

## 还剩什么

1. **武界识别块仍锁在主文件**：`isWujieTarget`、`shouldSkipHidForWujie`、`isWujieName`、`findNamedWujieAddress`、`findWujieAddress`。`WujieAutoConnectContractTest` 逐条断言这些函数体里的文本（`val pending = mPendingBluetoothAddress`、`readRemoteDeviceName(pending)`、`return findNamedWujieAddress() != null`、不含 `lastBluetoothDevice`）。要搬必须同步改契约的签名与边界，属于下一批。
2. **遥测块可搬**：`mDotsTelemetry` + `recordDots` + 5 个字段，约 50 行，没有契约覆盖。
3. **广播发送块可搬**：`sendConnectionState` / `sendConnectionProgress` / `broadcastConnectionState` / `broadcastConnectionProgress` / `broadcastInputEvent` / `haveClients`，约 80 行，只依赖 `mClients` 与三个状态字段。
4. `targetSdk` 仍是 35，设备的 API 37；边到边与预测性返回都没动。这是另一条线，和本次拆分无关。
