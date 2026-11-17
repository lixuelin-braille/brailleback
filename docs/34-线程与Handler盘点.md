# 34 · 线程与 Handler 盘点（收敛空间评估）

> 2026-11-17。只读盘点，**没有改代码**。结论先说：能收敛的已经在 11-15 那三批做完了，
> 剩下的要么被 native 驱动语义锁死，要么只值几个对象分配，不值得再动。

## 1. 盘点数据

`brailleback/src`、`service/src`、`libraries/*` 合计 201 个源文件，命中：

| 项 | 次数 | 分布 |
|:--|:--:|:--|
| `Handler(` 构造/引用 | 54 | 集中在 DisplayManager(19) / 驱动线程(4) / 各 IPC 回调类 |
| `HandlerThread` | 19 | DisplayManager 10、DriverThread 8、DisplayTimers 1 |
| 裸 `Thread(` | 12 | ReadThread 4、DisplayService 2、DriverThread 2，其余各 1 |

按文件（只列有命中的 24 个）：

| 文件 | Handler | HandlerThread | Thread |
|:--|:--:|:--:|:--:|
| DisplayManager.kt | 6 | 10 | 1 |
| DriverThread.kt | 4 | 8 | 2 |
| DisplayTimers.kt | 0 | 1 | 0 |
| ReadThread.kt | 0 | 0 | 4 |
| DisplayServiceHandler.kt | 3 | 0 | 0 |
| DisplayService.kt | 2 | 0 | 2 |
| DisplayCallbackHandler.kt | 2 | 0 | 0 |
| HidDisplayConnection.kt | 3 | 0 | 1 |
| UsbDisplayConnection.kt | 2 | 0 | 1 |
| WujieGattConnection.kt | 2 | 0 | 0 |
| SelfBrailleService.kt | 4 | 0 | 0 |
| BrailleBackService.kt | 4 | 0 | 0 |
| BrailleImeHostBridge.kt | 2 | 0 | 0 |
| DefaultNavigationMode.kt / …Scrolling.kt | 2 / 1 | 0 | 0 |
| FeedbackController.kt | 2 | 0 | 0 |
| OverlayDisplay.kt | 4 | 0 | 0 |
| DraggableOverlay.kt | 2 | 0 | 0 |
| PassthroughProbe.kt | 2 | 0 | 0 |
| WeakReferenceHandler.kt | 2 | 0 | 0 |
| IMESetupWizardActivity.kt | 3 | 0 | 0 |
| BrailleDisplayScreen.kt / DeveloperOptionsScreen.kt | 1 / 1 | 0 | 0 |
| TranslatorService.java | 0 | 0 | 1 |

## 2. 分三类看，处理方式完全不同

### A. 盲文驱动链（**不该换协程**）

`ReadThread`、`DriverThread`、`DisplayManager` 的 HandlerThread、`HidDisplayConnection`、
`UsbDisplayConnection`、`WujieGattConnection`。

原因不是「懒得改」，而是语义不允许：

- brltty 是**阻塞式 C 驱动**：`read()` 会一直挂起等设备数据，靠线程阻塞换事件；
- UART over USB 与 HID 的写入是同步 bulk transfer，超时靠 C 层计时；
- 设备插拔会让这些线程**整体重建**，生命周期跟硬件走，协程的取消语义反而更难对齐。

上一轮（`docs/15`）已经把这条链上能搬的都搬了：`@Volatile` 21 → 2、
三个自续 Runnable 改成协程 Job、限速三件套收进 `RefreshGate`。
剩下的线程是**驱动的骨架**，动它等于重写 JNI 边界，风险与收益不成比例。

### B. 进程内 IPC 回调（可合并，但收益微小）

`DisplayServiceHandler`、`DisplayCallbackHandler`、`WeakReferenceHandler`、`SelfBrailleService`。

这些是 AIDL 回调必须回主线程的落点。可以把各自的 `Handler(Looper.getMainLooper())`
换成一个进程级 `MainThread` 单例，省下几个对象——每个约几十字节，
对一个 33 MB 的包来说是统计噪声。**不建议为这件事改代码。**

### C. 界面层（有真实小收益）

`BrailleDisplayScreen`、`DeveloperOptionsScreen` 里的延迟（`Handler().postDelayed`）
和 `IMESetupWizardActivity` 的 300ms 延迟，可以换成 `LaunchedEffect + delay` /
生命周期绑定的协程，好处是**界面销毁时自动取消**，不会在已 finish 的 Activity 上回调。
这类是唯一「改了以后行为更安全」的部分，但量只有三四处。

## 3. 结论与建议

- 「Handler 收敛」这件事**已经基本做完**，再做是负收益：要么碰驱动（高险低益），
  要么省几个对象（噪声级）。
- 真正影响用户的不是线程模型新旧，而是**有线点显器几十秒后停更**（`docs` 里记的
  VID 1106/PID 256 那台）：应用层没有任何传输超时常量与失败恢复，
  只能拔插。这是本轮盘点发现的、比「现代化」更值钱的一条。
- 若要做界面层那三四处的生命周期绑定，可以并进下一次改 Compose 屏的时候顺手做，
  不值得单独开一批。

## 4. 本轮没做什么

- 没有改任何线程或 Handler 代码；
- 没有跑真机点显器验证（这条链上的行为只能真机验，不能模拟器验）。
