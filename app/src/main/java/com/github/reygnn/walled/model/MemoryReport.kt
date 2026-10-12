package com.github.reygnn.walled.model

import java.util.Locale

/**
 * Retained bitmap memory of one decoded layer — pure, JVM-testable (ported from
 * the launcher's `WallpaperMemoryReport`). The SOURCE resolution sits next to the
 * decoded size because a layer that looks small in the collage can still be a
 * full-resolution photo: the source is the cost driver, and this shows which
 * layer is the heavy one.
 */
data class MemoryRow(
    /** 1-based position from the bottom, as the layer indicator shows it. */
    val position: Int,
    val decodedWidth: Int,
    val decodedHeight: Int,
    /** `inSampleSize` of the decode — 1 = full resolution. */
    val sampleSize: Int,
    val originalWidth: Int,
    val originalHeight: Int,
    val bytes: Long,
    /** Raw `Bitmap.Config` name (HARDWARE / ARGB_8888 / …) — a diagnostic, kept verbatim. */
    val config: String,
) {
    val isDownsampled: Boolean
        get() = sampleSize > 1 && (originalWidth > decodedWidth || originalHeight > decodedHeight)
}

data class MemoryReport(val rows: List<MemoryRow>) {
    val totalBytes: Long get() = rows.sumOf { it.bytes }
}

/** `12.4 MB` (one decimal, locale-aware: `12,4 MB` on a German device). */
fun formatMegabytes(bytes: Long, locale: Locale = Locale.getDefault()): String =
    String.format(locale, "%.1f MB", bytes / 1_048_576.0)
