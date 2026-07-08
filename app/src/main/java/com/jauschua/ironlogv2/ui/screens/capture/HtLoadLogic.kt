// HtLoadLogic.kt
package com.jauschua.ironlogv2.ui.screens.capture

// ── Task 6: HT band-composite — pure helpers ─────────────────────────────────────────────
//
// Extracted out of CaptureScreen.kt so both CaptureScreen's [SetCard] (working-set capture) and
// TodayScreen's read-only preview can render the identical HT setup line without duplicating the
// formatting logic. Stays in the `capture` package (same-package visibility keeps CaptureScreen's
// existing call sites and CaptureScreenLogicTest's imports unchanged); TodayScreen imports
// [htSetupLine] explicitly since it lives in a different package.

/** Display names in server band-ordinal order (#0 Orange, #1 Red, …). Server BandPair.id is
 *  1-based (Orange=1), so map an id to a name via `id - 1`. */
private val BAND_NAMES = listOf("Orange", "Red", "Blue", "Green", "Black", "Purple")

/**
 * Maps band ids in [config] to their display names, in order, via [BAND_NAMES]. [config] holds
 * server BandPair ids (1-based: Orange=1, Red=2, …), so index [BAND_NAMES] at `id - 1`.
 * Out-of-range/non-positive ids are dropped defensively rather than crashing. Null or empty
 * [config] yields an empty list.
 */
internal fun bandNames(config: List<Int>?): List<String> =
    config?.mapNotNull { BAND_NAMES.getOrNull(it - 1) } ?: emptyList()

/**
 * Composes the "plates + band names" portion of an HT setup string, e.g. `"166 plates + Orange,
 * Red"`. Either part may be absent — this builds whatever is present, joined with `" + "`.
 * Returns an empty string when both [plates] and [config] are null/empty (no HT setup at all).
 * Shared by [htSetupLine] (adds the peak suffix) and [htReconfigure] (banner text — no peak).
 */
private fun composePlatesAndBands(plates: Double?, config: List<Int>?): String {
    val platesPart = plates?.let { "${formatWeight(it)} plates" }
    val bandsPart = bandNames(config).takeIf { it.isNotEmpty() }?.joinToString(", ")
    return listOfNotNull(platesPart, bandsPart).joinToString(" + ")
}

/**
 * Full HT setup display line for [SetCard]'s target row (and TodayScreen's read-only preview
 * row): `"<plates> plates + <bands> · peak ~<target>"`. Any of the three parts may be absent;
 * present parts are joined with the same `+`/`·` separators as the brief specifies (e.g.
 * plates-only -> `"166 plates"`, peak-only -> `"peak ~250"`). Reuses [composePlatesAndBands] for
 * the first two parts rather than duplicating that formatting here.
 */
internal fun htSetupLine(plates: Double?, config: List<Int>?, targetFeltPeak: Double?): String {
    val platesAndBands = composePlatesAndBands(plates, config).takeIf { it.isNotEmpty() }
    val peakPart = targetFeltPeak?.let { "peak ~${formatWeight(it)}" }
    return listOfNotNull(platesAndBands, peakPart).joinToString(" · ")
}

/**
 * Reconfigure banner cue: fires (returns a non-null "Reconfigure to ..." string) when EITHER the
 * band config differs from [prevConfig] OR the plate count differs from [prevPlates] — an OR,
 * not an AND. Comparisons are plain Kotlin equality (order-sensitive `List<Int>?`, exact
 * `Double?` — no tolerance/rounding). Returns null when neither differs, and also returns null
 * when [plates] and [config] are BOTH null (nothing to reconfigure TO, even if the prior setup
 * was non-null — don't recommend an empty setup). Total/null-safe for any combination of nulls.
 * Reuses [composePlatesAndBands] so the banner text renders the same "plates + bands" shape as
 * [htSetupLine]'s setup line, just prefixed with "Reconfigure to " and with no peak suffix
 * (peak isn't part of "reconfigure").
 */
internal fun htReconfigure(
    prevPlates: Double?,
    prevConfig: List<Int>?,
    plates: Double?,
    config: List<Int>?,
): String? {
    if (plates == null && config == null) return null
    val differs = config != prevConfig || plates != prevPlates
    if (!differs) return null
    return "Reconfigure to ${composePlatesAndBands(plates, config)}"
}

/**
 * Observed peak-minus-plates: [feltPeak] `-` [plates], meaningful ONLY for a single-band setup
 * (the arithmetic isolates one band's contribution; it doesn't decompose across a multi-band
 * stack). Returns null when [config] is null, empty, or has 2+ elements, or when [feltPeak] or
 * [plates] is null.
 */
internal fun htObservedPeak(feltPeak: Double?, plates: Double?, config: List<Int>?): Double? {
    if (config == null || config.size != 1) return null
    if (feltPeak == null || plates == null) return null
    return feltPeak - plates
}
