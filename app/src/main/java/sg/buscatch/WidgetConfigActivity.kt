package sg.buscatch

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Shown when a widget is placed (or reconfigured): pick which saved stop it should show. */
class WidgetConfigActivity : Activity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // If the user backs out, the launcher removes the half-added widget
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val routes = Store.routes(this)
        val current = Store.widgetRoute(this, widgetId)

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(16))
        }
        box.addView(TextView(this).apply {
            text = "Which stop should this widget show?"
            textSize = 18f; setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(12))
        })

        if (routes.isEmpty()) {
            box.addView(TextView(this).apply {
                text = "You haven't saved any stops yet. Add one in Bus Catch, then come back here."
                textSize = 14f
            })
            box.addView(Button(this).apply {
                text = "Open Bus Catch"; isAllCaps = false
                setOnClickListener { startActivity(Intent(this@WidgetConfigActivity, MainActivity::class.java)) }
            })
        } else {
            routes.forEach { r ->
                box.addView(Button(this).apply {
                    isAllCaps = false
                    textSize = 15f
                    text = (if (r.id == current) "✓ " else "") + "${r.displayName}\n${r.serviceLabel} · stop ${r.stopCode}"
                    setOnClickListener { choose(r) }
                }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
            }
            box.addView(TextView(this).apply {
                text = "Tip: add another Bus Catch widget for each stop you want on your home screen."
                textSize = 12f; setTextColor(Color.GRAY); setPadding(0, dp(8), 0, 0)
            })
        }
        setContentView(ScrollView(this).apply { addView(box) })
    }

    private fun choose(r: Route) {
        Store.setWidgetRoute(this, widgetId, r.id)
        BusWidget.update(this, widgetId)
        RefreshWorker.refreshSoon(this)
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }
}
