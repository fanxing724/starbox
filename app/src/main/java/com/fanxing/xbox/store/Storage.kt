package com.fanxing.xbox.store

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * 下载落盘位置：系统公共 Download 文件夹。
 *
 * Android 10+ 分区存储下不能直接用 File 写公共目录，
 * 必须走 MediaStore.Downloads；拿到的 Uri 可以用 "rw" 模式打开
 * ParcelFileDescriptor，从而支持 RandomAccessFile 的 seek —— 分片并发才写得进去。
 *
 * Android 9 及以下仍用 File API（需 WRITE_EXTERNAL_STORAGE）。
 */
object Storage {

    /** 一个下载目标：uri 非空走 MediaStore，否则走 file */
    data class Target(
        val uri: Uri?,
        val file: File?,
        val displayName: String,
        val displayPath: String
    )

    fun publicDirLabel(): String =
        if (Build.VERSION.SDK_INT >= 29) "内部存储/Download"
        else Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath

    private fun mimeOf(name: String): String {
        val e = name.substringAfterLast('.', "").lowercase()
        return when (e) {
            "apk" -> "application/vnd.android.package-archive"
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "mp3" -> "audio/mpeg"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "txt", "log", "md" -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    /** 重名时自动加序号，避免覆盖已有文件 */
    private fun uniqueName(ctx: Context, name: String): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val res = ctx.contentResolver
            var candidate = name
            var n = 1
            val base = name.substringBeforeLast('.', name)
            val ext = name.substringAfterLast('.', "")
            while (existsInDownloads(res, candidate)) {
                candidate = if (ext.isEmpty()) "$base($n)" else "$base($n).$ext"
                n++
                if (n > 999) break
            }
            return candidate
        }
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        dir.mkdirs()
        var f = File(dir, name)
        var n = 1
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        while (f.exists()) {
            val nn = if (ext.isEmpty()) "$base($n)" else "$base($n).$ext"
            f = File(dir, nn); n++
            if (n > 999) break
        }
        return f.name
    }

    private fun existsInDownloads(res: android.content.ContentResolver, name: String): Boolean =
        runCatching {
            res.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?",
                arrayOf(name, Environment.DIRECTORY_DOWNLOADS + "/"),
                null
            )?.use { it.moveToFirst() } ?: false
        }.getOrDefault(false)

    fun create(ctx: Context, rawName: String): Target {
        val safe = rawName.ifBlank { "file" }.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val name = uniqueName(ctx, safe)

        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mimeOf(name))
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver
                .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("无法在 Download 目录创建文件")
            return Target(uri, null, name, "Download/$name")
        }

        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        dir.mkdirs()
        val f = File(dir, name)
        if (!f.createNewFile() && !f.exists()) {
            throw IllegalStateException("无法写入 Download 目录，请检查存储权限")
        }
        return Target(null, f, name, f.absolutePath)
    }

    /** 下载完成后解除 pending，让文件管理器/其他应用可见。返回是否发布成功 */
    fun finish(ctx: Context, t: Target): Boolean {
        if (Build.VERSION.SDK_INT >= 29 && t.uri != null) {
            return runCatching {
                val n = ctx.contentResolver.update(
                    t.uri,
                    ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                    null, null
                )
                n > 0
            }.getOrDefault(false)
        }
        return true   // 旧系统走 File，写完即可见
    }

    fun delete(ctx: Context, uri: String?, path: String?) {
        if (Build.VERSION.SDK_INT >= 29 && !uri.isNullOrBlank()) {
            runCatching { ctx.contentResolver.delete(Uri.parse(uri), null, null) }
        } else if (!path.isNullOrBlank()) {
            runCatching { File(path).delete() }
        }
    }
}
