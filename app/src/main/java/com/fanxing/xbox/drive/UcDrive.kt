package com.fanxing.xbox.drive

import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * UC 网盘（游客链路，无需登录）。
 *
 * 三个必须条件，缺一个 OSS 直链就 403：
 *   1. Referer: https://drive.uc.cn/   （缺它还会被限速到 ~100KB/s）
 *   2. uc-cloud-drive 客户端 UA
 *   3. Cookie: __pugs=<游客令牌>        （取链接口的 Set-Cookie 下发）
 * 且只能 GET，HEAD 一律被 Callback 拒绝。
 */
class UcDrive : Drive {

    override val id = "uc"
    override val name = "UC 网盘"
    override val loginUrl: String? = null

    private val api = "https://pc-api.uc.cn"

    private val guestUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "uc-cloud-drive/2.5.20 Chrome/100.0.4896.160 Electron/18.3.5.4-b478491100 " +
            "Safari/537.36 Channel/pckk_other_ch"

    private val secChUa =
        "\"Not=A?Brand\";v=\"99\", \"Chromium\";v=\"100\", \"Google Chrome\";v=\"100\""

    @Volatile private var pugs: String = ""

    override fun match(url: String) =
        url.contains("drive.uc.cn/s/") || url.contains("fast.uc.cn/s/")

    override fun parse(url: String): ShareRef {
        val key = Regex("(?:drive|fast)\\.uc\\.cn/s/([A-Za-z0-9]+)").find(url)
            ?.groupValues?.get(1) ?: throw DriveError("UC：链接里没找到分享 ID")
        val pwd = Regex("[?&](?:password|pwd|passcode)=([A-Za-z0-9]+)").find(url)
            ?.groupValues?.get(1) ?: ""
        return ShareRef(key, pwd, url)
    }

    private fun headers(cookie: String = ""): Map<String, String> {
        val m = linkedMapOf(
            "User-Agent" to guestUa,
            "Referer" to "https://drive.uc.cn/",
            "Origin" to "https://drive.uc.cn",
            "Content-Type" to "application/json;charset=UTF-8",
            "Accept" to "application/json, text/plain, */*",
            "Sec-Ch-Ua" to secChUa,
            "Sec-Ch-Ua-Mobile" to "?0",
            "Sec-Ch-Ua-Platform" to "\"Windows\""
        )
        val ck = cookie.ifBlank { if (pugs.isNotBlank()) "__pugs=$pugs" else "" }
        if (ck.isNotBlank()) m["Cookie"] = ck
        return m
    }

    private fun post(url: String, body: JSONObject): JSONObject {
        val b = Request.Builder().url(url).post(Http.json(body.toString()))
        headers().forEach { (k, v) -> b.header(k, v) }
        Http.api.newCall(b.build()).execute().use { r ->
            capturePugs(r.headers("Set-Cookie"))
            val txt = r.body?.string() ?: throw DriveError("UC：空响应")
            return JSONObject(txt)
        }
    }

    private fun capturePugs(setCookies: List<String>) {
        for (sc in setCookies) {
            val kv = sc.substringBefore(';').trim()
            if (kv.startsWith("__pugs=")) {
                val v = kv.removePrefix("__pugs=")
                if (v.isNotBlank() && v != "deleted") pugs = v
            }
        }
    }

    private fun stoken(share: ShareRef): String {
        share.extra["stoken"]?.let { return it }
        val body = JSONObject()
            .put("pwd_id", share.key)
            .put("passcode", share.passcode)
            .put("share_for_transfer", true)
        val j = post("$api/1/clouddrive/share/sharepage/token?pr=UCBrowser&fr=pc", body)
        val d = j.optJSONObject("data")
        val st = d?.optString("stoken") ?: ""
        if (st.isBlank()) {
            throw DriveError("UC：取分享令牌失败 ${j.optInt("code")} ${j.optString("message")}")
        }
        share.extra["stoken"] = st
        return st
    }

    /**
     * 列表必须走 **transfer_share/detail**（GET + query 带 stoken）。
     *
     * ★ 这是 41020「转存文件 token 校验异常」的根因：
     *   sharepage/v2/detail 返回的 share_fid_token 与 file/download?entry=ft
     *   所用的 stoken **不绑定**，拿它去取链一定报 token 校验异常。
     *   只有 transfer_share/detail 给出的 token 才配套。
     */
    override fun list(share: ShareRef, dir: RemoteFile?): List<RemoteFile> {
        val out = ArrayList<RemoteFile>()
        var page = 1
        var st = stoken(share)
        var retried = false
        while (page <= 50) {
            val enc = { s: String -> java.net.URLEncoder.encode(s, "UTF-8") }
            val url = "$api/1/clouddrive/transfer_share/detail?entry=ft&fr=pc&pr=UCBrowser" +
                "&pwd_id=${share.key}&passcode=${enc(share.passcode)}&stoken=${enc(st)}" +
                "&pdir_fid=${dir?.id ?: "0"}&fetch_file_list=1" +
                "&_page=$page&_size=100&_fetch_total=1&_fetch_share=1" +
                "&_sort=file_type:asc,file_name:asc"

            val b = Request.Builder().url(url).get()
            browserHeaders().forEach { (k, v) -> b.header(k, v) }
            val j = exec(b.build())
            val code = j.optInt("code")
            val msg = j.optString("message")

            // stoken 失效：重取一次再来
            if ((code == 14001 || msg.contains("token", true)) && !retried) {
                retried = true
                share.extra.remove("stoken")
                st = stoken(share)
                continue
            }
            if (code != 0) throw DriveError("UC：列表失败 [$code] $msg")

            val d = j.optJSONObject("data") ?: break
            val arr = d.optJSONArray("list")
                ?: d.optJSONObject("detail_info")?.optJSONArray("list")
                ?: JSONArray()
            if (arr.length() == 0) break
            for (i in 0 until arr.length()) {
                val it = arr.getJSONObject(i)
                // 官方用 file 布尔判定，不是 dir
                val isFile = it.optBoolean("file", true)
                out.add(
                    RemoteFile(
                        id = it.optString("fid"),
                        name = it.optString("file_name").ifBlank { it.optString("name") },
                        size = it.optLong("size"),
                        isDir = !isFile,
                        path = it.optString("file_name"),
                        meta = mapOf(
                            "fid_token" to it.optString("share_fid_token"),
                            "stoken" to st,
                            "pdir" to (dir?.id ?: "0")
                        )
                    )
                )
            }
            if (arr.length() < 100) break
            page++
        }
        return out
    }

    /** 列表接口用的浏览器头（与游客取链头不同） */
    private fun browserHeaders(): Map<String, String> {
        val m = linkedMapOf(
            "User-Agent" to
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            "Accept" to "application/json, text/plain, */*",
            "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
            "Origin" to "https://drive.uc.cn",
            "Referer" to "https://drive.uc.cn/",
            "Content-Type" to "application/json;charset=UTF-8"
        )
        val ck = loginCookie
        if (ck.isNotBlank()) m["Cookie"] = ck
        return m
    }

    private fun exec(req: Request): JSONObject {
        Http.api.newCall(req).execute().use { r ->
            capturePugs(r.headers("Set-Cookie"))
            val txt = r.body?.string() ?: throw DriveError("UC：空响应")
            return runCatching { JSONObject(txt) }.getOrElse {
                throw DriveError("UC：响应非 JSON（HTTP ${r.code}）")
            }
        }
    }

    override fun link(share: ShareRef, file: RemoteFile): DirectLink {
        // 已转存到自己网盘的文件走登录态取链
        if (file.meta["personal"] == "1") return personalLink(file)

        // ★ 必须用「产出这个 fid_token 的那一个 stoken」，不能用当前缓存的
        var st = file.meta["stoken"] ?: stoken(share)
        var fidToken = file.meta["fid_token"] ?: ""
        var refreshed = false

        while (true) {
            val body = JSONObject()
                .put("fids", JSONArray().put(file.id))
                .put("pwd_id", share.key)
                .put("stoken", st)
                .put("fids_token", JSONArray().put(fidToken))
            val j = post("$api/1/clouddrive/file/download?entry=ft&fr=pc&pr=UCBrowser", body)
            val code = j.optInt("code")
            val msg = j.optString("message")

            // token 不配套/过期：重取 stoken 并重新列一次目录拿配套 token
            if ((code == 41020 || code == 14001 || msg.contains("token", true)) && !refreshed) {
                refreshed = true
                share.extra.remove("stoken")
                st = stoken(share)
                val parent = file.meta["pdir"]?.let {
                    if (it == "0") null else RemoteFile(it, "", 0, true)
                }
                val fresh = runCatching { list(share, parent) }.getOrNull()
                    ?.firstOrNull { it.id == file.id }
                if (fresh != null) {
                    fidToken = fresh.meta["fid_token"] ?: ""
                    st = fresh.meta["stoken"] ?: st
                    continue
                }
            }

            val url = j.optJSONArray("data")?.optJSONObject(0)?.optString("download_url") ?: ""
            if (url.isBlank()) {
                val hint = when (code) {
                    23018 -> "文件太大，超出游客下载限制，请用「转存后下载」"
                    31001 -> "需要登录 UC"
                    41017 -> "游客不能下载，请登录后用「转存后下载」"
                    41020 -> "分享令牌校验失败，请重新解析该链接"
                    else -> msg
                }
                throw DriveError("UC：取直链失败 [$code] $hint")
            }
            if (pugs.isBlank()) throw DriveError("UC：未拿到游客令牌 __pugs")
            return DirectLink(
                url = url,
                headers = mapOf(
                    "User-Agent" to guestUa,
                    "Referer" to "https://drive.uc.cn/",
                    "Cookie" to "__pugs=$pugs"
                )
            )
        }
    }

    // ---------------- 一键转存下载 ----------------

    override val supportsTransfer: Boolean get() = true

    private val loginCookie get() = com.fanxing.xbox.store.CredsHolder.cookie(id)

    /** 登录态客户端 UA（与游客 UA 不同） */
    private val cloudUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "uc-cloud-drive/1.6.1 Chrome/100.0.4896.160 Electron/18.3.5.16-b62cf9c50d " +
            "Safari/537.36 Channel/ucpan_other_ch"

    private fun authPost(url: String, body: JSONObject): JSONObject {
        val ck = loginCookie
        if (ck.isBlank()) throw DriveError("UC：转存需要先登录，请在设置里登录 UC")
        val b = Request.Builder().url(url).post(Http.json(body.toString()))
            .header("Cookie", ck)
            .header("User-Agent", cloudUa)
            .header("Origin", "https://drive.uc.cn")
            .header("Referer", "https://drive.uc.cn/")
            .header("Content-Type", "application/json;charset=UTF-8")
        Http.api.newCall(b.build()).execute().use { r ->
            val txt = r.body?.string() ?: throw DriveError("UC：空响应")
            return runCatching { JSONObject(txt) }.getOrElse {
                throw DriveError("UC：响应非 JSON（HTTP ${r.code}）")
            }
        }
    }

    private fun tempDir(): String {
        val j = authPost(
            "$api/1/clouddrive/file?pr=UCBrowser&fr=pc",
            JSONObject().put("pdir_fid", "0").put("file_name", "starbox临时转存")
                .put("dir_path", "").put("dir_init_lock", false)
        )
        val fid = j.optJSONObject("data")?.optString("fid") ?: ""
        if (fid.isBlank()) throw DriveError("UC：创建转存目录失败 ${j.optString("message")}")
        return fid
    }

    private fun pollTask(taskId: String): String {
        repeat(12) {
            val url = "$api/1/clouddrive/task?pr=UCBrowser&fr=pc&task_id=$taskId&retry_index=0"
            val b = Request.Builder().url(url).get()
                .header("Cookie", loginCookie)
                .header("User-Agent", cloudUa)
                .header("Referer", "https://drive.uc.cn/")
            runCatching {
                Http.api.newCall(b.build()).execute().use { r ->
                    val j = JSONObject(r.body?.string() ?: "{}")
                    val d = j.optJSONObject("data") ?: return@use
                    val finished = d.optLong("finished_at") > 0 ||
                        d.optInt("status") == 2 || d.optInt("task_status") == 2
                    if (finished) {
                        val fid = d.optJSONObject("save_as")
                            ?.optJSONArray("save_as_top_fids")?.optString(0) ?: ""
                        if (fid.isNotBlank()) return fid
                    }
                }
            }
            Thread.sleep(1000)
        }
        throw DriveError("UC：转存任务超时，请到网盘里确认是否已保存")
    }

    override fun transfer(share: ShareRef, files: List<RemoteFile>): List<RemoteFile> {
        val st = stoken(share)
        val to = tempDir()
        val out = ArrayList<RemoteFile>()
        for (f in files) {
            val j = authPost(
                "$api/1/clouddrive/share/sharepage/save?pr=UCBrowser&fr=pc",
                JSONObject()
                    .put("pwd_id", share.key).put("stoken", st)
                    .put("pdir_fid", "0").put("to_pdir_fid", to)
                    .put("fid_list", JSONArray().put(f.id))
                    .put("fid_token_list", JSONArray().put(f.meta["fid_token"] ?: ""))
                    .put("scene", "link")
            )
            val taskId = j.optJSONObject("data")?.optString("task_id") ?: ""
            if (taskId.isBlank()) {
                throw DriveError("UC：转存失败 ${j.optString("message")}（可能空间不足）")
            }
            val newFid = pollTask(taskId)
            out.add(f.copy(id = newFid, meta = mapOf("personal" to "1")))
        }
        return out
    }

    /** 个人网盘取链（转存后用） */
    private fun personalLink(file: RemoteFile): DirectLink {
        val j = authPost(
            "$api/1/clouddrive/file/download?pr=UCBrowser&fr=pc&sys=win32&ve=1.6.1",
            JSONObject().put("fids", JSONArray().put(file.id))
        )
        val url = j.optJSONArray("data")?.optJSONObject(0)?.optString("download_url") ?: ""
        if (url.isBlank()) throw DriveError("UC：个人盘取直链失败 ${j.optString("message")}")
        return DirectLink(
            url,
            mapOf(
                "User-Agent" to cloudUa,
                "Referer" to "https://drive.uc.cn/",
                "Cookie" to loginCookie
            )
        )
    }
}
