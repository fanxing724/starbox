package com.fanxing.xbox

import android.app.Application
import com.fanxing.xbox.store.Creds
import com.fanxing.xbox.store.CredsHolder

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CredsHolder.creds = Creds.get(this)
    }
}
