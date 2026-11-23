package com.fanxing.xbox.drive

import okhttp3.Request
import org.json.JSONObject

/**
 * 城通网盘（免登录，VIP 文件走多线路）。
 *
 * 文件 ID 两段 = 文件，三段 = 文件夹。
 * is_vip=1 时直接取 vip_dx/yd/lt_url 或 us_downurl_a（多线路可并发）。
 */
class CtfileDrive : Drive {

    override val id = "ctfile"
    override val name = "城通网盘"
    override val loginUrl = "https://home.ctfile.com/"
    override val desc = "免登录。VIP 文件走多条 CDN 线路，可并发加速"
    override val guestParse = true
    override val cookieDomain = "https://www.ctfile.com"

    private val webapi = "https://webapi.ctfile.com"
    private val ref = "https://www.ctfile.com"
    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    override fun match(url: String) =
        url.contains("ctfile.com") || url.contains("ctfile.fun") || url.contains("404ctfile.com")

    override fun parse(url: String): ShareRef {
        val key = Regex("([A-Za-z0-9]{3,}-[A-Za-z0-9]{3,}(?:-[A-Za-z0-9]{3,})?)").find(url)
            ?.groupValues?.get(1) ?: url.trimEnd('/').substringAfterLast('/')
        val pwd = Regex("[?&](?:p|pwd|passcode)=([^&]+)").find(url)?.groupValues?.get(1) ?: ""
        return ShareRef(key, pwd, url)
    }

    private fun headers() = mapOf(
        "User-Agent" to ua,
        "Origin" to ref,
        "Referer" to "$ref/"
    )

    private fun get(url: String): JSONObject {
        val b = Request.Builder().url(url).get()
        headers().forEach { (k, v) -> b.header(k, v) }
        Http.api.newCall(b.build()).execute().use { r ->
            val txt = r.body?.string() ?: throw DriveError("城通：空响应")
            return runCatching { JSONObject(txt) }.getOrElse {
                throw DriveError("城通：响应非 JSON（HTTP ${r.code}）")
            }
        }
    }

    private fun fileInfo(share: ShareRef): JSONObject {
        val path = if (share.key.split("-").size == 2) "file" else "f"
        val enc = { s: String -> java.net.URLEncoder.encode(s, "UTF-8") }
        val url = "$webapi/getfile.php?path=$path&f=${share.key}" +
            "&passcode=${enc(share.passcode)}&token=&r=${Math.random()}&ref=${enc(ref)}"
        val j = get(url)
        if (j.optInt("code") != 200) {
            val msg = j.optJSONObject("file")?.optString("message")
                ?.ifBlank { j.optString("message") } ?: "未知错误"
            throw DriveError("城通：$msg")
        }
        return j.optJSONObject("file") ?: throw DriveError("城通：未返回文件信息")
    }

    override fun list(share: ShareRef, dir: RemoteFile?): List<RemoteFile> {
        val info = fileInfo(share)
        return listOf(
            RemoteFile(
                id = share.key,
                name = info.optString("file_name").ifBlank { share.key },
                size = sizeToInt(info.opt("file_size")),
                isDir = false,
                path = info.optString("file_name"),
                meta = mapOf(
                    "userid" to info.optString("userid"),
                    "file_id" to info.optString("file_id"),
                    "file_chk" to info.optString("file_chk"),
                    "is_vip" to info.optInt("is_vip").toString(),
                    "vip_dx_url" to info.optString("vip_dx_url"),
                    "vip_yd_url" to info.optString("vip_yd_url"),
                    "vip_lt_url" to info.optString("vip_lt_url"),
                    "us_downurl_a" to info.optString("us_downurl_a")
                )
            )
        )
    }

    override fun link(share: ShareRef, file: RemoteFile): DirectLink {
        if (file.meta["is_vip"] == "1") {
            val url = listOf("vip_dx_url", "vip_yd_url", "vip_lt_url", "us_downurl_a")
                .firstNotNullOfOrNull { file.meta[it]?.takeIf { s -> s.isNotBlank() } }
                ?: throw DriveError("城通：VIP 文件无可用链接")
            return DirectLink(url, headers())
        }
        val url = "$webapi/get_file_url.php?uid=${file.meta["userid"] ?: ""}" +
            "&fid=${file.meta["file_id"] ?: ""}&file_chk=${file.meta["file_chk"] ?: ""}" +
            "&app=0&acheck=2&rd=${Math.random()}"
        val j = get(url)
        when (j.optInt("code")) {
            302 -> throw DriveError("城通：该文件需要登录，请在设置里登录城通")
            200 -> {}
            else -> throw DriveError("城通：取直链失败 ${j.optString("message")}")
        }
        val dl = j.optString("downurl")
        if (dl.isBlank()) throw DriveError("城通：未返回直链")
        return DirectLink(dl, headers())
    }

    private fun sizeToInt(v: Any?): Long {
        if (v is Number) return v.toLong()
        val s = v?.toString() ?: return 0
        val m = Regex("([\\d.]+)\\s*([KMGT]?B?)", RegexOption.IGNORE_CASE).find(s) ?: return 0
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
}
