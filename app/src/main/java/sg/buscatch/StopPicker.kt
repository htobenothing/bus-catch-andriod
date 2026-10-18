package sg.buscatch

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.util.concurrent.Executor

/**
 * "Find a stop" dialog: type a name (Jurong Point, Blk 512, Orchard Rd…) or tap Near me.
 * Picking a stop hands it back to the add/edit form.
 */
class StopPicker(private val activity: Activity, private val io: Executor, private val onPick: (BusStop) -> Unit) {
    private val ui = Handler(Looper.getMainLooper())
    private val d = activity.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    private lateinit var dialog: AlertDialog
    private lateinit var status: TextView
    private lateinit var listView: ListView
    private var shown: List<BusStop> = emptyList()
    private var searchSeq = 0

    fun show() {
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val query = EditText(activity).apply {
            hint = "Stop name, road or code"; setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        box.addView(query)
        box.addView(Button(activity).apply {
            text = "📍 Stops near me"; isAllCaps = false
            setOnClickListener { nearMe() }
        })
        status = TextView(activity).apply { textSize = 13f; setPadding(0, dp(6), 0, dp(4)); text = "Loading the stop list…" }
        box.addView(status)
        listView = ListView(activity).apply {
            setOnItemClickListener { _, _, pos, _ -> shown.getOrNull(pos)?.let { onPick(it); dialog.dismiss() } }
        }
        box.addView(listView, LinearLayout.LayoutParams(-1, dp(320)))

        dialog = AlertDialog.Builder(activity)
            .setTitle("Find a bus stop")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.show()

        // Warm the directory (first time downloads ~5,000 stops)
        io.execute {
            val ok = try { Stops.all(activity); true } catch (e: Exception) { false }
            ui.post { status.text = if (ok) "Type a name, or tap Stops near me." else "Couldn't download the stop list. Check your connection and try again." }
        }

        query.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString().orEmpty()
                val seq = ++searchSeq
                ui.postDelayed({ if (seq == searchSeq) search(q) }, 250)
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }

    private fun search(q: String) {
        if (q.isBlank()) { show(emptyList(), "Type a name, or tap Stops near me."); return }
        io.execute {
            val r = try { Stops.search(activity, q) } catch (e: Exception) { null }
            ui.post {
                when {
                    r == null -> show(emptyList(), "Couldn't load the stop list. Check your connection.")
                    r.isEmpty() -> show(emptyList(), "No stops match “$q”.")
                    else -> show(r.map { it to null }, "${r.size} stops")
                }
            }
        }
    }

    private fun show(rows: List<Pair<BusStop, Int?>>, msg: String) {
        shown = rows.map { it.first }
        status.text = msg
        listView.adapter = ArrayAdapter(activity, android.R.layout.simple_list_item_1, rows.map { (s, m) ->
            "${s.name}\n${s.road} · ${s.code}" + (m?.let { " · ${if (it < 1000) "$it m" else "%.1f km".format(it / 1000.0)}" } ?: "")
        })
    }

    // ---------- near me ----------

    private fun hasLocation() =
        activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun nearMe() {
        if (!hasLocation()) {
            pending = this
            activity.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOCATION)
            return
        }
        status.text = "Finding your location…"
        locate { loc ->
            if (loc == null) { status.text = "Couldn't get your location. Turn on Location in quick settings, or search by name."; return@locate }
            io.execute {
                val r = try { Stops.near(activity, loc.latitude, loc.longitude) } catch (e: Exception) { null }
                ui.post {
                    if (r == null) show(emptyList(), "Couldn't load the stop list. Check your connection.")
                    else show(r.map { it.first to it.second }, "Nearest stops")
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun locate(done: (Location?) -> Unit) {
        val lm = activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { try { lm.isProviderEnabled(it) } catch (e: Exception) { false } }
        // A fix from the last 2 minutes is good enough to find nearby stops
        val recent = providers.mapNotNull { try { lm.getLastKnownLocation(it) } catch (e: Exception) { null } }
            .filter { System.currentTimeMillis() - it.time < 2 * 60_000 }.maxByOrNull { it.time }
        if (recent != null) { done(recent); return }
        val provider = providers.firstOrNull() ?: run { done(null); return }
        var answered = false
        val finish = { l: Location? -> if (!answered) { answered = true; ui.post { done(l) } } }
        ui.postDelayed({ finish(providers.mapNotNull { try { lm.getLastKnownLocation(it) } catch (e: Exception) { null } }.maxByOrNull { it.time }) }, 15_000)
        if (Build.VERSION.SDK_INT >= 30) {
            lm.getCurrentLocation(provider, null, activity.mainExecutor) { finish(it) }
        } else {
            @Suppress("DEPRECATION")
            lm.requestSingleUpdate(provider, { finish(it) }, Looper.getMainLooper())
        }
    }

    companion object {
        const val REQ_LOCATION = 42
        private var pending: StopPicker? = null

        /** Call from Activity.onRequestPermissionsResult. */
        fun onPermissionResult(requestCode: Int) {
            if (requestCode != REQ_LOCATION) return
            val p = pending ?: return
            pending = null
            if (p.hasLocation()) p.nearMe() else p.status.text = "Location wasn't allowed. Search by name instead."
        }
    }
}
