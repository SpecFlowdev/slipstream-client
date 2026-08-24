package dev.specflow.slipstream.ui

import java.util.Locale

/**
 * Sizes and rates, in the units people read them in.
 *
 * Bytes are counted in powers of two because that is what the counters
 * actually measure, and rates are shown per second because that is the
 * question being asked of them.
 */

private val UNITS = arrayOf("B", "KB", "MB", "GB", "TB")

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < UNITS.size - 1) {
        value /= 1024
        unit++
    }
    val digits = if (value >= 100) 0 else if (value >= 10) 1 else 2
    return String.format(Locale.US, "%.${digits}f %s", value, UNITS[unit])
}

fun formatRate(bytesPerSecond: Long): String = "${formatBytes(bytesPerSecond)}/s"

fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}

fun formatWhen(epochMs: Long): String {
    val ago = (System.currentTimeMillis() - epochMs) / 1000
    return when {
        ago < 60 -> "just now"
        ago < 3600 -> "${ago / 60} min ago"
        ago < 86400 -> "${ago / 3600} h ago"
        else -> "${ago / 86400} d ago"
    }
}
