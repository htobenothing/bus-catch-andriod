package sg.buscatch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/**
 * Live updates during alert ranges. Runs as a quiet foreground service (Android requires the
 * "Live" notification) and, while the screen is on, refreshes bus times every 10 seconds so the
 * widgets and the notification stay current. Pauses while the screen is off and stops by
 * itself when no range is open.
 */
class LiveService : Service() {
    private lateinit var thread: HandlerThread
    private lateinit var worker: Handler
    private val screen = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_SCREEN_ON || i.action == Intent.ACTION_USER_PRESENT) {
                worker.removeCallbacks(loop); worker.post(loop) // catch up straight away
            }
        }
    }
    private val loop = object : Runnable {
        override fun run() {
            if (!step()) { stopSelf(); return }
            val pm = getSystemService(PowerManager::class.java)
            // Screen off: wait for SCREEN_ON (with a slow safety re-check every 5 minutes)
            worker.postDelayed(this, if (pm?.isInteractive == true) INTERVAL_MS else 5 * 60_000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        thread = HandlerThread("bus-pup-live").apply { start() }
        worker = Handler(thread.looper)
        val f = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screen, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(screen, f)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE) {
            Store.setLivePausedUntil(this, openRangesEnd(this) ?: System.currentTimeMillis())
            stopSelf(); return START_NOT_STICKY
        }
        val n = notification(this, "Live bus times", "Starting…")
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIF_ID, n)
        if (!looping) { looping = true; worker.post(loop) } // already running: just keep going
        return START_NOT_STICKY
    }

    private var looping = false

    override fun onDestroy() {
        running = false
        try { unregisterReceiver(screen) } catch (e: Exception) { }
        worker.removeCallbacksAndMessages(null)
        thread.quitSafely()
        super.onDestroy()
    }

    /** One refresh. Returns false when live updates should stop. */
    private fun step(): Boolean {
        val open = liveRoutes(this)
        if (open.isEmpty()) return false
        val pm = getSystemService(PowerManager::class.java)
        if (pm?.isInteractive != false) {
            var ok = true
            open.map { it.stopCode }.distinct().forEach { code ->
                try { val raw = Api.fetchRaw(code); Api.parse(raw); Store.cacheStop(this, code, raw) } catch (e: Exception) { ok = false }
            }
            Store.setLastRefresh(this, ok)
            Weather.update(this)
            BusWidget.updateAll(this)
            AlertEngine.tick(this, fetch = false) // keep the leave alarm on the latest times
        }
        val (title, body) = summary(open)
        getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, notification(this, title, body))
        return true
    }

    private fun summary(open: List<Route>): Pair<String, String> {
        val now = System.currentTimeMillis()
        val r = WidgetRoutes.nearest(this, open) ?: open.first()
        val plan = Planner.plan(r, Store.cachedStop(this, r.stopCode), now)
        val hm = SimpleDateFormat("HH:mm", Locale.getDefault())
        val next = plan?.upcoming?.take(3)?.joinToString("  ") {
            val m = ((it.timeMs - now) / 60_000).coerceAtLeast(0)
            "${it.service} ${if (m == 0L) "Arr" else "${m}m"}"
        }.orEmpty()
        val leave = plan?.leaveAtMs
        val title = when {
            plan == null -> "${r.displayName}: loading…"
            plan.upcoming.isEmpty() -> "${r.displayName}: no buses coming"
            leave == null -> "${r.displayName}: too late for the next bus"
            leave <= now + 60_000 -> "Leave NOW · ${plan.target?.service} at ${hm.format(Date(plan.target!!.timeMs))}"
            else -> "Leave at ${hm.format(Date(leave))} · ${plan.target?.service}"
        }
        return title to (if (next.isNotEmpty()) "${r.displayName} · $next" else r.displayName)
    }

    companion object {
        const val INTERVAL_MS = 10_000L
        @Volatile private var running = false
        const val NOTIF_ID = 5050
        private const val CHANNEL = "live"
        private const val ACTION_PAUSE = "sg.buscatch.LIVE_PAUSE"

        /** Stops whose alert range is open now (alert on or off, like Auto mode). */
        fun liveRoutes(ctx: Context): List<Route> {
            if (!Store.liveEnabled(ctx) || System.currentTimeMillis() < Store.livePausedUntil(ctx)) return emptyList()
            val now = LocalDateTime.now()
            return Store.routes(ctx).filter { WidgetRoutes.openNow(it, now) }
        }

        private fun openRangesEnd(ctx: Context): Long? {
            val now = LocalDateTime.now()
            return Store.routes(ctx).mapNotNull { WidgetRoutes.currentRangeEnd(it, now) }.maxOrNull()
                ?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
        }

        /** Start live updates if a range is open; safe to call often. */
        fun startIfNeeded(ctx: Context) {
            if (running || liveRoutes(ctx).isEmpty()) return
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(Intent(ctx, LiveService::class.java))
                else ctx.startService(Intent(ctx, LiveService::class.java))
            } catch (e: Exception) {
                // Android refused to start it from the background; the next alarm or opening the app retries
            }
        }

        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, LiveService::class.java)) }

        private fun notification(ctx: Context, title: String, body: String): Notification {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm?.getNotificationChannel(CHANNEL) == null) {
                nm?.createNotificationChannel(NotificationChannel(CHANNEL, "Live bus times", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shows while bus times update every 10 seconds during your alert ranges"
                    setShowBadge(false)
                })
            }
            val open = PendingIntent.getActivity(ctx, 5051, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val pause = PendingIntent.getService(ctx, 5052, Intent(ctx, LiveService::class.java).setAction(ACTION_PAUSE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_bus)
                .setContentTitle(title)
                .setContentText(body)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .addAction(Notification.Action.Builder(null, "Pause until range ends", pause).build())
                .build()
        }
    }
}
