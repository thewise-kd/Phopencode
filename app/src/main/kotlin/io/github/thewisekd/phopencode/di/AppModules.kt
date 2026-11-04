package io.github.thewisekd.phopencode.di

import io.github.thewisekd.phopencode.feature.catalog.ui.catalogModule
import io.github.thewisekd.phopencode.feature.chat.ui.chatModule
import io.github.thewisekd.phopencode.feature.connect.ui.connectModule
import io.github.thewisekd.phopencode.feature.files.ui.filesModule
import io.github.thewisekd.phopencode.feature.git.ui.gitModule
import io.github.thewisekd.phopencode.feature.notify.ui.notifyModule
import io.github.thewisekd.phopencode.feature.sessions.ui.sessionsModule
import io.github.thewisekd.phopencode.feature.settings.ui.settingsModule
import org.koin.core.module.Module

/** One Koin module per feature. */
val featureModules: List<Module> = listOf(
    connectModule,
    sessionsModule,
    chatModule,
    catalogModule,
    filesModule,
    gitModule,
    notifyModule,
    settingsModule,
)

/** The Koin modules the app starts with: the core objects, then the features that use them. */
val appModules: List<Module> = coreModules + featureModules
