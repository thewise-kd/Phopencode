package io.github.thewisekd.phopencode.feature.settings.ui.licenses

/** The scrolling list of the licenses screen. */
internal const val LICENSES_LIST_TAG = "licenses:list"

/** The LGPL notice, with its heading. */
internal const val LGPL_NOTICE_TAG = "licenses:lgpl"

/** The EPL notice, with its heading. */
internal const val EPL_NOTICE_TAG = "licenses:epl"

/** The heading over the libraries. */
internal const val LIBRARIES_HEADING_TAG = "licenses:libraries"

internal fun libraryTag(library: Library): String = "licenses:library:${library.coordinate}"

/** The row of a TextMate directory, which opens its license files. */
internal fun textMateTag(notice: TextMateNotice): String = "licenses:textmate:${notice.directory}"

/** The opened license files of a TextMate directory. */
internal fun textMateBodyTag(notice: TextMateNotice): String = "licenses:textmate-text:${notice.directory}"

/** The row of a license, which opens its text. */
internal fun licenseTextTag(license: LicenseText): String = "licenses:license:${license.name}"

/** The opened text of a license. */
internal fun licenseTextBodyTag(license: LicenseText): String = "licenses:license-text:${license.name}"
