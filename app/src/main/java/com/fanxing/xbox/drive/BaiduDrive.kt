package com.fanxing.xbox.drive

import com.fanxing.xbox.store.CredsHolder
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

/**
 * 百度网盘（需登录，WebView 抓 BDUSS）。
 *
 * 两个关键点：
 *   1. 分享直链走 api/sharedownload（配 share/tplconfig 的 sign/timestamp），
 *      不是 filemetas。
 *   2. 下载直链时 User-Agent 必须是 "pan.baidu.com"，
 *      否则超过 50MB 的文件服务器直接 403。
 */
class BaiduDrive : Drive {

    override val id = "baidu"
    override val name = "百度网盘"
    override val loginUrl = "https://pan.baidu.com/"
    override val cookieDomain = "https://pan.baidu.com"

    private val pan = "https://pan.baidu.com"
    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Safari/537.36"

    private val jar = LinkedHashMap<String, String>()

    init {
        CredsHolder.cookie(id).split(";").forEach {
            val kv = it.trim()
            val i = kv.indexOf('=')
            if (i > 0) jar[kv.substring(0, i)] = kv.substring(i + 1)
        }
    }

    override fun match(url: String) = url.contains("pan.baidu.com/s/")

    override fun parse(url: String): ShareRef {
        val m = Regex("pan\\.baidu\\.com/s/1([A-Za-z0-9_-]+)").find(url)
            ?: throw DriveError("百度：链接格式不对")
        val pwd = Regex("[?&](?:pwd|password|passcode)=([A-Za-z0-9]+)").find(url)
            ?.groupValues?.get(1) ?: ""
        return ShareRef(m.groupValues[1], pwd, url)
    }

    private fun cookieHeader(): String =
        jar.entries.joinToString("; ") { "${it.key}=${it.value}" }

    private fun capture(r: Response) {
        r.headers("Set-Cookie").forEach { sc ->
            val kv = sc.substringBefore(';').trim()
            val i = kv.indexOf('=')
            if (i > 0) jar[kv.substring(0, i)] = kv.substring(i + 1)
        }
    }

    private fun get(url: String, referer: String): String {
        val b = Request.Builder().url(url).get()
            .header("User-Agent", ua)
            .header("Referer", referer)
            .header("Accept-Language", "zh-CN,zh;q=0.9")
        if (jar.isNotEmpty()) b.header("Cookie", cookieHeader())
        Http.api.newCall(b.build()).execute().use { r ->
            capture(r)
            return r.body?.string() ?: ""
        }
    }

    private fun postForm(url: String, form: Map<String, String>, referer: String): String {
        val b = Request.Builder().url(url).post(Http.form(form))
            .header("User-Agent", ua)
            .header("Referer", referer)
            .header("Content-Type", "application/x-www-form-urlencoded")
        if (jar.isNotEmpty()) b.header("Cookie", cookieHeader())
        Http.api.newCall(b.build()).execute().use { r ->
            capture(r)
            return r.body?.string() ?: ""
        }
    }

    /** 打开分享页：拿基础 Cookie，并从 HTML 里抠 uk / shareid / bdstoken */
    private fun prime(share: ShareRef) {
        if (share.extra["primed"] == "1") return
        val pageUrl = "$pan/s/1${share.key}" +
            if (share.passcode.isNotBlank()) "?pwd=${share.passcode}" else ""
        val html = get(pageUrl, pan)
        Regex("\"uk\"\\s*:\\s*\"?(\\d+)").find(html)?.let { share.extra["uk"] = it.groupValues[1] }
        Regex("\"shareid\"\\s*:\\s*\"?(\\d+)").find(html)?.let { share.extra["shareid"] = it.groupValues[1] }
        Regex("\"bdstoken\"\\s*:\\s*\"([a-f0-9]+)\"").find(html)?.let { share.extra["bdstoken"] = it.groupValues[1] }
        jar["BDSTOKEN"]?.let { share.extra["bdstoken"] = it }
        share.extra["primed"] = "1"
    }

    private fun sekey(share: ShareRef): String {
        share.extra["sekey"]?.let { return it }
        prime(share)
        val txt = postForm(
            "$pan/share/verify?surl=${share.key}",
            mapOf("pwd" to share.passcode, "vcode_str" to "", "vcode" to ""),
            "$pan/s/1${share.key}"
        )
        val j = runCatching { JSONObject(txt) }.getOrNull()
            ?: throw DriveError("百度：提取码校验响应异常")
        val sk = j.optString("randsk")
        if (j.optInt("errno") != 0 || sk.isBlank()) {
            throw DriveError("百度：提取码校验失败 errno=${j.optInt("errno")}（提取码错误或需要登录）")
        }
        share.extra["sekey"] = sk
        return sk
    }

    override fun list(share: ShareRef, dir: RemoteFile?): List<RemoteFile> {
        val sk = sekey(share)
        val path = dir?.path ?: "/"
        val params = StringBuilder("$pan/rest/2.0/xpan/share?method=list")
            .append("&shorturl=").append(share.key)
            .append("&page=1&num=1000")
            .append("&root=").append(if (dir == null) "1" else "0")
            .append("&dir=").append(java.net.URLEncoder.encode(path, "UTF-8"))
            .append("&sekey=").append(java.net.URLEncoder.encode(sk, "UTF-8"))
        val txt = get(params.toString(), "$pan/s/1${share.key}")
        val j = runCatching { JSONObject(txt) }.getOrNull()
            ?: throw DriveError("百度：列表响应异常")
        if (j.optInt("errno") != 0) {
            throw DriveError("百度：列表失败 errno=${j.optInt("errno")}")
        }
        val arr = j.optJSONArray("list") ?: JSONArray()
        val out = ArrayList<RemoteFile>()
        for (i in 0 until arr.length()) {
            val it = arr.getJSONObject(i)
            out.add(
                RemoteFile(
                    id = it.optLong("fs_id").toString(),
                    name = it.optString("server_filename"),
                    size = it.optLong("size"),
                    isDir = it.optInt("isdir") == 1,
                    path = it.optString("path")
                )
            )
        }
        return out
    }

    override fun link(share: ShareRef, file: RemoteFile): DirectLink {
        val sk = sekey(share)
        val uk = share.extra["uk"] ?: throw DriveError("百度：没拿到分享者 uk，请先登录")
        val sid = share.extra["shareid"] ?: throw DriveError("百度：没拿到 shareid，请先登录")
        val bdstoken = share.extra["bdstoken"] ?: ""

        // sign / timestamp
        val signUrl = "$pan/share/tplconfig?fields=sign,timestamp&channel=chunlei&web=1" +
            "&app_id=250528&clienttype=0&view_mode=1&surl=1${share.key}&bdstoken=$bdstoken"
        val sj = runCatching { JSONObject(get(signUrl, "$pan/s/1${share.key}")) }.getOrNull()
        val sd = sj?.optJSONObject("data")
        val sign = sd?.optString("sign") ?: ""
        val ts = sd?.optLong("timestamp") ?: 0L

        val url = "$pan/api/sharedownload?channel=chunlei&clienttype=0&web=1&app_id=250528" +
            "&sign=$sign&timestamp=$ts&bdstoken=$bdstoken"
        val form = mapOf(
            "encrypt" to "0",
            "product" to "share",
            "uk" to uk,
            "primaryid" to sid,
            "fid_list" to JSONArray().put(file.id.toLong()).toString(),
            "extra" to JSONObject().put("sekey", sk).toString()
        )
        val txt = postForm(url, form, "$pan/s/1${share.key}")
        val j = runCatching { JSONObject(txt) }.getOrNull()
            ?: throw DriveError("百度：取直链响应异常")
        val errno = j.optInt("errno")
        if (errno != 0) {
            val hint = when (errno) {
                112 -> "页面已过期，请重新解析"
                9019 -> "访问令牌过期，请重新登录"
                -20 -> "需要验证码或登录"
                else -> "errno=$errno"
            }
            throw DriveError("百度：取直链失败 $hint")
        }
        val dlink = j.optJSONArray("list")?.optJSONObject(0)?.optString("dlink") ?: ""
        if (dlink.isBlank()) throw DriveError("百度：响应里没有 dlink")
        return DirectLink(
            url = dlink,
            // ★ 关键：UA 必须是 pan.baidu.com，否则 >50MB 直接 403
            headers = mapOf("User-Agent" to "pan.baidu.com")
        )
    }
}
