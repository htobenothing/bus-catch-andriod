package sg.buscatch

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Notifier {
    private const val CHANNEL = "leave_now"

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) != null) return
        val ch = NotificationChannel(CHANNEL, "Time to leave", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Tells you when to leave home to catch your bus"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 400)
        }
        nm.createNotificationChannel(ch)
    }

    fun canNotify(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun leaveNow(ctx: Context, r: Route, busMs: Long, bus: String = "") {
        if (!canNotify(ctx)) return
        ensureChannel(ctx)
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val time = if (busMs > 0) SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(busMs)) else null
        val mins = if (busMs > 0) ((busMs - System.currentTimeMillis()) / 60_000).coerceAtLeast(0) else null
        val busNo = bus.ifBlank { r.serviceLabel }
        val body = buildString {
            append("Bus $busNo ")
            if (time != null) append("arrives $time (in $mins min) ")
            append("at ${r.displayName}. ${r.walkMin} min walk.")
        }
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_bus)
            .setContentTitle("Leave now for bus $busNo")
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java)?.notify(r.id.hashCode(), n)
    }
}
