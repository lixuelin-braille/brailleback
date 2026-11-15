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

## 剩余 267 处的分布与下一批

| 文件 | 处数 | 行数 | 备注 |
|:--|--:|--:|:--|
| `BrailleBackService.kt` | 85 | 1040 | 无障碍服务主类，风险最高 |
| `UsbDisplayConnection.kt` | 21 | 500 | USB 连接，硬件路径 |
| `NodeBrailler.kt` | 15 | 311 | 节点转盲文，有单测 |
| `OverlayTutorialActivity.kt` | 14 | 130 | 教程浮层，UI |
| `HidDisplayConnection.kt` | 11 | 577 | HID 连接，硬件路径 |
| `TreeDebugNavigationMode.kt` | 11 | 390 | 调试导航 |
| `LabelProviderClient.kt` | 10 | 597 | 标签服务客户端 |
| `DefaultNavigationMode.kt` | 10 | 1290 | 导航主逻辑 |
| `ReadThread.kt` | 10 | 232 | 蓝牙读线程 |
| `IMESetupWizardActivity.kt` | 9 | 213 | 输入法向导 |
| `SelfBrailleService.kt` | 8 | 294 | 自述盲文服务 |
| 其余 34 个文件 | 63 | — | 多为测试与 UI |

建议下一批顺序（按「崩了影响多大」排）：

1. `NodeBrailler.kt`（15 处，纯逻辑，单测能兜底，先把模式跑顺）
2. `ReadThread.kt` + `UsbDisplayConnection.kt`（连接层，崩了用户只知道「连不上」）
3. `BrailleBackService.kt`（85 处，分 3～4 次改，每次改完必须装机冒烟）
