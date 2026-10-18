package sg.buscatch

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Decides which saved stop a Stop widget shows.
 *
 * Auto mode rules (every stop's alert range counts, whether its 🔔 is on or off;
 * 🔕 only silences the notification):
 *  1. If now is inside one or more ranges, show that stop. If several overlap,
 *     show the one you have to leave for soonest.
 *  2. Otherwise show the stop whose range opens next (on a day it runs).
 *  3. If no stop has a usable range, show nothing; the widget says "No alert times set".
 */
object WidgetRoutes {
    /** Stored instead of a stop id when the widget should pick the stop by alert range. */
    const val AUTO = "__auto__"

    fun isAuto(ctx: Context, widgetId: Int): Boolean {
        val chosen = Store.widgetRoute(ctx, widgetId)
        return chosen == null || chosen == AUTO
    }

    /** The stop for this widget: the chosen one, or in Auto mode the one picked by alert range. */
    fun resolve(ctx: Context, widgetId: Int): Route? {
        val routes = Store.routes(ctx)
        val chosen = Store.widgetRoute(ctx, widgetId)
        return if (chosen == null || chosen == AUTO) nearest(ctx, routes) else routes.firstOrNull { it.id == chosen }
    }

    fun nearest(ctx: Context, routes: List<Route>, now: LocalDateTime = LocalDateTime.now()): Route? {
        val ranged = routes.filter { range(it) != null }
        if (ranged.isEmpty()) return null

        val open = ranged.filter { openNow(it, now) }
        if (open.isNotEmpty()) {
            val nowMs = System.currentTimeMillis()
            // Overlap: the stop you must leave for first; ties (or no live data) keep list order
            return open.minByOrNull { r ->
                Planner.plan(r, Store.cachedStop(ctx, r.stopCode), nowMs)?.leaveAtMs ?: Long.MAX_VALUE
            }
        }
        // Next range to open; ties keep list order
        return ranged.minByOrNull { nextStart(it, now) ?: LocalDateTime.MAX }
    }

    /** Start and end times of a stop's range, or null if they can't be read or are identical. */
    private fun range(r: Route) = Route.parseTime(r.activeFrom)?.let { from ->
        Route.parseTime(r.activeTo)?.let { to -> if (from == to) null else from to to }
    }

    /**
     * Is [now] inside this stop's range? Handles ranges that cross midnight (22:00–01:00):
     * those count from the day the range started, so 00:30 Saturday is inside Friday's range.
     */
    fun openNow(r: Route, now: LocalDateTime = LocalDateTime.now()): Boolean {
        val (from, to) = range(r) ?: return false
        for (back in 0L..1L) {
            val day = now.toLocalDate().minusDays(back)
            if (!r.dayOk(day)) continue
            val start = day.atTime(from)
            val end = if (to.isAfter(from)) day.atTime(to) else day.plusDays(1).atTime(to)
            if (!now.isBefore(start) && !now.isAfter(end)) return true
        }
        return false
    }

    /** End of the range that contains [now], or null if [now] isn't inside one. */
    fun currentRangeEnd(r: Route, now: LocalDateTime = LocalDateTime.now()): LocalDateTime? {
        val (from, to) = range(r) ?: return null
        for (back in 0L..1L) {
            val day = now.toLocalDate().minusDays(back)
            if (!r.dayOk(day)) continue
            val start = day.atTime(from)
            val end = if (to.isAfter(from)) day.atTime(to) else day.plusDays(1).atTime(to)
            if (!now.isBefore(start) && !now.isAfter(end)) return end
        }
        return null
    }

    /** When this stop's range next opens, looking a week ahead. */
    fun nextStart(r: Route, now: LocalDateTime = LocalDateTime.now()): LocalDateTime? {
        val (from, _) = range(r) ?: return null
        for (d in 0L..7L) {
            val date: LocalDate = now.toLocalDate().plusDays(d)
            if (!r.dayOk(date)) continue
            val start = date.atTime(from)
            if (start.isAfter(now)) return start
        }
        return null
    }

    /** Kept for callers that want minutes. */
    fun minutesUntilWindow(r: Route, now: LocalDateTime = LocalDateTime.now()): Long? =
        nextStart(r, now)?.let { java.time.Duration.between(now, it).toMinutes() }

    /**
     * Wake the widgets exactly when the next range opens or closes, so Auto switches stops
     * on the minute instead of waiting for the next 15-minute refresh.
     */
    fun scheduleNextSwitch(ctx: Context) {
        val routes = Store.routes(ctx)
        val now = LocalDateTime.now()
        val next = routes.mapNotNull { r ->
            val (from, to) = range(r) ?: return@mapNotNull null
            (0L..8L).flatMap { d ->
                val day = now.toLocalDate().plusDays(d - 1)
                if (!r.dayOk(day)) emptyList() else {
                    val start = day.atTime(from)
                    val end = if (to.isAfter(from)) day.atTime(to) else day.plusDays(1).atTime(to)
                    listOf(start, end.plusMinutes(1)) // switch the minute after a range ends
                }
            }.filter { it.isAfter(now) }.minOrNull()
        }.minOrNull()

        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            ctx, 77, Intent(ctx, BusWidget::class.java).setAction(BusWidget.ACTION_REFRESH),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        if (next == null) { am.cancel(pi); return }
        val at = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val exactOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        try {
            if (exactOk) am.setExactAndAllowWhileIdle(AlarmManager.RTC, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC, at, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC, at, pi)
        }
    }
}
