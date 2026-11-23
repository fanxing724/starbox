package com.fanxing.xbox.download

/**
 * 分片与 Range 的纯计算逻辑，不依赖 Android，可直接 JVM 单测。
 * 从 Engine 抽出，避免这类最容易出错的边界逻辑只能靠真机验证。
 */
object ChunkMath {

    /**
     * 把 [0, size) 切成最多 conns 段。
     * 保证：无缝隙、无重叠、总覆盖 == size；小文件不会产出空段。
     */
    fun plan(size: Long, conns: Int): List<Chunk> {
        if (size <= 0) return emptyList()
        val n = conns.coerceAtLeast(1)
        // 段数不能超过字节数，否则会出现 start>end 的空段
        val parts = minOf(n.toLong(), size).toInt()
        val per = size / parts
        val rem = size % parts
        val out = ArrayList<Chunk>(parts)
        var cursor = 0L
        for (i in 0 until parts) {
            // 余数摊到前 rem 段，避免最后一段吞掉全部余数
            val len = per + if (i < rem) 1 else 0
            out.add(Chunk(i, cursor, cursor + len - 1))
            cursor += len
        }
        return out
    }

    /** 分片覆盖是否恰好等于文件长度（无缝隙、无重叠） */
    fun covers(chunks: List<Chunk>, size: Long): Boolean {
        if (chunks.isEmpty()) return size == 0L
        val sorted = chunks.sortedBy { it.start }
        if (sorted.first().start != 0L) return false
        if (sorted.last().end != size - 1) return false
        for (i in 1 until sorted.size) {
            if (sorted[i].start != sorted[i - 1].end + 1) return false
        }
        return sorted.sumOf { it.length } == size
    }

    data class Range(val start: Long, val end: Long, val total: Long?)

    /** 解析 Content-Range: bytes s-e/total（total 可为 *） */
    fun parseContentRange(header: String?): Range? {
        if (header.isNullOrBlank()) return null
        val m = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)").find(header) ?: return null
        return Range(
            m.groupValues[1].toLong(),
            m.groupValues[2].toLong(),
            m.groupValues[3].takeIf { it != "*" }?.toLong()
        )
    }

    /** 校验响应区间是否与请求一致；返回错误描述，null 表示通过 */
    fun checkRange(
        header: String?, wantStart: Long, chunkEnd: Long, fileSize: Long
    ): String? {
        val r = parseContentRange(header) ?: return null   // 没有该头就不强判
        if (r.start != wantStart) return "起点错位：期望 $wantStart 实得 ${r.start}"
        if (r.end > chunkEnd) return "响应区间超出请求范围"
        if (r.total != null && r.total != fileSize) return "文件大小不符：期望 $fileSize 实得 ${r.total}"
        return null
    }

    /**
     * 本次最多能写多少字节：不能越过本片边界。
     * 服务器多发时必须截断，否则会踩到相邻分片。
     */
    fun writable(read: Int, pos: Long, chunkEnd: Long): Int {
        val remain = chunkEnd - pos + 1
        if (remain <= 0 || read <= 0) return 0
        return minOf(read.toLong(), remain).toInt()
    }

    /** 把已下字节数夹到合法区间，防止损坏的 meta 让进度越界 */
    fun clampDone(done: Long, length: Long): Long =
        when {
            done < 0 -> 0
            done > length -> length
            else -> done
        }
}
