package com.muse.agent

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

data class ScheduleSpec(
    val nextAt: Long,
    val hour: Int,
    val minute: Int,
    val repeat: String,
)

fun parseScheduleWhen(
    raw: String,
    repeat: String,
    now: ZonedDateTime = ZonedDateTime.now(),
): ScheduleSpec {
    val rep = if (repeat.equals("daily", true) || repeat == "每天") "daily" else "once"
    val text = raw.trim().lowercase()
        .replace("今天", "today")
        .replace("明天", "tomorrow")
        .replace("：", ":")
        .trim()
    if (text.isEmpty()) throw IllegalArgumentException("时间不能为空。例如 08:00、today 14:32、2026-08-15 14:32。")

    relativeMinutes(text)?.let { mins ->
        val at = now.plusMinutes(mins.toLong())
        return ScheduleSpec(at.toInstant().toEpochMilli(), at.hour, at.minute, "once")
    }

    val clock = text.replace("点", ":").replace("分", "").trim()
    val dateTime = parseDateTime(clock, now)
        ?: throw IllegalArgumentException("看不懂时间「$raw」。用 08:00、today 14:32 或 2026-08-15 14:32。")
    var at = dateTime
    if (rep == "daily") {
        at = nextDaily(now, dateTime.toLocalTime())
    } else if (!at.isAfter(now)) {
        val forceToday = text.startsWith("today")
        if (forceToday) {
            throw IllegalArgumentException("「$raw」已经过了。")
        }
        if (text.matches(TIME_ONLY)) {
            at = nextDaily(now, dateTime.toLocalTime())
        } else {
            throw IllegalArgumentException("「$raw」已经过了。")
        }
    }
    return ScheduleSpec(at.toInstant().toEpochMilli(), at.hour, at.minute, rep)
}

fun nextDaily(now: ZonedDateTime, time: LocalTime): ZonedDateTime {
    var at = now.withHour(time.hour).withMinute(time.minute).withSecond(0).withNano(0)
    if (!at.isAfter(now.plusSeconds(30))) at = at.plusDays(1)
    return at
}

fun nextAfterRun(repeat: String, hour: Int, minute: Int, now: ZonedDateTime = ZonedDateTime.now()): Long? {
    if (repeat != "daily") return null
    return nextDaily(now, LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59)))
        .toInstant().toEpochMilli()
}

fun formatScheduleInstant(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val at = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMs), zone)
    return at.format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
}

private val TIME_ONLY = Regex("""^\d{1,2}:\d{2}$""")
private val HM = Regex("""(?:today|tomorrow)?\s*(\d{1,2}):(\d{2})""")
private val YMD_HM = Regex("""(\d{4})-(\d{2})-(\d{2})[ t](\d{1,2}):(\d{2})""")

private fun relativeMinutes(text: String): Int? {
    Regex("""^(?:in\s+)?(\d+)\s*(m|min|mins|分钟|分)$""").matchEntire(text)?.let {
        return it.groupValues[1].toInt().coerceIn(1, 24 * 60)
    }
    Regex("""^(?:in\s+)?(\d+)\s*(h|hr|hour|hours|小时)$""").matchEntire(text)?.let {
        return it.groupValues[1].toInt().coerceIn(1, 72) * 60
    }
    Regex("""^(\d+)\s*分钟后$""").matchEntire(text)?.let {
        return it.groupValues[1].toInt().coerceIn(1, 24 * 60)
    }
    Regex("""^(\d+)\s*小时后$""").matchEntire(text)?.let {
        return it.groupValues[1].toInt().coerceIn(1, 72) * 60
    }
    return null
}

private fun parseDateTime(text: String, now: ZonedDateTime): ZonedDateTime? {
    YMD_HM.find(text)?.let { m ->
        val dt = LocalDateTime.of(
            m.groupValues[1].toInt(),
            m.groupValues[2].toInt(),
            m.groupValues[3].toInt(),
            m.groupValues[4].toInt(),
            m.groupValues[5].toInt(),
        )
        return dt.atZone(now.zone)
    }
    HM.find(text)?.let { m ->
        val time = LocalTime.of(m.groupValues[1].toInt().coerceIn(0, 23), m.groupValues[2].toInt().coerceIn(0, 59))
        val day: LocalDate = when {
            text.startsWith("tomorrow") -> now.toLocalDate().plusDays(1)
            else -> now.toLocalDate()
        }
        return ZonedDateTime.of(day, time, now.zone)
    }
    return null
}
