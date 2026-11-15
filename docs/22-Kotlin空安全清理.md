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

## 剩余 185 处的分布与下一批

| 文件 | 处数 | 行数 | 备注 |
|:--|--:|--:|:--|
| `BrailleBackService.kt` | 85 | 1040 | 无障碍服务主类，风险最高，占剩余的一半 |
| `LabelProviderClient.kt` | 10 | 597 | 标签服务客户端 |
| `DefaultNavigationMode.kt` | 10 | 1290 | 导航主逻辑 |
| `IMESetupWizardActivity.kt` | 9 | 213 | 输入法向导（UI，可整批清） |
| `SelfBrailleService.kt` | 8 | 294 | 自述盲文服务 |
| `KeyBindingsActivity.kt` | 6 | — | 按键列表（UI） |
| `SoundBehaviorContractTest.kt` | 6 | — | 测试 |
| `BdoCArchiveParserTest.kt` | 6 | — | 测试 |
| `DisplayService.kt` | 5 | 1769 | 显示服务主类 |
| `JiebaDict.kt` | 4 | — | 分词词典 |
| 其余 18 个文件 | 36 | — | 多为测试与 UI |

建议下一批顺序：

1. UI 与测试这 8 个文件（约 40 处，改法全程 `?.` 与 `?: fail()`，风险最低，一次清完）
2. `BrailleBackService.kt`：85 处分 4 次，每次 20 处左右，每次改完跑单测 + 装机冒烟
3. `DisplayService.kt` 与 `DefaultNavigationMode.kt` 最后做（体量大、逻辑密）

目标：全仓 `!!` 先降到 0，再谈「密封类替代 null 返回」「不可变集合」「`when` 穷尽」这些更深的 Kotlin 化。
