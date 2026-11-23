package com.fanxing.xbox.drive

import com.fanxing.xbox.store.CredsHolder
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * 迅雷网盘（分享支持游客解析）。
 *
 * 移植自 YunX-Desktop 的 XunleiApi / XunleiWebCredential（抓包 + alist 双向验证）。
 *
 * 关键点：
 *  1. 游客模式**不能**带 Authorization 头 —— 带个空 Bearer 反而被判 unauthenticated。
 *  2. 每个请求都要 X-Captcha-Token，且 action 必须与请求路径一致（"GET:/drive/v1/share"）。
 *  3. 网页通道 captcha_sign 是 **9 层 md5**、raw 里放 host；
 *     App 通道是 10 层、raw 里放 package_name。用错会被判 client_id not match。
 *  4. 网页 captcha_token 只有 300s，这里按 270s 保守过期。
 */
class XunleiDrive : Drive {

    override val id = "xunlei"
    override val name = "迅雷网盘"
    override val loginUrl = "https://pan.xunlei.com/"
    override val desc = "免登录可解析分享。下载需登录；支持 20+ CDN 镜像节点并发"
    override val cookieDomain = "https://pan.xunlei.com"

    private val auth = "https://xluser-ssl.xunlei.com"
    private val pan = "https://api-pan.xunlei.com"

    private val webUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    private val desktopUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private val clientId = "Xqp0kJBXWhwaTpB6"      // 网页版 client_id
    private val webVersion = "1.93.10"
    private val webHost = "pan.xunlei.com"

    /** 9 条盐，顺序敏感 */
    private val salts = listOf(
        "DVM8BRQfJWDsIE0JbH5GCUM0z0jU1yGymBTj4Eyloazv1F+w0G7ig",
        "T",
        "a1fUxfjOO1/dW++DpGupRgw9P93L",
        "aktKbp+/lQc3MvlMHIupt65LjAgJOL6ttMFvTKK+7+WKubSf9rT",
        "SB/ne9FQUnQmWz4Wuv9KqKfGOBOCUubpHpECxOHfD4hNoMclYA2Yq",
        "sOZFBQ8IMFy3OFjAIf/eA65iqFvS9TIKzLyrhiT6",
        "ZhJdLgL7MGh2GanboB0yk",
        "Osf",
        "v"
    )

    private val deviceId: String by lazy {
        val saved = CredsHolder.token("xunlei_device")
        if (saved.isNotBlank()) saved
        else randomHex(32).also { CredsHolder.creds.setToken("xunlei_device", it) }
    }

    private val accessToken: String get() = CredsHolder.token(id)

    /** captcha 缓存：action -> (token, 过期时间) */
    private val captchaCache = HashMap<String, Pair<String, Long>>()

    override fun match(url: String) =
        url.contains("pan.xunlei.com/s/") || url.contains("xunlei.com/s/")

    override fun parse(url: String): ShareRef {
        val key = Regex("xunlei\\.com/s/([A-Za-z0-9_-]+)").find(url)
            ?.groupValues?.get(1) ?: throw DriveError("迅雷：链接里没找到分享 ID")
        val pwd = Regex("[?&](?:pwd|password|pass_code)=([A-Za-z0-9]+)").find(url)
            ?.groupValues?.get(1) ?: ""
        return ShareRef(key, pwd, url)
    }

    // ---------- 签名 / captcha ----------

    private fun md5Hex(s: String): String {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
        val hex = "0123456789abcdef"
        val sb = StringBuilder(d.size * 2)
        d.forEach { b -> sb.append(hex[(b.toInt() shr 4) and 0x0f]).append(hex[b.toInt() and 0x0f]) }
        return sb.toString()
    }

    private fun randomHex(n: Int): String {
        val hex = "0123456789abcdef"
        val r = java.security.SecureRandom()
        return buildString { repeat(n) { append(hex[r.nextInt(16)]) } }
    }

    private fun captchaSign(ts: Long): String {
        var h = clientId + webVersion + webHost + deviceId + ts
        salts.forEach { h = md5Hex(h + it) }
        return "1.$h"
    }

    private fun captcha(action: String): String {
        val now = System.currentTimeMillis()
        captchaCache[action]?.let { if (it.second > now) return it.first }

        val ts = now
        val meta = JSONObject()
            .put("username", "").put("phone_number", "").put("email", "")
            .put("package_name", webHost)
            .put("client_version", webVersion)
            .put("captcha_sign", captchaSign(ts))
            .put("timestamp", ts.toString())
            .put("user_id", "")
        val body = JSONObject()
            .put("client_id", clientId)
            .put("action", action)
            .put("device_id", deviceId)
            .put("meta", meta)

        val req = Request.Builder().url("$auth/v1/shield/captcha/init")
            .post(Http.json(body.toString()))
            .header("Accept", "*/*")
            // 官方网页端这里就是 text/plain，改成 application/json 会被风控
            .header("Content-Type", "text/plain;charset=UTF-8")
            .header("Origin", "https://pan.xunlei.com")
            .header("Referer", "https://pan.xunlei.com/")
            .header("User-Agent", desktopUa)
            .build()
        Http.api.newCall(req).execute().use { r ->
            val txt = r.body?.string() ?: ""
            if (txt.contains("client_id not match")) {
                throw DriveError("迅雷：client_id 与验证码不匹配（网页盐可能已轮换）")
            }
            val j = runCatching { JSONObject(txt) }.getOrNull()
            val tk = j?.optString("captcha_token") ?: ""
            if (tk.isBlank()) throw DriveError("迅雷：取验证码令牌失败")
            captchaCache[action] = tk to (now + 270_000L)
            return tk
        }
    }

    // ---------- 请求 ----------

    private fun panGet(url: String, action: String): JSONObject {
        var attempt = 0
        var token = captcha(action)
        while (attempt < 2) {
            val b = Request.Builder().url(url).get()
                .header("User-Agent", webUa)
                .header("X-Device-Id", deviceId)
                .header("X-Client-Version", "8.31.0.9726")
                .header("Content-Type", "application/json")
                .header("Origin", "https://pan.xunlei.com")
                .header("Referer", "https://pan.xunlei.com/")
                .header("X-Captcha-Token", token)
            // 游客：绝不写 Authorization
            if (accessToken.isNotBlank()) b.header("Authorization", "Bearer $accessToken")

            Http.api.newCall(b.build()).execute().use { r ->
                val txt = r.body?.string() ?: throw DriveError("迅雷：空响应")
                val j = runCatching { JSONObject(txt) }.getOrElse {
                    throw DriveError("迅雷：响应非 JSON（HTTP ${r.code}）")
                }
                val err = j.optString("error")
                if (r.isSuccessful && !j.has("error")) {
                    return j.optJSONObject("data") ?: j
                }
                if (err == "captcha_invalid" && attempt == 0) {
                    captchaCache.remove(action)
                    token = captcha(action)
                    attempt++
                    return@use
                }
                val msg = j.optString("error_description").ifBlank { j.optString("message") }
                    .ifBlank { err }.ifBlank { "HTTP ${r.code}" }
                throw DriveError("迅雷：$msg")
            }
            attempt++
        }
        throw DriveError("迅雷：验证码刷新后仍失败")
    }

    private fun parseFiles(arr: JSONArray?): List<RemoteFile> {
        val out = ArrayList<RemoteFile>()
        if (arr == null) return out
        for (i in 0 until arr.length()) {
            val it = arr.getJSONObject(i)
            out.add(
                RemoteFile(
                    id = it.optString("id"),
                    name = it.optString("name"),
                    size = it.optLong("size"),
                    isDir = it.optString("kind") == "drive#folder",
                    path = it.optString("name"),
                    meta = mapOf("parent_id" to it.optString("parent_id"))
                )
            )
        }
        return out
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    // ---------- Drive ----------

    override fun list(share: ShareRef, dir: RemoteFile?): List<RemoteFile> {
        val out = ArrayList<RemoteFile>()
        var pageToken = ""
        var guard = 0
        while (guard++ < 30) {
            val data: JSONObject
            if (dir == null) {
                val url = "$pan/drive/v1/share?share_id=${share.key}" +
                    "&pass_code=${enc(share.passcode)}&limit=100" +
                    "&page_token=${enc(pageToken)}&thumbnail_size=SIZE_SMALL"
                data = panGet(url, "GET:/drive/v1/share")
                when (data.optString("share_status")) {
                    "PASS_CODE_EMPTY" -> throw DriveError("迅雷：请输入提取码")
                    "PASS_CODE_ERROR" -> throw DriveError("迅雷：提取码错误")
                    "PASS_CODE_NEED" -> throw DriveError("迅雷：该分享需要提取码")
                }
                data.optString("pass_code_token").takeIf { it.isNotBlank() }
                    ?.let { share.extra["pass_code_token"] = it }
            } else {
                val pct = share.extra["pass_code_token"] ?: ""
                val url = "$pan/drive/v1/share/detail?share_id=${share.key}" +
                    "&parent_id=${dir.id}&pass_code_token=${enc(pct)}&limit=100" +
                    "&page_token=${enc(pageToken)}&thumbnail_size=SIZE_SMALL"
                data = panGet(url, "GET:/drive/v1/share/detail")
            }
            out.addAll(parseFiles(data.optJSONArray("files")))
            pageToken = data.optString("next_page_token")
            if (pageToken.isBlank()) break
        }
        return out
    }

    override fun link(share: ShareRef, file: RemoteFile): DirectLink {
        val action = "GET:/drive/v1/files/${file.id}"
        val url = "$pan/drive/v1/files/${file.id}?_magic=2021&usage=FETCH" +
            "&thumbnail_size=SIZE_LARGE&with=audit"
        val data = panGet(url, action)
        val link = data.optJSONObject("links")
            ?.optJSONObject("application/octet-stream")?.optString("url")
            ?: data.optString("web_content_link")
        if (link.isBlank()) {
            throw DriveError(
                "迅雷：该分享文件取不到直链。迅雷对分享文件通常要求先转存到自己网盘，" +
                    "请在设置里登录迅雷后重试"
            )
        }
        val headers = mutableMapOf("User-Agent" to webUa)
        // 有登录态时直链需要带 Bearer
        if (accessToken.isNotBlank()) headers["Authorization"] = "Bearer $accessToken"
        return DirectLink(url = link, headers = headers, mirrors = MIRRORS)
    }

    companion object {
        /** CDN 镜像节点：替换直链 host 即可跨节点并发 */
        val MIRRORS = listOf(
            "vod0780-aliyun04-vip-lixian.xunlei.com",
            "vod0781-aliyun04-vip-lixian.xunlei.com",
            "vod3379-aliyun04-vip-lixian.xunlei.com",
            "vod3429-aliyun04-vip-lixian.xunlei.com",
            "vod0555-aliyun06-vip-lixian.xunlei.com",
            "vod0556-aliyun06-vip-lixian.xunlei.com",
            "vod0254-aliyun08-vip-lixian.xunlei.com",
            "vod0255-aliyun08-vip-lixian.xunlei.com",
            "vod0080-b02-vip-lixian.xunlei.com",
            "vod0432-b02-vip-lixian.xunlei.com",
            "vod0006-b05-vip-lixian.xunlei.com",
            "vod0009-b05-vip-lixian.xunlei.com",
            "vod0001-c01-vip-lixian.xunlei.com",
            "vod0070-h01-vip-lixian.xunlei.com",
            "vod0088-h04-vip-lixian.xunlei.com",
            "vod0003-h05-vip-lixian.xunlei.com",
            "vod0001-m01-vip-lixian.xunlei.com",
            "vod0064-txyun08-vip-lixian.xunlei.com",
            "vod0032-z01-vip-lixian.xunlei.com",
            "vod0035-z01-vip-lixian.xunlei.com"
        )

        fun expand(url: String, limit: Int = 8): List<String> {
            val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return listOf(url)
            val out = ArrayList<String>()
            out.add(url)
            MIRRORS.take(limit).forEach { out.add(url.replace(host, it)) }
            return out
        }
    }
}
