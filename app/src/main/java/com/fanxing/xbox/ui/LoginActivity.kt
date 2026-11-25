package com.fanxing.xbox.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
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
        b.web.settings.textZoom = 100

        // ★ 拦掉「下载客户端」：官网/登录页常挂 APK 直链或应用商店跳转，
        //   点一下就触发下载。这里一律拦下，只保留网页导航。
        b.web.setDownloadListener { _, _, _, _, _ ->
            Toast.makeText(this, "已拦截客户端下载，请在网页内完成登录", Toast.LENGTH_SHORT).show()
        }

        b.web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url ?: return false
                val scheme = u.scheme?.lowercase() ?: ""
                // 非 http(s)（intent:// market:// 等）会拉起应用商店/客户端，直接拦掉
                if (scheme != "http" && scheme != "https") {
                    Toast.makeText(this@LoginActivity, "已拦截外部跳转", Toast.LENGTH_SHORT).show()
                    return true
                }
                val path = u.path?.lowercase() ?: ""
                if (path.endsWith(".apk") || path.endsWith(".exe") || path.endsWith(".dmg")) {
                    Toast.makeText(this@LoginActivity, "已拦截安装包下载", Toast.LENGTH_SHORT).show()
                    return true
                }
                return false   // 其余留在 WebView 内，避免跳系统浏览器丢 Cookie
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                cm.flush()
                // ★ 强制桌面宽度视口：部分登录页带 device-width 的 viewport，
                //   在窄屏上会把桌面布局裁掉一半。改成固定宽度后配合 overview 缩放即可显示完整。
                view?.evaluateJavascript(VIEWPORT_JS, null)
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
        // WebView 会把重 SPA 的资源长期堆在 cacheDir（实测可达几十上百 MB），
        // 关闭登录页时清掉；凭据在 CookieManager 里，不受影响。
        runCatching {
            b.web.clearCache(true)      // 只清 HTTP 缓存
            b.web.clearFormData()       // 不动 Cookie / LocalStorage，登录态保住
        }
        b.web.destroy()
        super.onDestroy()
    }

    companion object {
        /** 强制桌面宽度视口，避免登录页在窄屏被裁切 */
        val VIEWPORT_JS = """
            (function(){
              try{
                var m=document.querySelector('meta[name=viewport]');
                if(!m){m=document.createElement('meta');m.setAttribute('name','viewport');
                       (document.head||document.documentElement).appendChild(m);}
                m.setAttribute('content','width=1280, user-scalable=yes');
              }catch(e){}
            })()
        """.trimIndent()

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
