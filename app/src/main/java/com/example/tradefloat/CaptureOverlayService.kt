package com.example.tradefloat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
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
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Color
import android.view.View
import android.view.WindowManager.LayoutParams
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

class CaptureOverlayService : Service() {
    companion object {
        const val ACTION_START = "com.example.tradefloat.START"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        private const val CHANNEL_ID = "tradefloat_capture"
        private const val NOTIFICATION_ID = 4102
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var overlay: View? = null
    private var statusView: TextView? = null
    private val frames = ArrayDeque<Bitmap>()
    private val frameLock = Any()
    private val analysing = AtomicBoolean(false)
    private var lastFeatures: LocalFeatures? = null
    private var screenWidth = 720
    private var screenHeight = 1600
    private var density = 1
    private var lastFrameAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            createNotificationChannel()
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            else startForeground(NOTIFICATION_ID, notification)
            startProjection(intent)
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun startProjection(intent: Intent) {
        if (projection != null) return
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java) else intent.getParcelableExtra(EXTRA_RESULT_DATA)
        if (data == null) { updateStatus("ERROR: Missing capture permission"); stopSelf(); return }
        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels.coerceAtLeast(320)
        screenHeight = metrics.heightPixels.coerceAtLeast(480)
        density = metrics.densityDpi
        workerThread = HandlerThread("TradeFloatCapture").also { it.start() }
        workerHandler = Handler(workerThread!!.looper)
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            projection = manager.getMediaProjection(resultCode, data)
            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            imageReader!!.setOnImageAvailableListener({ reader -> onImage(reader.acquireLatestImage()) }, workerHandler)
            virtualDisplay = projection!!.createVirtualDisplay(
                "TradeFloatLiveChart", screenWidth, screenHeight, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface, null, workerHandler
            )
            projection!!.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { updateStatus("Capture stopped by Android"); stopSelf() }
            }, workerHandler)
            showOverlay()
            updateStatus("LIVE • waiting for chart frames")
        } catch (t: Throwable) {
            updateStatus("CAPTURE ERROR: ${t.message ?: "unknown"}")
            stopSelf()
        }
    }

    private fun onImage(image: Image?) {
        if (image == null) return
        try {
            val now = System.currentTimeMillis()
            // Keep the capture loop light: process at most 2 frames per second.
            if (now - lastFrameAt < 450) return
            lastFrameAt = now
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * screenWidth
            val paddedWidth = screenWidth + rowPadding / pixelStride
            val padded = Bitmap.createBitmap(paddedWidth, screenHeight, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(buffer)
            val cropped = Bitmap.createBitmap(padded, 0, 0, screenWidth, screenHeight)
            padded.recycle()
            val features = ChartFrameAnalyzer.analyze(cropped)
            lastFeatures = features
            synchronized(frameLock) {
                frames.addLast(cropped)
                while (frames.size > 4) frames.removeFirst().recycle()
            }
            updateStatus("LIVE • ${features.quality} • ${frames.size} frames")
        } catch (t: Throwable) {
            updateStatus("FRAME ERROR: ${t.message ?: "unknown"}")
        } finally { image.close() }
    }

    private fun showOverlay() {
        if (overlay != null || !android.provider.Settings.canDrawOverlays(this)) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 10, 12, 10)
            setBackgroundColor(0xee111820.toInt())
        }
        val title = TextView(this).apply { text = "TRADEFLOAT AI"; setTextColor(Color.WHITE); textSize = 13f; setPadding(4, 2, 4, 6) }
        statusView = TextView(this).apply { text = "LIVE • starting"; setTextColor(0xffaab4c0.toInt()); textSize = 10f; setPadding(4, 2, 4, 8) }
        panel.addView(title); panel.addView(statusView)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun actionButton(label: String, callback: () -> Unit): Button = Button(this).apply {
            text = label; textSize = 10f; isAllCaps = false; setOnClickListener { callback() }
        }
        row.addView(actionButton("30 sec") { requestPrediction(30) }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(actionButton("1 min") { requestPrediction(60) }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(actionButton("STOP") { stopSelf() }, LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(row)
        val params = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") LayoutParams.TYPE_PHONE,
            LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT)
        params.gravity = Gravity.TOP or Gravity.END
        params.x = 12; params.y = 180
        try { wm.addView(panel, params); overlay = panel } catch (_: Throwable) { updateStatus("OVERLAY ERROR: permission missing") }
    }

    private fun requestPrediction(horizon: Int) {
        if (!analysing.compareAndSet(false, true)) { updateStatus("ANALYSING • please wait"); return }
        val snapshot = synchronized(frameLock) { frames.map { it.copy(Bitmap.Config.ARGB_8888, false) } }
        val features = lastFeatures
        if (snapshot.isEmpty() || features == null) {
            analysing.set(false); updateStatus("WAIT • no chart frames yet"); return
        }
        updateStatus("$horizon sec • local read ready • cloud analysing…")
        WorkerClient.predict(snapshot, horizon, features) { result ->
            analysing.set(false)
            result.onSuccess { signal ->
                updateStatus("${signal.direction} • ${signal.confidence}% • ${signal.reason.take(90)}")
            }.onFailure { error -> updateStatus("CLOUD ERROR • ${error.message?.take(100) ?: "unknown"}") }
            snapshot.forEach { if (!it.isRecycled) it.recycle() }
        }
    }

    private fun updateStatus(message: String) {
        workerHandler?.post { statusView?.text = message }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "TradeFloat screen analysis", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }
    @Suppress("DEPRECATION")
    private fun buildNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("TradeFloat AI is active").setContentText("Chart capture is running. Tap STOP in the overlay to end.")
            .setSmallIcon(android.R.drawable.ic_menu_view).setOngoing(true).build()
        else Notification.Builder(this).setContentTitle("TradeFloat AI is active")
            .setContentText("Chart capture is running").setSmallIcon(android.R.drawable.ic_menu_view).setOngoing(true).build()
    }

    override fun onDestroy() {
        try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(overlay) } catch (_: Throwable) {}
        overlay = null
        virtualDisplay?.release(); virtualDisplay = null
        imageReader?.setOnImageAvailableListener(null, null); imageReader?.close(); imageReader = null
        projection?.stop(); projection = null
        synchronized(frameLock) { frames.forEach { it.recycle() }; frames.clear() }
        workerThread?.quitSafely(); workerThread = null; workerHandler = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
