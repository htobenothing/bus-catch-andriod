package sg.buscatch

import android.content.Context
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * A "trip" is one stop's alert range on one day. Once you're on your way, the trip is done:
 * no more alarms, live updates or "leave now" for that stop until its range ends.
 */
object Trip {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("buscatch", Context.MODE_PRIVATE)

    fun isDone(ctx: Context, routeId: String): Boolean =
        System.currentTimeMillis() < prefs(ctx).getLong("done_until_$routeId", 0)

    /** Mark this stop's current range as done (until the range ends, or the end of today). */
    fun finish(ctx: Context, routeId: String?) {
        if (routeId == null) return
        val r = Store.routes(ctx).firstOrNull { it.id == routeId } ?: return
        val now = LocalDateTime.now()
        val end = WidgetRoutes.currentRangeEnd(r, now) ?: now.toLocalDate().plusDays(1).atStartOfDay()
        val ms = end.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        prefs(ctx).edit().putLong("done_until_$routeId", ms).apply()
        Store.markAlerted(ctx, routeId) // no alarm later in this range either
    }

    /** Undo, e.g. if you missed the bus after all. */
    fun reopen(ctx: Context, routeId: String) {
        prefs(ctx).edit().remove("done_until_$routeId").apply()
        Store.clearAlerted(ctx, routeId)
    }

    /** Remember which bus the alert was for, so the trip can finish itself after it arrives. */
    fun alertedFor(ctx: Context, routeId: String, busMs: Long) {
        prefs(ctx).edit().putLong("alert_bus_$routeId", busMs).apply()
    }

    /**
     * Trips you didn't tap anything for: once the bus you were alerted for has arrived
     * (plus 3 minutes), assume you're on it and finish the trip.
     */
    fun autoFinish(ctx: Context) {
        val now = System.currentTimeMillis()
        Store.routes(ctx).forEach { r ->
            if (isDone(ctx, r.id) || !Store.alertedToday(ctx, r.id)) return@forEach
            val bus = prefs(ctx).getLong("alert_bus_${r.id}", 0)
            if (bus > 0 && now > bus + 3 * 60_000) finish(ctx, r.id)
        }
    }
}
