package com.nuliz.ads

import android.app.Application

class NulizApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Stats.init(this)
        ChatStore.init()
        Notifier.createChannels(this)
    }
}
