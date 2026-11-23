package com.fanxing.xbox.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/** 下载完成后打开文件：视频/PDF 用内置开源组件，其余交系统 */
object OpenFile {

    private val VIDEO = setOf("mp4", "mkv", "webm", "mov", "avi", "flv", "ts", "m3u8", "m4v", "3gp")
    private val AUDIO = setOf("mp3", "flac", "wav", "aac", "ogg", "m4a")

    fun open(ctx: Context, path: String) {
        val f = File(path)
        if (!f.exists()) { toast(ctx, "文件不存在"); return }
        val ext = f.extension.lowercase()

        when {
            ext in VIDEO || ext in AUDIO -> {
                ctx.startActivity(Intent(ctx, PlayerActivity::class.java).putExtra("path", path))
                return
            }
            ext == "pdf" -> {
                ctx.startActivity(Intent(ctx, PdfActivity::class.java).putExtra("path", path))
                return
            }
        }

        // 其余交给系统（APK 安装、图片、压缩包…）
        runCatching {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fp", f)
            val mime = when (ext) {
                "apk" -> "application/vnd.android.package-archive"
                "jpg", "jpeg", "png", "webp", "gif" -> "image/*"
                "zip", "rar", "7z" -> "application/zip"
                "txt", "md", "log" -> "text/plain"
                else -> "*/*"
            }
            val i = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(Intent.createChooser(i, "打开方式").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { toast(ctx, "没有可打开该文件的应用：${it.message}") }
    }

    private fun toast(ctx: Context, s: String) =
        Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()
}
