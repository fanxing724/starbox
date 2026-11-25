package com.fanxing.xbox.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.fanxing.xbox.databinding.ActivityLogsBinding
import com.fanxing.xbox.store.AppLog
import com.fanxing.xbox.store.Storage

class LogsActivity : AppCompatActivity() {

    private lateinit var b: ActivityLogsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Palette.apply(this)
        b = ActivityLogsBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.toolbar.setNavigationOnClickListener { finish() }

        // 级别过滤：全部 / 仅警告以上 / 仅错误
        val levels = arrayOf("全部", "警告以上", "仅错误")
        b.levelFilter.adapter = android.widget.ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, levels
        )
        b.levelFilter.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) = render()
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        b.keyword.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b1: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b1: Int, c: Int) = render()
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        b.btnRefresh.setOnClickListener { render() }
        b.btnCopy.setOnClickListener {
            val t = AppLog.text()
            if (t.isBlank()) { toast("暂无日志"); return@setOnClickListener }
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("starbox-log", t))
            toast("已复制 ${AppLog.entries().size} 条")
        }
        b.btnExport.setOnClickListener { export() }
        b.btnClear.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("清空日志")
                .setMessage("会同时清空内存与磁盘日志，确定？")
                .setPositiveButton("清空") { _, _ -> AppLog.clear(); render(); toast("已清空") }
                .setNegativeButton("取消", null)
                .show()
        }
        render()
    }

    private fun render() {
        // 渲染失败不能把整个页面带崩，否则恰好丢掉要看的现场
        runCatching {
            var list = AppLog.entries()
            // 级别过滤
            when (b.levelFilter.selectedItemPosition) {
                1 -> list = list.filter { it.level == com.fanxing.xbox.store.AppLog.Level.W || it.level == com.fanxing.xbox.store.AppLog.Level.E }
                2 -> list = list.filter { it.level == com.fanxing.xbox.store.AppLog.Level.E }
            }
            // 关键字过滤（标签或内容命中，大小写不敏感）
            val kw = b.keyword.text?.toString()?.trim().orEmpty()
            if (kw.isNotEmpty()) {
                list = list.filter { it.tag.contains(kw, true) || it.msg.contains(kw, true) }
            }
            val shown = list.size
            val total = AppLog.entries().size
            b.toolbar.subtitle = "显示 $shown / 共 $total 条"
            if (list.isEmpty()) {
                b.content.text = if (total == 0) "（暂无日志）" else "（当前过滤条件下无匹配）"
            } else {
                val body = list.joinToString("\n") {
                    "${com.fanxing.xbox.store.AppLog.formatTime(it.time)} ${it.level.name}/${it.tag}: ${it.msg}"
                }
                b.content.text = if (body.length > 60_000)
                    body.take(40_000) + "\n……（中间省略，请用「导出」取完整内容）\n" + body.takeLast(20_000)
                else body
            }
        // 崩后重开时明确告知：这里含上一次运行/崩溃的现场
        b.hint.text = when {
            AppLog.hasPreviousLog ->
                "⚠ 本列表已包含上一次运行的日志（含崩溃现场），未复制前请勿清空。"
            else -> "日志已脱敏：不含 Cookie / Token / 签名参数，可放心分享。\n导出位置：Download/starbox/log/"
        }
        b.toolbar.subtitle = "${list.size} 条"
            b.scroll.post { b.scroll.fullScroll(android.view.View.FOCUS_DOWN) }
        }.onFailure {
            com.fanxing.xbox.store.AppLog.e("Logs", "渲染日志失败：${it.message}")
            runCatching { b.content.text = "（渲染失败，请点「导出」取完整文件）" }
        }
    }

    /** 导出到系统 Download，方便用文件管理器发给别人 */
    private fun export() {
        val t = AppLog.text()
        if (t.isBlank()) { toast("暂无日志"); return }
        runCatching {
            val target = Storage.createLog(this, "starbox-log-${System.currentTimeMillis()}.txt")
            if (target.uri != null) {
                contentResolver.openOutputStream(target.uri)?.use { it.write(t.toByteArray()) }
            } else {
                target.file?.writeText(t)
            }
            Storage.finish(this, target)
            toast("已导出到 Download/starbox/log/${target.displayName}")
        }.onFailure { toast("导出失败：${it.message}") }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
