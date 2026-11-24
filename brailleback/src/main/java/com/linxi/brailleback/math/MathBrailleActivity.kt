/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 中文数学盲文通道入口页（工作流 A）。
 *
 * 边界（与阅读器 / 图片点显流程互不影响）：
 * - 只接受 MathML 文本（`<math>…</math>`）或受控线性写法（见 [MathBraille] /
 *   [LinearMathParser]）；不做公式 OCR、不读图片公式。
 * - 点位下发复用既有 [com.linxi.brailleback.tactile.TactileStripDisplay] 薄封装，
 *   只依赖 client 模块 DisplayClient，不经过 DisplayManager。
 * - 中文 <mtext> 走既有 TranslatorService 桥；数学表全部在本模块内，不碰 service 模块、
 *   liblouis 表与 tablelist.xml。
 *
 * 无障碍：结果文案直接进 TalkBack 朗读；状态行走 live region；6/8 点可切换。
 * 本页尚未在真实点显器上验证连接与落屏，仅完成代码层接线（见交付说明状态分层）。
 */

package com.linxi.brailleback.math

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.lifecycle.lifecycleScope
import androidx.compose.ui.platform.LocalContext
import com.googlecode.eyesfree.braille.display.Display
import com.googlecode.eyesfree.braille.display.DisplayClient
import com.linxi.brailleback.BuildConfig
import com.linxi.brailleback.R
import com.linxi.brailleback.tactile.TactileStripDisplay
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
class MathBrailleActivity : AppCompatActivity(),
    Display.OnConnectionStateChangeListener {

    /** 点显薄封装：持有真正下发点位的 DisplayClient；关闭时统一解绑。 */
    private var display: TactileStripDisplay? = null

    /** 只读连接状态观察客户端：只听状态、不发点位。 */
    private var observeClient: DisplayClient? = null

    /** 中文 <mtext> 提供者；懒建，销毁切回 IO 线程。 */
    private var chineseProvider: AndroidChineseBrailleProvider? = null

    private var inputField by mutableStateOf(TextFieldValue(""))
    private var result by mutableStateOf<MathBrailleResult?>(null)
    private var status by mutableStateOf<String?>(null)
    private var dotsPerCell by mutableStateOf(6)
    private var converting by mutableStateOf(false)
    private var displayConnected by mutableStateOf(false)

    /** 选文件：系统文档选择器（文本 / XML / XHTML），取消时静默。 */
    private val openFormulaLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) readFormulaUri(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        display = TactileStripDisplay(this)
        observeClient = DisplayClient(this).apply {
            setOnConnectionStateChangeListener(this@MathBrailleActivity)
        }
        chineseProvider = AndroidChineseBrailleProvider(this)

        // [DEBUG-HOOK] 仅 DEBUG 构建：Intent extra `formula_file`（绝对路径）直接载入公式文件，
        // 绕过软键盘注入受限的测试 harness；release 构建 BuildConfig.DEBUG=false，此段不可达。
        if (BuildConfig.DEBUG) {
            intent.getStringExtra("formula_file")?.takeIf { it.isNotBlank() }?.let { path ->
                readFormulaFile(path)
            }
            // [DEBUG-HOOK] `dots`（6/8）覆盖初始点档位；非法值回落默认 6 点。
            val dotsRaw = intent.getStringExtra("dots")
                ?: intent.getIntExtra("dots", -1).takeIf { it >= 0 }?.toString()
            MathDebugParams.parseDots(dotsRaw)?.let { dotsPerCell = it }
        }

        setContent {
            Scaffold(
                topBar = {
                    TopAppBar(title = { Text(getString(R.string.math_title), maxLines = 1) })
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
                    OutlinedTextField(
                        value = inputField,
                        onValueChange = { inputField = it },
                        label = { Text(getString(R.string.math_input_hint)) },
                        placeholder = { Text(getString(R.string.math_input_example)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                    )

                    TemplateRow(
                        onInsert = ::insertTemplate,
                    )

                    TextButton(
                        onClick = { openFormulaLauncher.launch(FORMULA_MIMES) },
                        modifier = Modifier.semantics {
                            contentDescription = getString(R.string.math_bs_import_file)
                        },
                    ) {
                        Text(getString(R.string.math_bs_import_file))
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = ::convert,
                            enabled = !converting,
                            modifier = Modifier.weight(1f),
                        ) { Text(getString(R.string.math_convert)) }
                        TextButton(
                            onClick = ::pushToDisplay,
                            enabled = result != null,
                            modifier = Modifier.weight(1f),
                        ) { Text(getString(R.string.math_push)) }
                        TextButton(
                            onClick = ::copyResult,
                            enabled = result != null,
                            modifier = Modifier.weight(1f),
                        ) { Text(getString(R.string.math_copy)) }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        DotRadioRow(
                            title = getString(R.string.tactile_strip_dots_6),
                            selected = dotsPerCell == 6,
                            onClick = { dotsPerCell = 6 },
                        )
                        DotRadioRow(
                            title = getString(R.string.tactile_strip_dots_8),
                            selected = dotsPerCell == 8,
                            onClick = { dotsPerCell = 8 },
                        )
                    }

                    result?.let { braille ->
                        Text(
                            text = braille.unicode,
                            style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    status?.let { message ->
                        Text(
                            text = message,
                            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        display?.close()
        display = null
        observeClient?.shutdown()
        observeClient = null
        // 桥的创建/销毁要在同一后台线程：切回 IO 再关。
        lifecycleScope.launch(Dispatchers.IO) { chineseProvider?.close() }
        chineseProvider = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 转换

    private fun convert() {
        val text = inputField.text.trim()
        if (text.isEmpty()) {
            showStatus(getString(R.string.math_need_input))
            return
        }
        converting = true
        status = getString(R.string.math_converting)
        lifecycleScope.launch(Dispatchers.IO) {
            val outcome = try {
                MathBraille.encode(text, dotsPerCell, chineseProvider) to null
            } catch (e: Exception) {
                null to e.message
            }
            withContext(Dispatchers.Main) {
                converting = false
                val (braille, error) = outcome
                if (braille != null) {
                    result = braille
                    showStatus(getString(R.string.math_converted))
                } else {
                    result = null
                    showStatus(getString(R.string.math_error, error ?: ""))
                }
            }
        }
    }

    // ------------------------------------------------------------------ 出口

    private fun pushToDisplay() {
        val braille = result ?: return
        val bytes = ByteArray(braille.cells.size) { braille.cells[it].toByte() }
        display?.show(bytes, braille.unicode)
        val note = if (displayConnected) {
            getString(R.string.math_connected)
        } else {
            getString(R.string.math_not_connected)
        }
        showStatus(getString(R.string.math_push_done, note))
    }

    private fun copyResult() {
        val braille = result ?: return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("braille", braille.unicode))
        Toast.makeText(this, R.string.math_copied, Toast.LENGTH_SHORT).show()
    }

    override fun onConnectionStateChanged(state: Int) {
        displayConnected = state == Display.STATE_CONNECTED
    }

    // ------------------------------------------------------------------ 文件导入

    /** SAF Uri 受限读字节，IO 线程解码后回主线程回填；不自动触发转换。 */
    private fun readFormulaUri(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            val outcome = try {
                contentResolver.openInputStream(uri)?.use { readCapped(it) }
                    ?: ImportOutcome.Failed("无法打开文件")
            } catch (e: Exception) {
                ImportOutcome.Failed(e.message ?: "读取异常")
            }
            withContext(Dispatchers.Main) { applyImport(outcome) }
        }
    }

    /** DEBUG 钩子：按绝对路径读本地公式文件（仅 BuildConfig.DEBUG 分支可达）。 */
    private fun readFormulaFile(path: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val outcome = try {
                val file = File(path)
                if (!file.isFile) ImportOutcome.Failed("文件不存在：$path")
                else readCapped(file.inputStream())
            } catch (e: Exception) {
                ImportOutcome.Failed(e.message ?: "读取异常")
            }
            withContext(Dispatchers.Main) { applyImport(outcome) }
        }
    }

    /** 带 256 KB 上限的流式读取；超限直接判 TooLarge，不把整份大文件载入内存。 */
    private fun readCapped(input: InputStream): ImportOutcome {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var total = 0
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            total += read
            if (total > MathFileImport.MAX_FILE_BYTES) return ImportOutcome.TooLarge
            buffer.write(chunk, 0, read)
        }
        return MathFileImport.decode(buffer.toByteArray())
    }

    /** 回填输入框或给可读提示；空 / 超限 / 失败都不清空既有结果以外的状态。 */
    private fun applyImport(outcome: ImportOutcome) {
        when (outcome) {
            is ImportOutcome.Ok -> {
                inputField = TextFieldValue(outcome.text, selection = TextRange(outcome.text.length))
                result = null
                showStatus(getString(R.string.math_bs_file_loaded))
            }
            ImportOutcome.Empty -> showStatus(getString(R.string.math_bs_file_empty))
            ImportOutcome.TooLarge -> showStatus(getString(R.string.math_bs_file_too_large))
            is ImportOutcome.Failed ->
                showStatus(getString(R.string.math_bs_file_failed, outcome.reason))
        }
    }

    // ------------------------------------------------------------------ 模板插入

    /**
     * 插入 MathML 骨架并把光标落到第一个可编辑槽位（`<mi>` 开标签之后）。
     * 骨架覆盖分数 / 根号 / 上下标 / 2×2 矩阵 / 两行方程组，用户无需手打 LaTeX/MathML。
     */
    private fun insertTemplate(skeleton: String) {
        val tag = "<mi>"
        val slot = skeleton.indexOf(tag).let { if (it >= 0) it + tag.length else skeleton.length }
        inputField = TextFieldValue(skeleton, selection = TextRange(slot))
        result = null
        showStatus(getString(R.string.math_bs_inserted))
    }

    // ------------------------------------------------------------------ 状态

    private fun showStatus(message: String) {
        status = message
        window.decorView.announceForAccessibility(message)
    }
}

/**
 * 文件选择 MIME 组合：纯文本、XML、XHTML。`.mathml` 无独立注册 MIME，设备 provider
 * 通常按 `text/plain` 或 `application/xml` 暴露，已被前两类覆盖。
 */
private val FORMULA_MIMES = arrayOf("text/*", "application/xml", "application/xhtml+xml")

/**
 * 模板按钮排：分数 / 根号 / 上下标 / 2×2 矩阵 / 方程组。
 * 文案来自独立文案文件 strings_math_ext.xml（math_bs_ 前缀），不碰 strings.xml。
 */
@Composable
private fun TemplateRow(onInsert: (String) -> Unit) {
    val context = LocalContext.current
    val templates = listOf(
        R.string.math_bs_fraction to
            "<math><mfrac><mi>a</mi><mi>b</mi></mfrac></math>",
        R.string.math_bs_root to
            "<math><msqrt><mi>x</mi></msqrt></math>",
        R.string.math_bs_script to
            "<math><msubsup><mi>x</mi><mi>i</mi><mn>2</mn></msubsup></math>",
        R.string.math_bs_matrix to
            "<math><mrow><mo>(</mo><mtable>" +
                "<mtr><mtd><mi>a</mi></mtd><mtd><mi>b</mi></mtd></mtr>" +
                "<mtr><mtd><mi>c</mi></mtd><mtd><mi>d</mi></mtd></mtr>" +
                "</mtable><mo>)</mo></mrow></math>",
        R.string.math_bs_cases to
            "<math><mrow><mo>{</mo><mtable>" +
                "<mtr><mtd><mi>x</mi><mo>+</mo><mi>y</mi></mtd></mtr>" +
                "<mtr><mtd><mi>x</mi><mo>−</mo><mi>y</mi></mtd></mtr>" +
                "</mtable></mrow></math>",
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = context.getString(R.string.math_bs_templates_label),
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            templates.forEach { (labelRes, skeleton) ->
                TextButton(onClick = { onInsert(skeleton) }) {
                    Text(context.getString(labelRes))
                }
            }
        }
    }
}

/**
 * 6/8 点单选行：整行 `selectable` + `Role.RadioButton`，选中态用 stateDescription 播报，
 * TalkBack 可读当前档位（对齐触觉页单选行做法，仅作用于数学页，不改触觉页）。
 */
@Composable
private fun DotRadioRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val stateDesc = MathAccessibility.dotStateDescription(
        selected,
        context.getString(R.string.math_bs_selected),
        context.getString(R.string.math_bs_unselected),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) { stateDescription = stateDesc }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(title)
    }
}
