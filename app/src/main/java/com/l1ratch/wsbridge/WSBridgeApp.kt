package com.l1ratch.wsbridge

import android.app.Application

class WSBridgeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // prefs должны быть готовы даже если процесс воскрес через START_STICKY
        // без MainActivity.
        TunnelManager.init(this)
    }
}
