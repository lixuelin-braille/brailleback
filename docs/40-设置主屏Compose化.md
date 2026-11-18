# 40 - 设置主屏 Compose 化

> 2026-11-18。设置界面里**最后一处**旧 preference 界面退场。
> 前置结论：不先做这一步，DataStore 迁移必然产生双源（见最后一节）。

## 为什么改

主屏此前是 `SettingsFragment : PreferenceFragmentCompat` + `res/xml/settings.xml`：

- fragment 只用来承载**一屏导航**，却带进三处耦合：ActionBar 标题要按
  `backStackEntryCount` 反推、返回箭头要手动 `setDisplayHomeAsUpEnabled`、
  进子屏还要 `announceSubScreenTitle` 手动播报标题；
- 六个子屏早就是独立 Activity，主屏的 fragment + back stack 已经没有任何用处；
- `settings.xml` 是**编译期资源**：改一条入口要动 XML + 代码两处，漏了就是
  「点了没反应」（历史上真出过，见 docs/19 的说明）。

改成 Compose 后：一张「key → 目标 Activity」接线表 + 一个列表，没有 fragment、
没有 back stack，每个子屏的标题由自己的 TopAppBar 播报。

## 改了什么

| 文件 | 变化 |
|---|---|
| `compose/SettingsHomeCatalog.kt` | 新增。三个分组（`category_start` / `category_settings` / `category_features`）+ 十行入口的数据表；key 与旧 `app:key` **同值**；`HomeAction` 描述「点一下做什么」 |
| `compose/SettingsHomeScreen.kt` | 新增。纯展示：`SettingsHomeScaffold` + 分组标题 + `SettingsActionRow`（复用设置组件底座，语义不动） |
| `compose/SettingsComponents.kt` | `SettingsScreenScaffold` 与新的 `SettingsHomeScaffold` 共用一个私有 `SettingsScaffold`；主屏不渲染返回箭头 |
| `SettingsActivity.kt` | 去掉 fragment 与三处导航状态；改 `setContent`；保留点显器 Activity 级监听与播报、蓝牙/通知权限时机、协议值归一、`LogFileWriter.init` |
| `res/xml/settings.xml` | 删除 |
| 6 个契约测试文件 | 原来断言 XML 的地方改成断言目录数据/源码；详见下 |

偏好键零迁移：目录里的 key（`setup_service`、`category_device`、`bookshelf`、
`pref_braille_keyboard_key`、`opensource_license_notice` …）与旧 XML 的 `app:key`
实际取值逐一相同，服务端与各 Compose 子屏读的还是同一个键。

## 测试怎么跟的

- **新增**（`SettingsStructureContractTest` 重写）：分组顺序与 key、六入口平级且
  都是 `Screen` 动作、每入口引用的偏好键与旧值一致、一屏节点数 ≤13、
  每行有 title/summary 且只有「启用服务」标动态摘要、**旧设施必须退场**
  （`settings.xml` 不存在 + 源码里没有 `PreferenceFragmentCompat`/`supportFragmentManager`/
  `setPreferencesFromResource`…）、六条 `wireCategory` 接线与真的 `startActivity`。
- **改**：`ComposeMigrationContractTest`、`DeveloperOptionsContractTest`、
  `KeysGesturesContractTest`、`SoundBehaviorContractTest`、`BrailleDisplayContractTest`、
  `KeyMapResourceContractTest` 原先读 `res/xml/settings.xml` 做「入口还在、内容项已归档」
  的断言，改成读 `SettingsHomeCatalog.kt`；`LogFileWriter.init(requireActivity())`
  改成 `init(this)`。
- **仪器测试不用改**：`SettingsCategoryNavigationTest` 本来就是按**文案**点分类入口，
  Compose 文本节点对 UiAutomator 同样可见——这正是它当初不绑资源 id 的价值。

## 验证

- 单测：主模块 **549 例**全过（比上一批多 1 例）。
- `lintDebug` + `detektCheck` 全过。detekt 拦了一次：`isServiceEnabled` 的
  `catch (SecurityException) { false }` 被判 `SwallowedException`——原来这行在
  baseline 里，代码搬家后签名变了、baseline 不再覆盖，于是从「已登记的旧债」变成
  「新债」。改成记一条 WARN 再返回 false，顺手补上原来真缺的排查线索。
- 仪器测试：模拟器 `brailleback_api35`，**`OK (19 tests)`，107 秒**，含真实点进
  「设备与连接」「按键与手势」「稳定性与省电」三个子屏。

## 踩到的两个坑

1. **`internal` 参数类型不能出现在 `public` 函数签名里**：`SettingsHomeScreen`
   收 `internal` 的 `HomeSectionUi`，函数本身也得是 `internal`，否则编译直接报
   `'public' function exposes its 'internal' parameter type`。
2. **契约测试与实现的耦合点会随重构一起搬家**：`wireCategory(...)` 的两行调用格式
   和两处注释标记（`// 六个分类入口跳各自的 Compose 页。` / `// 屏幕盲文键盘`）
   是四个测试类用来切「接线区」的边界。重构时**保留这两个标记和调用格式**，
   四个测试零改动；否则要一次性改四个文件，容易漏。

## 对后续（DataStore）的意义

上一轮扫描得出的阻塞点在这里：`chinese_segmentation` 与 `pref_sound_enabled`
被服务端**同步**读写；而只要还有 `PreferenceFragmentCompat` 在，
`androidx.preference` 就会绕过任何新存储层直接写 SharedPreferences——
一迁就是双源。现在旧界面清零，写入口只剩：

- 五个 Compose 子屏（`BrailleDisplay` / `DeviceConnection` / `KeysGestures` /
  `SoundBehavior` / `DeveloperOptions`）
- 主屏的协议值归一
- `CustomKeyMapStore`、`SoundSchemeManager`、`BookshelfActivity` / 阅读器自有的
  两个独立 prefs 文件

也就是说，DataStore 现在可以**按文件**整体替换默认 SharedPreferences 一处，
而不用去追 `PreferenceFragmentCompat` 的隐式写入。
