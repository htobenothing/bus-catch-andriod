package sg.buscatch

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Home-screen widget listing every saved stop: when to leave and the next buses. */
class AllStopsWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { mgr.updateAppWidget(it, build(ctx, it)) }
        RefreshWorker.schedulePeriodic(ctx)
        RefreshWorker.refreshSoon(ctx)
    }

    override fun onEnabled(ctx: Context) {
        RefreshWorker.schedulePeriodic(ctx)
    }

    companion object {
        private fun ids(ctx: Context): IntArray =
            AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, AllStopsWidget::class.java))

        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val all = ids(ctx)
            if (all.isEmpty()) return
            all.forEach { mgr.updateAppWidget(it, build(ctx, it)) }
            @Suppress("DEPRECATION")
            mgr.notifyAppWidgetViewDataChanged(all, R.id.allList)
        }

        private fun hm(ms: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

        private fun build(ctx: Context, widgetId: Int): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_all)
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            v.setOnClickPendingIntent(R.id.root, open)
            v.setOnClickPendingIntent(
                R.id.allRefresh,
                PendingIntent.getBroadcast(
                    ctx, 1, Intent(ctx, BusWidget::class.java).setAction(BusWidget.ACTION_REFRESH),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            val routes = Store.routes(ctx)
            val last = Store.lastRefresh(ctx)
            v.setTextViewText(
                R.id.allUpdated,
                when {
                    routes.isEmpty() || last == 0L -> ""
                    !Store.lastOk(ctx) -> "Offline · ${hm(last)}"
                    else -> "Live ${hm(last)}"
                }
            )
            v.setViewVisibility(R.id.allEmpty, if (routes.isEmpty()) View.VISIBLE else View.GONE)
            v.setViewVisibility(R.id.allList, if (routes.isEmpty()) View.GONE else View.VISIBLE)

            @Suppress("DEPRECATION")
            v.setRemoteAdapter(
                R.id.allList,
                Intent(ctx, AllStopsService::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                    .setData(android.net.Uri.parse("buscatch://all/$widgetId"))
            )
            v.setPendingIntentTemplate(
                R.id.allList,
                PendingIntent.getActivity(
                    ctx, 4, Intent(ctx, MainActivity::class.java),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            return v
        }
    }
}

/** Rows for [AllStopsWidget]: one per saved stop. */
class AllStopsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(applicationContext)

    private class Factory(private val ctx: Context) : RemoteViewsFactory {
        private data class Row(val name: String, val bell: String, val leave: String, val color: Int, val next: String)
        private var rows: List<Row> = emptyList()

        private fun hm(ms: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

        override fun onCreate() {}
        override fun onDestroy() {}

        override fun onDataSetChanged() {
            val now = System.currentTimeMillis()
            rows = Store.routes(ctx).map { r ->
                val services = Store.cachedStop(ctx, r.stopCode)
                val plan = Planner.plan(r, services, now)
                val at = plan?.leaveAtMs
                val bus = plan?.target
                val (leave, color) = when {
                    plan == null -> "Loading…" to 0xFF9A917C.toInt()
                    plan.upcoming.isEmpty() -> "No buses coming" to 0xFF9A917C.toInt()
                    at == null || bus == null -> "Too late for the next bus" to 0xFFFF7A45.toInt()
                    at <= now + 60_000 -> "Leave NOW · ${bus.service} at ${hm(bus.timeMs)}" to 0xFF5EE08F.toInt()
                    else -> "Leave ${hm(at)} · ${bus.service} at ${hm(bus.timeMs)}" to 0xFFFFB21A.toInt()
                }
                val multi = r.services.size > 1
                val next = plan?.upcoming?.take(4)?.joinToString("  ") {
                    if (multi) "${it.service} ${hm(it.timeMs)}" else hm(it.timeMs)
                }.orEmpty()
                Row(
                    name = "${r.displayName} · ${r.serviceLabel}",
                    bell = if (r.alertOn) "🔔" else "🔕",
                    leave = leave, color = color, next = next,
                )
            }
        }

        override fun getCount() = rows.size

        override fun getViewAt(position: Int): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_stop_row)
            val row = rows.getOrNull(position) ?: return v
            v.setTextViewText(R.id.srName, row.name)
            v.setTextViewText(R.id.srBell, row.bell)
            v.setTextViewText(R.id.srLeave, row.leave)
            v.setTextColor(R.id.srLeave, row.color)
            v.setTextViewText(R.id.srNext, row.next)
            v.setOnClickFillInIntent(R.id.stopRow, Intent())
            return v
        }

        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 1
        override fun getItemId(position: Int) = position.toLong()
        override fun hasStableIds() = false
    }
}
