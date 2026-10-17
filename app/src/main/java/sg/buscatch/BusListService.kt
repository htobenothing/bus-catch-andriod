package sg.buscatch

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Feeds the full-size widget's scrolling list: one row per tracked bus number. */
class BusListService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        Factory(applicationContext, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID))

    private class Factory(private val ctx: Context, private val widgetId: Int) : RemoteViewsFactory {
        private var rows: List<Pair<String, String>> = emptyList()
        private var pal = Theme.CLASSIC

        private fun hm(ms: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

        override fun onCreate() {}
        override fun onDestroy() {}

        override fun onDataSetChanged() {
            pal = Theme.of(ctx)
            val routes = Store.routes(ctx)
            val r = WidgetRoutes.resolve(ctx, widgetId)
            if (r == null) { rows = emptyList(); return }
            val services = Store.cachedStop(ctx, r.stopCode)
            val now = System.currentTimeMillis()
            rows = r.services.map { no ->
                val arrivals = services?.firstOrNull { it.no.equals(no, ignoreCase = true) }
                    ?.arrivals?.filter { it.timeMs > now - 30_000 }
                no to when {
                    services == null -> "Loading…"
                    arrivals.isNullOrEmpty() -> "Not running"
                    // Clock times rather than "in N min", so rows never show stale countdowns
                    else -> arrivals.take(3).joinToString("   ") { hm(it.timeMs) } +
                        (if (arrivals.first().monitored) "" else "  est.")
                }
            }
        }

        override fun getCount() = rows.size

        override fun getViewAt(position: Int): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_row)
            val (no, next) = rows.getOrNull(position) ?: ("" to "")
            v.setTextViewText(R.id.rowSvc, no)
            v.setTextViewText(R.id.rowNext, next)
            v.setTextColor(R.id.rowSvc, pal.accent)
            v.setTextColor(R.id.rowNext, pal.text)
            v.setOnClickFillInIntent(R.id.rowRoot, Intent()) // tap a row → open the app
            return v
        }

        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 1
        override fun getItemId(position: Int) = position.toLong()
        override fun hasStableIds() = false
    }
}
