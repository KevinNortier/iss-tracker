package io.github.kevinnortier.isstracker.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import io.github.kevinnortier.isstracker.Satellite
import io.github.kevinnortier.isstracker.data.TleRepository
import io.github.kevinnortier.isstracker.data.settings.SettingsRepository
import io.github.kevinnortier.isstracker.data.settings.resolveObserver
import io.github.kevinnortier.isstracker.orbit.PassPredictor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Keeps one exact alarm per selected pass, at the user's chosen lead time before rise or apex.
 *
 * [reschedule] recalculates the selected passes from the latest orbit data and location, so it is
 * called whenever any of those change, after a reboot and after an app update.
 */
class PassReminderScheduler(
    private val context: Context,
    private val satellite: Satellite,
    private val settings: SettingsRepository,
    private val tleRepository: TleRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val mutex = Mutex()

    private companion object {
        const val ACTION_REMINDER = "io.github.kevinnortier.isstracker.action.PASS_REMINDER"
        val LOOK_AHEAD_LIMIT: Duration = Duration.ofDays(11)
    }

    /** False if the system only allows inexact alarms (reminders may then be a few minutes late). */
    fun exactAlarmsAllowed(): Boolean = alarmManager.canScheduleExactAlarms()

    /**
     * Recalculates and re-arms all reminders.
     * @param showLateNow keys whose reminder should be shown immediately if its time has already passed.
     */
    suspend fun reschedule(showLateNow: Set<PassKey> = emptySet()) = mutex.withLock {
        // Never stop halfway: cancelling old alarms and recording new ones must happen together,
        // otherwise an alarm could be left behind that we no longer know about.
        withContext(Dispatchers.Default + NonCancellable) { rescheduleLocked(showLateNow) }
    }

    private suspend fun rescheduleLocked(showLateNow: Set<PassKey>) {
        val current = settings.settings.first()
        val selected = settings.selectedPasses.first().filter { it.noradId == satellite.noradId }.toSet()
        val previouslyScheduled = settings.scheduledReminders()

        if (selected.isEmpty()) {
            previouslyScheduled.forEach(::cancelAlarm)
            settings.setScheduledReminders(emptySet())
            return
        }

        // Without orbit data or a location we can't recalculate; keep the alarms we have.
        val observer = current.resolveObserver() ?: return
        val tle = tleRepository.getCached(satellite.noradId)?.tle ?: return

        val now = clock.instant()
        val lastRise = Instant.ofEpochMilli(selected.maxOf { it.riseMillis })
        val until = minOf(lastRise.plus(Duration.ofHours(1)), now.plus(LOOK_AHEAD_LIMIT))
        val passes = if (until.isAfter(now)) {
            PassPredictor(tle, observer.location, current.minElevationDeg).passes(now, until)
        } else {
            emptyList()
        }

        val plans = planReminders(
            selected = selected,
            passes = passes,
            trigger = current.notifyTrigger,
            leadMinutes = current.notifyLeadMinutes,
            now = now,
            showLateNow = showLateNow,
        )

        previouslyScheduled.forEach(::cancelAlarm)
        val scheduled = mutableSetOf<PassKey>()
        for (plan in plans) {
            when (plan) {
                is ReminderPlan.Schedule -> {
                    val key = PassKey.of(satellite.noradId, plan.pass)
                    setAlarm(plan.at, ReminderInfo.from(key, satellite.displayName, current.notifyTrigger, plan.pass))
                    scheduled += key
                }
                is ReminderPlan.ShowNow -> {
                    val key = PassKey.of(satellite.noradId, plan.pass)
                    ReminderNotifications.showReminder(
                        context,
                        ReminderInfo.from(key, satellite.displayName, current.notifyTrigger, plan.pass),
                        now,
                    )
                }
                is ReminderPlan.Keep -> Unit
                is ReminderPlan.Drop -> ReminderNotifications.cancel(context, plan.key)
            }
        }
        settings.setScheduledReminders(scheduled)

        // Follow the recalculated rise times so small shifts don't add up, and forget ended passes.
        val newSelection = updatedSelection(satellite.noradId, plans)
        if (newSelection != selected) settings.setSelectedPasses(newSelection)
    }

    private fun setAlarm(at: Instant, info: ReminderInfo) {
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            ReminderNotifications.notificationId(info.key),
            info.putInto(alarmIntent(info.key)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        if (alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), pendingIntent)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), pendingIntent)
        }
    }

    private fun cancelAlarm(key: PassKey) {
        PendingIntent.getBroadcast(
            context,
            ReminderNotifications.notificationId(key),
            alarmIntent(key),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        )?.let {
            alarmManager.cancel(it)
            it.cancel()
        }
    }

    /** A distinct intent per pass (the data URI makes them unequal for PendingIntent matching). */
    private fun alarmIntent(key: PassKey) = Intent(context, PassAlarmReceiver::class.java)
        .setAction(ACTION_REMINDER)
        .setData("isstracker://reminder/${key.noradId}/${key.riseMillis}".toUri())
}
