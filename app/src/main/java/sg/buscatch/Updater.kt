package sg.buscatch

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-update from GitHub Releases. Android requires the user to confirm every install
 * of an app outside the Play Store, so this gets as close to "hot update" as allowed:
 * the app finds the new version, downloads it, and opens the installer.
 */
object Updater {
    private const val LATEST = "https://api.github.com/repos/htobenothing/bus-catch-andriod/releases/latest"
    private const val PREFS = "buscatch_update"
    private const val CHANNEL = "updates"
    private const val NOTIF_ID = 4242

    data class Release(val version: String, val apkUrl: String, val notes: String)

    val current: String get() = BuildConfig.VERSION_NAME

    /** True if semantic version [a] is newer than [b] ("1.4.0" > "1.3.2"). */
    fun isNewer(a: String, b: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").substringBefore("-").split(".").map { it.toIntOrNull() ?: 0 }
        val x = parts(a); val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    /** Blocking. Latest published release, or null if none or offline. */
    fun fetchLatest(): Release? = try {
        val conn = URL(LATEST).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000; conn.readTimeout = 10_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "BusCatch-Android/$current")
        val body = try {
            if (conn.responseCode != 200) null else conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
        body?.let {
            val o = JSONObject(it)
            val tag = o.optString("tag_name")
            val assets = o.optJSONArray("assets")
            var url: String? = null
            if (assets != null) for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) { url = a.optString("browser_download_url"); break }
            }
            // Only semver tags (vX.Y.Z) count as releases
            if (url == null || !Regex("v?\\d+\\.\\d+\\.\\d+.*").matches(tag)) null
            else Release(tag.removePrefix("v"), url, o.optString("body").substringBefore("\n---").trim())
        }
    } catch (e: Exception) { null }

    /** Blocking. Returns a release newer than this app, remembering the result. */
    fun check(ctx: Context): Release? {
        val r = fetchLatest()
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("checked", System.currentTimeMillis())
        if (r != null && isNewer(r.version, current)) {
            p.putString("version", r.version).putString("url", r.apkUrl).putString("notes", r.notes).apply()
            return r
        }
        p.remove("version").remove("url").remove("notes").apply()
        return null
    }

    /** Last known newer release without touching the network. */
    fun known(ctx: Context): Release? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val v = p.getString("version", null) ?: return null
        val u = p.getString("url", null) ?: return null
        return if (isNewer(v, current)) Release(v, u, p.getString("notes", "") ?: "") else null
    }

    fun lastChecked(ctx: Context): Long =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("checked", 0)

    /** Called from the background worker: check at most once a day and notify once per version. */
    fun dailyCheck(ctx: Context) {
        if (System.currentTimeMillis() - lastChecked(ctx) < 24 * 3600_000L) return
        val r = check(ctx) ?: return
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.getString("notified", null) == r.version) return
        p.edit().putString("notified", r.version).apply()
        notify(ctx, r)
    }

    private fun notify(ctx: Context, r: Release) {
        if (!Notifier.canNotify(ctx)) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(
            ctx, 7, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        nm.notify(NOTIF_ID, Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_bus)
            .setContentTitle("Bus Pup ${r.version} is available")
            .setContentText("Tap to update. Your stops and widgets stay.")
            .setContentIntent(open).setAutoCancel(true).build())
    }

    /** Blocking. Downloads the APK into the app's cache, reporting progress 0–100. */
    fun download(ctx: Context, r: Release, onProgress: (Int) -> Unit): File {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "BusCatch-${r.version}.apk")
        var url = URL(r.apkUrl)
        // GitHub redirects release downloads to its file host; follow manually to be safe
        var conn: HttpURLConnection
        var hops = 0
        while (true) {
            conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000; conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", "BusCatch-Android/$current")
            val code = conn.responseCode
            if (code in 300..399 && hops++ < 5) {
                url = URL(url, conn.getHeaderField("Location")); conn.disconnect(); continue
            }
            if (code != 200) { conn.disconnect(); throw RuntimeException("Download failed (HTTP $code)") }
            break
        }
        try {
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(32 * 1024)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != last) { last = pct; onProgress(pct) }
                        }
                    }
                }
            }
        } finally { conn.disconnect() }
        return out
    }

    /** Opens Android's installer. Returns false if the user first has to allow installs from this app. */
    fun install(activity: Activity, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
            )
            return false
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", apk)
        activity.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }
}
