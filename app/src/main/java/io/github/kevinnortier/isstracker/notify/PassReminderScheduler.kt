package io.github.kevinnortier.isstracker.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import io.github.kevinnortier.isstracker.data.TleRepository
import io.github.kevinnortier.isstracker.data.settings.SettingsRepository
import io.github.kevinnortier.isstracker.data.settings.resolveObserver
import io.github.kevinnortier.isstracker.orbit.Pass
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
 * Keeps one exact alarm per selected pass (of any tracked satellite), at the user's chosen lead time
 * before rise or apex.
 *
 * [reschedule] recalculates the selected passes from the latest orbit data and location, so it is
 * called whenever any of those change, after a reboot and after an app update.
 */
class PassReminderScheduler(
    private val context: Context,
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

    /** True if [pass] of satellite [noradId] has a reminder turned on. */
    suspend fun hasReminder(noradId: Int, pass: Pass): Boolean =
        settings.selectedPasses.first().any { it.noradId == noradId && matchPass(it, listOf(pass)) != null }

    /**
     * Turns the reminder for [pass] of satellite [noradId] on or off and re-arms the alarms. When
     * turning it on after the reminder time has already passed, the reminder is shown right away.
     */
    suspend fun toggle(noradId: Int, pass: Pass) {
        val existing = settings.selectedPasses.first()
            .firstOrNull { it.noradId == noradId && matchPass(it, listOf(pass)) != null }
        if (existing != null) {
            settings.setPassSelected(existing, false)
            reschedule()
        } else {
            val key = PassKey.of(noradId, pass)
            settings.setPassSelected(key, true)
            reschedule(showLateNow = setOf(key))
        }
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
        val tracked = settings.trackedSatellites.first()
        val selected = settings.selectedPasses.first()
        val previouslyScheduled = settings.scheduledReminders()

        if (selected.isEmpty()) {
            previouslyScheduled.forEach(::cancelAlarm)
            settings.setScheduledReminders(emptySet())
            return
        }

        // Without a location we can't recalculate anything; keep the alarms we have.
        val observer = current.resolveObserver() ?: return

        val now = clock.instant()
        val split = splitSelection(selected, tracked.map { it.noradId }.toSet())

        val newScheduled = mutableSetOf<PassKey>()
        val newSelection = mutableSetOf<PassKey>()
        val alarmsToSet = mutableListOf<Pair<Instant, ReminderInfo>>()
        val showNow = mutableListOf<ReminderInfo>()
        val notificationsToCancel = mutableListOf<PassKey>()

        for (satellite in tracked) {
            val keys = split.bySatellite[satellite.noradId] ?: continue
            val tle = tleRepository.getCached(satellite.noradId)?.tle
            if (tle == null) {
                // No orbit data for this one right now: leave its reminders and alarms as they are.
                newSelection += keys
                newScheduled += previouslyScheduled.filter { it in keys }
                continue
            }

            val lastRise = Instant.ofEpochMilli(keys.maxOf { it.riseMillis })
            val until = minOf(lastRise.plus(Duration.ofHours(1)), now.plus(LOOK_AHEAD_LIMIT))
            val passes = if (until.isAfter(now)) {
                runCatching {
                    PassPredictor(tle, observer.location, current.minElevationDeg).passes(now, until)
                }.getOrDefault(emptyList())
            } else {
                emptyList()
            }

            val plans = planReminders(keys, passes, current.notifyTrigger, current.notifyLeadMinutes, now, showLateNow)
            for (plan in plans) {
                when (plan) {
                    is ReminderPlan.Schedule -> {
                        val key = PassKey.of(satellite.noradId, plan.pass)
                        alarmsToSet += plan.at to ReminderInfo.from(key, satellite.displayName, current.notifyTrigger, plan.pass)
                        newScheduled += key
                    }
                    is ReminderPlan.ShowNow -> {
                        val key = PassKey.of(satellite.noradId, plan.pass)
                        showNow += ReminderInfo.from(key, satellite.displayName, current.notifyTrigger, plan.pass)
                    }
                    is ReminderPlan.Keep -> Unit
                    is ReminderPlan.Drop -> notificationsToCancel += plan.key
                }
            }
            // Follow the recalculated rise times so small shifts don't add up, and forget ended passes.
            newSelection += updatedSelection(satellite.noradId, plans)
        }

        // Reminders of satellites that are no longer tracked are forgotten.
        notificationsToCancel += split.untracked

        // Cancel every old alarm we're not keeping, then set the new ones.
        (previouslyScheduled - newScheduled).forEach(::cancelAlarm)
        alarmsToSet.forEach { (at, info) -> setAlarm(at, info) }
        showNow.forEach { ReminderNotifications.showReminder(context, it, now) }
        notificationsToCancel.forEach { ReminderNotifications.cancel(context, it) }

        settings.setScheduledReminders(newScheduled)
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
