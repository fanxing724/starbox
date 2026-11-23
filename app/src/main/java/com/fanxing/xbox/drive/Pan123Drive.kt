package com.fanxing.xbox.drive

import android.util.Base64
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * 123 云盘（分享免登录）。
 *
 * 关键点：
 *  1. 分享列表用 **Dart UA**（匿名通道），换成浏览器 UA 会被拦。
 *  2. 取链优先 v2（downloadPath + dispatchList 拼），失败回退 legacy（DownloadURL）。
 *  3. 返回的地址可能是 base64（整段或 ?params=），要解码。
 *  4. CDN 在 auto_redirect=0 时返回 JSON 跳转页而不是文件，要跟一跳。
 */
class Pan123Drive : Drive {

    override val id = "pan123"
    override val name = "123 云盘"
    override val loginUrl = "https://www.123pan.com/"
    override val desc = "免登录。列表接口要求 Dart UA，重名文件自动加序号"
    override val cookieDomain = "https://www.123pan.com"

    private val api = "https://yun.123pan.cn"
    private val www = "https://www.123pan.com"
    private val uaBrowser =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    override fun match(url: String) =
        url.contains("123pan.com/s/") || url.contains("123pan.cn/s/") ||
            url.contains("123865.com/s/") || url.contains("123684.com/s/")

    override fun parse(url: String): ShareRef {
        val key = Regex("123[0-9a-z]*\\.(?:com|cn)/s/([A-Za-z0-9_-]+)").find(url)
            ?.groupValues?.get(1) ?: throw DriveError("123：链接里没找到分享 ID")
        val pwd = Regex("(?:提取码|pwd|password|passcode)[:=：]?\\s*([A-Za-z0-9]{4,})")
            .find(url)?.groupValues?.get(1) ?: ""
        return ShareRef(key, pwd, url)
    }

    private fun shareHeaders(share: ShareRef) = mapOf(
        "User-Agent" to "Dart/3.12 (dart:io)",
        "Accept" to "application/json, text/plain, */*",
        "platform" to "web",
        "App-Version" to "3",
        "Referer" to "$www/s/${share.key}"
    )

    private fun webHeaders(share: ShareRef) = mapOf(
        "User-Agent" to uaBrowser,
        "Accept" to "application/json, text/plain, */*",
        "platform" to "web",
        "App-Version" to "3",
        "Referer" to "$www/s/${share.key}"
    )

    private fun exec(req: Request): JSONObject {
        Http.api.newCall(req).execute().use { r ->
            val txt = r.body?.string() ?: throw DriveError("123：空响应")
            return runCatching { JSONObject(txt) }.getOrElse {
                throw DriveError("123：响应非 JSON（HTTP ${r.code}）")
            }
        }
    }

    override fun list(share: ShareRef, dir: RemoteFile?): List<RemoteFile> {
        val enc = { s: String -> java.net.URLEncoder.encode(s, "UTF-8") }
        val url = "$api/b/api/share/get?limit=200&next=1&orderBy=share_id&orderDirection=desc" +
            "&shareKey=${share.key}&SharePwd=${enc(share.passcode)}" +
            "&ParentFileId=${dir?.id ?: "0"}&Page=1"
        val b = Request.Builder().url(url).get()
        shareHeaders(share).forEach { (k, v) -> b.header(k, v) }
        val j = exec(b.build())
        val code = j.optInt("code", 0)
        if (code != 0) throw DriveError("123：列表失败 ${j.optString("message")}")
        val arr = j.optJSONObject("data")?.optJSONArray("InfoList") ?: JSONArray()
        val out = ArrayList<RemoteFile>()
        for (i in 0 until arr.length()) {
            val it = arr.getJSONObject(i)
            out.add(
                RemoteFile(
                    id = it.optLong("FileId").toString(),
                    name = it.optString("FileName"),
                    size = it.optLong("Size"),
                    isDir = it.optInt("Type") == 1,
                    path = it.optString("FileName"),
                    meta = mapOf(
                        "S3KeyFlag" to it.optString("S3KeyFlag"),
                        "Etag" to it.optString("Etag")
                    )
                )
            )
        }
        return out
    }

    private fun postJson(url: String, body: JSONObject, share: ShareRef): JSONObject {
        val b = Request.Builder().url(url).post(Http.json(body.toString()))
        webHeaders(share).forEach { (k, v) -> b.header(k, v) }
        return exec(b.build())
    }

    private fun linkV2(share: ShareRef, f: RemoteFile): String? {
        val body = JSONObject()
            .put("ShareKey", share.key)
            .put("FileID", f.id.toLongOrNull() ?: 0L)
            .put("S3keyFlag", f.meta["S3KeyFlag"] ?: "")
            .put("Size", f.size)
            .put("Etag", f.meta["Etag"] ?: "")
        val j = runCatching { postJson("$api/b/api/v2/share/download/info", body, share) }.getOrNull()
            ?: return null
        if (j.optInt("code") != 0) return null
        val d = j.optJSONObject("data") ?: return null
        val path = d.optString("downloadPath").ifBlank { return null }
        val prefix = d.optJSONArray("dispatchList")?.optJSONObject(0)?.optString("prefix") ?: ""
        return if (prefix.isNotBlank()) prefix.trimEnd('/') + "/" + path.trimStart('/') else path
    }

    private fun linkLegacy(share: ShareRef, f: RemoteFile): String? {
        val body = JSONObject()
            .put("Etag", f.meta["Etag"] ?: "")
            .put("FileID", f.id.toLongOrNull() ?: 0L)
            .put("S3keyFlag", f.meta["S3KeyFlag"] ?: "")
            .put("ShareKey", share.key)
            .put("Size", f.size)
        val j = runCatching { postJson("$www/b/api/share/download/info", body, share) }.getOrNull()
            ?: return null
        if (j.optInt("code") != 0) return null
        return j.optJSONObject("data")?.optString("DownloadURL")?.takeIf { it.isNotBlank() }
    }

    /** 123 的下载地址可能是整段 base64，或 ?params=<urlsafe base64> */
    private fun decodeParams(raw: String): String {
        val s = raw.trim()
        fun b64(t: String): String? = runCatching {
            val norm = t.replace('-', '+').replace('_', '/')
            val pad = "=".repeat((4 - norm.length % 4) % 4)
            String(Base64.decode(norm + pad, Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull()

        if (!s.contains("://")) {
            b64(s)?.let { if (it.startsWith("http")) return it }
            return raw
        }
        val params = Regex("[?&]params=([^&]+)").find(s)?.groupValues?.get(1) ?: return raw
        return b64(java.net.URLDecoder.decode(params, "UTF-8")) ?: raw
    }

    /** CDN 可能返回 JSON 跳转页而非文件流 */
    private fun followRedirect(url: String): String {
        var cur = url
        repeat(4) {
            val req = Request.Builder().url(cur).get()
                .header("Referer", "$www/")
                .header("User-Agent", uaBrowser)
                .header("Range", "bytes=0-0")
                .build()
            val next = runCatching {
                Http.dl.newCall(req).execute().use { r ->
                    val len = r.header("Content-Length")?.toLongOrNull() ?: Long.MAX_VALUE
                    if (len in 0..8192) {
                        val txt = r.body?.string()?.trim() ?: ""
                        if (txt.startsWith("{")) {
                            runCatching {
                                JSONObject(txt).optJSONObject("data")?.optString("redirect_url")
                            }.getOrNull()?.takeIf { it.isNotBlank() }
                        } else null
                    } else null
                }
            }.getOrNull()
            if (next == null) return cur
            cur = next
        }
        return cur
    }

    override fun link(share: ShareRef, file: RemoteFile): DirectLink {
        val raw = linkV2(share, file) ?: linkLegacy(share, file)
            ?: throw DriveError("123：未获取到直链（可能需要登录或文件受限）")
        val url = followRedirect(decodeParams(raw))
        return DirectLink(
            url,
            mapOf("Referer" to "$www/", "User-Agent" to uaBrowser)
        )
    }
}
