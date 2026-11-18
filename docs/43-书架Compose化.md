# 43 - 盲文书架 Compose 化

> 第二个残留布局：`bookshelf.xml`（29 行，`TextView + Button + ListView`）。
> 与键盘帮助屏不同，这屏**会写数据**（删除、重命名、清空），所以护栏重点从「显示对不对」
> 变成「误删护栏还在不在」。

---

## 1. 这屏的核心不是列表，是四条语义

| 语义 | 旧实现 | 新实现 |
|---|---|---|
| 一本书念什么 | `ArrayAdapter.getView` 里设 `contentDescription` | 行 `semantics(mergeDescendants)` + `contentDescription = description` |
| 点击 | `onItemClickListener` → 阅读器 | `combinedClickable` 的 onClick |
| 长按 | `onItemLongClickListener` → 三项菜单 | `combinedClickable` 的 onLongClick |
| 清空全部 | `onCreateOptionsMenu` + 二次确认 | 顶栏图标按钮（`contentDescription` = 原文案）+ 二次确认 |

朗读文本仍是「书名, 格式, 上次打开 X 前」，由 `bookshelf_item_desc` / `bookshelf_item_desc_no_time` 两个模板拼出来，
`DateUtils.getRelativeTimeSpanString` 照旧 —— 这部分一行没动，只是从 `getView` 移到 `describe()`。

## 2. 存储——刻意没动

书架用的是**自己的**偏好文件 `bookshelf`（`PREFS_NAME`），不是默认偏好，所以上一批的 `SettingsStore` 覆盖不到它，
这次也没顺手搬进 DataStore：

```
key = "books"
value = {"<uri>\u0001<name>\u0001<ext>\u0001<openedAt>", ...}   // 上限 200 条
```

这是**用户已有书籍的 ABI**。编码一改，用户攒的 200 本书就全部读不出来，所以契约测试逐字钉住了
`SEP`、`PREFS_NAME`、`KEY_BOOKS`、`BOOK_LIMIT = 200`、四段分支与旧两段兼容分支。

`recordRecent(context, uri, name)` 是阅读器把书送回书架的静态入口，签名同样钉死。

## 3. 一个绕不开的 Kotlin 约束：Activity 不能直接实现 internal 接口

第一版让 `BookshelfActivity : ComponentActivity(), BookshelfActions` 编译失败：

```
'e public' function exposes its 'internal' parameter type 'BookshelfEntry'
```

原因：`Activity` 是 public 类，它的 public override 带着 `internal` 的 `BookshelfEntry`。
同模块同包（`compose` 包）里不会有这问题，因为屏幕和 Activity 都在 `compose` 包里、类型也是 `internal`——
这正是前面几屏没踩到的原因。

两个出路：把整组类型改成 public（污染 API 面），或改用匿名实现。
选了后者：`private fun bookshelfActions() = object : BookshelfActions { ... }`，
匿名对象的成员不进 public 签名，类型仍可保持 `internal`。

顺带一个 detekt 反馈：11 个 lambda 参数撞 `LongParameterList`（阈值 9），
所以动作本来就是「一组」的，收进接口比摊平成 11 个回调更合理。

## 4. 误删护栏（三条，都被测试钉住）

1. 长按 → 菜单（打开 / 重命名 / 删除），删除必须再确认，确认文案带书名；
2. 顶栏清空必须确认，文案带本数（「移除全部 N 本书」）；
3. 书架为空时点清空只 toast「书架是空的」，不弹确认。

第 3 条容易在重构里丢——旧实现是在 `onOptionsItemSelected` 里判断 `mBooks.isEmpty()`。

## 5. 测试适配：没有资源 id 了

`ImportFlowSmokeTest` 原来用 `By.res("…:id/btn_bookshelf_open")` 找导入按钮。
Compose 的 `Button` 没有资源 id，改成**按文案找**：

```kotlin
val importLabel = InstrumentationRegistry.getInstrumentation().targetContext
    .getString(R.string.bookshelf_import)
device.wait(Until.findObject(By.text(importLabel)), 10_000)
```

文案取自资源本身，所以与语言无关 —— 换语言跑也不会挂。这也说明「给 Compose 控件留稳定语义」这件事
以后要靠 `testTag`，不能指望资源 id。

## 6. 验证

| 项 | 结果 |
|---|---|
| 单元测试 | brailleback **560**（新增 4 例书架契约）、service 172，全绿 |
| lint ×2 / detekt | 通过（`LongParameterList`、`SwallowedException` 各修一次） |
| 仪器测试（AVD `brailleback_api35`） | **27 例全绿**，含导入按钮真拉起选择器那例 |
| 布局 | `res/layout/bookshelf.xml` 已删除 |

新增契约（`ComposeMigrationContractTest` 第七批）：

1. `bookshelfIsComposeAndXmlLayoutIsGone`；
2. `bookshelfKeepsStorageFormat`（存储 ABI + `recordRecent` 签名）；
3. `bookshelfKeepsLongPressMenuAndConfirmations`（三种对话框 + 两条确认文案参数）；
4. `bookshelfRowsReadNameTypeAndTime`（`contentDescription` 覆盖 + 两个朗读模板 + 相对时间）。

## 7. 剩下的布局

| 布局 | 难度 |
|---|---|
| `braille_reader.xml`（86 行，906 行 Activity） | 高（要真机验点显器交互） |
| `braille_ime.xml`（33 行） | 低价值（自绘键盘区，Compose 化要重画触控） |
| `overlay.xml` / `search_tutorial_activity.xml` / `overlay_tutorial_activity.xml` | 中（`WindowManager` 悬浮窗，脱离 Activity 树） |
| `activity_licenses.xml`（6 行） | 不建议（只是 `WebView` 容器） |
