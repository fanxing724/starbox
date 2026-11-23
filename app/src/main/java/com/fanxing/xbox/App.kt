package com.fanxing.xbox

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.fanxing.xbox.store.Creds
import com.fanxing.xbox.store.CredsHolder

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CredsHolder.creds = Creds.get(this)
        // 固定浅色，彻底关闭明暗切换（配色由 Palette 覆盖层决定）
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
    }
}
