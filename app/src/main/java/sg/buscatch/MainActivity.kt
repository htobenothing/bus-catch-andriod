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
import android.widget.ImageView
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

    companion object {
        /** Always points at the newest release's APK. */
        const val SHARE_APK = "https://github.com/htobenothing/bus-catch-andriod/releases/latest/download/BusPup.apk"
    }

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var notices: LinearLayout
    private var puppy: PuppyRunView? = null
    private var rewardsCard: LinearLayout? = null
    private var headerDog: ImageView? = null
    private lateinit var addBtn: Button
    private lateinit var stopsPage: View
    private lateinit var widgetsPage: View
    private lateinit var settingsPage: ScrollView
    private val tabs = mutableListOf<TextView>()
    private var tab = 0 // 0 Stops, 1 Widgets, 2 Settings
    private var pendingApk: java.io.File? = null
    private var downloading = false
    private var progressBtn: Button? = null

    // Colours come from the chosen theme (Settings → Theme)
    private var pal = Theme.CLASSIC
    private val BG: Int get() = pal.bg
    private val PANEL: Int get() = pal.panel
    private val LED: Int get() = pal.accent
    private val GREEN: Int get() = pal.go
    private val RED: Int get() = pal.late
    private val DIM: Int get() = pal.dim
    private val PAPER: Int get() = pal.text

    private val tick = object : Runnable {
        override fun run() { render(); ui.postDelayed(this, 5_000) }
    }
    private val poll = object : Runnable {
        override fun run() { fetchAll(); ui.postDelayed(this, 10_000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        pal = Theme.of(this)
        setTheme(pal.styleRes)
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = PANEL
        if (pal.puppy) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        Notifier.ensureChannel(this)
        RefreshWorker.schedulePeriodic(this)
        tab = savedInstanceState?.getInt("tab") ?: 0

        // Header: title + live status, and the Add button on the Stops tab
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(12), dp(8))
        }
        if (pal.puppy) header.addView(ImageView(this).apply {
            setImageBitmap(Rewards.puppyBitmap(this@MainActivity, dp(46)))
            setOnClickListener { wardrobe() }
            headerDog = this
        }, LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginEnd = dp(10) })
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TextView(this).apply {
            text = if (pal.puppy) "BUS PUP 🐾" else "BUS PUP"; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = 24f; setTextColor(LED)
        })
        status = TextView(this).apply { textSize = 12f; setTextColor(DIM) }
        titles.addView(status)
        header.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        addBtn = Button(this).apply {
            text = "+ Add stop"; isAllCaps = false
            setOnClickListener { editDialog(null) }
        }
        header.addView(addBtn)

        // Pages
        notices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        puppy = if (pal.puppy) PuppyRunView(this) else null
        rewardsCard = if (pal.puppy) LinearLayout(this).apply { orientation = LinearLayout.VERTICAL } else null
        stopsPage = puppy?.let { page(it, rewardsCard!!, notices, list) } ?: page(notices, list)
        widgetsPage = page(*widgetsContent())
        settingsPage = page()
        val pages = android.widget.FrameLayout(this).apply {
            addView(stopsPage); addView(widgetsPage); addView(settingsPage)
        }

        // Bottom tab bar
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(PANEL)
            setPadding(0, dp(6), 0, dp(6))
        }
        (if (pal.puppy) listOf("🐶\nStops", "🦴\nWidgets", "🐾\nSettings") else listOf("🚌\nStops", "🧩\nWidgets", "⚙️\nSettings")).forEachIndexed { i, label ->
            val t = TextView(this).apply {
                text = label; gravity = Gravity.CENTER; textSize = 12f
                setPadding(0, dp(4), 0, dp(4))
                setOnClickListener { showTab(i) }
            }
            tabs.add(t)
            bar.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            addView(header)
            addView(pages, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(bar)
        })
        showTab(tab)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", tab)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        showTab(0) // opened from a widget or alert: show the stops
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // Back from Widgets or Settings returns to Stops before leaving the app
        if (tab != 0) showTab(0) else super.onBackPressed()
    }

    private fun page(vararg children: View) = ScrollView(this).apply {
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(24))
            children.forEach { addView(it) }
        })
    }

    private fun showTab(i: Int) {
        tab = i
        stopsPage.visibility = if (i == 0) View.VISIBLE else View.GONE
        widgetsPage.visibility = if (i == 1) View.VISIBLE else View.GONE
        settingsPage.visibility = if (i == 2) View.VISIBLE else View.GONE
        addBtn.visibility = if (i == 0) View.VISIBLE else View.GONE
        if (i == 2) renderSettings()
        renderTabs()
    }

    /** Things that need attention; shown as a dot on Settings and a bar on Stops. */
    private fun needsNotifications() = Build.VERSION.SDK_INT >= 33 && !Notifier.canNotify(this)
    private fun needsExactAlarms(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return false
        val am = getSystemService(AlarmManager::class.java) ?: return false
        return !am.canScheduleExactAlarms()
    }

    private fun renderTabs() {
        val attention = Updater.known(this) != null || needsNotifications() || needsExactAlarms()
        tabs.forEachIndexed { i, t ->
            t.setTextColor(if (i == tab) LED else DIM)
            t.setTypeface(null, if (i == tab) Typeface.BOLD else Typeface.NORMAL)
        }
        val icon = if (pal.puppy) "🐾" else "⚙️"
        tabs[2].text = if (attention) "$icon\nSettings •" else "$icon\nSettings"
    }

    override fun onResume() {
        super.onResume()
        AlertEngine.rescheduleAsync(this) // keep the alert chain alive
        offerCrashReport()
        // Back from "allow installs from this app": continue the update
        pendingApk?.let { apk ->
            if (Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()) {
                if (Updater.install(this, apk)) pendingApk = null
            }
        }
        render()
        if (tab == 2) renderSettings()
        renderTabs()
        if (System.currentTimeMillis() - Updater.lastChecked(this) > 6 * 3600_000L) checkForUpdate(manual = false)
        ui.post(poll)
        ui.postDelayed(tick, 5_000)
    }

    override fun onPause() {
        super.onPause()
        puppy?.stop()
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
            routes.isEmpty() -> "Live LTA arrivals · leave-home alerts"
            last == 0L -> "Loading live arrivals…"
            !Store.lastOk(this) -> "Offline · showing data from ${hm(last)}"
            else -> "● Live · updated ${(System.currentTimeMillis() - last) / 1000}s ago"
        }
        status.setTextColor(if (routes.isNotEmpty() && last != 0L && Store.lastOk(this)) GREEN else DIM)

        renderRewards()

        // Slim notices that lead to Settings
        notices.removeAllViews()
        Feedback.suggestion(this)?.let { t ->
            notices.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = rounded(pal.notice)
                addView(TextView(context).apply {
                    text = "⏱ ${t.reason} at ${t.route.displayName}. " +
                        (if (t.delta > 0) "Add 1 min to the buffer?" else "Take 1 min off the buffer?")
                    textSize = 13f; setTextColor(PAPER)
                })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END
                    addView(Button(context).apply {
                        text = "No thanks"; isAllCaps = false
                        setOnClickListener { Feedback.resolve(this@MainActivity, t, false); render() }
                    })
                    addView(Button(context).apply {
                        text = "Apply"; isAllCaps = false
                        setOnClickListener {
                            Feedback.resolve(this@MainActivity, t, true)
                            AlertEngine.rescheduleAsync(this@MainActivity)
                            toast("Buffer is now ${(t.route.bufferMin + t.delta).coerceIn(0, 30)} min"); render()
                        }
                    })
                })
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
        Updater.known(this)?.let { notice("⬆  Version ${it.version} is ready · tap to update", GREEN) }
        if (needsNotifications() || needsExactAlarms()) notice("⚠  Alerts need a permission · tap to fix", LED)

        list.removeAllViews()
        if (routes.isEmpty()) {
            puppy?.apply { mode = PuppyRunView.Mode.SIT; caption = "Add a stop and I'll walk you to the bus!"; start() }
            if (pal.puppy) list.addView(ImageView(this).apply { setImageBitmap(Rewards.puppyBitmap(this@MainActivity, dp(120))) },
                LinearLayout.LayoutParams(dp(120), dp(120)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(12) })
            list.addView(TextView(this).apply {
                text = (if (pal.puppy) "Woof! " else "") + "No stops yet.\nTap “+ Add stop”, find your stop by name or near you, and tick the buses you take."
                setTextColor(PAPER); textSize = 15f; gravity = Gravity.CENTER
                setPadding(dp(16), dp(28), dp(16), dp(28))
                background = rounded(PANEL)
            })
            return
        }
        val now = System.currentTimeMillis()
        updatePuppy(routes, now)
        routes.forEach { r -> list.addView(card(r, now), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }) }
    }

    /** Puppy strolls with time to spare, runs when it's nearly time, sits if the bus is missed. */
    private fun updatePuppy(routes: List<Route>, now: Long) {
        val p = puppy ?: return
        val r = WidgetRoutes.nearest(this, routes)
        val plan = r?.let { Planner.plan(it, Store.cachedStop(this, it.stopCode), now) }
        val leave = plan?.leaveAtMs
        val mins = leave?.let { ((it - now) / 60_000).coerceAtLeast(0) }
        when {
            r == null || plan == null -> { p.mode = PuppyRunView.Mode.SIT; p.caption = "Waiting for live buses…" }
            plan.upcoming.isEmpty() -> { p.mode = PuppyRunView.Mode.SIT; p.caption = "No buses coming at ${r.displayName}" }
            leave == null -> { p.mode = PuppyRunView.Mode.SIT; p.caption = "Missed it! Next bus soon" }
            mins!! <= 3 -> { p.mode = PuppyRunView.Mode.RUN; p.caption = if (mins == 0L) "Go go go! Leave now for ${plan.target?.service}" else "Hurry! Leave in $mins min" }
            else -> { p.mode = PuppyRunView.Mode.WALK; p.caption = "Strolling… leave in $mins min for ${plan.target?.service}" }
        }
        p.start()
    }

    /** Treats and streak, with the next thing to unlock. Tap for the wardrobe. */
    private fun renderRewards() {
        val card = rewardsCard ?: return
        card.removeAllViews()
        headerDog?.setImageBitmap(Rewards.puppyBitmap(this, dp(46)))
        val t = Rewards.treats(this); val st = Rewards.streak(this)
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(pal.notice)
            setOnClickListener { wardrobe() }
            addView(ImageView(context).apply { setImageBitmap(Rewards.puppyBitmap(this@MainActivity, dp(44))) },
                LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = "🦴 $t treat${if (t == 1) "" else "s"}" + (if (st > 0) "   🔥 $st-day streak" else "")
                    textSize = 15f; setTextColor(PAPER); setTypeface(typeface, Typeface.BOLD)
                })
                addView(TextView(context).apply {
                    text = Rewards.nextGoal(this@MainActivity)?.let { "Next: $it" }
                        ?: "All outfits unlocked! Tap to dress up"
                    textSize = 12f; setTextColor(DIM)
                })
                if (t == 0) addView(TextView(context).apply {
                    text = "Tap “I'm going” when your alert rings to earn treats"
                    textSize = 12f; setTextColor(DIM)
                })
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
    }

    /** Dress up the puppy: wear or take off each unlocked accessory. */
    private fun wardrobe() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)); gravity = Gravity.CENTER_HORIZONTAL }
        val preview = ImageView(this).apply { setImageBitmap(Rewards.puppyBitmap(this@MainActivity, dp(120))) }
        box.addView(preview, LinearLayout.LayoutParams(dp(120), dp(120)))
        box.addView(TextView(this).apply {
            text = "🦴 ${Rewards.treats(this@MainActivity)} treats · 🔥 ${Rewards.streak(this@MainActivity)}-day streak (best ${Rewards.bestStreak(this@MainActivity)})"
            textSize = 13f; gravity = Gravity.CENTER; setPadding(0, dp(6), 0, dp(10))
        })
        Rewards.Acc.values().forEach { a ->
            val open = Rewards.unlocked(this, a)
            box.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
                addView(TextView(context).apply {
                    text = "${if (open) a.emoji else "🔒"}  ${a.title}\n" + (if (open) (if (a == Rewards.Acc.RAINCOAT) "Worn when rain is forecast" + (Weather.forecast(this@MainActivity)?.let { " · now $it" } ?: "") else "Unlocked") else "Needs ${a.need}")
                    textSize = 14f
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(Switch(context).apply {
                    isEnabled = open
                    isChecked = Rewards.wearing(this@MainActivity, a)
                    setOnCheckedChangeListener { _, on ->
                        Rewards.setWearing(this@MainActivity, a, on)
                        preview.setImageBitmap(Rewards.puppyBitmap(this@MainActivity, dp(120)))
                        renderRewards(); BusWidget.updateAll(this@MainActivity)
                    }
                })
            }, LinearLayout.LayoutParams(-1, -2))
        }
        AlertDialog.Builder(this)
            .setTitle("Puppy's wardrobe")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Done", null)
            .show()
    }

    private fun notice(text: String, color: Int) {
        notices.addView(TextView(this).apply {
            this.text = text; textSize = 13f; setTextColor(color)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(pal.notice)
            setOnClickListener { showTab(2) }
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
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
        if (pal.puppy) head.addView(ImageView(this).apply { setImageResource(R.drawable.ic_paw) },
            LinearLayout.LayoutParams(dp(28), dp(28)))
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
            text = if (r.alertOn) "🔔 Alert ${r.activeFrom}–${r.activeTo}, ${daysLabel(r.days)}" + (if (Trip.isDone(this@MainActivity, r.id)) " · ✓ on your way" else if (Store.alertedToday(this@MainActivity, r.id)) " · sent today" else "")
                   else "🔕 Alert off · tap to edit"
            textSize = 12f; setTextColor(if (r.alertOn) PAPER else DIM); setPadding(0, dp(10), 0, 0)
        })
        return c
    }

    private fun daysLabel(d: String) = when (d) { "weekdays" -> "weekdays"; "weekends" -> "weekends"; else -> "daily" }

    // ---------- Widgets tab (static help) ----------

    private fun sectionTitle(t: String) = TextView(this).apply {
        text = t.uppercase(); textSize = 12f; letterSpacing = 0.08f
        setTextColor(DIM); setPadding(dp(4), dp(18), 0, dp(6))
    }

    private fun panel(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(14))
        background = rounded(PANEL)
        views.forEach { addView(it) }
    }

    private fun para(t: String, color: Int = PAPER, size: Float = 14f) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); setLineSpacing(0f, 1.15f)
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun widgetsContent(): Array<View> = arrayOf(
        sectionTitle("Add a widget"),
        panel(para("1.  Long-press an empty spot on your home screen\n2.  Tap Widgets and find Bus Pup\n3.  Drag a widget out. The Stop widget asks which stop to show.")),
        sectionTitle("Bus Pup · Stop"),
        panel(
            para("Shows one stop. Choose ⟳ Auto when placing it: it shows the stop whose alert time range you're in right now, or the one whose range starts next. One widget covers all your stops."),
            para("🔔  Tap the bell to switch that stop's leave alert on or off.", LED),
            para("Resize it to change what it shows:", DIM, 13f),
            para("1×1   bus number and leave time\n2×1   leave time and which bus\n3×1   leave time, bus, stop and bell\n3×2+  every bus at the stop with next arrivals", PAPER, 13f).apply {
                typeface = Typeface.MONOSPACE
            },
            para("Auto uses every stop's alert range, even with 🔕 on. 🔕 only silences the notification. Long-press the widget to switch between Auto and a fixed stop (Android 12+).", DIM, 13f),
        ),
        sectionTitle("Bus Pup · All stops"),
        panel(para("Every saved stop in one scrolling list, with when to leave and the next buses. No setup needed.")),
        sectionTitle("Quick Settings tile"),
        panel(
            para("Swipe down twice from the top, tap ✏️ (edit), and drag the Bus Pup tile into your tiles."),
            para("It shows when to leave. Tap it for the next buses, no unlocking needed.", DIM, 13f),
        ),
        sectionTitle("Puppy rewards"),
        panel(
            para("Tap “I'm going” when your leave alert rings and the puppy gets a 🦴 treat. Go every day you're alerted to build a 🔥 streak."),
            para("🧣 Scarf at 3 treats · 🕶️ Sunglasses at a 5-day streak · 🥳 Party hat at 10 treats · 🌧️ Raincoat at 15 treats, worn when rain is forecast near your stop.", DIM, 13f),
            para("Tap the puppy to open its wardrobe. Rewards show in the Puppy theme.", DIM, 13f),
        ),
        sectionTitle("Reading the colours"),
        panel(
            para("Amber   leave at this time", LED),
            para("Green   leave now", GREEN),
            para("Orange  too late for the next bus", RED),
            para("est. = schedule estimate; other times are GPS-tracked.", DIM, 13f),
        ),
    )

    // ---------- Settings tab ----------

    private fun renderSettings() {
        val box = settingsPage.getChildAt(0) as LinearLayout
        box.removeAllViews()

        box.addView(sectionTitle("Theme"))
        val themes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun themeCard(label: String, puppy: Boolean) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(8), dp(14), dp(8), dp(12))
            val p = if (puppy) Theme.PUPPY else Theme.CLASSIC
            background = GradientDrawable().apply {
                setColor(p.bg); cornerRadius = dp(16).toFloat()
                setStroke(dp(if (pal.puppy == puppy) 3 else 1), if (pal.puppy == puppy) pal.accent else pal.dim)
            }
            addView(if (puppy) ImageView(context).apply { setImageResource(R.drawable.ic_dog) }
                    else TextView(context).apply {
                        text = "961"; textSize = 26f; setTextColor(p.accent); gravity = Gravity.CENTER
                        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                    },
                LinearLayout.LayoutParams(dp(56), dp(56)))
            addView(TextView(context).apply {
                text = (if (pal.puppy == puppy) "✓ " else "") + label
                textSize = 14f; setTextColor(p.text); gravity = Gravity.CENTER
            })
            setOnClickListener {
                if (pal.puppy != puppy) {
                    Theme.set(this@MainActivity, puppy)
                    BusWidget.updateAll(this@MainActivity) // widgets follow the theme too
                    tab = 2
                    recreate()
                }
            }
        }
        themes.addView(themeCard("Classic", false), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        themes.addView(themeCard("Puppy", true), LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(themes)

        box.addView(sectionTitle("Alerts"))
        val alerts = panel()
        alerts.addView(permRow("Notifications", "So leave alerts can reach you", !needsNotifications()) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        })
        alerts.addView(permRow("Exact alarms", "So alerts arrive on the minute", !needsExactAlarms()) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        })
        alerts.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(8))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply { text = "Live updates"; textSize = 15f; setTextColor(PAPER) })
                addView(TextView(context).apply {
                    text = "During alert ranges, refresh bus times every 10 s while the screen is on. Shows a quiet Live notification."
                    textSize = 12f; setTextColor(DIM)
                })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(android.widget.Switch(context).apply {
                isChecked = Store.liveEnabled(this@MainActivity)
                setOnCheckedChangeListener { _, on ->
                    Store.setLiveEnabled(this@MainActivity, on)
                    if (on) AlertEngine.rescheduleAsync(this@MainActivity, fetch = true) else LiveService.stop(this@MainActivity)
                }
            })
        })
        alerts.addView(Button(this).apply {
            text = "🔔 Test alert in 5 seconds"; isAllCaps = false
            setOnClickListener {
                AlertEngine.test(this@MainActivity)
                toast("Test alert in 5 seconds. Lock the phone to see the full-screen alert.")
            }
        })
        alerts.addView(para("Rings at alarm volume, even on silent, until you tap I'm going.", DIM, 12f))

        val xiaomi = Build.MANUFACTURER.lowercase().let { it.contains("xiaomi") || it.contains("redmi") || it.contains("poco") }
        alerts.addView(para(
            if (xiaomi) "Xiaomi phones block background apps by default. Do all three so alerts arrive:"
            else "If alerts arrive late, let Bus Pup run in the background:",
            PAPER, 13f).apply { setPadding(0, dp(12), 0, dp(4)) })
        if (xiaomi) alerts.addView(Button(this).apply {
            text = "1. Turn on Autostart"; isAllCaps = false
            setOnClickListener { openMiui("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity") }
        })
        alerts.addView(Button(this).apply {
            text = if (xiaomi) "2. Battery saver → No restrictions" else "Battery: Unrestricted"; isAllCaps = false
            setOnClickListener {
                if (xiaomi) openMiui("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
                    Intent().putExtra("package_name", packageName).putExtra("package_label", getString(R.string.app_name)))
                else startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        })
        alerts.addView(Button(this).apply {
            text = if (xiaomi) "3. Notifications: allow Floating & Lock screen" else "Notification settings"; isAllCaps = false
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            }
        })
        box.addView(alerts)

        box.addView(sectionTitle("App updates"))
        val upd = panel()
        val r = Updater.known(this)
        if (r == null) {
            upd.addView(para("You have v${Updater.current}."))
            upd.addView(Button(this).apply {
                text = "Check for updates"; isAllCaps = false
                setOnClickListener { checkForUpdate(manual = true) }
            })
        } else {
            upd.addView(para("Version ${r.version} is available (you have ${Updater.current})", GREEN).apply {
                setTypeface(typeface, Typeface.BOLD)
            })
            if (r.notes.isNotBlank()) upd.addView(para(r.notes.lines().filter { it.isNotBlank() }.take(6).joinToString("\n"), PAPER, 13f))
            val btn = Button(this).apply {
                text = if (downloading) "Downloading…" else "Update now"; isAllCaps = false
                isEnabled = !downloading
            }
            btn.setOnClickListener { startUpdate(r, btn) }
            progressBtn = btn
            upd.addView(btn)
            upd.addView(para("Your stops and widgets stay after updating.", DIM, 12f))
        }
        box.addView(upd)

        box.addView(sectionTitle("Feedback"))
        box.addView(panel(
            para("Found a problem or have an idea? Send it by WhatsApp, Telegram or email, or post it on GitHub."),
            Button(this).apply {
                text = "💬 Send feedback"; isAllCaps = false
                setOnClickListener { feedbackDialog() }
            },
            para("Your app version and phone model are added so problems are easier to fix.", DIM, 12f),
        ))

        box.addView(sectionTitle("Share"))
        box.addView(panel(
            para("Send Bus Pup to family and friends. They get the download link and simple install steps."),
            Button(this).apply {
                text = "Share Bus Pup"; isAllCaps = false
                setOnClickListener { shareApp() }
            },
        ))

        box.addView(sectionTitle("About"))
        box.addView(panel(
            para("Bus Pup v${Updater.current}"),
            para("Live arrivals from LTA DataMall via arrivelah. Refreshes every 15 minutes, and about every minute during an alert window.", DIM, 13f),
            para("github.com/htobenothing/bus-catch-andriod", DIM, 12f).apply { setTextIsSelectable(true) },
        ))
    }

    private fun permRow(title: String, why: String, ok: Boolean, fix: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(4), 0, dp(8))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply { text = title; textSize = 15f; setTextColor(PAPER) })
            addView(TextView(context).apply { text = why; textSize = 12f; setTextColor(DIM) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (ok) addView(TextView(context).apply { text = "✓ On"; textSize = 14f; setTextColor(GREEN) })
        else addView(Button(context).apply { text = "Allow"; isAllCaps = false; setOnClickListener { fix() } })
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        StopPicker.onPermissionResult(requestCode)
        render(); if (tab == 2) renderSettings(); renderTabs()
    }

    /** Open a Xiaomi settings page; falls back to the app's system settings. Returns true if opened. */
    private fun openMiui(pkg: String, cls: String, base: Intent = Intent()): Boolean = try {
        startActivity(base.setClassName(pkg, cls)); true
    } catch (e: Exception) {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))); false
    }

    private fun feedbackDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val kinds = listOf("🐞 Something's wrong" to "bug", "💡 Idea" to "idea", "💬 Other" to "feedback")
        val kind = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, kinds.map { it.first })
        }
        box.addView(kind)
        val msg = EditText(this).apply {
            hint = "What happened, or what would you like?"
            minLines = 4; gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        box.addView(msg)
        val who = EditText(this).apply {
            hint = "Your name (optional)"; setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        box.addView(who)
        box.addView(TextView(this).apply {
            text = "Your app version and phone model are added automatically."; textSize = 12f
            setPadding(0, dp(6), 0, 0)
        })
        val dlg = AlertDialog.Builder(this)
            .setTitle("Send feedback")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Send", null)
            .setNegativeButton("Cancel", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = msg.text.toString().trim()
                if (text.isEmpty()) { toast("Write a few words first."); return@setOnClickListener }
                val (label, tag) = kinds[kind.selectedItemPosition]
                val name = who.text.toString().trim()
                val title = label.substringAfter(" ") + ": " + text.lineSequence().first().take(60)
                val body = text + "\n\n---\n" + (if (name.isNotEmpty()) "From: $name\n" else "") + Feedback.deviceLine(this)
                dlg.dismiss()
                AlertDialog.Builder(this)
                    .setTitle("Send it with…")
                    .setItems(arrayOf("💬 WhatsApp, Telegram, email…", "🐙 GitHub (needs a GitHub account)")) { _, which ->
                        if (which == 0) Feedback.share(this, "Bus Pup feedback: $title", "Bus Pup feedback · $label\n\n$body")
                        else Feedback.openGitHubIssue(this, title, body, tag)
                    }
                    .show()
            }
        }
        dlg.show()
    }

    /** If the app crashed last time, offer to send the details. */
    private fun offerCrashReport() {
        val crash = Feedback.pendingCrash(this) ?: return
        Feedback.clearCrash(this) // ask once
        AlertDialog.Builder(this)
            .setTitle("Bus Pup closed unexpectedly")
            .setMessage("Share the details so it can be fixed? They include technical details, your app version and phone model, but no stops or personal info.")
            .setPositiveButton("Share details") { _, _ ->
                Feedback.share(this, "Bus Pup crash report", "Bus Pup closed unexpectedly.\n\n${Feedback.deviceLine(this)}\n\n$crash")
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun shareApp() {
        val msg = """
            |🐶🚌 Try Bus Pup! It shows live Singapore bus arrivals and tells you exactly when to leave home, so you don't wait long at the stop.
            |
            |Download (Android): $SHARE_APK
            |
            |How to install:
            |1. Open the link on your Android phone and download BusPup.apk
            |2. Open the file. If asked, allow installs from your browser
            |3. If Play Protect warns, tap More details → Install anyway
            |4. Open Bus Pup, tap + Add stop and enter the 5-digit code on your bus stop sign
            |
            |Updates come through the app (Settings → Check for updates).
        """.trimMargin()
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "Bus Pup: never wait long for your bus")
                .putExtra(Intent.EXTRA_TEXT, msg),
            "Share Bus Pup"
        ))
    }

    // ---------- updates ----------

    private fun checkForUpdate(manual: Boolean) {
        if (manual) toast("Checking for updates…")
        io.execute {
            val r = Updater.check(this)
            ui.post {
                render(); if (tab == 2 && !downloading) renderSettings(); renderTabs()
                if (manual && r == null) toast("You're on the latest version (v${Updater.current}).")
            }
        }
    }

    private fun startUpdate(r: Updater.Release, btn: Button) {
        downloading = true
        btn.isEnabled = false; btn.text = "Downloading 0%"
        io.execute {
            try {
                val apk = Updater.download(this, r) { pct -> ui.post { progressBtn?.text = "Downloading $pct%" } }
                ui.post {
                    downloading = false
                    val b = progressBtn ?: btn
                    b.isEnabled = true; b.text = "Install update"
                    b.setOnClickListener { if (!Updater.install(this, apk)) pendingApk = apk }
                    if (!Updater.install(this, apk)) {
                        pendingApk = apk
                        toast("Allow Bus Pup to install updates, then come back.")
                    }
                }
            } catch (e: Exception) {
                ui.post {
                    downloading = false
                    progressBtn?.let { it.isEnabled = true; it.text = "Try again" }
                    toast("Couldn't download the update. Check your connection and try again.")
                }
            }
        }
    }

    // ---------- add / edit ----------

    private fun editDialog(existing: Route?) {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        fun label(t: String) = TextView(this).apply { text = t; textSize = 13f; setPadding(0, dp(10), 0, 0) }
        fun field(hint: String, value: String, type: Int) = EditText(this).apply {
            this.hint = hint; setText(value); inputType = type; setSingleLine()
        }

        val code = field("e.g. 83139", existing?.stopCode ?: "", InputType.TYPE_CLASS_NUMBER)
        val svc = field("e.g. 155 or 155, 43", existing?.services?.joinToString(", ") ?: "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
        val name = field("e.g. Home to work", existing?.label ?: "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)

        form.addView(Button(this).apply {
            text = "🔎 Find stop by name or near me"; isAllCaps = false
            setOnClickListener {
                StopPicker(this@MainActivity, io) { stop ->
                    code.setText(stop.code)
                    if (name.text.isBlank()) name.setText(stop.name)
                    findBuses(stop.code, svc) // straight to ticking the buses at that stop
                }.show()
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        form.addView(label("Or type the bus stop code (5 digits on the stop sign)"))
        val codeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        codeRow.addView(code, LinearLayout.LayoutParams(0, -2, 1f))
        codeRow.addView(Button(this).apply {
            text = "Find buses"; isAllCaps = false
            setOnClickListener { findBuses(code.text.toString().trim(), svc) }
        })
        form.addView(codeRow)

        form.addView(label("Bus numbers (one or more, separated by commas)")); form.addView(svc)
        form.addView(label("Label (optional)"))
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
                        AlertEngine.rescheduleAsync(this, fetch = true)
                        // Changing a route's settings re-arms today's alert
                        Store.clearAlerted(this, r.id)
                        Trip.reopen(this, r.id) // saving a stop re-arms it
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
                    Store.delete(this, ex.id); BusWidget.updateAll(this); AlertEngine.rescheduleAsync(this); render(); dlg.dismiss()
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
