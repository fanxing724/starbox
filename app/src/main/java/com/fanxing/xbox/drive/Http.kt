package com.fanxing.xbox.drive

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 全局 HTTP 客户端。
 *
 * 关键坑：OkHttp 默认 maxRequestsPerHost = 5，分片下载开 32 条也只有 5 条在跑，
 * 其余全在排队 —— 必须显式调大，否则多线程加速完全失效。
 */
object Http {

    const val JSON_TYPE = "application/json; charset=utf-8"

    /** 普通 API 客户端 */
    val api: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** 下载专用：大 Dispatcher + 长超时 + 仅 HTTP/1.1（分片并发更稳） */
    val dl: OkHttpClient by lazy {
        val dispatcher = Dispatcher().apply {
            maxRequests = 1024
            maxRequestsPerHost = 1024
        }
        OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(ConnectionPool(256, 2, TimeUnit.MINUTES))
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .protocols(listOf(Protocol.HTTP_1_1))
            .build()
    }

    fun json(body: String): RequestBody = body.toRequestBody(JSON_TYPE.toMediaType())

    fun form(map: Map<String, String>): RequestBody {
        val b = FormBody.Builder()
        map.forEach { (k, v) -> b.add(k, v) }
        return b.build()
    }
}
