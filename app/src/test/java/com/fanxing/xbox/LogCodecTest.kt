package com.fanxing.xbox

import com.fanxing.xbox.store.AppLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 崩后重开要能看到上一次运行的日志，这里保证往返不丢内容。
 */
class LogCodecTest {

    @Test
    fun decode_parsesStandardLines() {
        val lines = listOf(
            "11-25 16:15:05.453 I/Quark: 快传令牌已取，走快传线路",
            "11-25 16:15:06.033 E/DL: 片20 HTTP 412"
        )
        val out = AppLog.testDecode(lines)
        assertEquals(2, out.size)
        assertEquals("Quark", out[0].tag)
        assertEquals(com.fanxing.xbox.store.AppLog.Level.I, out[0].level)
        assertEquals("快传令牌已取，走快传线路", out[0].msg)
        assertEquals("DL", out[1].tag)
        assertEquals(com.fanxing.xbox.store.AppLog.Level.E, out[1].level)
    }

    @Test
    fun decode_mergesStackTraceContinuationIntoPreviousEntry() {
        // 崩溃堆栈是多行的，必须并回上一条，不能丢也不能变成独立条目
        val lines = listOf(
            "11-25 16:20:01.000 E/Crash: 未捕获异常线程=main",
            "\tat com.fanxing.xbox.download.Engine.run(Engine.kt:120)",
            "\tat kotlin.coroutines.BuildersKt.startCoroutine",
            "11-25 16:20:02.000 I/App: starbox 启动"
        )
        val out = AppLog.testDecode(lines)
        assertEquals(2, out.size)
        assertEquals("Crash", out[0].tag)
        assertNotNull(out[0].msg)
        org.junit.Assert.assertTrue(
            "堆栈应并入 Crash 条目",
            out[0].msg.contains("Engine.kt:120") && out[0].msg.contains("BuildersKt")
        )
        assertEquals("App", out[1].tag)
    }

    @Test
    fun decode_toleratesBlankAndGarbageLines() {
        val lines = listOf("", "   ", "garbage without format", "11-25 16:21:01.000 D/DL: ok")
        val out = AppLog.testDecode(lines)
        assertEquals(1, out.size)
        assertEquals("ok", out[0].msg)
    }

    @Test
    fun parse_returnsNullForNonStandardLine() {
        assertNull(AppLog.testParse("\tat something"))
        assertNull(AppLog.testParse("no timestamp here"))
        assertNotNull(AppLog.testParse("11-25 16:22:01.000 I/X: hi"))
    }

    @Test
    fun decode_acceptsEpochMillisFormat() {
        // 回归：0.9.25 曾把时间写成 13 位毫秒，而解析只认格式化日期，
        // 导致「文件有内容却还原 0 条」，日志页看起来像被清空。
        val line = "1763817705453 I/Quark: 快传令牌已取，走快传线路"
        val out = AppLog.testDecode(listOf(line))
        assertEquals(1, out.size)
        assertEquals("Quark", out[0].tag)
        assertEquals(com.fanxing.xbox.store.AppLog.Level.I, out[0].level)
        assertEquals(1763817705453L, out[0].time)
    }

    @Test
    fun encodeThenDecode_roundTrips() {
        // 自己写的必须自己读得回来：格式改了就要两端一起改
        val src = com.fanxing.xbox.store.AppLog.testEncode(
            com.fanxing.xbox.store.AppLog.Entry(
                1763817705453L,
                com.fanxing.xbox.store.AppLog.Level.W, "DL", "分片 3 HTTP 412"
            )
        )
        val out = AppLog.testDecode(listOf(src))
        assertEquals(1, out.size)
        assertEquals("DL", out[0].tag)
        assertEquals(com.fanxing.xbox.store.AppLog.Level.W, out[0].level)
        assertEquals("分片 3 HTTP 412", out[0].msg)
    }
}
