package com.fanxing.xbox.store

import android.content.Context
import android.content.SharedPreferences

/** 各网盘凭据（Cookie / Token）本地保存 */
class Creds(ctx: Context) {

    private val sp: SharedPreferences =
        ctx.applicationContext.getSharedPreferences("xbox_creds", Context.MODE_PRIVATE)

    fun cookie(drive: String): String = sp.getString("ck_$drive", "") ?: ""
    fun token(drive: String): String = sp.getString("tk_$drive", "") ?: ""

    fun setCookie(drive: String, v: String) = sp.edit().putString("ck_$drive", v).apply()
    fun setToken(drive: String, v: String) = sp.edit().putString("tk_$drive", v).apply()

    fun has(drive: String): Boolean =
        cookie(drive).isNotBlank() || token(drive).isNotBlank()

    fun clear(drive: String) =
        sp.edit().remove("ck_$drive").remove("tk_$drive").apply()

    // ---- 下载参数 ----
    var connections: Int
        get() = sp.getInt("conns", 32)
        set(v) = sp.edit().putInt("conns", v.coerceIn(1, 512)).apply()

    /** 同时下载的任务数 */
    var parallelTasks: Int
        get() = sp.getInt("parallel", 3)
        set(v) = sp.edit().putInt("parallel", v.coerceIn(1, 5)).apply()

    /** 0=跟随系统 1=浅色 2=深色 */
    var themeMode: Int
        get() = sp.getInt("theme", 0)
        set(v) = sp.edit().putInt("theme", v.coerceIn(0, 2)).apply()

    var keepAlive: Boolean
        get() = sp.getBoolean("keep_alive", true)
        set(v) = sp.edit().putBoolean("keep_alive", v).apply()

    /** 已同意的协议版本日期；与当前文本版本不一致时需重新提示 */
    var acceptedLegal: String
        get() = sp.getString("legal_accepted", "") ?: ""
        set(v) = sp.edit().putString("legal_accepted", v).apply()

    var useMirror: Boolean
        get() = sp.getBoolean("mirror", true)
        set(v) = sp.edit().putBoolean("mirror", v).apply()

    var saveDir: String
        get() = sp.getString("save_dir", "") ?: ""
        set(v) = sp.edit().putString("save_dir", v).apply()

    companion object {
        @Volatile private var inst: Creds? = null
        fun get(ctx: Context): Creds =
            inst ?: synchronized(this) { inst ?: Creds(ctx).also { inst = it } }
    }
}

/** 供非 UI 层（下载引擎 / 网盘驱动）读取全局设置与凭据 */
object CredsHolder {
    @Volatile lateinit var creds: Creds
    val connections: Int get() = if (::creds.isInitialized) creds.connections else 32
    val parallelTasks: Int get() = if (::creds.isInitialized) creds.parallelTasks else 3
    val useMirror: Boolean get() = if (::creds.isInitialized) creds.useMirror else true
    fun cookie(id: String): String = if (::creds.isInitialized) creds.cookie(id) else ""
    fun token(id: String): String = if (::creds.isInitialized) creds.token(id) else ""
}
