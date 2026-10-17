package sg.buscatch

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Fetches live arrivals for every saved stop, refreshes the widget, and handles leave alerts.
 *
 * Refresh rhythm:
 *  - every 15 min in the background (Android's minimum for periodic work)
 *  - every ~1 min while any alert-on route is inside (or 15 min before) its alert window
 */
class RefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        refreshNow(applicationContext)
        return Result.success()
    }

    companion object {
        private const val PERIODIC = "buscatch-periodic"
        private const val FAST = "buscatch-fast"
        private const val NOW = "buscatch-now"
        private const val WINDOW_LEAD_MIN = 15L

        private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedulePeriodic(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES)
                .setConstraints(net).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        /** Refresh as soon as possible (widget button, app open, route saved). */
        fun refreshSoon(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<RefreshWorker>().setConstraints(net).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, req)
        }

        private fun scheduleFast(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<RefreshWorker>()
                .setInitialDelay(60, TimeUnit.SECONDS)
                .setConstraints(net).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(FAST, ExistingWorkPolicy.REPLACE, req)
        }

        /** Does the actual work. Safe to call from any background coroutine. */
        suspend fun refreshNow(ctx: Context) = withContext(Dispatchers.IO) {
            val routes = Store.routes(ctx)
            var allOk = true
            routes.map { it.stopCode }.distinct().forEach { code ->
                try {
                    val raw = Api.fetchRaw(code)
                    Api.parse(raw) // validate before caching
                    Store.cacheStop(ctx, code, raw)
                } catch (e: Exception) {
                    allOk = false
                }
            }
            if (routes.isNotEmpty()) Store.setLastRefresh(ctx, allOk)

            handleAlerts(ctx, routes)
            Updater.dailyCheck(ctx)
            BusWidget.updateAll(ctx)

            if (routes.any { it.alertOn && it.inWindow(leadMin = WINDOW_LEAD_MIN) && !Store.alertedToday(ctx, it.id) }) {
                scheduleFast(ctx)
            }
            Unit
        }

        private fun handleAlerts(ctx: Context, routes: List<Route>) {
            val now = System.currentTimeMillis()
            for (r in routes) {
                if (!r.alertOn || !r.inWindow() || Store.alertedToday(ctx, r.id)) {
                    cancelAlarm(ctx, r)
                    continue
                }
                val plan = Planner.plan(r, Store.cachedStop(ctx, r.stopCode)) ?: continue
                val leaveAt = plan.leaveAtMs ?: continue
                val bus = plan.target ?: continue
                if (leaveAt - now <= 45_000) {
                    Notifier.leaveNow(ctx, r, bus.timeMs, bus.service)
                    Store.markAlerted(ctx, r.id)
                    cancelAlarm(ctx, r)
                } else {
                    setAlarm(ctx, r, leaveAt, bus.timeMs, bus.service)
                }
            }
        }

        private fun alarmIntent(ctx: Context, r: Route, busMs: Long = 0, bus: String = ""): PendingIntent {
            val i = Intent(ctx, LeaveAlarmReceiver::class.java)
                .putExtra(LeaveAlarmReceiver.EXTRA_ROUTE, r.id)
                .putExtra(LeaveAlarmReceiver.EXTRA_BUS_MS, busMs)
                .putExtra(LeaveAlarmReceiver.EXTRA_BUS, bus)
            return PendingIntent.getBroadcast(
                ctx, r.id.hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun setAlarm(ctx: Context, r: Route, atMs: Long, busMs: Long, bus: String) {
            val am = ctx.getSystemService(AlarmManager::class.java) ?: return
            val pi = alarmIntent(ctx, r, busMs, bus)
            val exactOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
            try {
                if (exactOk) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
                else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
            } catch (e: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
            }
        }

        private fun cancelAlarm(ctx: Context, r: Route) {
            ctx.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent(ctx, r))
        }
    }
}
