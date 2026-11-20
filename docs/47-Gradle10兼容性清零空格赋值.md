# 47 · Gradle 10 兼容性：清零「空格赋值」弃用项

## 为什么现在做

`BUILD SUCCESSFUL` 里一直挂着一条尾巴：

> Deprecated Gradle features were used in this build, making it incompatible with Gradle 10.

这不是风格问题——**Gradle 10 会把它们从警告变成构建失败**。项目现在跑在 Gradle 9.4.1，等于脚底下埋着一颗定时炸弹：哪天升级 Gradle 或换 CI 镜像，整条构建链一起炸。

## 根因只有一个

`--warning-mode all` 把全部警告打出来后，除了一条 AGP 选项警告，**其余 100% 是同一件事**：Groovy DSL 的「空格赋值」语法（`prop value` / `prop(value)`）在 Gradle 10 被移除。

仓库里的写法是这样的：

```groovy
compileSdk libs.versions.android.compileSdk.get().toInteger()
namespace 'com.googlecode.eyesfree.brailleback'
abortOnError true
maven { url 'https://maven.aliyun.com/repository/google' }
```

Gradle 现在把 `compileSdk 37` 当成「调用一个叫 compileSdk 的方法」，靠 Groovy 的动态派发兜住；Gradle 10 不再兜了，必须写成明确赋值：

```groovy
compileSdk = libs.versions.android.compileSdk.get().toInteger()
namespace = 'com.googlecode.eyesfree.brailleback'
abortOnError = true
maven { url = 'https://maven.aliyun.com/repository/google' }
```

## 改了哪些

**只改属性赋值，不改方法调用。** 这是本次改动唯一的判断难点：`implementation libs.x`、`proguardFiles ...`、`buildConfigField "int", ...`、`abiFilters 'arm64-v8a'`、`apply plugin: '...'` 都是**方法调用**，加 `=` 会直接编译失败。

改写的 21 个属性名（白名单，逐个确认过是 AGP/Gradle 的 Property 而不是方法）：

| 类别 | 属性 |
| --- | --- |
| Android 编译 | `compileSdk`、`minSdk`、`targetSdk`、`ndkVersion`、`buildToolsVersion`（本仓库未用） |
| 应用标识 | `namespace`、`applicationId`、`versionCode`、`versionName` |
| 构建类型 | `minifyEnabled`、`shrinkResources`、`signingConfig` |
| 签名 | `storeFile`、`storePassword`、`keyAlias`、`keyPassword` |
| lint | `abortOnError`、`checkReleaseBuilds` |
| Java 版本 | `sourceCompatibility`、`targetCompatibility` |
| 仓库 | `url` |

规模：**11 个模块 `build.gradle` 94 行 + `settings.gradle` 5 行 + 根 `build.gradle` 2 行 + `tools/dependency-probe/settings.gradle` 5 行**，合计 106 行，全部是「同一行插入 ` = `」，无语义改动。

## 验证

```
# 1) 警告清零（读配置即可，不必全量构建）
gradle help --warning-mode all --offline --no-configuration-cache
# 之前：几十条 + 每次配置重复刷；现在：只剩 1 条（见下）

# 2) 全量回归
gradle :brailleback:assembleDebug :brailleback:testDebugUnitTest :service:testDebugUnitTest \
       :brailleback:lintDebug :service:lintDebug --offline --no-configuration-cache -x verifyNativeLibs
# BUILD SUCCESSFUL，83 套件 / 743 用例全过，lint 0 error
```

`settings.gradle` 的 7 条警告容易被漏掉：它们在 `pluginManagement { repositories { maven { url '...' } } }` 里，**单行内联写法**，按「行首是属性名」的正则会全部跳过。这类警告在 `gradle help` 的输出里标的是 `Settings file ...: line 3`，而不是 `build.gradle`，看日志时要认这一行。

## 刻意留下的那一条

```
WARNING: The option setting 'android.enableResourceOptimizations=false' is deprecated.
```

它是 **AGP 的选项弃用**，不是 Gradle 特性弃用，不在 Gradle 10 的移除清单里，不会导致硬失败。保留原因：

- 该选项控制 release 包的**资源名混淆**；本项目明确要求发布包资源名可读（排查 APK 内容、给补丁工具用）。
- AGP 9 官方升级说明里，被「强制报错」的属性只有 `android.r8.integratedResourceShrinking`、`android.enableNewResourceShrinker.preciseShrinking` 两个，不包含它。
- 目前查不到官方给出的等价新属性；在没有明确替代项之前**删掉它是赌博**：一旦 AGP 的资源优化默认打开，release 包的资源名会被混淆，出问题只在发布后才发现。

处理方式：留着，并接受 1 条警告。等 AGP 给出等价属性再换。**判断依据写在这里，避免下次有人「顺手清了」。**

## 还没做的

- **Groovy DSL 本身还在**（`*.gradle` 32 个 / `*.gradle.kts` 0 个）。本次只是让 Groovy 写法符合 Gradle 10 语法，没有转 Kotlin DSL。转 DSL 与抽 convention plugin 应合成一批做：先把 11 个模块重复的 `compileSdk`/`compose`/`lint` 配置收进 convention plugin，再逐脚本转 kts，每步可单独验证。
- 根 `build.gradle` 的 `task clean(type: Delete) { delete rootProject.buildDir }` 仍是 `task` 老 API + `buildDir`（Gradle 9 内已标记 `buildDir` 应换 `layout.buildDirectory`），当前不产生弃用警告，不影响 Gradle 10，未动。
