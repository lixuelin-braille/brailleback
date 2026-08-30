/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 盲文阅读器：用系统文件选择器打开 BRF / 文本文件，
 * 把 ASCII 盲文(0x20-0x5F)或 Unicode 盲文(U+2800-)转为 Unicode 盲文分页显示，
 * 并可一键发送当前页到点字显示器。
 */

package com.googlecode.eyesfree.brailleback

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.googlecode.eyesfree.braille.display.Display
import com.googlecode.eyesfree.braille.display.DisplayClient
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

class BrailleReaderActivity : Activity(), Display.OnConnectionStateChangeListener {

    private var mDisplay: DisplayClient? = null
    private var mReaderText: TextView? = null
    private var mBtnSend: Button? = null
    private var mPages: List<String> = emptyList()
    private var mPage = 0
    private var mConnected = false
    private val mLinePerPage = 20

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.braille_reader)
        mReaderText = findViewById(R.id.reader_text)
        mBtnSend = findViewById(R.id.btn_send)
        findViewById<Button>(R.id.btn_open).setOnClickListener { openFile() }
        findViewById<Button>(R.id.btn_prev).setOnClickListener { prevPage() }
        findViewById<Button>(R.id.btn_next).setOnClickListener { nextPage() }
        mBtnSend?.setOnClickListener { sendToDisplay() }
    }

    override fun onResume() {
        super.onResume()
        val display = DisplayClient(this)
        mDisplay = display
        display.setOnConnectionStateChangeListener(this)
        onConnectionStateChanged(Display.STATE_NOT_CONNECTED)
    }

    override fun onPause() {
        super.onPause()
        mDisplay?.shutdown()
        mDisplay = null
    }

    override fun onConnectionStateChanged(state: Int) {
        mConnected = state == Display.STATE_CONNECTED
        runOnUiThread { mBtnSend?.isEnabled = mConnected }
    }

    private fun openFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES,
                arrayOf("text/plain", "application/octet-stream"))
        }
        startActivityForResult(intent, REQUEST_OPEN)
    }

    override fun onActivityResult(
        requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_OPEN && resultCode == Activity.RESULT_OK) {
            data?.data?.let { loadFile(it) }
        }
    }

    private fun loadFile(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val reader = BufferedReader(
                    InputStreamReader(stream, StandardCharsets.UTF_8))
                val brailleLines = ArrayList<String>()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    brailleLines.add(toUnicodeBraille(line ?: ""))
                }
                mPages = brailleLines.chunked(mLinePerPage)
                    .map { it.joinToString("\n") }
                mPage = 0
                showPage()
            }
        } catch (e: Exception) {
            Toast.makeText(this,
                getString(R.string.braille_reader_load_fail, e.message),
                Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Convert a line of ASCII braille (0x20-0x5F) to Unicode braille.
     * Unicode braille (U+2800-) and plain text are kept as-is.
     */
    private fun toUnicodeBraille(line: String): String {
        val sb = StringBuilder(line.length)
        for (c in line) {
            sb.append(when {
                c.code in 0x2800..0x28FF -> c
                c.code in 0x20..0x5F ->
                    (0x2800 + (c.code - 0x20)).toChar()
                else -> c
            })
        }
        return sb.toString()
    }

    private fun showPage() {
        if (mPages.isEmpty()) {
            mReaderText?.setText(R.string.braille_reader_hint)
            return
        }
        mReaderText?.text = mPages[mPage]
    }

    private fun prevPage() {
        if (mPages.isEmpty() || mPage <= 0) return
        mPage--
        showPage()
    }

    private fun nextPage() {
        if (mPages.isEmpty() || mPage >= mPages.size - 1) return
        mPage++
        showPage()
    }

    private fun sendToDisplay() {
        val text = mReaderText?.text?.toString() ?: return
        val display = mDisplay ?: return
        val len = text.length
        if (len == 0) return
        val patterns = ByteArray(len)
        val map = IntArray(len)
        for (i in 0 until len) {
            val c = text[i]
            val dots = when {
                c.code in 0x2800..0x28FF -> c.code - 0x2800
                c.code in 0x20..0x5F -> c.code - 0x20
                else -> 0
            }
            patterns[i] = (dots and 0xFF).toByte()
            map[i] = i
        }
        display.displayDots(patterns, text, map)
    }

    companion object {
        private const val REQUEST_OPEN = 1
    }
}
