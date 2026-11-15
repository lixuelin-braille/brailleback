# 22 - Kotlin 空安全清理（第一批）

上一批把工程护栏补齐了，这一批开始动代码本身：清理 Kotlin 里的非空断言（`!!`）。

## 为什么先清这个

盲文点显器服务是**无障碍服务**，一处未捕获的 NPE 不是「一个页面白屏」，
是整个服务被系统判定崩溃、从无障碍列表里掉出去，用户手里那块点显器立刻哑掉。
`!!` 就是「编译器替我检查过了」的谎话，它把编译期的确定性换成了运行期的爆炸。

全仓基线：**281 处 / 45 个文件**。这一批清掉 14 处，剩 267 处。

## 本批清掉的 14 处与改法

改法不是把 `!!` 换成 `?.` 就完事，每一处都按「原来崩了会怎样」重新定语义。

| 位置 | 原写法 | 改成 | 语义变化 |
|:--|:--|:--|:--|
| `DisplayClient.handleReportInputEvent` | `localListener?.let { it.onInputEvent(event!!) }` | 两个 `?: return` | 事件为 null 时丢弃，不再把回调线程炸掉 |
| `TranslatorClient.handleOnInit` | 回调后置 null | 先取局部、先置 null、再回调 | 回调里若重入，同一个监听器不会被通知两次 |
| `ClassLoadingManager.findClass` | `set != null && set!!.contains(...)` | 局部变量 `knownMissing` | 属性局部化后才能智能转换，语义不变 |
| `SimpleOverlay.dispatchKeyEvent/dispatchTouchEvent` | `l != null && l!!.onKey(...)` | `l?.onKey(...) == true` | 等价，少一次读属性 |
| `ModeSwitcher.onAccessibilityEvent` | `m == null \|\| !m!!.on(...)` | 局部 `overrideMode` | 名字不能叫 `override`（Kotlin 关键字，编译直接报错） |
| `WebViewDialog.onCreate` | `loadUrl(getStringExtra(...)!!)` | 判空后 `finish(); return` | 缺 URL 不再崩在 onCreate |
| `SoundSchemeManager.getInstance` | `return instance!!` | `checkNotNull(instance) { ... }` | 保留失败语义，但给出可读消息 |
| `SharedPreferencesUtils`（int/float 两处） | `getString(k, d)!!` | 先取非空 `defaultValue`，再 `?: defaultValue` | 默认值必非空，编译器能认 |
| `DisplayContent.translateWithVerbatimBraille` | `val text = this.text!!` | `?: return createEmptyTranslation(this.text)` | 空内容按空翻译返回，与同函数其它分支一致 |
| `DisplayContent.translateOrDefault` | `translator.translate(text!!.toString())` | 局部 `content` + 空则空翻译 | 同上 |
| `DisplayManager.updateDisplayedContent` | `currentContent.getText()!!` | `?: return` | 内容已清空就不做这帧位置换算 |
| `BrailleIME.onUpdateSelection` | `if (mExtractedText != null) { mExtractedText!!... }` | 局部 `extracted` | 属性局部化，语义不变 |

## 三条硬规矩

1. **不为了「去掉符号」而改语义。** 原来会崩的地方，改成静默返回是**行为变化**，
   必须在注释里写清「以前会崩、现在丢弃/放弃这帧」，否则下次看代码的人不知道边界在哪。
2. **属性先落局部变量再判空。** Kotlin 不会对 `var` 属性做智能转换（多线程下值可能变），
   所以 `x != null && x.foo()` 这种写法在属性上永远需要非空断言，这是 `!!` 的一大来源。
3. **改文件只允许「字符串替换 + 原样写回」。** 见下面的踩坑。

## 踩坑记录（两条，都真实发生过）

### 一、PowerShell 函数输出把文件头写坏了

第一版替换脚本长这样：

```powershell
function Rep2($text,$old,$new){
  if(-not $text.Contains($old)){ '  MISS'; return $text }   # 这行把 '  MISS' 写进了输出流
  $text.Replace($old,$new)
}
$s = Rep2 $s $old $new        # $s 变成数组：'  MISS' + 原文本
[IO.File]::WriteAllText($p,$s) # 数组转字符串 → 两个 MISS 被拼到文件第一行
```

结果是 `DisplayContent.kt` 第一行变成 `  MISS   MISS /*`，编译报 178 条错误，
其中最迷惑的是 `Unresolved reference 'Content'`——因为整个文件语法废了，类根本没被声明。
用 `git checkout --` 恢复后重做。

**教训**：函数里不要往输出流写调试字符串又把它当返回值用；
写文件前先断言「首行是 `/*` 或 `package`」。

### 二、同一仓库里行尾不统一

`DisplayContent.kt` 是 CRLF，其它改动文件是 LF。
多行匹配必须按文件实际行尾拼字符串（先 `Contains("`r`n")` 判断），
否则 `Contains` 一直 false、替换静默不生效——第一次就吃了这个亏（两处 MISS）。

## 验证

| 项 | 结果 |
|:--|:--|
| 编译 | 全部模块通过（`e:` 行 0 条） |
| 单测 | 主模块 520 + service 164，全绿 |
| detekt | `detektCheck` 通过（没有新增基线外问题） |
| lint | `lintDebug` 通过，error=0 |
| 真机 | 装机后连续三轮采样 `Crashed services:{}`，主进程与 `:service` 都在，无 `AndroidRuntime`/FATAL |

真机第一轮采样曾看到 `Crashed services:{{...BrailleBackService}}`，
复采三次全空、日志无 FATAL——那是「服务刚被停用、正在解绑」的瞬时记录，不是本次改动引起的崩溃。

## 第二批：7 个文件 82 处（267 → 185）

这一批换了一个更彻底的手法：**属性局部化**。

`mDevice!!`、`mSocket!!`、`currentNode!!` 这类写法，问题的根不在「少一个 `?.`」，
而在「同一个可空属性在一段逻辑里被读了好几次」——中间任何一次被别的线程清空，
断言就炸。所以不是逐行换安全调用，而是在函数开头取一次局部变量，后面全用局部：

```kotlin
// 改前：三次读属性，三次断言
if (!mUsbManager!!.hasPermission(mDevice!!)) {
    if (sPromptedDeviceName == mDevice!!.getDeviceName()) { ... }
}

// 改后：各取一次，权限与设备名都来自同一份快照
val usbManager = mUsbManager
val deviceName = chosenDevice.getDeviceName()
if (usbManager == null || !usbManager.hasPermission(chosenDevice)) { ... }
```

| 文件 | 处数 | 改法要点 |
|:--|--:|:--|
| `NodeBrailler` | 15 | 节点 ref 为空就放弃这次边界计算；`AccessibilityNodeInfo` 释放改 `?.recycle()` |
| `ReadThread` | 10 | socket/驱动线程各取局部；SDP UUID 缺失跳过该设备继续找下一台 |
| `UsbDisplayConnection` | 21 | 设备与管理器快照化；权限轮询/广播/请求三处统一 |
| `HidDisplayConnection` | 11 | 同上，另加 `unregisterReceiver` 用局部引用 |
| `TreeDebugNavigationMode` | 11 | `currentNode`/`pendingNode` 快照；比较改 `!=` 而非 `.equals` |
| `OverlayTutorialActivity` | 14 | 演示视图全部 `?.` 调用：视图没建好时安静跳过，不再崩在定时线程 |

`ReadThread` 里有一处**行为修正**要记住：原来读循环每轮都用 `mSocket!!` 重新取属性，
现在进入循环时取一次。一个读循环对应一个 socket 本来就是正确的生命周期，
而且断连时不会再把读线程连带炸掉。

## 第二批踩坑：detekt 拦住了我新加的 break

给循环加「为空就 `break`」之后，`detektCheck` 直接失败：

```
NodeBrailler.kt:259 [LoopWithTooManyJumpStatements]
NodeBrailler.kt:274 [LoopWithTooManyJumpStatements]
ReadThread.kt:107  [LoopWithTooManyJumpStatements]
```

这条规则是「一个循环里最多一个跳出口」。当时的写法确实有两个：
`get() 为空就 break` 和 `不同行就 break`。**没有抑制规则**，而是重构：

- `NodeBrailler`：把「沿兄弟链朝一个方向扫」抽成 `scanSiblings(step, onAccept)` 单出口函数，
  左扫右扫各调用一次，两个循环和四处重复逻辑一起消失。
- `ReadThread`：改成 `while (running)` + 标志位，循环里零 `break`。

两个方向都要扫，所以不能用 `ret && scanSiblings(...)` 短路——原逻辑左边失败右边照扫。

## 验证

| 项 | 结果 |
|:--|:--|
| 编译 | 全部模块通过 |
| 单测 | 520 + 164 全绿 |
| detekt | 通过（重构后不再报 `LoopWithTooManyJumpStatements`） |
| lint | 通过，error=0 |
| 真机 | 连续四轮采样 `Crashed services:{}`，主进程与 `:service` 都在，无 `FATAL EXCEPTION` |

## 第三批：19 个文件 51 处（185 → 134）

先把「≤3 处的小文件」一次清完，剩下的大文件集中攻。这一批里 UI 类占多数，
改法也统一成了三种：

| 情况 | 改法 |
|:--|:--|
| 视图 / Activity / ActionBar 可能为空 | `?.` 直接调用，空的机型上本来就是空操作 |
| 刚赋值给可空属性，马上要用 | 先落局部，赋给属性，再用局部 |
| `Parcel` 反序列化 | **读写顺序不能动**，用 `?: 默认值` 或局部，绝不可跳过不读 |

`Parcel` 里有一条纪律值得单独记：`BrailleDisplayProperties` 读友好键名时，
原来写作 `names[in.readString()!!] = in.readString()!!` —— 两个 `!!` 之间是两次
`readString()`。改成局部变量时如果只读一次，后面所有字段都会错位。所以改成：

```kotlin
for (i in 0 until size) {
    // 反序列化必须读写成对：即使键为空也要把后面的值读完，否则字段会错位。
    val key = `in`.readString()
    val value = `in`.readString()
    if (key != null) {
        names[key] = value ?: ""
    }
}
```

`WriteData.writeToParcel` 则用 `checkNotNull` 保留失败语义，因为缺节点时继续写会把
整条 Parcel 流写坏，读端会读到错位数据——这种情况必须明确失败：

```kotlin
val node = checkNotNull(accessibilityNodeInfo) {
    "WriteData 缺少 accessibilityNodeInfo，无法写入 Parcel"
}
```

测试文件 17 处统一改成 `requireNotNull(x) { "说明" }`。注意
`org.junit.Assume.assumeTrue` **不带 contract**，它后面编译器不会收窄类型，
所以「断言不为空 + 后面直接用」的写法仍要显式 `requireNotNull`。

## 第三批 detekt 又拦了两条

| 规则 | 位置 | 处理 |
|:--|:--|:--|
| `ComplexCondition`（7 > 5） | `DisplayServiceHandlerActions.kt:48` | 抽出 `hidBusy` 与 `connectionInProgress` 两个局部布尔 |
| `ReturnCount`（7 > 6） | `BrailleMenuNavigationMode.activateCurrentMenuItem` | 三个分支各自 `return` 收敛成一个 `when` 表达式，标签分支抽成 `activateLabelItem` |

`ReturnCount` 这条说明一件事：**每加一个 `?: return`，都在往「返回点太多」这条线上走**。
把分支收敛成表达式比逐处加 early return 更耐用。

## 第四批：八个小文件 49 处（134 → 85）

到这一步，全仓只剩 `BrailleBackService.kt` 一个文件还有 `!!`。

| 文件 | 处数 | 改法 |
|:--|--:|:--|
| `DefaultNavigationMode` | 10 | `AccessibilityNodeInfoRef.get()` 取快照；`?.let { ... } ?: false` 表达失败 |
| `LabelProviderClient` | 10 | `mClient!!` 全换成方法顶部 `val client = mClient ?: return ...` |
| `SelfBrailleService` | 8 | 协议字段缺失用 `requireNotNull` 说明原因；旧状态缺字段则跳过 |
| `BdoCArchiveParserTest` | 6 | `assertNotNull` + `!!` 合并成 `requireNotNull` |
| `SoundBehaviorContractTest` | 6 | 同上，资源查表统一 `requireNotNull` |
| `DisplayService` | 5 | `if (x != null) { x!!.disconnect() }` → `x?.disconnect()` |
| `JiebaDict` | 4 | `dag[i]!!` → `dag[i].orEmpty()`；`containsKey + !!` → `?: 1` |

两条经验：

1. **`mClient!!` 这类属性，要改成「方法顶部取局部」，而不是逐处换 `?.`**。
   `LabelProviderClient` 的 8 个方法都有 `if (!checkClient()) { return null }`，
   紧跟一行 `val client = mClient ?: return null` 之后，下面所有 `mClient!!` 全消失。
   `checkClient()` 只判了「当下非空」，两次调用之间仍可能被 `shutdown()` 清掉，
   取局部才真正修掉这个窗口。

2. **删掉 `sentinel!!` 会让后面的 `sentinel.get()` 编译失败**。
   原因是那一行的 `!!` 同时承担了「智能转换」的作用——它把 `sentinel` 从可空收窄成非空，
   后面才允许直接调 `.get()`。删断言必须换成
   `val sentinelRef = requireNotNull(sentinel) { "…" }`，否则编译器只看到可空类型，
   下一行立刻报 `Only safe (?.) or non-null asserted (!!.) calls are allowed`。
   这是本批唯一一次编译失败，也是最容易踩的一类坑：**断言不只是「压住警告」，它还在给编译器发信号**。

`shutdown()` 顺手改成先摘引用再释放：

```kotlin
val client = mClient
mClient = null
client?.release()
```

原来的写法是 `if (checkClient()) { mClient!!.release(); mClient = null }`——
释放过程中被重入时，第二次会拿到同一个 client 再 release 一遍。

验证：编译通过、520 + 164 全绿、detekt 通过、lint error=0；
真机连续五轮采样 `Crashed services:{}`，主进程与 `:service` 都在，无 `FATAL EXCEPTION`。

## 剩余 85 处：只剩 BrailleBackService

| 文件 | 处数 | 行数 | 说明 |
|:--|--:|--:|:--|
| `BrailleBackService.kt` | 85 | 1040 | 无障碍服务主类，全仓最后一处 |

分 4 批做，每批 20 处左右，每批跑单测 + 装机冒烟：

1. 成员属性断言（`mDisplayManager!!`、`mSelfBrailleService!!` 一类）
2. 生命周期回调（`onServiceConnected` / `onAccessibilityEvent` / `onDestroy`）
3. 偏好设置与内容刷新路径（`SharedPreferences`、`DisplayManager.setContent`）
4. 剩下的 UI、菜单、广播接收

目标：全仓 `!!` 归零，再谈「密封类替代 null 返回」「不可变集合」「`when` 穷尽」这些更深的 Kotlin 化。
