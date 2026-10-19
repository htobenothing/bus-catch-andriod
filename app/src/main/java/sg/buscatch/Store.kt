package sg.buscatch

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime

/** One bus the user wants to catch at one stop. */
data class Route(
    val id: String,
    val stopCode: String,
    val service: String,      // one or more bus numbers, comma-separated: "961,190"
    val label: String,
    val walkMin: Int,
    val bufferMin: Int,
    val activeFrom: String,   // "HH:mm" — alerts only fire inside this window
    val activeTo: String,
    val days: String,         // 7 chars Mon..Sun, e.g. "1111100"; older saves used "all" | "weekdays" | "weekends"
    val alertOn: Boolean,
) {
    val needMin get() = walkMin + bufferMin
    val services: List<String> get() = splitServices(service)
    val serviceLabel: String get() = services.joinToString(" / ")
    val displayName get() = label.ifBlank { "Stop $stopCode" }

    /** Which days the alert range runs, Monday first. */
    val dayMask: BooleanArray get() = maskOf(days)

    fun dayOk(date: LocalDate = LocalDate.now()): Boolean = dayMask[date.dayOfWeek.value - 1]

    /** "Weekdays", "Every day", "Weekends", "Mon–Thu", "Mon, Wed, Fri"… */
    val daysLabel: String get() = labelOf(dayMask)

    /** True when [t] falls in the alert window, optionally widened by [leadMin] before it starts. */
    fun inWindow(t: LocalTime = LocalTime.now(), leadMin: Long = 0): Boolean {
        if (!dayOk()) return false
        val from = parseTime(activeFrom) ?: return true
        val to = parseTime(activeTo) ?: return true
        val start = from.minusMinutes(leadMin)
        return if (!from.isAfter(to)) {
            !t.isBefore(start) && !t.isAfter(to)
        } else { // window crosses midnight, e.g. 22:00–01:00
            !t.isBefore(start) || !t.isAfter(to)
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("stopCode", stopCode).put("service", service).put("label", label)
        .put("walkMin", walkMin).put("bufferMin", bufferMin)
        .put("activeFrom", activeFrom).put("activeTo", activeTo)
        .put("days", days).put("alertOn", alertOn)

    companion object {
        fun fromJson(o: JSONObject) = Route(
            id = o.getString("id"),
            stopCode = o.getString("stopCode"),
            service = o.getString("service"),
            label = o.optString("label", ""),
            walkMin = o.optInt("walkMin", 5),
            bufferMin = o.optInt("bufferMin", 1),
            activeFrom = o.optString("activeFrom", "07:00"),
            activeTo = o.optString("activeTo", "09:00"),
            days = maskString(maskOf(o.optString("days", WEEKDAYS))), // older saves become a day mask
            alertOn = o.optBoolean("alertOn", true),
        )

        fun splitServices(s: String): List<String> =
            s.split(Regex("[,/\\s]+")).map { it.trim().uppercase() }.filter { it.isNotEmpty() }.distinct()

        val DAY_SHORT = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        val DAY_LETTER = listOf("M", "T", "W", "T", "F", "S", "S")
        const val WEEKDAYS = "1111100"

        fun maskOf(days: String): BooleanArray {
            val m = when (days) { "weekdays" -> WEEKDAYS; "weekends" -> "0000011"; "all" -> "1111111"; else -> days }
            return BooleanArray(7) { m.getOrNull(it) == '1' }
        }

        fun maskString(m: BooleanArray) = m.joinToString("") { if (it) "1" else "0" }

        fun labelOf(m: BooleanArray): String {
            val on = (0..6).filter { m[it] }
            return when {
                on.size == 7 -> "Every day"
                on.isEmpty() -> "No days"
                on == (0..4).toList() -> "Weekdays"
                on == listOf(5, 6) -> "Weekends"
                // one unbroken run of 3+ days: "Mon–Thu"
                on.size >= 3 && on.last() - on.first() == on.size - 1 -> "${DAY_SHORT[on.first()]}–${DAY_SHORT[on.last()]}"
                else -> on.joinToString(", ") { DAY_SHORT[it] }
            }
        }

        fun parseTime(s: String): LocalTime? = try {
            val p = s.trim().split(":")
            LocalTime.of(p[0].toInt(), p[1].toInt())
        } catch (e: Exception) { null }
    }
}

/** Small persistence layer over SharedPreferences. */
object Store {
    private const val PREFS = "buscatch"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun routes(ctx: Context): List<Route> {
        val raw = prefs(ctx).getString("routes", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { Route.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) { emptyList() }
    }

    fun saveRoutes(ctx: Context, list: List<Route>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs(ctx).edit().putString("routes", arr.toString()).apply()
    }

    fun upsert(ctx: Context, r: Route) {
        val list = routes(ctx).toMutableList()
        val i = list.indexOfFirst { it.id == r.id }
        if (i >= 0) list[i] = r else list.add(r)
        saveRoutes(ctx, list)
    }

    fun delete(ctx: Context, id: String) = saveRoutes(ctx, routes(ctx).filterNot { it.id == id })

    // ---- cached arrivals per stop ----
    fun cacheStop(ctx: Context, code: String, json: String) {
        prefs(ctx).edit()
            .putString("stop_$code", json)
            .putLong("stop_at_$code", System.currentTimeMillis())
            .apply()
    }

    fun cachedStop(ctx: Context, code: String): List<Service>? =
        prefs(ctx).getString("stop_$code", null)?.let { Api.parse(it) }

    fun cachedAt(ctx: Context, code: String): Long = prefs(ctx).getLong("stop_at_$code", 0)

    fun lastRefresh(ctx: Context): Long = prefs(ctx).getLong("last_refresh", 0)
    fun setLastRefresh(ctx: Context, ok: Boolean) {
        prefs(ctx).edit().putLong("last_refresh", System.currentTimeMillis())
            .putBoolean("last_ok", ok).apply()
    }
    fun lastOk(ctx: Context): Boolean = prefs(ctx).getBoolean("last_ok", true)

    // ---- live updates during alert ranges ----
    fun liveEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("live_enabled", true)
    fun setLiveEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("live_enabled", on).remove("live_paused_until").apply()
    }
    fun livePausedUntil(ctx: Context): Long = prefs(ctx).getLong("live_paused_until", 0)
    fun setLivePausedUntil(ctx: Context, ms: Long) { prefs(ctx).edit().putLong("live_paused_until", ms).apply() }

    /** Fetch live times for these stops now and cache them. Blocking; call off the main thread. */
    fun fetchStops(ctx: Context, codes: Collection<String>) {
        var ok = true
        codes.distinct().forEach { code ->
            try { val raw = Api.fetchRaw(code); Api.parse(raw); cacheStop(ctx, code, raw) } catch (e: Exception) { ok = false }
        }
        if (codes.isNotEmpty()) setLastRefresh(ctx, ok)
    }

    // ---- which stop each home-screen widget shows ----
    fun widgetRoute(ctx: Context, widgetId: Int): String? = prefs(ctx).getString("widget_$widgetId", null)
    fun setWidgetRoute(ctx: Context, widgetId: Int, routeId: String) {
        prefs(ctx).edit().putString("widget_$widgetId", routeId).apply()
    }
    fun clearWidget(ctx: Context, widgetId: Int) {
        prefs(ctx).edit().remove("widget_$widgetId").apply()
    }

    // ---- alert bookkeeping: one "leave now" per route per day ----
    fun alertedToday(ctx: Context, routeId: String): Boolean =
        prefs(ctx).getString("alerted_$routeId", null) == LocalDate.now().toString()

    fun markAlerted(ctx: Context, routeId: String) {
        prefs(ctx).edit().putString("alerted_$routeId", LocalDate.now().toString()).apply()
    }

    fun clearAlerted(ctx: Context, routeId: String) {
        prefs(ctx).edit().remove("alerted_$routeId").apply()
    }
}
