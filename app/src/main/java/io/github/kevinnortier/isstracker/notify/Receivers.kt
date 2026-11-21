package io.github.kevinnortier.isstracker.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.kevinnortier.isstracker.IssTrackerApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Fired by the alarm: shows the reminder notification. */
class PassAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val info = ReminderInfo.fromIntent(intent) ?: return
        ReminderNotifications.showReminder(context, info)
    }
}

/** Alarms are cleared on reboot (and may be after an app update), so set them again. */
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext as IssTrackerApp
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                app.container.reminderScheduler.reschedule()
            } finally {
                pending.finish()
            }
        }
    }
}
