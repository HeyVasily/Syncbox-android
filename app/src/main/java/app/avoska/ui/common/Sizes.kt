package app.avoska.ui.common

import java.util.Locale
import kotlin.math.abs

private val UNITS = listOf("B", "KB", "MB", "GB", "TB")
private const val STEP = 1000.0

/** Decimal units as Android uses them: "2 MB", "14.2 GB", "41 GB". One decimal, dropped when zero. */
fun formatSize(bytes: Long, locale: Locale = Locale.getDefault()): String {
    val unit = unitIndex(bytes)
    return "${formatNumber(bytes, unit, locale)} ${UNITS[unit]}"
}

/** "2.3 of 6.8 GB": both values in the unit of [total]. */
fun formatProgress(done: Long, total: Long, ofWord: String, locale: Locale = Locale.getDefault()): String {
    val unit = unitIndex(total)
    return "${formatNumber(done, unit, locale)} $ofWord ${formatNumber(total, unit, locale)} ${UNITS[unit]}"
}

/** Nearest whole percent, never 100 until [done] reaches [total]. */
fun percent(done: Long, total: Long): Int =
    if (total <= 0) 0 else if (done >= total) 100 else ((done * 100 + total / 2) / total).toInt().coerceAtMost(99)

private fun unitIndex(bytes: Long): Int {
    var value = abs(bytes.toDouble())
    var unit = 0
    while (value >= STEP && unit < UNITS.lastIndex) {
        value /= STEP
        unit++
    }
    return unit
}

private fun formatNumber(bytes: Long, unit: Int, locale: Locale): String {
    var value = bytes.toDouble()
    repeat(unit) { value /= STEP }
    val rounded = Math.round(value * 10) / 10.0
    return if (rounded == Math.floor(rounded)) {
        String.format(locale, "%d", rounded.toLong())
    } else {
        String.format(locale, "%.1f", rounded)
    }
}
