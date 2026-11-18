# 41 - 设置存储 DataStore 化

> 目标：把 `SharedPreferences` 从默认偏好这条主干上换下来，同时**不改 60 多处调用点的语法**。
> 前置：`docs/40`（设置主屏 Compose 化）。`androidx.preference` 会绕过任何新存储层直写默认文件，
> 主屏那处 `PreferenceFragmentCompat` 不退场，这一批就无从下手。

---

## 1. 为什么不是「全量换 DataStore」

`BrailleService`/`TranslatorService` 的分工里有一个绕不过去的硬约束：

```
brailleback/AndroidManifest.xml
  <service android:name=".translate.TranslatorService"
           android:process=":service" ... />
```

`TranslatorService` 跑在**独立进程**，而 DataStore 明确不支持跨进程（多进程读同一文件没有一致性保证）。
`service/src` 里真正读偏好的只有它一个，读到的东西也极少：

```java
// TranslatorService.java（全文只有这一处偏好）
prefs.getBoolean("chinese_segmentation", true);   // L83
prefs.registerOnSharedPreferenceChangeListener(…); // L84
prefs.unregisterOnSharedPreferenceChangeListener(…); // L91
```

所以本批的规则是**进程边界即存储边界**：

| 键 | 存储 | 原因 |
|---|---|---|
| `chinese_segmentation` | 旧 SharedPreferences（`<包名>_preferences`） | `:service` 进程要读、要监听 |
| 其余全部键 | DataStore（`braille_settings.preferences_pb`） | 只有主进程读写 |

名单写在 `SettingsStore.CROSS_PROCESS_KEYS`，并且由 `SettingsStoreContractTest` 用源码字面量钉住
**只能是 `setOf("chinese_segmentation")`**。将来 `:service` 要新增读某个键，先确认读者进程，
再改名单 —— 顺序反了就会出现「开关点了没反应、日志一行没有」。

## 2. 方案：DataStore 做唯一写源，内存快照供同步读

调用点是这样的：

```kotlin
val prefs = PreferenceManager.getDefaultSharedPreferences(this)  // 旧
prefs.getBoolean(key, false)
prefs.edit().putString(...).apply()
```

`apply()` 之后**立刻** `getBoolean()` 必须能读到新值（旧代码大量这么写：设置页写完马上刷 UI）。
DataStore 的 `data` 是 Flow，天生不满足同步读。因此 `SettingsStore` 的做法：

1. **仍然实现 `SharedPreferences`** —— 调用点只把 `PreferenceManager.getDefaultSharedPreferences(this)`
   换成 `SettingsStore.get(this)`，一行不用重写；
2. 进程内维护 `HashMap<String, Any>` 快照，同步读全走快照；
3. 写入**先改快照，再落盘**（顺序反了就是「点了开关但界面不变」）；
4. 首次访问时同步加载一次（`runBlocking` + 迁移），之后靠后台收集器把磁盘变化回灌快照。

## 3. 迁移：不建标记，靠「旧文件里还有没有可迁的键」

```
首次访问 → legacy.all 里除 CROSS_PROCESS_KEYS 外的键
        → 一次性写进 DataStore（DataStore 里已有的键不覆盖，避免双写打架）
        → 从 legacy 删掉这批键
        → 读一次 DataStore 得到最终快照
```

幂等点在于完成条件：**旧文件已没有可迁键 = 迁移完成**。中途被杀、IO 失败，下次启动接着搬；
不需要 `migrated=true` 这类标记（标记本身也要存，存哪又是个先有鸡还是先有蛋的问题）。

## 4. 踩到的四个坑（都值得记住）

### 4.1 初始化锁与快照锁必须分开（线上级：设置页永久卡死）

第一版 `ensureLoaded()` 是这样的：

```kotlin
synchronized(lock) {
    if (loaded) return
    runBlocking(Dispatchers.IO) { migrateAndLoad() }   // 迁移里又要 synchronized(lock) 更新快照
}
```

`runBlocking` 把**当前线程**卡住并等 IO 线程上的迁移跑完，而迁移要拿的那把 `lock` 正被当前线程握着
→ 自锁。表现：模拟器上 25 例仪器测试跑到 `settingsActivityReachesResumed` 就再也不动，
`logcat -s TestRunner:I` 只有 `started:` 没有 `finished:`，进程还活着。

修法：初始化用 `initLock`，快照用 `lock`，两个锁永不嵌套。

### 4.2 监听器回调是投递到主线程的，测试要等主线程

`settingsStore.edit().putBoolean(...).commit()` 之后**立刻**断言监听器被调用 → 必挂。
回调按服务层既有假设统一 post 到主线程，所以测试里要
`InstrumentationRegistry.getInstrumentation().waitForIdleSync()` 再断言。
这不是实现的问题，是断言的时机问题 —— 但顺手确认了一件事：**监听器派发语义没变**，
`DisplayManager`/`BrailleBackService` 那些 `onSharedPreferenceChanged` 的假设仍然成立。

### 4.3 detekt 两条：`write` 圈复杂度 24、`catch (Exception)` 过泛

`Editor.write` 原来同时做四件事（分发 / 清空 / 主进程键 / 跨进程键），复杂度 24 > 阈值 22。
拆成 `clearEverything` / `writeMainProcessKeys` / `writeCrossProcessKeys`，`write` 只负责分发与统一通知。
异常收窄到 `java.io.IOException`（DataStore 的失败面就是 IO），不再吞掉 `RuntimeException`。

### 4.4 契约测试的 cwd 是模块目录，且会扫到自己

两条与上一批同源的坑：

- `File("brailleback/src")` 在 cwd=`…\brailleback` 时找不到 → 加 `projectDir()`（三个候选路径 + `isDirectory`）；
- 扫描范围用 `brailleback/src` / `service/src`，**不要**用 `brailleback` —— 否则 `test/` 下的契约测试
  自己那句 `contains("getDefaultSharedPreferences(")` 会把自己判成违规。

## 5. 验证

| 项 | 结果 |
|---|---|
| `:brailleback:assembleDebug` + `:service:assembleDebug` | BUILD SUCCESSFUL（3m41s） |
| 单元测试 | brailleback **553** 例（含新增 `SettingsStoreContractTest` 4 例）、service **172** 例，全绿 |
| lint | `:brailleback:lintDebug` + `:service:lintDebug` 无新增问题 |
| detekt | `detektCheck` 通过（重构后 0 weighted issue） |
| 仪器测试（AVD `brailleback_api35`） | **25 例全绿**（原 19 + 新增 `SettingsStoreInstrumentedTest` 6 例） |

新增仪器测试覆盖的六件事：`apply` 后立刻同步读可见 / `StringSet` 往返且返回副本 /
跨进程键真的落进旧文件 / 旧值一次性迁移并从旧文件消失 / 写入通知监听器 / 类型不符回退默认值不崩。

## 6. 回滚

- 代码回滚：`SettingsStore.get()` → `PreferenceManager.getDefaultSharedPreferences()`（19 处，机械替换）。
- 数据：DataStore 值是**迁移时从旧文件搬过去的**，旧文件在迁移后被清掉这批键，所以回滚到旧实现
  会读不到用户设置。真需要回滚时把 `braille_settings.preferences_pb` 转回 xml 再装旧包。

## 7. 后续

- 状态管理这一维从「SharedPreferences 136 行 / DataStore 0」变成「DataStore 唯一写源 + 快照」，
  但**没有**改成 Flow 驱动 UI（那样要动全部设置页）。若哪天要做「设置项跨界面实时联动」，
  `SettingsStore` 已经有 `dataStore` 可用，加一条 `settingsFlow(key)` 即可，不必再换存储层。
- `TranslatorService` 的 `chinese_segmentation` 仍然是旧文件单键，跨进程一致性的边界就这一条，
  改动时看 `SettingsStoreContractTest` 的两个用例即可。
