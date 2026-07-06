package app.hack.eightballpool

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat

class ScreenCaptureService : Service() {

    companion object {
        const val ACTION_START_CAPTURE = "app.hack.eightballpool.action.START_CAPTURE"
        const val ACTION_STOP_CAPTURE = "app.hack.eightballpool.action.STOP_CAPTURE"

        const val EXTRA_RESULT_CODE = "app.hack.eightballpool.extra.RESULT_CODE"
        const val EXTRA_RESULT_DATA = "app.hack.eightballpool.extra.RESULT_DATA"

        private const val TAG = "ScreenCaptureService"
        private const val CHANNEL_ID = "ScreenCaptureServiceChannel"
        private const val NOTIFICATION_ID = 889
        private const val FRAME_INTERVAL_MS = 100L
        private const val IMAGE_BUFFER_SIZE = 3
    }

    private lateinit var captureThread: HandlerThread
    private lateinit var captureHandler: Handler

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var isCapturing = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.d(TAG, "MediaProjection stopped")
            stopCapture(stopProjection = false)
            stopSelf()
        }
    }

    private val frameTicker = object : Runnable {
        override fun run() {
            if (!isCapturing) return

            captureLatestBitmap()?.let { bitmap ->
                processFrame(bitmap)
            }

            captureHandler.postDelayed(this, FRAME_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")

        captureThread = HandlerThread("ScreenCaptureThread")
        captureThread.start()
        captureHandler = Handler(captureThread.looper)

        createNotificationChannel()
        startAsForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")

        when (intent?.action) {
            ACTION_START_CAPTURE -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = getParcelableIntentExtra(intent, EXTRA_RESULT_DATA)

                if (resultCode == 0 || resultData == null) {
                    Log.e(TAG, "MediaProjection permission data is missing")
                    stopSelf()
                    return START_NOT_STICKY
                }

                startCapture(resultCode, resultData)
            }

            ACTION_STOP_CAPTURE -> stopSelf()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        stopCapture(stopProjection = true)
        captureThread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startCapture(resultCode: Int, resultData: Intent) {
        if (isCapturing) return

        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        val projection = projectionManager.getMediaProjection(resultCode, resultData)
        if (projection == null) {
            Log.e(TAG, "Unable to obtain MediaProjection (consent token invalid?)")
            stopSelf()
            return
        }

        val (screenWidth, screenHeight, densityDpi) = getScreenMetrics()
        imageReader = ImageReader.newInstance(
            screenWidth,
            screenHeight,
            PixelFormat.RGBA_8888,
            IMAGE_BUFFER_SIZE
        )

        // On Android 14+ the callback must be registered before creating the VirtualDisplay.
        projection.registerCallback(projectionCallback, captureHandler)
        mediaProjection = projection

        virtualDisplay = projection.createVirtualDisplay(
            "PoolTableScreenCapture",
            screenWidth,
            screenHeight,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            captureHandler
        )

        isCapturing = true
        captureHandler.removeCallbacks(frameTicker)
        captureHandler.post(frameTicker)

        Log.d(TAG, "Screen capture started: ${screenWidth}x$screenHeight @ ${FRAME_INTERVAL_MS}ms")
    }

    private fun stopCapture(stopProjection: Boolean) {
        isCapturing = false
        if (::captureHandler.isInitialized) {
            captureHandler.removeCallbacks(frameTicker)
        }

        runCatching { virtualDisplay?.release() }
        virtualDisplay = null

        runCatching { imageReader?.close() }
        imageReader = null

        mediaProjection?.let { projection ->
            runCatching { projection.unregisterCallback(projectionCallback) }
            if (stopProjection) {
                runCatching { projection.stop() }
            }
        }
        mediaProjection = null

        ScreenFrameProcessor.clear()
    }

    private fun captureLatestBitmap(): Bitmap? {
        val reader = imageReader ?: return null
        var image: Image? = null

        return try {
            image = reader.acquireLatestImage() ?: return null
            image.toBitmap()
        } catch (error: Throwable) {
            Log.e(TAG, "Failed to capture screen frame", error)
            null
        } finally {
            image?.close()
        }
    }

    private fun Image.toBitmap(): Bitmap {
        val plane = planes.first()
        val buffer = plane.buffer
        buffer.rewind()

        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val bitmapWidth = width + rowPadding / pixelStride

        val paddedBitmap = Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888)
        paddedBitmap.copyPixelsFromBuffer(buffer)

        if (bitmapWidth == width) return paddedBitmap

        val croppedBitmap = Bitmap.createBitmap(paddedBitmap, 0, 0, width, height)
        paddedBitmap.recycle()
        return croppedBitmap
    }

    private fun processFrame(bitmap: Bitmap) {
        ScreenFrameProcessor.submit(bitmap)
    }

    private fun getScreenMetrics(): Triple<Int, Int, Int> {
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            val densityDpi = resources.configuration.densityDpi
            Triple(bounds.width(), bounds.height(), densityDpi)
        } else {
            @Suppress("DEPRECATION")
            val display = windowManager.defaultDisplay
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            Triple(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
        }
    }

    private fun startAsForeground() {
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.app_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Captura de tela para análise visual da mesa de sinuca"
            setSound(null, null)
        }

        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ACTION_STOP_CAPTURE
        }

        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Analisando a mesa de sinuca em tempo real")
            .setSmallIcon(R.drawable.ic_8_ball)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, getString(R.string.stop), stopPendingIntent)
            .build()
    }

    private fun getParcelableIntentExtra(source: Intent, key: String): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            source.getParcelableExtra(key, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            source.getParcelableExtra(key)
        }
    }
}
