package io.github.thewisekd.phopencode.feature.settings.ui.prefs

import io.github.thewisekd.phopencode.core.storage_api.AppLanguage
import io.github.thewisekd.phopencode.core.storage_api.ThemePreference

/** Test tags of the preferences screen; tests find its parts by these, never by position. */
internal const val PREFS_LIST_TAG = "settings:prefs:list"
internal const val PREFS_LOADING_TAG = "settings:prefs:loading"
internal const val LANGUAGE_GROUP_TAG = "settings:prefs:language"
internal const val THEME_GROUP_TAG = "settings:prefs:theme"
internal const val FILE_SIZE_FIELD_TAG = "settings:prefs:file-size"
internal const val LICENSES_TAG = "settings:prefs:licenses"
internal const val DELETE_BUTTON_TAG = "settings:prefs:delete"
internal const val DELETING_TAG = "settings:prefs:deleting"
internal const val DELETE_DIALOG_TAG = "settings:prefs:delete-dialog"

internal fun languageOptionTag(language: AppLanguage): String = "settings:prefs:language:${language.name}"

internal fun themeOptionTag(theme: ThemePreference): String = "settings:prefs:theme:${theme.name}"
