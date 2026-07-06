package app.hack.eightballpool

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class IndicatorOverlayService : Service() {

    companion object {
        const val ACTION_START_SERVICE = "app.hack.eightballpool.action.START_INDICATOR_OVERLAY"
        const val ACTION_STOP_SERVICE = "app.hack.eightballpool.action.STOP_INDICATOR_OVERLAY"

        private const val TAG = "IndicatorOverlayService"
        private const val CHANNEL_ID = "IndicatorOverlayServiceChannel"
        private const val NOTIFICATION_ID = 890

        fun start(context: Context) {
            val intent = Intent(context, IndicatorOverlayService::class.java).apply {
                action = ACTION_START_SERVICE
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, IndicatorOverlayService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            context.startService(intent)
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val windowManager: WindowManager
        get() = getSystemService(WINDOW_SERVICE) as WindowManager

    private var overlayView: IndicatorOverlayView? = null
    private var removeIndicatorListener: (() -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        overlayView = IndicatorOverlayView(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")

        when (intent?.action) {
            ACTION_STOP_SERVICE -> stopSelf()
            else -> addOverlay()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        removeOverlay()
        OverlayIndicatorBus.clear()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun addOverlay() {
        val view = overlayView ?: return
        if (view.parent != null) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        windowManager.addView(view, params)

        removeIndicatorListener = OverlayIndicatorBus.addListener { indicators ->
            mainHandler.post {
                overlayView?.setIndicators(indicators)
            }
        }
    }

    private fun removeOverlay() {
        removeIndicatorListener?.invoke()
        removeIndicatorListener = null

        overlayView?.let { view ->
            if (view.parent != null) {
                windowManager.removeView(view)
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.app_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Overlay com a análise da jogada sobre a mesa de sinuca"
            setSound(null, null)
        }

        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, IndicatorOverlayService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }

        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Análise da jogada ativa sobre a mesa")
            .setSmallIcon(R.drawable.ic_8_ball)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, getString(R.string.stop), stopPendingIntent)
            .build()
    }
}
