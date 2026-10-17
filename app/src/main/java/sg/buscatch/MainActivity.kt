package sg.buscatch

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var permBox: LinearLayout

    private val BG = Color.parseColor("#0D110F")
    private val PANEL = Color.parseColor("#141A16")
    private val LED = Color.parseColor("#FFB21A")
    private val GREEN = Color.parseColor("#5EE08F")
    private val RED = Color.parseColor("#FF7A45")
    private val DIM = Color.parseColor("#9A917C")
    private val PAPER = Color.parseColor("#E9E2CF")

    private val tick = object : Runnable {
        override fun run() { render(); ui.postDelayed(this, 5_000) }
    }
    private val poll = object : Runnable {
        override fun run() { fetchAll(); ui.postDelayed(this, 30_000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        Notifier.ensureChannel(this)
        RefreshWorker.schedulePeriodic(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(24))
        }
        root.addView(TextView(this).apply {
            text = "BUS CATCH"
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = 28f; setTextColor(LED)
        })
        root.addView(TextView(this).apply {
            text = "Live LTA arrivals and a nudge when it's time to leave home."
            textSize = 14f; setTextColor(DIM)
        })
        status = TextView(this).apply { textSize = 13f; setTextColor(DIM); setPadding(0, dp(6), 0, dp(12)) }
        root.addView(status)

        permBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(permBox)

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)

        root.addView(Button(this).apply {
            text = "+ Add stop"
            setOnClickListener { editDialog(null) }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        root.addView(TextView(this).apply {
            text = "Add a widget: long-press an empty spot on your home screen → Widgets → Bus Catch, then pick a stop. " +
                "Each widget shows one stop, so add one widget per stop. Long-press a widget to switch its stop.\n\n" +
                "Alerts fire once a day per stop, inside the alert window you set, even when the app is closed. " +
                "Times marked est. are schedule estimates; others are GPS-tracked. Data: LTA DataMall via arrivelah."
            textSize = 12f; setTextColor(DIM); setPadding(0, dp(16), 0, 0)
        })

        setContentView(ScrollView(this).apply { setBackgroundColor(BG); addView(root) })
    }

    override fun onResume() {
        super.onResume()
        renderPermissions()
        render()
        ui.post(poll)
        ui.postDelayed(tick, 5_000)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(poll); ui.removeCallbacks(tick)
    }

    private fun fetchAll() {
        val codes = Store.routes(this).map { it.stopCode }.distinct()
        if (codes.isEmpty()) return
        io.execute {
            var ok = true
            codes.forEach { c ->
                try { val raw = Api.fetchRaw(c); Api.parse(raw); Store.cacheStop(this, c, raw) } catch (e: Exception) { ok = false }
            }
            Store.setLastRefresh(this, ok)
            BusWidget.updateAll(this)
            ui.post { render() }
        }
    }

    // ---------- rendering ----------

    private fun hm(ms: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

    private fun render() {
        val routes = Store.routes(this)
        val last = Store.lastRefresh(this)
        status.text = when {
            routes.isEmpty() -> "Add a bus to start."
            last == 0L -> "Loading live arrivals…"
            !Store.lastOk(this) -> "Can't reach live arrivals. Showing data from ${hm(last)}."
            else -> "● Live · updated ${(System.currentTimeMillis() - last) / 1000}s ago"
        }
        status.setTextColor(if (routes.isNotEmpty() && last != 0L && Store.lastOk(this)) GREEN else DIM)

        list.removeAllViews()
        if (routes.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "No buses yet.\nTap “+ Add stop”, enter the 5-digit code on your bus stop sign, and tick the buses you take."
                setTextColor(PAPER); textSize = 15f; gravity = Gravity.CENTER
                setPadding(dp(16), dp(28), dp(16), dp(28))
                background = rounded(PANEL)
            })
            return
        }
        val now = System.currentTimeMillis()
        routes.forEach { r -> list.addView(card(r, now), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }) }
    }

    private fun card(r: Route, now: Long): View {
        val plan = Planner.plan(r, Store.cachedStop(this, r.stopCode), now)
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(PANEL)
            alpha = if (r.alertOn) 1f else 0.7f
            setOnClickListener { editDialog(r) }
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply {
            val multi = r.services.size > 1
            text = if (multi) r.services.joinToString("\n") else r.service
            textSize = if (multi) 18f else 34f; setTextColor(LED)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            minWidth = dp(84)
        })
        head.addView(TextView(this).apply {
            text = "${r.displayName}\nStop ${r.stopCode} · ${r.walkMin} min walk + ${r.bufferMin} buffer"
            textSize = 13f; setTextColor(PAPER)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        c.addView(head)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
        fun cell(label: String, big: String, small: String, color: Int) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply { text = label; textSize = 11f; setTextColor(DIM); letterSpacing = 0.08f })
            addView(TextView(context).apply {
                text = big; textSize = 26f; setTextColor(color)
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            })
            addView(TextView(context).apply { text = small; textSize = 12f; setTextColor(DIM) })
        }
        when {
            plan == null -> row.addView(cell("NEXT BUS", "…", "Loading", LED), LinearLayout.LayoutParams(0, -2, 1f))
            plan.upcoming.isEmpty() -> row.addView(cell("NEXT BUS", "--", "Not running now", DIM), LinearLayout.LayoutParams(0, -2, 1f))
            else -> {
                val first = plan.upcoming.first()
                val m = ((first.timeMs - now) / 60_000).coerceAtLeast(0)
                val multi = r.services.size > 1
                val then = plan.upcoming.drop(1).take(2).joinToString(", ") {
                    (if (multi) "${it.service} " else "") + "${((it.timeMs - now) / 60_000).coerceAtLeast(0)}m"
                }
                row.addView(cell(if (multi) "NEXT · ${first.service}" else "NEXT BUS", if (m == 0L) "Arr" else "$m min",
                    hm(first.timeMs) + (if (then.isNotEmpty()) " · then $then" else "") + (if (first.monitored) "" else " · est."), LED),
                    LinearLayout.LayoutParams(0, -2, 1f))
                val leave = plan.leaveAtMs
                val leaveCell = when {
                    leave == null -> cell("LEAVE HOME", "MISS", "Buses too close to reach", RED)
                    leave <= now + 60_000 -> cell("LEAVE HOME", "NOW", "for ${plan.target!!.service} at ${hm(plan.target.timeMs)}", GREEN)
                    else -> {
                        val lm = (leave - now) / 60_000
                        cell("LEAVE IN", "$lm min", "by ${hm(leave)} for ${plan.target!!.service} at ${hm(plan.target.timeMs)}", if (lm <= 3) GREEN else LED)
                    }
                }
                row.addView(leaveCell, LinearLayout.LayoutParams(0, -2, 1f))
            }
        }
        c.addView(row)
        c.addView(TextView(this).apply {
            text = if (r.alertOn) "🔔 Alert ${r.activeFrom}–${r.activeTo}, ${daysLabel(r.days)}" + (if (Store.alertedToday(this@MainActivity, r.id)) " · sent today" else "")
                   else "🔕 Alert off · tap to edit"
            textSize = 12f; setTextColor(if (r.alertOn) PAPER else DIM); setPadding(0, dp(10), 0, 0)
        })
        return c
    }

    private fun daysLabel(d: String) = when (d) { "weekdays" -> "weekdays"; "weekends" -> "weekends"; else -> "daily" }

    private fun renderPermissions() {
        permBox.removeAllViews()
        if (Build.VERSION.SDK_INT >= 33 && !Notifier.canNotify(this)) {
            permBox.addView(permButton("Allow notifications so alerts can reach you") {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            })
        }
        if (Build.VERSION.SDK_INT >= 31) {
            val am = getSystemService(AlarmManager::class.java)
            if (am != null && !am.canScheduleExactAlarms()) {
                permButton("Allow exact alarms so alerts are on time") {
                    try {
                        startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
                    } catch (e: Exception) {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                    }
                }.also { permBox.addView(it) }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        renderPermissions()
    }

    private fun permButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
    }

    // ---------- add / edit ----------

    private fun editDialog(existing: Route?) {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        fun label(t: String) = TextView(this).apply { text = t; textSize = 13f; setPadding(0, dp(10), 0, 0) }
        fun field(hint: String, value: String, type: Int) = EditText(this).apply {
            this.hint = hint; setText(value); inputType = type; setSingleLine()
        }

        form.addView(label("Bus stop code (5 digits on the stop sign)"))
        val codeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val code = field("e.g. 83139", existing?.stopCode ?: "", InputType.TYPE_CLASS_NUMBER)
        val svc = field("e.g. 155 or 155, 43", existing?.services?.joinToString(", ") ?: "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
        codeRow.addView(code, LinearLayout.LayoutParams(0, -2, 1f))
        codeRow.addView(Button(this).apply {
            text = "Find buses"; isAllCaps = false
            setOnClickListener { findBuses(code.text.toString().trim(), svc) }
        })
        form.addView(codeRow)

        form.addView(label("Bus numbers (one or more, separated by commas)")); form.addView(svc)
        form.addView(label("Label (optional)"))
        val name = field("e.g. Home to work", existing?.label ?: "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        form.addView(name)

        val walk = field("5", (existing?.walkMin ?: 5).toString(), InputType.TYPE_CLASS_NUMBER)
        val buf = field("1", (existing?.bufferMin ?: 1).toString(), InputType.TYPE_CLASS_NUMBER)
        form.addView(pair(label("Walk to stop (min)"), walk, label("Buffer (min)"), buf))

        val from = field("07:00", existing?.activeFrom ?: "07:00", InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME)
        val to = field("09:00", existing?.activeTo ?: "09:00", InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME)
        form.addView(pair(label("Alert from (24h)"), from, label("Alert until"), to))

        form.addView(label("Days"))
        val dayKeys = listOf("weekdays", "all", "weekends")
        val days = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Weekdays (Mon–Fri)", "Every day", "Weekends"))
            setSelection(dayKeys.indexOf(existing?.days ?: "weekdays").coerceAtLeast(0))
        }
        form.addView(days)

        val alert = Switch(this).apply {
            text = "Leave-home alert"; isChecked = existing?.alertOn ?: true
            setPadding(0, dp(12), 0, dp(8))
        }
        form.addView(alert)

        val builder = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Add a stop" else "Edit ${existing.serviceLabel}")
            .setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
        if (existing != null) builder.setNeutralButton("Delete", null)
        val dlg = builder.create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val c = code.text.toString().trim()
                val s = Route.splitServices(svc.text.toString()).joinToString(",")
                val f = from.text.toString().trim()
                val t = to.text.toString().trim()
                when {
                    !Regex("\\d{5}").matches(c) -> toast("Enter the 5-digit stop code from the bus stop sign.")
                    s.isEmpty() -> toast("Enter or pick at least one bus number.")
                    Route.parseTime(f) == null || Route.parseTime(t) == null -> toast("Alert times need 24-hour format, like 07:30.")
                    else -> {
                        val r = Route(
                            id = existing?.id ?: "r${System.currentTimeMillis()}",
                            stopCode = c, service = s, label = name.text.toString().trim(),
                            walkMin = walk.text.toString().toIntOrNull()?.coerceIn(0, 60) ?: 5,
                            bufferMin = buf.text.toString().toIntOrNull()?.coerceIn(0, 30) ?: 1,
                            activeFrom = fmtTime(f), activeTo = fmtTime(t),
                            days = dayKeys[days.selectedItemPosition], alertOn = alert.isChecked,
                        )
                        Store.upsert(this, r)
                        // Changing a route's settings re-arms today's alert
                        Store.clearAlerted(this, r.id)
                        RefreshWorker.refreshSoon(this)
                        fetchAll(); render()
                        dlg.dismiss()
                    }
                }
            }
            existing?.let { ex ->
                val del = dlg.getButton(AlertDialog.BUTTON_NEUTRAL)
                var armed = false
                del.setOnClickListener {
                    if (!armed) { armed = true; del.text = "Tap again"; return@setOnClickListener }
                    Store.delete(this, ex.id); BusWidget.updateAll(this); render(); dlg.dismiss()
                }
            }
        }
        dlg.show()
    }

    private fun fmtTime(s: String): String = Route.parseTime(s)!!.let { String.format(Locale.US, "%02d:%02d", it.hour, it.minute) }

    private fun pair(l1: View, f1: View, l2: View, f2: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; addView(l1); addView(f1) },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(12) })
        addView(LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; addView(l2); addView(f2) },
            LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun findBuses(code: String, target: EditText) {
        if (!Regex("\\d{5}").matches(code)) { toast("Enter the 5-digit stop code first."); return }
        toast("Finding buses at $code…")
        io.execute {
            val result = try { Api.parse(Api.fetchRaw(code)).map { it.no } } catch (e: Exception) { null }
            ui.post {
                when {
                    result == null -> toast("Couldn't reach live data. Check your connection, or type the bus number.")
                    result.isEmpty() -> toast("No buses running at $code right now. Type the bus number instead.")
                    else -> {
                        val current = Route.splitServices(target.text.toString()).toSet()
                        val checked = BooleanArray(result.size) { result[it].uppercase() in current }
                        AlertDialog.Builder(this)
                            .setTitle("Buses at stop $code")
                            .setMultiChoiceItems(result.toTypedArray(), checked) { _, i, on -> checked[i] = on }
                            .setPositiveButton("Use selected") { _, _ ->
                                target.setText(result.filterIndexed { i, _ -> checked[i] }.joinToString(", "))
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(16).toFloat() }
}
