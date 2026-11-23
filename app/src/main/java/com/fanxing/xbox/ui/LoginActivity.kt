package com.fanxing.xbox.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.fanxing.xbox.databinding.ActivityLoginBinding
import com.fanxing.xbox.drive.Registry
import com.fanxing.xbox.store.Creds

class LoginActivity : AppCompatActivity() {

    private lateinit var b: ActivityLoginBinding

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Palette.apply(this)
        b = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(b.root)

        val driveId = intent.getStringExtra("drive") ?: ""
        val drive = Registry.byId(driveId)
        val loginUrl = drive?.loginUrl
        if (drive == null || loginUrl == null) {
            Toast.makeText(this, "该网盘无需登录", Toast.LENGTH_SHORT).show()
            finish(); return
        }

        b.title.text = "登录 · ${drive.name}"
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)

        b.web.settings.javaScriptEnabled = true
        b.web.settings.domStorageEnabled = true
        b.web.settings.databaseEnabled = true
        b.web.settings.javaScriptCanOpenWindowsAutomatically = true
        b.web.settings.setSupportMultipleWindows(false)

        // ★ 必须用「电脑版」UA：手机 UA 会被网盘踢到 App 下载页 / 移动端登录，
        //   网页登录走不通，也抓不到完整的 Cookie。
        b.web.settings.userAgentString = DESKTOP_UA

        // 电脑页面在手机上要能缩放看全
        b.web.settings.useWideViewPort = true
        b.web.settings.loadWithOverviewMode = true
        b.web.settings.setSupportZoom(true)
        b.web.settings.builtInZoomControls = true
        b.web.settings.displayZoomControls = false
        b.web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                cm.flush()
            }
        }
        b.web.loadUrl(loginUrl)

        b.btnDone.setOnClickListener {
            val url = b.web.url ?: loginUrl
            val ck = cm.getCookie(url) ?: ""
            if (ck.isBlank()) {
                Toast.makeText(this, "还没抓到 Cookie，请先完成登录", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            Creds.get(this).setCookie(drive.id, ck)
            // 各家的 token 存放位置不同，按实际键名取
            b.web.evaluateJavascript(TOKEN_JS) { v ->
                val raw = (v ?: "").let {
                    if (it.startsWith("\"") && it.endsWith("\""))
                        it.substring(1, it.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
                    else it
                }
                runCatching {
                    val o = org.json.JSONObject(raw)
                    o.optString(drive.id).takeIf { it.isNotBlank() }
                        ?.let { Creds.get(this).setToken(drive.id, it) }
                }
            }
            Toast.makeText(this, "已保存 ${drive.name} 登录凭据", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onDestroy() {
        b.web.destroy()
        super.onDestroy()
    }

    companion object {
        /** 登录一律用电脑版 UA，手机 UA 会导致网页登录不可用 */
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        /**
         * 各家 token 的实际存放位置：
         *  - 迅雷：localStorage['credentials_Xqp0kJBXWhwaTpB6']（多账号时带 @sub 后缀）
         *  - 阿里：localStorage['token'] 是个 JSON，里面才有 access_token
         *  - 百度/微云：靠 Cookie，不需要 token
         */
        val TOKEN_JS = """
            (function(){
              try{
                var out={};
                var sub=localStorage.getItem('current_sub');
                var k='credentials_Xqp0kJBXWhwaTpB6';
                var raw=sub?localStorage.getItem(k+'@'+sub):null;
                if(!raw) raw=localStorage.getItem(k);
                if(raw){var d=JSON.parse(raw); if(d&&d.access_token) out.xunlei=d.access_token;}
                var t=localStorage.getItem('token');
                if(t){try{var a=JSON.parse(t); if(a&&a.access_token) out.aliyun=a.access_token;}catch(e2){}}
                return JSON.stringify(out);
              }catch(e){return '{}';}
            })()
        """.trimIndent()
    }
}
