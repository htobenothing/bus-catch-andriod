package sg.buscatch

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDate

/**
 * Feedback without tokens or extra services:
 *  - messages and crash reports go out through the phone's share sheet (WhatsApp, Telegram, email…),
 *    or as a pre-filled GitHub issue page for people with a GitHub account
 *  - "was the alert on time?" answers stay on the phone and suggest walk/buffer tweaks
 */
object Feedback {
    private const val REPO_URL = "https://github.com/htobenothing/bus-catch-andriod"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("buscatch_feedback", Context.MODE_PRIVATE)

    fun deviceLine(ctx: Context) =
        "Bus Pup v${BuildConfig.VERSION_NAME} · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · " +
            "${if (Theme.of(ctx).puppy) "Puppy" else "Classic"} theme"

    /** Open the share sheet so the person picks who to send it to. */
    fun share(activity: Activity, subject: String, text: String) {
        activity.startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, subject)
                .putExtra(Intent.EXTRA_TEXT, text),
            "Send feedback with…"
        ))
    }

    /** Open a pre-filled "new issue" page on GitHub (needs a GitHub account to submit). */
    fun openGitHubIssue(activity: Activity, title: String, body: String, label: String) {
        val url = "$REPO_URL/issues/new?title=${Uri.encode(title)}&body=${Uri.encode(body)}&labels=${Uri.encode(label)}"
        try { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: Exception) { }
    }

    // ---------- "Was the alert on time?" (stays on the phone) ----------

    private const val CHANNEL = "ask"
    private const val ASK_ID = 6060
    const val ACTION_RATE = "sg.buscatch.RATE"
    const val EXTRA_ANSWER = "answer"
    const val EXTRA_ROUTE = "route"

    /** After a trip finishes, ask once a day how the timing was. */
    fun askAboutTrip(ctx: Context, routeId: String) {
        if (!Notifier.canNotify(ctx)) return
        val today = LocalDate.now().toString()
        if (prefs(ctx).getString("asked", null) == today) return
        prefs(ctx).edit().putString("asked", today).apply()
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Quick questions", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Occasional one-tap questions that help tune your leave times"
            })
        }
        fun answer(code: String, label: String, req: Int) = Notification.Action.Builder(null, label,
            PendingIntent.getBroadcast(ctx, req, Intent(ctx, RatingReceiver::class.java).setAction(ACTION_RATE)
                .putExtra(EXTRA_ANSWER, code).putExtra(EXTRA_ROUTE, routeId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()
        nm.notify(ASK_ID, Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_bus)
            .setContentTitle((if (Theme.of(ctx).puppy) "🐶 " else "") + "Was the alert on time?")
            .setContentText("One tap helps tune your leave time.")
            .addAction(answer("ontime", "👍 On time", 6061))
            .addAction(answer("early", "⏰ Too early", 6062))
            .addAction(answer("late", "🏃 Too late", 6063))
            .setAutoCancel(true)
            .setTimeoutAfter(3 * 3600_000L)
            .build())
    }

    fun dismissAsk(ctx: Context) { ctx.getSystemService(NotificationManager::class.java)?.cancel(ASK_ID) }

    /** Keep the last 3 answers per stop. */
    fun recordAnswer(ctx: Context, routeId: String, answer: String) {
        val p = prefs(ctx)
        val last = (p.getString("answers_$routeId", "") ?: "").split(",").filter { it.isNotBlank() }
        p.edit().putString("answers_$routeId", (last + answer).takeLast(3).joinToString(",")).apply()
    }

    data class Tweak(val route: Route, val delta: Int, val reason: String)

    /** "Too late" twice in the last 3 → suggest +1 min buffer; "too early" twice → suggest −1. */
    fun suggestion(ctx: Context): Tweak? {
        for (r in Store.routes(ctx)) {
            val last = (prefs(ctx).getString("answers_${r.id}", "") ?: "").split(",")
            if (last.count { it == "late" } >= 2) return Tweak(r, +1, "You were too late twice recently")
            if (last.count { it == "early" } >= 2 && r.bufferMin > 0) return Tweak(r, -1, "You were too early twice recently")
        }
        return null
    }

    /** Apply or dismiss a suggestion; either way, start counting fresh for that stop. */
    fun resolve(ctx: Context, t: Tweak, apply: Boolean) {
        if (apply) Store.upsert(ctx, t.route.copy(bufferMin = (t.route.bufferMin + t.delta).coerceIn(0, 30)))
        prefs(ctx).edit().remove("answers_${t.route.id}").apply()
    }

    // ---------- crash reports ----------

    /** Save any crash so the app can offer to share it next time it opens. */
    fun installCrashCatcher(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
                prefs(app).edit().putString("crash", "Thread: ${t.name}\n\n${sw.toString().take(4000)}").commit()
            } catch (x: Exception) { }
            previous?.uncaughtException(t, e)
        }
    }

    fun pendingCrash(ctx: Context): String? = prefs(ctx).getString("crash", null)
    fun clearCrash(ctx: Context) { prefs(ctx).edit().remove("crash").apply() }
}

/** Handles the 👍 / ⏰ / 🏃 taps. Answers stay on the phone. */
class RatingReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Feedback.dismissAsk(ctx)
        val answer = intent.getStringExtra(Feedback.EXTRA_ANSWER) ?: return
        val routeId = intent.getStringExtra(Feedback.EXTRA_ROUTE) ?: return
        Feedback.recordAnswer(ctx, routeId, answer)
        val msg = if (Feedback.suggestion(ctx) != null) "Thanks! Open Bus Pup for a timing tip 🐾" else "Thanks! 🐾"
        android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
    }
}

/** App entry point: only used to catch crashes for reporting. */
class BusPupApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Feedback.installCrashCatcher(this)
    }
}
