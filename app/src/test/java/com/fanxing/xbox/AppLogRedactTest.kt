package com.fanxing.xbox

import com.fanxing.xbox.store.AppLog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日志脱敏回归。日志会被用户复制分享，绝不能带出凭据。
 * AppLog 未 init 时不写文件，纯内存路径可在 JVM 直接测。
 */
class AppLogRedactTest {

    private fun logged(msg: String): String {
        AppLog.clear()
        AppLog.i("T", msg)
        return AppLog.text()
    }

    @Test
    fun cookiesAreRedacted() {
        val out = logged("请求头 Cookie: __pugs=ABCDEF123456; __puus=SECRET999")
        assertFalse("pugs 泄露", out.contains("ABCDEF123456"))
        assertFalse("puus 泄露", out.contains("SECRET999"))
        assertTrue(out.contains("***"))
    }

    @Test
    fun tokensAreRedacted() {
        val out = logged("stoken=abcdef123456 access_token:tok_987654321 BDUSS=XYZxyz123")
        assertFalse(out.contains("abcdef123456"))
        assertFalse(out.contains("tok_987654321"))
        assertFalse(out.contains("XYZxyz123"))
    }

    @Test
    fun signedUrlQueryIsStripped() {
        val out = logged("取链 https://dl-uf-zb.pds.uc.cn/abc/file.apk?Signature=SECRETSIG&Expires=123&x-oss-signature=zzz")
        assertFalse("签名泄露", out.contains("SECRETSIG"))
        assertFalse(out.contains("x-oss-signature=zzz"))
        // host 与 path 要保留，便于排查是哪个节点
        assertTrue(out.contains("dl-uf-zb.pds.uc.cn"))
        assertTrue(out.contains("query已省略"))
    }

    @Test
    fun plainDiagnosticsArePreserved() {
        val out = logged("HTTP 403 code=41020 msg=token 校验异常 分片 3 第 2 次失败")
        assertTrue(out.contains("HTTP 403"))
        assertTrue(out.contains("code=41020"))
        assertTrue(out.contains("分片 3"))
    }

    @Test
    fun ringBufferIsBounded() {
        AppLog.clear()
        repeat(1200) { AppLog.i("T", "line $it") }
        assertTrue("内存日志必须有上限", AppLog.entries().size <= 800)
        // 保留的是最新的
        assertTrue(AppLog.text().contains("line 1199"))
    }
}
