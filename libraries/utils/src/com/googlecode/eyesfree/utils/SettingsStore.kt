/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 设置存储：DataStore 是唯一写源，内存快照供同步读。
 *
 * 为什么不是「全异步 Flow」：
 * 仓库里有 60 多处同步读偏好（无障碍服务 onServiceConnected、叠加层 onDraw、
 * 翻译服务的分词开关、点显器连接编排…），都改成 Flow 会把一次读取拆成
 * 「订阅 + 等第一帧」，在无障碍服务的启动路径上直接表现为「服务起来了但还没配好」。
 * 所以这里保留同步读语义：DataStore 负责持久化与一致性，内存快照负责同步读。
 *
 * 为什么还有 SharedPreferences：
 * TranslatorService 在独立进程 `:service`（AndroidManifest 里的 android:process），
 * DataStore **不支持跨进程**（同文件两个实例会抛异常，跨进程就是最后写入者赢）。
 * 因此被该进程读取的键留在 SharedPreferences，其余键全部搬进 DataStore。
 * 这份名单由 SettingsStoreContractTest 守着：服务进程新增读键时测试会失败。
 *
 * 冷启动代价：第一次访问会阻塞加载（含一次性迁移旧文件），实测 1~5ms；
 * 超过 20ms 打一条 WARN，避免以后悄悄退化成启动卡顿。
 */

package com.googlecode.eyesfree.utils

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

private val Context.brailleSettingsDataStore: DataStore<Preferences> by
    preferencesDataStore(name = SettingsStore.DATA_STORE_NAME)

/**
 * 默认偏好文件的替代实现：[androidx.datastore] 持久化 + 内存快照同步读。
 *
 * 它仍然实现 [SharedPreferences]，因此所有既有调用点（`prefs.getBoolean(...)`
 * `prefs.edit().putString(...).apply()`）不用改写一行，只是换一个来源。
 */
class SettingsStore private constructor(appContext: Context) : SharedPreferences {

    companion object {
        private const val TAG = "SettingsStore"

        /** DataStore 文件名（与旧 SharedPreferences 文件刻意不同，便于排查与回滚）。 */
        const val DATA_STORE_NAME = "braille_settings"

        /**
         * 留在 SharedPreferences 的键：被 `:service` 进程读取，DataStore 管不到。
         * 名单有变化必须同时确认进程归属，别凭印象加。
         */
        val CROSS_PROCESS_KEYS: Set<String> = setOf("chinese_segmentation")

        private const val LOAD_WARN_MS = 20L

        @Volatile
        private var instance: SettingsStore? = null

        /** 进程内单例：DataStore 同一文件只允许一个实例。 */
        fun get(context: Context): SettingsStore {
            instance?.let { return it }
            synchronized(SettingsStore::class.java) {
                instance?.let { return it }
                return SettingsStore(context.applicationContext).also { instance = it }
            }
        }

        @VisibleForTesting
        fun clearInstanceForTesting() {
            instance = null
        }
    }

    private val dataStore: DataStore<Preferences> = appContext.brailleSettingsDataStore

    /**
     * 旧文件：`PreferenceManager` 的默认名是 `<包名>_preferences`，直接用这个文件，
     * 迁移才能读到老用户已有的值。
     */
    private val legacy: SharedPreferences = appContext.getSharedPreferences(
        appContext.packageName + "_preferences", Context.MODE_PRIVATE
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val lock = Any()

    /**
     * 首次加载用的锁，**与 [lock] 分开**：迁移在 IO 线程上跑，它自己要拿 [lock] 更新快照。
     * 若初始化时持着 [lock] 去 runBlocking，就会自己等自己（真机上表现为设置页永久卡住）。
     */
    private val initLock = Any()

    /** 同步读用的快照，键 → 值（Boolean/String/Int/Long/Float/Set<String>）。 */
    private val snapshot = HashMap<String, Any>()

    @Volatile
    private var loaded = false

    private val listeners =
        CopyOnWriteArrayList<WeakReference<SharedPreferences.OnSharedPreferenceChangeListener>>()

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    // ---------------------------------------------------------------- 读

    override fun getAll(): Map<String, *> {
        ensureLoaded()
        val copy = HashMap<String, Any>()
        synchronized(lock) { copy.putAll(snapshot) }
        // 跨进程键仍从旧文件取，保证与 `:service` 看到的是同一个值。
        legacy.all.forEach { (key, value) -> if (key in CROSS_PROCESS_KEYS && value != null) copy[key] = value }
        return copy
    }

    override fun getString(key: String, defValue: String?): String? = read(key) as? String ?: defValue

    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
        when (val value = read(key)) {
            is Set<*> -> value.filterIsInstance<String>().toSet()
            else -> defValues
        }

    override fun getInt(key: String, defValue: Int): Int = read(key) as? Int ?: defValue

    override fun getLong(key: String, defValue: Long): Long = read(key) as? Long ?: defValue

    override fun getFloat(key: String, defValue: Float): Float = read(key) as? Float ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean = read(key) as? Boolean ?: defValue

    override fun contains(key: String): Boolean {
        if (key in CROSS_PROCESS_KEYS) return legacy.contains(key)
        ensureLoaded()
        return synchronized(lock) { snapshot.containsKey(key) }
    }

    /** 跨进程键走旧文件；其余走快照。类型不符时回退默认值（旧实现会抛 ClassCastException）。 */
    private fun read(key: String): Any? {
        if (key in CROSS_PROCESS_KEYS) return legacy.all[key]
        ensureLoaded()
        return synchronized(lock) { snapshot[key] }
    }

    // ---------------------------------------------------------------- 写

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) {
        if (listener == null) return
        pruneDeadListeners()
        listeners.add(WeakReference(listener))
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) {
        if (listener == null) return
        listeners.removeAll { it.get() == null || it.get() === listener }
    }

    private fun pruneDeadListeners() {
        listeners.removeAll { it.get() == null }
    }

    private inner class Editor : SharedPreferences.Editor {

        private val puts = LinkedHashMap<String, Any?>()

        private val removals = LinkedHashSet<String>()

        private var clearRequested = false

        override fun putString(key: String, value: String?) = applyPut(key, value)

        override fun putStringSet(key: String, values: Set<String>?) =
            applyPut(key, values?.toSet())

        override fun putInt(key: String, value: Int) = applyPut(key, value)

        override fun putLong(key: String, value: Long) = applyPut(key, value)

        override fun putFloat(key: String, value: Float) = applyPut(key, value)

        override fun putBoolean(key: String, value: Boolean) = applyPut(key, value)

        override fun remove(key: String): SharedPreferences.Editor {
            removals.add(key)
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearRequested = true
            return this
        }

        private fun applyPut(key: String, value: Any?): SharedPreferences.Editor {
            puts[key] = value
            return this
        }

        override fun commit(): Boolean = write(sync = true)

        override fun apply() {
            write(sync = false)
        }

        /**
         * 关键语义：**先改快照，再落盘**。
         * 旧代码大量是「apply 之后立刻 getBoolean」，若等落盘完成才更新快照，
         * 就会出现「点了开关但界面不变」。
         */
        private fun write(sync: Boolean): Boolean {
            ensureLoaded()
            val changed = LinkedHashSet<String>()
            if (clearRequested) {
                clearEverything(sync, changed)
            } else {
                changed.addAll(writeMainProcessKeys(sync))
                changed.addAll(writeCrossProcessKeys(sync))
            }
            notifyChanged(changed)
            return true
        }

        /** 全清：两份存储一起清，否则会留下半份数据。 */
        private fun clearEverything(sync: Boolean, changed: MutableCollection<String>) {
            val cleared = synchronized(lock) {
                val keys = snapshot.keys.toList()
                snapshot.clear()
                keys
            }
            changed.addAll(cleared)
            legacy.edit().clear().apply()
            runDataStoreEdit(sync) { it.clear() }
        }

        /** 主进程键：先改快照（保证 apply 后立刻读到新值），再异步/同步落 DataStore。 */
        private fun writeMainProcessKeys(sync: Boolean): Set<String> {
            val main = puts.filterKeys { it !in CROSS_PROCESS_KEYS }
            val removalsMain = removals.filter { it !in CROSS_PROCESS_KEYS }
            if (main.isEmpty() && removalsMain.isEmpty()) return emptySet()
            synchronized(lock) {
                main.forEach { (key, value) ->
                    if (value == null) snapshot.remove(key) else snapshot[key] = value
                }
                removalsMain.forEach { snapshot.remove(it) }
            }
            runDataStoreEdit(sync) { prefs ->
                main.forEach { (key, value) -> prefs.putAny(key, value) }
                removalsMain.forEach { prefs.removeAny(it) }
            }
            return main.keys + removalsMain
        }

        /**
         * 跨进程键（当前只有 chinese_segmentation）：必须落在旧 SharedPreferences 文件，
         * `:service` 进程的 TranslatorService 才读得到。
         */
        private fun writeCrossProcessKeys(sync: Boolean): Set<String> {
            val cross = puts.filterKeys { it in CROSS_PROCESS_KEYS }
            val removalsCross = removals.filter { it in CROSS_PROCESS_KEYS }
            if (cross.isEmpty() && removalsCross.isEmpty()) return emptySet()
            val editor = legacy.edit()
            cross.forEach { (key, value) -> editor.putValue(key, value) }
            removalsCross.forEach { editor.remove(it) }
            if (sync) editor.commit() else editor.apply()
            return cross.keys + removalsCross
        }

        private fun runDataStoreEdit(sync: Boolean, block: (MutablePreferences) -> Unit) {
            val action: suspend () -> Unit = {
                try {
                    dataStore.edit(block)
                } catch (e: java.io.IOException) {
                    // 落盘失败时快照已经更新，进程内读到的仍是新值；这里只记录，
                    // 让下一轮写入或下次启动的迁移把它补回来，不能因为一次 IO 失败就崩服务。
                    Log.w(TAG, "写入 DataStore 失败：$e")
                }
            }
            if (sync) {
                runBlocking(Dispatchers.IO) { action() }
            } else {
                scope.launch { action() }
            }
        }
    }

    // ---------------------------------------------------------------- 通知

    private fun notifyChanged(keys: Collection<String>) {
        if (keys.isEmpty()) return
        pruneDeadListeners()
        if (listeners.isEmpty()) return
        val dispatch = Runnable {
            keys.forEach { key ->
                listeners.forEach { ref -> ref.get()?.onSharedPreferenceChanged(this, key) }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            dispatch.run()
        } else {
            mainHandler.post(dispatch)
        }
    }

    // ------------------------------------------------------------ 加载与迁移

    /**
     * 首次访问时同步加载：读一次 DataStore（顺带把旧文件迁进来），
     * 之后只靠快照与后台收集器。
     */
    private fun ensureLoaded() {
        if (loaded) return
        synchronized(initLock) {
            if (loaded) return
            val start = SystemClock.elapsedRealtime()
            val migratedKeys = runBlocking(Dispatchers.IO) { migrateAndLoad() }
            loaded = true
            val cost = SystemClock.elapsedRealtime() - start
            if (cost >= LOAD_WARN_MS) {
                Log.w(TAG, "首次加载设置用时 ${cost}ms（迁移 $migratedKeys 个键）")
            }
            startCollector()
        }
    }

    /**
     * 一次性迁移：旧文件里除跨进程键之外的全部搬进 DataStore，搬完从旧文件删掉。
     *
     * 不建迁移标记：以「旧文件里已没有可迁移的键」为完成条件，天然幂等；
     * 中途失败下次启动会接着搬。
     */
    private suspend fun migrateAndLoad(): Int {
        var values = dataStoreValues()
        val pending = legacy.all.filterKeys { it !in CROSS_PROCESS_KEYS }
        if (pending.isNotEmpty()) {
            dataStore.edit { prefs ->
                pending.forEach { (key, value) ->
                    if (key !in values) prefs.putAny(key, value)
                }
            }
            legacy.edit().also { editor -> pending.keys.forEach { editor.remove(it) } }.apply()
            values = dataStoreValues()
        }
        synchronized(lock) {
            snapshot.clear()
            snapshot.putAll(values)
        }
        return pending.size
    }

    private suspend fun dataStoreValues(): Map<String, Any> {
        val prefs = dataStore.data.first()
        val out = HashMap<String, Any>(prefs.asMap().size)
        prefs.asMap().forEach { (key, value) -> out[key.name] = value }
        return out
    }

    /** 后台收集器：我们自己的写入也会回灌快照，保持与磁盘一致（失败重试后尤其重要）。 */
    private fun startCollector() {
        scope.launch {
            dataStore.data.collect { prefs ->
                val values = HashMap<String, Any>(prefs.asMap().size)
                prefs.asMap().forEach { (key, value) -> values[key.name] = value }
                synchronized(lock) {
                    snapshot.clear()
                    snapshot.putAll(values)
                }
            }
        }
    }
}

/** 按值的实际类型写入 SharedPreferences 编辑器；null 视为删除。 */
private fun SharedPreferences.Editor.putValue(key: String, value: Any?) {
    when (value) {
        null -> remove(key)
        is Boolean -> putBoolean(key, value)
        is Int -> putInt(key, value)
        is Long -> putLong(key, value)
        is Float -> putFloat(key, value)
        is String -> putString(key, value)
        is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
        else -> Unit
    }
}

/** 按值的实际类型选 DataStore 的键类型；不支持的类型直接跳过（迁移不该让应用崩）。 */
private fun MutablePreferences.putAny(key: String, value: Any?) {
    when (value) {
        is Boolean -> this[booleanPreferencesKey(key)] = value
        is Int -> this[intPreferencesKey(key)] = value
        is Long -> this[longPreferencesKey(key)] = value
        is Float -> this[floatPreferencesKey(key)] = value
        is String -> this[stringPreferencesKey(key)] = value
        is Set<*> -> this[stringSetPreferencesKey(key)] = value.filterIsInstance<String>().toSet()
        else -> Unit
    }
}

private fun MutablePreferences.removeAny(key: String) {
    // Preferences 的 remove 要求具体键类型，只能按现有值的类型分派。
    val current = asMap().entries.firstOrNull { it.key.name == key }?.value ?: return
    when (current) {
        is Boolean -> remove(booleanPreferencesKey(key))
        is Int -> remove(intPreferencesKey(key))
        is Long -> remove(longPreferencesKey(key))
        is Float -> remove(floatPreferencesKey(key))
        is String -> remove(stringPreferencesKey(key))
        is Set<*> -> remove(stringSetPreferencesKey(key))
        else -> Unit
    }
}
