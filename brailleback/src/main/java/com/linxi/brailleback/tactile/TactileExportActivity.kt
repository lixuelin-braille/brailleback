/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 为什么需要它：
 * 触觉图流水线（选图 → 参数 → 栅格化 → 编码 → 落盘）需要一个盲人用户能靠 TalkBack
 * 全程操作的入口页。算法内核在同包的 Pipeline / Rasterizer / Encoder，本 Activity 只做
 * 界面接线：选图、收参数、后台线程转换、SAF 写出 .bmp，并把每一步结果播报成可读文本。
 *
 * 入口现状（2026-11-24）：本页在 Manifest 注册为独立 launcher（应用列表图标直达）。
 * TactileExperiment 保留为「将来是否接进设置主屏」的标志，默认 false，不在本页做任何阻断：
 * 点图标进来即可选图、导出。后续接设置主屏时，可按 TactileExperiment.isEnabled 决定行是否出现。
 *
 * 无障碍是硬要求：每个可点控件都有文字标签 / 角色 / 状态描述；结果不只画在画面上，
 * 同时进 live region、announceForAccessibility 与 Toast 三条路径，保证读屏能朗读。
 */

package com.linxi.brailleback.tactile

import android.net.Uri
import android.os.Bundle
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

/** 后台转换 / 写盘的两种结局，回主线程后再翻译成界面文案。 */
private sealed interface ExportOutcome {
    data class Success(val fileName: String) : ExportOutcome
    data class Failure(val message: String) : ExportOutcome
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
    val exportAction: String,
    val exporting: String,
    val success: String,
    val fail: String,
    val cancelled: String,
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

    /** 进 SAF 选保存位置前把参数定死，后台线程只按这份参数编码。 */
    private var pendingSettings: TactileSettings? = null
    private var pendingProfile: TactileProfile? = null

    /** 选图：系统文档选择器，只认图片。取消时回调为 null。 */
    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) onImagePicked(uri) else showStatus(getString(R.string.tactile_export_cancelled))
    }

    /** 导出落盘：SAF 创建一份 BMP。取消时回调为 null。 */
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
                    onExport = ::onExportClicked,
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
        showStatus(getString(R.string.tactile_export_reading))
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
                    showStatus(getString(R.string.tactile_export_fail, readErrorLabel()))
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

    // ------------------------------------------------------------------ 导出

    private fun onExportClicked() {
        val src = sourceFile
        if (src == null) {
            showStatus(getString(R.string.tactile_export_need_image))
            return
        }
        // 参数在主线程定死，后台线程只按这份设置编码，避免播报结果与落盘内容不一致。
        pendingSettings = TactileSettings(mode = mode, threshold = threshold)
        pendingProfile = if (landscape) landscapeProfile() else TactileProfile.A4
        createBmpLauncher.launch("tactile_export.bmp")
    }

    /** A4 横版：297 × 210 mm，dpi 与默认剖面一致。 */
    private fun landscapeProfile(): TactileProfile =
        TactileProfile(pageWidthMm = 297.0, pageHeightMm = 210.0)

    private fun onExportUri(uri: Uri?) {
        if (isFinishing) return
        if (uri == null) {
            showStatus(getString(R.string.tactile_export_cancelled))
            return
        }
        val src = sourceFile
        if (src == null) {
            showStatus(getString(R.string.tactile_export_need_image))
            return
        }
        val settings = pendingSettings ?: TactileSettings()
        val profile = pendingProfile ?: TactileProfile.A4
        converting = true
        showStatus(getString(R.string.tactile_export_exporting))
        lifecycleScope.launch(Dispatchers.IO) {
            val outcome = try {
                val bytes = TactilePipeline.imageToBmp(src, settings, profile)
                contentResolver.openOutputStream(uri)?.use { out -> out.write(bytes) }
                    ?: throw IllegalStateException("openOutputStream returned null")
                ExportOutcome.Success(uri.lastPathSegment ?: "")
            } catch (e: Exception) {
                ExportOutcome.Failure(e.message ?: readErrorLabel())
            }
            withContext(Dispatchers.Main) {
                converting = false
                when (outcome) {
                    is ExportOutcome.Success ->
                        showStatus(getString(R.string.tactile_export_success, outcome.fileName))
                    is ExportOutcome.Failure ->
                        showStatus(getString(R.string.tactile_export_fail, outcome.message))
                }
            }
        }
    }

    private fun readErrorLabel(): String = getString(R.string.tactile_export_read_error)

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
        exportAction = getString(R.string.tactile_export_action),
        exporting = getString(R.string.tactile_export_exporting),
        success = getString(R.string.tactile_export_success),
        fail = getString(R.string.tactile_export_fail),
        cancelled = getString(R.string.tactile_export_cancelled),
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
    onFirstShown: () -> Unit,
    onPickImage: () -> Unit,
    onModeChange: (TactileMode) -> Unit,
    onThresholdDecrease: () -> Unit,
    onThresholdIncrease: () -> Unit,
    onPageChange: (Boolean) -> Unit,
    onExport: () -> Unit,
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

            Button(
                onClick = onExport,
                enabled = !converting && sourceName != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(labels.exportAction)
            }

            status?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
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
