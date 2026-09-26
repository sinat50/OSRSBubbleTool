package com.sinat.osrsbubbletool

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// Wakes up when a farming timer is due, and after the phone restarts or the app updates
// (which wipes alarms) so the timers can be set again.
class FarmingTimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            FarmingTimers.ACTION_DONE ->
                FarmingTimers.onAlarm(context, intent.getIntExtra(FarmingTimers.EXTRA_ID, -1))
            else -> FarmingTimers.rescheduleAll(context)
        }
    }
}
