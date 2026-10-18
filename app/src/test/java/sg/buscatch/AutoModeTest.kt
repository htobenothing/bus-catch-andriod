package sg.buscatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** Rules for the Stop widget's Auto mode (see WidgetRoutes). 2026-10-16 is a Friday. */
class AutoModeTest {
    private fun stop(id: String, from: String, to: String, days: String = "all", alertOn: Boolean = true) =
        Route(id, "83139", "155", id, 5, 1, from, to, days, alertOn)

    private fun at(s: String) = LocalDateTime.parse(s)

    private val morning = stop("morning", "07:00", "09:00", "weekdays")
    private val evening = stop("evening", "17:30", "19:00", "weekdays")

    @Test fun insideRangeIsOpen() {
        assertTrue(WidgetRoutes.openNow(morning, at("2026-10-16T07:00")))
        assertTrue(WidgetRoutes.openNow(morning, at("2026-10-16T08:59")))
        assertFalse(WidgetRoutes.openNow(morning, at("2026-10-16T09:01")))
        assertFalse(WidgetRoutes.openNow(morning, at("2026-10-16T06:59")))
    }

    @Test fun weekdayRangeClosedOnWeekend() {
        assertFalse(WidgetRoutes.openNow(morning, at("2026-10-17T08:00"))) // Saturday
    }

    @Test fun afterMorningRangeTheNextToOpenIsEvening() {
        // 09:30 Friday: morning closed 30 min ago, evening opens 17:30 the same day
        val now = at("2026-10-16T09:30")
        assertEquals(at("2026-10-16T17:30"), WidgetRoutes.nextStart(evening, now))
        assertEquals(at("2026-10-19T07:00"), WidgetRoutes.nextStart(morning, now)) // Monday
    }

    @Test fun fridayNightNextIsMondayMorning() {
        val now = at("2026-10-16T20:00")
        assertEquals(at("2026-10-19T07:00"), WidgetRoutes.nextStart(morning, now))
        assertEquals(at("2026-10-19T17:30"), WidgetRoutes.nextStart(evening, now))
    }

    @Test fun rangeCrossingMidnightStaysOpenAfterMidnight() {
        val late = stop("late", "22:00", "01:00", "weekdays")
        assertTrue(WidgetRoutes.openNow(late, at("2026-10-16T23:30")))  // Friday night
        assertTrue(WidgetRoutes.openNow(late, at("2026-10-17T00:30")))  // after midnight, still Friday's range
        assertFalse(WidgetRoutes.openNow(late, at("2026-10-17T01:30")))
        assertFalse(WidgetRoutes.openNow(late, at("2026-10-17T23:30"))) // Saturday: no range
    }

    @Test fun alertOffStillHasARange() {
        val muted = stop("muted", "07:00", "09:00", alertOn = false)
        assertTrue(WidgetRoutes.openNow(muted, at("2026-10-16T08:00")))
        assertEquals(at("2026-10-16T07:00"), WidgetRoutes.nextStart(muted, at("2026-10-16T06:00")))
    }

    @Test fun identicalStartAndEndIsNotARange() {
        val broken = stop("broken", "08:00", "08:00")
        assertFalse(WidgetRoutes.openNow(broken, at("2026-10-16T08:00")))
        assertNull(WidgetRoutes.nextStart(broken, at("2026-10-16T07:00")))
    }
}
