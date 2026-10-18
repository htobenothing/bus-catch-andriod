package sg.buscatch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/**
 * Puppy rewards. Tapping "I'm going" on a leave alert earns a treat (once per stop per day).
 * A streak counts days in a row where you went when alerted; a day with an alert you didn't
 * act on resets it, days without alerts don't. Treats and streaks unlock accessories.
 */
object Rewards {
    enum class Acc(val title: String, val emoji: String, val res: Int, val need: String) {
        SCARF("Scarf", "🧣", R.drawable.acc_scarf, "3 treats"),
        GLASSES("Sunglasses", "🕶️", R.drawable.acc_glasses, "5-day streak"),
        HAT("Party hat", "🥳", R.drawable.acc_hat, "10 treats"),
        RAINCOAT("Raincoat", "🌧️", R.drawable.acc_raincoat, "15 treats · worn when rain is forecast"),
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("buscatch_rewards", Context.MODE_PRIVATE)

    fun treats(ctx: Context) = prefs(ctx).getInt("treats", 0)
    fun streak(ctx: Context) = prefs(ctx).getInt("streak", 0)
    fun bestStreak(ctx: Context) = prefs(ctx).getInt("best", 0)

    fun unlocked(ctx: Context, a: Acc): Boolean = when (a) {
        Acc.SCARF -> treats(ctx) >= 3
        Acc.GLASSES -> bestStreak(ctx) >= 5
        Acc.HAT -> treats(ctx) >= 10
        Acc.RAINCOAT -> treats(ctx) >= 15
    }

    /** Accessories the user chose to wear (newly unlocked ones are worn automatically). */
    fun wearing(ctx: Context, a: Acc): Boolean = unlocked(ctx, a) && prefs(ctx).getBoolean("wear_${a.name}", true)
    fun setWearing(ctx: Context, a: Acc, on: Boolean) { prefs(ctx).edit().putBoolean("wear_${a.name}", on).apply() }

    /** What's next to unlock, for the progress line. */
    fun nextGoal(ctx: Context): String? {
        val t = treats(ctx)
        return when {
            t < 3 -> "${3 - t} more treat${if (3 - t == 1) "" else "s"} for a 🧣 scarf"
            bestStreak(ctx) < 5 -> "${5 - streak(ctx)}-day streak for 🕶️ sunglasses"
            t < 10 -> "${10 - t} more treats for a 🥳 party hat"
            t < 15 -> "${15 - t} more treats for a 🌧️ raincoat"
            else -> null
        }
    }

    /** A real leave alert went off today. Resets the streak if the last alert day had no treat. */
    fun onAlert(ctx: Context) {
        val p = prefs(ctx)
        val today = LocalDate.now().toString()
        val lastAlert = p.getString("last_alert_day", null)
        if (lastAlert != null && lastAlert != today && p.getString("last_treat_day", null) != lastAlert) {
            p.edit().putInt("streak", 0).apply()
        }
        p.edit().putString("last_alert_day", today).apply()
    }

    /**
     * "I'm going" tapped for [routeId]. Returns a message to show, or null if this stop
     * already earned today's treat.
     */
    fun onGoing(ctx: Context, routeId: String?): String? {
        if (routeId == null) return null
        val p = prefs(ctx)
        val today = LocalDate.now().toString()
        if (p.getString("treat_$routeId", null) == today) return null
        val before = Acc.values().filter { unlocked(ctx, it) }.toSet()
        val e = p.edit().putString("treat_$routeId", today).putInt("treats", treats(ctx) + 1)
        if (p.getString("last_treat_day", null) != today) {
            val s = streak(ctx) + 1
            e.putInt("streak", s).putInt("best", maxOf(bestStreak(ctx), s)).putString("last_treat_day", today)
        }
        e.apply()
        val fresh = Acc.values().filter { unlocked(ctx, it) && it !in before }
        return buildString {
            append("+1 🦴 treat! ${treats(ctx)} so far")
            if (streak(ctx) > 1) append(" · 🔥 ${streak(ctx)}-day streak")
            fresh.forEach { append("\nUnlocked: ${it.emoji} ${it.title}!") }
        }
    }

    /** The puppy face with whatever it's wearing right now. */
    fun puppyBitmap(ctx: Context, sizePx: Int): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val layers = mutableListOf(R.drawable.ic_dog)
        val rain = wearing(ctx, Acc.RAINCOAT) && Weather.raining(ctx)
        if (wearing(ctx, Acc.SCARF) && !rain) layers += R.drawable.acc_scarf
        if (wearing(ctx, Acc.GLASSES)) layers += R.drawable.acc_glasses
        if (wearing(ctx, Acc.HAT) && !rain) layers += R.drawable.acc_hat
        if (rain) layers += R.drawable.acc_raincoat
        layers.forEach { res -> ctx.getDrawable(res)?.apply { setBounds(0, 0, sizePx, sizePx); draw(c) } }
        return bmp
    }
}

/** Is rain forecast near your stop? NEA 2-hour forecast, checked at most every 30 minutes. */
object Weather {
    private const val URL_2H = "https://api-open.data.gov.sg/v2/real-time/api/two-hr-forecast"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("buscatch_rewards", Context.MODE_PRIVATE)

    fun raining(ctx: Context): Boolean {
        val p = prefs(ctx)
        return p.getBoolean("rain", false) && System.currentTimeMillis() - p.getLong("rain_at", 0) < 3 * 3600_000
    }

    /** Blocking; call off the main thread. Only fetches when the last check is over 30 minutes old. */
    fun update(ctx: Context) {
        val p = prefs(ctx)
        if (System.currentTimeMillis() - p.getLong("rain_at", 0) < 30 * 60_000) return
        if (!Rewards.unlocked(ctx, Rewards.Acc.RAINCOAT)) return // nothing to dress for yet
        val r = WidgetRoutes.nearest(ctx, Store.routes(ctx)) ?: Store.routes(ctx).firstOrNull() ?: return
        val stop = Stops.byCode(ctx, r.stopCode) ?: return
        try {
            val c = URL(URL_2H).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 10_000
            val body = try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
            val data = JSONObject(body).getJSONObject("data")
            val areas = data.getJSONArray("area_metadata")
            var best = ""; var bestD = Int.MAX_VALUE
            for (i in 0 until areas.length()) {
                val a = areas.getJSONObject(i); val loc = a.getJSONObject("label_location")
                val d = Stops.metres(stop.lat, stop.lng, loc.getDouble("latitude"), loc.getDouble("longitude"))
                if (d < bestD) { bestD = d; best = a.getString("name") }
            }
            val fc = data.getJSONArray("items").getJSONObject(0).getJSONArray("forecasts")
            var text = ""
            for (i in 0 until fc.length()) fc.getJSONObject(i).let { if (it.getString("area") == best) text = it.getString("forecast") }
            val rain = Regex("rain|shower|thunder|drizzle", RegexOption.IGNORE_CASE).containsMatchIn(text)
            p.edit().putBoolean("rain", rain).putLong("rain_at", System.currentTimeMillis()).putString("forecast", "$best: $text").apply()
        } catch (e: Exception) { }
    }

    fun forecast(ctx: Context): String? = prefs(ctx).getString("forecast", null)
}
