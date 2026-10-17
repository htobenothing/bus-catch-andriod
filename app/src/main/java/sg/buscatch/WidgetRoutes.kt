package sg.buscatch

import android.content.Context
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/** Decides which saved stop a Stop widget shows. */
object WidgetRoutes {
    /** Stored instead of a stop id when the widget should pick the stop by alert time. */
    const val AUTO = "__auto__"

    fun isAuto(ctx: Context, widgetId: Int): Boolean {
        val chosen = Store.widgetRoute(ctx, widgetId)
        return chosen == null || chosen == AUTO
    }

    /** The stop for this widget: the chosen one, or in Auto mode the one whose alert time is nearest. */
    fun resolve(ctx: Context, widgetId: Int): Route? {
        val routes = Store.routes(ctx)
        val chosen = Store.widgetRoute(ctx, widgetId)
        return if (chosen == null || chosen == AUTO) nearest(ctx, routes) else routes.firstOrNull { it.id == chosen }
    }

    /**
     * 1. A stop whose alert window is open right now (if several, the one you must leave for first).
     * 2. Otherwise the stop whose alert window starts soonest.
     * 3. Otherwise (no alerts set up) the first stop.
     */
    fun nearest(ctx: Context, routes: List<Route>): Route? {
        if (routes.isEmpty()) return null
        val withAlert = routes.filter { it.alertOn }
        val open = withAlert.filter { it.inWindow() }
        if (open.isNotEmpty()) {
            val now = System.currentTimeMillis()
            return open.minByOrNull { r ->
                Planner.plan(r, Store.cachedStop(ctx, r.stopCode), now)?.leaveAtMs ?: Long.MAX_VALUE
            }
        }
        val now = LocalDateTime.now()
        return withAlert.minByOrNull { minutesUntilWindow(it, now) ?: Long.MAX_VALUE } ?: routes.first()
    }

    /** Minutes until this stop's next alert window opens, looking a week ahead. */
    fun minutesUntilWindow(r: Route, now: LocalDateTime = LocalDateTime.now()): Long? {
        val from = Route.parseTime(r.activeFrom) ?: return null
        for (d in 0L..7L) {
            val date: LocalDate = now.toLocalDate().plusDays(d)
            if (!r.dayOk(date)) continue
            val start = date.atTime(from)
            if (start.isAfter(now)) return Duration.between(now, start).toMinutes()
        }
        return null
    }
}
