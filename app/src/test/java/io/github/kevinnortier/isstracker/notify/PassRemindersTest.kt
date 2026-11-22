package io.github.kevinnortier.isstracker.notify

import io.github.kevinnortier.isstracker.data.settings.NotifyTrigger
import io.github.kevinnortier.isstracker.orbit.Pass
import io.github.kevinnortier.isstracker.orbit.SkyPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PassRemindersTest {

    private val iss = 25544
    private val base = Instant.parse("2026-11-22T18:00:00Z")
    private fun t(minute: Double): Instant = base.plusMillis((minute * 60_000).toLong())

    /** A 10 minute pass rising at [riseMinute], highest 5 minutes later. */
    private fun pass(riseMinute: Double) = Pass(
        rise = SkyPoint(t(riseMinute), 200.0, 0.0),
        apex = SkyPoint(t(riseMinute + 5), 150.0, 40.0),
        set = SkyPoint(t(riseMinute + 10), 100.0, 0.0),
        visible = true,
        visibleFrom = t(riseMinute),
        visibleUntil = t(riseMinute + 10),
    )

    private fun key(riseMinute: Double) = PassKey(iss, t(riseMinute).toEpochMilli())

    @Test
    fun `keys round trip through text`() {
        val k = key(12.5)
        assertEquals(k, PassKey.decode(k.encode()))
        assertNull(PassKey.decode("garbage"))
        assertNull(PassKey.decode("1:2:3"))
        assertNull(PassKey.decode("abc:123"))
    }

    @Test
    fun `matches the nearest pass within ten minutes`() {
        val passes = listOf(pass(0.0), pass(92.0), pass(184.0))
        assertEquals(passes[1], matchPass(key(93.5), passes)) // new orbit data shifted it 1.5 min
        assertEquals(passes[1], matchPass(key(90.0), passes))
        assertNull(matchPass(key(130.0), passes)) // between passes
        assertNull(matchPass(key(0.0), emptyList()))
    }

    @Test
    fun `reminder time is lead minutes before rise or apex`() {
        val p = pass(60.0)
        assertEquals(t(45.0), reminderTime(p, NotifyTrigger.RISE, 15))
        assertEquals(t(50.0), reminderTime(p, NotifyTrigger.APEX, 15))
        assertEquals(t(0.0), reminderTime(p, NotifyTrigger.RISE, 60))
    }

    @Test
    fun `future reminder is scheduled`() {
        val p = pass(60.0)
        val plans = planReminders(setOf(key(60.0)), listOf(p), NotifyTrigger.RISE, 15, now = t(0.0))
        assertEquals(listOf(ReminderPlan.Schedule(key(60.0), p, t(45.0))), plans)
    }

    @Test
    fun `late reminder is shown now only when just requested`() {
        val p = pass(60.0)
        val now = t(50.0) // reminder time 45 has passed, rise at 60 hasn't
        assertEquals(
            listOf(ReminderPlan.ShowNow(key(60.0), p)),
            planReminders(setOf(key(60.0)), listOf(p), NotifyTrigger.RISE, 15, now, showLateNow = setOf(key(60.0))),
        )
        // e.g. after a reboot: don't show a missed reminder, but keep the selection
        assertEquals(
            listOf(ReminderPlan.Keep(key(60.0), p)),
            planReminders(setOf(key(60.0)), listOf(p), NotifyTrigger.RISE, 15, now),
        )
    }

    @Test
    fun `no late reminder once the trigger moment has passed`() {
        val p = pass(60.0)
        val plans = planReminders(
            setOf(key(60.0)), listOf(p), NotifyTrigger.RISE, 15, now = t(62.0), showLateNow = setOf(key(60.0)),
        )
        assertEquals(listOf(ReminderPlan.Keep(key(60.0), p)), plans)
    }

    @Test
    fun `apex trigger can still be early during the pass`() {
        val p = pass(60.0)
        // At minute 61 the pass has risen, but the apex (65) is still ahead; reminder time 64 is in the future.
        val plans = planReminders(setOf(key(60.0)), listOf(p), NotifyTrigger.APEX, 1, now = t(61.0))
        assertEquals(listOf(ReminderPlan.Schedule(key(60.0), p, t(64.0))), plans)
    }

    @Test
    fun `ended and vanished passes are dropped`() {
        val p = pass(60.0)
        val ended = planReminders(setOf(key(60.0)), listOf(p), NotifyTrigger.RISE, 15, now = t(70.0))
        assertEquals(listOf(ReminderPlan.Drop(key(60.0))), ended)
        val vanished = planReminders(setOf(key(60.0)), emptyList(), NotifyTrigger.RISE, 15, now = t(0.0))
        assertEquals(listOf(ReminderPlan.Drop(key(60.0))), vanished)
    }

    @Test
    fun `selection follows recalculated rise times and forgets dropped passes`() {
        val shifted = pass(61.0) // new orbit data: rises a minute later than when selected
        val plans = planReminders(
            setOf(key(60.0), key(500.0)), listOf(shifted), NotifyTrigger.RISE, 15, now = t(0.0),
        )
        assertEquals(setOf(key(61.0)), updatedSelection(iss, plans))
    }

    @Test
    fun `selection is split by satellite, untracked satellites separately`() {
        val iss1 = PassKey(25544, 1_000L)
        val iss2 = PassKey(25544, 2_000L)
        val so50 = PassKey(27607, 3_000L)
        val removed = PassKey(11111, 4_000L)
        val split = splitSelection(setOf(iss1, iss2, so50, removed), trackedIds = setOf(25544, 27607))
        assertEquals(mapOf(25544 to setOf(iss1, iss2), 27607 to setOf(so50)), split.bySatellite)
        assertEquals(setOf(removed), split.untracked)
    }

    @Test
    fun `notification ids are stable and distinct per pass`() {
        val a = ReminderNotifications.notificationId(key(0.0))
        val b = ReminderNotifications.notificationId(key(92.0))
        assertEquals(a, ReminderNotifications.notificationId(key(0.0)))
        assertTrue(a != b)
    }

    @Test
    fun `notification title counts down to the trigger`() {
        val info = ReminderInfo.from(key(60.0), "ISS", NotifyTrigger.RISE, pass(60.0))
        assertEquals("ISS rises in 15 min", ReminderNotifications.title(info, t(45.0)))
        assertEquals("ISS rises in 1 min", ReminderNotifications.title(info, t(59.5)))
        assertEquals("ISS rises now", ReminderNotifications.title(info, t(60.0)))
        val apex = info.copy(trigger = NotifyTrigger.APEX)
        assertEquals("ISS is highest in 20 min", ReminderNotifications.title(apex, t(45.0)))
    }

    @Test
    fun `notification body mentions visibility`() {
        val info = ReminderInfo.from(key(60.0), "ISS", NotifyTrigger.RISE, pass(60.0))
        assertTrue(ReminderNotifications.body(info).contains("highest 40°"))
        assertTrue(ReminderNotifications.body(info).endsWith("Visible to the naked eye."))
        assertTrue(!ReminderNotifications.body(info.copy(visible = false)).contains("Visible"))
    }
}
