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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

enum class State { QUEUED, RUNNING, PAUSED, DONE, ERROR }

class Chunk(val index: Int, val start: Long, val end: Long, @Volatile var done: Long = 0) {
    val length: Long get() = end - start + 1
    val finished: Boolean get() = done >= length
}

class Task(
    val id: String,
    val name: String,
    val sources: List<String>,
    val headers: Map<String, String>,
    val savePath: String,
    /** 非空表示落盘到 MediaStore（系统 Download），savePath 此时仅作展示 */
    val uri: String? = null,
    val displayPath: String = savePath,
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
    fun init(ctx: Context) { appCtx = ctx.applicationContext }

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

        override fun close() {
            runCatching { raf?.close() }
            runCatching { pfd?.close() }
        }
    }

    private fun metaFile(t: Task): File =
        File(appCtx.filesDir, "meta/${t.id}.json")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    val tasks = CopyOnWriteArrayList<Task>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val activeTasks = AtomicInteger(0)
    private const val MAX_TOTAL_CONNS = 512

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun notifyUi() = main.post { listeners.forEach { runCatching { it() } } }

    fun submit(t: Task) {
        tasks.add(t)
        notifyUi()
        pump()
    }

    fun find(id: String): Task? = tasks.firstOrNull { it.id == id }

    fun pause(id: String) {
        val t = find(id) ?: return
        t.cancelFlag.set(true)
        t.state = State.PAUSED
        t.speed = 0
        persist(t)
        notifyUi()
    }

    fun resume(id: String) {
        val t = find(id) ?: return
        if (t.state == State.RUNNING) return
        t.cancelFlag.set(false)
        t.state = State.QUEUED
        t.error = ""
        notifyUi()
        pump()
    }

    fun remove(id: String) {
        val t = find(id) ?: return
        t.cancelFlag.set(true)
        runCatching { com.fanxing.xbox.store.Storage.delete(appCtx, t.uri, t.savePath) }
        runCatching { metaFile(t).delete() }
        tasks.remove(t)
        notifyUi()
    }

    private fun pump() {
        while (activeTasks.get() < com.fanxing.xbox.store.CredsHolder.parallelTasks) {
            val next = tasks.firstOrNull { it.state == State.QUEUED } ?: return
            next.state = State.RUNNING
            activeTasks.incrementAndGet()
            next.job = scope.launch {
                try {
                    run(next)
                } catch (e: Exception) {
                    next.state = State.ERROR
                    next.error = e.message ?: e.toString()
                } finally {
                    activeTasks.decrementAndGet()
                    next.speed = 0
                    persist(next)
                    notifyUi()
                    pump()
                }
            }
            notifyUi()
        }
    }

    // ---------------- 核心 ----------------

    private suspend fun run(t: Task) {
        if (t.uri == null) File(t.savePath).parentFile?.mkdirs()
        metaFile(t).parentFile?.mkdirs()

        // 1) 探测真实大小：必须用 Range，UC 的 HEAD 返回的 Content-Length 是错的
        if (t.size <= 0) t.size = probeSize(t)
        if (t.size <= 0) throw DriveErr("无法获取文件大小（服务器未返回 Content-Range）")

        // 2) 载入/建立分片
        if (!loadMeta(t)) buildChunks(t, connectionsFor(t))
        normalizeChunks(t)

        // 3) 预分配文件；整个任务共用一个写入句柄，避免 512 分片各开一个 fd 撑爆 FD 上限
        val sink = Sink(t)
        sink.preallocate(t.size)

        // 4) 进度心跳
        val tick = scope.launch {
            var last = t.downloaded
            while (isActive && !t.cancelFlag.get()) {
                delay(1000)
                val now = t.downloaded
                t.speed = now - last
                last = now
                persist(t)
                notifyUi()
            }
        }

        try {
            // 5) 并发拉各分片
            val jobs = t.chunks.filter { !it.finished }.map { c ->
                scope.launch { fetchChunk(t, c, sink) }
            }
            jobs.forEach { it.join() }

            if (t.cancelFlag.get()) {
                t.state = State.PAUSED
            } else if (t.chunks.all { it.finished }) {
                // 完工校验：分片覆盖必须等于文件总长，否则宁可报错也不交付损坏文件
                val covered = t.chunks.sumOf { it.length }
                if (covered != t.size) {
                    t.state = State.ERROR
                    t.error = "分片覆盖 ${covered}B ≠ 文件 ${t.size}B，已中止以防损坏"
                } else {
                    t.state = State.DONE
                    metaFile(t).delete()
                    com.fanxing.xbox.store.Storage.finish(
                        appCtx,
                        com.fanxing.xbox.store.Storage.Target(
                            t.uri?.let { Uri.parse(it) }, null, t.name, t.displayPath
                        )
                    )
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

    private fun connectionsFor(t: Task): Int {
        val n = com.fanxing.xbox.store.CredsHolder.connections
        return n.coerceIn(1, MAX_TOTAL_CONNS)
    }

    /** 有镜像就把连接摊到多个节点上 */
    private fun sourceFor(t: Task, chunkIndex: Int): String =
        t.sources[chunkIndex % t.sources.size]

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
            throw DriveErr("探测失败 HTTP ${r.code}")
        }
    }

    private fun buildChunks(t: Task, conns: Int) {
        t.chunks.clear()
        val n = conns.coerceAtLeast(1)
        val per = t.size / n
        for (i in 0 until n) {
            val start = i * per
            val end = if (i == n - 1) t.size - 1 else start + per - 1
            if (start <= end) t.chunks.add(Chunk(i, start, end))
        }
        persist(t)
    }

    private fun normalizeChunks(t: Task) {
        t.chunks.forEach { c ->
            if (c.done > c.length) c.done = c.length
            if (c.done < 0) c.done = 0
        }
    }

    private suspend fun fetchChunk(t: Task, c: Chunk, sink: Sink) {
        var attempt = 0
        while (!c.finished && !t.cancelFlag.get() && attempt < 6) {
            try {
                val from = c.start + c.done
                val req = Request.Builder().url(sourceFor(t, c.index))
                    .header("Range", "bytes=$from-${c.end}")
                    .apply { t.headers.forEach { (k, v) -> header(k, v) } }
                    .get().build()

                Http.dl.newCall(req).execute().use { r ->
                    if (r.code == 200) {
                        // 服务器忽略了 Range，回的是「整个文件」。
                        // 多分片时每片都会把文件开头写到自己的偏移上 → 静默损坏，必须拒绝。
                        if (from != 0L || t.chunks.size != 1) {
                            throw DriveErr("服务器不支持 Range（HTTP 200），无法分片下载")
                        }
                    } else if (r.code != 206) {
                        throw DriveErr("分片 ${c.index} HTTP ${r.code}")
                    } else {
                        // 206 也要校验起点，防止 CDN 返回错位区间
                        val crStart = r.header("Content-Range")
                            ?.substringAfter("bytes ", "")
                            ?.substringBefore("-", "")?.trim()?.toLongOrNull()
                        if (crStart != null && crStart != from) {
                            throw DriveErr("分片 ${c.index} 起点错位：期望 $from 实得 $crStart")
                        }
                    }
                    val body = r.body ?: throw DriveErr("分片 ${c.index} 空响应")
                    run {
                        var pos = from
                        val buf = ByteArray(64 * 1024)
                        val input = body.byteStream()
                        while (!t.cancelFlag.get()) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            sink.writeAt(pos, buf, n)
                            pos += n
                            c.done += n
                            if (c.finished) break
                        }
                    }
                }
                if (c.finished) return
                // 连接被提前关闭但没下完 —— 继续续传
                attempt++
            } catch (e: Exception) {
                if (t.cancelFlag.get()) return
                attempt++
                t.error = e.message ?: "分片重试"
                delay(800L * attempt)
            }
        }
        if (!c.finished && !t.cancelFlag.get()) {
            throw DriveErr("分片 ${c.index} 重试耗尽")
        }
    }

    // ---------------- 断点续传元数据 ----------------

    private fun persist(t: Task) {
        if (t.size <= 0 || t.chunks.isEmpty()) return
        runCatching {
            val arr = JSONArray()
            t.chunks.forEach { c ->
                arr.put(JSONObject().put("i", c.index).put("s", c.start).put("e", c.end).put("d", c.done))
            }
            val o = JSONObject()
                .put("size", t.size).put("name", t.name).put("chunks", arr)
            metaFile(t).writeText(o.toString())
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

    fun restore(t: Task) { submit(t) }
}

class DriveErr(msg: String) : Exception(msg)
