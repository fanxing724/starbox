package com.fanxing.xbox.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.fanxing.xbox.R
import com.fanxing.xbox.ui.MainActivity
import com.fanxing.xbox.ui.Fmt

/**
 * 前台服务：把下载挂在通知栏上，防止切后台被系统杀掉。
 * 参考 MoePal 的「后台保活，防止下载被系统终止」。
 */
class DownloadService : Service() {

    private val listener: () -> Unit = { refresh() }
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        Engine.addListener(listener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE_ALL -> Engine.tasks.filter { it.state == State.RUNNING }.forEach { Engine.pause(it.id) }
            ACTION_RESUME_ALL -> Engine.tasks.filter { it.state == State.PAUSED }.forEach { Engine.resume(it.id) }
        }
        startForeground(NOTIF_ID, build())
        refresh()
        return START_STICKY
    }

    private fun refresh() {
        val now = System.currentTimeMillis()
        if (now - lastNotify < 900) return
        lastNotify = now
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val active = Engine.tasks.count { it.state == State.RUNNING || it.state == State.QUEUED }
        if (active == 0 && Engine.tasks.none { it.state == State.PAUSED }) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        nm.notify(NOTIF_ID, build())
    }

    private fun build(): Notification {
        val running = Engine.tasks.filter { it.state == State.RUNNING }
        val queued = Engine.tasks.count { it.state == State.QUEUED }
        val paused = Engine.tasks.count { it.state == State.PAUSED }
        val done = Engine.tasks.count { it.state == State.DONE }

        val title = when {
            running.isNotEmpty() -> "正在下载 ${running.size} 个任务"
            queued > 0 -> "排队中 $queued 个任务"
            paused > 0 -> "已暂停 $paused 个任务"
            else -> "下载完成 $done 个"
        }
        val speed = running.sumOf { it.speed }
        val text = if (running.isNotEmpty()) {
            val t = running.first()
            "${t.name}  ${t.percent}%  ·  ${Fmt.speed(speed)}"
        } else "已完成 $done · 暂停 $paused"

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .putExtra("tab", 1)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (running.isNotEmpty() || queued > 0) {
            b.setProgress(100, running.firstOrNull()?.percent ?: 0, queued > 0 && running.isEmpty())
            b.addAction(0, "全部暂停", action(ACTION_PAUSE_ALL, 1))
        } else if (paused > 0) {
            b.addAction(0, "全部继续", action(ACTION_RESUME_ALL, 2))
        }

        // 多任务时展开显示
        if (Engine.tasks.size > 1) {
            val style = NotificationCompat.InboxStyle()
            Engine.tasks.take(6).forEach {
                style.addLine("${it.name}  ${it.percent}%  ${Fmt.speed(it.speed)}")
            }
            b.setStyle(style)
        }
        return b.build()
    }

    private fun action(a: String, req: Int): PendingIntent =
        PendingIntent.getService(
            this, req, Intent(this, DownloadService::class.java).setAction(a),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "下载", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    override fun onDestroy() {
        Engine.removeListener(listener)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "xbox_download"
        private const val NOTIF_ID = 1001
        const val ACTION_PAUSE_ALL = "xbox.pause_all"
        const val ACTION_RESUME_ALL = "xbox.resume_all"

        fun start(ctx: Context) {
            val i = Intent(ctx, DownloadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }
    }
}
