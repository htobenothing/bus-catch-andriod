package sg.buscatch

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime

data class Arrival(
    val timeMs: Long,
    val monitored: Boolean,   // true = GPS-tracked, false = schedule estimate
    val load: String,         // SEA seats, SDA standing, LSD packed
    val type: String,         // SD single deck, DD double deck, BD bendy
    val service: String = "",
)

data class Service(val no: String, val arrivals: List<Arrival>)

/**
 * Live LTA DataMall bus arrivals through arrivelah (no API key needed).
 * https://github.com/cheeaun/arrivelah
 */
object Api {
    private const val BASE = "https://arrivelah2.busrouter.sg/?id="

    /** Blocking. Returns the raw JSON body; call off the main thread. */
    fun fetchRaw(stopCode: String): String {
        val conn = URL(BASE + stopCode).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", "BusCatch-Android/1.0")
        try {
            val code = conn.responseCode
            if (code != 200) throw RuntimeException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    fun parse(json: String): List<Service> {
        val out = mutableListOf<Service>()
        val arr = JSONObject(json).optJSONArray("services") ?: return out
        for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i)
            val arrivals = listOf("next", "subsequent", "next2", "next3").mapNotNull { key ->
                val b = s.optJSONObject(key) ?: return@mapNotNull null
                val t = b.optString("time", "")
                if (t.isBlank()) return@mapNotNull null
                val ms = try { OffsetDateTime.parse(t).toInstant().toEpochMilli() } catch (e: Exception) { return@mapNotNull null }
                Arrival(ms, b.optInt("monitored", 0) == 1, b.optString("load", ""), b.optString("type", ""), s.optString("no"))
            }.sortedBy { it.timeMs }
            out.add(Service(s.optString("no"), arrivals))
        }
        return out
    }
}

/** What to tell the user for one route right now. */
data class Plan(
    val upcoming: List<Arrival>,   // arrivals still in the future
    val target: Arrival?,          // first bus that can still be caught
    val leaveAtMs: Long?,          // when to step out of the door for [target]
)

object Planner {
    fun plan(route: Route, services: List<Service>?, now: Long = System.currentTimeMillis()): Plan? {
        services ?: return null
        val wanted = route.services.toSet()
        // Merge arrivals of every chosen bus at this stop, soonest first
        val upcoming = services.filter { it.no.uppercase() in wanted }
            .flatMap { it.arrivals }
            .filter { it.timeMs > now - 30_000 }
            .sortedBy { it.timeMs }
        val need = route.needMin * 60_000L
        val walk = route.walkMin * 60_000L
        val target = upcoming.firstOrNull { it.timeMs - now >= need }
            ?: upcoming.firstOrNull { it.timeMs - now >= walk }
        return Plan(upcoming, target, target?.let { it.timeMs - need })
    }
}
