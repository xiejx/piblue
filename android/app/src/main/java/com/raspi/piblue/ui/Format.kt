package com.raspi.piblue.ui

import java.util.Locale

fun formatUptime(seconds: Long): String {
    if (seconds <= 0) return "—"
    val days = seconds / 86_400
    val hours = (seconds % 86_400) / 3_600
    val minutes = (seconds % 3_600) / 60
    return when {
        days > 0 -> "${days}天 ${hours}小时"
        hours > 0 -> "${hours}小时 ${minutes}分"
        minutes > 0 -> "${minutes}分 ${seconds % 60}秒"
        else -> "${seconds}秒"
    }
}

fun formatTemp(c: Double?): String =
    if (c == null) "—" else String.format(Locale.US, "%.1f", c)

fun formatPct(v: Double?): String =
    if (v == null) "—" else String.format(Locale.US, "%.0f%%", v)

fun formatGb(v: Double?): String =
    if (v == null) "—" else String.format(Locale.US, "%.1f", v)

fun formatNumber(v: Double?): String =
    if (v == null) "—" else String.format(Locale.US, "%.1f", v)
