package com.fanxing.xbox.drive

import okhttp3.Request
import org.json.JSONObject
import java.security.SecureRandom

/**
 * 蓝奏云（免登录，单文件分享）。
 *
 * 三个坑：
 *  1. 首次访问可能返回 acw_sc__v2 人机校验页，要算出 Cookie 再重取。
 *  2. 无密码时真正的 sign 在 iframe 子页里，不在主页面。
 *  3. ajaxm 返回的 dom+url 是「假链」，要带移动端 UA 请求它拿 302 Location。
 */
class LanzouDrive : Drive {

    override val id = "lanzou"
    override val name = "蓝奏云"
    override val loginUrl: String? = null

    private val uaMobile =
        "Mozilla/5.0 (Linux; U; Android 4.0.4; es-mx; HTC_One_X Build/IMM76D) " +
            "AppleWebKit/534.30 (KHTML, like Gecko) Version/4.0"
    private val uaPc =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36"

    private var acwCookie: String = ""

    override fun match(url: String) =
        Regex("lanzou[a-z]?\\.com/|ilanzou\\.com/|lanzou[a-z]\\.com/").containsMatchIn(url)

    override fun parse(url: String): ShareRef {
        val host = Regex("https?://([a-z0-9.]+)/").find(url)?.groupValues?.get(1)
            ?: throw DriveError("蓝奏：链接格式不对")
        val key = Regex("https?://[a-z0-9.]+/(?:tp/)?([A-Za-z0-9_-]+)").find(url)
            ?.groupValues?.get(1) ?: throw DriveError("蓝奏：没找到分享 ID")
        val pwd = Regex("(?:密码|提取码|pwd|password)[:=：]?\\s*([A-Za-z0-9]{3,})")
            .find(url)?.groupValues?.get(1) ?: ""
        return ShareRef(key, pwd, url, mutableMapOf("host" to host))
    }

    // ---------- acw_sc__v2 ----------

    private fun acwScV2(arg1: String): String {
        val q = CharArray(ACW_POS.size) { ' ' }
        arg1.forEachIndexed { x, ch ->
            ACW_POS.forEachIndexed { z, pos -> if (pos == x + 1) q[z] = ch }
        }
        val u = String(q)
        val sb = StringBuilder()
        var i = 0
        val limit = minOf(u.length, ACW_MASK.length) - 1
        while (i < limit) {
            val a = u.substring(i, i + 2).toIntOrNull(16) ?: break
            val b = ACW_MASK.substring(i, i + 2).toInt(16)
            sb.append(String.format("%02x", a xor b))
            i += 2
        }
        return sb.toString()
    }

    private fun getPage(url: String, mobile: Boolean): String {
        repeat(3) {
            val b = Request.Builder().url(url).get()
                .header("User-Agent", if (mobile) uaMobile else uaPc)
                .header("Accept-Language", "zh-CN,zh;q=0.9")
            if (acwCookie.isNotBlank()) b.header("Cookie", acwCookie)
            Http.api.newCall(b.build()).execute().use { r ->
                val txt = r.body?.string() ?: ""
                val arg1 = Regex("arg1\\s*=\\s*'([0-9A-Fa-f]{40})'").find(txt)
                if (arg1 != null) {
                    acwCookie = "acw_sc__v2=" + acwScV2(arg1.groupValues[1])
                    return@repeat
                }
                return txt
            }
        }
        return ""
    }

    private fun extractSign(html: String): String {
        Regex("sign=(\\w+?)&").find(html)?.let { return it.groupValues[1] }
        Regex("'sign':(.+?),").find(html)?.let { m ->
            var sign = m.groupValues[1].trim().trim('\'', '"')
            if (sign.length < 20) {
                Regex("var ${Regex.escape(sign)}\\s*=\\s*'(.+?)';").find(html)?.let {
                    sign = it.groupValues[1]
                }
            }
            return sign
        }
        throw DriveError("蓝奏：未能提取 sign")
    }

    private fun ajaxm(baseUrl: String, form: Map<String, String>): JSONObject {
        val endpoint = baseUrl.replace(Regex("/[^/]*$"), "/ajaxm.php")
        val b = Request.Builder().url(endpoint).post(Http.form(form))
            .header("User-Agent", uaPc)
            .header("Referer", baseUrl)
            .header("Accept-Language", "zh-CN,zh;q=0.9")
        if (acwCookie.isNotBlank()) b.header("Cookie", acwCookie)
        Http.api.newCall(b.build()).execute().use { r ->
            val txt = r.body?.string() ?: throw DriveError("蓝奏：ajaxm 空响应")
            return runCatching { JSONObject(txt) }.getOrElse {
                throw DriveError("蓝奏：ajaxm 响应非 JSON")
            }
        }
    }

    private fun fakeToDirect(dom: String, url: String): String {
        val fake = "$dom/file/$url"
        val ip = (1..4).joinToString(".") { (SecureRandom().nextInt(254) + 1).toString() }
        val req = Request.Builder().url(fake).get()
            .header("User-Agent", uaMobile)
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("X-Forwarded-For", ip)
            .build()
        // 不跟随跳转，直接取 Location
        val noRedirect = Http.api.newBuilder().followRedirects(false).build()
        noRedirect.newCall(req).execute().use { r ->
            r.header("Location")?.let { return it }
            val txt = r.body?.string() ?: ""
            if (txt.contains("网络异常")) throw DriveError("蓝奏：触发验证码，请在浏览器手动下载")
        }
        return fake
    }

    override fun list(share: ShareRef, dir: RemoteFile?): List<RemoteFile> {
        val host = share.extra["host"] ?: "www.lanzoui.com"
        val base = "https://$host/${share.key}"
        val html = getPage(base, mobile = true)
        if (html.isBlank()) throw DriveError("蓝奏：页面打不开")

        val name = Regex("<title>(.+?) - 蓝奏").find(html)?.groupValues?.get(1)
            ?: Regex("var filename = '(.+?)';").find(html)?.groupValues?.get(1)
            ?: Regex("id=\"filenajax\">(.+?)</div>").find(html)?.groupValues?.get(1)
            ?: share.key
        val sizeText = Regex("大小.+?(\\d[\\d.,]+\\s?[BKM]?)<").find(html)?.groupValues?.get(1) ?: ""

        val needPwd = html.contains("文件受密码保护") ||
            html.contains("id=\"pwdload\"") || html.contains("id=\"passwddiv\"")

        val info: JSONObject = if (needPwd) {
            if (share.passcode.isBlank()) throw DriveError("蓝奏：该文件需要提取码")
            ajaxm(base, mapOf(
                "action" to "downprocess",
                "sign" to extractSign(html),
                "p" to share.passcode
            ))
        } else {
            val sub = Regex("<iframe.*?src=\"(.+?)\"").find(html)?.groupValues?.get(1)
                ?: throw DriveError("蓝奏：未找到下载页")
            val subUrl = if (sub.startsWith("http")) sub else "https:$sub"
            val page2 = getPage(subUrl, mobile = false)
            ajaxm(base, mapOf(
                "action" to "downprocess",
                "sign" to extractSign(page2.ifBlank { html }),
                "ves" to "1"
            ))
        }

        if (info.optInt("zt") != 1) {
            throw DriveError("蓝奏：解析失败（密码错误或链接失效）")
        }
        val direct = fakeToDirect(info.optString("dom"), info.optString("url"))
        return listOf(
            RemoteFile(
                id = share.key,
                name = name.replace("*", "_"),
                size = sizeToInt(sizeText),
                isDir = false,
                path = name,
                meta = mapOf("direct" to direct)
            )
        )
    }

    override fun link(share: ShareRef, file: RemoteFile): DirectLink {
        val direct = file.meta["direct"]?.takeIf { it.isNotBlank() }
            ?: list(share, null).firstOrNull()?.meta?.get("direct")
            ?: throw DriveError("蓝奏：未获取到直链")
        return DirectLink(
            direct,
            mapOf("User-Agent" to uaMobile, "Referer" to "https://www.lanzoui.com/")
        )
    }

    private fun sizeToInt(text: String): Long {
        val m = Regex("([\\d.]+)\\s*([KMGT]?B?)", RegexOption.IGNORE_CASE).find(text) ?: return 0
        val num = m.groupValues[1].toDoubleOrNull() ?: return 0
        val f = when (m.groupValues[2].uppercase()) {
            "K", "KB" -> 1024.0
            "M", "MB" -> 1024.0 * 1024
            "G", "GB" -> 1024.0 * 1024 * 1024
            "T", "TB" -> 1024.0 * 1024 * 1024 * 1024
            else -> 1.0
        }
        return (num * f).toLong()
    }

    companion object {
        private val ACW_POS = intArrayOf(
            15, 35, 29, 24, 33, 16, 1, 38, 10, 9, 19, 31, 40, 27, 22, 23, 25, 13, 6, 11,
            39, 18, 20, 8, 14, 21, 32, 26, 2, 30, 7, 4, 17, 5, 3, 28, 34, 37, 12, 36
        )
        private const val ACW_MASK = "3000176000856006061501533003690027800375"
    }
}
