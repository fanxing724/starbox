package com.fanxing.xbox.drive

import com.fanxing.xbox.store.CredsHolder
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

/**
 * 腾讯微云（**只支持浏览自己的网盘**，不支持他人分享链接解析）。
 *
 * 协议移植自 wenxi(文析助手) 的 weiyun provider：
 *   POST https://www.weiyun.com/webapp/json/<protocol>/<name>?g_tk=<wyctoken>&cmd=<cmd>
 *   body = { req_header: "<json字符串>", req_body: "<json字符串>" }
 *   req_body 里再套 ReqMsg_body.ext_req_head.token_info + ".weiyun.<name>MsgReq_body"
 *
 * 登录方式决定 token_info：
 *   QQ 登录  → token_type 0, login_key_type 27,  login_key_value = p_skey
 *   微信开放 → token_type 1, login_key_type 192, login_key_value = access_token
 *   扫码开放 → token_type 3, login_key_type 1540
 */
class WeiyunDrive : Drive {

    override val id = "weiyun"
    override val name = "腾讯微云"
    override val loginUrl = "https://www.weiyun.com/"
    override val cookieDomain = "https://www.weiyun.com"

    private val web = "https://www.weiyun.com"
    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val jar = LinkedHashMap<String, String>()
    private var rootId: String = ""

    init {
        CredsHolder.cookie(id).split(";").forEach {
            val kv = it.trim(); val i = kv.indexOf('=')
            if (i > 0) jar[kv.substring(0, i)] = kv.substring(i + 1)
        }
    }

    override fun match(url: String) =
        url.contains("weiyun.com") || url.startsWith("weiyun://")

    override fun parse(url: String): ShareRef {
        if (url.contains("share.weiyun.com")) {
            throw DriveError(
                "微云不支持解析他人分享链接（官方接口就没开放）。" +
                    "请在设置里登录微云，然后粘贴 https://www.weiyun.com/ 浏览并下载自己的网盘"
            )
        }
        if (jar.isEmpty()) throw DriveError("微云：请先在设置里登录")
        return ShareRef("my", "", url)
    }

    private fun cookieHeader() = jar.entries.joinToString("; ") { "${it.key}=${it.value}" }

    private fun capture(r: Response) {
        r.headers("Set-Cookie").forEach { sc ->
            val kv = sc.substringBefore(';').trim()
            val i = kv.indexOf('=')
            if (i > 0 && kv.substring(i + 1).isNotBlank()) {
                jar[kv.substring(0, i)] = kv.substring(i + 1)
            }
        }
    }

    private fun tokenInfo(): JSONObject {
        val o = JSONObject()
        when {
            jar.containsKey("weiyun_qq_openid") || jar.containsKey("weiyun_wx_openid") -> {
                o.put("token_type", 3).put("login_key_type", 1540)
                jar["weiyun_qq_openid"]?.takeIf { it.isNotBlank() }?.let { o.put("qq_openid", it) }
                jar["weiyun_wx_openid"]?.takeIf { it.isNotBlank() }?.let { o.put("openid", it) }
            }
            jar.containsKey("openid") && jar.containsKey("access_token") -> {
                o.put("token_type", 1)
                    .put("openid", jar["openid"] ?: "")
                    .put("open_appid", jar["wy_appid"] ?: "")
                    .put("access_token", jar["access_token"] ?: "")
                    .put("login_key_type", 192)
                    .put("login_key_value", jar["access_token"] ?: "")
            }
            else -> {
                o.put("token_type", 0)
                    .put("login_key_type", 27)
                    .put("login_key_value", jar["p_skey"] ?: "")
                    .put("openid", "")
            }
        }
        return o
    }

    private fun call(
        name: String,
        cmd: Int,
        body: JSONObject,
        protocol: String = "weiyunQdiskClient"
    ): JSONObject {
        val gtk = jar["wyctoken"] ?: ""
        val url = "$web/webapp/json/$protocol/$name?g_tk=$gtk&cmd=$cmd"

        val info = tokenInfo()
        // p_skey / access_token 可能被服务端刷新
        when (info.optInt("token_type", -1)) {
            0 -> jar["p_skey"]?.let { info.put("login_key_value", it) }
            1 -> jar["access_token"]?.let {
                info.put("access_token", it); info.put("login_key_value", it)
            }
        }

        val reqHeader = JSONObject()
            .put("seq", System.currentTimeMillis() / 1000)
            .put("cmd", cmd)
            .put("wx_openid", info.optString("openid"))
            .put("qq_openid", info.optString("qq_openid"))
            .put("user_flag", info.optInt("token_type"))
            .put("type", 1).put("appid", 30013)
            .put("version", 3).put("major_version", 3)
            .put("minor_version", 3).put("fix_version", 3)

        val extHead = JSONObject()
            .put("token_info", info)
            .put("language_info", JSONObject().put("language_type", 2052))

        val reqBody = JSONObject().put(
            "ReqMsg_body",
            JSONObject()
                .put("ext_req_head", extHead)
                .put(".weiyun.${name}MsgReq_body", body)
        )

        val payload = JSONObject()
            .put("req_header", reqHeader.toString())
            .put("req_body", reqBody.toString())

        val req = Request.Builder().url(url).post(Http.json(payload.toString()))
            .header("User-Agent", ua)
            .header("Referer", "$web/disk")
            .header("Origin", web)
            .header("Content-Type", "application/json; charset=utf-8")
            .header("Cookie", cookieHeader())
            .build()

        Http.api.newCall(req).execute().use { r ->
            capture(r)
            if (r.code == 401 || r.code == 403) {
                throw DriveError("微云：登录已失效，请重新登录")
            }
            val txt = r.body?.string() ?: throw DriveError("微云：空响应")
            val j = runCatching { JSONObject(txt) }.getOrElse {
                throw DriveError("微云：响应非 JSON（HTTP ${r.code}）")
            }
            val head = j.optJSONObject("rsp_header")
            val ret = head?.optInt("retcode", head.optInt("result", 0)) ?: 0
            if (ret != 0) {
                throw DriveError("微云：$name 失败 ret=$ret ${head?.optString("retmsg") ?: ""}")
            }
            val rb = j.optString("rsp_body")
            val bodyJson = if (rb.isNotBlank()) runCatching { JSONObject(rb) }.getOrNull() else j
            val msg = bodyJson?.optJSONObject("ReqMsg_body") ?: bodyJson
            return msg?.optJSONObject(".weiyun.${name}MsgRsp_body")
                ?: msg?.optJSONObject("weiyun.${name}MsgRsp_body")
                ?: msg ?: JSONObject()
        }
    }

    private fun ensureRoot() {
        if (rootId.isNotBlank()) return
        val d = call(
            "DiskUserInfoGet", 2201,
            JSONObject()
                .put("is_get_upload_flow_flag", false)
                .put("is_get_high_speed_flow_info", false)
                .put("is_get_weiyun_flag", false)
                .put("is_get_space_clean_info", false)
                .put("is_get_user_reward_info", false)
        )
        rootId = d.optString("main_dir_key")
        if (rootId.isBlank()) throw DriveError("微云：没拿到根目录，登录可能已失效")
    }

    override fun list(share: ShareRef, dir: RemoteFile?): List<RemoteFile> {
        ensureRoot()
        val parent = dir?.id ?: rootId
        val out = ArrayList<RemoteFile>()
        var start = 0
        var guard = 0
        while (guard++ < 100) {
            val d = call(
                "DiskDirList", 2208,
                JSONObject()
                    .put("dir_key", parent)
                    .put("start", start)
                    .put("count", 500)
                    .put("sort_field", 1)
                    .put("reverse_order", false)
                    .put("get_type", 0)
                    .put("get_abstract_url", false)
                    .put("get_dir_detail_info", false),
                protocol = "weiyunQdisk"
            )
            val dirs = d.optJSONArray("dir_list") ?: JSONArray()
            for (i in 0 until dirs.length()) {
                val it = dirs.getJSONObject(i)
                out.add(
                    RemoteFile(
                        id = it.optString("dir_key"),
                        name = it.optString("dir_name"),
                        size = 0, isDir = true,
                        path = it.optString("dir_name"),
                        meta = mapOf("pdir_key" to parent)
                    )
                )
            }
            val files = d.optJSONArray("file_list") ?: JSONArray()
            for (i in 0 until files.length()) {
                val it = files.getJSONObject(i)
                out.add(
                    RemoteFile(
                        id = it.optString("file_id"),
                        name = it.optString("filename"),
                        size = it.optLong("file_size"),
                        isDir = false,
                        path = it.optString("filename"),
                        meta = mapOf("pdir_key" to parent)
                    )
                )
            }
            if (d.optBoolean("finish_flag", false)) break
            val added = dirs.length() + files.length()
            if (added == 0) break
            start += added
        }
        return out
    }

    override fun link(share: ShareRef, file: RemoteFile): DirectLink {
        val pdir = file.meta["pdir_key"] ?: ""
        if (pdir.isBlank()) throw DriveError("微云：文件缺少父目录信息，请刷新列表")
        val d = call(
            "DiskFileBatchDownload", 2402,
            JSONObject()
                .put(
                    "file_list",
                    JSONArray().put(
                        JSONObject().put("pdir_key", pdir).put("file_id", file.id)
                    )
                )
                .put("download_type", 0)
        )
        val arr = d.optJSONArray("file_list")
        val f = arr?.optJSONObject(0) ?: throw DriveError("微云：未返回下载信息")
        if (f.optInt("retcode", -1) != 0) {
            throw DriveError("微云：取直链失败 ret=${f.optInt("retcode")}")
        }
        val url = f.optString("download_url")
        if (url.isBlank()) throw DriveError("微云：下载地址为空")

        val headers = mutableMapOf(
            "User-Agent" to ua,
            "Referer" to "$web/"
        )
        // 微云直链要带它下发的一次性下载 Cookie
        val cn = f.optString("cookie_name")
        val cv = f.optString("cookie_value")
        if (cn.isNotBlank() && cv.isNotBlank()) headers["Cookie"] = "$cn=$cv"
        return DirectLink(url = url, headers = headers)
    }
}
