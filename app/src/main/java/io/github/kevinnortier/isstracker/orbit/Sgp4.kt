package io.github.kevinnortier.isstracker.orbit

import java.time.Duration
import java.time.Instant
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Position (km) and velocity (km/s) in the TEME frame (True Equator, Mean Equinox) at [time]. */
data class StateVector(val time: Instant, val position: Vector3, val velocity: Vector3)

class Sgp4Exception(val code: Int, message: String) : RuntimeException(message)

class DeepSpaceNotSupportedException(periodMinutes: Double) : UnsupportedOperationException(
    "Orbital period is %.1f min. Deep-space (SDP4) orbits with a period of 225 min or more are not supported yet."
        .format(periodMinutes),
)

/**
 * SGP4 orbit propagator for near-earth satellites (orbital period < 225 min), such as the ISS.
 *
 * Ported from David Vallado's reference implementation ("Revisiting Spacetrack Report #3",
 * AIAA 2006-6753), using WGS-72 constants as the TLE format expects.
 * Deep-space orbits (SDP4) are not implemented and throw [DeepSpaceNotSupportedException].
 */
class Sgp4(val tle: Tle) {

    private companion object {
        // WGS-72 constants
        const val MU = 398600.8 // km³/s²
        const val EARTH_RADIUS_KM = 6378.135
        val XKE = 60.0 / sqrt(EARTH_RADIUS_KM * EARTH_RADIUS_KM * EARTH_RADIUS_KM / MU)
        const val J2 = 0.001082616
        const val J3 = -0.00000253881
        const val J4 = -0.00000165597
        const val J3OJ2 = J3 / J2

        const val TWO_PI = 2.0 * PI
        const val X2O3 = 2.0 / 3.0
        const val TEMP4 = 1.5e-12
        const val DEG2RAD = PI / 180.0
        const val XPDOTP = 1440.0 / TWO_PI // rev/day -> rad/min
        val VKMPERSEC = EARTH_RADIUS_KM * XKE / 60.0
    }

    // Elements in SGP4 units (radians, rad/min)
    private val ecco = tle.eccentricity
    private val inclo = tle.inclinationDeg * DEG2RAD
    private val nodeo = tle.raanDeg * DEG2RAD
    private val argpo = tle.argOfPerigeeDeg * DEG2RAD
    private val mo = tle.meanAnomalyDeg * DEG2RAD
    private val bstar = tle.bstar
    private val noKozai = tle.meanMotion / XPDOTP

    // Initialised constants
    private val noUnkozai: Double
    private val ao: Double
    private val con41: Double
    private val x1mth2: Double
    private val x7thm1: Double
    private val isimp: Boolean
    private val eta: Double
    private val cc1: Double
    private val cc4: Double
    private val cc5: Double
    private val mdot: Double
    private val argpdot: Double
    private val nodedot: Double
    private val omgcof: Double
    private val xmcof: Double
    private val nodecf: Double
    private val t2cof: Double
    private val xlcof: Double
    private val aycof: Double
    private val delmo: Double
    private val sinmao: Double
    private var d2 = 0.0
    private var d3 = 0.0
    private var d4 = 0.0
    private var t3cof = 0.0
    private var t4cof = 0.0
    private var t5cof = 0.0

    init {
        // ---- initl ----
        val eccsq = ecco * ecco
        val omeosq = 1.0 - eccsq
        val rteosq = sqrt(omeosq)
        val cosio = cos(inclo)
        val cosio2 = cosio * cosio

        val ak = (XKE / noKozai).pow(X2O3)
        val d1 = 0.75 * J2 * (3.0 * cosio2 - 1.0) / (rteosq * omeosq)
        var del = d1 / (ak * ak)
        val adel = ak * (1.0 - del * del - del * (1.0 / 3.0 + 134.0 * del * del / 81.0))
        del = d1 / (adel * adel)
        noUnkozai = noKozai / (1.0 + del)

        ao = (XKE / noUnkozai).pow(X2O3)
        val sinio = sin(inclo)
        val po = ao * omeosq
        val con42 = 1.0 - 5.0 * cosio2
        con41 = -con42 - cosio2 - cosio2
        val posq = po * po
        val rp = ao * (1.0 - ecco)

        val periodMinutes = TWO_PI / noUnkozai
        if (periodMinutes >= 225.0) throw DeepSpaceNotSupportedException(periodMinutes)
        if (omeosq < 0.0 || noUnkozai < 0.0) throw Sgp4Exception(1, "Invalid orbital elements")

        // ---- sgp4init ----
        val ss = 78.0 / EARTH_RADIUS_KM + 1.0
        val qzms2t = ((120.0 - 78.0) / EARTH_RADIUS_KM).pow(4)

        isimp = rp < (220.0 / EARTH_RADIUS_KM + 1.0)
        var sfour = ss
        var qzms24 = qzms2t
        val perige = (rp - 1.0) * EARTH_RADIUS_KM
        if (perige < 156.0) {
            sfour = perige - 78.0
            if (perige < 98.0) sfour = 20.0
            qzms24 = ((120.0 - sfour) / EARTH_RADIUS_KM).pow(4)
            sfour = sfour / EARTH_RADIUS_KM + 1.0
        }
        val pinvsq = 1.0 / posq

        val tsi = 1.0 / (ao - sfour)
        eta = ao * ecco * tsi
        val etasq = eta * eta
        val eeta = ecco * eta
        val psisq = abs(1.0 - etasq)
        val coef = qzms24 * tsi.pow(4)
        val coef1 = coef / psisq.pow(3.5)
        val cc2 = coef1 * noUnkozai * (
            ao * (1.0 + 1.5 * etasq + eeta * (4.0 + etasq)) +
                0.375 * J2 * tsi / psisq * con41 * (8.0 + 3.0 * etasq * (8.0 + etasq))
            )
        cc1 = bstar * cc2
        val cc3 = if (ecco > 1.0e-4) -2.0 * coef * tsi * J3OJ2 * noUnkozai * sinio / ecco else 0.0
        x1mth2 = 1.0 - cosio2
        cc4 = 2.0 * noUnkozai * coef1 * ao * omeosq * (
            eta * (2.0 + 0.5 * etasq) + ecco * (0.5 + 2.0 * etasq) -
                J2 * tsi / (ao * psisq) * (
                    -3.0 * con41 * (1.0 - 2.0 * eeta + etasq * (1.5 - 0.5 * eeta)) +
                        0.75 * x1mth2 * (2.0 * etasq - eeta * (1.0 + etasq)) * cos(2.0 * argpo)
                    )
            )
        cc5 = 2.0 * coef1 * ao * omeosq * (1.0 + 2.75 * (etasq + eeta) + eeta * etasq)

        val cosio4 = cosio2 * cosio2
        val temp1 = 1.5 * J2 * pinvsq * noUnkozai
        val temp2 = 0.5 * temp1 * J2 * pinvsq
        val temp3 = -0.46875 * J4 * pinvsq * pinvsq * noUnkozai
        mdot = noUnkozai + 0.5 * temp1 * rteosq * con41 +
            0.0625 * temp2 * rteosq * (13.0 - 78.0 * cosio2 + 137.0 * cosio4)
        argpdot = -0.5 * temp1 * con42 + 0.0625 * temp2 * (7.0 - 114.0 * cosio2 + 395.0 * cosio4) +
            temp3 * (3.0 - 36.0 * cosio2 + 49.0 * cosio4)
        val xhdot1 = -temp1 * cosio
        nodedot = xhdot1 + (0.5 * temp2 * (4.0 - 19.0 * cosio2) + 2.0 * temp3 * (3.0 - 7.0 * cosio2)) * cosio
        omgcof = bstar * cc3 * cos(argpo)
        xmcof = if (ecco > 1.0e-4) -X2O3 * coef * bstar / eeta else 0.0
        nodecf = 3.5 * omeosq * xhdot1 * cc1
        t2cof = 1.5 * cc1
        xlcof = if (abs(cosio + 1.0) > 1.5e-12) {
            -0.25 * J3OJ2 * sinio * (3.0 + 5.0 * cosio) / (1.0 + cosio)
        } else {
            -0.25 * J3OJ2 * sinio * (3.0 + 5.0 * cosio) / TEMP4
        }
        aycof = -0.5 * J3OJ2 * sinio
        delmo = (1.0 + eta * cos(mo)).pow(3)
        sinmao = sin(mo)
        x7thm1 = 7.0 * cosio2 - 1.0

        if (!isimp) {
            val cc1sq = cc1 * cc1
            d2 = 4.0 * ao * tsi * cc1sq
            val temp = d2 * tsi * cc1 / 3.0
            d3 = (17.0 * ao + sfour) * temp
            d4 = 0.5 * temp * ao * tsi * (221.0 * ao + 31.0 * sfour) * cc1
            t3cof = d2 + 2.0 * cc1sq
            t4cof = 0.25 * (3.0 * d3 + cc1 * (12.0 * d2 + 10.0 * cc1sq))
            t5cof = 0.2 * (3.0 * d4 + 12.0 * cc1 * d3 + 6.0 * d2 * d2 + 15.0 * cc1sq * (2.0 * d2 + cc1sq))
        }
    }

    /** Propagates to an absolute time. */
    fun propagate(time: Instant): StateVector {
        val minutes = Duration.between(tle.epoch, time).toNanos() / 60e9
        return propagate(minutes).copy(time = time)
    }

    /** Propagates to [tsince] minutes after the TLE epoch (negative = before the epoch). */
    fun propagate(tsince: Double): StateVector {
        val t = tsince

        // ---- secular gravity and atmospheric drag ----
        val xmdf = mo + mdot * t
        val argpdf = argpo + argpdot * t
        val nodedf = nodeo + nodedot * t
        var argpm = argpdf
        var mm = xmdf
        val t2 = t * t
        var nodem = nodedf + nodecf * t2
        var tempa = 1.0 - cc1 * t
        var tempe = bstar * cc4 * t
        var templ = t2cof * t2

        if (!isimp) {
            val delomg = omgcof * t
            val delm = xmcof * ((1.0 + eta * cos(xmdf)).pow(3) - delmo)
            val temp = delomg + delm
            mm = xmdf + temp
            argpm = argpdf - temp
            val t3 = t2 * t
            val t4 = t3 * t
            tempa = tempa - d2 * t2 - d3 * t3 - d4 * t4
            tempe += bstar * cc5 * (sin(mm) - sinmao)
            templ = templ + t3cof * t3 + t4 * (t4cof + t * t5cof)
        }

        var nm = noUnkozai
        var em = ecco
        val inclm = inclo
        if (nm <= 0.0) throw Sgp4Exception(2, "Mean motion is not positive")

        val am = (XKE / nm).pow(X2O3) * tempa * tempa
        nm = XKE / am.pow(1.5)
        em -= tempe
        if (em >= 1.0 || em < -0.001) throw Sgp4Exception(1, "Eccentricity out of range: $em")
        if (em < 1.0e-6) em = 1.0e-6
        mm += noUnkozai * templ
        var xlm = mm + argpm + nodem

        nodem = nodem.rem(TWO_PI)
        argpm = argpm.rem(TWO_PI)
        xlm = xlm.rem(TWO_PI)
        mm = (xlm - argpm - nodem).rem(TWO_PI)

        val sinip = sin(inclm)
        val cosip = cos(inclm)

        // ---- long period periodics ----
        val axnl = em * cos(argpm)
        var temp = 1.0 / (am * (1.0 - em * em))
        val aynl = em * sin(argpm) + temp * aycof
        val xl = mm + argpm + nodem + temp * xlcof * axnl

        // ---- solve Kepler's equation ----
        val u = (xl - nodem).rem(TWO_PI)
        var eo1 = u
        var tem5 = 9999.9
        var ktr = 1
        var sineo1 = 0.0
        var coseo1 = 0.0
        while (abs(tem5) >= 1.0e-12 && ktr <= 10) {
            sineo1 = sin(eo1)
            coseo1 = cos(eo1)
            tem5 = 1.0 - coseo1 * axnl - sineo1 * aynl
            tem5 = (u - aynl * coseo1 + axnl * sineo1 - eo1) / tem5
            if (abs(tem5) >= 0.95) tem5 = if (tem5 > 0.0) 0.95 else -0.95
            eo1 += tem5
            ktr++
        }

        // ---- short period periodics ----
        val ecose = axnl * coseo1 + aynl * sineo1
        val esine = axnl * sineo1 - aynl * coseo1
        val el2 = axnl * axnl + aynl * aynl
        val pl = am * (1.0 - el2)
        if (pl < 0.0) throw Sgp4Exception(4, "Semi-latus rectum is negative")

        val rl = am * (1.0 - ecose)
        val rdotl = sqrt(am) * esine / rl
        val rvdotl = sqrt(pl) / rl
        val betal = sqrt(1.0 - el2)
        temp = esine / (1.0 + betal)
        val sinu = am / rl * (sineo1 - aynl - axnl * temp)
        val cosu = am / rl * (coseo1 - axnl + aynl * temp)
        var su = atan2(sinu, cosu)
        val sin2u = (cosu + cosu) * sinu
        val cos2u = 1.0 - 2.0 * sinu * sinu
        temp = 1.0 / pl
        val temp1 = 0.5 * J2 * temp
        val temp2 = temp1 * temp

        val mrt = rl * (1.0 - 1.5 * temp2 * betal * con41) + 0.5 * temp1 * x1mth2 * cos2u
        su -= 0.25 * temp2 * x7thm1 * sin2u
        val xnode = nodem + 1.5 * temp2 * cosip * sin2u
        val xinc = inclm + 1.5 * temp2 * cosip * sinip * cos2u
        val mvt = rdotl - nm * temp1 * x1mth2 * sin2u / XKE
        val rvdot = rvdotl + nm * temp1 * (x1mth2 * cos2u + 1.5 * con41) / XKE

        // ---- orientation vectors ----
        val sinsu = sin(su)
        val cossu = cos(su)
        val snod = sin(xnode)
        val cnod = cos(xnode)
        val sini = sin(xinc)
        val cosi = cos(xinc)
        val xmx = -snod * cosi
        val xmy = cnod * cosi
        val ux = xmx * sinsu + cnod * cossu
        val uy = xmy * sinsu + snod * cossu
        val uz = sini * sinsu
        val vx = xmx * cossu - cnod * sinsu
        val vy = xmy * cossu - snod * sinsu
        val vz = sini * cossu

        if (mrt < 1.0) throw Sgp4Exception(6, "Satellite has decayed")

        val position = Vector3(mrt * ux, mrt * uy, mrt * uz) * EARTH_RADIUS_KM
        val velocity = Vector3(
            mvt * ux + rvdot * vx,
            mvt * uy + rvdot * vy,
            mvt * uz + rvdot * vz,
        ) * VKMPERSEC
        return StateVector(tle.epoch.plusNanos((tsince * 60e9).toLong()), position, velocity)
    }
}
