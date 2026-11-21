package io.github.kevinnortier.isstracker.orbit

import java.time.Duration
import java.time.Instant
import kotlin.math.max
import kotlin.math.sqrt

/** A satellite's direction from the observer at one moment. */
data class SkyPoint(val time: Instant, val azimuthDeg: Double, val elevationDeg: Double)

/** One pass of the satellite over the observer, above the minimum elevation. */
data class Pass(
    val rise: SkyPoint,
    /** Highest point of the pass. */
    val apex: SkyPoint,
    val set: SkyPoint,
    /** True if the satellite can be seen with the naked eye at some point during the pass. */
    val visible: Boolean,
    /** First and last moment it is visible to the eye (null if not visible). */
    val visibleFrom: Instant?,
    val visibleUntil: Instant?,
) {
    val duration: Duration get() = Duration.between(rise.time, set.time)

    /** True if the satellite is above the minimum elevation at [time]. */
    fun isInProgress(time: Instant): Boolean = !time.isBefore(rise.time) && time.isBefore(set.time)
}

/**
 * Finds passes of a satellite over an observer.
 *
 * Steps through time looking for elevation crossings of [minElevationDeg], refines the crossing
 * times by bisection and the apex by golden-section search (both to ~0.1 s). Passes that stay
 * above the minimum for less than the search step (well under a minute) can be missed; for the
 * ISS that only affects passes peaking a fraction of a degree above the minimum.
 */
class PassPredictor(
    tle: Tle,
    private val observer: GeodeticLocation,
    private val minElevationDeg: Double = 0.0,
) {
    private val sgp4 = Sgp4(tle)

    private companion object {
        val STEP: Duration = Duration.ofSeconds(30)
        const val PRECISION_MILLIS = 100L
        val VISIBILITY_STEP: Duration = Duration.ofSeconds(10)

        /** How far back/forward to look for the rise/set of a pass that is cut by the search window. */
        val MAX_PASS_LENGTH: Duration = Duration.ofMinutes(30)
        val GOLDEN = (sqrt(5.0) - 1.0) / 2.0
    }

    /**
     * Returns all passes that are above the minimum elevation at some time in [from, until).
     * A pass in progress at [from] is included with its real (earlier) rise time, and a pass
     * that starts before [until] is included with its real (later) set time.
     */
    fun passes(from: Instant, until: Instant): List<Pass> {
        val result = mutableListOf<Pass>()
        var t = from
        var prevElevation = relativeElevation(t)
        var riseTime: Instant? = if (prevElevation >= 0.0) findCrossingBackward(from) else null

        while (t.isBefore(until)) {
            val next = t.plus(STEP)
            val elevation = relativeElevation(next)
            if (riseTime == null && prevElevation < 0.0 && elevation >= 0.0) {
                riseTime = bisect(t, next)
            } else if (riseTime != null && prevElevation >= 0.0 && elevation < 0.0) {
                result += buildPass(riseTime, bisect(t, next))
                riseTime = null
            }
            t = next
            prevElevation = elevation
        }
        // A pass that is still in progress at the end of the window.
        if (riseTime != null) {
            findCrossingForward(t)?.let { result += buildPass(riseTime, it) }
        }
        return result
    }

    /** Look angles at [time] (convenience for UI code that already has a predictor). */
    fun lookAngles(time: Instant): LookAngles = Topocentric.lookAngles(observer, sgp4.propagate(time))

    private fun relativeElevation(time: Instant): Double = lookAngles(time).elevationDeg - minElevationDeg

    /** [above] has relative elevation >= 0, [below] < 0 (in either time order). Returns the crossing. */
    private fun bisect(a: Instant, b: Instant): Instant {
        var lo = a.toEpochMilli()
        var hi = b.toEpochMilli()
        val loAbove = relativeElevation(a) >= 0.0
        while (hi - lo > PRECISION_MILLIS) {
            val mid = (lo + hi) / 2
            val midAbove = relativeElevation(Instant.ofEpochMilli(mid)) >= 0.0
            if (midAbove == loAbove) lo = mid else hi = mid
        }
        // Return the first moment above the minimum for a rise, the last for a set.
        return Instant.ofEpochMilli(if (loAbove) lo else hi)
    }

    /** From a time above the minimum, steps backwards to find where the pass rose. */
    private fun findCrossingBackward(start: Instant): Instant {
        var t = start
        val limit = start.minus(MAX_PASS_LENGTH)
        while (t.isAfter(limit)) {
            val prev = t.minus(STEP)
            if (relativeElevation(prev) < 0.0) return bisect(prev, t)
            t = prev
        }
        return limit
    }

    /** From a time above the minimum, steps forwards to find where the pass sets. */
    private fun findCrossingForward(start: Instant): Instant? {
        var t = start
        val limit = start.plus(MAX_PASS_LENGTH)
        while (t.isBefore(limit)) {
            val next = t.plus(STEP)
            if (relativeElevation(next) < 0.0) return bisect(t, next)
            t = next
        }
        return null
    }

    private fun buildPass(rise: Instant, set: Instant): Pass {
        val apexTime = findApex(rise, set)
        val (visibleFrom, visibleUntil) = visibleWindow(rise, set)
        return Pass(
            rise = skyPoint(rise),
            apex = skyPoint(apexTime),
            set = skyPoint(set),
            visible = visibleFrom != null,
            visibleFrom = visibleFrom,
            visibleUntil = visibleUntil,
        )
    }

    /** Golden-section search for the highest elevation between [a] and [b]. */
    private fun findApex(a: Instant, b: Instant): Instant {
        var lo = a.toEpochMilli().toDouble()
        var hi = b.toEpochMilli().toDouble()
        fun el(ms: Double) = lookAngles(Instant.ofEpochMilli(ms.toLong())).elevationDeg
        var x1 = hi - GOLDEN * (hi - lo)
        var x2 = lo + GOLDEN * (hi - lo)
        var f1 = el(x1)
        var f2 = el(x2)
        while (hi - lo > PRECISION_MILLIS) {
            if (f1 < f2) {
                lo = x1; x1 = x2; f1 = f2
                x2 = lo + GOLDEN * (hi - lo); f2 = el(x2)
            } else {
                hi = x2; x2 = x1; f2 = f1
                x1 = hi - GOLDEN * (hi - lo); f1 = el(x1)
            }
        }
        return Instant.ofEpochMilli(((lo + hi) / 2).toLong())
    }

    /**
     * First and last sampled moment (10 s steps) when the satellite is visible to the eye:
     * above the horizon/minimum, sunlit, and the observer's sky is dark.
     */
    private fun visibleWindow(rise: Instant, set: Instant): Pair<Instant?, Instant?> {
        var first: Instant? = null
        var last: Instant? = null
        val threshold = max(minElevationDeg, 0.0)
        var t = rise
        while (!t.isAfter(set)) {
            val state = sgp4.propagate(t)
            val look = Topocentric.lookAngles(observer, state)
            if (look.elevationDeg >= threshold &&
                Sun.elevationDeg(observer, t) < Sun.DARK_SKY_SUN_ELEVATION_DEG &&
                Sun.isSunlit(state.position, t)
            ) {
                if (first == null) first = t
                last = t
            }
            t = t.plus(VISIBILITY_STEP)
        }
        return first to last
    }

    private fun skyPoint(time: Instant): SkyPoint {
        val look = lookAngles(time)
        return SkyPoint(time, look.azimuthDeg, look.elevationDeg)
    }
}
