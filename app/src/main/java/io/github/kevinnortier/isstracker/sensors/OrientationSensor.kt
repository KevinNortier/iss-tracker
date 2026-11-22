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
import kotlin.math.sqrt

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

/**
 * One orientation reading. Matrices are Android rotation matrices (world = R · device;
 * east, north, up).
 */
class OrientationReading(
    /** From the selected heading source: compass (magnetic north) or gyro (arbitrary north, aligned later). */
    val rotationMatrix: FloatArray,
    val source: HeadingSource,
    /** The latest compass (rotation vector) matrix, also in gyro mode; null until one has arrived. */
    val compassMatrix: FloatArray?,
    /** Calibration state of the magnetometer. */
    val compassAccuracy: CompassAccuracy,
    /** Strength of the measured magnetic field in µT (Earth: about 25–65), or null if unknown. */
    val magneticFieldMicroTesla: Double?,
) {
    /** True when the user should calibrate (figure-8 motion) before trusting the compass. */
    val needsCalibration: Boolean
        get() = compassAccuracy == CompassAccuracy.LOW || compassAccuracy == CompassAccuracy.UNRELIABLE

    /** True when the field is far from Earth's, e.g. a magnet (MagSafe) or metal close to the phone. */
    val magneticInterference: Boolean
        get() = magneticFieldMicroTesla != null && magneticFieldMicroTesla !in NORMAL_FIELD_RANGE

    companion object {
        /** Earth's field is roughly 25–65 µT; allow some margin before warning. */
        val NORMAL_FIELD_RANGE = 20.0..75.0
    }
}

/** The phone's orientation from the fused rotation sensors. */
class OrientationSensor(context: Context) {

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val rotationSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val gameRotationSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val magnetometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    val isAvailable: Boolean get() = rotationSensor != null

    /** True if gyro-only heading (game rotation vector) is available on this phone. */
    val isGyroAvailable: Boolean get() = gameRotationSensor != null

    /**
     * Emits readings while collected (the sensors are turned off when collection stops). A reading
     * is emitted for every event of the selected [source]; readings are smoothed with an
     * exponential filter ([smoothing] = share of each new reading).
     *
     * The compass accuracy comes from the magnetometer's calibration status. (The rotation vector's
     * own heading-accuracy estimate, values[4], is a fixed placeholder on some phones, e.g. 30° on
     * the Pixel 10, so it can't tell whether the user has calibrated.)
     */
    fun readings(source: HeadingSource, smoothing: Float = 0.2f): Flow<OrientationReading> = callbackFlow {
        val rotation = rotationSensor ?: run {
            close()
            return@callbackFlow
        }
        val gameRotation = if (source == HeadingSource.GYRO) gameRotationSensor else null
        val effectiveSource = if (gameRotation != null) HeadingSource.GYRO else HeadingSource.COMPASS

        var compassAccuracy = if (magnetometer == null) CompassAccuracy.UNKNOWN else CompassAccuracy.UNRELIABLE
        var fieldStrength: Double? = null
        var compass: FloatArray? = null
        var gyro: FloatArray? = null
        val raw = FloatArray(9)

        fun emit(matrix: FloatArray) {
            trySend(
                OrientationReading(
                    rotationMatrix = matrix.copyOf(),
                    source = effectiveSource,
                    compassMatrix = compass?.copyOf(),
                    compassAccuracy = compassAccuracy,
                    magneticFieldMicroTesla = fieldStrength,
                ),
            )
        }

        val rotationListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(raw, event.values)
                compass = Pointing.smooth(compass, raw, smoothing)
                if (effectiveSource == HeadingSource.COMPASS) emit(compass!!)
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }

        val gameRotationListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(raw, event.values)
                gyro = Pointing.smooth(gyro, raw, smoothing)
                emit(gyro!!)
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }

        // Only listened to for its calibration status and field strength; the values are fused above.
        val magnetometerListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                compassAccuracy = CompassAccuracy.fromSensorStatus(event.accuracy)
                val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
                fieldStrength = sqrt((x * x + y * y + z * z).toDouble())
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
                compassAccuracy = CompassAccuracy.fromSensorStatus(accuracy)
            }
        }

        sensorManager.registerListener(rotationListener, rotation, SensorManager.SENSOR_DELAY_GAME)
        gameRotation?.let { sensorManager.registerListener(gameRotationListener, it, SensorManager.SENSOR_DELAY_GAME) }
        magnetometer?.let { sensorManager.registerListener(magnetometerListener, it, SensorManager.SENSOR_DELAY_UI) }
        awaitClose {
            sensorManager.unregisterListener(rotationListener)
            sensorManager.unregisterListener(gameRotationListener)
            sensorManager.unregisterListener(magnetometerListener)
        }
    }.conflate()
}
