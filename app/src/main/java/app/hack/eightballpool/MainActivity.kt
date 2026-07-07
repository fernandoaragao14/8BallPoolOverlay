package app.hack.eightballpool

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"
    private var captureConsentRequested = false

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val screenCaptureConsentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                Log.d(TAG, "Screen capture consent granted")
                startIndicatorOverlayService()
                startScreenCaptureService(result.resultCode, result.data!!)
            } else {
                Log.w(TAG, "Screen capture consent denied")
                captureConsentRequested = false
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "onCreate")
        super.onCreate(savedInstanceState)

        requestNotificationPermissionIfNeeded()
        logDeviceDimensions()
    }

    override fun onResume() {
        Log.d(TAG, "onResume")
        super.onResume()

        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            return
        }

        requestScreenCaptureConsentIfNeeded()
    }

    private fun requestOverlayPermission() {
        Log.d(TAG, "requestOverlayPermission")
        val permissionIntent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(permissionIntent)
    }

    private fun requestScreenCaptureConsentIfNeeded() {
        if (captureConsentRequested) return

        captureConsentRequested = true
        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        val consentIntent = projectionManager.createScreenCaptureIntent()
        screenCaptureConsentLauncher.launch(consentIntent)
    }

    private fun startScreenCaptureService(resultCode: Int, resultData: Intent) {
        Log.d(TAG, "startScreenCaptureService")
        val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_START_CAPTURE
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, resultData)
        }
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    private fun startIndicatorOverlayService() {
        Log.d(TAG, "startIndicatorOverlayService")
        IndicatorOverlayService.start(this)
    }

    private fun logDeviceDimensions() {
        val metrics = resources.displayMetrics
        Log.d(TAG, "Device Width: ${metrics.widthPixels}")
        Log.d(TAG, "Device Height: ${metrics.heightPixels}")
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(
                Manifest.permission.POST_NOTIFICATIONS
            )
        }
    }
}
