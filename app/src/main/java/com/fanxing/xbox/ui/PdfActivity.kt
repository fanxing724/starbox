package com.fanxing.xbox.ui

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.fanxing.xbox.databinding.ActivityPdfBinding
import java.io.File

/**
 * PDF 预览：用系统自带 android.graphics.pdf.PdfRenderer（API 21+）。
 * **零第三方依赖**，不打包 pdfium 的 .so。
 */
class PdfActivity : AppCompatActivity() {

    private lateinit var b: ActivityPdfBinding
    private var fd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var index = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Palette.apply(this)
        b = ActivityPdfBinding.inflate(layoutInflater)
        setContentView(b.root)

        val path = intent.getStringExtra("path") ?: ""
        val uriStr = intent.getStringExtra("uri")
        try {
            fd = if (!uriStr.isNullOrBlank()) {
                contentResolver.openFileDescriptor(android.net.Uri.parse(uriStr), "r")
            } else {
                val f = File(path)
                if (!f.exists()) { toast("文件不存在"); finish(); return }
                ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
            }
            if (fd == null) { toast("无法打开文件"); finish(); return }
            renderer = PdfRenderer(fd!!)
        } catch (e: Exception) {
            toast("打开 PDF 失败：${e.message}"); finish(); return
        }

        b.btnPrev.setOnClickListener { if (index > 0) { index--; render() } }
        b.btnNext.setOnClickListener {
            if (index < (renderer?.pageCount ?: 0) - 1) { index++; render() }
        }
        render()
    }

    private fun render() {
        val r = renderer ?: return
        if (r.pageCount == 0) { toast("空文档"); return }
        r.openPage(index).use { p ->
            val scale = 2
            val bmp = Bitmap.createBitmap(
                p.width * scale, p.height * scale, Bitmap.Config.ARGB_8888
            )
            bmp.eraseColor(android.graphics.Color.WHITE)
            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            b.page.setImageBitmap(bmp)
        }
        b.pageInfo.text = "${index + 1} / ${r.pageCount}"
        b.btnPrev.isEnabled = index > 0
        b.btnNext.isEnabled = index < r.pageCount - 1
    }

    override fun onDestroy() {
        runCatching { renderer?.close() }
        runCatching { fd?.close() }
        super.onDestroy()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
