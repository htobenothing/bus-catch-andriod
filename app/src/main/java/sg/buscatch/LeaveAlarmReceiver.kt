package sg.buscatch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Alarms set by versions before 1.15 land here; hand them to the new alert engine. */
class LeaveAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        AlertEngine.rescheduleAsync(ctx, fetch = true)
    }
}
