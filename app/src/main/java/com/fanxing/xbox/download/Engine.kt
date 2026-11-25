package com.fanxing.xbox.download

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.fanxing.xbox.drive.Http
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

enum class State { QUEUED, RUNNING, PAUSED, DONE, ERROR }

class Chunk(
    val index: Int,
    val start: Long,
    val end: Long,
    @Volatile var done: Long = 0,
    /** 当前用第几条源；4xx 被拒时顺延到下一条（主链/镜像/备用线路） */
    @Volatile var srcIdx: Int = 0
) {
    val length: Long get() = end - start + 1
    val finished: Boolean get() = done >= length
}

class Task(
    val id: String,
    var name: String,
    val sources: List<String>,
    val headers: Map<String, String>,
    var savePath: String,
    /** 非空表示落盘到 MediaStore（系统 Download），savePath 此时仅作展示 */
    var uri: String? = null,
    var displayPath: String = savePath,
    var size: Long = 0,
    var state: State = State.QUEUED,
    var error: String = "",
    var speed: Long = 0
) {
    val chunks = CopyOnWriteArrayList<Chunk>()
    val downloaded: Long get() = chunks.sumOf { it.done }
    val percent: Int
        get() = if (size <= 0) 0 else ((downloaded * 100) / size).toInt().coerceIn(0, 100)
    internal var job: Job? = null
    internal val cancelFlag = java.util.concurrent.atomic.AtomicBoolean(false)
    /** 旧执行是否还在跑（防止快速暂停/继续时两份执行写同一文件） */
    @Volatile internal var running: Boolean = false
    /** 旧执行未退出时收到的继续请求，等它退出后再入队 */
    @Volatile internal var resumeRequested: Boolean = false
}

/**
 * 下载引擎。
 *
 * 加速原理：网盘限的是「单连接速度」，不是总速度。
 *   1) 把文件切成 N 段，N 条连接同时拉（Range 请求）
 *   2) 有镜像节点时，把直链 host 换成多个 CDN 节点，再各开若干连接
 *   3) 每段独立断点续传，失败只重那一段
 *
 * 注意：这里不需要给 URL 补 "&" 制造唯一性 —— 那是给 IDM/aria2 这类
 * 外部下载器去重用的；本引擎自己发请求，同一 URL 可以并发请求多次。
 */
object Engine {

    @Volatile private lateinit var appCtx: Context
    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        restoreFromDisk()
    }

    /** 统一写入句柄。
     *  MediaStore 走 ParcelFileDescriptor + Os.pwrite（按偏移定位写，天然并发安全）；
     *  旧系统走 RandomAccessFile。每个分片各开一个，互不干扰。 */
    private class Sink(t: Task) : AutoCloseable {
        private val pfd: android.os.ParcelFileDescriptor? = t.uri?.let {
            appCtx.contentResolver.openFileDescriptor(Uri.parse(it), "rw")
        }
        private val raf: RandomAccessFile? =
            if (pfd == null) RandomAccessFile(File(t.savePath), "rw") else null

        fun preallocate(size: Long) {
            if (pfd != null) android.system.Os.ftruncate(pfd.fileDescriptor, size)
            else raf!!.setLength(size)
        }

        fun writeAt(offset: Long, buf: ByteArray, len: Int) {
            if (pfd != null) {
                // pwrite 按偏移定位写，多分片共用一个 fd 也安全
                var done = 0
                while (done < len) {
                    val n = android.system.Os.pwrite(
                        pfd.fileDescriptor,
                        java.nio.ByteBuffer.wrap(buf, done, len - done),
                        offset + done
                    )
                    if (n <= 0) throw DriveErr("写入失败")
                    done += n
                }
            } else {
                // RandomAccessFile 的 seek+write 不是原子的，共用时必须串行
                synchronized(this) {
                    raf!!.seek(offset)
                    raf.write(buf, 0, len)
                }
            }
        }

        fun length(): Long =
            if (pfd != null) android.system.Os.fstat(pfd.fileDescriptor).st_size
            else raf!!.length()

        override fun close() {
            runCatching { raf?.close() }
            runCatching { pfd?.close() }
        }
    }

    private fun metaFile(t: Task): File =
        File(appCtx.filesDir, "meta/${t.id}.json")

    // 分片用的是阻塞式 execute()，Dispatchers.IO 默认只有 64 线程，
    // 开 256 分片时会有 192 个协程卡在等线程，实际并发被锁在 64。
    // 改用专用弹性线程池，让并发真正等于设定值。
    private val dlExecutor = java.util.concurrent.Executors.newCachedThreadPool { r ->
        Thread(r, "xbox-dl").apply { isDaemon = true }
    }
    private val scope = CoroutineScope(
        SupervisorJob() + dlExecutor.asCoroutineDispatcher()
    )
    private val main = Handler(Looper.getMainLooper())
    val tasks = CopyOnWriteArrayList<Task>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val activeTasks = AtomicInteger(0)
    /** 队列领取与状态转换必须串行，否则多个任务同时结束会重复领取 */
    private val queueLock = Any()
    private const val MAX_TOTAL_CONNS = 512

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun notifyUi() = main.post { listeners.forEach { runCatching { it() } } }

    fun submit(t: Task) {
        synchronized(queueLock) {
            tasks.add(t)
            persist(t)
        }
        notifyUi()
        pump()
    }

    fun find(id: String): Task? = tasks.firstOrNull { it.id == id }

    fun pause(id: String) {
        synchronized(queueLock) {
            val t = find(id) ?: return
            t.cancelFlag.set(true)
            t.resumeRequested = false
            if (!t.running) t.state = State.PAUSED
            t.speed = 0
            persist(t)
        }
        notifyUi()
    }

    fun resume(id: String) {
        synchronized(queueLock) {
            val t = find(id) ?: return
            if (t.state == State.RUNNING && t.running) return
            t.error = ""
            if (t.running) {
                // 旧执行还在收尾（可能卡在网络读），先记下意图，
                // 等它退出并关闭写入句柄后再重新入队，避免两份执行写同一文件
                t.resumeRequested = true
                t.state = State.PAUSED
            } else {
                t.cancelFlag.set(false)
                t.state = State.QUEUED
            }
        }
        notifyUi()
        pump()
    }

    fun remove(id: String) {
        synchronized(queueLock) {
            val t = find(id) ?: return
            t.cancelFlag.set(true)
            t.resumeRequested = false
            runCatching { com.fanxing.xbox.store.Storage.delete(appCtx, t.uri, t.savePath) }
            runCatching { metaFile(t).delete() }
            tasks.remove(t)
        }
        notifyUi()
    }

    private fun pump() {
        synchronized(queueLock) {
            while (activeTasks.get() < com.fanxing.xbox.store.CredsHolder.parallelTasks) {
                val next = tasks.firstOrNull { it.state == State.QUEUED && !it.running } ?: return
                next.state = State.RUNNING
                next.running = true
                next.cancelFlag.set(false)
                activeTasks.incrementAndGet()
                next.job = scope.launch {
                    try {
                        run(next)
                    } catch (e: Exception) {
                        next.state = State.ERROR
                        next.error = e.message ?: e.toString()
                    com.fanxing.xbox.store.AppLog.err("DL", "任务失败 ${next.name}", e)
                    } finally {
                        synchronized(queueLock) {
                            next.running = false
                            activeTasks.decrementAndGet()
                            next.speed = 0
                            if (next.resumeRequested) {
                                next.resumeRequested = false
                                next.cancelFlag.set(false)
                                next.state = State.QUEUED
                            }
                            persist(next)
                        }
                        notifyUi()
                        pump()
                    }
                }
            }
        }
        notifyUi()
    }

    // ---------------- 核心 ----------------

    private suspend fun run(t: Task) {
        if (t.uri == null) File(t.savePath).parentFile?.mkdirs()
        metaFile(t).parentFile?.mkdirs()

        // 1) 探测真实大小：必须用 Range，UC 的 HEAD 返回的 Content-Length 是错的
        com.fanxing.xbox.store.AppLog.i("DL", "开始 ${t.name} 源数=${t.sources.size} 预设大小=${t.size}")
        if (t.size <= 0) t.size = probeSize(t)
        if (t.size <= 0) throw DriveErr("无法获取文件大小（服务器未返回 Content-Range）")

        // 2) 载入/建立分片
        val fromMeta = loadMeta(t)
        if (!fromMeta) buildChunks(t, connectionsFor(t))
        normalizeChunks(t)
        com.fanxing.xbox.store.AppLog.i(
            "DL", "大小=${t.size} 分片数=${t.chunks.size} 来源=${if (fromMeta) "断点恢复" else "新建"} " +
                "已完成=${t.chunks.count { it.finished }} 待下=${t.chunks.count { !it.finished }}"
        )
        if (t.chunks.isEmpty()) throw DriveErr("分片为空，无法下载")

        // 3) 预分配文件；整个任务共用一个写入句柄，避免高并发分片各开一个 fd 撑爆 FD 上限
        val sink = openOrRecreate(t)
        sink.preallocate(t.size)

        // 4) 进度心跳
        val tick = scope.launch {
            var last = t.downloaded
            var beats = 0
            while (isActive && !t.cancelFlag.get()) {
                delay(1000)
                val now = t.downloaded
                t.speed = now - last
                last = now
                // 每 5 秒记一条进度，方便从日志判断是真在下还是卡住
                if (++beats % 5 == 0) {
                    com.fanxing.xbox.store.AppLog.i(
                        "DL", "进度 ${t.percent}% ${now}/${t.size} 速度=${t.speed}B/s " +
                            "完成片=${t.chunks.count { it.finished }}/${t.chunks.size}"
                    )
                }
                persist(t)
                notifyUi()
            }
        }

        try {
            // 5) 并发拉各分片
            val jobs = t.chunks.filter { !it.finished }.map { c ->
                scope.launch { fetchChunk(t, c, sink) }
            }
            com.fanxing.xbox.store.AppLog.i("DL", "已派发 ${jobs.size} 个分片协程")
            jobs.forEach { it.join() }
            com.fanxing.xbox.store.AppLog.i("DL", "分片协程全部返回 完成=${t.chunks.count { it.finished }}/${t.chunks.size}")

            if (t.cancelFlag.get()) {
                t.state = State.PAUSED
            } else if (t.chunks.all { it.finished }) {
                // 完工校验：分片覆盖 + 实际落盘长度都必须等于文件总长
                val covered = t.chunks.sumOf { it.length }
                val actual = runCatching { sink.length() }.getOrDefault(-1L)
                if (!ChunkMath.covers(t.chunks, t.size) || (actual >= 0 && actual != t.size)) {
                    t.state = State.ERROR
                    t.error = "长度校验失败：分片 $covered / 落盘 $actual / 期望 ${t.size}"
                    com.fanxing.xbox.store.AppLog.e("DL", "长度校验失败 分片=$covered 落盘=$actual 期望=${t.size}")
                } else {
                    // 必须发布成功才算完成，否则界面显示完成但文件管理器看不到
                    val published = com.fanxing.xbox.store.Storage.finish(
                        appCtx,
                        com.fanxing.xbox.store.Storage.Target(
                            t.uri?.let { Uri.parse(it) }, null, t.name, t.displayPath
                        )
                    )
                    if (published) {
                        t.state = State.DONE
                        com.fanxing.xbox.store.AppLog.i("DL", "完成 ${t.name} ${t.size}B")
                        metaFile(t).delete()
                    } else {
                        t.state = State.ERROR
                        t.error = "文件已下完，但发布到 Download 失败，请点继续重试"
                        com.fanxing.xbox.store.AppLog.e("DL", "MediaStore 发布失败 ${t.name}")
                    }
                }
            } else {
                t.state = State.ERROR
                t.error = "部分分片未完成"
            }
        } finally {
            tick.cancelAndJoin()
            runCatching { sink.close() }
            persist(t)
            notifyUi()
        }
    }

    /**
     * 打开写入句柄；MediaStore 条目已失效时重建。
     * 旧任务恢复后常报 FileNotFoundException: No item at content://media/...
     * （pending 条目被系统清理或用户删了文件）。旧进度对新文件无意义，必须归零。
     */
    private fun openOrRecreate(t: Task): Sink {
        try {
            return Sink(t)
        } catch (e: java.io.FileNotFoundException) {
            com.fanxing.xbox.store.AppLog.w("DL", "落盘目标失效，重建：${e.message}")
            val target = com.fanxing.xbox.store.Storage.create(appCtx, t.name)
            t.uri = target.uri?.toString()
            t.savePath = target.file?.absolutePath ?: target.displayPath
            t.displayPath = target.displayPath
            t.name = target.displayName
            t.chunks.forEach { it.done = 0 }
            persist(t)
            com.fanxing.xbox.store.AppLog.i("DL", "已重建目标 ${t.displayPath}，进度归零重下")
            return Sink(t)
        }
    }

    private fun connectionsFor(t: Task): Int {
        val n = com.fanxing.xbox.store.CredsHolder.connections
        return n.coerceIn(1, MAX_TOTAL_CONNS)
    }

    /** 有镜像就把连接摊到多个节点上 */
    private fun sourceFor(t: Task, c: Chunk): String =
        t.sources[c.srcIdx % t.sources.size]

    private fun probeSize(t: Task): Long {
        val req = Request.Builder().url(t.sources[0])
            .header("Range", "bytes=0-0")
            .apply { t.headers.forEach { (k, v) -> header(k, v) } }
            .get().build()
        Http.dl.newCall(req).execute().use { r ->
            val cr = r.header("Content-Range")
            if (cr != null && cr.contains("/")) {
                val total = cr.substringAfterLast("/").trim()
                total.toLongOrNull()?.let { if (it > 0) return it }
            }
            if (r.code == 200) {
                r.header("Content-Length")?.toLongOrNull()?.let { if (it > 0) return it }
            }
            com.fanxing.xbox.store.AppLog.e("DL", "探测失败 HTTP ${r.code} CR=${r.header("Content-Range")} CL=${r.header("Content-Length")}")
            throw DriveErr("探测失败 HTTP ${r.code}")
        }
    }

    private fun buildChunks(t: Task, conns: Int) {
        t.chunks.clear()
        t.chunks.addAll(ChunkMath.plan(t.size, conns))
        persist(t)
    }

    private fun normalizeChunks(t: Task) {
        t.chunks.forEach { c -> c.done = ChunkMath.clampDone(c.done, c.length) }
    }

    private suspend fun fetchChunk(t: Task, c: Chunk, sink: Sink) {
        var attempt = 0
        var lastErr = ""
        while (!c.finished && !t.cancelFlag.get() && attempt < 6) {
            try {
                val from = c.start + c.done
                val req = Request.Builder().url(sourceFor(t, c))
                    .header("Range", "bytes=$from-${c.end}")
                    .apply { t.headers.forEach { (k, v) -> header(k, v) } }
                    .get().build()

                var switched = false
                Http.dl.newCall(req).execute().use { r ->
                    if (r.code == 200) {
                        // 服务器忽略了 Range，回的是「整个文件」。
                        // 多分片时每片都会把文件开头写到自己的偏移上 → 静默损坏，必须拒绝。
                        if (from != 0L || t.chunks.size != 1) {
                            throw DriveErr("服务器不支持 Range（HTTP 200），无法分片下载")
                        }
                    } else if (r.code != 206) {
                        // 4xx 多为「这条链被拒/已失效」。若还有别的源（备用线路），
                        // 先换一条重试，而不是拿死链重复撞。（判断放在 lambda 外，
                        // 否则 continue 在 inline lambda 里属于实验特性）
                        if (r.code in 401..499 && c.srcIdx + 1 < t.sources.size) {
                            c.srcIdx++
                            com.fanxing.xbox.store.AppLog.i(
                                "DL", "片${c.index} HTTP ${r.code}，切换到第 ${c.srcIdx + 1}/${t.sources.size} 条源"
                            )
                            switched = true
                            return@use   // 不在 inline lambda 里 continue（属实验特性），改由外层处理
                        }
                        val snippet = runCatching {
                            r.peekBody(200).string()
                        }.getOrDefault("")
                        com.fanxing.xbox.store.AppLog.e(
                            "DL", "片${c.index} HTTP ${r.code} 源=${c.srcIdx + 1}/${t.sources.size} 响应=${snippet.ifBlank { "(空)" }}"
                        )
                        throw DriveErr("分片 ${c.index} HTTP ${r.code}")
                    } else {
                        // 206 要完整校验区间：起点、终点、总大小
                        ChunkMath.checkRange(r.header("Content-Range"), from, c.end, t.size)
                            ?.let { throw DriveErr("分片 ${c.index} $it") }
                    }
                    if (attempt == 0 && c.done == 0L) {
                        com.fanxing.xbox.store.AppLog.d(
                            "DL", "片${c.index} HTTP ${r.code} CR=${r.header("Content-Range") ?: "无"} CL=${r.header("Content-Length") ?: "无"}"
                        )
                    }
                    val body = r.body ?: throw DriveErr("分片 ${c.index} 空响应")
                    run {
                        var pos = from
                        val buf = ByteArray(64 * 1024)
                        val input = body.byteStream()
                        while (!t.cancelFlag.get()) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            // 限长写入：服务器多发也不能越过本片边界去踩相邻分片
                            val want = ChunkMath.writable(n, pos, c.end)
                            if (want <= 0) break
                            sink.writeAt(pos, buf, want)
                            pos += want
                            c.done += want
                            if (want < n || c.finished) break
                        }
                    }
                }
                if (switched) continue   // 已切到下一条源，重新请求
                if (c.finished) return
                // 连接被提前关闭但没下完 —— 继续续传
                attempt++
            } catch (e: Exception) {
                if (t.cancelFlag.get()) return
                attempt++
                t.error = e.message ?: "分片重试"
                lastErr = e.message ?: ""
                // 逐片每轮都刷会把日志冲爆（36 片 × 6 轮 ≈ 400 行），
                // 只在第 1 轮详记，后续只累计，耗尽时统一报。
                if (attempt == 1) {
                    com.fanxing.xbox.store.AppLog.w("DL", "分片 ${c.index} 首次失败：${e.message}")
                }
                delay(800L * attempt)
            }
        }
        if (!c.finished && !t.cancelFlag.get()) {
            com.fanxing.xbox.store.AppLog.e(
                "DL", "分片 ${c.index} 重试 $attempt 次后仍失败：${lastErr}"
            )
            throw DriveErr("分片 ${c.index} 重试耗尽")
        }
    }

    // ---------------- 断点续传元数据 ----------------

    private fun persist(t: Task) {
        if (t.size <= 0) return
        // 心跳与暂停可能同时触发，必须串行 + 原子替换，避免写半截 JSON
        synchronized(t) {
            runCatching {
                val arr = JSONArray()
                t.chunks.forEach { c ->
                    arr.put(JSONObject().put("i", c.index).put("s", c.start).put("e", c.end).put("d", c.done))
                }
                val src = JSONArray(); t.sources.forEach { src.put(it) }
                val hdr = JSONObject(); t.headers.forEach { (k, v) -> hdr.put(k, v) }
                val o = JSONObject()
                    .put("size", t.size).put("name", t.name).put("chunks", arr)
                    // 任务描述符：进程重启后据此重建队列
                    .put("id", t.id).put("sources", src).put("headers", hdr)
                    .put("savePath", t.savePath)
                    .put("uri", t.uri ?: "")
                    .put("displayPath", t.displayPath)
                val f = metaFile(t)
                f.parentFile?.mkdirs()
                val tmp = File(f.absolutePath + ".tmp")
                tmp.writeText(o.toString())
                if (!tmp.renameTo(f)) { f.writeText(o.toString()); tmp.delete() }
            }
        }
    }

    /** 进程重启后从磁盘重建未完成任务（一律置为暂停，由用户决定何时继续） */
    fun restoreFromDisk() {
        val dir = File(appCtx.filesDir, "meta")
        val files = dir.listFiles { f -> f.name.endsWith(".json") } ?: return
        val restored = ArrayList<Task>()
        files.forEach { f ->
            runCatching {
                val o = JSONObject(f.readText())
                val id = o.optString("id").ifBlank { return@runCatching }
                if (tasks.any { it.id == id }) return@runCatching
                val srcArr = o.optJSONArray("sources") ?: return@runCatching
                val sources = (0 until srcArr.length()).map { srcArr.getString(it) }
                if (sources.isEmpty()) return@runCatching
                val hdrObj = o.optJSONObject("headers") ?: JSONObject()
                val headers = hdrObj.keys().asSequence().associateWith { hdrObj.getString(it) }
                val t = Task(
                    id = id,
                    name = o.optString("name"),
                    sources = sources,
                    headers = headers,
                    savePath = o.optString("savePath"),
                    uri = o.optString("uri").ifBlank { null },
                    displayPath = o.optString("displayPath"),
                    size = o.optLong("size"),
                    state = State.PAUSED
                )
                val arr = o.optJSONArray("chunks") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val c = arr.getJSONObject(i)
                    t.chunks.add(Chunk(c.optInt("i", i), c.getLong("s"), c.getLong("e"), c.optLong("d")))
                }
                if (t.chunks.isNotEmpty() && t.chunks.all { it.finished }) {
                    f.delete()   // 已下完的残留元数据，清掉
                } else {
                    restored.add(t)
                    com.fanxing.xbox.store.AppLog.w(
                        "DL",
                        "从断点恢复 ${t.name}（未完成 ${t.chunks.count { !it.finished }} 片）；" +
                            "若直链已失效或返回 401/403/412，请删除后重新解析下载"
                    )
                }
            }
        }
        if (restored.isNotEmpty()) {
            synchronized(queueLock) { tasks.addAll(restored) }
            notifyUi()
        }
    }

    private fun loadMeta(t: Task): Boolean {
        val f = metaFile(t)
        if (!f.exists()) return false
        return runCatching {
            val o = JSONObject(f.readText())
            if (o.optLong("size") != t.size) return false
            val arr = o.optJSONArray("chunks") ?: return false
            t.chunks.clear()
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                t.chunks.add(Chunk(c.optInt("i", i), c.getLong("s"), c.getLong("e"), c.optLong("d")))
            }
            t.chunks.isNotEmpty()
        }.getOrDefault(false)
    }

}

class DriveErr(msg: String) : Exception(msg)
