package sg.buscatch

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The "leave now" alert, alarm-style: rings at alarm volume (plays even on silent),
 * repeats and vibrates until you tap "I'm going" or 2 minutes pass, and shows a
 * full-screen page on the lock screen.
 */
object Notifier {
    private const val CHANNEL = "leave_alarm"
    private const val OLD_CHANNEL = "leave_now"
    const val ACTION_DISMISS = "sg.buscatch.DISMISS_ALERT"
    const val EXTRA_ID = "notif_id"
    const val EXTRA_TITLE = "title"
    const val EXTRA_BODY = "body"
    const val EXTRA_ROUTE_ID = "route_id"
    const val ACTION_GOING = "sg.buscatch.IM_GOING"
    const val TEST_ID = 4343

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.getNotificationChannel(OLD_CHANNEL)?.let { nm.deleteNotificationChannel(OLD_CHANNEL) }
        if (nm.getNotificationChannel(CHANNEL) != null) return
        val ch = NotificationChannel(CHANNEL, "Time to leave (alarm)", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Rings like an alarm when it's time to leave for your bus"
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 700, 400, 700, 400, 700)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
        }
        nm.createNotificationChannel(ch)
    }

    fun canNotify(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun leaveNow(ctx: Context, r: Route, busMs: Long, bus: String = "") {
        val busNo = bus.ifBlank { r.serviceLabel }
        val time = if (busMs > 0) SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(busMs)) else null
        val mins = if (busMs > 0) ((busMs - System.currentTimeMillis()) / 60_000).coerceAtLeast(0) else null
        val body = buildString {
            append("Bus $busNo ")
            if (time != null) append("arrives $time (in $mins min) ")
            append("at ${r.displayName}. ${r.walkMin} min walk.")
        }
        val title = (if (Theme.of(ctx).puppy) "🐶 Woof! " else "") + "Leave now for bus $busNo"
        Rewards.onAlert(ctx)
        alarm(ctx, r.id.hashCode(), title, body, r.id)
    }

    fun testAlarm(ctx: Context) =
        alarm(ctx, TEST_ID, (if (Theme.of(ctx).puppy) "🐶 Woof! " else "") + "Test alert",
            "This is how Bus Pup tells you to leave. Tap I'm going to stop it.")

    private fun alarm(ctx: Context, id: Int, title: String, body: String, routeId: String? = null) {
        if (!canNotify(ctx)) return
        ensureChannel(ctx)
        val page = Intent(ctx, AlarmActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_ID, id).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_BODY, body).putExtra(EXTRA_ROUTE_ID, routeId)
        val full = PendingIntent.getActivity(ctx, id, page, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val dismiss = PendingIntent.getBroadcast(
            ctx, id, Intent(ctx, DismissReceiver::class.java).setAction(ACTION_DISMISS).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // "I'm going" stops the ringing and earns the puppy a treat
        val going = PendingIntent.getBroadcast(
            ctx, id + 1, Intent(ctx, DismissReceiver::class.java).setAction(ACTION_GOING)
                .putExtra(EXTRA_ID, id).putExtra(EXTRA_ROUTE_ID, routeId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_bus)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(full)
            .setFullScreenIntent(full, true)
            .setDeleteIntent(dismiss)
            .addAction(Notification.Action.Builder(null, "I'm going", going).build())
            .setAutoCancel(true)
            .setTimeoutAfter(2 * 60_000L)
            .build()
        n.flags = n.flags or Notification.FLAG_INSISTENT // repeat the sound until dismissed
        ctx.getSystemService(NotificationManager::class.java)?.notify(id, n)
    }

    fun dismiss(ctx: Context, id: Int) {
        ctx.getSystemService(NotificationManager::class.java)?.cancel(id)
    }
}

/** "I'm going" on the notification stops the ringing. */
class DismissReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Notifier.dismiss(ctx, intent.getIntExtra(Notifier.EXTRA_ID, 0))
        if (intent.action == Notifier.ACTION_GOING) {
            Rewards.onGoing(ctx, intent.getStringExtra(Notifier.EXTRA_ROUTE_ID))?.let {
                android.widget.Toast.makeText(ctx, it, android.widget.Toast.LENGTH_LONG).show()
            }
            BusWidget.updateAll(ctx)
        }
    }
}
