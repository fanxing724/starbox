package com.fanxing.xbox.store

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用内运行日志：内存环形缓冲 + 落盘，供「运行日志」页查看/复制/导出。
 *
 * 只记排查所需的步骤、HTTP 状态与业务错误码；
 * **绝不记录 Cookie / Token / 完整签名 URL**，写入前统一脱敏。
 */
object AppLog {

    enum class Level { D, I, W, E }

    data class Entry(val time: Long, val level: Level, val tag: String, val msg: String)

    private const val MAX_MEM = 800
    private const val MAX_FILE_BYTES = 512L * 1024
    private val buf = ArrayDeque<Entry>()
    private val lock = Any()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile private var logFile: File? = null

    fun init(ctx: Context) {
        val dir = File(ctx.applicationContext.filesDir, "logs")
        dir.mkdirs()
        logFile = File(dir, "starbox.log")
    }

    /** 脱敏：Cookie/Token/签名参数一律打码，避免日志泄露凭据 */
    private fun redact(s: String): String {
        var out = s
        // 只打码「像令牌」的值：长度>=8 且不含中文/空白，
        // 否则会把「含__pugs=是」这种自己的诊断文案也替换掉
        out = out.replace(
            Regex("(?i)(cookie|authorization|__pugs|__puus|__pus|stoken|access_token|share_fid_token|captcha_token|BDUSS|p_skey)\\s*[=:]\\s*([A-Za-z0-9._~%+*\\-]{8,})"),
            "$1=***"
        )
        // 签名 URL 的查询串整体打码，只留 host+path
        out = out.replace(Regex("(https?://[^\\s?]+)\\?[^\\s]+"), "$1?<query已省略>")
        return out
    }

    private fun add(level: Level, tag: String, msg: String) {
        val e = Entry(System.currentTimeMillis(), level, tag, redact(msg))
        synchronized(lock) {
            buf.addLast(e)
            while (buf.size > MAX_MEM) buf.removeFirst()
        }
        val f = logFile ?: return
        runCatching {
            synchronized(lock) {
                if (f.length() > MAX_FILE_BYTES) {
                    val old = File(f.parentFile, "starbox.log.1")
                    old.delete(); f.renameTo(old)
                }
                f.appendText("${fmt.format(Date(e.time))} ${e.level.name}/$tag: ${e.msg}\n")
            }
        }
    }

    fun d(tag: String, msg: String) = add(Level.D, tag, msg)
    fun i(tag: String, msg: String) = add(Level.I, tag, msg)
    fun w(tag: String, msg: String) = add(Level.W, tag, msg)
    fun e(tag: String, msg: String) = add(Level.E, tag, msg)

    /** 记录异常，带类型便于区分网络错误与业务错误 */
    fun err(tag: String, msg: String, t: Throwable?) =
        add(Level.E, tag, msg + (t?.let { " | ${it.javaClass.simpleName}: ${it.message}" } ?: ""))

    fun entries(): List<Entry> = synchronized(lock) { buf.toList() }

    fun text(): String = entries().joinToString("\n") {
        "${fmt.format(Date(it.time))} ${it.level.name}/${it.tag}: ${it.msg}"
    }

    fun clear() {
        synchronized(lock) {
            buf.clear()
            runCatching { logFile?.writeText("") }
            runCatching { File(logFile?.parentFile, "starbox.log.1").delete() }
        }
    }

    fun file(): File? = logFile
}
