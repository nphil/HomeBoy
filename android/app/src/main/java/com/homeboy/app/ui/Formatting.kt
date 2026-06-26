package com.homeboy.app.ui

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** "$1,234.50" */
fun formatMoney(value: Double): String =
    "$" + String.format(Locale.US, "%,.2f", value)

/** Compact money for tight chart labels: "$1.2k", "$3.4M". */
fun formatMoneyCompact(value: Double): String = when {
    value >= 1_000_000 -> "$" + String.format(Locale.US, "%.1fM", value / 1_000_000)
    value >= 1_000 -> "$" + String.format(Locale.US, "%.1fk", value / 1_000)
    else -> "$" + String.format(Locale.US, "%.0f", value)
}

private val isoDate = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val prettyDate = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
private val shortDate = DateTimeFormatter.ofPattern("MMM d", Locale.US)

/** Parse a Homebox date string ("yyyy-MM-dd" or RFC3339) to a LocalDate, or null. */
fun parseHbDate(raw: String?): LocalDate? {
    if (raw.isNullOrBlank()) return null
    val head = raw.take(10)
    if (head.startsWith("0001")) return null
    return runCatching { LocalDate.parse(head, isoDate) }.getOrNull()
}

fun LocalDate.pretty(): String = format(prettyDate)
fun LocalDate.short(): String = format(shortDate)

/** Human relative phrasing for a scheduled/completed date relative to today. */
fun relativeWhen(date: LocalDate, completed: Boolean): String {
    val today = LocalDate.now()
    val days = ChronoUnit.DAYS.between(today, date).toInt()
    if (completed) return "Completed ${date.short()}"
    return when {
        days < -1 -> "Overdue by ${-days} days"
        days == -1 -> "Overdue by 1 day"
        days == 0 -> "Due today"
        days == 1 -> "Due tomorrow"
        days in 2..30 -> "Due in $days days"
        else -> "Scheduled ${date.short()}"
    }
}
