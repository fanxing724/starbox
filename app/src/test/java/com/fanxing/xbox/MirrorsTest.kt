package com.fanxing.xbox

import com.fanxing.xbox.drive.expandMirrors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

/**
 * 镜像展开回归：只能换 host，绝不能动 path/query。
 * 签名 URL 常把 host 回写进参数，全串替换会让签名失效。
 */
class MirrorsTest {

    private val mirrors = listOf("cdn-a.example.com", "cdn-b.example.com")

    @Test
    fun noMirrors_returnsOriginalOnly() {
        val u = "https://origin.example.com/f/a.bin?sig=abc"
        assertEquals(listOf(u), expandMirrors(u, emptyList()))
    }

    @Test
    fun replacesHostOnly_keepsPathAndQueryVerbatim() {
        val u = "https://origin.example.com/f/a.bin?sig=abc%2Bdef&x=1"
        val out = expandMirrors(u, mirrors)
        assertEquals(3, out.size)
        assertEquals(u, out[0])
        val src = URI(u)
        out.drop(1).forEachIndexed { i, s ->
            val m = URI(s)
            assertEquals(mirrors[i], m.host)
            assertEquals("path 被改动", src.path, m.path)
            assertEquals("query 被改动", src.rawQuery, m.rawQuery)
        }
    }

    @Test
    fun hostAppearingInQuery_mustNotBeReplaced() {
        // 回归：旧实现用 String.replace，会把参数里的 host 一起换掉 → 签名失效
        val u = "https://origin.example.com/f?ref=origin.example.com&sig=zzz"
        val out = expandMirrors(u, mirrors)
        out.drop(1).forEach {
            assertTrue("参数里的 host 被误替换: $it", it.contains("ref=origin.example.com"))
            assertTrue("签名被改动: $it", it.contains("sig=zzz"))
        }
    }

    @Test
    fun deduplicates() {
        val u = "https://origin.example.com/f"
        val out = expandMirrors(u, listOf("cdn-a.example.com", "cdn-a.example.com"))
        assertEquals(2, out.size)
    }

    @Test
    fun keepsPortAndScheme() {
        val u = "https://origin.example.com:8443/f?a=1"
        val m = URI(expandMirrors(u, mirrors)[1])
        assertEquals("https", m.scheme)
        assertEquals(8443, m.port)
        assertEquals("cdn-a.example.com", m.host)
    }

    @Test
    fun malformedUrl_fallsBackToOriginal() {
        val bad = "not a url"
        assertEquals(listOf(bad), expandMirrors(bad, mirrors))
    }

    @Test
    fun respectsLimit() {
        val u = "https://origin.example.com/f"
        val many = (1..20).map { "cdn$it.example.com" }
        assertEquals(1 + 8, expandMirrors(u, many).size)          // 默认 limit=8
        assertEquals(1 + 3, expandMirrors(u, many, limit = 3).size)
    }
}
