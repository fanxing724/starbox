package com.fanxing.xbox

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.fanxing.xbox.store.Creds
import com.fanxing.xbox.store.CredsHolder

class App : Application() {

    /** 闪退现场留存：把堆栈写进日志文件，避免崩溃后什么都不留 */
    private fun installCrashLogger() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                com.fanxing.xbox.store.AppLog.init(this@App)
                val st = android.util.Log.getStackTraceString(e)
                com.fanxing.xbox.store.AppLog.e(
                    "Crash", "未捕获异常线程=${t.name} 类型=${e.javaClass.name}: ${e.message}\n$st"
                )
                // 崩溃专档：即使主日志随后被清空或轮转，这份仍在
                com.fanxing.xbox.store.AppLog.dumpCrash(this@App, st)
                // 再往公共 Download 导一份：应用私有目录若也出问题，这份依然拿得到
                runCatching {
                    val body = com.fanxing.xbox.store.AppLog.text()
                    val target = com.fanxing.xbox.store.Storage.create(
                        this@App, "starbox-crash-${System.currentTimeMillis()}.txt"
                    )
                    if (target.uri != null) {
                        contentResolver.openOutputStream(target.uri)?.use {
                            it.write(body.toByteArray())
                        }
                    } else {
                        target.file?.writeText(body)
                    }
                    com.fanxing.xbox.store.Storage.finish(this@App, target)
                }
            }
            // 仍然交给系统处理，保持原有崩溃行为
            prev?.uncaughtException(t, e)
        }
    }

    override fun onCreate() {
        super.onCreate()
        CredsHolder.creds = Creds.get(this)
        com.fanxing.xbox.store.AppLog.init(this)
        installCrashLogger()
        com.fanxing.xbox.store.AppLog.i("App", "starbox 启动")
        com.fanxing.xbox.download.Engine.init(this)
        // 固定浅色，彻底关闭明暗切换（配色由 Palette 覆盖层决定）
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
    }
}
