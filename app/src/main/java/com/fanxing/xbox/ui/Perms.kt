package com.fanxing.xbox.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * 权限与保活状态查询 + 跳转。
 *
 * 设计原则：任何一项被拒绝后，都必须能跳到系统设置手动开启，
 * 不能因为「系统不再弹窗」就把用户卡死。
 */
object Perms {

    enum class State { GRANTED, DENIED, NOT_APPLICABLE }

    data class Item(
        val key: String,
        val title: String,
        val why: String,
        val state: State,
        /** 能否在应用内直接申请（否则只能跳系统设置） */
        val requestable: Boolean,
        val permission: String? = null
    )

    fun notificationsEnabled(ctx: Context): Boolean =
        NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    fun ignoringBattery(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return runCatching { pm.isIgnoringBatteryOptimizations(ctx.packageName) }.getOrDefault(false)
    }

    /** 通知渠道是否被用户单独关掉（总开关开着但渠道关了同样收不到） */
    fun channelBlocked(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 26) return false
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
        val ch = nm.getNotificationChannel("xbox_download") ?: return false
        return ch.importance == NotificationManager.IMPORTANCE_NONE
    }

    fun has(ctx: Context, perm: String): Boolean =
        ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    fun items(ctx: Context): List<Item> {
        val out = ArrayList<Item>()

        // 通知：前台服务通知的载体，关了下载进度就看不见
        val notifOk = notificationsEnabled(ctx) && !channelBlocked(ctx)
        out.add(
            Item(
                "notif", "通知权限",
                "显示下载进度与前台服务通知；关闭后后台下载可能被系统回收",
                if (notifOk) State.GRANTED else State.DENIED,
                Build.VERSION.SDK_INT >= 33,
                if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null
            )
        )

        // 电池优化白名单：保活最关键的一项
        out.add(
            Item(
                "battery", "忽略电池优化",
                "加入白名单后，息屏/长时间下载不易被系统冻结（保活关键）",
                if (ignoringBattery(ctx)) State.GRANTED else State.DENIED,
                true
            )
        )

        // 存储：仅 Android 9 及以下需要；10+ 走 MediaStore 免权限
        if (Build.VERSION.SDK_INT <= 28) {
            out.add(
                Item(
                    "storage", "存储权限",
                    "写入公共 Download 目录（Android 10+ 已改用 MediaStore，无需此权限）",
                    if (has(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE)) State.GRANTED else State.DENIED,
                    true, Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        } else {
            out.add(
                Item(
                    "storage", "存储权限",
                    "Android 10+ 通过 MediaStore 写入 Download，无需存储权限",
                    State.NOT_APPLICABLE, false
                )
            )
        }

        // 厂商自启动：无法程序化查询/开启，只能引导
        out.add(
            Item(
                "autostart", "自启动 / 后台运行",
                "部分厂商系统需手动允许自启动与后台高耗电，否则应用被杀后无法继续下载",
                State.NOT_APPLICABLE, false
            )
        )
        return out
    }

    fun readyCount(ctx: Context): Pair<Int, Int> {
        val list = items(ctx).filter { it.state != State.NOT_APPLICABLE }
        return list.count { it.state == State.GRANTED } to list.size
    }

    // ---------------- 跳转 ----------------

    fun appDetails(ctx: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun notificationSettings(ctx: Context): Intent = when {
        Build.VERSION.SDK_INT >= 26 ->
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
        else -> appDetails(ctx)
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun channelSettings(ctx: Context): Intent =
        if (Build.VERSION.SDK_INT >= 26) {
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, "xbox_download")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else notificationSettings(ctx)

    /** 请求加入电池优化白名单；部分机型没有该 Activity，调用方需兜底 */
    fun requestIgnoreBattery(ctx: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun batteryListSettings(ctx: Context): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun start(ctx: Context, intent: Intent): Boolean =
        runCatching { ctx.startActivity(intent) }.isSuccess
}
