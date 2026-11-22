package io.github.kevinnortier.isstracker.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/** How well the compass (magnetometer) is calibrated. */
enum class CompassAccuracy {
    HIGH, MEDIUM, LOW, UNRELIABLE, UNKNOWN;

    companion object {
        fun fromSensorStatus(status: Int): CompassAccuracy = when (status) {
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> HIGH
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> MEDIUM
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> LOW
            SensorManager.SENSOR_STATUS_UNRELIABLE -> UNRELIABLE
            else -> UNKNOWN
        }
    }
}

/** One orientation reading: Android's rotation matrix (world = R · device; east, magnetic north, up). */
class OrientationReading(
    val rotationMatrix: FloatArray,
    /** Calibration state of the magnetometer, which decides how far the azimuth can be trusted. */
    val compassAccuracy: CompassAccuracy,
) {
    /** True when the user should calibrate (figure-8 motion) before trusting the azimuth. */
    val needsCalibration: Boolean
        get() = compassAccuracy == CompassAccuracy.LOW || compassAccuracy == CompassAccuracy.UNRELIABLE
}

/** The phone's orientation from the fused rotation-vector sensor (accelerometer + gyro + magnetometer). */
class OrientationSensor(context: Context) {

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val rotationSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val magnetometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    val isAvailable: Boolean get() = rotationSensor != null

    /**
     * Emits readings while collected (the sensors are turned off when collection stops).
     * Readings are smoothed with an exponential filter ([smoothing] = share of each new reading).
     *
     * The compass accuracy comes from the magnetometer's calibration status. (The rotation vector's
     * own heading-accuracy estimate, values[4], is a fixed placeholder on some phones, e.g. 30° on
     * the Pixel 10, so it can't tell whether the user has calibrated.)
     */
    fun readings(smoothing: Float = 0.2f): Flow<OrientationReading> = callbackFlow {
        val rotation = rotationSensor ?: run {
            close()
            return@callbackFlow
        }
        var compassAccuracy = if (magnetometer == null) CompassAccuracy.UNKNOWN else CompassAccuracy.UNRELIABLE
        var smoothed: FloatArray? = null
        val raw = FloatArray(9)

        val rotationListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(raw, event.values)
                smoothed = Pointing.smooth(smoothed, raw, smoothing)
                trySend(OrientationReading(smoothed!!.copyOf(), compassAccuracy))
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }

        // Only listened to for its calibration status; the values themselves are already fused above.
        val magnetometerListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                compassAccuracy = CompassAccuracy.fromSensorStatus(event.accuracy)
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
                compassAccuracy = CompassAccuracy.fromSensorStatus(accuracy)
            }
        }

        sensorManager.registerListener(rotationListener, rotation, SensorManager.SENSOR_DELAY_GAME)
        magnetometer?.let { sensorManager.registerListener(magnetometerListener, it, SensorManager.SENSOR_DELAY_UI) }
        awaitClose {
            sensorManager.unregisterListener(rotationListener)
            sensorManager.unregisterListener(magnetometerListener)
        }
    }.conflate()
}
