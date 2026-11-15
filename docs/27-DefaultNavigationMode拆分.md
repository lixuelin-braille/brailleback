# 27-DefaultNavigationMode 拆分

> 日期：2026-11-15
> 目标：第二个千行大类降下来（1302 → 937 行）
> 结果：滚动与可见性整块搬进 `DefaultNavigationModeScrolling.kt`，方法体一字未改

---

## 为什么拆这个、为什么能拆

`DefaultNavigationMode.kt` 是除 `DisplayService` 之外最大的文件（1302 行），
但是与 `DisplayService` 不同：**没有任何契约测试引用它**（全仓 `test` 里搜不到这个类名），
所以拆分不需要同步改断言，风险只在编译与单测。

滚动/可见性那一块（915–1269 行）自成体系：`autoScrollItem`、`scrollAtEdge`、
`collectVisibleNodes`、`updateAfterScroll`、`chooseVisibleTarget`、
`rememberScrollContainer` 等 15 个函数，加上 `NodeSnapshot` 与 3 个滚动常量。

## 怎么拆的

沿用 `DisplayServiceConnectionFlow.kt` 的做法：**同包扩展函数**。

- `private fun X(` → `internal fun DefaultNavigationMode.X(`
- 方法体一字未改，包括缩进与注释
- `NodeSnapshot` 只有被搬走的代码在用，直接挪成新文件里的 `internal class NodeSnapshot`，
  这样不用在每个引用处写 `DefaultNavigationMode.NodeSnapshot`
- `MAX_VISIBLE_NODE_DEPTH`、`MAX_VISIBLE_NODE_COUNT`、`SCROLL_UPDATE_DELAYS_MS`
  同样只有被搬走的代码在用，从 companion 挪到新文件做 top-level `private`，调用处不用加前缀

## 代价：6 个字段 + 3 个方法从 private 放宽到 internal

Kotlin 的 `private` 是**文件级**可见性，同包扩展函数看不到。搬走的函数要用这些：

| 成员 | 谁在用 |
|:---|:---|
| `mAccessibilityService` | `collectVisibleNodesInternal` 判断可聚焦性 |
| `mFocusFinder` | `hasVisibleFocusableAhead`、`focusNextAfterNoScroll` |
| `mMainHandler` | `updateAfterScroll` 的延迟重试 |
| `mLastScrollableNode` | `rememberScrollContainer`、`findFocusableInLastScrollContainer` |
| `mScrollSequence`、`mScrollUpdatePending` | 滚动序列防过期回调 |
| `focusOrBraille`、`isQuietNavigationEnabled`、`attemptScrollAction` | 搬走的代码调用它们 |

这 3 个方法改成 `internal` 而不是搬走，是因为它们还被主文件里的导航路径调用。

## detekt 拦了一次（真问题）

```
DefaultNavigationModeScrolling.kt: LoopWithTooManyJumpStatements
```

`collectVisibleNodesInternal` 的循环里有 3 个 `continue` 加 1 个 `return`。按项目规矩重构而不是抑制：
把「这个子节点算不算可见目标」抽成 `acceptVisibleChild(child, containerBounds, depth, result)`，
循环里只剩 `?: continue` 一处跳转。抽的时候顺带保留了 `try/finally { child.recycle() }` 的位置
（回收必须在循环体内，不能在抽出来的函数里）。

另外 detekt 还报了 `NewLineAtEndOfFile`——写文件时拼字符串丢了行尾换行，补上即可。

## 结果

| 文件 | 行数 | 非空行 |
|:---|--:|--:|
| `DefaultNavigationMode.kt` | 1302 → **937** | 871 |
| `DefaultNavigationModeScrolling.kt` | 新建 **417** | 394 |

新增护栏 `DefaultNavigationModeSplitContractTest`（5 个用例）：主文件 < 950 非空行、
9 个关键签名在滚动文件、7 个签名不许回潮、快照类与常量不许搬回去、滚动文件 < 500 非空行。

## 验证

| 项 | 结果 |
|:---|:---|
| 编译 | 通过（只剩既有的 deprecation 警告） |
| 主模块单测 | **51 套件 / 525 用例 / 0 失败**（+1 套件 / +5 用例） |
| service 单测 | **24 套件 / 168 用例 / 0 失败** |
| `detektCheck` | 0 个新问题 |
| `lintDebug` | **0 errors / 536 warnings** |
| APK（`--rerun-tasks` 全量） | **28.95 MB**（未变） |
| 真机冒烟 | 5 轮 `Crashed services:{}`、两进程在、无 FATAL；无障碍清单已恢复 |
