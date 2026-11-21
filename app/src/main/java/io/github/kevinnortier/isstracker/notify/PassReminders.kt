package io.github.kevinnortier.isstracker.notify

import io.github.kevinnortier.isstracker.data.settings.NotifyTrigger
import io.github.kevinnortier.isstracker.orbit.Pass
import java.time.Duration
import java.time.Instant
import kotlin.math.abs

/**
 * Identifies a pass the user wants a reminder for.
 *
 * Pass times shift slightly every time new orbit data arrives, so a key is matched to the
 * recalculated pass whose rise time is closest (within [MATCH_TOLERANCE]). ISS passes are
 * about 90 minutes apart, so this can't pick the wrong pass.
 */
data class PassKey(val noradId: Int, val riseMillis: Long) {

    fun encode(): String = "$noradId:$riseMillis"

    companion object {
        val MATCH_TOLERANCE: Duration = Duration.ofMinutes(10)

        fun of(noradId: Int, pass: Pass) = PassKey(noradId, pass.rise.time.toEpochMilli())

        fun decode(text: String): PassKey? {
            val parts = text.split(':')
            if (parts.size != 2) return null
            val norad = parts[0].toIntOrNull() ?: return null
            val rise = parts[1].toLongOrNull() ?: return null
            return PassKey(norad, rise)
        }
    }
}

/** Finds the pass in [passes] that [key] refers to, or null if none is close enough. */
fun matchPass(key: PassKey, passes: List<Pass>): Pass? = passes
    .minByOrNull { abs(it.rise.time.toEpochMilli() - key.riseMillis) }
    ?.takeIf { abs(it.rise.time.toEpochMilli() - key.riseMillis) <= PassKey.MATCH_TOLERANCE.toMillis() }

/** The moment the reminder is about: the rise or the highest point of the pass. */
fun triggerTime(pass: Pass, trigger: NotifyTrigger): Instant = when (trigger) {
    NotifyTrigger.RISE -> pass.rise.time
    NotifyTrigger.APEX -> pass.apex.time
}

/** When the reminder should be shown: [leadMinutes] before the trigger moment. */
fun reminderTime(pass: Pass, trigger: NotifyTrigger, leadMinutes: Int): Instant =
    triggerTime(pass, trigger).minus(Duration.ofMinutes(leadMinutes.toLong()))

/** What to do with one selected pass when (re)scheduling reminders. */
sealed interface ReminderPlan {
    val key: PassKey

    /** Schedule an alarm at [at]. [pass] is the matched (recalculated) pass. */
    data class Schedule(override val key: PassKey, val pass: Pass, val at: Instant) : ReminderPlan

    /** The reminder time has passed but the pass hasn't reached its trigger yet: show it now. */
    data class ShowNow(override val key: PassKey, val pass: Pass) : ReminderPlan

    /** Nothing to schedule (reminder time passed, or the pass is over), but keep the selection until it ends. */
    data class Keep(override val key: PassKey, val pass: Pass) : ReminderPlan

    /** The pass is over or no longer exists (e.g. after raising the minimum elevation): forget it. */
    data class Drop(override val key: PassKey) : ReminderPlan
}

/**
 * Decides what to do for each selected pass.
 *
 * @param showLateNow keys for which a late reminder should be shown immediately (the user just
 *   tapped the bell). For other keys a missed reminder is skipped, e.g. after a reboot.
 */
fun planReminders(
    selected: Set<PassKey>,
    passes: List<Pass>,
    trigger: NotifyTrigger,
    leadMinutes: Int,
    now: Instant,
    showLateNow: Set<PassKey> = emptySet(),
): List<ReminderPlan> = selected.map { key ->
    val pass = matchPass(key, passes)
    when {
        pass == null -> ReminderPlan.Drop(key)
        !pass.set.time.isAfter(now) -> ReminderPlan.Drop(key)
        else -> {
            val at = reminderTime(pass, trigger, leadMinutes)
            when {
                at.isAfter(now) -> ReminderPlan.Schedule(key, pass, at)
                key in showLateNow && triggerTime(pass, trigger).isAfter(now) -> ReminderPlan.ShowNow(key, pass)
                else -> ReminderPlan.Keep(key, pass)
            }
        }
    }
}

/** The selection after planning: dropped keys removed, kept keys moved to their recalculated rise time. */
fun updatedSelection(noradId: Int, plans: List<ReminderPlan>): Set<PassKey> = plans.mapNotNull { plan ->
    when (plan) {
        is ReminderPlan.Schedule -> PassKey.of(noradId, plan.pass)
        is ReminderPlan.ShowNow -> PassKey.of(noradId, plan.pass)
        is ReminderPlan.Keep -> PassKey.of(noradId, plan.pass)
        is ReminderPlan.Drop -> null
    }
}.toSet()
