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
        row("主题颜色", "固定浅色底 · 8 种强调色可选", Palette.nameOf(creds.colorTheme)) {
            pickColor()
        }

        section("网盘")
        val needLogin = Registry.all.filter { it.loginUrl != null }
        val logged = needLogin.count { creds.has(it.id) }
        row("网盘登录", "点击查看全部 ${Registry.all.size} 家网盘与登录状态",
            "$logged/${needLogin.size} 已登录") {
            DrivesDialog.show(requireActivity())
        }

        section("关于")
        row("用户协议", "使用条款与责任范围", "") { openLegal("agreement") }
        row("隐私政策", "无服务器 · 无埋点 · 数据只留本地", "") { openLegal("privacy") }
        row("免责声明", "内容来源与版权责任", "") { openLegal("disclaimer") }
        row("开源组件与许可", "AGPL-3.0 · 运行库 Apache 2.0 · 无 native 库", "") { showLicenses() }
        row("获取源代码", "AGPL-3.0 要求向使用者提供对应源码", "GitHub") { showSource() }
        row("starbox", "版本 0.9.11 · 14 家网盘 · AGPL-3.0", "") {}
    }

    private fun openLegal(kind: String) {
        startActivity(Intent(requireContext(), LegalActivity::class.java).putExtra("kind", kind))
    }

    private fun section(title: String) {
        val tv = TextView(requireContext()).apply {
            text = title
            textSize = 12f
            setTextColor(
                com.google.android.material.color.MaterialColors.getColor(
                    this, com.google.android.material.R.attr.colorPrimary
                )
            )
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

    /** 配色选择：带色块的列表，选中后重建界面生效 */
    private fun pickColor() {
        val creds = Creds.get(requireContext())
        val box = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("主题颜色")
            .setView(box)
            .setNegativeButton("取消", null)
            .create()

        Palette.names.forEachIndexed { i, name ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(12), dp(8), dp(12))
                setBackgroundResource(
                    android.util.TypedValue().let {
                        requireContext().theme.resolveAttribute(
                            androidx.appcompat.R.attr.selectableItemBackground, it, true
                        )
                        it.resourceId
                    }
                )
                setOnClickListener {
                    creds.colorTheme = i
                    dialog.dismiss()
                    activity?.recreate()
                }
            }
            val dot = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(resources.getColor(Palette.swatches[i], null))
                }
            }
            val label = TextView(requireContext()).apply {
                text = name + if (i == creds.colorTheme) "　✓ 当前" else ""
                textSize = 15f
                setTextColor(resources.getColor(com.fanxing.xbox.R.color.text_main, null))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(14) }
            }
            row.addView(dot); row.addView(label)
            box.addView(row)
        }
        dialog.show()
    }

    private fun showSource() {
        val url = "https://github.com/fanxing724/starbox"
        AlertDialog.Builder(requireContext())
            .setTitle("获取源代码")
            .setMessage(
                "本应用以 AGPL-3.0 授权。按照该许可证，向您提供本程序的完整对应源码：\n\n" +
                    "$url\n\n" +
                    "克隆命令：\n" +
                    "git clone $url.git\n\n" +
                    "源码含全部网盘驱动、下载引擎与界面代码，" +
                    "不含签名密钥（需自备 keystore）。"
            )
            .setPositiveButton("打开仓库") { _, _ ->
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                }.onFailure { toast("没有可用的浏览器") }
            }
            .setNeutralButton("复制地址") { _, _ ->
                val cm = requireContext()
                    .getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("starbox", url))
                toast("已复制")
            }
            .setNegativeButton("关闭", null)
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
