# 仪器测试冒烟（真机 / 模拟器）

在设备上跑 `androidTest` 冒烟用例。**不走 `gradlew connectedDebugAndroidTest`**：
AGP 9.2 的 connected 任务依赖 `com.android.tools.utp:android-test-plugin-host-additional-test-output`，
本机离线缓存没有这个依赖，加 `--offline` 会直接失败；加 `--no-configuration-cache` 也救不了
（缺的是依赖，不是缓存序列化）。

所以这里直接 `adb install` 两个 APK，再用 `am instrument` 跑，效果相同且不依赖 UTP。

## 用法

```powershell
# 默认跑在 emulator-5554（不打扰手机）
powershell -File .\tools\run-androidtest.ps1

# 指定设备（例如真机无线）
powershell -File .\tools\run-androidtest.ps1 -Serial '10.121.61.209:5555'

# 只跑某个类
powershell -File .\tools\run-androidtest.ps1 -Class 'com.googlecode.eyesfree.brailleback.AppLaunchSmokeTest'
```

## 跑之前

两个 APK 必须先构建出来：

```powershell
.\gradlew.bat :brailleback:assembleDebug :brailleback:assembleDebugAndroidTest --offline
```

## 结果怎么看

- `OK (4 tests)` —— 全过。
- `INSTRUMENTATION_STATUS_CODE: -1` 里的 `-2` 是失败码，配合 `stream=` 里的断言消息。
- 用例清单在 `brailleback/androidTest/kotlin/.../AppLaunchSmokeTest.kt`。

## 已知限制

- debug APK 的 `abiFilters` 只有 `armeabi-v7a` / `arm64-v8a`，模拟器是 `x86_64`，
  所以**任何会走到 `BrlttyWrapper` 的用例在模拟器上都会 `UnsatisfiedLinkError`**。
  当前 14 个用例只启动设置主屏、保活屏、书架、阅读器、IME 向导、新手引导页、读资源数组、
  跑引导页的演示动画，不碰 native，可以在模拟器跑；
  以后加「服务能起」这类用例，必须跑真机（`-Serial '10.121.61.209:5555'`）。
- 没用 espresso / compose-ui-test：离线缓存里 `androidx.compose.ui:ui-test` 只有 1.10.2，
  与项目的 Compose 1.7.6 不同版，混用会在运行时版本冲突。要做 UI 交互测试，得先补上同版依赖。
