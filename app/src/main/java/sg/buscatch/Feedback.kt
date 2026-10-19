package sg.buscatch

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.PrintWriter
import java.io.StringWriter
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.util.UUID

/**
 * Feedback goes to this app's GitHub repo as issues, so it all lands in one place you already use.
 * The token (repo secret FEEDBACK_TOKEN) is added at build time and can only create issues
 * and comments in this one repo. Anything that can't be sent right away is queued and retried.
 */
object Feedback {
    private const val REPO = "htobenothing/bus-catch-andriod"
    private const val RATINGS_ISSUE = 1
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("buscatch_feedback", Context.MODE_PRIVATE)

    val enabled get() = BuildConfig.FEEDBACK_TOKEN.isNotBlank()

    /** Short anonymous id so you can tell reports from the same phone apart. No personal data. */
    private fun installId(ctx: Context): String = prefs(ctx).getString("id", null)
        ?: UUID.randomUUID().toString().take(6).also { prefs(ctx).edit().putString("id", it).apply() }

    fun deviceLine(ctx: Context) =
        "Bus Pup v${BuildConfig.VERSION_NAME} · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · " +
            "${if (Theme.of(ctx).puppy) "Puppy" else "Classic"} theme · id ${installId(ctx)}"

    // ---------- sending (blocking; call off the main thread) ----------

    /** New issue. Returns true if sent, false if queued for later. */
    fun sendIssue(ctx: Context, title: String, body: String, labels: List<String>): Boolean {
        val json = JSONObject().put("title", title).put("body", body).put("labels", JSONArray(labels + "from-app"))
        return post(ctx, "https://api.github.com/repos/$REPO/issues", json)
    }

    /** One alert-timing answer, as a comment on the ratings issue. */
    fun sendRating(ctx: Context, text: String): Boolean =
        post(ctx, "https://api.github.com/repos/$REPO/issues/$RATINGS_ISSUE/comments", JSONObject().put("body", text))

    private fun post(ctx: Context, url: String, json: JSONObject): Boolean {
        if (!enabled) return false
        val ok = try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true
            c.connectTimeout = 10_000; c.readTimeout = 15_000
            c.setRequestProperty("Authorization", "Bearer ${BuildConfig.FEEDBACK_TOKEN}")
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "BusPup-Android")
            c.setRequestProperty("Content-Type", "application/json")
            try {
                c.outputStream.use { it.write(json.toString().toByteArray()) }
                c.responseCode in 200..299
            } finally { c.disconnect() }
        } catch (e: Exception) { false }
        if (!ok) queue(ctx, url, json)
        return ok
    }

    private fun queue(ctx: Context, url: String, json: JSONObject) {
        val arr = JSONArray(prefs(ctx).getString("queue", "[]"))
        if (arr.length() >= 20) return // don't pile up forever
        arr.put(JSONObject().put("url", url).put("json", json))
        prefs(ctx).edit().putString("queue", arr.toString()).apply()
    }

    /** Retry anything that couldn't be sent earlier. */
    fun flush(ctx: Context) {
        if (!enabled) return
        val arr = JSONArray(prefs(ctx).getString("queue", "[]"))
        if (arr.length() == 0) return
        prefs(ctx).edit().putString("queue", "[]").apply()
        for (i in 0 until arr.length()) arr.getJSONObject(i).let { post(ctx, it.getString("url"), it.getJSONObject("json")) }
    }

    // ---------- "Was the alert on time?" ----------

    private const val CHANNEL = "ask"
    private const val ASK_ID = 6060
    const val ACTION_RATE = "sg.buscatch.RATE"
    const val EXTRA_ANSWER = "answer"
    const val EXTRA_ROUTE = "route"

    /** After a trip finishes, ask once a day how the timing was (only if feedback is set up). */
    fun askAboutTrip(ctx: Context, routeId: String) {
        if (!enabled || !Notifier.canNotify(ctx)) return
        val today = LocalDate.now().toString()
        if (prefs(ctx).getString("asked", null) == today) return
        prefs(ctx).edit().putString("asked", today).apply()
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Quick questions", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Occasional one-tap questions to make the alerts better"
            })
        }
        fun answer(code: String, label: String, req: Int) = Notification.Action.Builder(null, label,
            PendingIntent.getBroadcast(ctx, req, Intent(ctx, RatingReceiver::class.java).setAction(ACTION_RATE)
                .putExtra(EXTRA_ANSWER, code).putExtra(EXTRA_ROUTE, routeId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()
        nm.notify(ASK_ID, Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_bus)
            .setContentTitle((if (Theme.of(ctx).puppy) "🐶 " else "") + "Was the alert on time?")
            .setContentText("One tap helps tune your leave times.")
            .addAction(answer("ontime", "👍 On time", 6061))
            .addAction(answer("early", "⏰ Too early", 6062))
            .addAction(answer("late", "🏃 Too late", 6063))
            .setAutoCancel(true)
            .setTimeoutAfter(3 * 3600_000L)
            .build())
    }

    fun dismissAsk(ctx: Context) { ctx.getSystemService(NotificationManager::class.java)?.cancel(ASK_ID) }

    // ---------- crash reports ----------

    /** Save any crash so the app can offer to send it next time it opens. */
    fun installCrashCatcher(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
                prefs(app).edit().putString("crash", "Thread: ${t.name}\n\n${sw.toString().take(6000)}").commit()
            } catch (x: Exception) { }
            previous?.uncaughtException(t, e)
        }
    }

    fun pendingCrash(ctx: Context): String? = prefs(ctx).getString("crash", null)
    fun clearCrash(ctx: Context) { prefs(ctx).edit().remove("crash").apply() }
}

/** Handles the 👍 / ⏰ / 🏃 taps. */
class RatingReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Feedback.dismissAsk(ctx)
        val answer = intent.getStringExtra(Feedback.EXTRA_ANSWER) ?: return
        val r = Store.routes(ctx).firstOrNull { it.id == intent.getStringExtra(Feedback.EXTRA_ROUTE) }
        val face = when (answer) { "ontime" -> "👍 On time"; "early" -> "⏰ Too early"; else -> "🏃 Too late" }
        val text = buildString {
            append("**$face**")
            if (r != null) append(" · bus ${r.serviceLabel} · walk ${r.walkMin} + buffer ${r.bufferMin} min")
            append("\n\n<sub>${Feedback.deviceLine(ctx)}</sub>")
        }
        android.widget.Toast.makeText(ctx, "Thanks! 🐾", android.widget.Toast.LENGTH_SHORT).show()
        val pending = goAsync()
        Thread { try { Feedback.sendRating(ctx, text) } finally { pending.finish() } }.start()
    }
}

/** App entry point: only used to catch crashes for reporting. */
class BusPupApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Feedback.installCrashCatcher(this)
    }
}
