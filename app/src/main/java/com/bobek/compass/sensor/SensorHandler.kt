package com.bobek.compass.sensor

import android.hardware.SensorManager.getOrientation
import android.hardware.SensorManager.getRotationMatrix

private const val AZIMUTH = 0
private const val AXIS_SIZE = 3
private const val ROTATION_MATRIX_SIZE = 9
private const val ZERO = 0.0f

class SensorHandler {

    private val accelerometerReading = FloatArray(AXIS_SIZE)
    private val magnetometerReading = FloatArray(AXIS_SIZE)

    fun handleAccelerometerValues(values: SensorValues): Float? {
        floatArrayOf(values.x, values.y, values.z).copyInto(accelerometerReading)
        return updateAzimuth()
    }

    fun handleMagneticFieldValues(values: SensorValues): Float? {
        floatArrayOf(values.x, values.y, values.z).copyInto(magnetometerReading)
        return updateAzimuth()
    }

    private fun updateAzimuth(): Float? {
        val rotationMatrix = FloatArray(ROTATION_MATRIX_SIZE)

        val success = getRotationMatrix(rotationMatrix, null, accelerometerReading, magnetometerReading)

        return if (success) {
            calculateAzimuth(rotationMatrix)
        } else {
            null
        }
    }

    private fun calculateAzimuth(rotationMatrix: FloatArray): Float {
        val orientationAnglesInRadians = getOrientation(rotationMatrix, FloatArray(AXIS_SIZE))
        val azimuthInRadians = orientationAnglesInRadians[AZIMUTH]
        val azimuthInDegrees = Math.toDegrees(azimuthInRadians.toDouble()).toFloat()
        return SensorUtils.normalizeAngle(azimuthInDegrees)
    }

    fun reset() {
        accelerometerReading.fill(ZERO)
        magnetometerReading.fill(ZERO)
    }
}