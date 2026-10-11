package com.macrosniper.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * Activity trong suốt, chỉ để mở bộ chọn tệp của hệ thống (dịch vụ nổi không tự mở được).
 * Xuất: tạo tệp .json · Nhập: đọc tệp .json rồi lưu vào danh sách macro đã lưu.
 */
class ShareActivity : Activity() {

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_EXPORT = "export"
        const val MODE_IMPORT = "import"
        const val EXTRA_SW = "sw"
        const val EXTRA_SH = "sh"
        private const val RC_EXPORT = 11
        private const val RC_IMPORT = 12
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return // tạo lại do xoay màn hình: vẫn đang chờ kết quả
        try {
            when (intent.getStringExtra(EXTRA_MODE)) {
                MODE_EXPORT -> {
                    val safe = MacroShare.pendingName.replace(Regex("[^\\p{L}\\p{N}_-]+"), "_").trim('_').ifEmpty { "macro" }
                    val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
                    i.addCategory(Intent.CATEGORY_OPENABLE)
                    i.type = "application/json"
                    i.putExtra(Intent.EXTRA_TITLE, "MacroTouch_$safe.json")
                    startActivityForResult(i, RC_EXPORT)
                }
                MODE_IMPORT -> {
                    val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
                    i.addCategory(Intent.CATEGORY_OPENABLE)
                    i.type = "*/*" // tệp gửi qua app chat đôi khi bị đổi kiểu, nên không lọc theo kiểu
                    startActivityForResult(i, RC_IMPORT)
                }
                else -> done(null, false)
            }
        } catch (_: Exception) {
            done("Không mở được bộ chọn tệp", false)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            done(null, false)
            return
        }
        when (requestCode) {
            RC_EXPORT -> exportTo(uri)
            RC_IMPORT -> importFrom(uri)
            else -> done(null, false)
        }
    }

    private fun exportTo(uri: android.net.Uri) {
        val text = MacroShare.pendingExport
        MacroShare.pendingExport = null
        if (text == null) {
            done("Không có dữ liệu để xuất", false)
            return
        }
        try {
            contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                ?: throw IllegalStateException()
            done("Đã xuất macro", false)
        } catch (_: Exception) {
            done("Xuất macro thất bại", false)
        }
    }

    private fun importFrom(uri: android.net.Uri) {
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { ins ->
                val buf = java.io.ByteArrayOutputStream()
                val tmp = ByteArray(8192)
                while (true) {
                    val n = ins.read(tmp)
                    if (n < 0) break
                    buf.write(tmp, 0, n)
                    if (buf.size() > MacroShare.MAX_BYTES) throw IllegalArgumentException("big")
                }
                buf.toByteArray()
            } ?: throw IllegalStateException()
            val sw = intent.getIntExtra(EXTRA_SW, 0)
            val sh = intent.getIntExtra(EXTRA_SH, 0)
            val parsed = MacroShare.fromJson(String(bytes, Charsets.UTF_8), minOf(sw, sh), maxOf(sw, sh))
            if (parsed == null) {
                done("Tệp không phải macro của Macro Touch", false)
                return
            }
            val saved = ArrayList<String>()
            for ((name, list) in parsed) {
                var n = name
                var k = 2
                while (Store.macroNames(this).contains(n)) {
                    n = "$name ($k)" // trùng tên: không ghi đè macro của bạn
                    k++
                }
                Store.saveMacro(this, n, list)
                saved.add(n)
            }
            done("Đã nhập: " + saved.joinToString(", "), true)
        } catch (_: IllegalArgumentException) {
            done("Tệp quá lớn, không phải macro", false)
        } catch (_: Exception) {
            done("Nhập macro thất bại", false)
        }
    }

    private fun done(msg: String?, imported: Boolean) {
        if (msg != null) Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
        MacroService.instance?.onShareDone(imported)
        finish()
    }
}
