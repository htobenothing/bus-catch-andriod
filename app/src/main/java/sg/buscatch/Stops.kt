package sg.buscatch

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.cos
import kotlin.math.sqrt

/** One bus stop from the Singapore stop directory. */
data class BusStop(val code: String, val name: String, val road: String, val lat: Double, val lng: Double) {
    val label get() = "$name, $road"
}

/**
 * Directory of every Singapore bus stop (from busrouter.sg, built on LTA DataMall),
 * downloaded once and cached for 30 days. Used to search by name and to find stops nearby.
 */
object Stops {
    private const val URL_STOPS = "https://data.busrouter.sg/v1/stops.min.json"
    private const val MAX_AGE = 30L * 24 * 3600_000
    @Volatile private var memo: List<BusStop>? = null

    private fun file(ctx: Context) = File(ctx.filesDir, "stops.json")

    /** Blocking: returns the directory, downloading it if missing or old. Throws if never available. */
    fun all(ctx: Context): List<BusStop> {
        memo?.let { return it }
        val f = file(ctx)
        val fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < MAX_AGE
        if (!fresh) {
            try { f.writeText(download()) } catch (e: Exception) { if (!f.exists()) throw e }
        }
        return parse(f.readText()).also { memo = it }
    }

    private fun download(): String {
        val c = URL(URL_STOPS).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "BusPup-Android")
        try {
            if (c.responseCode != 200) throw RuntimeException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    private fun parse(json: String): List<BusStop> {
        val o = JSONObject(json)
        val out = ArrayList<BusStop>(o.length())
        o.keys().forEach { code ->
            val a = o.optJSONArray(code) ?: return@forEach
            out.add(BusStop(code, a.optString(2), a.optString(3), a.optDouble(1), a.optDouble(0)))
        }
        return out
    }

    /** Search by name, road or code. Best matches first (name starts with the query, then contains). */
    fun search(ctx: Context, query: String, limit: Int = 40): List<BusStop> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val words = q.split(Regex("\\s+"))
        return all(ctx).asSequence()
            .map { s -> s to "${s.name} ${s.road} ${s.code}".lowercase() }
            .filter { (_, hay) -> words.all { hay.contains(it) } }
            .sortedBy { (s, _) ->
                val n = s.name.lowercase()
                when { s.code == q -> 0; n.startsWith(q) -> 1; n.contains(q) -> 2; else -> 3 }
            }
            .take(limit).map { it.first }.toList()
    }

    /** Stops nearest to a point, with distance in metres. */
    fun near(ctx: Context, lat: Double, lng: Double, limit: Int = 25): List<Pair<BusStop, Int>> =
        all(ctx).map { it to metres(lat, lng, it.lat, it.lng) }.sortedBy { it.second }.take(limit)

    fun byCode(ctx: Context, code: String): BusStop? = try { all(ctx).firstOrNull { it.code == code } } catch (e: Exception) { null }

    /** Good-enough distance for short hops (equirectangular). */
    fun metres(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Int {
        val x = Math.toRadians(lng2 - lng1) * cos(Math.toRadians((lat1 + lat2) / 2))
        val y = Math.toRadians(lat2 - lat1)
        return (sqrt(x * x + y * y) * 6_371_000).toInt()
    }
}
