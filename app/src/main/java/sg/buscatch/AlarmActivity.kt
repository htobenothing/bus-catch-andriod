package sg.buscatch

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Full-screen "Leave now" page, shown over the lock screen while the alert rings. */
class AlarmActivity : Activity() {
    private var notifId = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        val pal = Theme.of(this)
        setTheme(pal.styleRes)
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = pal.bg
        window.navigationBarColor = pal.bg

        notifId = intent.getIntExtra(Notifier.EXTRA_ID, 0)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setBackgroundColor(pal.bg); setPadding(dp(28), dp(40), dp(28), dp(40))
        }
        if (pal.puppy) box.addView(ImageView(this).apply { setImageBitmap(Rewards.puppyBitmap(this@AlarmActivity, dp(140))) },
            LinearLayout.LayoutParams(dp(140), dp(140)).apply { bottomMargin = dp(16) })
        else box.addView(TextView(this).apply {
            text = "🚌"; textSize = 72f; gravity = Gravity.CENTER
        })
        box.addView(TextView(this).apply {
            text = intent.getStringExtra(Notifier.EXTRA_TITLE) ?: "Leave now"
            textSize = 30f; setTextColor(pal.accent); gravity = Gravity.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        })
        box.addView(TextView(this).apply {
            text = intent.getStringExtra(Notifier.EXTRA_BODY) ?: ""
            textSize = 18f; setTextColor(pal.text); gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(32))
        })
        box.addView(Button(this).apply {
            text = "I'm going"; isAllCaps = false; textSize = 20f
            setTextColor(if (pal.puppy) 0xFFFFFFFF.toInt() else pal.bg)
            background = GradientDrawable().apply { setColor(pal.go); cornerRadius = dp(32).toFloat() }
            setPadding(dp(40), dp(16), dp(40), dp(16))
            setOnClickListener { going() }
        })
        setContentView(box)
    }

    private fun stopAndClose() {
        Notifier.dismiss(this, notifId)
        finish()
    }

    /** "I'm going": stop the alarm and give the puppy its treat. */
    private fun going() {
        Rewards.onGoing(this, intent.getStringExtra(Notifier.EXTRA_ROUTE_ID))?.let {
            android.widget.Toast.makeText(this, it, android.widget.Toast.LENGTH_LONG).show()
        }
        BusWidget.updateAll(this)
        stopAndClose()
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() { stopAndClose() }
}
