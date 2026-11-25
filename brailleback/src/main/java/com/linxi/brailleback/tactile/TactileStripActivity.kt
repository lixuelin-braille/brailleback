/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 为什么需要它：
 * 纸面触觉图走 BMP 落盘链路（TactileExportActivity），本页是另一条出口——图片切成
 * 「封面屏 → N 个图形带页 → 封底屏」三段，逐屏送到连着的盲文点显器上触摸浏览。
 * 纯逻辑在同包的 Session / Pager / Display：本 Activity 只做界面接线：选图、收参数、
 * 后台线程转换、翻页播报与状态提示。
 *
 * 三段式约定：
 * - 封面 / 封底是盲文文字屏，各占整屏，不占图形行；图形页里不夹任何文字；
 * - 页码 / 带号只走 TTS 播报，绝不写进点显字节，也不为页码预留显示格；
 * - 封面 / 封底文字由核心层生成，本页不自己造封面文案。
 *
 * 无障碍是硬要求：
 * - 每个可点控件都有文字标签 / 角色 / 状态描述，不靠颜色或位置提示；
 * - 每次翻页都走 announceForAccessibility 播报，状态行同时进 live region；
 * - 参数项用单选行与「减 1 / 加 1」按钮，TalkBack 滑动 + 双击即可操作；
 * - 点显未连接时不崩、不弹错，只在状态行提示「仅显示页码」，点位下发由 Display 层丢帧兜底。
 *
 * 生命周期：onCreate 建 TactileStripDisplay（内部自绑点显服务），onDestroy 关闭解绑；
 * 图片解码与切片全部在 IO 线程，主线程只做状态刷新，不在主线程解大图。
 */

package com.linxi.brailleback.tactile

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.OpenableColumns
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.googlecode.eyesfree.braille.display.BrailleInputEvent
import com.googlecode.eyesfree.braille.display.Display
import com.googlecode.eyesfree.braille.display.DisplayClient
import com.linxi.brailleback.BuildConfig
import com.linxi.brailleback.BrailleBackService
import com.linxi.brailleback.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** DEBUG 阶段日志统一 TAG；release 下所有打点分支不可达，不产生日志。 */
internal const val DEBUG_TAG = "TactileStrip"

class TactileStripActivity : AppCompatActivity(),
    Display.OnConnectionStateChangeListener {

    /** 点显薄封装：持有真正下发点位的 DisplayClient；关闭时统一解绑。 */
    internal var display: TactileStripDisplay? = null

    /**
     * 只读连接状态观察客户端：Display 封装内部的 client 不外露，本 Activity 需要知道
     * 「连上没有」才能写状态行；点位下发仍走 [display]，本客户端只听状态、不发点位。
     */
    internal var observeClient: DisplayClient? = null

    internal var sourceUri: Uri? by mutableStateOf(null)
    internal var sourceSummary: String? by mutableStateOf(null)

    internal var cellsPerLine by mutableStateOf(40)

    /**
     * 方数是否跟随点显器。默认跟随：连上后按设备上报的文本格数换行，用户不必先猜自己机器几方；
     * 只有手动点了「高级 → 方数档位」才关闭跟随（[onCellsChange]）。
     */
    internal var cellsFollowDisplay by mutableStateOf(true)
    internal var linesPerScreen by mutableStateOf(1)
    internal var dotsPerCell by mutableStateOf(8)

    /** 线宽归一三态，默认自动；仅影响多行三段式成书链路。 */
    internal var normalizerMode by mutableStateOf(StockNormalizerMode.AUTO)

    /** G1 多行封面分层，默认关；单行设备忽略。 */
    internal var g1SplitCover by mutableStateOf(false)

    internal var converting by mutableStateOf(false)
    internal var status by mutableStateOf<String?>(null)
    internal var book: StripBook? by mutableStateOf(null)
    internal var pager by mutableStateOf<TactileStripPager?>(null)
    internal var displayConnected by mutableStateOf(false)

    /** 单行设备的视口模式；非空时三段式 [book]/[pager] 置空，两套路径互不干扰。 */
    internal var viewport: TactileViewport? by mutableStateOf(null)

    /** 当前是否停在独立图例屏；图例不占图形页。 */
    internal var vpLegend by mutableStateOf(false)

    /** 图例屏当前页码（0 基）。单行设备一行一屏，候选一多就必须翻页。 */
    internal var vpLegendPage by mutableStateOf(0)

    /** 细节态是否已向无障碍服务申请独占点显器（冻结服务侧文字输出）；只在真冻上过才交还。 */
    internal var holdingDisplay = false

    /**
     * 点显器自带按键监听：视口态下这四个方向 / 图例优先由设备按键触发，屏幕按钮仍保留作备用。
     * 屏蔽表见 [TactileDisplayKey]；未映射的命令原样放过。
     */
    private val displayKeyListener = object : Display.OnInputEventListener {
        override fun onInputEvent(inputEvent: BrailleInputEvent) {
            // 回调发生在客户端 handler 线程；UI 状态一律回主线程改。
            runOnUiThread { onDisplayKey(inputEvent.command) }
        }
    }

    /** 图例屏的盲文行（IO 线程翻译好，主线程只读）；空列表出空图例。 */
    internal var legendBrailleLines: List<String> = emptyList()

    /** 边界短震封装；无马达 / 无权限时取 null，调用处静默降级。 */
    internal var vibrator: Vibrator? = null

    /** 选图：系统文档选择器，只认图片。取消时回调为 null，静默处理。 */
    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) onImagePicked(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 标题栏交给 Compose 的 TopAppBar，避免两个标题栏叠加、读屏重复朗读。
        supportActionBar?.hide()

        display = TactileStripDisplay(this).also {
            it.setOnInputEventListener(displayKeyListener)
        }
        vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator
        observeClient = DisplayClient(this).apply {
            setOnConnectionStateChangeListener(this@TactileStripActivity)
        }

        // [DEBUG-HOOK] 仅 DEBUG 构建：Intent extra `image_file`（绝对路径）直接读图跳过 SAF；
        // release 构建 BuildConfig.DEBUG=false，此段不可达。只接读取支路，不改既有选图 /
        // 解码 / 分页 / 视口 / 会话逻辑。
        if (BuildConfig.DEBUG) {
            intent.getStringExtra("image_file")?.takeIf { it.isNotBlank() }?.let { path ->
                runCatching {
                    val file = File(path)
                    if (file.isFile) onImagePicked(Uri.fromFile(file))
                }
            }

            // [DEBUG-HOOK] 参数档位注入：cells_per_line / lines_per_screen / dots /
            // normalizer / g1_split_cover，合法值覆盖既有参数状态，非法值回落默认。
            val debugExtras = mapOf(
                "cells_per_line" to extraText("cells_per_line"),
                "lines_per_screen" to extraText("lines_per_screen"),
                "dots" to extraText("dots"),
                "normalizer" to extraText("normalizer"),
                "g1_split_cover" to extraText("g1_split_cover"),
            )
            TactileDebugParams.parse(debugExtras)?.let { applyDebugParams(it) }
        }

        setContent {
            val labels = labels()
            val colorScheme = if (isSystemInDarkTheme()) {
                darkColorScheme()
            } else {
                lightColorScheme()
            }
            MaterialTheme(colorScheme = colorScheme) {
                StripScreen(
                    labels = labels,
                    sourceSummary = sourceSummary,
                    cellsPerLine = cellsPerLine,
                    cellsFollowDisplay = cellsFollowDisplay,
                    linesPerScreen = linesPerScreen,
                    dotsPerCell = dotsPerCell,
                    normalizerMode = normalizerMode,
                    g1SplitCover = g1SplitCover,
                    converting = converting,
                    status = status,
                    currentKind = pager?.current?.kind,
                    canPage = pager != null,
                    canPrev = pager?.canPrev() == true,
                    canNext = pager?.canNext() == true,
                    viewportActive = viewport != null,
                    detailMode = viewport?.isOverview == false,
                    displayConnected = displayConnected,
                    vpIsOverview = viewport?.isOverview == true,
                    vpIsLegend = vpLegend,
                    // 图例行数超一屏才给翻页按钮；一屏装得下就不多摆两个空按钮。
                    vpLegendMultiPage = viewport?.let { legendPageCount(it) > 1 } == true,
                    onFirstShown = { announce(labels.firstHint) },
                    onPickImage = { pickImageLauncher.launch(arrayOf("image/*")) },
                    onCellsChange = ::onCellsChange,
                    onCellsFollowChange = ::onCellsFollow,
                    onLinesDecrease = { onLinesChange(-1) },
                    onLinesIncrease = { onLinesChange(1) },
                    onDotsChange = ::onDotsChange,
                    onNormalizerChange = ::onNormalizerChange,
                    onG1Change = ::onG1Change,
                    onFirst = ::goFirst,
                    onPrev = ::goPrev,
                    onNext = ::goNext,
                    onLast = ::goLast,
                    onExit = { finish() },
                    onRepick = { pickImageLauncher.launch(arrayOf("image/*")) },
                    onBack = { finish() },
                    onPanLeft = { vpNavigate(-1, 0) },
                    onPanRight = { vpNavigate(1, 0) },
                    onPanUp = { vpNavigate(0, -1) },
                    onPanDown = { vpNavigate(0, 1) },
                    onEnterDetail = ::vpEnterDetail,
                    onBackOverview = ::vpBackOverview,
                    onOpenLegend = ::vpOpenLegend,
                    onCloseLegend = ::vpCloseLegend,
                    onLegendPrev = { vpLegendNavigate(-1) },
                    onLegendNext = { vpLegendNavigate(1) },
                )
            }
        }
    }

    /**
     * 退到后台就交还点显器：否则服务一直冻着，用户切到别的应用摸到的还是上一屏点位。
     */
    override fun onStop() {
        releaseDisplay()
        super.onStop()
    }

    override fun onDestroy() {
        releaseDisplay()
        // 点显客户端解绑；观察客户端只听状态，一并释放。
        display?.setOnInputEventListener(null)
        display?.close()
        display = null
        observeClient?.shutdown()
        observeClient = null
        vibrator = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 选图与转换

    private fun onImagePicked(uri: Uri) {
        sourceSummary = describeUri(uri)
        sourceUri = uri
        rebuild()
    }

    /** 查询文件名与大小摘要；provider 不支持查询时回落到 URI 末段，不阻断选图。 */
    private fun describeUri(uri: Uri): String {
        var name: String? = null
        var sizeBytes: Long = -1
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIdx >= 0) name = cursor.getString(nameIdx)
                    if (sizeIdx >= 0) sizeBytes = cursor.getLong(sizeIdx)
                }
            }
        } catch (e: Exception) {
            // 查询失败按无名处理，摘要退让到 URI 末段。
        }
        val fallback = uri.lastPathSegment ?: ""
        val label = name?.takeIf { it.isNotBlank() } ?: fallback
        return if (sizeBytes >= 0) {
            getString(R.string.tactile_strip_image_picked_size, label, formatSize(sizeBytes))
        } else {
            getString(R.string.tactile_strip_image_picked, label)
        }
    }

    private fun formatSize(bytes: Long): String {
        val kb = bytes / 1024.0
        return if (kb >= 1024) String.format("%.1f MB", kb / 1024.0) else "${kb.toInt()} KB"
    }

    /** 后台解码 + 排版；单行设备进视口模式，多行设备保留三段式 Book。失败不刷新上一版。 */
    private fun rebuild() {
        val uri = sourceUri ?: return
        converting = true
        showStatus(getString(R.string.tactile_strip_converting))
        lifecycleScope.launch(Dispatchers.IO) {
            if (BuildConfig.DEBUG) {
                Log.d(
                    DEBUG_TAG,
                    "rebuild start: uri=${uri.lastPathSegment} cells=$cellsPerLine " +
                        "lines=$linesPerScreen dots=$dotsPerCell norm=$normalizerMode g1=$g1SplitCover",
                )
            }
            val spec = ImageStripEncoder.Spec.forDisplay(cellsPerLine, linesPerScreen, dotsPerCell)
            val outcome: Any? = try {
                if (spec.linesPerScreen == 1) {
                    if (BuildConfig.DEBUG) Log.d(DEBUG_TAG, "route=viewport")
                    buildViewport(uri, spec)
                } else {
                    if (BuildConfig.DEBUG) Log.d(DEBUG_TAG, "route=session")
                    TactileStripSession.build(
                        this@TactileStripActivity, uri, spec,
                        g1SplitCover = g1SplitCover,
                        normalizerMode = normalizerMode,
                    )
                }
            } catch (e: Exception) {
                // DEBUG 下记录异常类型 / message / stacktrace；最后一条 stage 打点即失败阶段。
                if (BuildConfig.DEBUG) {
                    Log.e(
                        DEBUG_TAG,
                        "rebuild failed: type=${e.javaClass.name} msg=${e.message}",
                        e,
                    )
                }
                null
            }
            withContext(Dispatchers.Main) {
                converting = false
                when (outcome) {
                    is StripBook -> {
                        if (BuildConfig.DEBUG) Log.d(DEBUG_TAG, "bind=book pages=${outcome.pages.size}")
                        book = outcome
                        pager = TactileStripPager(outcome)
                        viewport = null
                        vpLegend = false
                        legendBrailleLines = emptyList()
                        renderCurrent(announce = true)
                    }
                    is TactileViewport -> {
                        if (BuildConfig.DEBUG) Log.d(DEBUG_TAG, "bind=viewport")
                        viewport = outcome
                        book = null
                        pager = null
                        vpLegend = false
                        renderCurrent(announce = true)
                    }
                    else -> showStatus(getString(R.string.tactile_strip_fail_decode))
                }
            }
        }
    }

    /** 单行视口链路：读图 → 全局点阵 → 视口；不切三段式封面 / 封底。 */
    private fun buildViewport(
        uri: Uri,
        spec: ImageStripEncoder.Spec,
    ): TactileViewport {
        if (BuildConfig.DEBUG) Log.d(DEBUG_TAG, "stage=load viewport")
        val gray = TactileStripSource.loadGrayImage(this, uri)
        if (BuildConfig.DEBUG) {
            Log.d(DEBUG_TAG, "stage=decode viewport gray=${gray.width}x${gray.height}")
        }
        val cols = spec.dotColumns
        val rows = maxOf(
            1,
            Math.round(cols.toFloat() * gray.height.toFloat() / gray.width).toInt(),
        )
        val ink = ImageStripEncoder.dotGrid(gray, cols, rows, spec.threshold)
        val vp = TactileViewport(ink, cols, rows, spec)
        // 图例为辅助屏：检测 / 翻译失败不阻塞图形视口，图例回退空屏。
        try {
            prepareLegend(vp)
        } catch (e: Exception) {
            legendBrailleLines = emptyList()
        }
        return vp
    }

    /** 检测图内标注候选位置，合成源行并经翻译桥翻成盲文；只报位置，不识别文字内容。 */
    private fun prepareLegend(vp: TactileViewport) {
        val boxes = TactileLabelDetector.detect(
            vp.ink, vp.cols, vp.rows,
            TactileLabelDetector.Config(enabled = true),
        )
        val source = TactileLegendPage.labelSourceLines(
            boxes, vp.bandRows, vp.spec.cellsPerLine,
        )
        val bridge = TactileStripCaptionBridge(this)
        try {
            legendBrailleLines = source.map { bridge.toBraille(it) }
        } finally {
            bridge.close()
        }
    }

    // ------------------------------------------------------------------ 参数变更

    /** DEBUG 专用：兼容 `--es` 字符串与 `--ei` 整型两种注入方式，归一为文本交解析器。 */
    private fun extraText(key: String): String? =
        intent.getStringExtra(key)
            ?: intent.getIntExtra(key, -1).takeIf { it >= 0 }?.toString()

    /** DEBUG 专用：把解析出的合法档位写进既有参数状态模型；未选图时不触发 rebuild。 */
    private fun applyDebugParams(params: TactileDebugParams) {
        params.cellsPerLine?.let {
            // debug 注入的档位等同于手动指定，关掉跟随，避免被设备值覆盖。
            cellsFollowDisplay = false
            cellsPerLine = it
        }
        params.linesPerScreen?.let { linesPerScreen = it }
        params.dots?.let { dotsPerCell = it }
        params.normalizer?.let { normalizerMode = it }
        params.g1SplitCover?.let { g1SplitCover = it }
        if (BuildConfig.DEBUG) {
            Log.d(
                DEBUG_TAG,
                "debug params applied: cells=$cellsPerLine lines=$linesPerScreen " +
                    "dots=$dotsPerCell normalizer=$normalizerMode g1=$g1SplitCover",
            )
        }
    }

    private fun onCellsChange(value: Int) {
        // 手动点档位即退出「跟随点显器」，否则下次连接又被设备值覆盖掉。
        cellsFollowDisplay = false
        if (cellsPerLine == value) return
        cellsPerLine = value
        // 图片已选的前提下重转并回到封面；未选图只记参数，等选图后生效。
        if (sourceUri != null) rebuild()
    }

    /** 高级里的「跟随点显器」：切回自动，并按当前设备值立即重算一次。 */
    private fun onCellsFollow() {
        if (cellsFollowDisplay) return
        cellsFollowDisplay = true
        applyDisplayCells()?.let { if (sourceUri != null) rebuild() }
    }

    /**
     * 按点显器上报的文本格数更新方数；返回是否真的改了值（未连接 / 值相同返回 false）。
     * 行数不在这里跟随：客户端 API（[com.googlecode.eyesfree.braille.display.BrailleDisplayProperties]）
     * 只暴露文本格数，行数没有任何设备上报到这一层，所以行数仍由「减 1 / 加 1」决定。
     */
    private fun applyDisplayCells(): Boolean? {
        if (!cellsFollowDisplay) return null
        val cells = observeClient?.displayProperties?.numTextCells ?: 0
        if (cells <= 0 || cells == cellsPerLine) return null
        cellsPerLine = cells
        return true
    }

    private fun onLinesChange(delta: Int) {
        val next = (linesPerScreen + delta).coerceIn(MIN_LINES, MAX_LINES)
        if (next == linesPerScreen) return
        linesPerScreen = next
        if (sourceUri != null) rebuild()
    }

    private fun onDotsChange(value: Int) {
        if (dotsPerCell == value) return
        dotsPerCell = value
        if (sourceUri != null) rebuild()
    }

    private fun onNormalizerChange(mode: StockNormalizerMode) {
        if (normalizerMode == mode) return
        normalizerMode = mode
        if (sourceUri != null) rebuild()
    }

    private fun onG1Change(enabled: Boolean) {
        if (g1SplitCover == enabled) return
        g1SplitCover = enabled
        if (sourceUri != null) rebuild()
    }


    /** 点显连接状态回调：只刷新状态行文案，不重复下发点位。 */
    override fun onConnectionStateChanged(state: Int) {
        displayConnected = state == Display.STATE_CONNECTED
        // 断线后冻结没意义，清标记；重连而图形页还在时由 syncDisplayHold 重新申请。
        if (!displayConnected) {
            holdingDisplay = false
        }
        syncDisplayHold()
        // 连上就按设备的方数换行（跟随模式），再把当前屏重画一遍。
        if (applyDisplayCells() == true && sourceUri != null) rebuild()
        if (pager != null) renderCurrent(announce = false)
    }

    /** 音量键 / DPAD 单手操作：视口态四向平移，图例态翻图例页，三段式态上下翻页。 */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val vpActive = viewport != null
        // 图例态下手机上能按的键也要能翻页，不然只有一个「返回图形」按钮就没法看后面的候选。
        if (vpActive && vpLegend) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    vpLegendNavigate(1)
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    vpLegendNavigate(-1)
                    return true
                }
            }
        }
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (vpActive) vpNavigate(0, 1) else goNext()
                return true
            }
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_DPAD_UP -> {
                if (vpActive) vpNavigate(0, -1) else goPrev()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (vpActive) vpNavigate(1, 0) else goNext()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (vpActive) vpNavigate(-1, 0) else goPrev()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ------------------------------------------------------------------ 播报与状态

    internal fun showStatus(message: String) {
        status = message
        announce(message)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    /** 只做一次语音播报，不改界面状态、不弹 Toast（首屏提示用）。 */
    internal fun announce(message: String) {
        window.decorView.announceForAccessibility(message)
    }

}
