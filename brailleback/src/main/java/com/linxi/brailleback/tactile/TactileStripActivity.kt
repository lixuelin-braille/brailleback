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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.googlecode.eyesfree.braille.display.Display
import com.googlecode.eyesfree.braille.display.DisplayClient
import com.linxi.brailleback.BuildConfig
import com.linxi.brailleback.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 可选一行方数：点显器常见 20 / 30 / 40 方。 */
private val CELL_CHOICES = listOf(20, 30, 40)

/** 点显屏行数取值范围：单行设备到多行列屏。 */
private const val MIN_LINES = 1
private const val MAX_LINES = 8

/** DEBUG 阶段日志统一 TAG；release 下所有打点分支不可达，不产生日志。 */
private const val DEBUG_TAG = "TactileStrip"

/** 一次性收集好的文案，界面不直接碰 Context，便于读屏线性朗读。 */
private data class StripLabels(
    val title: String,
    val back: String,
    val firstHint: String,
    val pickImage: String,
    val noImage: String,
    val imagePicked: String,
    val imagePickedSize: String,
    val sectionCells: String,
    val cellsDesc: String,
    val cellsChoice: List<String>,
    /** 方数跟随点显器的只读说明（带当前方数），默认展示在主界面。 */
    val cellsFollow: String,
    val advanced: String,
    val advancedHint: String,
    val sectionLines: String,
    val linesValue: String,
    val decrease: String,
    val increase: String,
    val sectionDots: String,
    val dots6: String,
    val dots8: String,
    val prev: String,
    val next: String,
    val first: String,
    val last: String,
    val coverHint: String,
    val statusCover: String,
    val statusGraphic: String,
    val statusBack: String,
    val notConnected: String,
    val connected: String,
    val failDecode: String,
    val needImage: String,
    val selected: String,
    val unselected: String,
    val actionRepick: String,
    val vpEnterDetail: String,
    val vpBackOverview: String,
    val vpLegend: String,
    val vpLegendBack: String,
    val vpPanLeft: String,
    val vpPanRight: String,
    val vpPanUp: String,
    val vpPanDown: String,
    val normSection: String,
    val normDesc: String,
    val normAuto: String,
    val normOff: String,
    val normForce: String,
    val g1Split: String,
    val g1Desc: String,
)

class TactileStripActivity : AppCompatActivity(),
    Display.OnConnectionStateChangeListener {

    /** 点显薄封装：持有真正下发点位的 DisplayClient；关闭时统一解绑。 */
    private var display: TactileStripDisplay? = null

    /**
     * 只读连接状态观察客户端：Display 封装内部的 client 不外露，本 Activity 需要知道
     * 「连上没有」才能写状态行；点位下发仍走 [display]，本客户端只听状态、不发点位。
     */
    private var observeClient: DisplayClient? = null

    private var sourceUri: Uri? by mutableStateOf(null)
    private var sourceSummary: String? by mutableStateOf(null)

    private var cellsPerLine by mutableStateOf(40)

    /**
     * 方数是否跟随点显器。默认跟随：连上后按设备上报的文本格数换行，用户不必先猜自己机器几方；
     * 只有手动点了「高级 → 方数档位」才关闭跟随（[onCellsChange]）。
     */
    private var cellsFollowDisplay by mutableStateOf(true)
    private var linesPerScreen by mutableStateOf(1)
    private var dotsPerCell by mutableStateOf(8)

    /** 线宽归一三态，默认自动；仅影响多行三段式成书链路。 */
    private var normalizerMode by mutableStateOf(StockNormalizerMode.AUTO)

    /** G1 多行封面分层，默认关；单行设备忽略。 */
    private var g1SplitCover by mutableStateOf(false)

    private var converting by mutableStateOf(false)
    private var status by mutableStateOf<String?>(null)
    private var book: StripBook? by mutableStateOf(null)
    private var pager by mutableStateOf<TactileStripPager?>(null)
    private var displayConnected by mutableStateOf(false)

    /** 单行设备的视口模式；非空时三段式 [book]/[pager] 置空，两套路径互不干扰。 */
    private var viewport: TactileViewport? by mutableStateOf(null)

    /** 当前是否停在独立图例屏；图例不占图形页。 */
    private var vpLegend by mutableStateOf(false)

    /** 图例屏的盲文行（IO 线程翻译好，主线程只读）；空列表出空图例。 */
    private var legendBrailleLines: List<String> = emptyList()

    /** 边界短震封装；无马达 / 无权限时取 null，调用处静默降级。 */
    private var vibrator: Vibrator? = null

    /** 选图：系统文档选择器，只认图片。取消时回调为 null，静默处理。 */
    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) onImagePicked(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 标题栏交给 Compose 的 TopAppBar，避免两个标题栏叠加、读屏重复朗读。
        supportActionBar?.hide()

        display = TactileStripDisplay(this)
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
                    vpIsOverview = viewport?.isOverview == true,
                    vpIsLegend = vpLegend,
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
                )
            }
        }
    }

    override fun onDestroy() {
        // 点显客户端解绑；观察客户端只听状态，一并释放。
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

    // ------------------------------------------------------------------ 翻页

    private fun goFirst() = moveTo { first() }
    private fun goPrev() = moveTo { prev() }
    private fun goNext() = moveTo { next() }
    private fun goLast() = moveTo { last() }

    /** 统一翻页入口：空图（pager 为 null）只提示选图，不碰空指针。 */
    private fun moveTo(step: TactileStripPager.() -> Boolean) {
        val p = pager ?: run {
            showStatus(getString(R.string.tactile_strip_need_image))
            return
        }
        if (p.step()) {
            renderCurrent(announce = true)
        }
        // 已在边界时 pager 序号不变，不重复播报结束提示。
    }

    /** 把当前屏字节下发到点显器，并刷新状态行；announce 控制是否语音播报。 */
    private fun renderCurrent(announce: Boolean) {
        if (viewport != null) {
            renderViewport(announce)
            return
        }
        val p = pager ?: return
        // 只下发当前屏字节；页签 / 带号走 TTS，不写进点位字节、不预留显示格。
        display?.show(p.current.bytes, p.label())
        val note = if (displayConnected) {
            getString(R.string.tactile_strip_connected)
        } else {
            getString(R.string.tactile_strip_not_connected)
        }
        status = when (p.current.kind) {
            StripPageKind.COVER -> getString(R.string.tactile_strip_status_cover, note)
            StripPageKind.GRAPHIC ->
                getString(R.string.tactile_strip_status_graphic, p.label(), note)
            StripPageKind.BACK -> getString(R.string.tactile_strip_status_back, note)
        }
        if (announce) {
            // 封面额外补一句操作引导；图形 / 封底直接播核心层生成的页签文案。
            val speak = if (p.current.kind == StripPageKind.COVER) {
                getString(R.string.tactile_strip_cover_hint)
            } else {
                p.label()
            }
            announce(speak)
        }
    }

    // ------------------------------------------------------------------ 视口模式

    /** 渲染当前视口屏：总览 / 细节 / 图例三态；页签走 TTS，不写进点位字节。 */
    private fun renderViewport(announce: Boolean) {
        val vp = viewport ?: return
        val note = if (displayConnected) {
            getString(R.string.tactile_strip_connected)
        } else {
            getString(R.string.tactile_strip_not_connected)
        }

        if (vpLegend) {
            display?.show(legendBytes(vp), getString(R.string.tactile_vp_legend))
            status = getString(R.string.tactile_vp_status_legend, note)
            if (announce) announce(getString(R.string.tactile_vp_legend))
            return
        }

        if (vp.isOverview) {
            display?.show(vp.overviewBytes(), vp.overviewLabel)
            status = getString(R.string.tactile_vp_status_overview, note)
            if (announce) announce(getString(R.string.tactile_vp_hint))
        } else {
            val label = getString(
                R.string.tactile_vp_block_page,
                vp.blockIndex + 1,
                vp.blockCount,
                vp.blockPageIndex + 1,
                vp.blockPageCount(),
            )
            display?.show(vp.windowBytes(), label)
            status = getString(R.string.tactile_vp_status_detail, label, note)
            if (announce) announce(label)
        }
    }

    /** 图例屏点位：检测到的标注位置汇总行（盲文）；无候选时出「未检测到图内标注」屏。 */
    private fun legendBytes(vp: TactileViewport): ByteArray =
        TactileLegendPage.build(legendBrailleLines, vp.spec)

    /**
     * 视口导航统一入口：总览态移动选择框（横向按方、纵向按块）；细节态横向平移，纵向先翻
     * 块内屏，到块首 / 块末再换相邻块。成功刷新当前屏，受阻时短震并播报边界，状态不变。
     */
    private fun vpNavigate(dxCells: Int, dyBands: Int) {
        val vp = viewport ?: run {
            showStatus(getString(R.string.tactile_strip_need_image))
            return
        }
        if (vpLegend) return
        val result: MoveResult = when {
            vp.isOverview -> vp.moveBox(dxCells, dyBands)
            dxCells < 0 -> vp.panLeft()
            dxCells > 0 -> vp.panRight()
            dyBands < 0 -> {
                val r = vp.prevPage()
                if (!r.moved && r.boundary == Boundary.BLOCK_START) vp.prevBlock() else r
            }
            else -> {
                val r = vp.nextPage()
                if (!r.moved && r.boundary == Boundary.BLOCK_END) vp.nextBlock() else r
            }
        }
        if (result.moved) {
            renderViewport(announce = true)
        } else {
            buzz()
            result.boundary?.let { announce(boundaryText(it)) }
        }
    }

    private fun boundaryText(boundary: Boundary): String = when (boundary) {
        Boundary.LEFT -> getString(R.string.tactile_vp_left_end)
        Boundary.RIGHT -> getString(R.string.tactile_vp_right_end)
        Boundary.TOP -> getString(R.string.tactile_vp_top_end)
        Boundary.BOTTOM -> getString(R.string.tactile_vp_bottom_end)
        Boundary.BLOCK_START -> getString(R.string.tactile_vp_block_start)
        Boundary.BLOCK_END -> getString(R.string.tactile_vp_block_end)
        Boundary.FIRST_BLOCK -> getString(R.string.tactile_vp_first_block)
        Boundary.LAST_BLOCK -> getString(R.string.tactile_vp_last_block)
    }

    private fun vpEnterDetail() {
        viewport?.enterDetail()
        vpLegend = false
        renderViewport(announce = true)
    }

    private fun vpBackOverview() {
        viewport?.backToOverview()
        vpLegend = false
        renderViewport(announce = true)
    }

    private fun vpOpenLegend() {
        vpLegend = true
        renderViewport(announce = true)
    }

    private fun vpCloseLegend() {
        vpLegend = false
        renderViewport(announce = true)
    }

    /** 边界短震；马达 / 权限不可用时静默降级，不抛错、不阻断浏览。 */
    private fun buzz() {
        try {
            val v = vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(20)
            }
        } catch (e: Exception) {
            // 无震动能力：忽略。
        }
    }

    /** 点显连接状态回调：只刷新状态行文案，不重复下发点位。 */
    override fun onConnectionStateChanged(state: Int) {
        displayConnected = state == Display.STATE_CONNECTED
        // 连上就按设备的方数换行（跟随模式），再把当前屏重画一遍。
        if (applyDisplayCells() == true && sourceUri != null) rebuild()
        if (pager != null) renderCurrent(announce = false)
    }

    /** 音量键 / DPAD 单手操作：视口态四向平移，三段式态上下翻页。 */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val vpActive = viewport != null
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

    private fun showStatus(message: String) {
        status = message
        announce(message)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    /** 只做一次语音播报，不改界面状态、不弹 Toast（首屏提示用）。 */
    private fun announce(message: String) {
        window.decorView.announceForAccessibility(message)
    }

    // ------------------------------------------------------------------ 文案

    private fun labels(): StripLabels = StripLabels(
        title = getString(R.string.tactile_strip_title),
        back = getString(R.string.tactile_strip_back),
        firstHint = getString(R.string.tactile_strip_first_hint),
        pickImage = getString(R.string.tactile_strip_pick_image),
        noImage = getString(R.string.tactile_strip_no_image),
        imagePicked = getString(R.string.tactile_strip_image_picked),
        imagePickedSize = getString(R.string.tactile_strip_image_picked_size),
        sectionCells = getString(R.string.tactile_strip_section_cells),
        cellsDesc = getString(R.string.tactile_strip_cells_desc),
        cellsChoice = CELL_CHOICES.map {
            getString(R.string.tactile_strip_cells_choice, it)
        },
        cellsFollow = getString(R.string.tactile_vp_cells_follow),
        advanced = getString(R.string.tactile_vp_advanced),
        advancedHint = getString(R.string.tactile_vp_advanced_hint),
        sectionLines = getString(R.string.tactile_strip_section_lines),
        linesValue = getString(R.string.tactile_strip_lines_value),
        decrease = getString(R.string.tactile_strip_decrease),
        increase = getString(R.string.tactile_strip_increase),
        sectionDots = getString(R.string.tactile_strip_section_dots),
        dots6 = getString(R.string.tactile_strip_dots_6),
        dots8 = getString(R.string.tactile_strip_dots_8),
        prev = getString(R.string.tactile_strip_prev),
        next = getString(R.string.tactile_strip_next),
        first = getString(R.string.tactile_strip_first),
        last = getString(R.string.tactile_strip_last),
        coverHint = getString(R.string.tactile_strip_cover_hint),
        statusCover = getString(R.string.tactile_strip_status_cover),
        statusGraphic = getString(R.string.tactile_strip_status_graphic),
        statusBack = getString(R.string.tactile_strip_status_back),
        notConnected = getString(R.string.tactile_strip_not_connected),
        connected = getString(R.string.tactile_strip_connected),
        failDecode = getString(R.string.tactile_strip_fail_decode),
        needImage = getString(R.string.tactile_strip_need_image),
        selected = getString(R.string.tactile_strip_selected),
        unselected = getString(R.string.tactile_strip_unselected),
        actionRepick = getString(R.string.tactile_strip_action_repick),
        vpEnterDetail = getString(R.string.tactile_vp_enter_detail),
        vpBackOverview = getString(R.string.tactile_vp_back_overview),
        vpLegend = getString(R.string.tactile_vp_legend),
        vpLegendBack = getString(R.string.tactile_vp_legend_back),
        vpPanLeft = getString(R.string.tactile_vp_pan_left),
        vpPanRight = getString(R.string.tactile_vp_pan_right),
        vpPanUp = getString(R.string.tactile_vp_pan_up),
        vpPanDown = getString(R.string.tactile_vp_pan_down),
        normSection = getString(R.string.tactile_vp_norm_section),
        normDesc = getString(R.string.tactile_vp_norm_desc),
        normAuto = getString(R.string.tactile_vp_norm_auto),
        normOff = getString(R.string.tactile_vp_norm_off),
        normForce = getString(R.string.tactile_vp_norm_force),
        g1Split = getString(R.string.tactile_vp_g1_split),
        g1Desc = getString(R.string.tactile_vp_g1_desc),
    )
}

// ======================================================================
// 界面（纯展示：状态与回调由 Activity 持有，这里只排版与语义）
// ======================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StripScreen(
    labels: StripLabels,
    sourceSummary: String?,
    cellsPerLine: Int,
    cellsFollowDisplay: Boolean,
    linesPerScreen: Int,
    dotsPerCell: Int,
    normalizerMode: StockNormalizerMode,
    g1SplitCover: Boolean,
    converting: Boolean,
    status: String?,
    currentKind: StripPageKind?,
    canPage: Boolean,
    canPrev: Boolean,
    canNext: Boolean,
    viewportActive: Boolean,
    vpIsOverview: Boolean,
    vpIsLegend: Boolean,
    onFirstShown: () -> Unit,
    onPickImage: () -> Unit,
    onCellsChange: (Int) -> Unit,
    onCellsFollowChange: () -> Unit,
    onLinesDecrease: () -> Unit,
    onLinesIncrease: () -> Unit,
    onDotsChange: (Int) -> Unit,
    onNormalizerChange: (StockNormalizerMode) -> Unit,
    onG1Change: (Boolean) -> Unit,
    onFirst: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onLast: () -> Unit,
    onExit: () -> Unit,
    onRepick: () -> Unit,
    onBack: () -> Unit,
    onPanLeft: () -> Unit,
    onPanRight: () -> Unit,
    onPanUp: () -> Unit,
    onPanDown: () -> Unit,
    onEnterDetail: () -> Unit,
    onBackOverview: () -> Unit,
    onOpenLegend: () -> Unit,
    onCloseLegend: () -> Unit,
) {
    // 首屏只播报一次功能说明；LaunchedEffect(Unit) 在重组 / 状态变化时不会重跑。
    LaunchedEffect(Unit) { onFirstShown() }

    // 高级项默认收起，减少首屏可扫读的内容；展开状态只活在本次界面里。
    var advancedOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(labels.title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = labels.back,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onPickImage,
                enabled = !converting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(labels.pickImage)
            }

            Text(
                sourceSummary ?: labels.noImage,
                style = MaterialTheme.typography.bodyMedium,
            )

            Text(
                // 方数默认跟随点显器：连上就按它的方数换行，用户不必先猜自己的机器有几方。
                labels.cellsFollow.format(cellsPerLine),
                style = MaterialTheme.typography.bodyMedium,
            )

            // 行数：滑杆对读屏不一定好用，给「减 1 / 加 1」纯点击按钮作等价路径。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(labels.sectionLines, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        labels.linesValue.format(linesPerScreen),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = onLinesDecrease,
                    enabled = !converting && linesPerScreen > MIN_LINES,
                ) { Text(labels.decrease) }
                TextButton(
                    onClick = onLinesIncrease,
                    enabled = !converting && linesPerScreen < MAX_LINES,
                ) { Text(labels.increase) }
            }

            Text(
                labels.sectionDots,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            StripRadioRow(
                title = labels.dots6,
                description = "",
                selected = dotsPerCell == 6,
                selectedLabel = labels.selected,
                unselectedLabel = labels.unselected,
                onClick = { onDotsChange(6) },
            )
            StripRadioRow(
                title = labels.dots8,
                description = "",
                selected = dotsPerCell == 8,
                selectedLabel = labels.selected,
                unselectedLabel = labels.unselected,
                onClick = { onDotsChange(8) },
            )

            // 高级项默认收起：方数手动档、线条归一、G1 封面分层。平时不用碰，
            // 展开后仍是原来的单选行，读屏可逐项读。
            TextButton(onClick = { advancedOpen = !advancedOpen }) {
                Text(labels.advanced)
            }
            if (advancedOpen) {
                Text(
                    labels.advancedHint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text(
                    labels.sectionCells,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                StripRadioRow(
                    title = labels.cellsFollow.format(cellsPerLine),
                    description = labels.cellsDesc,
                    selected = cellsFollowDisplay,
                    selectedLabel = labels.selected,
                    unselectedLabel = labels.unselected,
                    onClick = onCellsFollowChange,
                )
                CELL_CHOICES.forEachIndexed { index, choice ->
                    StripRadioRow(
                        title = labels.cellsChoice[index],
                        description = "",
                        selected = !cellsFollowDisplay && cellsPerLine == choice,
                        selectedLabel = labels.selected,
                        unselectedLabel = labels.unselected,
                        onClick = { onCellsChange(choice) },
                    )
                }

                // 线条归一三态：自动（默认）/ 关 / 强制（调试）。
                Text(
                    labels.normSection,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                StripRadioRow(
                    title = labels.normAuto,
                    description = labels.normDesc,
                    selected = normalizerMode == StockNormalizerMode.AUTO,
                    selectedLabel = labels.selected,
                    unselectedLabel = labels.unselected,
                    onClick = { onNormalizerChange(StockNormalizerMode.AUTO) },
                )
                StripRadioRow(
                    title = labels.normOff,
                    description = "",
                    selected = normalizerMode == StockNormalizerMode.OFF,
                    selectedLabel = labels.selected,
                    unselectedLabel = labels.unselected,
                    onClick = { onNormalizerChange(StockNormalizerMode.OFF) },
                )
                StripRadioRow(
                    title = labels.normForce,
                    description = "",
                    selected = normalizerMode == StockNormalizerMode.FORCE,
                    selectedLabel = labels.selected,
                    unselectedLabel = labels.unselected,
                    onClick = { onNormalizerChange(StockNormalizerMode.FORCE) },
                )

                // G1 封面分层：仅多行设备可用；单行设备置灰不可点。
                StripRadioRow(
                    title = labels.g1Split,
                    description = labels.g1Desc,
                    selected = g1SplitCover,
                    selectedLabel = labels.selected,
                    unselectedLabel = labels.unselected,
                    enabled = linesPerScreen >= 2,
                    onClick = { onG1Change(!g1SplitCover) },
                )
            }

            // 状态区放在翻页按钮上方：连接 / 解码 / 页签结果直接进首屏无障碍焦点序列，
            // live region 保证状态变化时读屏也会主动朗读。
            status?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }

            // 视口模式：平移、进细节 / 回总览、独立图例屏；按钮均可聚焦点击，不靠视觉。
            if (viewportActive) {
                if (vpIsLegend) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onCloseLegend, modifier = Modifier.weight(1f)) {
                            Text(labels.vpLegendBack)
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (vpIsOverview) {
                            TextButton(onClick = onEnterDetail, modifier = Modifier.weight(1f)) {
                                Text(labels.vpEnterDetail)
                            }
                        } else {
                            TextButton(onClick = onBackOverview, modifier = Modifier.weight(1f)) {
                                Text(labels.vpBackOverview)
                            }
                        }
                        TextButton(onClick = onOpenLegend, modifier = Modifier.weight(1f)) {
                            Text(labels.vpLegend)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onPanUp, modifier = Modifier.weight(1f)) {
                            Text(labels.vpPanUp)
                        }
                        TextButton(onClick = onPanDown, modifier = Modifier.weight(1f)) {
                            Text(labels.vpPanDown)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onPanLeft, modifier = Modifier.weight(1f)) {
                            Text(labels.vpPanLeft)
                        }
                        TextButton(onClick = onPanRight, modifier = Modifier.weight(1f)) {
                            Text(labels.vpPanRight)
                        }
                    }
                }
            } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onFirst,
                    enabled = canPage && canPrev,
                    modifier = Modifier.weight(1f),
                ) { Text(labels.first) }
                TextButton(
                    onClick = onPrev,
                    enabled = canPage && canPrev,
                    modifier = Modifier.weight(1f),
                ) { Text(labels.prev) }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onNext,
                    enabled = canPage && canNext,
                    modifier = Modifier.weight(1f),
                ) { Text(labels.next) }
                TextButton(
                    onClick = onLast,
                    enabled = canPage && canNext,
                    modifier = Modifier.weight(1f),
                ) { Text(labels.last) }
            }

            // 封底屏专属动作：返回上一层 / 换一张图。其余屏不显示，避免图形页混进文字动作。
            if (currentKind == StripPageKind.BACK) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = onExit,
                        modifier = Modifier.weight(1f),
                    ) { Text(labels.back) }
                    TextButton(
                        onClick = onRepick,
                        modifier = Modifier.weight(1f),
                    ) { Text(labels.actionRepick) }
                }
            }
            }
        }
    }
}

/** 单选行：整行是 RadioButton，选中态用 stateDescription 播报。 */
@Composable
private fun StripRadioRow(
    title: String,
    description: String,
    selected: Boolean,
    selectedLabel: String,
    unselectedLabel: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                enabled = enabled,
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) {
                stateDescription = if (selected) selectedLabel else unselectedLabel
            }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description.isNotEmpty()) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
