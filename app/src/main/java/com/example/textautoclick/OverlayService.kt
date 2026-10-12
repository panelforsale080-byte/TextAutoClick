package com.example.textautoclick

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*

class OverlayService : LifecycleService(), SavedStateRegistryOwner, ViewModelStoreOwner {

    companion object {
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "OverlayServiceChannel"
        const val ACTION_STOP = "com.example.textautoclick.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        private const val POLL_MS = 1000L      // battery-friendly cadence
        private const val COOLDOWN_MS = 2000L  // no double-taps on the same text
        private const val SCALE = 0.5f         // OCR on downscaled frame = less CPU
    }

    // Compose-in-a-Service needs all three view-tree owners wired manually.
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    private lateinit var windowManager: WindowManager
    private var overlayView: ComposeView? = null
    private lateinit var params: WindowManager.LayoutParams

    // Compose state the UI binds to
    private val minimized = mutableStateOf(false)
    private val detecting = mutableStateOf(false)
    private val keyword = mutableStateOf("Click Me")
    private val lastStatus = mutableStateOf("idle")
    private val hitsSeen = mutableStateOf(0)

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var detectionJob: Job? = null
    private var lastTapAt = 0L

    override fun onCreate() {
        super.onCreate()
        savedStateController.performRestore(null)
        createNotificationChannel()
        startForegroundCompat(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        addOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        if (resultCode == Activity.RESULT_OK && data != null && mediaProjection == null) {
            setupMediaProjection(resultCode, data)
        }
        return START_STICKY
    }

    // ---------- Overlay ----------

    private fun addOverlay() {
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@OverlayService)
            setViewTreeViewModelStoreOwner(this@OverlayService)
            setViewTreeSavedStateRegistryOwner(this@OverlayService)
            setContent {
                OverlayUI(
                    minimized = minimized.value,
                    detecting = detecting.value,
                    keyword = keyword.value,
                    lastStatus = lastStatus.value,
                    onKeywordChange = { keyword.value = it },
                    onToggleDetection = { toggleDetection() },
                    onMinimize = { minimized.value = true },
                    onExpand = { minimized.value = false },
                    onFieldFocus = { focused -> setFocusable(focused) },
                    onDragBy = { dx, dy ->
                        params.x += dx; params.y += dy
                        try { windowManager.updateViewLayout(overlayView, params) } catch (_: Throwable) {}
                    }
                )
            }
        }
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 80; y = 160 }
        windowManager.addView(view, params)
        overlayView = view
    }

    /** Keyboard fix: overlay windows start NOT_FOCUSABLE so touches pass to apps below.
     *  Flip focusable + NOT_TOUCH_MODAL while the keyword field is focused, then
     *  explicitly showSoftInput (flipping flags alone does NOT attach the IME). */
    private fun setFocusable(focused: Boolean) {
        val v = overlayView ?: return
        params.flags = if (focused)
            (params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()) or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        else
            (params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) and
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL.inv()
        try { windowManager.updateViewLayout(v, params) } catch (_: Throwable) {}
        if (focused) {
            v.post {
                try {
                    val imm = getSystemService(INPUT_METHOD_SERVICE)
                            as android.view.inputmethod.InputMethodManager
                    imm.showSoftInput(v, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                } catch (_: Throwable) {}
            }
        }
    }

    /** Screen rect (capture pixels = screen * SCALE) occupied by our own overlay —
     *  OCR hits inside it are OUR panel text, never tap them. */
    private fun selfRect(): Rect {
        val v = overlayView ?: return Rect()
        val loc = IntArray(2); v.getLocationOnScreen(loc)
        return Rect(
            (loc[0] * SCALE).toInt(), (loc[1] * SCALE).toInt(),
            ((loc[0] + v.width) * SCALE).toInt(), ((loc[1] + v.height) * SCALE).toInt()
        )
    }

    // ---------- MediaProjection ----------

    private fun setupMediaProjection(resultCode: Int, data: Intent) {
        // Android 14+: the FGS must run with mediaProjection type BEFORE getMediaProjection().
        if (Build.VERSION.SDK_INT >= 29) {
            startForegroundCompat(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(resultCode, data)

        val w = (resources.displayMetrics.widthPixels * SCALE).toInt()
        val h = (resources.displayMetrics.heightPixels * SCALE).toInt()
        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { releaseCapture() }
        }, null)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "capture", w, h, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
        lastStatus.value = "capture ready — press Start"
    }

    // ---------- Detection loop ----------

    private fun toggleDetection() {
        if (detecting.value) {
            detectionJob?.cancel(); detectionJob = null
            detecting.value = false
            minimized.value = false   // bring the panel back when stopping
            lastStatus.value = "stopped"
        } else {
            if (mediaProjection == null) { lastStatus.value = "grant screen capture first"; return }
            if (!AutoClickAccessibilityService.isRunning()) {
                lastStatus.value = "enable accessibility first"; return
            }
            detecting.value = true
            hitsSeen.value = 0
            // CRITICAL: hide the panel — otherwise OCR reads our own TextField
            // showing the keyword and self-taps in a loop.
            minimized.value = true
            lastStatus.value = "watching '${keyword.value}'… (panel hidden)"
            detectionJob = serviceScope.launch { detectionLoop() }
        }
    }

    private suspend fun detectionLoop() {
        while (currentCoroutineContext().isActive) {
            val image = try { imageReader?.acquireLatestImage() } catch (_: Throwable) { null }
            if (image != null) {
                val bitmap = imageToBitmapCropped(image)
                image.close()
                if (bitmap != null) {
                    val kw = keyword.value.trim().lowercase()
                    recognizer.process(InputImage.fromBitmap(bitmap, 0))
                        .addOnSuccessListener { text ->
                            // FUZZY line-level matching: normalize both sides (lowercase,
                            // letters/digits only) so "Click Me", "click me", "CLICK ME"
                            // and "Click me Owdbsiwowwkek" all match keyword "click me".
                            val normKw = normalize(kw)
                            val self = selfRect()
                            val hit = if (normKw.isEmpty()) null else text.textBlocks.asSequence()
                                .flatMap { it.lines.asSequence() }
                                .firstOrNull { line ->
                                    val box = line.boundingBox ?: return@firstOrNull false
                                    normalize(line.text).contains(normKw) && !Rect.intersects(box, self)
                                }
                            if (hit != null) {
                                hitsSeen.value += 1
                                val now = System.currentTimeMillis()
                                if (now - lastTapAt < COOLDOWN_MS) return@addOnSuccessListener
                                val box = hit.boundingBox ?: return@addOnSuccessListener
                                val ok = AutoClickAccessibilityService.instance
                                    ?.performClick(box.exactCenterX() / SCALE, box.exactCenterY() / SCALE)
                                    ?: false
                                lastTapAt = now
                                lastStatus.value = "tapped '${hit.text}' ok=$ok"
                            } else {
                                lastStatus.value = "scanning… no match for '${keyword.value}'"
                            }
                        }
                        .addOnFailureListener { lastStatus.value = "ocr error" }
                }
            }
            delay(POLL_MS)
        }
    }

    /** Lowercase + letters/digits only — "Click Me" -> "clickme". */
    private fun normalize(s: String): String =
        s.lowercase().filter { it.isLetterOrDigit() }

    /** Handles row-stride padding AND crops back to the true frame size. */
    private fun imageToBitmapCropped(image: android.media.Image): Bitmap? {
        return try {
            val plane = image.planes[0]
            val paddedWidth = plane.rowStride / plane.pixelStride
            val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(plane.buffer)
            if (paddedWidth == image.width) padded
            else Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        } catch (_: Throwable) { null }
    }

    // ---------- Lifecycle / notification ----------

    private fun releaseCapture() {
        try { virtualDisplay?.release() } catch (_: Throwable) {}
        try { imageReader?.close() } catch (_: Throwable) {}
        try { mediaProjection?.stop() } catch (_: Throwable) {}
        virtualDisplay = null; imageReader = null; mediaProjection = null
    }

    private fun startForegroundCompat(type: Int) {
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notif, type)
        else startForeground(NOTIFICATION_ID, notif)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Overlay Service", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(): Notification {
        // The ONLY way to fully stop the service — the overlay itself can only be minimized.
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Text AutoClick running")
            .setContentText("Overlay active — stop from here")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    override fun onDestroy() {
        detectionJob?.cancel()
        serviceScope.cancel()
        releaseCapture()
        recognizer.close()
        overlayView?.let { try { windowManager.removeView(it) } catch (_: Throwable) {} }
        overlayView = null
        store.clear()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }
}
