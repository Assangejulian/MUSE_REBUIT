package com.muse.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ScheduleTimeTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = ZonedDateTime.of(LocalDateTime.of(2026, 8, 15, 10, 0), zone)

    @Test
    fun dailyClockPicksTodayIfFuture() {
        val spec = parseScheduleWhen("16:30", "daily", now)
        assertEquals("daily", spec.repeat)
        assertEquals(16, spec.hour)
        assertEquals(30, spec.minute)
        val at = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(spec.nextAt), zone)
        assertEquals(15, at.dayOfMonth)
    }

    @Test
    fun dailyClockRollsToTomorrowIfPast() {
        val spec = parseScheduleWhen("08:00", "每天", now)
        val at = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(spec.nextAt), zone)
        assertEquals(16, at.dayOfMonth)
        assertEquals(8, at.hour)
    }

    @Test
    fun todayPastThrows() {
        try {
            parseScheduleWhen("today 09:00", "once", now)
            throw AssertionError("expected past today to fail")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("过了"))
        }
    }

    @Test
    fun todayFutureStaysToday() {
        val spec = parseScheduleWhen("today 14:32", "once", now)
        val at = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(spec.nextAt), zone)
        assertEquals("once", spec.repeat)
        assertEquals(15, at.dayOfMonth)
        assertEquals(14, at.hour)
        assertEquals(32, at.minute)
    }

    @Test
    fun relativeMinutes() {
        val spec = parseScheduleWhen("30分钟后", "once", now)
        val at = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(spec.nextAt), zone)
        assertEquals(10, at.hour)
        assertEquals(30, at.minute)
    }

    @Test
    fun absoluteStamp() {
        val spec = parseScheduleWhen("2026-08-16 07:05", "once", now)
        val at = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(spec.nextAt), zone)
        assertEquals(16, at.dayOfMonth)
        assertEquals(7, at.hour)
        assertEquals(5, at.minute)
    }
}
