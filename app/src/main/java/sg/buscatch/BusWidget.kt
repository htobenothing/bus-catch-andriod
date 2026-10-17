package sg.buscatch

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Home-screen widget for ONE saved stop. Add several widgets to watch several stops;
 * each one asks which stop to show when it's placed (see [WidgetConfigActivity]).
 */
class BusWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { mgr.updateAppWidget(it, build(ctx, it, refreshing = false)) }
        RefreshWorker.schedulePeriodic(ctx)
        RefreshWorker.refreshSoon(ctx)
    }

    override fun onEnabled(ctx: Context) {
        RefreshWorker.schedulePeriodic(ctx)
    }

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, newOptions: Bundle) {
        // Older Android: pick the layout for the new size ourselves
        mgr.updateAppWidget(id, build(ctx, id, refreshing = false))
    }

    override fun onDeleted(ctx: Context, ids: IntArray) {
        ids.forEach { Store.clearWidget(ctx, it) }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action == ACTION_REFRESH) {
            val mgr = AppWidgetManager.getInstance(ctx)
            ids(ctx).forEach { mgr.updateAppWidget(it, build(ctx, it, refreshing = true)) }
            RefreshWorker.refreshSoon(ctx)
        }
    }

    companion object {
        const val ACTION_REFRESH = "sg.buscatch.REFRESH"

        private val ROWS = listOf(
            intArrayOf(R.id.row1, R.id.svc1, R.id.next1),
            intArrayOf(R.id.row2, R.id.svc2, R.id.next2),
            intArrayOf(R.id.row3, R.id.svc3, R.id.next3),
            intArrayOf(R.id.row4, R.id.svc4, R.id.next4),
        )

        private const val AMBER = 0xFFFFB21A.toInt()
        private const val GREEN = 0xFF5EE08F.toInt()
        private const val RED = 0xFFFF7A45.toInt()
        private const val DIM = 0xFF9A917C.toInt()

        private fun ids(ctx: Context): IntArray =
            AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, BusWidget::class.java))

        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            ids(ctx).forEach { mgr.updateAppWidget(it, build(ctx, it, refreshing = false)) }
        }

        fun update(ctx: Context, widgetId: Int) {
            AppWidgetManager.getInstance(ctx).updateAppWidget(widgetId, build(ctx, widgetId, refreshing = false))
        }

        private fun hm(ms: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

        /**
         * Size-aware views. Android 12+ switches between them live as the widget is resized;
         * older versions get one layout chosen from the widget's current size.
         */
        fun build(ctx: Context, widgetId: Int, refreshing: Boolean): RemoteViews {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                return RemoteViews(
                    mapOf(
                        SizeF(100f, 40f) to buildTiny(ctx, widgetId),
                        SizeF(170f, 40f) to buildSmall(ctx, widgetId, refreshing),
                        SizeF(170f, 100f) to buildFull(ctx, widgetId, refreshing),
                    )
                )
            }
            val o = AppWidgetManager.getInstance(ctx).getAppWidgetOptions(widgetId)
            val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 180)
            val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 110)
            return when {
                h < 100 && w < 170 -> buildTiny(ctx, widgetId)
                h < 100 -> buildSmall(ctx, widgetId, refreshing)
                else -> buildFull(ctx, widgetId, refreshing)
            }
        }

        private fun routeFor(ctx: Context, widgetId: Int): Route? {
            val routes = Store.routes(ctx)
            val chosen = Store.widgetRoute(ctx, widgetId)
            return if (chosen == null) routes.firstOrNull() else routes.firstOrNull { it.id == chosen }
        }

        private fun tapIntent(ctx: Context, widgetId: Int, r: Route?): PendingIntent =
            if (r == null) PendingIntent.getActivity(
                ctx, widgetId,
                Intent(ctx, WidgetConfigActivity::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                    .setData(android.net.Uri.parse("buscatch://widget/$widgetId")),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            ) else PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

        private fun refreshIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
            ctx, 1, Intent(ctx, BusWidget::class.java).setAction(ACTION_REFRESH),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        /** Leave advice for a stop: big text, detail text, colour. */
        private data class Leave(val big: String, val detail: String, val color: Int)

        private fun leaveFor(ctx: Context, r: Route, now: Long): Leave {
            val plan = Planner.plan(r, Store.cachedStop(ctx, r.stopCode), now)
            val at = plan?.leaveAtMs
            val bus = plan?.target
            return when {
                plan == null -> Leave("…", "Loading", DIM)
                plan.upcoming.isEmpty() -> Leave("--", "No buses coming", DIM)
                at == null || bus == null -> Leave("MISSED", "Too late for the next bus", RED)
                at <= now + 60_000 -> Leave("NOW", "${bus.service} at ${hm(bus.timeMs)}", GREEN)
                else -> Leave(hm(at), "${bus.service} at ${hm(bus.timeMs)}", AMBER)
            }
        }

        /** 2x1: leave time in big type, bus and stop underneath. */
        private fun buildTiny(ctx: Context, widgetId: Int): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_tiny)
            val r = routeFor(ctx, widgetId)
            v.setOnClickPendingIntent(R.id.root, tapIntent(ctx, widgetId, r))
            if (r == null) {
                v.setTextViewText(R.id.tBig, "Bus Catch"); v.setTextColor(R.id.tBig, AMBER)
                v.setTextViewText(R.id.tSmall, "Tap to pick a stop")
                return v
            }
            val l = leaveFor(ctx, r, System.currentTimeMillis())
            v.setTextViewText(R.id.tBig, if (l.big == "NOW" || l.big.contains(":")) "Leave ${l.big}" else l.big)
            v.setTextColor(R.id.tBig, l.color)
            v.setTextViewText(R.id.tSmall, l.detail)
            return v
        }

        /** 3x1: stop name over the leave line, with refresh. */
        private fun buildSmall(ctx: Context, widgetId: Int, refreshing: Boolean): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_small)
            val r = routeFor(ctx, widgetId)
            v.setOnClickPendingIntent(R.id.root, tapIntent(ctx, widgetId, r))
            v.setOnClickPendingIntent(R.id.refresh, refreshIntent(ctx))
            if (r == null) {
                v.setTextViewText(R.id.sStop, "Bus Catch")
                v.setTextViewText(R.id.sLeave, "Tap to pick a stop"); v.setTextColor(R.id.sLeave, AMBER)
                return v
            }
            v.setTextViewText(R.id.sStop, if (refreshing) "${r.displayName} · updating…" else r.displayName)
            val l = leaveFor(ctx, r, System.currentTimeMillis())
            val prefix = if (l.big == "NOW" || l.big.contains(":")) "Leave ${l.big}" else l.big
            v.setTextViewText(R.id.sLeave, if (l.detail.isNotEmpty()) "$prefix · ${l.detail}" else prefix)
            v.setTextColor(R.id.sLeave, l.color)
            return v
        }

        /** 3x2 and up: every tracked bus with its next arrivals, plus the leave line. */
        private fun buildFull(ctx: Context, widgetId: Int, refreshing: Boolean): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_bus)
            val routes = Store.routes(ctx)
            val chosen = Store.widgetRoute(ctx, widgetId)
            // Widgets from before per-stop widgets existed fall back to the first stop
            val r = if (chosen == null) routes.firstOrNull() else routes.firstOrNull { it.id == chosen }
            val now = System.currentTimeMillis()

            // Tapping a widget with no stop opens the stop picker; otherwise opens the app
            val tap = if (r == null) {
                PendingIntent.getActivity(
                    ctx, widgetId,
                    Intent(ctx, WidgetConfigActivity::class.java)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                        .setData(android.net.Uri.parse("buscatch://widget/$widgetId")),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            } else {
                PendingIntent.getActivity(
                    ctx, 0, Intent(ctx, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            }
            v.setOnClickPendingIntent(R.id.root, tap)
            v.setOnClickPendingIntent(
                R.id.refresh,
                PendingIntent.getBroadcast(
                    ctx, 1, Intent(ctx, BusWidget::class.java).setAction(ACTION_REFRESH),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )

            if (r == null) {
                v.setTextViewText(R.id.stopName, "Bus Catch")
                v.setTextViewText(R.id.updated, "")
                v.setTextViewText(
                    R.id.empty,
                    if (routes.isEmpty()) "No stops yet. Tap to open Bus Catch and add one."
                    else "This stop was removed. Tap to choose another."
                )
                v.setViewVisibility(R.id.empty, View.VISIBLE)
                ROWS.forEach { v.setViewVisibility(it[0], View.GONE) }
                v.setViewVisibility(R.id.leave, View.GONE)
                return v
            }
            v.setViewVisibility(R.id.empty, View.GONE)

            // Header
            v.setTextViewText(R.id.stopName, r.displayName)
            val last = Store.cachedAt(ctx, r.stopCode)
            v.setTextViewText(
                R.id.updated,
                when {
                    refreshing -> "Updating…"
                    last == 0L -> "Stop ${r.stopCode}"
                    !Store.lastOk(ctx) -> "Offline · ${hm(last)}"
                    else -> "Live ${hm(last)}"
                }
            )

            // One row per bus number: its next arrivals as clock times (never go stale)
            val services = Store.cachedStop(ctx, r.stopCode)
            ROWS.forEachIndexed { i, ids ->
                val no = r.services.getOrNull(i)
                if (no == null) { v.setViewVisibility(ids[0], View.GONE); return@forEachIndexed }
                v.setViewVisibility(ids[0], View.VISIBLE)
                v.setTextViewText(ids[1], no)
                val arrivals = services?.firstOrNull { it.no.equals(no, ignoreCase = true) }
                    ?.arrivals?.filter { it.timeMs > now - 30_000 }
                v.setTextViewText(
                    ids[2],
                    when {
                        services == null -> "Loading…"
                        arrivals.isNullOrEmpty() -> "Not running"
                        else -> arrivals.take(3).joinToString("   ") { hm(it.timeMs) } +
                            (if (arrivals.first().monitored) "" else "  est.")
                    }
                )
            }

            // Leave line for the soonest bus you can still catch (any of the chosen numbers)
            val plan = Planner.plan(r, services, now)
            val leaveAt = plan?.leaveAtMs
            val bus = plan?.target
            val (text, color) = when {
                plan == null -> "" to DIM
                plan.upcoming.isEmpty() -> "No buses coming" to DIM
                leaveAt == null || bus == null -> "Too late for the next bus" to RED
                leaveAt <= now + 60_000 -> "Leave NOW · ${bus.service} at ${hm(bus.timeMs)}" to GREEN
                else -> "Leave ${hm(leaveAt)} · ${bus.service} at ${hm(bus.timeMs)}" to AMBER
            }
            v.setViewVisibility(R.id.leave, if (text.isEmpty()) View.GONE else View.VISIBLE)
            v.setTextViewText(R.id.leave, text)
            v.setTextColor(R.id.leave, color)
            return v
        }
    }
}
