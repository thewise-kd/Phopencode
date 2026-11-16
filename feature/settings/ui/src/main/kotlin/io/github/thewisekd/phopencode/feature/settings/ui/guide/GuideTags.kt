package io.github.thewisekd.phopencode.feature.settings.ui.guide

import io.github.thewisekd.phopencode.feature.settings.logic.contract.GuidePage

/** Test tags of the usage guide; tests find its parts by these, never by position. */
internal const val GUIDE_PAGER_TAG = "settings:guide:pager"
internal const val GUIDE_INDICATOR_TAG = "settings:guide:indicator"
internal const val GUIDE_BACK_TAG = "settings:guide:back"
internal const val GUIDE_NEXT_TAG = "settings:guide:next"
internal const val GUIDE_SKIP_TAG = "settings:guide:skip"
internal const val GUIDE_START_TAG = "settings:guide:start"

internal fun guidePageTag(page: GuidePage): String = "settings:guide:page:${page.name}"

internal fun guideBodyTag(page: GuidePage): String = "settings:guide:body:${page.name}"
