package io.github.thewisekd.phopencode

import android.app.Application
import io.github.thewisekd.phopencode.di.appModules
import io.github.thewisekd.phopencode.keepalive.KeepAliveWatcher
import io.github.thewisekd.phopencode.security.BouncyCastle
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class PhopencodeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        BouncyCastle.install()
        val koin = startKoin {
            androidContext(this@PhopencodeApplication)
            modules(appModules)
        }.koin
        // P9.39: from now on an active connection keeps the process alive in the background.
        koin.get<KeepAliveWatcher>().start()
    }
}
