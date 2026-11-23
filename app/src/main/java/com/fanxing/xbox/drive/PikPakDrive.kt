package com.fanxing.xbox.drive

import com.fanxing.xbox.store.CredsHolder
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * PikPak（分享可游客解析；下载需登录）。
 *
 * 与迅雷同源：都是 shield/captcha/init 那套。
 * 这里走 **安卓客户端** profile（网页 profile 的 timestamp 是写死的常量，容易失效）。
 * captcha_sign = 9 层 md5，raw = clientId + version + package + deviceId + ts。
 */
class PikPakDrive : Drive {

    override val id = "pikpak"
    override val name = "PikPak"
    override val loginUrl = "https://mypikpak.com/"
    override val desc = "免登录可列目录。下载需登录（分享文件要先转存）"
    override val guestParse = true
    override val cookieDomain = "https://mypikpak.com"

    private val api = "https://api-drive.mypikpak.com"
    private val userApi = "https://user.mypikpak.com"
    private val clientId = "YNxT9w7GMdWvEOKa"
    private val version = "1.21.0"
    private val pkg = "com.pikcloud.pikpak"
    private val ua = "ANDROID-com.pikcloud.pikpak/1.21.0"

    private val salts = listOf(
        "",
        "E32cSkYXC2bciKJGxRsE8ZgwmH/YwkvpD6/O9guSOa2irCwciH4xPHaH",
        "QtqgfMgHP2TFl",
        "zOKgHT56L7nIzFzDpUGhpWFrgP53m3G6ML",
        "S",
        "THxpsktzfFXizUv7DK1y/N7NZ1WhayViluBEvAJJ8bA1Wr6",
        "y9PXH3xGUhG/zQI8CaapRw2LhldCaFM9CRlKpZXJvj+pifu",
        "+RaaG7T8FRTI4cP019N5y9ofLyHE9ySFUr",
        "6Pf1l8UTeuzYldGtb/d"
    )

    private val deviceId: String by lazy {
        val saved = CredsHolder.token("pikpak_device")
        if (saved.isNotBlank()) saved
        else {
            val hex = "0123456789abcdef"
            val r = SecureRandom()
            buildString { repeat(32) { append(hex[r.nextInt(16)]) } }
                .also { CredsHolder.creds.setToken("pikpak_device", it) }
        }
    }

    private val accessToken get() = CredsHolder.token(id)
    private val captchaCache = HashMap<String, String>()

    override fun match(url: String) =
        url.contains("mypikpak.com/s/") || url.contains("pikpak.com/s/")

    override fun parse(url: String): ShareRef {
        val key = Regex("pikpak\\.com/s/([A-Za-z0-9_-]+)").find(url)?.groupValues?.get(1)
            ?: throw DriveError("PikPak：链接里没找到分享 ID")
        val pwd = Regex("[?&](?:pwd|password|pass_code)=([A-Za-z0-9]+)").find(url)
            ?.groupValues?.get(1) ?: ""
        return ShareRef(key, pwd, url)
    }

    private fun md5(s: String): String {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
    }

    private fun captcha(action: String): String {
        captchaCache[action]?.let { return it }
        val ts = System.currentTimeMillis().toString()
        var h = clientId + version + pkg + deviceId + ts
        salts.forEach { h = md5(h + it) }

        val body = JSONObject()
            .put("client_id", clientId)
            .put("device_id", deviceId)
            .put("action", action)
            .put("captcha_token", "")
            .put("redirect_uri", "https://api.mypikpak.com/v1/auth/callback")
            .put("meta", JSONObject()
                .put("captcha_sign", "1.$h")
                .put("user_id", "")
                .put("package_name", pkg)
                .put("client_version", version)
                .put("timestamp", ts))

        val req = Request.Builder().url("$userApi/v1/shield/captcha/init")
            .post(Http.json(body.toString()))
            .header("User-Agent", ua)
            .header("X-Device-Id", deviceId)
            .header("X-Client-Id", clientId)
            .header("Content-Type", "application/json")
            .build()
        Http.api.newCall(req).execute().use { r ->
            val txt = r.body?.string() ?: throw DriveError("PikPak：验证码空响应")
            val j = runCatching { JSONObject(txt) }.getOrElse {
                throw DriveError("PikPak：验证码响应非 JSON")
            }
            if (j.optString("url").isNotBlank()) {
                throw DriveError("PikPak：需要人机验证，请在设置里用网页登录后重试")
            }
            val tk = j.optString("captcha_token")
            if (tk.isBlank()) throw DriveError("PikPak：未返回验证凭据")
            captchaCache[action] = tk
            return tk
        }
    }

    private fun get(url: String, action: String): JSONObject {
        val b = Request.Builder().url(url).get()
            .header("User-Agent", ua)
            .header("X-Device-Id", deviceId)
            .header("X-Client-Id", clientId)
            .header("X-Captcha-Token", captcha(action))
        if (accessToken.isNotBlank()) b.header("Authorization", "Bearer $accessToken")
        Http.api.newCall(b.build()).execute().use { r ->
            val txt = r.body?.string() ?: throw DriveError("PikPak：空响应")
            val j = runCatching { JSONObject(txt) }.getOrElse {
                throw DriveError("PikPak：响应非 JSON（HTTP ${r.code}）")
            }
            val err = j.optString("error")
            if (err.isNotBlank() || j.optInt("error_code", 0) != 0) {
                val hint = when (err) {
                    "file_restore_own" -> "这是你自己账号的分享，请在个人网盘里下载"
                    "captcha_required", "captcha_invalid", "captcha_token_invalid" ->
                        "需要安全验证，请网页登录后重试"
                    "unauthenticated", "invalid_grant" -> "登录已过期，请重新登录"
                    else -> err.ifBlank { "HTTP ${r.code}" }
                }
                throw DriveError("PikPak：$hint")
            }
            return j
        }
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
                    path = it.optString("name")
                )
            )
        }
        return out
    }

    override fun list(share: ShareRef, dir: RemoteFile?): List<RemoteFile> {
        val enc = { s: String -> java.net.URLEncoder.encode(s, "UTF-8") }
        val out = ArrayList<RemoteFile>()
        var page = ""
        var guard = 0
        while (guard++ < 30) {
            val url: String
            val action: String
            if (dir == null && share.extra["pass_code_token"].isNullOrBlank()) {
                action = "GET:/drive/v1/share"
                url = "$api/drive/v1/share?share_id=${share.key}&limit=100" +
                    "&thumbnail_size=SIZE_SMALL&pass_code=${enc(share.passcode)}"
            } else {
                action = "GET:/drive/v1/share/detail"
                val pct = share.extra["pass_code_token"] ?: ""
                url = "$api/drive/v1/share/detail?share_id=${share.key}&limit=100" +
                    "&thumbnail_size=SIZE_SMALL&pass_code_token=${enc(pct)}" +
                    (dir?.let { "&parent_id=${it.id}" } ?: "") +
                    (if (page.isNotBlank()) "&page_token=${enc(page)}" else "")
            }
            val j = get(url, action)
            j.optString("pass_code_token").takeIf { it.isNotBlank() }
                ?.let { share.extra["pass_code_token"] = it }
            out.addAll(parseFiles(j.optJSONArray("files")))
            page = j.optString("next_page_token")
            if (page.isBlank()) break
        }
        return out
    }

    override fun link(share: ShareRef, file: RemoteFile): DirectLink {
        if (accessToken.isBlank()) {
            throw DriveError("PikPak：下载需要登录（分享文件要先转存到自己网盘），请在设置里登录")
        }
        val url = "$api/drive/v1/files/${file.id}?usage=FETCH&_magic=2021"
        val j = get(url, "GET:/drive/v1/files/${file.id}")
        val link = j.optString("web_content_link")
            .ifBlank { j.optJSONArray("medias")?.optJSONObject(0)
                ?.optJSONObject("link")?.optString("url") ?: "" }
        if (link.isBlank()) throw DriveError("PikPak：未取到直链，可能需要先转存")
        return DirectLink(
            link,
            mapOf("User-Agent" to ua, "Authorization" to "Bearer $accessToken")
        )
    }
}
