# 依赖探测（离线缓存补齐）

这个目录不属于主构建。它的唯一作用是：**用国内镜像把依赖拉进本机 Gradle 缓存**，
让主工程继续以 `--offline` 构建。

## 为什么需要它

这台机器一直靠 `GRADLE_USER_HOME=D:\cache\gradle` 的离线缓存构建。缓存里有什么就只能用什么，
想加一个新依赖（Compose UI 测试、UiAutomator、LeakCanary……）以前是加不进来的。

镜像实测可用（2026-11-15）：

| 镜像 | 结果 |
|:--|:--|
| `https://maven.aliyun.com/repository/public` | 200 |
| `https://maven.aliyun.com/repository/google` | 200（androidx 走这个） |
| `https://mirrors.cloud.tencent.com/nexus/repository/maven-public/` | 200 |
| `https://dl.google.com/dl/android/maven2/` | 200 |

## 怎么用

### JVM 依赖（jar）

```powershell
.\gradlew.bat -p tools\dependency-probe resolveProbe --refresh-dependencies
```

### AAR 依赖（androidx / compose / espresso / uiautomator / leakcanary）

```powershell
.\gradlew.bat :brailleback:resolveProbeAar --init-script tools\dependency-probe\android-probe.init.gradle
```

`init-script` 只在这一次构建里生效，往主工程注入一个 `probeAar` 配置，
它复制 `debugRuntimeClasspath` 的变体属性（AGP/BuildType/Flavor），所以能解析 AAR。
不改仓库里任何 `build.gradle`，也不影响日常构建。

要拉新的坐标，改 `android-probe.init.gradle` 里那个列表，或 `build.gradle` 的 `dependencies`。

## 试跑静态检查

```powershell
.\gradlew.bat -p tools\dependency-probe ktlintProbe --offline
.\gradlew.bat -p tools\dependency-probe detektProbe --offline
```

两个都 `ignoreExitValue = true`，只报告不改文件。

## 实测结论（2026-11-15）

- **ktlint 1.3.1**：能解析 Kotlin 2.2 代码，但当前代码报 **9256 条**风格问题
  （`function-signature` 要求每个参数换行、`no-empty-first-line-in-class-body`、`import-ordering` 等）。
  直接当门禁会让整个项目红，必须先 `ktlintFormat` 全仓重排（248 个文件的巨大 diff），**暂不接**。
- **detekt 1.23.7**：能解析 Kotlin 2.2，报的都是真实复杂度问题（`BrailleBackService` 39 个函数、
  `LargeClass`、`CyclomaticComplexMethod`、`NestedBlockDepth`）。可用，建议以 baseline 方式接
  「只拦新增债务」。
- **robolectric 4.14.1 / leakcanary 2.14 / work 2.10 / profileinstaller 1.4.1**：已进缓存，尚未接入
  （理由见 `docs/20-依赖与镜像.md`）。
