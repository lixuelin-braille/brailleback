# 35 · minSdk 23 → 29（Android 6.0 → Android 10）

> 2026-11-18。用户要求「把 2015 年的运行时升到最新」，确认口径是 `minSdk 23`
> （Android 6.0，2015-10 发布），目标档位最终定为 **29（Android 10）**。
> 本批做两件事：抬 minSdk、把因此变成死代码的版本分支删掉。

## 1. 为什么是 29

- 23 这一档已经没有维护意义：Android 6.0 的设备在用的极少，但每一处
  `SDK_INT < 23/24/26/29` 的分支都要跟着回归；
- 29 之后 `compileSdk/targetSdk` 仍是 37，维护面从「跨 11 个版本」收到「跨 9 个」；
- 没有选 31/35/37：31 以上会把 Android 10/11 这批仍然在用的机器、以及大量
  老点显器用户的旧机挡在门外，而这一档降低对代码维护的收益已经很小。

## 2. 连带清理（删掉的分支）

抬到 29 后，所有 `SDK_INT < 29` 的判断恒为假、`>= 29` 恒为真。按文件：

| 文件 | 删掉的东西 |
|:--|:--|
| `compose/StabilityComposeActivity.kt` | `< M` 提前返回、`< P` 提前返回、`>= M` 外包一层 |
| `BrailleBackService.kt` | 通知渠道 `>= O` 的两处包裹、`>= Q` 的三参 `startForeground`、`>= N` 的 `stopForeground`、标签管理 `>= MIN_API_LEVEL` 整层缩进 |
| `DraggableOverlay.kt` / `SearchOverlay.kt` | `TYPE_SYSTEM_ERROR` 旧分支（统一用 `TYPE_ACCESSIBILITY_OVERLAY`） |
| `rule/DefaultBrailleRule.kt` / `BrailleMenuNavigationMode.kt` / `KeyBindingsActivity.kt` | 标签菜单的 API 18 门槛三处 |
| `display/DisplayForegroundKeepAlive.kt` | `>= O` 的 `startService` 兜底、`>= Q` 三参 `startForeground`、`>= N` 的 `stopForeground`、`createKeepAliveChannel` 的 `< O` 提前返回 |
| `display/UsbDisplayConnection.kt` | `>= Q` 包在 `getSerialNumber()` 外面的判断 |
| `display/WujieGattConnection.kt` | `>= LOLLIPOP` 包在 `requestMtu()` 外面的判断 |
| `utils/AccessibilityNodeInfoUtils.kt` | `SUPPORTS_VISIBILITY` 常量、pre-JellyBean 的「全选中」分支；`isVisibleOrLegacy` 直接返回 `node.isVisibleToUser` |
| `utils/SharedPreferencesUtils.kt` | GINGERBREAD 的 `commit()` 线程兜底 |
| `labeling/CustomLabelManager.kt` / `PackageRemovalReceiver.kt` | `MIN_API_LEVEL` 常量（两处都删，全仓已无引用） |

顺带删了 7 个文件里已经用不到的 `import android.os.Build`（detekt 的
UnusedImports 会拦）。

**保留的**（都在 29 以上，仍有意义）：`>= S(31)`、`>= TIRAMISU(33)`、
`>= VANILLA_ICE_CREAM(35)`、`>= 36`，以及 `addon/` 里那套 Java 兼容库。

改动量：**20 个文件，+75 / −160 行**，净删 85 行。

## 3. 验证

| 项 | 结果 |
|:--|:--|
| 合并后的 manifest | `android:minSdkVersion="29"`、`targetSdkVersion="37"` |
| 单元测试 | 主模块 53 套件 / **538 例**、service 25 套件 / **172 例**，全绿 |
| lint 门禁 | `brailleback`、`service` 均通过（`abortOnError=true`） |
| detekt | 通过 |
| 仪器测试 | 模拟器 `brailleback_api35` headless 开机 27 秒，**11 例全过**（39 秒），跑完 `emu kill` |
| 契约测试 | `DependencyCatalogContractTest` 的版本钉同步改成 `minSdk 29` |

## 4. 必须知道的一件事：debug APK 变大了

`brailleback-debug.apk` 从 **28.98 MB → 49.49 MB**。原因不是代码变多（反而少了），
是 **dex 的存放方式变了**：

- 包里现在 26 个 `classes*.dex`，合计 33.54 MB，**全部以未压缩方式（STORED）存放**；
  其余 748 个条目仍然 deflate。`resources.arsc`、`lib/*.so` 也都是 STORED
  （后者是 `extractNativeLibs=false` 的既有行为）；
- 这是 `minSdk ≥ 29` 带来的：Android 10 的 ART 支持直接从 APK 映射读取未压缩 dex，
  AGP 因此不再对它做 deflate。副作用是文件变大、安装/首启更快；
- **release 包不受影响**：release 开了 `minifyEnabled` + `shrinkResources`，
  当前产物 17.75 MB，dex 体积本来就小；
- 如果要在 debug 包里把 dex 压回去，AGP 有 `packagingOptions` 下的 dex 开关，
  但 9.2 的准确 DSL 需要在有网环境里对着 AGP 文档确认（本机离线缓存里没有
  AGP 9.2 的 sources），**本批没动**，留作可选优化。

## 5. 影响面（要说清的代价）

- **Android 10 以下（6.0–9.0）的手机将无法安装**，包括可能仍在服役的旧机；
  用户主力机是 Android 17，模拟器是 API 35，都不受影响；
- `addon/` 六个 Java 模块（TalkBack 移植的盲文键盘）也一起变成 29，
  它们内部的 `BuildVersionUtils` 兼容库**没有逐个删**：那是移植代码的公共层，
  删了收益低、且会扩大 diff 面。留着不影响运行。

## 6. 回滚

改 `gradle/libs.versions.toml` 的 `android-minSdk` 一行即可；
代码侧的删除是纯简化，回滚用 `git revert` 恢复分支，不需要额外准备。

## 7. 本轮没做

- 没升任何依赖版本（Compose 1.7.6 等保持不变）；
- 没为 debug 包重新加上 dex 压缩（见第 4 节）；
- 没在真机上装这一版验证（按当前策略默认走模拟器，真机只在点显器相关验证时才上）。
