package sg.buscatch

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Leave-home alerts that don't depend on Android's background job scheduler
 * (which phones like Xiaomi delay or skip).
 *
 * The phone's AlarmManager wakes the app:
 *  - at the start of each stop's alert range ("tick"): fetch live times, work out when to leave
 *  - a few minutes before leaving ("tick" again): re-check in case the bus moved
 *  - at the leave time ("fire"): an alarm-clock alarm, the most reliable kind, which rings
 * Every tick reschedules the next one, and boot or app update restarts the chain.
 */
object AlertEngine {
    const val ACTION_TICK = "sg.buscatch.ALERT_TICK"
    const val ACTION_FIRE = "sg.buscatch.ALERT_FIRE"
    const val EXTRA_ROUTE = "route"
    const val EXTRA_BUS = "bus"
    const val EXTRA_BUS_MS = "bus_ms"
    const val EXTRA_TEST = "test"

    private const val REQ_TICK = 9101
    private const val REQ_FIRE = 9102
    private const val REQ_TEST = 9103

    private fun toMs(t: LocalDateTime) = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /**
     * Decide what happens next. [fetch] = true pulls live times for stops in an open range first.
     * Call from a background thread.
     */
    fun tick(ctx: Context, fetch: Boolean) {
        val routes = Store.routes(ctx)
        val now = System.currentTimeMillis()
        val nowLdt = LocalDateTime.now()
        val active = routes.filter { it.alertOn && WidgetRoutes.openNow(it, nowLdt) && !Store.alertedToday(ctx, it.id) }

        if (fetch && active.isNotEmpty()) {
            var ok = true
            active.map { it.stopCode }.distinct().forEach { code ->
                try { val raw = Api.fetchRaw(code); Api.parse(raw); Store.cacheStop(ctx, code, raw) } catch (e: Exception) { ok = false }
            }
            Store.setLastRefresh(ctx, ok)
        }

        var nextTick: Long? = null
        var fireAt: Long? = null
        var fireRoute: Route? = null
        var fireBus: Arrival? = null
        fun tickAt(t: Long) { nextTick = minOf(nextTick ?: t, t) }

        for (r in active) {
            val plan = Planner.plan(r, Store.cachedStop(ctx, r.stopCode), now)
            val leave = plan?.leaveAtMs
            val bus = plan?.target
            if (plan == null || leave == null || bus == null) { tickAt(now + 3 * 60_000); continue } // no data yet: retry
            val rangeEnd = WidgetRoutes.currentRangeEnd(r, nowLdt)?.let { toMs(it) } ?: continue
            if (leave > rangeEnd) continue // you'd leave after the range closes: no alert for this range
            if (leave - now <= 45_000) {
                Notifier.leaveNow(ctx, r, bus.timeMs, bus.service)
                Store.markAlerted(ctx, r.id)
                continue
            }
            if (fireAt == null || leave < fireAt!!) { fireAt = leave; fireRoute = r; fireBus = bus }
            // Re-check 3 minutes before leaving (or every 5 minutes until then)
            tickAt(maxOf(now + 60_000, minOf(leave - 3 * 60_000, now + 5 * 60_000)))
        }

        // Wake at the next range start of any stop with the alert on
        routes.filter { it.alertOn }
            .mapNotNull { WidgetRoutes.nextStart(it, nowLdt) }
            .minOrNull()?.let { tickAt(toMs(it)) }

        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val fire = firePending(ctx, fireRoute, fireBus)
        if (fireAt != null) setAlarmClock(ctx, am, fireAt!!, fire) else am.cancel(fire)
        val tick = tickPending(ctx)
        if (nextTick != null) setExact(am, nextTick!!, tick) else am.cancel(tick)
    }

    /** Fire a sample alarm in 5 seconds so you can hear what it sounds like. */
    fun test(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            ctx, REQ_TEST, Intent(ctx, AlertReceiver::class.java).setAction(ACTION_FIRE).putExtra(EXTRA_TEST, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        setAlarmClock(ctx, am, System.currentTimeMillis() + 5_000, pi)
    }

    private fun tickPending(ctx: Context) = PendingIntent.getBroadcast(
        ctx, REQ_TICK, Intent(ctx, AlertReceiver::class.java).setAction(ACTION_TICK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun firePending(ctx: Context, r: Route?, bus: Arrival?) = PendingIntent.getBroadcast(
        ctx, REQ_FIRE,
        Intent(ctx, AlertReceiver::class.java).setAction(ACTION_FIRE)
            .putExtra(EXTRA_ROUTE, r?.id).putExtra(EXTRA_BUS, bus?.service).putExtra(EXTRA_BUS_MS, bus?.timeMs ?: 0L),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun canExact(am: AlarmManager) = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()

    /** Alarm-clock alarms ring on time even in deep sleep; the clock icon shows in the status bar. */
    private fun setAlarmClock(ctx: Context, am: AlarmManager, at: Long, pi: PendingIntent) {
        if (!canExact(am)) { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi); return }
        val show = PendingIntent.getActivity(
            ctx, 9104, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        try { am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi) }
        catch (e: SecurityException) { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
    }

    private fun setExact(am: AlarmManager, at: Long, pi: PendingIntent) {
        try {
            if (canExact(am)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
    }

    /** Re-plan now on a background thread (after saving a stop, toggling an alert, opening the app). */
    fun rescheduleAsync(ctx: Context, fetch: Boolean = false) {
        val app = ctx.applicationContext
        Thread { try { tick(app, fetch) } catch (e: Exception) { } }.start()
    }
}

/** Receives ticks, leave alarms, the test alarm, and boot/update events. */
class AlertReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                when (intent.action) {
                    AlertEngine.ACTION_FIRE -> {
                        if (intent.getBooleanExtra(AlertEngine.EXTRA_TEST, false)) {
                            Notifier.testAlarm(ctx)
                        } else {
                            val id = intent.getStringExtra(AlertEngine.EXTRA_ROUTE)
                            val r = Store.routes(ctx).firstOrNull { it.id == id }
                            if (r != null && r.alertOn && !Store.alertedToday(ctx, r.id)) {
                                Notifier.leaveNow(ctx, r, intent.getLongExtra(AlertEngine.EXTRA_BUS_MS, 0),
                                    intent.getStringExtra(AlertEngine.EXTRA_BUS) ?: "")
                                Store.markAlerted(ctx, r.id)
                            }
                            AlertEngine.tick(ctx, fetch = false)
                        }
                    }
                    else -> AlertEngine.tick(ctx, fetch = true) // tick, boot, app updated, time changed
                }
                BusWidget.updateAll(ctx)
            } catch (e: Exception) {
            } finally {
                pending.finish()
            }
        }.start()
    }
}
