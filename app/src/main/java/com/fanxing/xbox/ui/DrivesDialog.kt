package com.fanxing.xbox.ui

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.fanxing.xbox.R
import com.fanxing.xbox.drive.Drive
import com.fanxing.xbox.drive.Registry
import com.fanxing.xbox.store.Creds

/**
 * 支持的网盘列表。顶栏按钮与设置页共用。
 * 按「免登录 / 需登录」分组排序，每组内按名称排列。
 */
object DrivesDialog {

    fun show(activity: Activity) {
        val ctx = activity
        fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
        fun color(id: Int) = ctx.resources.getColor(id, null)

        val creds = Creds.get(ctx)
        val all = Registry.all
        val free = all.filter { it.guestParse }.sortedBy { it.name }
        val need = all.filter { !it.guestParse }.sortedBy { it.name }

        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(8))
        }

        val loggedIn = need.count { creds.has(it.id) }
        box.addView(TextView(ctx).apply {
            text = "共 ${all.size} 家 · ${free.size} 家免登录 · ${need.size} 家需登录（已登录 $loggedIn）"
            textSize = 12f
            setTextColor(color(R.color.text_sub))
            setPadding(0, 0, 0, dp(10))
        })

        fun section(title: String) {
            box.addView(TextView(ctx).apply {
                text = title
                textSize = 12f
                setTextColor(color(R.color.text_sub))
                setPadding(0, dp(12), 0, dp(4))
            })
            box.addView(android.view.View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
                )
                setBackgroundColor(color(R.color.divider))
            })
        }

        fun row(d: Drive) {
            // 两个独立概念，不能混：
            // guestParse = 能否不登录就解析（决定分组与徽标）
            // canLogin   = 是否提供登录（决定能不能点去登录）
            // 夸克/迅雷/PikPak 等属于「可游客解析，但登录才能下大文件」，两者都为真
            val needLogin = !d.guestParse
            val canLogin = d.loginUrl != null
            val logged = creds.has(d.id)

            val line = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(10))
                isClickable = canLogin
                if (canLogin) setOnClickListener {
                    ctx.startActivity(
                        Intent(ctx, LoginActivity::class.java).putExtra("drive", d.id)
                    )
                }
            }

            val nameBox = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
            }
            val titleRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            titleRow.addView(TextView(ctx).apply {
                text = d.name
                textSize = 15f
                setTextColor(color(R.color.text_main))
            })
            titleRow.addView(TextView(ctx).apply {
                text = if (needLogin) "需登录" else "免登录"
                textSize = 10f
                setPadding(dp(6), dp(2), dp(6), dp(2))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(8) }
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(color(if (needLogin) R.color.brand_container else R.color.surface_alt))
                }
                setTextColor(color(if (needLogin) R.color.on_brand_container else R.color.text_sub))
            })
            nameBox.addView(titleRow)
            if (d.desc.isNotBlank()) {
                nameBox.addView(TextView(ctx).apply {
                    text = d.desc
                    textSize = 11f
                    setTextColor(color(R.color.text_sub))
                    setPadding(0, dp(3), 0, 0)
                })
            }
            line.addView(nameBox)

            if (canLogin) {
                line.addView(TextView(ctx).apply {
                    text = if (logged) "已登录 ›" else "去登录 ›"
                    textSize = 12f
                    setTextColor(color(if (logged) R.color.ok else R.color.brand))
                    setPadding(dp(8), dp(4), 0, dp(4))
                })
            }
            box.addView(line)
        }

        section("免登录解析 · 标「去登录」的登录后功能更全")
        free.forEach { row(it) }
        section("需登录解析 · 点整行去登录")
        need.forEach { row(it) }

        AlertDialog.Builder(ctx)
            .setTitle("支持的网盘")
            .setView(ScrollView(ctx).apply { addView(box) })
            .setPositiveButton("知道了", null)
            .show()
    }
}
