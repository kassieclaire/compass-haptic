/*
 * This file is part of Compass.
 * Copyright (C) 2022 Philipp Bobek <philipp.bobek@mailbox.org>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Compass is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.bobek.compass

import android.content.Context
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.SensorManager.*
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.method.LinkMovementMethod
import android.util.Log
import android.view.*
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate.*
import androidx.databinding.DataBindingUtil
import com.bobek.compass.databinding.AboutAlertDialogViewBinding
import com.bobek.compass.databinding.ActivityMainBinding
import com.bobek.compass.databinding.SensorAlertDialogViewBinding
import com.bobek.compass.model.Azimuth
import com.bobek.compass.model.DisplayRotation
import com.bobek.compass.model.RotationVector
import com.bobek.compass.model.SensorAccuracy
import com.bobek.compass.util.MathUtils
import com.bobek.compass.view.ObservableSensorAccuracy
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.math.abs

const val OPTION_INSTRUMENTED_TEST = "INSTRUMENTED_TEST"

private const val TAG = "MainActivity"

class MainActivity : AppCompatActivity(), SensorEventListener {

    private val observableSensorAccuracy = ObservableSensorAccuracy(SensorAccuracy.NO_CONTACT)

    private lateinit var binding: ActivityMainBinding
    private lateinit var sensorManager: SensorManager

    private var optionsMenu: Menu? = null

    //get the vibration service
    private lateinit var vibratorManager : VibratorManager
    private lateinit var vibrator: Vibrator

    //private float which stores the calibrated azimuth value
    private var azimuthCalibration : Float = 0f
    //private float storing the current azimuth value
    private var currentAzimuth : Float = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = DataBindingUtil.setContentView(this, R.layout.activity_main)
        setSupportActionBar(binding.toolbar)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        //vibration service
        //if the API level is at or above 31, get the vibrator manager service and the vibrator as the default vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            vibratorManager = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibrator = vibratorManager.defaultVibrator
            //log that the API level is at or above 31
            Log.d("API", "API level is at or above 31")
        }
        //else, use the vibrator service
        else {
            vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
            //log that the API level is below 31
            Log.d("API", "API level is below 31")
        }
    }

    override fun onResume() {
        super.onResume()

        if (isInstrumentedTest()) {
            Log.i(TAG, "Skipping registration of sensor listener")
        } else {
            registerSensorListener()
        }

        Log.i(TAG, "Started compass")
    }

    private fun registerSensorListener() {
        val rotationVectorSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotationVectorSensor == null) {
            Log.w(TAG, "Rotation vector sensor not available")
            showSensorErrorDialog()
            return
        }

        val success = sensorManager.registerListener(this, rotationVectorSensor, SENSOR_DELAY_FASTEST)
        if (!success) {
            Log.w(TAG, "Could not enable rotation vector sensor")
            showSensorErrorDialog()
            return
        }
    }

    private fun showSensorErrorDialog() {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.sensor_error_message)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setCancelable(false)
            .show()
    }

    private fun isInstrumentedTest() = intent.extras?.getBoolean(OPTION_INSTRUMENTED_TEST) ?: false

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        Log.i(TAG, "Stopped compass")
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        optionsMenu = menu
        updateSensorStatusIcon()
        updateScreenRotationIcon()
        updateNightModeIcon()
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_sensor_status -> {
                showSensorStatusPopup()
                true
            }
            R.id.action_screen_rotation -> {
                toggleScreenRotationMode()
                true
            }
            R.id.action_night_mode -> {
                toggleNightMode()
                true
            }
            R.id.action_about -> {
                showAboutPopup()
                //calibrate the azimuth
                azimuthCalibration = currentAzimuth
                //log the calibrated azimuth
                Log.i("Azimuth", "Calibrated azimuth: $azimuthCalibration")
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showSensorStatusPopup() {
        val alertDialogBuilder = MaterialAlertDialogBuilder(this)
        val dialogContextInflater = LayoutInflater.from(alertDialogBuilder.context)

        val dialogBinding = SensorAlertDialogViewBinding.inflate(dialogContextInflater, null, false)
        dialogBinding.sensorAccuracy = observableSensorAccuracy

        alertDialogBuilder
            .setTitle(R.string.sensor_status)
            .setView(dialogBinding.root)
            .setNeutralButton(R.string.ok) { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun toggleScreenRotationMode() {
        when (requestedOrientation) {
            SCREEN_ORIENTATION_UNSPECIFIED -> changeScreenRotationMode(SCREEN_ORIENTATION_LOCKED)
            else -> changeScreenRotationMode(SCREEN_ORIENTATION_UNSPECIFIED)
        }
    }

    private fun changeScreenRotationMode(screenOrientation: Int) {
        Log.d(TAG, "Setting requested orientation to value $screenOrientation")
        requestedOrientation = screenOrientation
        updateScreenRotationIcon()
    }

    private fun toggleNightMode() {
        when (getDefaultNightMode()) {
            MODE_NIGHT_NO -> changeNightMode(MODE_NIGHT_YES)
            MODE_NIGHT_YES -> changeNightMode(MODE_NIGHT_FOLLOW_SYSTEM)
            else -> changeNightMode(MODE_NIGHT_NO)
        }
    }

    private fun changeNightMode(@NightMode mode: Int) {
        Log.d(TAG, "Setting night mode to value $mode")
        setDefaultNightMode(mode)
        updateNightModeIcon()
    }

    private fun showAboutPopup() {
        val alertDialogBuilder = MaterialAlertDialogBuilder(this)
        val dialogContextInflater = LayoutInflater.from(alertDialogBuilder.context)

        val dialogBinding = AboutAlertDialogViewBinding.inflate(dialogContextInflater, null, false)
        dialogBinding.version = BuildConfig.VERSION_NAME
        dialogBinding.copyrightText.movementMethod = LinkMovementMethod.getInstance()
        dialogBinding.licenseText.movementMethod = LinkMovementMethod.getInstance()
        dialogBinding.sourceCodeText.movementMethod = LinkMovementMethod.getInstance()

        alertDialogBuilder
            .setTitle(R.string.app_name)
            .setView(dialogBinding.root)
            .setNeutralButton(R.string.ok) { dialog, _ -> dialog.dismiss() }
            .show()
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        when (sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> setSensorAccuracy(accuracy)
            else -> Log.w(TAG, "Unexpected accuracy changed event of type ${sensor.type}")
        }
    }

    private fun setSensorAccuracy(accuracy: Int) {
        Log.v(TAG, "Sensor accuracy value $accuracy")
        val sensorAccuracy = adaptSensorAccuracy(accuracy)
        setSensorAccuracy(sensorAccuracy)
    }

    internal fun setSensorAccuracy(sensorAccuracy: SensorAccuracy) {
        observableSensorAccuracy.set(sensorAccuracy)
        updateSensorStatusIcon()
    }

    private fun adaptSensorAccuracy(accuracy: Int): SensorAccuracy {
        return when (accuracy) {
            SENSOR_STATUS_NO_CONTACT -> SensorAccuracy.NO_CONTACT
            SENSOR_STATUS_UNRELIABLE -> SensorAccuracy.UNRELIABLE
            SENSOR_STATUS_ACCURACY_LOW -> SensorAccuracy.LOW
            SENSOR_STATUS_ACCURACY_MEDIUM -> SensorAccuracy.MEDIUM
            SENSOR_STATUS_ACCURACY_HIGH -> SensorAccuracy.HIGH
            else -> {
                Log.w(TAG, "Encountered unexpected sensor accuracy value '$accuracy'")
                SensorAccuracy.NO_CONTACT
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> updateCompass(event)
            else -> Log.w(TAG, "Unexpected sensor changed event of type ${event.sensor.type}")
        }
    }

    private fun updateCompass(event: SensorEvent) {
        val rotationVector = RotationVector(event.values[0], event.values[1], event.values[2])
        val displayRotation = getDisplayRotation()
        //Log.d("Rotation", "Display rotation is $displayRotation")
        val azimuth = MathUtils.calculateAzimuth(rotationVector, displayRotation)

        setAzimuth(azimuth)

        currentAzimuth = azimuth.degrees
        //Log.d("Rotation", "Azimuth is $currentAzimuth")
        //if calibrated azimuth is not null, calculate the difference between the two
        if (azimuthCalibration != null) {
            val azimuthDifference = getAzimuthDifference()
            Log.d("Difference", "Azimuth difference is $azimuthDifference")
            //if azimuth difference is greater than 35 degrees, vibrate the phone
            if (azimuthDifference > 35) {
                vibrate()
            }
        }
    }

    private fun getDisplayRotation(): DisplayRotation {
        return when (getDisplayCompat()?.rotation) {
            Surface.ROTATION_90 -> DisplayRotation.ROTATION_90
            Surface.ROTATION_180 -> DisplayRotation.ROTATION_180
            Surface.ROTATION_270 -> DisplayRotation.ROTATION_270
            else -> DisplayRotation.ROTATION_0
        }
    }

    private fun getDisplayCompat(): Display? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display
        } else {
            windowManager.defaultDisplay
        }
    }

    internal fun setAzimuth(azimuth: Azimuth) {
        binding.contentMain.compass.setAzimuth(azimuth)
        //Log.v(TAG, "Azimuth $azimuth")
    }

    private fun updateSensorStatusIcon() {
        val sensorAccuracy = observableSensorAccuracy.get() ?: SensorAccuracy.NO_CONTACT

        optionsMenu
            ?.findItem(R.id.action_sensor_status)
            ?.setIcon(sensorAccuracy.iconResourceId)
    }

    private fun updateScreenRotationIcon() {
        optionsMenu
            ?.findItem(R.id.action_screen_rotation)
            ?.setIcon(getScreenRotationIcon())
    }

    @DrawableRes
    private fun getScreenRotationIcon(): Int {
        return when (requestedOrientation) {
            SCREEN_ORIENTATION_UNSPECIFIED -> R.drawable.ic_screen_rotation
            else -> R.drawable.ic_screen_rotation_lock
        }
    }

    private fun updateNightModeIcon() {
        optionsMenu
            ?.findItem(R.id.action_night_mode)
            ?.setIcon(getNightModeIcon())
    }

    @DrawableRes
    private fun getNightModeIcon(): Int {
        return when (getDefaultNightMode()) {
            MODE_NIGHT_NO -> R.drawable.ic_night_mode_no
            MODE_NIGHT_YES -> R.drawable.ic_night_mode_yes
            else -> R.drawable.ic_night_mode_auto
        }
    }
    private fun vibrate() {
        //call the vibratePhone function
        //log that the vibrate function was called
        Log.d("vibrate", "called the vibrate function")
        //vibrate the phone with version for API 26 and above
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && vibrator != null) {
            vibrator!!.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
            //log that the phone was vibrated
            Log.d("vibrate", "vibrated the phone")
        }
        else {
            //vibrate the phone with version for API 25 and below
            vibrator!!.vibrate(100)
            //log that the phone was vibrated
            Log.d("vibrate", "vibrated the phone")
        }


    }
    //function which gets the difference between the current azimuth value and the calibrated azimuth value
    //keep in mind that the azimuth is on a 360 degree scale -- thus, the difference can be greater than 180 degrees
    private fun getAzimuthDifference(): Float {
        //calculate the raw difference
        var difference = azimuthCalibration - currentAzimuth
        //if the difference is greater than 180, subtract 360 to get the difference between the two values
        if (difference > 180) {
            difference -= 360
            //Log.d("Difference", "Difference is greater than 180")
        }
        //return the absolute value of the difference
        return abs(difference)
    }


}
