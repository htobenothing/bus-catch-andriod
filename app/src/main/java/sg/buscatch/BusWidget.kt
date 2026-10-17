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
import android.widget.Toast
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
        if (intent.action == ACTION_TOGGLE_ALERT) {
            val id = intent.getStringExtra(EXTRA_ROUTE_ID) ?: return
            val r = Store.routes(ctx).firstOrNull { it.id == id } ?: return
            val on = !r.alertOn
            Store.upsert(ctx, r.copy(alertOn = on))
            if (on) Store.clearAlerted(ctx, id) // re-arm today's alert
            Toast.makeText(
                ctx,
                if (on) "Alert on for ${r.displayName} (${r.activeFrom}–${r.activeTo})" else "Alert off for ${r.displayName}",
                Toast.LENGTH_SHORT
            ).show()
            updateAll(ctx)
            RefreshWorker.refreshSoon(ctx) // sets or cancels the scheduled alarm
        }
    }

    companion object {
        const val ACTION_REFRESH = "sg.buscatch.REFRESH"
        const val ACTION_TOGGLE_ALERT = "sg.buscatch.TOGGLE_ALERT"
        private const val EXTRA_ROUTE_ID = "route_id"

        /** One-tap alert switch for a stop. */
        private fun bellIntent(ctx: Context, r: Route): PendingIntent = PendingIntent.getBroadcast(
            ctx, ("bell" + r.id).hashCode(),
            Intent(ctx, BusWidget::class.java).setAction(ACTION_TOGGLE_ALERT).putExtra(EXTRA_ROUTE_ID, r.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        private fun setBell(ctx: Context, v: RemoteViews, r: Route?) {
            if (r == null) { v.setViewVisibility(R.id.bell, View.GONE); return }
            v.setViewVisibility(R.id.bell, View.VISIBLE)
            v.setImageViewResource(R.id.bell, if (r.alertOn) R.drawable.ic_bell_on else R.drawable.ic_bell_off)
            v.setContentDescription(R.id.bell, if (r.alertOn) "Leave alert on. Tap to turn off." else "Leave alert off. Tap to turn on.")
            v.setOnClickPendingIntent(R.id.bell, bellIntent(ctx, r))
        }

        private fun alertLabel(r: Route) = if (r.alertOn) "🔔 ${r.activeFrom}–${r.activeTo}" else "🔕 alert off"

        // Colours follow the chosen theme (classic LED or puppy); refreshed at each build
        private var pal = Theme.CLASSIC
        private val AMBER: Int get() = pal.accent
        private val GREEN: Int get() = pal.go
        private val RED: Int get() = pal.late
        private val DIM: Int get() = pal.dim

        private fun ids(ctx: Context): IntArray =
            AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, BusWidget::class.java))

        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val all = ids(ctx)
            all.forEach { mgr.updateAppWidget(it, build(ctx, it, refreshing = false)) }
            @Suppress("DEPRECATION")
            if (all.isNotEmpty()) mgr.notifyAppWidgetViewDataChanged(all, R.id.busList)
            AllStopsWidget.updateAll(ctx)
        }

        fun update(ctx: Context, widgetId: Int) {
            val mgr = AppWidgetManager.getInstance(ctx)
            mgr.updateAppWidget(widgetId, build(ctx, widgetId, refreshing = false))
            @Suppress("DEPRECATION")
            mgr.notifyAppWidgetViewDataChanged(widgetId, R.id.busList)
        }

        private fun hm(ms: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

        /**
         * Size-aware views. Android 12+ switches between them live as the widget is resized;
         * older versions get one layout chosen from the widget's current size.
         */
        fun build(ctx: Context, widgetId: Int, refreshing: Boolean): RemoteViews {
            pal = Theme.of(ctx)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                return RemoteViews(
                    mapOf(
                        SizeF(40f, 40f) to buildMicro(ctx, widgetId),
                        SizeF(100f, 40f) to buildTiny(ctx, widgetId),
                        SizeF(170f, 40f) to buildSmall(ctx, widgetId, refreshing),
                        SizeF(170f, 80f) to buildFull(ctx, widgetId, refreshing),
                    )
                )
            }
            val o = AppWidgetManager.getInstance(ctx).getAppWidgetOptions(widgetId)
            val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 180)
            val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 110)
            return when {
                h < 80 && w < 100 -> buildMicro(ctx, widgetId)
                h < 80 && w < 170 -> buildTiny(ctx, widgetId)
                h < 80 -> buildSmall(ctx, widgetId, refreshing)
                else -> buildFull(ctx, widgetId, refreshing)
            }
        }

        private fun routeFor(ctx: Context, widgetId: Int): Route? {
            val routes = Store.routes(ctx)
            return WidgetRoutes.resolve(ctx, widgetId)
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
        private data class Leave(val big: String, val detail: String, val color: Int, val bus: String = "", val busAt: String = "")

        private fun leaveFor(ctx: Context, r: Route, now: Long): Leave {
            val plan = Planner.plan(r, Store.cachedStop(ctx, r.stopCode), now)
            val at = plan?.leaveAtMs
            val bus = plan?.target
            return when {
                plan == null -> Leave("…", "Loading", DIM)
                plan.upcoming.isEmpty() -> Leave("--", "No buses coming", DIM)
                at == null || bus == null -> Leave("MISSED", "Too late for the next bus", RED)
                at <= now + 60_000 -> Leave("NOW", "${bus.service} at ${hm(bus.timeMs)}", GREEN, bus.service, hm(bus.timeMs))
                else -> Leave(hm(at), "${bus.service} at ${hm(bus.timeMs)}", AMBER, bus.service, hm(bus.timeMs))
            }
        }

        /** 2x1: leave time in big type, bus and stop underneath. */
        private fun buildTiny(ctx: Context, widgetId: Int): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_tiny)
            Theme.style(v, pal, dim = intArrayOf(R.id.tSmall))
            val r = routeFor(ctx, widgetId)
            v.setOnClickPendingIntent(R.id.root, tapIntent(ctx, widgetId, r))
            if (r == null) {
                v.setTextViewText(R.id.tBig, "Bus Pup"); v.setTextColor(R.id.tBig, AMBER)
                v.setTextViewText(R.id.tSmall, "Tap to pick a stop")
                return v
            }
            val l = leaveFor(ctx, r, System.currentTimeMillis())
            v.setTextViewText(R.id.tBig, if (l.big == "NOW" || l.big.contains(":")) "Leave ${l.big}" else l.big)
            v.setTextColor(R.id.tBig, l.color)
            v.setTextViewText(R.id.tSmall, l.detail)
            return v
        }

        /** 1x1: bus number, big leave time. */
        private fun buildMicro(ctx: Context, widgetId: Int): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_micro)
            Theme.style(v, pal, text = intArrayOf(R.id.mBus), dim = intArrayOf(R.id.mLabel))
            val r = routeFor(ctx, widgetId)
            v.setOnClickPendingIntent(R.id.root, tapIntent(ctx, widgetId, r))
            if (r == null) {
                v.setTextViewText(R.id.mBus, "BUS"); v.setTextViewText(R.id.mBig, "+")
                v.setTextColor(R.id.mBig, AMBER); v.setTextViewText(R.id.mLabel, "pick stop")
                return v
            }
            val l = leaveFor(ctx, r, System.currentTimeMillis())
            v.setTextViewText(R.id.mBus, (if (r.alertOn) "" else "🔕") + l.bus.ifBlank { r.services.firstOrNull() ?: "" })
            v.setTextViewText(R.id.mBig, l.big)
            v.setTextColor(R.id.mBig, l.color)
            v.setTextViewText(R.id.mLabel, when {
                l.big == "NOW" -> "GO"
                l.big.contains(":") -> "LEAVE"
                l.big == "MISSED" -> "next bus"
                else -> l.detail.take(10)
            })
            return v
        }

        /** 3x1: big leave time, then bus + stop, then the alert bell. Nothing long shares one line. */
        private fun buildSmall(ctx: Context, widgetId: Int, refreshing: Boolean): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_small)
            Theme.style(v, pal, text = intArrayOf(R.id.sBus), dim = intArrayOf(R.id.sLabel, R.id.sStop), dog = R.id.dog)
            val r = routeFor(ctx, widgetId)
            v.setOnClickPendingIntent(R.id.root, tapIntent(ctx, widgetId, r))
            setBell(ctx, v, r)
            if (r == null) {
                v.setTextViewText(R.id.sLabel, "")
                v.setTextViewText(R.id.sBig, "+"); v.setTextColor(R.id.sBig, AMBER)
                v.setTextViewText(R.id.sBus, "Bus Pup")
                v.setTextViewText(R.id.sStop, "Tap to pick a stop")
                return v
            }
            val l = leaveFor(ctx, r, System.currentTimeMillis())
            v.setTextViewText(R.id.sLabel, if (l.big.contains(":")) "LEAVE AT" else "LEAVE")
            v.setTextViewText(R.id.sBig, l.big)
            v.setTextColor(R.id.sBig, l.color)
            v.setTextViewText(R.id.sBus, if (l.bus.isNotEmpty()) "${l.bus} · ${l.busAt}" else l.detail)
            v.setTextViewText(R.id.sStop, if (refreshing) "Updating…" else (if (WidgetRoutes.isAuto(ctx, widgetId)) "⟳ " else "") + r.displayName)
            return v
        }

        /** 3x2 and up: leave line, then a scrolling list of every tracked bus and its next arrivals. */
        private fun buildFull(ctx: Context, widgetId: Int, refreshing: Boolean): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_bus)
            Theme.style(v, pal, text = intArrayOf(R.id.stopName, R.id.empty), dim = intArrayOf(R.id.updated), dog = R.id.dog)
            val routes = Store.routes(ctx)
            // Auto mode (or widgets set up before stop picking) shows the stop with the nearest alert time
            val r = WidgetRoutes.resolve(ctx, widgetId)
            val auto = WidgetRoutes.isAuto(ctx, widgetId)
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
            setBell(ctx, v, r)
            v.setOnClickPendingIntent(
                R.id.refresh,
                PendingIntent.getBroadcast(
                    ctx, 1, Intent(ctx, BusWidget::class.java).setAction(ACTION_REFRESH),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )

            if (r == null) {
                v.setTextViewText(R.id.stopName, "Bus Pup")
                v.setTextViewText(R.id.updated, "")
                v.setTextViewText(
                    R.id.empty,
                    if (routes.isEmpty()) "No stops yet. Tap to open Bus Pup and add one."
                    else "This stop was removed. Tap to choose another."
                )
                v.setViewVisibility(R.id.empty, View.VISIBLE)
                v.setViewVisibility(R.id.busList, View.GONE)
                v.setViewVisibility(R.id.leave, View.GONE)
                return v
            }
            v.setViewVisibility(R.id.empty, View.GONE)
            v.setViewVisibility(R.id.busList, View.VISIBLE)

            // Header
            v.setTextViewText(R.id.stopName, (if (auto) "⟳ " else "") + r.displayName)
            val last = Store.cachedAt(ctx, r.stopCode)
            v.setTextViewText(
                R.id.updated,
                when {
                    refreshing -> "Updating…"
                    last == 0L -> "Stop ${r.stopCode}"
                    !Store.lastOk(ctx) -> "Offline · ${hm(last)}"
                    else -> "Live ${hm(last)}"
                } + " · " + alertLabel(r)
            )

            // Every tracked bus, scrollable (rows come from BusListService)
            val services = Store.cachedStop(ctx, r.stopCode)
            @Suppress("DEPRECATION")
            v.setRemoteAdapter(
                R.id.busList,
                Intent(ctx, BusListService::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                    .setData(android.net.Uri.parse("buscatch://list/$widgetId"))
            )
            // List taps need a mutable template so each row's fill-in intent can merge into it
            v.setPendingIntentTemplate(
                R.id.busList,
                PendingIntent.getActivity(
                    ctx, 3, Intent(ctx, MainActivity::class.java),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )

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
