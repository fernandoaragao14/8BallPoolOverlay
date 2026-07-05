package app.hack.eightballpool

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import app.hack.eightballpool.databinding.BoardOverlayBinding

class OverlayService : Service() {

    companion object {
        const val ACTION_STOP_SERVICE = "ACTION_STOP_SERVICE"
        const val ACTION_START_SERVICE = "ACTION_START_SERVICE"
        private const val CHANNEL_ID = "overlay_service_channel"
        private const val NOTIFICATION_ID = 888
        private const val CONTENT_REQUEST_CODE = 100
        private const val START_REQUEST_CODE = 101
        private const val STOP_REQUEST_CODE = 102
    }

    private val windowManager: WindowManager by lazy {
        getSystemService(WINDOW_SERVICE) as WindowManager
    }

    private val tag = "OverlayService"
    private var boardBinding: BoardOverlayBinding? = null
    private var floatingButton: ImageButton? = null

    override fun onBind(intent: Intent): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForegroundService()
        prepareOverlayViews()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVICE -> stopOverlayService()
            ACTION_START_SERVICE, null -> showOverlay()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        removeViewIfAttached(floatingButton)
        removeViewIfAttached(boardBinding?.root)
        super.onDestroy()
    }

    private fun startAsForegroundService() {
        createNotificationChannel()
        val notification = createNotification()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun prepareOverlayViews() {
        val binding = BoardOverlayBinding.inflate(LayoutInflater.from(this))
        OverlayView(binding, resources)

        val button = ImageButton(this).apply {
            setImageDrawable(ResourcesCompat.getDrawable(resources, R.drawable.button_8_ball, null))
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                binding.root.isVisible = !binding.root.isVisible
            }
            setOnLongClickListener {
                stopOverlayService()
                true
            }
        }

        boardBinding = binding
        floatingButton = button
    }

    private fun showOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Log.w(tag, "Overlay permission missing, stopping service")
            stopOverlayService()
            return
        }

        val binding = boardBinding ?: return
        val button = floatingButton ?: return

        addViewIfPossible(binding.root, createBoardLayoutParams())
        addViewIfPossible(button, createButtonLayoutParams())
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            CONTENT_REQUEST_CODE,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val startPendingIntent = PendingIntent.getService(
            this,
            START_REQUEST_CODE,
            Intent(this, OverlayService::class.java).apply {
                action = ACTION_START_SERVICE
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopPendingIntent = PendingIntent.getService(
            this,
            STOP_REQUEST_CODE,
            Intent(this, OverlayService::class.java).apply {
                action = ACTION_STOP_SERVICE
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_8_ball)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_body))
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                android.R.drawable.ic_media_play,
                getString(R.string.notification_action_show),
                startPendingIntent
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.notification_action_stop),
                stopPendingIntent
            )
            .build()
    }

    private fun createBaseLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }

    private fun createBoardLayoutParams(): WindowManager.LayoutParams =
        createBaseLayoutParams().apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            verticalMargin = resources.getDimension(R.dimen.boardMarginBottom)
        }

    private fun createButtonLayoutParams(): WindowManager.LayoutParams =
        createBaseLayoutParams().apply {
            gravity = Gravity.BOTTOM or Gravity.END
            verticalMargin = resources.getDimension(R.dimen.boardMarginBottom)
            horizontalMargin = resources.getDimension(R.dimen.boardMarginBottom)
        }

    private fun addViewIfPossible(view: View, params: WindowManager.LayoutParams) {
        if (view.parent != null) {
            return
        }

        runCatching { windowManager.addView(view, params) }
            .onFailure {
                Log.e(tag, "Unable to add overlay window", it)
                if (it is SecurityException || it is WindowManager.BadTokenException) {
                    stopOverlayService()
                }
            }
    }

    private fun removeViewIfAttached(view: View?) {
        if (view?.parent != null) {
            runCatching { windowManager.removeViewImmediate(view) }
                .onFailure { Log.w(tag, "Unable to remove overlay window", it) }
        }
    }

    private fun stopOverlayService() {
        removeViewIfAttached(floatingButton)
        removeViewIfAttached(boardBinding?.root)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
