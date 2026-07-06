package app.hack.eightballpool

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity

class HomeActivity : AppCompatActivity() {

    private val TAG = "HomeActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "onCreate")
        super.onCreate(savedInstanceState)

        stopServices()
        goToHome()
        finish()
    }

    private fun goToHome() {
        Log.d(TAG, "goToHome")
        val homeIntent = Intent(Intent.ACTION_MAIN)
        homeIntent.addCategory(Intent.CATEGORY_HOME)
        runCatching { startActivity(homeIntent) }
    }

    private fun stopServices() {
        Log.d(TAG, "stopServices")

        stopService(Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_STOP_SERVICE
        })

        stopService(Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP_CAPTURE
        })

        stopService(Intent(this, IndicatorOverlayService::class.java).apply {
            action = IndicatorOverlayService.ACTION_STOP_SERVICE
        })
    }
}
