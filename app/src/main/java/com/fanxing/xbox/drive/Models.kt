package com.fanxing.xbox.drive

/** 一个分享链接的解析结果 */
data class ShareRef(
    val key: String,
    val passcode: String = "",
    val raw: String = "",
    /** 驱动内部缓存：stoken / sekey / uk / shareId 等 */
    val extra: MutableMap<String, String> = mutableMapOf()
)

/** 分享里的一个文件或目录 */
data class RemoteFile(
    val id: String,
    val name: String,
    val size: Long,
    val isDir: Boolean,
    var path: String = "",
    val meta: Map<String, String> = emptyMap()
)

/**
 * 取到的直链。
 * mirrors：可替换 host 的镜像节点域名（迅雷那套多节点并发）。
 */
data class DirectLink(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val mirrors: List<String> = emptyList()
)

/** 网盘驱动统一接口 */
interface Drive {
    val id: String
    val name: String

    /** 登录页地址；null 表示游客可用，无需登录 */
    val loginUrl: String?

    /** 该登录页下需要抓取的凭据域名 */
    val cookieDomain: String get() = ""

    /** 该网盘的特点说明（展示在「支持列表」里） */
    val desc: String get() = ""

    fun match(url: String): Boolean
    fun parse(url: String): ShareRef

    /** 列目录；dir 为 null 表示根目录 */
    fun list(share: ShareRef, dir: RemoteFile?): List<RemoteFile>

    fun link(share: ShareRef, file: RemoteFile): DirectLink

    /** 是否支持「转存到自己网盘再下载」 */
    val supportsTransfer: Boolean get() = false

    /**
     * 把分享文件转存到自己网盘，返回**个人网盘里**的文件对象。
     * 用于游客直下被拒（大文件/需登录）时的兜底路径。
     */
    fun transfer(share: ShareRef, files: List<RemoteFile>): List<RemoteFile> =
        throw DriveError("$name 暂不支持转存")
}

class DriveError(message: String) : Exception(message)

/**
 * 把直链展开成「原节点 + 多个镜像节点」。
 * 只替换 host，不动路径与查询串 —— 查询串一改签名就废了。
 */
fun expandMirrors(url: String, mirrors: List<String>, limit: Int = 8): List<String> {
    if (mirrors.isEmpty()) return listOf(url)
    val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return listOf(url)
    val host = uri.host
    if (host.isNullOrBlank()) return listOf(url)
    // 只重建 authority：String.replace 会连 path/query 里出现的同名串一起换掉，
    // 而签名 URL 常把 host 回写进参数，一改签名就废。
    return (listOf(url) + mirrors.take(limit).map { node ->
        runCatching {
            java.net.URI(
                uri.scheme, uri.userInfo, node, uri.port,
                uri.path, uri.query, uri.fragment
            ).toString()
        }.getOrDefault(url)
    }).distinct()
}
