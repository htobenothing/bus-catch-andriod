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
    val service: String,
    val label: String,
    val walkMin: Int,
    val bufferMin: Int,
    val activeFrom: String,   // "HH:mm" — alerts only fire inside this window
    val activeTo: String,
    val days: String,         // "all" | "weekdays" | "weekends"
    val alertOn: Boolean,
) {
    val needMin get() = walkMin + bufferMin
    val displayName get() = label.ifBlank { "Stop $stopCode" }

    fun dayOk(date: LocalDate = LocalDate.now()): Boolean {
        val dow = date.dayOfWeek.value // 1 = Mon … 7 = Sun
        return when (days) {
            "weekdays" -> dow <= 5
            "weekends" -> dow >= 6
            else -> true
        }
    }

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
            days = o.optString("days", "weekdays"),
            alertOn = o.optBoolean("alertOn", true),
        )

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
