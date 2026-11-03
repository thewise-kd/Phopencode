package io.github.thewisekd.phopencode

import android.app.Application
import io.github.thewisekd.phopencode.di.appModules
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class PhopencodeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@PhopencodeApplication)
            modules(appModules)
        }
    }
}
