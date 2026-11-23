package com.fanxing.xbox.drive

import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * 阿里云盘（分享游客链路）。
 * 直链必须带 Referer: https://www.alipan.com/ ，否则 403。
 */
class AliyunDrive : Drive {

    override val id = "aliyun"
    override val name = "阿里云盘"
    override val loginUrl = "https://www.alipan.com/"
    override val desc = "需登录。分享链接解析稳定"
    override val cookieDomain = "https://www.alipan.com"

    private val api = "https://api.alipan.com"
    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Safari/537.36"

    override fun match(url: String) =
        url.contains("alipan.com/s/") || url.contains("aliyundrive.com/s/")

    override fun parse(url: String): ShareRef {
        val key = Regex("(?:alipan|aliyundrive)\\.com/s/([A-Za-z0-9]+)").find(url)
            ?.groupValues?.get(1) ?: throw DriveError("阿里云盘：链接里没找到分享 ID")
        val pwd = Regex("[?&](?:password|pwd|share_pwd)=([A-Za-z0-9]+)").find(url)
            ?.groupValues?.get(1) ?: ""
        return ShareRef(key, pwd, url)
    }

    private fun post(url: String, body: JSONObject, shareToken: String = ""): JSONObject {
        val b = Request.Builder().url(url).post(Http.json(body.toString()))
            .header("User-Agent", ua)
            .header("Content-Type", "application/json;charset=UTF-8")
            .header("Referer", "https://www.alipan.com/")
            .header("Origin", "https://www.alipan.com")
            .header("Accept", "application/json, text/plain, */*")
        if (shareToken.isNotBlank()) b.header("X-Share-Token", shareToken)
        Http.api.newCall(b.build()).execute().use { r ->
            val txt = r.body?.string() ?: throw DriveError("阿里云盘：空响应")
            return runCatching { JSONObject(txt) }.getOrElse {
                throw DriveError("阿里云盘：响应非 JSON（HTTP ${r.code}）")
            }
        }
    }

    private fun shareToken(share: ShareRef): String {
        share.extra["share_token"]?.let { return it }
        val body = JSONObject().put("share_id", share.key).put("share_pwd", share.passcode)
        val j = post("$api/v2/share_link/get_share_token", body)
        val t = j.optString("share_token")
        if (t.isBlank()) {
            throw DriveError("阿里云盘：取分享令牌失败 ${j.optString("code")} ${j.optString("message")}")
        }
        share.extra["share_token"] = t
        return t
    }

    override fun list(share: ShareRef, dir: RemoteFile?): List<RemoteFile> {
        val tk = shareToken(share)
        val out = ArrayList<RemoteFile>()
        var marker = ""
        var guard = 0
        while (guard++ < 50) {
            val body = JSONObject()
                .put("share_id", share.key)
                .put("parent_file_id", dir?.id ?: "root")
                .put("limit", 100)
                .put("order_by", "name")
                .put("order_direction", "ASC")
            if (marker.isNotBlank()) body.put("marker", marker)
            val j = post("$api/adrive/v3/file/list", body, tk)
            val items = j.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) {
                val it = items.getJSONObject(i)
                out.add(
                    RemoteFile(
                        id = it.optString("file_id"),
                        name = it.optString("name"),
                        size = it.optLong("size"),
                        isDir = it.optString("type") == "folder",
                        path = it.optString("name")
                    )
                )
            }
            marker = j.optString("next_marker")
            if (marker.isBlank()) break
        }
        return out
    }

    override fun link(share: ShareRef, file: RemoteFile): DirectLink {
        val tk = shareToken(share)
        val body = JSONObject()
            .put("share_id", share.key)
            .put("file_id", file.id)
            .put("expire_sec", 600)
        val j = post("$api/v2/file/get_share_link_download_url", body, tk)
        val url = j.optString("url")
        if (url.isBlank()) {
            throw DriveError("阿里云盘：取直链失败 ${j.optString("code")} ${j.optString("message")}")
        }
        return DirectLink(
            url = url,
            headers = mapOf(
                "User-Agent" to ua,
                "Referer" to "https://www.alipan.com/"
            )
        )
    }
}
