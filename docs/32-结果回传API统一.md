# 32 · 结果回传 API 统一（Activity Result）

> 2026-11-17。把全仓最后 6 处旧结果回传路径（`startActivityForResult` /
> `onActivityResult` / `requestPermissions` / `onRequestPermissionsResult`）
> 换成 Activity Result API。用户可见行为不变，改变的是「失败时有没有声音」。

## 1. 为什么要换

旧路径的实害不是「代码老」，是**静默失败**：

- 请求码是各文件里的私有常量，新增一处调用忘加分支，返回后什么都不发生；
- 对读屏用户，这表现为「点了导入没反应」——没有 Toast、没有日志，
  只能靠记 logcat 排查，而这类问题最容易被当成「软件卡住了」；
- 三个界面还挂在 `android.app.Activity` 上，与全仓的 AppCompatActivity / Compose
  两套基类混着，改 Compose 或改权限流程时会互相牵扯。

Activity Result API 把「注册的回调」与「发起调用」绑在同一个 launcher 上，
没有第二个分支需要维护，也就不存在漏分支。

## 2. 换法

| 文件 | 旧 | 新 |
|:--|:--|:--|
| `BrailleReaderActivity` | `startActivityForResult(ACTION_OPEN_DOCUMENT, REQUEST_OPEN=1)` | `openFileLauncher`（`ActivityResultContracts.OpenDocument`） |
| `BookshelfActivity` | 同上，`REQUEST_OPEN=3` | `importBookLauncher`（同上） |
| `IMESetupWizardActivity` | `context.startActivityForResult(ACTION_INPUT_METHOD_SETTINGS, REQUEST_ENABLE_IME=1)` | 宿主 Activity 的 `imeSettingsLauncher`（`StartActivityForResult`），回调里 `checkStatus()` |
| `SettingsActivity`（SettingsFragment） | `requestPermissions` + `onRequestPermissionsResult` | 两个 launcher：`RequestMultiplePermissions`（蓝牙）/ `RequestPermission`（通知） |
| `compose/DeviceConnectionComposeActivity` | 同上 | 同上 |
| `compose/StabilityComposeActivity` | 同上 | 同上 |

语义保持的三点：

- 文件选择仍是不限 MIME 的 `*/*`（`.bdo/.bdoc/.brf` 没有标准 MIME 类型），
  仍保留 `takePersistableUriPermission`，书架记录的书下次启动才能续读；
- 蓝牙权限判定沿用「看最终权限状态」而不是「看本次授予结果」——原代码就是查
  `checkSelfPermission`，所以「只补授了 SCAN」的情况行为与以前一致；
- 通知权限结果原本就不影响界面状态，现在仍不影响。

## 3. 基类为什么用 ComponentActivity 而不是 AppCompatActivity

`registerForActivityResult` 只在 `ComponentActivity` 系可用，三个旧界面必须换基类。
选 `ComponentActivity` 而不是 `AppCompatActivity`，理由全在主题：

- `BrailleReaderActivity` / `BookshelfActivity` 继承 `BraillebackTheme`，
  其父是 `@android:style/Theme.DeviceDefault`；
- `IMESetupWizardActivity` 用的是 `@android:style/Theme.Holo.Dialog`；
- 这两个都不是 AppCompat 主题。换 `AppCompatActivity` 必须连主题一起改成
  `Theme.AppCompat.*`，等于把阅读器和书架的配色、尺寸整体换掉——
  对低视力用户是可感知的视觉回归，而这批改动本不该有可见变化。

`ComponentActivity` 对主题没有要求，所以主题一行没动。全仓其余界面仍是
AppCompatActivity，两套基类并存，互不影响。

## 4. 验证

- 单元测试：**53 套件 / 538 用例**全绿，新增 `LegacyResultApiContractTest` 4 例：
  全量扫描 `brailleback/src` 不再出现旧 API、六个文件各自注册了 launcher、
  文件选择走 `OpenDocument` 且保留 `*/*` 与持久化权限、三个基类不再是 `android.app.Activity`；
- `lintDebug` 门禁（`abortOnError=true`）通过；
- 构建：debug APK 28.98 MB，arm64-v8a 与 armeabi-v7a 各 6 个 `.so`；
- 真机（Redmi K80 / Android 17 / 无线 `10.121.61.209:5555`）：
  - 仪器测试 **11 例**全过，含新增三例：书架、阅读器到 `RESUMED`，
    IME 向导弹窗不因 `Theme.Holo.Dialog` 崩；
  - `ImportFlowSmokeTest` 真点一次书架「导入」，前台包从本应用切走，
    确认系统文件选择器确实被拉起；
  - `logcat -b crash` 里 `brailleback` 命中 0 条。

## 5. 边界（没验的）

- 只验到「选择器被拉起」，没有在真机上走完「选一个 `.docx`／`.brf` → 阅读器真的读出来」；
  `takePersistableUriPermission` 的持久化续读同样没有真机证据（逻辑未改，属既有风险）；
- 权限弹窗的「拒绝」「不再询问」两条路径没在真机逐项点过，
  只保证回调语义与旧代码一致；通知权限回调故意为空，与旧版一致；
- 顺带发现一处文档失真：`brailleback/build.gradle` 里「core 锁 1.9.0 是解析出的最高版本」
  与事实不符——`:brailleback:dependencies` 显示 `androidx.core:core:1.9.0 -> 1.13.1`，
  被 `activity-compose:1.9.3` 的传递依赖抬上去了。本次只更正注释，不动依赖版本
  （强行降级会影响 Compose 运行时）；
- detekt baseline 改了一条：`TooManyFunctions` 的签名里带基类名，
  `BrailleReaderActivity : Activity...` 换成 `ComponentActivity...` 后签名不再匹配，
  于是被当成新增债务报出来。函数数没变（仍是 47 个，超阈值 30 是既有债务），
  只同步了这条签名，没有重新冻结整个 baseline。

## 6. 改了哪些文件

- `BrailleReaderActivity.kt`、`BookshelfActivity.kt`、`IMESetupWizardActivity.kt`：
  换基类、加 launcher、删 `onActivityResult` 与 `REQUEST_*` 常量；
- `SettingsActivity.kt`、`compose/DeviceConnectionComposeActivity.kt`、
  `compose/StabilityComposeActivity.kt`：权限申请换 launcher，删 `onRequestPermissionsResult`；
- `test/.../LegacyResultApiContractTest.kt`：新增 4 例；
- `androidTest/.../AppLaunchSmokeTest.kt`：新增书架、阅读器、IME 向导 3 例；
- `androidTest/.../ImportFlowSmokeTest.kt`：新增「导入按钮拉起系统选择器」1 例；
- `brailleback/build.gradle`：更正 androidX.core 版本注释。
