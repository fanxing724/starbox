package com.fanxing.xbox.drive

import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * 夸克网盘（游客可下小文件）。
 *
 * 与 UC 同宗但域名/参数不同：
 *   API   drive-pc.quark.cn，pr=ucpro
 *   列表  GET share/sharepage/detail（UC 是 POST v2/detail）
 *   取链  POST file/download?pr=ucpro&fr=pc&sys=win32&ve=3.23.2
 *
 * 坑：游客取链的 UA 用的是 **uc-cloud-drive**（不是 quark-cloud-drive），
 *     且必须带响应下发的 __pugs，缺它 CDN 直接 412。
 */
class QuarkDrive : Drive {

    override val id = "quark"
    override val name = "夸克网盘"
    override val loginUrl = "https://pan.quark.cn/"
    override val desc = "免登录可解析。小文件直接下，大文件需登录或转存"
    override val guestParse = true
    override val cookieDomain = "https://pan.quark.cn"

    private val api = "https://drive-pc.quark.cn"
    private val TEMP_DIR = "starbox临时转存"

    private val uaQuark =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) quark-cloud-drive/2.5.20 Chrome/100.0.4896.160 " +
            "Electron/18.3.5.12-a038f7b798 Safari/537.36 Channel/pckk_other_ch"

    private val uaGuest =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) uc-cloud-drive/2.5.20 Chrome/100.0.4896.160 " +
            "Electron/18.3.5.4-b478491100 Safari/537.36 Channel/pckk_other_ch"

    private val secChUa =
        "\"Not=A?Brand\";v=\"99\", \"Chromium\";v=\"100\", \"Google Chrome\";v=\"100\""

    @Volatile private var pugs: String = ""

    override fun match(url: String) = url.contains("pan.quark.cn/s/")

    override fun parse(url: String): ShareRef {
        val key = Regex("pan\\.quark\\.cn/s/([A-Za-z0-9]+)").find(url)
            ?.groupValues?.get(1) ?: throw DriveError("夸克：链接里没找到分享 ID")
        val pwd = Regex("[?&](?:password|pwd|passcode)=([A-Za-z0-9]+)").find(url)
            ?.groupValues?.get(1) ?: ""
        return ShareRef(key, pwd, url)
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

    private fun apiHeaders(): Map<String, String> = mapOf(
        "User-Agent" to uaQuark,
        "Referer" to "https://pan.quark.cn/",
        "Origin" to "https://pan.quark.cn",
        "Content-Type" to "application/json;charset=UTF-8"
    )

    private fun guestHeaders(): Map<String, String> {
        val m = linkedMapOf(
            "User-Agent" to uaGuest,
            "Sec-Ch-Ua" to secChUa,
            "Content-Type" to "application/json;charset=UTF-8",
            "Origin" to "https://pan.quark.cn",
            "Referer" to "https://pan.quark.cn/"
        )
        if (pugs.isNotBlank()) m["Cookie"] = "__pugs=$pugs"
        return m
    }

    private fun exec(req: Request): JSONObject {
        Http.api.newCall(req).execute().use { r ->
            capturePugs(r.headers("Set-Cookie"))
            val txt = r.body?.string() ?: throw DriveError("夸克：空响应")
            val j = runCatching { JSONObject(txt) }.getOrElse {
                com.fanxing.xbox.store.AppLog.e("Quark", "响应非 JSON HTTP ${r.code} body=${txt.take(200)}")
                throw DriveError("夸克：响应非 JSON（HTTP ${r.code}）")
            }
            com.fanxing.xbox.store.AppLog.i("Quark", "HTTP ${r.code} code=${j.optInt("code")} msg=${j.optString("message")}")
            return j
        }
    }

    private fun post(url: String, body: JSONObject, headers: Map<String, String>): JSONObject {
        val b = Request.Builder().url(url).post(Http.json(body.toString()))
        headers.forEach { (k, v) -> b.header(k, v) }
        return exec(b.build())
    }

    private fun stoken(share: ShareRef): String {
        share.extra["stoken"]?.let { return it }
        val j = post(
            "$api/1/clouddrive/share/sharepage/token?pr=ucpro&fr=pc",
            JSONObject().put("pwd_id", share.key).put("passcode", share.passcode),
            apiHeaders()
        )
        if (j.optInt("code") != 0) {
            throw DriveError("夸克：取分享令牌失败 ${j.optString("message")}")
        }
        val st = j.optJSONObject("data")?.optString("stoken") ?: ""
        if (st.isBlank()) throw DriveError("夸克：未返回 stoken")
        share.extra["stoken"] = st
        return st
    }

    override fun list(share: ShareRef, dir: RemoteFile?): List<RemoteFile> {
        val st = stoken(share)
        val enc = { s: String -> java.net.URLEncoder.encode(s, "UTF-8") }
        val url = "$api/1/clouddrive/share/sharepage/detail?pr=ucpro&fr=pc" +
            "&pwd_id=${share.key}&stoken=${enc(st)}&pdir_fid=${dir?.id ?: "0"}&force=0" +
            "&_page=1&_size=200&_fetch_banner=1&_fetch_share=1&_fetch_total=1" +
            "&_sort=file_type:asc,file_name:asc"
        val b = Request.Builder().url(url).get()
        apiHeaders().forEach { (k, v) -> b.header(k, v) }
        val j = exec(b.build())
        if (j.optInt("code") != 0) {
            throw DriveError("夸克：列表失败 ${j.optString("message")}")
        }
        val arr = j.optJSONObject("data")?.optJSONArray("list") ?: JSONArray()
        val out = ArrayList<RemoteFile>()
        for (i in 0 until arr.length()) {
            val it = arr.getJSONObject(i)
            val isFile = when {
                it.optBoolean("dir", false) -> false
                it.optBoolean("file", false) -> true
                it.optInt("file_type", 0) > 0 -> true
                else -> it.optString("obj_category").isNotBlank()
            }
            out.add(
                RemoteFile(
                    id = it.optString("fid"),
                    name = it.optString("file_name").ifBlank { it.optString("name") },
                    size = it.optLong("size"),
                    isDir = !isFile,
                    path = it.optString("file_name"),
                    meta = mapOf("share_fid_token" to it.optString("share_fid_token"))
                )
            )
        }
        return out
    }

    override fun link(share: ShareRef, file: RemoteFile): DirectLink {
        if (file.meta["personal"] == "1") return personalLink(file)
        val st = stoken(share)
        val body = JSONObject()
            .put("fids", JSONArray().put(file.id))
            .put("pwd_id", share.key)
            .put("stoken", st)
        file.meta["share_fid_token"]?.takeIf { it.isNotBlank() }?.let {
            body.put("fids_token", JSONArray().put(it))
        }
        val j = post(
            "$api/1/clouddrive/file/download?pr=ucpro&fr=pc&sys=win32&ve=3.23.2",
            body, guestHeaders()
        )
        val code = j.optInt("code")
        if (code != 0) {
            val hint = when (code) {
                31001 -> "该分享需要登录夸克后才能下载"
                23018 -> "文件超出游客下载大小上限，请登录后重试"
                else -> j.optString("message")
            }
            throw DriveError("夸克：取直链失败 [$code] $hint")
        }
        val url = j.optJSONArray("data")?.optJSONObject(0)?.optString("download_url") ?: ""
        if (url.isBlank()) throw DriveError("夸克：未返回直链")
        val h = linkedMapOf(
            "Referer" to "https://pan.quark.cn/",
            "User-Agent" to uaGuest
        )
        if (pugs.isNotBlank()) h["Cookie"] = "__pugs=$pugs"
        return DirectLink(url, h)
    }

    // ---------------- 一键转存下载 ----------------

    override val supportsTransfer: Boolean get() = true

    private val loginCookie get() = com.fanxing.xbox.store.CredsHolder.cookie(id)

    private fun authPost(url: String, body: JSONObject): JSONObject {
        val ck = loginCookie
        if (ck.isBlank()) throw DriveError("夸克：转存需要先登录，请在设置里登录夸克")
        com.fanxing.xbox.store.AppLog.d("Quark", "POST ${url.substringAfter("/1/clouddrive/").substringBefore("?")}")
        val b = Request.Builder().url(url).post(Http.json(body.toString()))
            .header("Cookie", ck)
            .header("User-Agent", uaQuark)
            .header("Origin", "https://pan.quark.cn")
            .header("Referer", "https://pan.quark.cn/")
            .header("Content-Type", "application/json;charset=UTF-8")
        Http.api.newCall(b.build()).execute().use { r ->
            val txt = r.body?.string() ?: throw DriveError("夸克：空响应")
            val j = runCatching { JSONObject(txt) }.getOrElse {
                com.fanxing.xbox.store.AppLog.e("Quark", "响应非 JSON HTTP ${r.code} body=${txt.take(200)}")
                throw DriveError("夸克：响应非 JSON（HTTP ${r.code}）")
            }
            com.fanxing.xbox.store.AppLog.i("Quark", "HTTP ${r.code} code=${j.optInt("code")} msg=${j.optString("message")}")
            return j
        }
    }


    /** 登录态 GET（查个人盘目录用） */
    private fun authGet(url: String): JSONObject {
        val ck = loginCookie
        if (ck.isBlank()) throw DriveError("夸克：需要先登录")
        val b = Request.Builder().url(url).get()
            .header("Cookie", ck)
            .header("User-Agent", uaQuark)
            .header("Referer", "https://pan.quark.cn/")
        Http.api.newCall(b.build()).execute().use { r ->
            val txt = r.body?.string() ?: throw DriveError("夸克：空响应")
            return runCatching { JSONObject(txt) }.getOrElse {
                throw DriveError("夸克：响应非 JSON（HTTP ${r.code}）")
            }
        }
    }

    /** 根目录下按名字找已存在的文件夹 */
    private fun findOwnFolder(name: String): String? = runCatching {
        val j = authGet(
            "$api/1/clouddrive/file/sort?pr=ucpro&fr=pc&pdir_fid=0" +
                "&_page=1&_size=200&_sort=file_type:asc,file_name:asc"
        )
        val arr = j.optJSONObject("data")?.optJSONArray("list") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val it = arr.getJSONObject(i)
            val isDir = it.optBoolean("dir", false) || !it.optBoolean("file", true)
            if (isDir && it.optString("file_name") == name) return@runCatching it.optString("fid")
        }
        null
    }.getOrNull()

    private fun tempDir(): String {
        val j = authPost(
            "$api/1/clouddrive/file?pr=ucpro&fr=pc",
            JSONObject().put("pdir_fid", "0").put("file_name", TEMP_DIR)
                .put("dir_path", "").put("dir_init_lock", false)
        )
        val fid = j.optJSONObject("data")?.optString("fid")?.takeIf { it.isNotBlank() }
        if (fid != null) {
            com.fanxing.xbox.store.AppLog.i("Quark", "临时目录已建 fid=$fid")
            return fid
        }
        // 23008「同名冲突」= 上次已经建过了，直接复用，不是错误
        val msg = j.optString("message")
        if (j.optInt("code") == 23008 || msg.contains("同名") || msg.contains("doloading")) {
            val exist = findOwnFolder(TEMP_DIR)
            if (!exist.isNullOrBlank()) {
                com.fanxing.xbox.store.AppLog.i("Quark", "临时目录已存在，复用 fid=$exist")
                return exist
            }
            com.fanxing.xbox.store.AppLog.w("Quark", "同名冲突但根目录没找到 $TEMP_DIR")
        }
        throw DriveError("夸克：创建转存目录失败 ${j.optString("message")}")
    }

    private fun pollTask(taskId: String): String {
        repeat(12) {
            val b = Request.Builder()
                .url("$api/1/clouddrive/task?pr=ucpro&fr=pc&task_id=$taskId&retry_index=0")
                .get()
                .header("Cookie", loginCookie)
                .header("User-Agent", uaQuark)
                .header("Referer", "https://pan.quark.cn/")
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
        com.fanxing.xbox.store.AppLog.e("Quark", "转存任务超时 task_id=$taskId")
        throw DriveError("夸克：转存任务超时，请到网盘里确认是否已保存")
    }

    override fun transfer(share: ShareRef, files: List<RemoteFile>): List<RemoteFile> {
        val st = stoken(share)
        val to = tempDir()
        val out = ArrayList<RemoteFile>()
        for (f in files) {
            val j = authPost(
                "$api/1/clouddrive/share/sharepage/save?pr=ucpro&fr=pc",
                JSONObject()
                    .put("pwd_id", share.key).put("stoken", st)
                    .put("pdir_fid", "0").put("to_pdir_fid", to)
                    .put("fid_list", JSONArray().put(f.id))
                    .put("fid_token_list", JSONArray().put(f.meta["share_fid_token"] ?: ""))
                    .put("scene", "link")
            )
            val taskId = j.optJSONObject("data")?.optString("task_id") ?: ""
            com.fanxing.xbox.store.AppLog.i("Quark", "转存 ${f.name} → task_id=${taskId.ifBlank { "无" }} code=${j.optInt("code")} msg=${j.optString("message")}")
            if (taskId.isBlank()) {
                throw DriveError("夸克：转存失败 ${j.optString("message")}（可能空间不足）")
            }
            val newFid = pollTask(taskId)
            com.fanxing.xbox.store.AppLog.i("Quark", "转存完成 新 fid=$newFid")
            out.add(f.copy(id = newFid, meta = mapOf("personal" to "1")))
        }
        return out
    }

    private fun personalLink(file: RemoteFile): DirectLink {
        val j = authPost(
            "$api/1/clouddrive/file/download?pr=ucpro&fr=pc",
            JSONObject().put("fids", JSONArray().put(file.id))
        )
        val url = j.optJSONArray("data")?.optJSONObject(0)?.optString("download_url") ?: ""
        com.fanxing.xbox.store.AppLog.i("Quark", "个人盘取链 fid=${file.id} → ${if (url.isBlank()) "失败 code=" + j.optInt("code") + " " + j.optString("message") else "OK"}")
        if (url.isBlank()) throw DriveError("夸克：个人盘取直链失败 ${j.optString("message")}")
        return DirectLink(
            url,
            mapOf(
                "User-Agent" to uaQuark,
                "Referer" to "https://pan.quark.cn/",
                "Cookie" to loginCookie
            )
        )
    }
}
