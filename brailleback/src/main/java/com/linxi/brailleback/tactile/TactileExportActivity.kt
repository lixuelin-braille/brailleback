/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 为什么需要它：
 * 手机不连打印机，触觉页只负责「出一份能刻印的 BMP」，再把文件稳稳交到用户手里拿到电脑上。
 * 算法内核在同包的 Pipeline / Rasterizer / Encoder，本 Activity 只做界面接线：选图、收参数、
 * 后台线程转换、两条落盘通道（公共下载目录为主，SAF 另存为为辅）、可选分享，并把每一步结果
 * 播报成可读文本。
 *
 * 入口现状（2026-11-24）：本页在 Manifest 注册为独立 launcher（应用列表图标直达）。
 * TactileExperiment 保留为「将来是否接进设置主屏」的标志，默认 false，不在本页做任何阻断。
 *
 * 无障碍是硬要求：每个可点控件都有文字标签 / 角色 / 状态描述；结果不只画在画面上，
 * 同时进 live region、announceForAccessibility 与 Toast 三条路径，保证读屏能朗读。
 */

package com.linxi.brailleback.tactile

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
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
import com.linxi.brailleback.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/** 后台转换 / 落盘的结局，回主线程后再翻译成界面文案。 */
private sealed interface ExportOutcome {
    data class Success(
        val uri: Uri?,
        val fileName: String,
        val sizeBytes: Long,
    ) : ExportOutcome

    data class Failure(val kind: FailureKind, val detail: String?) : ExportOutcome
}

/** 失败原因分类：播报必须说清是哪一类，不能只说「失败」。 */
private enum class FailureKind {
    /** ① 源文件超过 32 MB。 */
    SOURCE_TOO_LARGE,

    /** ② 目标像素超过 3000 万（尺寸 / DPI 过大）。 */
    TARGET_TOO_LARGE,

    /** ③ 图片解码失败或格式不支持。 */
    DECODE_FAILED,

    /** ④ 没有写入权限（含写盘流为空）。 */
    PERMISSION_DENIED,

    /** 其余未归类错误。 */
    UNKNOWN,
}

/** 一次性收集好的文案，界面不直接碰 Context，便于读屏线性朗读。 */
private data class TactileLabels(
    val title: String,
    val back: String,
    val firstHint: String,
    val pickImage: String,
    val noImage: String,
    val imagePicked: String,
    val sectionMode: String,
    val modeThreshold: String,
    val modeThresholdDesc: String,
    val modeGrayscale: String,
    val modeGrayscaleDesc: String,
    val modeEdge: String,
    val modeEdgeDesc: String,
    val thresholdTitle: String,
    val thresholdValue: String,
    val decrease: String,
    val increase: String,
    val sectionPage: String,
    val pageA4: String,
    val pageA4Landscape: String,
    val actionDownload: String,
    val actionOther: String,
    val actionShare: String,
    val exporting: String,
    val successDownload: String,
    val successOther: String,
    val failSourceLarge: String,
    val failTargetLarge: String,
    val failDecode: String,
    val failPermission: String,
    val failUnknown: String,
    val shareNoApp: String,
    val needImage: String,
    val selected: String,
    val unselected: String,
)

class TactileExportActivity : AppCompatActivity() {

    /** 已选图片先复制到缓存目录的临时文件，导出时走流水线的 File 入口。 */
    private var sourceFile: File? = null
    private var sourceName by mutableStateOf<String?>(null)

    private var mode by mutableStateOf(TactileMode.THRESHOLD)
    private var threshold by mutableStateOf(TactileSettings.DEFAULT_THRESHOLD)
    private var landscape by mutableStateOf(false)
    private var converting by mutableStateOf(false)
    private var status by mutableStateOf<String?>(null)

    /** 进落盘流程前把参数定死，后台线程只按这份设置编码。 */
    private var pendingSettings: TactileSettings? = null
    private var pendingProfile: TactileProfile? = null

    /** SAF 通道建议的文件名，成功播报时用。 */
    private var pendingFileName: String? = null

    /** 最近一次成功落盘的文件，供「分享」按钮直接取流。 */
    private var lastExportUri: Uri? = null
    private var lastExportName: String? = null

    /** 选图：系统文档选择器，只认图片。取消时回调为 null，静默处理。 */
    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) onImagePicked(uri) }

    /** 落盘辅通道：SAF 创建一份 BMP。取消时回调为 null，静默处理。 */
    private val createBmpLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("image/bmp")
    ) { uri -> onExportUri(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 标题栏交给 Compose 的 TopAppBar，避免两个标题栏叠加、读屏重复朗读。
        supportActionBar?.hide()

        setContent {
            val labels = labels()
            val colorScheme = if (isSystemInDarkTheme()) {
                darkColorScheme()
            } else {
                lightColorScheme()
            }
            MaterialTheme(colorScheme = colorScheme) {
                TactileExportScreen(
                    labels = labels,
                    sourceName = sourceName,
                    mode = mode,
                    threshold = threshold,
                    landscape = landscape,
                    converting = converting,
                    status = status,
                    canShare = lastExportUri != null,
                    onFirstShown = { announce(labels.firstHint) },
                    onPickImage = { pickImageLauncher.launch("image/*") },
                    onModeChange = { mode = it },
                    onThresholdDecrease = {
                        threshold = (threshold - 1).coerceIn(
                            TactileSettings.MIN_THRESHOLD, TactileSettings.MAX_THRESHOLD
                        )
                    },
                    onThresholdIncrease = {
                        threshold = (threshold + 1).coerceIn(
                            TactileSettings.MIN_THRESHOLD, TactileSettings.MAX_THRESHOLD
                        )
                    },
                    onPageChange = { landscape = it },
                    onSaveDownloads = ::onSaveDownloadsClicked,
                    onSaveOther = ::onSaveOtherClicked,
                    onShare = ::onShareClicked,
                    onBack = { finish() },
                )
            }
        }
    }

    override fun onDestroy() {
        // 临时图片只在本次会话使用；缓存目录由系统按空间回收，这里不主动删，避免重复选图时丢样本。
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 选图

    private fun onImagePicked(uri: Uri) {
        val name = displayNameOf(uri)
        converting = true
        lifecycleScope.launch(Dispatchers.IO) {
            val tmp = File(cacheDir, "tactile_source_${System.currentTimeMillis()}.tmp")
            val copied = try {
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tmp).use { out -> input.copyTo(out, bufferSize = 8192) }
                } ?: throw IllegalStateException("cannot open $uri")
                true
            } catch (e: Exception) {
                false
            }
            withContext(Dispatchers.Main) {
                converting = false
                if (copied) {
                    sourceFile?.delete()
                    sourceFile = tmp
                    sourceName = name ?: uri.lastPathSegment ?: ""
                    showStatus(
                        getString(R.string.tactile_export_image_picked, sourceName.orEmpty())
                    )
                } else {
                    tmp.delete()
                    showStatus(getString(R.string.tactile_fail_decode))
                }
            }
        }
    }

    /** 取文件显示名；provider 不支持查询时回落到 URI 末段。 */
    private fun displayNameOf(uri: Uri): String? {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 落盘后回读真实显示名（`OpenableColumns.DISPLAY_NAME`）。重名时系统会自动追加
     * ` (1)` 之类后缀，播报必须与磁盘文件一致。仅查这一列；cursor 空 / 抛异常 / 名为空白
     * 一律返回 null——保存本身已成功，回读失败只影响播报退让，不阻断成功路径。
     */
    private fun queryDisplayName(uri: Uri): String? = try {
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) {
                cursor.getString(idx)?.takeIf { it.isNotBlank() }
            } else {
                null
            }
        }
    } catch (e: Exception) {
        null
    }

    // ------------------------------------------------------------------ 导出

    /** 主通道：写入公共下载目录（手机→电脑 USB 拷文件的主路径）。 */
    private fun onSaveDownloadsClicked() {
        val src = sourceFile ?: run {
            showStatus(getString(R.string.tactile_export_need_image))
            return
        }
        prepareParams()
        converting = true
        showStatus(getString(R.string.tactile_export_exporting))
        lifecycleScope.launch(Dispatchers.IO) {
            val outcome = try {
                val bytes = TactilePipeline.imageToBmp(
                    src, pendingSettings ?: TactileSettings(), pendingProfile ?: TactileProfile.A4
                )
                val fileName = TactileExportNames.build(
                    sourceName, TactileExportNames.timestamp(System.currentTimeMillis())
                )
                val uri = saveToDownloads(fileName, bytes)
                // IS_PENDING 已在 saveToDownloads 内清 0 之后再查，避免拿到占位名；
                // 重名时系统追加 ` (1)` 后缀，播报必须念磁盘真实名。
                val realName = TactileExportNames.resolveDisplay(fileName, queryDisplayName(uri))
                ExportOutcome.Success(uri, realName, bytes.size.toLong())
            } catch (e: Exception) {
                ExportOutcome.Failure(classify(e), e.message)
            }
            withContext(Dispatchers.Main) {
                converting = false
                renderOutcome(outcome, fromDownloads = true)
            }
        }
    }

    /** 辅通道：SAF 另存到用户自选位置。 */
    private fun onSaveOtherClicked() {
        val src = sourceFile ?: run {
            showStatus(getString(R.string.tactile_export_need_image))
            return
        }
        prepareParams()
        pendingFileName = TactileExportNames.build(
            sourceName, TactileExportNames.timestamp(System.currentTimeMillis())
        )
        createBmpLauncher.launch(pendingFileName ?: "tactile_export.bmp")
    }

    private fun onExportUri(uri: Uri?) {
        if (isFinishing) return
        // 用户取消：静默，不报错音、不弹失败提示。
        if (uri == null) return
        val src = sourceFile ?: return
        converting = true
        showStatus(getString(R.string.tactile_export_exporting))
        lifecycleScope.launch(Dispatchers.IO) {
            val outcome = try {
                val bytes = TactilePipeline.imageToBmp(
                    src, pendingSettings ?: TactileSettings(), pendingProfile ?: TactileProfile.A4
                )
                contentResolver.openOutputStream(uri)?.use { out -> out.write(bytes) }
                    ?: throw IllegalStateException("openOutputStream returned null")
                // SAF 选「另存」时目标目录也可能已有同名文件，文档 Uri 同样可回读真实名；
                // 个别 provider 不支持查询 DISPLAY_NAME 时回 null，退让本地拼的名字。
                val localName = pendingFileName ?: uri.lastPathSegment.orEmpty()
                val realName = TactileExportNames.resolveDisplay(localName, queryDisplayName(uri))
                ExportOutcome.Success(uri, realName, bytes.size.toLong())
            } catch (e: Exception) {
                ExportOutcome.Failure(classify(e), e.message)
            }
            withContext(Dispatchers.Main) {
                converting = false
                renderOutcome(outcome, fromDownloads = false)
            }
        }
    }

    /** 分享最近一次导出的 BMP；content URI 临时授予读权限，不另建 FileProvider。 */
    private fun onShareClicked() {
        val uri = lastExportUri ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/bmp"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, lastExportName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.tactile_export_action_share)))
        } catch (e: Exception) {
            showStatus(getString(R.string.tactile_export_share_no_app))
        }
    }

    /** 横版只是把 A4 的宽高互换；毫米真源仍在 [TactileProfile.A4]，活动里不另写一份魔法数。 */
    private fun landscapeProfile(): TactileProfile =
        TactileProfile(
            dpi = TactileProfile.A4.dpi,
            pageWidthMm = TactileProfile.A4.pageHeightMm,
            pageHeightMm = TactileProfile.A4.pageWidthMm,
        )

    private fun prepareParams() {
        val profile = if (landscape) landscapeProfile() else TactileProfile.A4
        pendingProfile = profile
        // 页面毫米从 profile 读回 settings；宽高留 0 会被栅格化当成「自动推算」，
        // 按长边 160 mm 兜底出图，与界面选的 A4 版面不一致。
        pendingSettings = TactileSettings(
            mode = mode,
            threshold = threshold,
            widthMm = profile.pageWidthMm,
            heightMm = profile.pageHeightMm,
        )
    }

    /** MediaStore.Downloads 标准 IS_PENDING 流程：占位 → 写入 → 落定；失败回滚删行。 */
    private fun saveToDownloads(fileName: String, bytes: ByteArray): Uri {
        val resolver = contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "image/bmp")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore insert returned null")
        try {
            resolver.openOutputStream(uri)?.use { out -> out.write(bytes) }
                ?: throw IllegalStateException("openOutputStream returned null")
            val settled = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(uri, settled, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    /** 按异常类型 / 文案关键词分到四类失败；归类不出的落 UNKNOWN，带上原始细节。 */
    private fun classify(e: Throwable): FailureKind = when {
        e is TactileTooLargeException -> FailureKind.TARGET_TOO_LARGE
        e.message?.contains("32 MB") == true -> FailureKind.SOURCE_TOO_LARGE
        e is SecurityException -> FailureKind.PERMISSION_DENIED
        e.message?.contains("openOutputStream") == true -> FailureKind.PERMISSION_DENIED
        e.message?.let {
            it.contains("无法识别") || it.contains("解码失败") || it.contains("内存不足")
        } == true -> FailureKind.DECODE_FAILED
        else -> FailureKind.UNKNOWN
    }

    private fun renderOutcome(outcome: ExportOutcome, fromDownloads: Boolean) {
        when (outcome) {
            is ExportOutcome.Success -> {
                lastExportUri = outcome.uri
                lastExportName = outcome.fileName
                val size = TactileExportNames.formatSize(outcome.sizeBytes)
                val msg = if (fromDownloads) {
                    getString(R.string.tactile_export_success_download, outcome.fileName, size)
                } else {
                    getString(R.string.tactile_export_success_other, outcome.fileName, size)
                }
                showStatus(msg)
            }
            is ExportOutcome.Failure -> showStatus(failureMessage(outcome))
        }
    }

    private fun failureMessage(failure: ExportOutcome.Failure): String = when (failure.kind) {
        FailureKind.SOURCE_TOO_LARGE -> getString(R.string.tactile_fail_source_large)
        FailureKind.TARGET_TOO_LARGE -> getString(R.string.tactile_fail_target_large)
        FailureKind.DECODE_FAILED -> getString(R.string.tactile_fail_decode)
        FailureKind.PERMISSION_DENIED -> getString(R.string.tactile_fail_permission)
        FailureKind.UNKNOWN -> getString(R.string.tactile_fail_unknown, failure.detail.orEmpty())
    }

    private fun showStatus(message: String) {
        status = message
        // 三条播报路径互补：live region 给读屏，announce 兜底系统语音，Toast 留给可视用户。
        announce(message)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    /** 只做一次语音播报，不改界面状态、不弹 Toast（首屏提示用）。 */
    private fun announce(message: String) {
        window.decorView.announceForAccessibility(message)
    }

    // ------------------------------------------------------------------ 文案

    private fun labels(): TactileLabels = TactileLabels(
        title = getString(R.string.tactile_export_title),
        back = getString(R.string.tactile_export_back),
        firstHint = getString(R.string.tactile_export_first_hint),
        pickImage = getString(R.string.tactile_export_pick_image),
        noImage = getString(R.string.tactile_export_no_image),
        imagePicked = getString(R.string.tactile_export_image_picked),
        sectionMode = getString(R.string.tactile_export_section_mode),
        modeThreshold = getString(R.string.tactile_mode_threshold),
        modeThresholdDesc = getString(R.string.tactile_mode_threshold_desc),
        modeGrayscale = getString(R.string.tactile_mode_grayscale),
        modeGrayscaleDesc = getString(R.string.tactile_mode_grayscale_desc),
        modeEdge = getString(R.string.tactile_mode_edge),
        modeEdgeDesc = getString(R.string.tactile_mode_edge_desc),
        thresholdTitle = getString(R.string.tactile_export_threshold),
        thresholdValue = getString(R.string.tactile_export_threshold_value),
        decrease = getString(R.string.tactile_export_decrease),
        increase = getString(R.string.tactile_export_increase),
        sectionPage = getString(R.string.tactile_export_section_page),
        pageA4 = getString(R.string.tactile_page_a4),
        pageA4Landscape = getString(R.string.tactile_page_a4_landscape),
        actionDownload = getString(R.string.tactile_export_action_download),
        actionOther = getString(R.string.tactile_export_action_other),
        actionShare = getString(R.string.tactile_export_action_share),
        exporting = getString(R.string.tactile_export_exporting),
        successDownload = getString(R.string.tactile_export_success_download),
        successOther = getString(R.string.tactile_export_success_other),
        failSourceLarge = getString(R.string.tactile_fail_source_large),
        failTargetLarge = getString(R.string.tactile_fail_target_large),
        failDecode = getString(R.string.tactile_fail_decode),
        failPermission = getString(R.string.tactile_fail_permission),
        failUnknown = getString(R.string.tactile_fail_unknown),
        shareNoApp = getString(R.string.tactile_export_share_no_app),
        needImage = getString(R.string.tactile_export_need_image),
        selected = getString(R.string.tactile_export_selected),
        unselected = getString(R.string.tactile_export_unselected),
    )
}

// ======================================================================
// 界面（纯展示：状态与回调由 Activity 持有，这里只排版与语义）
// ======================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TactileExportScreen(
    labels: TactileLabels,
    sourceName: String?,
    mode: TactileMode,
    threshold: Int,
    landscape: Boolean,
    converting: Boolean,
    status: String?,
    canShare: Boolean,
    onFirstShown: () -> Unit,
    onPickImage: () -> Unit,
    onModeChange: (TactileMode) -> Unit,
    onThresholdDecrease: () -> Unit,
    onThresholdIncrease: () -> Unit,
    onPageChange: (Boolean) -> Unit,
    onSaveDownloads: () -> Unit,
    onSaveOther: () -> Unit,
    onShare: () -> Unit,
    onBack: () -> Unit,
) {
    // 首屏只播报一次功能说明；LaunchedEffect(Unit) 在重组 / 状态变化时不会重跑，避免读屏被反复骚扰。
    LaunchedEffect(Unit) { onFirstShown() }

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
                sourceName?.let { labels.imagePicked.format(it) } ?: labels.noImage,
                style = MaterialTheme.typography.bodyMedium,
            )

            Text(
                labels.sectionMode,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            ModeRadioRow(
                title = labels.modeThreshold,
                description = labels.modeThresholdDesc,
                selected = mode == TactileMode.THRESHOLD,
                selectedLabel = labels.selected,
                unselectedLabel = labels.unselected,
                onClick = { onModeChange(TactileMode.THRESHOLD) },
            )
            ModeRadioRow(
                title = labels.modeGrayscale,
                description = labels.modeGrayscaleDesc,
                selected = mode == TactileMode.GRAYSCALE,
                selectedLabel = labels.selected,
                unselectedLabel = labels.unselected,
                onClick = { onModeChange(TactileMode.GRAYSCALE) },
            )
            ModeRadioRow(
                title = labels.modeEdge,
                description = labels.modeEdgeDesc,
                selected = mode == TactileMode.EDGE,
                selectedLabel = labels.selected,
                unselectedLabel = labels.unselected,
                onClick = { onModeChange(TactileMode.EDGE) },
            )

            // 阈值：滑杆对中文读屏不一定好用，给「减 1 / 加 1」纯点击按钮作等价路径，
            // 当前值始终以文字显示并写进状态描述。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(labels.thresholdTitle, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        labels.thresholdValue.format(threshold),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = onThresholdDecrease,
                    enabled = !converting && threshold > TactileSettings.MIN_THRESHOLD,
                ) { Text(labels.decrease) }
                TextButton(
                    onClick = onThresholdIncrease,
                    enabled = !converting && threshold < TactileSettings.MAX_THRESHOLD,
                ) { Text(labels.increase) }
            }

            Text(
                labels.sectionPage,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            ModeRadioRow(
                title = labels.pageA4,
                description = "",
                selected = !landscape,
                selectedLabel = labels.selected,
                unselectedLabel = labels.unselected,
                onClick = { onPageChange(false) },
            )
            ModeRadioRow(
                title = labels.pageA4Landscape,
                description = "",
                selected = landscape,
                selectedLabel = labels.selected,
                unselectedLabel = labels.unselected,
                onClick = { onPageChange(true) },
            )

            // 状态区放在操作按钮上方：成功 / 失败结果直接进首屏无障碍焦点序列，
            // 盲人不用先滚到屏外才能听到播报；live region 保证状态变化时读屏也会主动朗读。
            status?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }

            // 主通道：公共下载目录；辅通道：SAF 另存。两个按钮标签读屏可区分。
            Button(
                onClick = onSaveDownloads,
                enabled = !converting && sourceName != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(labels.actionDownload)
            }
            TextButton(
                onClick = onSaveOther,
                enabled = !converting && sourceName != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(labels.actionOther)
            }
            TextButton(
                onClick = onShare,
                enabled = !converting && canShare,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(labels.actionShare)
            }
        }
    }
}

/** 模式 / 版面单选行：整行是 RadioButton，选中态用 stateDescription 播报。 */
@Composable
private fun ModeRadioRow(
    title: String,
    description: String,
    selected: Boolean,
    selectedLabel: String,
    unselectedLabel: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) {
                stateDescription = if (selected) selectedLabel else unselectedLabel
            }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
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
