package com.fanxing.xbox.drive

import com.fanxing.xbox.store.CredsHolder
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * 115 网盘（需登录 Cookie）。
 * 分享列表 GET share/snap，取链 POST share/skip_login_downurl。
 */
class Pan115Drive : Drive {

    override val id = "pan115"
    override val name = "115 网盘"
    override val loginUrl = "https://115.com/"
    override val desc = "需登录。支持秒传与分享直下"
    override val cookieDomain = "https://115.com"

    private val api = "https://115cdn.com/webapi"
    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val cookie get() = CredsHolder.cookie(id)

    override fun match(url: String) =
        url.contains("115.com/s/") || url.contains("115cdn.com/s/") || url.contains("anxia.com/s/")

    override fun parse(url: String): ShareRef {
        val key = Regex("/s/([A-Za-z0-9_-]+)").find(url)?.groupValues?.get(1)
            ?: throw DriveError("115：链接里没找到分享 ID")
        val pwd = Regex("[?&](?:password|pwd|receive_code)=([A-Za-z0-9]+)").find(url)
            ?.groupValues?.get(1) ?: ""
        return ShareRef(key, pwd, url)
    }

    private fun headers(): Map<String, String> {
        val m = linkedMapOf(
            "User-Agent" to ua,
            "Accept" to "application/json, text/plain, */*",
            "Accept-Language" to "zh-CN,zh;q=0.9",
            "Origin" to "https://115cdn.com",
            "Referer" to "https://115cdn.com/"
        )
        if (cookie.isNotBlank()) m["Cookie"] = cookie
        return m
    }

    private fun exec(req: Request): JSONObject {
        Http.api.newCall(req).execute().use { r ->
            val txt = r.body?.string() ?: throw DriveError("115：空响应")
            return runCatching { JSONObject(txt) }.getOrElse {
                throw DriveError("115：响应非 JSON（HTTP ${r.code}）")
            }
        }
    }

    override fun list(share: ShareRef, dir: RemoteFile?): List<RemoteFile> {
        if (cookie.isBlank()) throw DriveError("115：请先在设置里登录")
        val enc = { s: String -> java.net.URLEncoder.encode(s, "UTF-8") }
        val url = "$api/share/snap?share_code=${share.key}&offset=0&limit=200" +
            "&receive_code=${enc(share.passcode)}&cid=${dir?.id ?: ""}"
        val b = Request.Builder().url(url).get()
        headers().forEach { (k, v) -> b.header(k, v) }
        val j = exec(b.build())
        if (!j.optBoolean("state")) {
            throw DriveError("115：列表失败 ${j.optString("error")}")
        }
        val arr = j.optJSONObject("data")?.optJSONArray("list") ?: JSONArray()
        val out = ArrayList<RemoteFile>()
        for (i in 0 until arr.length()) {
            val it = arr.getJSONObject(i)
            out.add(
                RemoteFile(
                    id = it.optString("fid").ifBlank { it.optString("file_id") },
                    name = it.optString("name").ifBlank { it.optString("file_name") },
                    size = it.optLong("size").let { s -> if (s > 0) s else it.optLong("filesize") },
                    isDir = it.optBoolean("is_dir") || it.optBoolean("folder"),
                    path = it.optString("name")
                )
            )
        }
        return out
    }

    override fun link(share: ShareRef, file: RemoteFile): DirectLink {
        val b = Request.Builder().url("$api/share/skip_login_downurl")
            .post(Http.form(mapOf(
                "share_code" to share.key,
                "receive_code" to share.passcode,
                "file_id" to file.id
            )))
        headers().forEach { (k, v) -> b.header(k, v) }
        b.header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        val j = exec(b.build())
        if (!j.optBoolean("state")) {
            throw DriveError("115：取直链失败 ${j.optString("error").ifBlank { j.optString("msg") }}")
        }
        val url = j.optJSONObject("data")?.optJSONObject("url")?.optString("url") ?: ""
        if (url.isBlank()) throw DriveError("115：未返回直链")
        val h = linkedMapOf("User-Agent" to ua, "Referer" to "https://115cdn.com/")
        if (cookie.isNotBlank()) h["Cookie"] = cookie
        return DirectLink(url, h)
    }
}
