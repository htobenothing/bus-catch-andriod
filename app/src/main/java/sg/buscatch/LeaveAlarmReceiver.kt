package sg.buscatch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Fires at the computed leave-home time for one route. */
class LeaveAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ROUTE) ?: return
        val busMs = intent.getLongExtra(EXTRA_BUS_MS, 0)
        val bus = intent.getStringExtra(EXTRA_BUS) ?: ""
        val route = Store.routes(ctx).firstOrNull { it.id == id } ?: return
        if (route.alertOn && route.inWindow() && !Store.alertedToday(ctx, id)) {
            Notifier.leaveNow(ctx, route, busMs, bus)
            Store.markAlerted(ctx, id)
        }
        RefreshWorker.refreshSoon(ctx)
    }

    companion object {
        const val EXTRA_ROUTE = "route"
        const val EXTRA_BUS_MS = "bus_ms"
        const val EXTRA_BUS = "bus"
    }
}
