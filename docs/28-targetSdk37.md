# 28-targetSdk 升到 37（Android 17）

> 日期：2026-11-15
> 目标：11 个模块的 `compileSdk` / `targetSdk` 从 35 提到 **37**
> 结果：装机包 `targetSdkVersion:'37'`，真机（Android 17 / API 37）五轮冒烟无崩溃

---

## 为什么能直接提

本机 SDK 里已经装了 `platforms/android-37.0` 与 `build-tools/37.0.0`，
AGP 9.2.0 能直接解析 `compileSdk 37`（不需要额外的 minor 版本写法），
所以这次没有动 AGP / Gradle / Kotlin 版本，只改了 11 个 `build.gradle` 的两行。

## 改了哪些文件

`compileSdk 35 → 37`、`targetSdk 35 → 37`，11 个模块各两行：

```
addon/braillecommon、addon/brailleime、addon/brailleimeanalytics、
addon/brailleinterfaces、addon/brailletranslate、addon/tbcompat、
brailleback、client、libraries/compatutils、libraries/utils、service
```

`minSdk 23` 未动。

---

## 按 Android 17 的行为变更逐条核对

以目标 API 37 为前提（官方 behavior-changes-17）：

| 变更 | 本仓库是否受影响 | 依据 |
|:---|:---|:---|
| 大屏（sw≥600dp）忽略 `screenOrientation`、`resizeableActivity`、宽高比限制，且 37 起不再可 opt-out | **不受影响** | 全仓搜不到这些属性与 `setRequestedOrientation`；手机 sw≈392dp，未达 600 |
| `MessageQueue` 改无锁实现，反射私有字段会坏 | **不受影响** | 全仓无 `MessageQueue` 反射，只有一处注释提到它 |
| `static final` 字段不可再被反射/JNI 修改 | **不受影响** | 无 `setAccessible` 改静态字段的代码 |
| 本地网络权限从「可选」变「强制」 | **不受影响** | 无 `DatagramSocket` / `MulticastSocket` / `NetworkInterface`；点显器走蓝牙与 USB |
| CT（证书透明度）默认开启 | **不受影响** | 应用不发 HTTPS 请求 |
| 原生库加载必须只读（DCL 扩展到 .so） | **不受影响** | `System.loadLibrary("brlttywrap"/"louiswrap")` 从 APK 的 lib 目录加载，没有把 .so 复制到可写目录再 `System.load` |
| 自定义通知视图尺寸进一步限制 | **不受影响** | 用的是标准 `Notification.Builder`，没有自定义 RemoteViews |
| 部分配置变更不再默认重建 Activity（键盘/导航/触摸屏/色彩模式） | **不受影响** | 没有依赖「重建才能刷资源」的逻辑；`android:recreateOnConfigChanges` 未使用 |
| Keystore 每应用密钥上限（37 目标：5 万） | **不受影响** | 应用不创建密钥 |

另外两项在 35 就已生效、这次没有变化：**边到边强制**（35 起）与**预测性返回**。
前者本仓库仍未显式处理 `WindowInsets`（见「遗留」），后者没有 `onBackPressed` 覆写，走系统默认。

---

## 验证

| 项 | 结果 |
|:---|:---|
| `aapt2 dump badging` | `targetSdkVersion:'37'`、`compileSdkVersion='37'`、平台名 `17` |
| 编译 + 打包 | 通过 |
| 主模块单测 | **51 套件 / 525 用例 / 0 失败** |
| service 单测 | **24 套件 / 168 用例 / 0 失败** |
| `detektCheck` | 0 个新问题 |
| `lintDebug` | **0 errors / 534 warnings**（35 时是 536，少 2 条） |
| APK（`--rerun-tasks` 全量） | **28.96 MB**（原 28.95 MB） |
| 真机（API 37） | 两进程在、`DisplayService: Service started`、`dots stats` 正常、`am_crash` 为空、无 FATAL |
| 设置页 + 返回键 | `SettingsActivity` 启动正常，`KEYCODE_BACK` 后进程存活 |

### 一条被证实为假信号的记录

装机后第一轮冒烟里，`dumpsys accessibility` 的 `Crashed services` 出现
`{com.googlecode.eyesfree.brailleback/...BrailleBackService}`，五轮都在。
按老规矩「关掉再启用一轮」后回到 `Crashed services:{}`，同时 `logcat -b events` 里
没有任何 `am_crash`、应用日志里 `DisplayService: Service started` 正常、两进程都在。
结论：这是 `install -r` 覆盖安装杀掉已绑定服务留下的历史条目，不是崩溃。
**判断真崩只看 `am_crash` / `FATAL EXCEPTION`，不看这一条。**

---

## 遗留

1. **边到边没有显式适配**：35 起系统就强制边到边，但全仓仍是零 `WindowInsets`。
   读屏用户靠焦点遍历不受影响，明眼人看到的界面可能被状态栏/导航栏压住。
   要处理就得给 8 个 Compose 页面加 inset padding，是独立一批。
2. **预测性返回**：没有 `onBackPressed` 覆写，也没有 `OnBackPressedCallback`；
   系统默认行为可用，但没有返回预览动画。
3. **16KB 页**：本机 `getconf PAGE_SIZE = 4096`，不涉及；换 16KB 设备前要单独验
   `libbrlttywrap.so` 的段对齐。
