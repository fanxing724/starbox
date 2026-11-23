package com.fanxing.xbox

import com.fanxing.xbox.download.Chunk
import com.fanxing.xbox.download.ChunkMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分片边界与 Range 校验的回归测试。
 * 覆盖两轮审查揪出的「静默损坏」类缺陷。
 */
class ChunkMathTest {

    @Test
    fun plan_coversExactly_noGapNoOverlap() {
        // 各种大小 × 各种并发，都必须无缝无重叠
        val sizes = listOf(1L, 2L, 7L, 10L, 999L, 1024L, 65537L, 2_076_870_610L)
        val conns = listOf(1, 2, 3, 8, 32, 64, 512)
        for (s in sizes) for (c in conns) {
            val chunks = ChunkMath.plan(s, c)
            assertTrue("size=$s conns=$c 覆盖不完整", ChunkMath.covers(chunks, s))
            assertEquals("size=$s conns=$c 总长不符", s, chunks.sumOf { it.length })
            assertTrue("size=$s conns=$c 出现空段", chunks.all { it.length > 0 })
        }
    }

    @Test
    fun plan_smallFileWithManyConns_doesNotProduceEmptyChunks() {
        // 回归：10 字节 / 32 连接，旧实现会产出 31 个空段 + 1 个 index=31 的段
        val chunks = ChunkMath.plan(10, 32)
        assertEquals(10, chunks.size)
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), chunks.map { it.index })
        assertTrue(chunks.all { it.length == 1L })
    }

    @Test
    fun plan_indicesAreContiguousFromZero() {
        // 回归：index 必须从 0 连续，否则镜像轮转与 meta 恢复会错位
        val chunks = ChunkMath.plan(1000, 7)
        assertEquals((0 until chunks.size).toList(), chunks.map { it.index })
    }

    @Test
    fun plan_zeroOrNegativeSize_isEmpty() {
        assertTrue(ChunkMath.plan(0, 32).isEmpty())
        assertTrue(ChunkMath.plan(-5, 32).isEmpty())
    }

    @Test
    fun covers_rejectsGapOverlapAndShortfall() {
        assertFalse("有缝隙", ChunkMath.covers(listOf(Chunk(0, 0, 4), Chunk(1, 6, 9)), 10))
        assertFalse("有重叠", ChunkMath.covers(listOf(Chunk(0, 0, 5), Chunk(1, 4, 9)), 10))
        assertFalse("没到末尾", ChunkMath.covers(listOf(Chunk(0, 0, 8)), 10))
        assertFalse("不从 0 开始", ChunkMath.covers(listOf(Chunk(0, 1, 9)), 10))
        assertTrue(ChunkMath.covers(listOf(Chunk(0, 0, 9)), 10))
    }

    @Test
    fun parseContentRange_handlesStarTotalAndSpaces() {
        val a = ChunkMath.parseContentRange("bytes 0-99/2076870610")
        assertNotNull(a)
        assertEquals(0L, a!!.start); assertEquals(99L, a.end); assertEquals(2076870610L, a.total)

        val b = ChunkMath.parseContentRange("bytes 100-199/*")
        assertNotNull(b)
        assertEquals(100L, b!!.start); assertNull(b.total)

        assertNull(ChunkMath.parseContentRange(null))
        assertNull(ChunkMath.parseContentRange("garbage"))
    }

    @Test
    fun checkRange_rejectsWrongStart() {
        val err = ChunkMath.checkRange("bytes 0-99/1000", wantStart = 100, chunkEnd = 199, fileSize = 1000)
        assertNotNull("起点错位必须报错", err)
    }

    @Test
    fun checkRange_rejectsEndBeyondRequest() {
        // 回归：服务器回得比请求多，旧实现只查起点会放行 → 踩相邻分片
        val err = ChunkMath.checkRange("bytes 100-999/1000", wantStart = 100, chunkEnd = 199, fileSize = 1000)
        assertNotNull("终点越界必须报错", err)
    }

    @Test
    fun checkRange_rejectsTotalMismatch() {
        val err = ChunkMath.checkRange("bytes 0-99/999", wantStart = 0, chunkEnd = 99, fileSize = 1000)
        assertNotNull("总大小不符必须报错", err)
    }

    @Test
    fun checkRange_acceptsValidAndShorterEnd() {
        assertNull(ChunkMath.checkRange("bytes 100-199/1000", 100, 199, 1000))
        // 服务器给得少是允许的，后续续传补齐
        assertNull(ChunkMath.checkRange("bytes 100-149/1000", 100, 199, 1000))
        // 没有该头就不强判
        assertNull(ChunkMath.checkRange(null, 100, 199, 1000))
    }

    @Test
    fun writable_clampsToChunkBoundary() {
        // 片 [100,199]，已写到 190，只剩 10 字节；服务器发来 64KB 也只能写 10
        assertEquals(10, ChunkMath.writable(65536, pos = 190, chunkEnd = 199))
        assertEquals(0, ChunkMath.writable(65536, pos = 200, chunkEnd = 199))
        assertEquals(0, ChunkMath.writable(0, pos = 100, chunkEnd = 199))
        assertEquals(50, ChunkMath.writable(50, pos = 100, chunkEnd = 199))
    }

    @Test
    fun writable_neverExceedsRemaining_acrossSweep() {
        for (end in listOf(0L, 9L, 999L)) {
            for (pos in 0..end + 5) {
                val w = ChunkMath.writable(1 shl 16, pos, end)
                val remain = maxOf(0L, end - pos + 1)
                assertTrue("pos=$pos end=$end 写越界", w.toLong() <= remain)
                if (pos <= end) assertTrue("pos=$pos end=$end 应能写", w > 0)
                else assertEquals("越过末尾必须写 0", 0, w)
            }
        }
    }

    @Test
    fun clampDone_repairsCorruptMeta() {
        assertEquals(0L, ChunkMath.clampDone(-100, 50))
        assertEquals(50L, ChunkMath.clampDone(9999, 50))
        assertEquals(20L, ChunkMath.clampDone(20, 50))
    }
}
