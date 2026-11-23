package com.fanxing.xbox.drive

import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * 天翼云盘（189，分享免登录）。
 *
 * 流程：getShareInfoByCodeV2 → (有访问码时) checkAccessCode 拿 shareId
 *      → listShareDir 列目录 → getFileDownloadUrl(dt=1&shareId=) 取直链
 *
 * 坑：有访问码的分享，后续请求必须带 Cookie `share_<shareId>=<accessCode>`，
 *     否则列表和取链都会失败。
 */
class TianyiDrive : Drive {

    override val id = "tianyi"
    override val name = "天翼云盘"
    override val loginUrl = "https://cloud.189.cn/web/login.html"
    override val desc = "免登录。有访问码的分享需带 share_ 会话 Cookie"
    override val guestParse = true
    override val cookieDomain = "https://cloud.189.cn"

    private val origin = "https://cloud.189.cn"
    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    override fun match(url: String) =
        url.contains("cloud.189.cn") || url.contains("189.cn/t/")

    override fun parse(url: String): ShareRef {
        val code = Regex("189\\.cn/t/([A-Za-z0-9]+)").find(url)?.groupValues?.get(1)
            ?: Regex("[?&]code=([A-Za-z0-9]+)").find(url)?.groupValues?.get(1)
            ?: throw DriveError("天翼：链接里没找到分享编号")
        val access = Regex("[?&]accessCode=([A-Za-z0-9]+)").find(url)?.groupValues?.get(1)
            ?: Regex("(?:访问码|提取码)[:：=]?\\s*([A-Za-z0-9]{1,12})").find(url)?.groupValues?.get(1)
            ?: ""
        return ShareRef(code, access, url)
    }

    private fun shareCookie(share: ShareRef): String {
        val sid = share.extra["shareId"] ?: return ""
        return if (share.passcode.isBlank()) "" else "share_$sid=${share.passcode}"
    }

    private fun api(path: String, params: Map<String, String>, share: ShareRef): JSONObject {
        val q = StringBuilder("$origin/api/$path.action?noCache=${System.nanoTime()}")
        params.forEach { (k, v) ->
            q.append("&").append(k).append("=")
                .append(java.net.URLEncoder.encode(v, "UTF-8"))
        }
        val b = Request.Builder().url(q.toString()).get()
            .header("Accept", "application/json;charset=UTF-8")
            .header("Sign-Type", "1")
            .header("User-Agent", ua)
            .header("Referer", "$origin/web/")
            .header("Origin", origin)
        shareCookie(share).takeIf { it.isNotBlank() }?.let { b.header("Cookie", it) }
        Http.api.newCall(b.build()).execute().use { r ->
            val txt = r.body?.string() ?: throw DriveError("天翼：空响应")
            val j = runCatching { JSONObject(txt) }.getOrElse {
                throw DriveError("天翼：响应非 JSON（HTTP ${r.code}，可能需要登录）")
            }
            val err = j.optString("errorCode").ifBlank { j.optString("res_code") }
            if (err.isNotBlank() && err != "0" && err != "SUCCESS") {
                throw DriveError("天翼：${j.optString("errorMsg").ifBlank { j.optString("res_message") }.ifBlank { err }}")
            }
            return j
        }
    }

    private fun info(share: ShareRef): JSONObject {
        share.extra["info"]?.let { return JSONObject(it) }
        val j = api("open/share/getShareInfoByCodeV2", mapOf("shareCode" to share.key), share)
        if (j.optString("fileId").isBlank()) throw DriveError("天翼：未返回有效分享信息")
        share.extra["info"] = j.toString()
        share.extra["isFolder"] = if (j.optBoolean("isFolder", false)) "1" else "0"
        share.extra["shareMode"] = j.optString("shareMode").ifBlank { "1" }
        j.optString("shareId").takeIf { it.isNotBlank() }?.let { share.extra["shareId"] = it }
        if (share.passcode.isNotBlank() || share.extra["shareId"].isNullOrBlank()) {
            val c = api(
                "open/share/checkAccessCode",
                mapOf("shareCode" to share.key, "accessCode" to share.passcode),
                share
            )
            val sid = c.optString("shareId")
            if (sid.isBlank()) throw DriveError("天翼：访问码错误或已失效")
            share.extra["shareId"] = sid
        }
        return j
    }

    override fun list(share: ShareRef, dir: RemoteFile?): List<RemoteFile> {
        val j = info(share)
        val sid = share.extra["shareId"] ?: throw DriveError("天翼：缺少分享凭证")
        val single = share.extra["isFolder"] != "1"

        if (single && dir == null) {
            return listOf(
                RemoteFile(
                    id = j.optString("fileId"),
                    name = j.optString("fileName").ifBlank { share.key },
                    size = j.optLong("fileSize"),
                    isDir = false,
                    path = j.optString("fileName")
                )
            )
        }

        val params = linkedMapOf(
            "isFolder" to "true",
            "shareId" to sid,
            "shareMode" to (share.extra["shareMode"] ?: "1"),
            "accessCode" to share.passcode,
            "shareDirFileId" to (dir?.id ?: j.optString("fileId")),
            "orderBy" to "1",
            "descending" to "true",
            "pageNum" to "1",
            "pageSize" to "200"
        )
        val res = api("open/share/listShareDir", params, share)
        val ao = res.optJSONObject("fileListAO") ?: JSONObject()
        val out = ArrayList<RemoteFile>()
        val folders = ao.optJSONArray("folderList") ?: JSONArray()
        for (i in 0 until folders.length()) {
            val it = folders.getJSONObject(i)
            out.add(
                RemoteFile(
                    it.optString("id").ifBlank { it.optString("fileId") },
                    it.optString("name").ifBlank { it.optString("fileName") },
                    0, true,
                    it.optString("name").ifBlank { it.optString("fileName") }
                )
            )
        }
        val files = ao.optJSONArray("fileList") ?: JSONArray()
        for (i in 0 until files.length()) {
            val it = files.getJSONObject(i)
            out.add(
                RemoteFile(
                    it.optString("id").ifBlank { it.optString("fileId") },
                    it.optString("name").ifBlank { it.optString("fileName") },
                    it.optLong("size").let { s -> if (s > 0) s else it.optLong("fileSize") },
                    false,
                    it.optString("name").ifBlank { it.optString("fileName") }
                )
            )
        }
        return out
    }

    override fun link(share: ShareRef, file: RemoteFile): DirectLink {
        info(share)
        val sid = share.extra["shareId"] ?: throw DriveError("天翼：缺少分享凭证")
        val j = api(
            "open/file/getFileDownloadUrl",
            mapOf("fileId" to file.id, "dt" to "1", "shareId" to sid),
            share
        )
        val url = j.optString("fileDownloadUrl")
        if (url.isBlank()) throw DriveError("天翼：未返回直链（大文件可能需要登录）")
        return DirectLink(url, mapOf("User-Agent" to ua, "Referer" to "$origin/web/"))
    }
}
