package com.fanxing.xbox.store

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 历史解析记录（参考 MoePal 的「历史解析」） */
class History(ctx: Context) {

    private val sp = ctx.applicationContext.getSharedPreferences("xbox_history", Context.MODE_PRIVATE)

    data class Item(val url: String, val drive: String, val title: String, val time: Long)

    fun all(): List<Item> {
        val arr = runCatching { JSONArray(sp.getString("items", "[]")) }.getOrNull() ?: JSONArray()
        val out = ArrayList<Item>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(Item(o.optString("url"), o.optString("drive"), o.optString("title"), o.optLong("time")))
        }
        return out.sortedByDescending { it.time }
    }

    fun add(url: String, drive: String, title: String) {
        val list = all().filter { it.url != url }.toMutableList()
        list.add(0, Item(url, drive, title, System.currentTimeMillis()))
        val arr = JSONArray()
        list.take(100).forEach {
            arr.put(
                JSONObject().put("url", it.url).put("drive", it.drive)
                    .put("title", it.title).put("time", it.time)
            )
        }
        sp.edit().putString("items", arr.toString()).apply()
    }

    fun clear() = sp.edit().remove("items").apply()

    companion object {
        @Volatile private var inst: History? = null
        fun get(ctx: Context): History =
            inst ?: synchronized(this) { inst ?: History(ctx).also { inst = it } }
    }
}
