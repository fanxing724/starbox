package com.fanxing.xbox

import com.fanxing.xbox.drive.Registry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 链接识别与解析回归。纯字符串逻辑，不需要设备。
 * 网盘改域名/加参数时，这里最先暴露问题。
 */
class DriveParseTest {

    private fun idOf(url: String) = Registry.detect(url)?.id

    @Test
    fun detectsAllSupportedDrives() {
        val cases = mapOf(
            "https://drive.uc.cn/s/2e551d52edae4?public=1" to "uc",
            "https://pan.quark.cn/s/01326c405859" to "quark",
            "https://www.alipan.com/s/AbCdEf12345" to "aliyun",
            "https://pan.baidu.com/s/18ZXptQms3kiZVCasKudPPw?pwd=6666" to "baidu",
            "https://pan.xunlei.com/s/VO1234abcd?pwd=9x9x" to "xunlei",
            "https://www.123pan.com/s/AbCd-1234.html" to "pan123",
            "https://wwi.lanzoui.com/iAbCdEf" to "lanzou",
            "https://www.guangyapan.com/s/1968622361676525654_anwNeGVemjn9t-u0" to "guangya",
            "https://yun.139.com/shareweb/#/w/i/AbCdEf123" to "yidong139",
            "https://115.com/s/swzabcd1234?password=a1b2" to "pan115",
            "https://url89.ctfile.com/f/12345678-123456789-abcdef?p=1234" to "ctfile",
            "https://mypikpak.com/s/VOabcd1234" to "pikpak",
            "https://cloud.189.cn/t/AbCdEf123456" to "tianyi",
            "https://share.weiyun.com/AbCdEf12" to "weiyun"
        )
        cases.forEach { (url, want) ->
            assertEquals("识别错误: $url", want, idOf(url))
        }
    }

    @Test
    fun unknownLink_returnsNull() {
        assertNull(idOf("https://example.com/whatever"))
        assertNull(idOf("随便一段中文"))
    }

    @Test
    fun uc_extractsKeyAndPasscode() {
        val d = Registry.byId("uc")!!
        val s = d.parse("https://drive.uc.cn/s/2e551d52edae4?public=1")
        assertEquals("2e551d52edae4", s.key)
        val p = d.parse("https://drive.uc.cn/s/abc123?password=9x9x")
        assertEquals("abc123", p.key)
        assertEquals("9x9x", p.passcode)
    }

    @Test
    fun baidu_stripsLeadingOne() {
        // 百度 /s/1XXXX 里的 1 是前缀，surl 不含它
        val d = Registry.byId("baidu")!!
        val s = d.parse("https://pan.baidu.com/s/18ZXptQms3kiZVCasKudPPw?pwd=6666")
        assertEquals("8ZXptQms3kiZVCasKudPPw", s.key)
        assertEquals("6666", s.passcode)
    }

    @Test
    fun tianyi_extractsCodeAndAccessCode() {
        val d = Registry.byId("tianyi")!!
        val s = d.parse("https://cloud.189.cn/t/AbCdEf123456?accessCode=9x9x")
        assertEquals("AbCdEf123456", s.key)
        assertEquals("9x9x", s.passcode)
    }

    @Test
    fun pan123_extractsKey() {
        val d = Registry.byId("pan123")!!
        assertEquals("AbCd-1234", d.parse("https://www.123pan.com/s/AbCd-1234.html").key)
    }

    @Test
    fun everyDrive_hasNameAndDesc() {
        Registry.all.forEach {
            assertTrue("${it.id} 缺名称", it.name.isNotBlank())
            assertTrue("${it.id} 缺特点说明", it.desc.isNotBlank())
        }
    }

    @Test
    fun guestParseSet_matchesPublishedSupportList() {
        // 免登录「解析」集合是对外承诺，改动时必须同步更新支持列表与 README。
        // 注意：不能用 loginUrl==null 判断 —— 夸克/迅雷等配了登录页但仍可游客解析。
        val guest = Registry.all.filter { it.guestParse }.map { it.id }.toSet()
        assertEquals(
            setOf(
                "uc", "quark", "pan123", "lanzou", "guangya",
                "yidong139", "ctfile", "tianyi", "xunlei", "pikpak"
            ),
            guest
        )
        // 真正必须登录才能解析的四家
        val mustLogin = Registry.all.filter { !it.guestParse }.map { it.id }.toSet()
        assertEquals(setOf("aliyun", "baidu", "pan115", "weiyun"), mustLogin)
    }

    @Test
    fun transferCapableDrives() {
        val t = Registry.all.filter { it.supportsTransfer }.map { it.id }.toSet()
        assertEquals(setOf("uc", "quark"), t)
    }

    @Test
    fun registry_hasNoDuplicateIds() {
        val ids = Registry.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun malformedShareLink_throwsRatherThanSilentlyPassing() {
        val d = Registry.byId("uc")!!
        var threw = false
        try { d.parse("https://drive.uc.cn/nope") } catch (e: Exception) { threw = true }
        assertTrue("无效链接必须报错", threw)
        assertNotNull(d)
    }
}
