package io.github.thewisekd.phopencode

import android.app.Application
import io.github.thewisekd.phopencode.di.appModules
import io.github.thewisekd.phopencode.security.BouncyCastle
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class PhopencodeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        BouncyCastle.install()
        startKoin {
            androidContext(this@PhopencodeApplication)
            modules(appModules)
        }
    }
}
