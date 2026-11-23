package com.fanxing.xbox.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.fanxing.xbox.R
import com.fanxing.xbox.databinding.ActivityMainBinding
import com.fanxing.xbox.store.Creds

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    private lateinit var parse: ParseFragment
    private lateinit var tasks: DownloadsFragment
    private lateinit var settings: SettingsFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Palette.apply(this)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        // ★ 主题切换会重建 Activity，FragmentManager 会自动恢复旧 Fragment。
        //   必须按 tag 复用，不能再 new 一份叠上去（否则旧面板没人 hide，赖在屏幕上）。
        val fm = supportFragmentManager
        parse = fm.findFragmentByTag(TAG_PARSE) as? ParseFragment ?: ParseFragment()
        tasks = fm.findFragmentByTag(TAG_TASKS) as? DownloadsFragment ?: DownloadsFragment()
        settings = fm.findFragmentByTag(TAG_SETTINGS) as? SettingsFragment ?: SettingsFragment()

        b.nav.setOnItemSelectedListener { item ->
            show(item.itemId)
            true
        }

        val tab = savedInstanceState?.getInt(KEY_TAB)
            ?: when (intent?.getIntExtra("tab", 0)) {
                1 -> R.id.nav_tasks
                2 -> R.id.nav_settings
                else -> R.id.nav_parse
            }
        // 不能只设 selectedItemId：若与默认项相同，监听器不会触发，
        // 恢复出来的 Fragment 就一个都没被 hide。这里显式切一次。
        b.nav.selectedItemId = tab
        show(tab)

        intent?.getStringExtra(Intent.EXTRA_TEXT)?.let { parse.pendingLink = it }

        maybeShowLegalGate()
    }

    private fun show(itemId: Int) {
        val target = when (itemId) {
            R.id.nav_tasks -> tasks
            R.id.nav_settings -> settings
            else -> parse
        }
        val tag = when (itemId) {
            R.id.nav_tasks -> TAG_TASKS
            R.id.nav_settings -> TAG_SETTINGS
            else -> TAG_PARSE
        }
        b.toolbar.title = when (itemId) {
            R.id.nav_tasks -> "下载"
            R.id.nav_settings -> "设置"
            else -> "starbox"
        }

        val tx = supportFragmentManager.beginTransaction()
        // 把所有非目标面板都藏掉，包含主题切换后被系统恢复出来的那些
        listOf(parse, tasks, settings).forEach { f ->
            if (f !== target && f.isAdded) tx.hide(f)
        }
        if (target.isAdded) tx.show(target) else tx.add(R.id.container, target, tag)
        tx.commitNowAllowingStateLoss()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, b.nav.selectedItemId)
    }

    fun goTasks() {
        b.nav.selectedItemId = R.id.nav_tasks
        show(R.id.nav_tasks)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
            parse.pendingLink = it
            b.nav.selectedItemId = R.id.nav_parse
            show(R.id.nav_parse)
            parse.consumePending()
        }
        intent.getIntExtra("tab", -1).takeIf { it >= 0 }?.let {
            val id = when (it) {
                1 -> R.id.nav_tasks
                2 -> R.id.nav_settings
                else -> R.id.nav_parse
            }
            b.nav.selectedItemId = id
            show(id)
        }
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
            .setPositiveButton("同意并继续") { _, _ -> creds.acceptedLegal = Legal.VERSION }
            .setNegativeButton("不同意并退出") { _, _ -> finishAffinity() }
            .setNeutralButton("查看协议") { _, _ ->
                startActivity(Intent(this, LegalActivity::class.java).putExtra("kind", "agreement"))
                b.root.postDelayed({ maybeShowLegalGate() }, 400)
            }
            .show()
    }

    companion object {
        private const val TAG_PARSE = "tab_parse"
        private const val TAG_TASKS = "tab_tasks"
        private const val TAG_SETTINGS = "tab_settings"
        private const val KEY_TAB = "selected_tab"
    }
}
