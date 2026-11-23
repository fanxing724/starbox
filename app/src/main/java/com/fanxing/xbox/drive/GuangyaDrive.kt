package com.fanxing.xbox.drive

import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/**
 * 光鸭云盘（分享者开启免登录时可游客下载）。
 *
 * 业务请求必须带 dt / traceparent / did 三个头。
 * code 207 = 分享者未开启免登录下载；code 209 = 提取码错误。
 */
class GuangyaDrive : Drive {

    override val id = "guangya"
    override val name = "光鸭云盘"
    override val loginUrl = "https://www.guangyapan.com/login"
    override val desc = "免登录（取决于分享者是否开启免登录下载）"
    override val cookieDomain = "https://www.guangyapan.com"

    private val api = "https://api.guangyapan.com"
    private val web = "https://www.guangyapan.com"
    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    override fun match(url: String) = url.contains("guangyapan.com")

    override fun parse(url: String): ShareRef {
        val key = Regex("/s/([A-Za-z0-9_-]+)").find(url)?.groupValues?.get(1)
            ?: url.trimEnd('/').substringAfterLast('/')
        val pwd = Regex("(?:提取码|密码|pwd|password)[:=：]?\\s*([A-Za-z0-9]{4,})")
            .find(url)?.groupValues?.get(1) ?: ""
        return ShareRef(key, pwd, url)
    }

    private fun hex(n: Int): String {
        val c = "0123456789abcdef"
        val r = SecureRandom()
        return buildString { repeat(n) { append(c[r.nextInt(16)]) } }
    }

    private fun post(path: String, body: JSONObject): JSONObject {
        val req = Request.Builder().url(api + path).post(Http.json(body.toString()))
            .header("Accept", "application/json, text/plain, */*")
            .header("Content-Type", "application/json")
            .header("User-Agent", ua)
            .header("Origin", web)
            .header("Referer", "$web/")
            .header("dt", "4")
            .header("traceparent", "00-${hex(32)}-${hex(16)}-01")
            .header("did", hex(32))
            .build()
        Http.api.newCall(req).execute().use { r ->
            val txt = r.body?.string() ?: throw DriveError("光鸭：空响应")
            val j = runCatching { JSONObject(txt) }.getOrElse {
                throw DriveError("光鸭：$path 返回非 JSON（HTTP ${r.code}）")
            }
            val code = j.opt("code")?.toString() ?: "0"
            if (code != "0" && code != "null" && code != "success" && j.optString("msg") != "success") {
                val hint = when (code) {
                    "207" -> "分享者未开启免登录下载，需登录光鸭并转存后才能下载"
                    "209" -> "提取码错误或缺失"
                    else -> j.optString("msg").ifBlank { j.optString("message") }
                }
                throw DriveError("光鸭：$hint（code=$code）")
            }
            return j
        }
    }

    private fun accessToken(share: ShareRef): String {
        share.extra["accessToken"]?.let { return it }
        val j = post(
            "/userres/v1/get_share_access_token",
            JSONObject().put("shareId", share.key).put("code", share.passcode)
        )
        val d = j.optJSONObject("data")
        val t = d?.optString("accessToken")?.ifBlank { d.optString("access_token") } ?: ""
        if (t.isBlank()) throw DriveError("光鸭：分享链接无效或已失效")
        share.extra["accessToken"] = t
        return t
    }

    override fun list(share: ShareRef, dir: RemoteFile?): List<RemoteFile> {
        val token = accessToken(share)
        val out = ArrayList<RemoteFile>()
        var cursor: String? = null
        var guard = 0
        while (guard++ < 50) {
            val body = JSONObject()
                .put("parentId", dir?.id ?: "")
                .put("pageSize", 100)
                .put("orderBy", 0).put("sortType", 0)
                .put("accessToken", token)
            cursor?.let { body.put("cursor", it) }
            val d = post("/userres/v1/get_share_page_files_list", body).optJSONObject("data")
                ?: break
            val arr = d.optJSONArray("list") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val it = arr.getJSONObject(i)
                val ft = it.optInt("fileType")
                val isDir = (ft == 1 || ft == 2) && it.optInt("resType") != 1
                out.add(
                    RemoteFile(
                        id = it.optString("fileId"),
                        name = it.optString("fileName"),
                        size = it.optLong("fileSize"),
                        isDir = isDir,
                        path = it.optString("fileName"),
                        meta = mapOf("accessToken" to token)
                    )
                )
            }
            if (!d.optBoolean("hasMore", false)) break
            cursor = d.optString("cursor").takeIf { it.isNotBlank() } ?: break
        }
        return out
    }

    override fun link(share: ShareRef, file: RemoteFile): DirectLink {
        val token = file.meta["accessToken"] ?: accessToken(share)
        val d = post(
            "/userres/v1/get_share_download_url",
            JSONObject().put("fileId", file.id).put("accessToken", token)
        ).optJSONObject("data") ?: throw DriveError("光鸭：未返回下载地址")

        val url = listOf("signedURL", "signedUrl", "downloadUrl", "download_url", "url", "cdnUrl")
            .firstNotNullOfOrNull { d.optString(it).takeIf { s -> s.isNotBlank() } }
            ?: throw DriveError("光鸭：未返回有效下载地址")

        return DirectLink(url, mapOf("User-Agent" to ua, "Referer" to "$web/"))
    }
}
