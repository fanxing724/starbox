package com.fanxing.xbox.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.fanxing.xbox.R
import com.fanxing.xbox.databinding.ActivityPermsBinding
import com.fanxing.xbox.databinding.ItemSettingBinding

/**
 * 权限管理页。每项都能看状态、点一下申请或跳系统设置。
 * 关键：被永久拒绝（系统不再弹窗）时，自动改为跳应用详情页，不把用户卡死。
 */
class PermsActivity : AppCompatActivity() {

    private lateinit var b: ActivityPermsBinding
    private var pendingKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Palette.apply(this)
        b = ActivityPermsBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.toolbar.setNavigationOnClickListener { finish() }

        b.btnAppDetails.setOnClickListener {
            if (!Perms.start(this, Perms.appDetails(this))) toast("无法打开系统设置")
        }
        b.btnBatteryList.setOnClickListener {
            if (!Perms.start(this, Perms.batteryListSettings(this))) {
                Perms.start(this, Perms.appDetails(this))
            }
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        render()   // 从系统设置回来要刷新真实状态
    }

    private fun render() {
        val (ok, total) = Perms.readyCount(this)
        b.summary.text = if (ok == total) "保活权限已就绪（$ok/$total）"
        else "保活权限 $ok/$total 已就绪"

        b.rows.removeAllViews()
        Perms.items(this).forEach { item ->
            val row = ItemSettingBinding.inflate(layoutInflater, b.rows, false)
            row.title.text = item.title
            row.subtitle.text = item.why
            row.value.text = when (item.state) {
                Perms.State.GRANTED -> "已允许"
                Perms.State.DENIED -> "去开启 ›"
                Perms.State.NOT_APPLICABLE -> if (item.key == "autostart") "需手动 ›" else "无需"
            }
            row.value.setTextColor(
                resources.getColor(
                    when (item.state) {
                        Perms.State.GRANTED -> R.color.ok
                        Perms.State.DENIED -> R.color.err
                        Perms.State.NOT_APPLICABLE -> R.color.text_sub
                    }, null
                )
            )
            row.root.setOnClickListener { act(item) }
            b.rows.addView(row.root)
        }
    }

    private fun act(item: Perms.Item) {
        when (item.key) {
            "notif" -> {
                if (item.state == Perms.State.GRANTED) {
                    toast("通知已允许")
                    return
                }
                // 渠道被单独关掉时，申请权限没用，得去渠道设置
                if (Perms.channelBlocked(this)) {
                    if (!Perms.start(this, Perms.channelSettings(this))) {
                        Perms.start(this, Perms.notificationSettings(this))
                    }
                    return
                }
                val perm = item.permission
                if (perm != null && !Perms.has(this, perm)) {
                    // 系统已不再弹窗（永久拒绝）→ 直接跳设置
                    val asked = getSharedPreferences("xbox_perms", MODE_PRIVATE)
                        .getBoolean("asked_$perm", false)
                    val canAsk = !asked ||
                        ActivityCompat.shouldShowRequestPermissionRationale(this, perm)
                    if (canAsk) {
                        pendingKey = item.key
                        getSharedPreferences("xbox_perms", MODE_PRIVATE)
                            .edit().putBoolean("asked_$perm", true).apply()
                        ActivityCompat.requestPermissions(this, arrayOf(perm), REQ)
                        return
                    }
                }
                if (!Perms.start(this, Perms.notificationSettings(this))) {
                    Perms.start(this, Perms.appDetails(this))
                }
            }

            "battery" -> {
                if (item.state == Perms.State.GRANTED) { toast("已在电池优化白名单"); return }
                // 部分机型没有该 Activity，失败就退到电池优化列表 / 应用详情
                if (!Perms.start(this, Perms.requestIgnoreBattery(this))) {
                    if (!Perms.start(this, Perms.batteryListSettings(this))) {
                        Perms.start(this, Perms.appDetails(this))
                    }
                }
            }

            "storage" -> {
                if (item.state == Perms.State.NOT_APPLICABLE) {
                    toast("Android 10+ 无需存储权限"); return
                }
                val perm = item.permission ?: return
                if (Perms.has(this, perm)) { toast("存储权限已允许"); return }
                val asked = getSharedPreferences("xbox_perms", MODE_PRIVATE)
                    .getBoolean("asked_$perm", false)
                if (!asked || ActivityCompat.shouldShowRequestPermissionRationale(this, perm)) {
                    pendingKey = item.key
                    getSharedPreferences("xbox_perms", MODE_PRIVATE)
                        .edit().putBoolean("asked_$perm", true).apply()
                    ActivityCompat.requestPermissions(this, arrayOf(perm), REQ)
                } else {
                    Perms.start(this, Perms.appDetails(this))
                }
            }

            "autostart" -> {
                AlertDialog.Builder(this)
                    .setTitle("自启动 / 后台运行")
                    .setMessage(
                        "这一项由厂商系统控制，应用无法代为开启，也读不到真实状态。\n\n" +
                            "请在系统设置里找到本应用，允许：\n" +
                            "· 自启动 / 关联启动\n" +
                            "· 后台运行 / 后台高耗电\n" +
                            "· 锁定任务卡片（部分机型）\n\n" +
                            "常见位置：\n" +
                            "小米 → 安全中心 · 应用管理 · 权限\n" +
                            "华为 → 应用启动管理\n" +
                            "OPPO / vivo → 电池 · 后台耗电管理\n\n" +
                            "点下面按钮打开本应用的系统详情页。"
                    )
                    .setPositiveButton("打开设置") { _, _ ->
                        Perms.start(this, Perms.appDetails(this))
                    }
                    .setNegativeButton("知道了", null)
                    .show()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ) return
        val granted = grantResults.isNotEmpty() &&
            grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            // 被拒后给出明确出路，而不是再弹一次没用的窗
            AlertDialog.Builder(this)
                .setTitle("权限被拒绝")
                .setMessage("没有该权限，后台下载可能被系统中断。\n\n可以现在去系统设置手动开启。")
                .setPositiveButton("去设置") { _, _ ->
                    val target = if (pendingKey == "notif") Perms.notificationSettings(this)
                    else Perms.appDetails(this)
                    if (!Perms.start(this, target)) Perms.start(this, Perms.appDetails(this))
                }
                .setNegativeButton("暂不", null)
                .show()
        }
        render()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object { private const val REQ = 200 }
}
