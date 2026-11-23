package com.fanxing.xbox.drive

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 中国移动云盘（139，分享免登录）。
 *
 * 请求体是 **AES-128-CBC + PKCS5**，随机 IV 前置后整体 base64；
 * 响应同样加密，解出来若以 1F 8B 开头还要 gzip 解压。
 * 取链额外需要 mcloud-sign 头（字符排序 + 两次 md5）。
 *
 * 加解密全部用 JDK 自带 javax.crypto，零第三方依赖。
 */
class Yidong139Drive : Drive {

    override val id = "yidong139"
    override val name = "移动云盘"
    override val loginUrl: String? = null
    override val desc = "免登录。请求体 AES 加密，需 mcloud-sign 签名头"

    private val base = "https://share-kd-njs.yun.139.com/yun-share/richlifeApp/devapp/IOutLink"
    private val aesKey = "PVGDwmcvfs1uV3d1".toByteArray(Charsets.UTF_8)

    private val ua =
        "Mozilla/5.0 (Linux; Android 12; LIO-AN00 Build/HUAWEILIO-AN00; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/99.0.4844.88 " +
            "Mobile Safari/537.36 MCloudApp/7.17.9"

    override fun match(url: String) =
        url.contains("yun.139.com") || url.contains("caiyun.139.com")

    override fun parse(url: String): ShareRef {
        val key = Regex("yun\\.139\\.com/shareweb/.*?/w/i/([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1)
            ?: Regex("[?&](?:linkId|data)=([^&]+)").find(url)?.groupValues?.get(1)
            ?: throw DriveError("移动云盘：链接里没找到 linkID")
        val pwd = Regex("[?&](?:pwd|password|passwd)=([^&]+)").find(url)?.groupValues?.get(1) ?: ""
        return ShareRef(key, pwd, url)
    }

    // ---------- 加解密 ----------

    private fun encrypt(plain: String): String {
        val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + ct, Base64.NO_WRAP)
    }

    private fun decrypt(b64: String): String {
        val raw = Base64.decode(b64.trim(), Base64.DEFAULT)
        if (raw.size <= 16) return b64
        val iv = raw.copyOfRange(0, 16)
        val ct = raw.copyOfRange(16, raw.size)
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
        var d = c.doFinal(ct)
        if (d.size > 2 && d[0] == 0x1F.toByte() && d[1] == 0x8B.toByte()) {
            d = GZIPInputStream(d.inputStream()).use { it.readBytes() }
        }
        return String(d, Charsets.UTF_8)
    }

    private fun md5(s: String): String {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
    }

    /** 等价 JS encodeURIComponent：空格→%20，保留 ! ' ( ) * */
    private fun encodeUriComponent(s: String): String {
        var out = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        listOf("%21" to "!", "%27" to "'", "%28" to "(", "%29" to ")", "%2A" to "*", "%7E" to "~")
            .forEach { (a, b) -> out = out.replace(a, b) }
        return out
    }

    private fun signHeader(bodyJson: String): String {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val r = SecureRandom()
        val rand = buildString { repeat(16) { append(chars[r.nextInt(chars.length)]) } }
        val encoded = encodeUriComponent(bodyJson)
        val sorted = encoded.toCharArray().sorted().joinToString("")
        val b64 = Base64.encodeToString(sorted.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val sign = md5(md5(b64) + md5("$ts:$rand")).uppercase(Locale.US)
        return "$ts,$rand,$sign"
    }

    private fun post(url: String, plain: String, sign: Boolean = false): JSONObject {
        val b = Request.Builder().url(url)
            .post(encrypt(plain).toRequestBody("text/plain; charset=utf-8".toMediaType()))
            .header("hcy-cool-flag", "1")
            .header("x-deviceinfo", "||9|7.17.9|chrome|116.0.0.0|2cdaf7ada9e353c70eba99092e177991||windows 10||zh-CN|||")
            .header("x-huawei-channelsrc", "10000034")
            .header("x-mm-source", "10002")
            .header("Content-Type", "application/json;charset=UTF-8")
            .header("User-Agent", ua)
            .header("Origin", "https://yun.139.com")
            .header("Referer", "https://yun.139.com/")
            .header("Accept", "application/json, text/plain, */*")
        if (sign) b.header("mcloud-sign", signHeader(plain))
        Http.api.newCall(b.build()).execute().use { r ->
            val txt = r.body?.string() ?: throw DriveError("移动云盘：空响应")
            val decoded = runCatching { decrypt(txt) }.getOrDefault(txt)
            return runCatching { JSONObject(decoded) }.getOrElse {
                throw DriveError("移动云盘：响应解析失败（HTTP ${r.code}）")
            }
        }
    }

    private fun general(share: ShareRef): JSONObject {
        val plain = JSONObject().put(
            "getOutLinkGeneralReq",
            JSONObject().put("linkID", share.key).put("isPasswd", 1).put("account", "")
        ).toString()
        val j = post("$base/getOutLinkGeneral", plain)
        return j.optJSONObject("data")?.optJSONObject("getOutLinkGeneralResp") ?: JSONObject()
    }

    private fun passwdOf(share: ShareRef): String {
        if (share.passcode.isNotBlank()) return share.passcode
        share.extra["passwd"]?.let { return it }
        val arr = general(share).optJSONArray("outLinkGeneral")
        val p = arr?.optJSONObject(0)?.optString("passwd") ?: ""
        share.extra["passwd"] = p
        return p
    }

    override fun list(share: ShareRef, dir: RemoteFile?): List<RemoteFile> {
        val req = JSONObject()
            .put("account", "")
            .put("linkID", share.key)
            .put("passwd", passwdOf(share))
            .put("caSrt", 1).put("coSrt", 1).put("srtDr", 0)
            .put("bNum", 1).put("pCaID", dir?.id ?: "root").put("eNum", 200)
        val plain = JSONObject().put("getOutLinkInfoReq", req).toString()
        val j = post("$base/getOutLinkInfoV6", plain)
        val code = j.optString("resultCode")
        if (code.isNotBlank() && code != "0") {
            throw DriveError("移动云盘：列表失败 ${j.optString("desc").ifBlank { code }}")
        }
        val d = j.optJSONObject("data") ?: JSONObject()
        val out = ArrayList<RemoteFile>()
        val dirs = d.optJSONArray("caLst") ?: JSONArray()
        for (i in 0 until dirs.length()) {
            val it = dirs.getJSONObject(i)
            out.add(RemoteFile(it.optString("caID"), it.optString("caName"), 0, true, it.optString("caName")))
        }
        val files = d.optJSONArray("coLst") ?: JSONArray()
        for (i in 0 until files.length()) {
            val it = files.getJSONObject(i)
            val isDir = it.optBoolean("isdir") || it.optInt("coType") == 2
            out.add(
                RemoteFile(
                    it.optString("coID"), it.optString("coName"),
                    it.optLong("coSize"), isDir, it.optString("coName")
                )
            )
        }
        return out
    }

    override fun link(share: ShareRef, file: RemoteFile): DirectLink {
        val req = JSONObject()
            .put("account", "")
            .put("linkID", share.key)
            .put("coIDLst", JSONObject().put("item", JSONArray().put(file.id)))
            .put("commonAccountInfo", JSONObject().put("account", "").put("accountType", 1))
        val plain = JSONObject().put("dlFromOutLinkReqV3", req).toString()
        val j = post("$base/dlFromOutLinkV3", plain, sign = true)
        val code = j.optString("resultCode")
        if (code.isNotBlank() && code != "0") {
            throw DriveError("移动云盘：取直链失败 ${j.optString("desc").ifBlank { code }}")
        }
        val url = j.optJSONObject("data")?.optString("redrUrl") ?: ""
        if (url.isBlank()) throw DriveError("移动云盘：未返回直链")
        return DirectLink(url, mapOf("User-Agent" to ua, "Referer" to "https://yun.139.com/"))
    }
}
