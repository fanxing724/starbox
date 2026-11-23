package com.fanxing.xbox.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import com.fanxing.xbox.R
import com.fanxing.xbox.databinding.ActivityMainBinding
import com.fanxing.xbox.store.Creds

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    private val parse by lazy { ParseFragment() }
    private val tasks by lazy { DownloadsFragment() }
    private val settings by lazy { SettingsFragment() }
    private var current: Fragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.nav.setOnItemSelectedListener { item ->
            val f = when (item.itemId) {
                R.id.nav_tasks -> tasks
                R.id.nav_settings -> settings
                else -> parse
            }
            switchTo(f)
            b.toolbar.title = when (item.itemId) {
                R.id.nav_tasks -> "下载"
                R.id.nav_settings -> "设置"
                else -> "starbox"
            }
            true
        }

        val tab = intent?.getIntExtra("tab", 0) ?: 0
        b.nav.selectedItemId = when (tab) {
            1 -> R.id.nav_tasks
            2 -> R.id.nav_settings
            else -> R.id.nav_parse
        }

        intent?.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
            parse.pendingLink = text
        }

        maybeShowLegalGate()
    }

    /** 首次启动（或协议更新后）必须先同意，否则退出 */
    private fun maybeShowLegalGate() {
        val creds = Creds.get(this)
        if (creds.acceptedLegal == Legal.VERSION) return

        val msg = "欢迎使用 starbox。\n\n" +
            "· 本应用是本地网盘下载工具，不是资源站，不提供任何内容\n" +
            "· 无服务器、无广告、无统计埋点，数据只留在您的设备\n" +
            "· 请遵守各网盘服务条款，仅下载您有权获取的内容\n" +
            "· 使用非官方客户端存在账号被限制的风险，请自行评估\n\n" +
            "点击下方按钮可阅读完整文本。"

        AlertDialog.Builder(this)
            .setTitle("用户协议与隐私政策")
            .setMessage(msg)
            .setCancelable(false)
            .setPositiveButton("同意并继续") { _, _ ->
                creds.acceptedLegal = Legal.VERSION
            }
            .setNegativeButton("不同意并退出") { _, _ ->
                finishAffinity()
            }
            .setNeutralButton("查看协议") { _, _ ->
                startActivity(Intent(this, LegalActivity::class.java).putExtra("kind", "agreement"))
                b.root.postDelayed({ maybeShowLegalGate() }, 400)
            }
            .show()
    }

    private fun switchTo(f: Fragment) {
        if (current === f) return
        val tx = supportFragmentManager.beginTransaction()
        current?.let { tx.hide(it) }
        if (f.isAdded) tx.show(f) else tx.add(R.id.container, f)
        tx.commitNowAllowingStateLoss()
        current = f
    }

    fun goTasks() { b.nav.selectedItemId = R.id.nav_tasks }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
            parse.pendingLink = it
            b.nav.selectedItemId = R.id.nav_parse
            parse.consumePending()
        }
    }

    private fun applyTheme() {
        AppCompatDelegate.setDefaultNightMode(
            when (Creds.get(this).themeMode) {
                1 -> AppCompatDelegate.MODE_NIGHT_NO
                2 -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }
}
