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

    /** 本次启动前，磁盘上已有一份日志（含上一次运行 / 上一次崩溃） */
    @Volatile var hasPreviousLog: Boolean = false
        private set

    fun init(ctx: Context) {
        val dir = File(ctx.applicationContext.filesDir, "logs")
        dir.mkdirs()
        logFile = File(dir, "starbox.log")
        loadFromDisk()
    }

    /**
     * 把上一次运行的日志读回内存。
     * 关键场景：应用闪退后来不及复制日志，重启后必须还能看到现场。
     */
    private fun loadFromDisk() {
        val f = logFile ?: return
        if (!f.exists() || f.length() == 0L) return
        val size = f.length()
        val lines = runCatching { f.readLines() }.getOrNull() ?: return
        val restored = LogCodec.decode(lines)
        synchronized(lock) {
            buf.clear()
            buf.addAll(restored)
            while (buf.size > MAX_MEM) buf.removeFirst()
        }
        // 自证：文件多大、还原出多少条。下次再出现「只有一条分隔线」就能立刻判断原因
        add(
            Level.I, "AppLog",
            "读取上次日志：文件=${size}B 行数=${lines.size} 还原=${restored.size}条 " +
                "本次路径=${f.absolutePath}"
        )
        if (restored.isNotEmpty()) hasPreviousLog = true
        // 打一条分隔，明确区分两次运行，避免排查时把旧记录当成新的
        add(Level.D, "AppLog", "—— 以上为上一次运行的日志 ——")
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
                f.appendText(LogCodec.encode(e) + "\n")
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

    /** 供单测直接调用纯解析逻辑 */
    internal fun testDecode(lines: List<String>) = LogCodec.decode(lines)
    internal fun testParse(line: String) = LogCodec.parse(line, 0L)

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

    /** 崩溃现场另存一份，主日志轮转/清空都不影响 */
    fun dumpCrash(ctx: Context, stack: String) {
        runCatching {
            val dir = File(ctx.applicationContext.filesDir, "logs")
            dir.mkdirs()
            File(dir, "crash.log").appendText(
                "===== ${fmt.format(Date())} =====\n$stack\n"
            )
        }
    }

    fun file(): File? = logFile
}

/**
 * 日志文件的读写编解码。抽出来是为了能单测：
 * 崩后重开必须能把上一次运行的内容完整还原。
 */
internal object LogCodec {

    private val lineRegex =
        Regex("^(\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}) ([DIWE])/([^:]{1,32}): (.*)$", RegexOption.DOT_MATCHES_ALL)

    fun encode(e: AppLog.Entry): String =
        "${e.time} ${e.level.name}/${e.tag}: ${e.msg}"

    /** 解析一行；不是标准格式则返回 null（视为上一条的续行，比如堆栈） */
    fun parse(line: String, base: Long): Pair<AppLog.Entry, String>? {
        val m = lineRegex.find(line) ?: return null
        val lvl = runCatching { AppLog.Level.valueOf(m.groupValues[2]) }.getOrNull() ?: return null
        val tag = m.groupValues[3].take(32)
        val msg = m.groupValues[4]
        return AppLog.Entry(base, lvl, tag, msg) to msg
    }

    /**
     * 把整份文件还原成条目列表：
     * 标准行直接成条；非标准行（堆栈续行、异常换行）并入上一条。
     */
    fun decode(lines: List<String>): List<AppLog.Entry> {
        val out = ArrayList<AppLog.Entry>()
        var base = System.currentTimeMillis()
        for (raw in lines) {
            val line = raw.trimEnd('\n', '\r')
            if (line.isBlank()) continue
            val parsed = parse(line, base)
            if (parsed == null) {
                if (out.isNotEmpty()) {
                    val last = out.removeAt(out.size - 1)
                    out.add(last.copy(msg = last.msg + "\n" + line))
                }
                continue
            }
            val (entry, _) = parsed
            out.add(entry)
            base += 1   // 同一毫秒的多条按顺序递增，保证「最新在末尾」
        }
        return out
    }
}
