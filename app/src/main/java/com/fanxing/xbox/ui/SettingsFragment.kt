package com.fanxing.xbox.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import com.fanxing.xbox.databinding.FragmentSettingsBinding
import com.fanxing.xbox.databinding.ItemSettingBinding
import com.fanxing.xbox.drive.Registry
import com.fanxing.xbox.store.Creds

class SettingsFragment : Fragment() {

    private var _b: FragmentSettingsBinding? = null
    private val b get() = _b!!

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentSettingsBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(v: View, s: Bundle?) { build() }

    override fun onResume() { super.onResume(); if (_b != null) build() }

    private fun build() {
        val creds = Creds.get(requireContext())
        b.settingsList.removeAllViews()

        section("下载")
        row("线程数（连接数）", "1–512，越多不一定越快", creds.connections.toString()) {
            askNumber("线程数（1–512）", creds.connections) { n ->
                if (n in 1..512) { creds.connections = n; build() } else toast("需在 1-512 之间")
            }
        }
        row("同时下载任务数", "1–5", creds.parallelTasks.toString()) {
            askNumber("同时下载任务数（1–5）", creds.parallelTasks) { n ->
                if (n in 1..5) { creds.parallelTasks = n; build() } else toast("需在 1-5 之间")
            }
        }
        switchRow("多节点镜像", "有镜像的网盘跨 CDN 节点并发", creds.useMirror) {
            creds.useMirror = it
        }
        switchRow("后台保活", "前台服务 + 通知栏，防止被系统杀", creds.keepAlive) {
            creds.keepAlive = it
        }

        section("外观")
        row("主题", "跟随系统 / 浅色 / 深色",
            arrayOf("跟随系统", "浅色", "深色")[creds.themeMode]) {
            AlertDialog.Builder(requireContext())
                .setTitle("主题")
                .setItems(arrayOf("跟随系统", "浅色", "深色")) { _, i ->
                    creds.themeMode = i
                    AppCompatDelegate.setDefaultNightMode(
                        when (i) {
                            1 -> AppCompatDelegate.MODE_NIGHT_NO
                            2 -> AppCompatDelegate.MODE_NIGHT_YES
                            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                        }
                    )
                }.show()
        }

        section("网盘登录")
        Registry.all.forEach { d ->
            val state = when {
                d.loginUrl == null -> "免登录"
                creds.has(d.id) -> "已登录"
                else -> "未登录"
            }
            row(d.name, if (d.loginUrl == null) "游客可直接解析下载" else "点击打开网页登录", state) {
                if (d.loginUrl == null) toast("${d.name} 无需登录")
                else startActivity(Intent(requireContext(), LoginActivity::class.java).putExtra("drive", d.id))
            }
        }

        section("关于")
        row("用户协议", "使用条款与责任范围", "") { openLegal("agreement") }
        row("隐私政策", "无服务器 · 无埋点 · 数据只留本地", "") { openLegal("privacy") }
        row("免责声明", "内容来源与版权责任", "") { openLegal("disclaimer") }
        row("开源组件与许可", "AGPL-3.0 · 运行库 Apache 2.0 · 无 native 库", "") { showLicenses() }
        row("starbox", "版本 0.9.1 · 14 家网盘 · AGPL-3.0", "") {}
    }

    private fun openLegal(kind: String) {
        startActivity(Intent(requireContext(), LegalActivity::class.java).putExtra("kind", kind))
    }

    private fun section(title: String) {
        val tv = TextView(requireContext()).apply {
            text = title
            textSize = 12f
            setTextColor(resources.getColor(com.fanxing.xbox.R.color.brand, null))
            setPadding(dp(6), dp(16), dp(6), dp(6))
        }
        b.settingsList.addView(tv)
        val card = com.google.android.material.card.MaterialCardView(requireContext()).apply {
            radius = dp(18).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(resources.getColor(com.fanxing.xbox.R.color.surface, null))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val inner = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        card.addView(inner)
        b.settingsList.addView(card)
        currentCard = inner
    }

    private var currentCard: LinearLayout? = null

    private fun row(title: String, sub: String, value: String, onClick: () -> Unit) {
        val ib = ItemSettingBinding.inflate(layoutInflater, currentCard, false)
        ib.title.text = title
        ib.subtitle.text = sub
        ib.value.text = value
        ib.root.setOnClickListener { onClick() }
        currentCard?.addView(ib.root)
    }

    private fun switchRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        val ib = ItemSettingBinding.inflate(layoutInflater, currentCard, false)
        ib.title.text = title
        ib.subtitle.text = sub
        ib.toggle.visibility = View.VISIBLE
        ib.toggle.isChecked = checked
        ib.toggle.setOnCheckedChangeListener { _, v -> onChange(v) }
        ib.root.setOnClickListener { ib.toggle.isChecked = !ib.toggle.isChecked }
        currentCard?.addView(ib.root)
    }

    private fun askNumber(title: String, current: Int, onOk: (Int) -> Unit) {
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(current.toString())
            setSelection(text.length)
        }
        val box = LinearLayout(requireContext()).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(box)
            .setPositiveButton("确定") { _, _ ->
                input.text.toString().toIntOrNull()?.let(onOk) ?: toast("请输入数字")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showLicenses() {
        AlertDialog.Builder(requireContext())
            .setTitle("开源组件与许可")
            .setMessage(
                """
                starbox 全部使用开源组件，无闭源依赖，无 native 二进制库。
                解析与下载全部在设备本地完成，不经过任何第三方服务器。

                【本应用许可证】
                AGPL-3.0
                各网盘驱动移植自下列 AGPL-3.0 项目，作为衍生作品，
                本应用整体同样以 AGPL-3.0 授权。

                【移植来源 · 均 AGPL-3.0】
                YunX-Desktop（Kotlin）— UC/夸克/百度/迅雷/123/蓝奏/115/光鸭/139
                wenxi 文析助手（Dart）— 微云/天翼/PikPak
                LinkSwift（JS）— 百度 UA 规则、迅雷镜像节点
                感谢上述项目作者。仅移植接口实现，未使用其二进制或专有代码。

                【运行库 · 均 Apache 2.0】
                Kotlin / kotlinx.coroutines
                AndroidX（core / appcompat / fragment / recyclerview / lifecycle）
                Material Components
                OkHttp + Okio（Square）
                Media3 / ExoPlayer（视频播放）
                Guava / errorprone annotations

                【零依赖部分】
                下载引擎（分片并发 / 断点续传 / 多节点镜像）— 自写
                PDF 预览 — 系统 PdfRenderer

                【构建链】
                OpenJDK 21（GPLv2 + CE）· Gradle 8.7 · Android SDK / AGP
                """.trimIndent()
            )
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroyView() { _b = null; super.onDestroyView() }

    private fun toast(s: String) =
        Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show()
}
