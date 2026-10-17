package sg.buscatch

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Home-screen widget: up to 3 saved buses with live arrivals and leave-by time. */
class BusWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { mgr.updateAppWidget(it, build(ctx, refreshing = false)) }
        RefreshWorker.schedulePeriodic(ctx)
        RefreshWorker.refreshSoon(ctx)
    }

    override fun onEnabled(ctx: Context) {
        RefreshWorker.schedulePeriodic(ctx)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action == ACTION_REFRESH) {
            val mgr = AppWidgetManager.getInstance(ctx)
            mgr.getAppWidgetIds(ComponentName(ctx, BusWidget::class.java)).forEach {
                mgr.updateAppWidget(it, build(ctx, refreshing = true))
            }
            RefreshWorker.refreshSoon(ctx)
        }
    }

    companion object {
        const val ACTION_REFRESH = "sg.buscatch.REFRESH"

        private val ROW_IDS = listOf(
            intArrayOf(R.id.row1, R.id.svc1, R.id.name1, R.id.next1, R.id.leave1),
            intArrayOf(R.id.row2, R.id.svc2, R.id.name2, R.id.next2, R.id.leave2),
            intArrayOf(R.id.row3, R.id.svc3, R.id.name3, R.id.next3, R.id.leave3),
        )

        private const val AMBER = 0xFFFFB21A.toInt()
        private const val GREEN = 0xFF5EE08F.toInt()
        private const val RED = 0xFFFF7A45.toInt()
        private const val DIM = 0xFF9A917C.toInt()

        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, BusWidget::class.java))
            if (ids.isEmpty()) return
            val views = build(ctx, refreshing = false)
            ids.forEach { mgr.updateAppWidget(it, views) }
        }

        private fun hm(ms: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

        fun build(ctx: Context, refreshing: Boolean): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_bus)
            val routes = Store.routes(ctx)
            val now = System.currentTimeMillis()

            // Header
            val last = Store.lastRefresh(ctx)
            v.setTextViewText(
                R.id.updated,
                when {
                    refreshing -> "Updating…"
                    routes.isEmpty() -> "Open app to add a bus"
                    last == 0L -> "Waiting for data"
                    !Store.lastOk(ctx) -> "Offline · ${hm(last)}"
                    else -> "Live · ${hm(last)}"
                }
            )

            v.setViewVisibility(R.id.empty, if (routes.isEmpty()) View.VISIBLE else View.GONE)

            ROW_IDS.forEachIndexed { i, ids ->
                val r = routes.getOrNull(i)
                if (r == null) { v.setViewVisibility(ids[0], View.GONE); return@forEachIndexed }
                v.setViewVisibility(ids[0], View.VISIBLE)
                v.setTextViewText(ids[1], r.service)
                v.setTextViewText(ids[2], r.displayName)

                val plan = Planner.plan(r, Store.cachedStop(ctx, r.stopCode), now)
                val (nextTxt, leaveTxt, leaveColor) = when {
                    plan == null -> Triple("Loading…", "", DIM)
                    plan.upcoming.isEmpty() -> Triple("Not running now", "", DIM)
                    else -> {
                        // Clock times rather than "in N min", so the widget never shows stale countdowns
                        val nxt = plan.upcoming.take(3).joinToString("  ") { hm(it.timeMs) }
                        val est = if (plan.upcoming.first().monitored) "" else " est."
                        val leaveAt = plan.leaveAtMs
                        when {
                            leaveAt == null -> Triple(nxt + est, "Missed", RED)
                            leaveAt <= now + 60_000 -> Triple(nxt + est, "Leave NOW", GREEN)
                            else -> Triple(nxt + est, "Leave ${hm(leaveAt)}", AMBER)
                        }
                    }
                }
                v.setTextViewText(ids[3], nextTxt)
                v.setTextViewText(ids[4], leaveTxt)
                v.setTextColor(ids[4], leaveColor)
            }

            // Tap refresh icon → refresh; tap anywhere else → open app
            val refresh = PendingIntent.getBroadcast(
                ctx, 1, Intent(ctx, BusWidget::class.java).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            v.setOnClickPendingIntent(R.id.refresh, refresh)
            val open = PendingIntent.getActivity(
                ctx, 2, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            v.setOnClickPendingIntent(R.id.root, open)
            return v
        }
    }
}
