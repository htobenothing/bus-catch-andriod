package sg.buscatch

import android.app.AlertDialog
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Quick Settings tile: shows when to leave for the Auto stop (the one whose alert range you're in,
 * or the next). Tap for a pop-up with the next buses, no unlocking needed.
 */
class BusTile : TileService() {
    private val ui = Handler(Looper.getMainLooper())
    private fun hm(ms: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

    override fun onStartListening() {
        super.onStartListening()
        paint()
        refresh { paint() }
    }

    override fun onClick() {
        super.onClick()
        showSummary() // instant, from the last known times
        refresh { paint(); if (dialog?.isShowing == true) showSummary() } // then fresh ones
    }

    private var dialog: AlertDialog? = null

    private fun stop(): Route? = WidgetRoutes.nearest(this, Store.routes(this)) ?: Store.routes(this).firstOrNull()

    /** Fetch fresh times for the shown stop in the background. */
    private fun refresh(then: () -> Unit) {
        val r = stop() ?: return
        Thread {
            Store.fetchStops(this, listOf(r.stopCode))
            ui.post { try { then() } catch (e: Exception) { } }
        }.start()
    }

    private fun paint() {
        val tile = qsTile ?: return
        val r = stop()
        val now = System.currentTimeMillis()
        tile.icon = Icon.createWithResource(this, R.drawable.ic_bus)
        if (r == null) {
            tile.label = "Bus Pup"
            if (Build.VERSION.SDK_INT >= 29) tile.subtitle = "Add a stop"
            tile.state = Tile.STATE_INACTIVE
        } else {
            val plan = Planner.plan(r, Store.cachedStop(this, r.stopCode), now)
            val leave = plan?.leaveAtMs
            tile.label = when {
                plan == null -> "Bus ${r.serviceLabel}"
                plan.upcoming.isEmpty() -> "No buses"
                leave == null -> "Missed it"
                leave <= now + 60_000 -> "Leave NOW"
                else -> "Leave ${hm(leave)}"
            }
            if (Build.VERSION.SDK_INT >= 29) tile.subtitle = plan?.target?.let { "${it.service} at ${hm(it.timeMs)}" } ?: r.displayName
            tile.state = if (leave != null && leave <= now + 3 * 60_000) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        }
        tile.updateTile()
    }

    private fun showSummary() {
        val r = stop()
        val now = System.currentTimeMillis()
        val msg = if (r == null) "Open Bus Pup and add a stop first." else {
            val services = Store.cachedStop(this, r.stopCode)
            val plan = Planner.plan(r, services, now)
            buildString {
                val leave = plan?.leaveAtMs
                append(when {
                    plan == null -> "Loading live times…"
                    plan.upcoming.isEmpty() -> "No buses coming right now."
                    leave == null -> "Too late for the next bus."
                    leave <= now + 60_000 -> "Leave NOW for ${plan.target?.service} at ${hm(plan.target!!.timeMs)}."
                    else -> "Leave at ${hm(leave)} for ${plan.target?.service} at ${hm(plan.target!!.timeMs)}."
                })
                append("\n\n")
                r.services.forEach { no ->
                    val a = services?.firstOrNull { it.no.equals(no, true) }?.arrivals?.filter { it.timeMs > now - 30_000 }
                    append(no.padEnd(5)).append("  ")
                    append(if (a.isNullOrEmpty()) "not running" else a.take(3).joinToString("   ") {
                        val m = ((it.timeMs - now) / 60_000).coerceAtLeast(0)
                        if (m == 0L) "Arr" else "$m min"
                    })
                    append("\n")
                }
            }
        }
        dialog?.dismiss()
        dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(r?.let { "🚏 ${it.displayName}" } ?: "Bus Pup")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .create()
        try { showDialog(dialog!!) } catch (e: Exception) { }
    }
}
