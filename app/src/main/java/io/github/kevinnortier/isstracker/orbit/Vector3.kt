package io.github.kevinnortier.isstracker.orbit

import kotlin.math.sqrt

/** A simple immutable 3D vector. Units depend on the context (usually km or km/s). */
data class Vector3(val x: Double, val y: Double, val z: Double) {

    val magnitude: Double get() = sqrt(x * x + y * y + z * z)

    operator fun plus(o: Vector3) = Vector3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vector3) = Vector3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vector3(x * s, y * s, z * s)
    operator fun div(s: Double) = Vector3(x / s, y / s, z / s)

    infix fun dot(o: Vector3): Double = x * o.x + y * o.y + z * o.z

    infix fun cross(o: Vector3) = Vector3(
        y * o.z - z * o.y,
        z * o.x - x * o.z,
        x * o.y - y * o.x,
    )

    fun normalized(): Vector3 = this / magnitude

    companion object {
        val ZERO = Vector3(0.0, 0.0, 0.0)
    }
}
