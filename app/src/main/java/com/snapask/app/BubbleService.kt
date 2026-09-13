package com.snapask.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

class BubbleService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val PREFS = "snapask"
        const val KEY_TARGET_PKG = "targetPkg"
        const val KEY_TARGET_CLS = "targetCls"
        /** true = keep a persistent capture session (instant taps, but Android
         * shows a permanent recording icon). false = ask for consent on every
         * tap (no persistent icon). */
        const val KEY_PERSISTENT = "persistentCapture"
        const val EXTRA_ONE_SHOT = "oneShot"
        const val ACTION_RELEASE_CAPTURE = "com.snapask.app.RELEASE_CAPTURE"
        private const val CHANNEL_ID = "bubble"
        private const val NOTIF_ID = 1

        @Volatile var running = false
    }

    private var mediaProjection: MediaProjection? = null
    private var bubble: View? = null
    private var wm: WindowManager? = null
    private val handler = Handler(Looper.getMainLooper())
    // The consent Intent we already built a projection from. Android 14 throws
    // SecurityException ("Don't re-use the resultData...") if the same
    // resultData is handed to getMediaProjection() twice, so we must skip
    // redelivered/duplicate start commands.
    private var usedResultFingerprint: String? = null
    // Persistent capture pipeline: one VirtualDisplay + ImageReader per
    // projection, reused for every tap. Creating/releasing a display per tap
    // is what trips Android 14's reuse protections.
    private var captureReader: ImageReader? = null
    private var captureDisplay: VirtualDisplay? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        startAsForeground()
        addBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Mode switch from the settings UI: drop any persistent capture
        // session immediately (idempotent when nothing is held).
        if (intent?.action == ACTION_RELEASE_CAPTURE) {
            releaseCapture()
            try { mediaProjection?.stop() } catch (_: Exception) {}
            mediaProjection = null
            // Drop back to the non-projection foreground type.
            startAsForeground()
            return START_STICKY
        }
        val rc = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else
            intent?.getParcelableExtra(EXTRA_DATA)
        if (rc == Activity.RESULT_OK && data != null) {
            // Skip duplicate/redelivered commands carrying data we already used.
            // NOTE: use toUri (includes extras) — filterEquals() ignores extras
            // and would wrongly match a *fresh* consent.
            val fingerprint = data.toUri(Intent.URI_INTENT_SCHEME)
            if (fingerprint == usedResultFingerprint) {
                return START_STICKY
            }
            val oneShot = intent?.getBooleanExtra(EXTRA_ONE_SHOT, false) == true
            try {
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                        as MediaProjectionManager
                if (oneShot) {
                    // One-shot capture: build a throwaway projection, grab one
                    // frame, then release everything. Nothing stays alive, so
                    // Android shows no persistent recording indicator.
                    // Claim the mediaProjection foreground type FIRST: on
                    // Android 14+ getMediaProjection() itself throws
                    // SecurityException unless the caller already runs a
                    // foreground service of that type.
                    startAsForeground(projectionType = true)
                    val mp = try {
                        mpm.getMediaProjection(rc, data)
                    } catch (t: Throwable) {
                        startAsForeground() // drop back to specialUse
                        throw t
                    }
                    // Android 14+ (target 34): a callback must be registered
                    // before createVirtualDisplay, or it throws SecurityException.
                    mp.registerCallback(object : MediaProjection.Callback() {},
                        handler)
                    usedResultFingerprint = fingerprint
                    try {
                        oneShotCapture(mp)
                    } finally {
                        try { mp.stop() } catch (_: Exception) {}
                        // Drop back to specialUse: no lingering red indicator.
                        startAsForeground()
                    }
                    return START_STICKY
                }
                releaseCapture()
                try { mediaProjection?.stop() } catch (_: Exception) {}
                mediaProjection = null
                // Claim the mediaProjection foreground type BEFORE
                // getMediaProjection(): Android 14+ requires it at that point.
                startAsForeground(projectionType = true)
                mediaProjection = try {
                    mpm.getMediaProjection(rc, data)
                } catch (t: Throwable) {
                    startAsForeground() // drop back to specialUse
                    throw t
                }
                // Android 14+ (target 34): a callback must be registered before
                // createVirtualDisplay, or it throws SecurityException.
                mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        handler.post { stopSelf() }
                    }
                }, handler)
                usedResultFingerprint = fingerprint
                setupCapture()
                // Live session held: the mediaProjection foreground type is
                // already claimed above (required on Android 14+ while
                // capturing).
            } catch (t: Throwable) {
                releaseCapture()
                try { mediaProjection?.stop() } catch (_: Exception) {}
                mediaProjection = null
                handler.post {
                    Toast.makeText(this,
                        "Screen capture failed: ${t.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        try { bubble?.let { wm?.removeView(it) } } catch (_: Exception) {}
        bubble = null
        releaseCapture()
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null
        super.onDestroy()
    }

    /** Creates the persistent VirtualDisplay + ImageReader for this projection. */
    private fun setupCapture() {
        val mp = mediaProjection ?: return
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val d = metrics.densityDpi
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        val vd = mp.createVirtualDisplay(
            "snapask", w, h, d,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, null
        )
        captureReader = reader
        captureDisplay = vd
    }

    /** Releases the persistent capture pipeline. */
    private fun releaseCapture() {
        try { captureDisplay?.release() } catch (_: Exception) {}
        captureDisplay = null
        try { captureReader?.close() } catch (_: Exception) {}
        captureReader = null
    }

    /**
     * Finds the Muse app's image-share activity, if installed.
     * Prefers an exact "Muse" label match, then any label/package containing "muse".
     */
    private fun resolveMuseTarget(): Pair<String, String>? {
        val probe = Intent(Intent.ACTION_SEND).apply { type = "image/png" }
        val infos = packageManager.queryIntentActivities(probe, 0)
        val scored = infos.mapNotNull { ri ->
            val ai = ri.activityInfo ?: return@mapNotNull null
            if (!ai.exported) return@mapNotNull null
            val label = ri.loadLabel(packageManager).toString()
            val score = when {
                label.equals("Muse", ignoreCase = true) -> 0
                label.contains("muse", ignoreCase = true) -> 1
                ai.packageName.contains("muse", ignoreCase = true) -> 2
                else -> return@mapNotNull null
            }
            Triple(score, ai.packageName, ai.name)
        }.sortedWith(compareBy({ it.first }, { it.second }))
        return scored.firstOrNull()?.let { it.second to it.third }
    }

    // ---------- foreground ----------

    private fun startAsForeground(projectionType: Boolean = false) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "SnapAsk bubble", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SnapAsk bubble is on")
            .setContentText("Tap the white bubble to screenshot & ask")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
        // Android 14+ throws SecurityException if we claim the mediaProjection
        // foreground type without actually holding a live MediaProjection, so
        // only use that type while a persistent session exists, or while a
        // one-shot capture explicitly requests it. In per-tap mode (no
        // session held) run as specialUse instead.
        if (Build.VERSION.SDK_INT >= 29 && (mediaProjection != null || projectionType)) {
            startForeground(NOTIF_ID, notif,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    // ---------- bubble ----------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** Clips [src] to a circle for the floating bubble. */
    private fun circularBitmap(src: android.graphics.Bitmap): android.graphics.Bitmap {
        val size = minOf(src.width, src.height)
        val out = android.graphics.Bitmap.createBitmap(
            size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.xfermode = android.graphics.PorterDuffXfermode(
            android.graphics.PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(src,
            (size - src.width) / 2f, (size - src.height) / 2f, paint)
        return out
    }

    private fun addBubble() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val tv = android.widget.ImageView(this).apply {
            val raw = android.graphics.BitmapFactory.decodeResource(
                resources, R.drawable.bubble_icon)
            setImageBitmap(circularBitmap(raw))
            background = null
            setPadding(0, 0, 0, 0)
        }
        val size = dp(46)
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = dp(220)
        }

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        var moved = false
        tv.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = params.x; startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt()
                    val dy = (e.rawY - downY).toInt()
                    if (dx * dx + dy * dy > dp(8) * dp(8)) moved = true
                    params.x = startX + dx
                    params.y = startY + dy
                    wm?.updateViewLayout(v, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) takeScreenshot()
                    true
                }
                else -> false
            }
        }
        bubble = tv
        wm?.addView(tv, params)
    }

    // ---------- screenshot + share ----------

    private fun takeScreenshot() {
        val persistent = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getBoolean(KEY_PERSISTENT, false)
        val reader = captureReader
        if (persistent) {
            if (reader == null || mediaProjection == null) {
                Toast.makeText(this,
                    "Screen capture not granted — open SnapAsk and allow it",
                    Toast.LENGTH_LONG).show()
                return
            }
            // Fast path: grab from the persistent pipeline; it stays alive.
            grabFrameAndShare(reader) { /* keep persistent session */ }
        } else {
            // Per-tap mode: ask the system for fresh consent on every tap.
            // Nothing is kept alive, so there is no persistent recording icon.
            val gate = Intent(this, CaptureGateActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                startActivity(gate)
            } catch (t: Throwable) {
                Toast.makeText(this, "Couldn't open capture prompt: ${t.message}",
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * One-shot capture: creates a throwaway VirtualDisplay + ImageReader on
     * the given projection, grabs one frame, then tears everything down.
     */
    private fun oneShotCapture(mp: MediaProjection) {
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val d = metrics.densityDpi
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        val vd = mp.createVirtualDisplay(
            "snapask-oneshot", w, h, d,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, null
        )
        grabFrameAndShare(reader) {
            try { vd.release() } catch (_: Exception) {}
            try { reader.close() } catch (_: Exception) {}
            try { mp.stop() } catch (_: Exception) {}
            // One-shot session is over: drop back to the non-projection
            // foreground type so no recording icon lingers.
            if (mediaProjection === mp) mediaProjection = null
            startAsForeground()
        }
    }

    /**
     * Shared frame grab: hides the bubble, waits for the latest frame from
     * [reader], saves it as PNG and shares it, then runs [onDone] (used to
     * tear down one-shot pipelines; the persistent pipeline stays alive).
     */
    private fun grabFrameAndShare(reader: ImageReader, onDone: () -> Unit) {
        bubble?.visibility = View.INVISIBLE
        Thread {
            try {
                Thread.sleep(250) // let the bubble disappear
                var image = reader.acquireLatestImage()
                var tries = 0
                while (image == null && tries < 40) {
                    Thread.sleep(100); tries++
                    try { image = reader.acquireLatestImage() } catch (_: Exception) { break }
                }
                if (image == null) throw IllegalStateException("no frame captured")
                val metrics = resources.displayMetrics
                val w = metrics.widthPixels
                val h = metrics.heightPixels
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * w
                var bmp = Bitmap.createBitmap(
                    w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(buffer)
                image.close()
                bmp = Bitmap.createBitmap(bmp, 0, 0, w, h)

                val dir = File(cacheDir, "shots").apply { mkdirs() }
                // keep only the newest 10 shots
                dir.listFiles()?.sortedBy { it.lastModified() }
                    ?.dropLast(9)?.forEach { try { it.delete() } catch (_: Exception) {} }
                val file = File(dir, "shot_${System.currentTimeMillis()}.png")
                FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bmp.recycle()
                handler.post { shareShot(file) }
            } catch (t: Throwable) {
                handler.post {
                    Toast.makeText(this, "Screenshot failed: ${t.message}",
                        Toast.LENGTH_LONG).show()
                }
            } finally {
                try { onDone() } catch (_: Exception) {}
                handler.post { bubble?.visibility = View.VISIBLE }
            }
        }.start()
    }

    private fun shareShot(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        var pkg = prefs.getString(KEY_TARGET_PKG, null)
        var cls = prefs.getString(KEY_TARGET_CLS, null)
        if (pkg == null || cls == null) {
            // No explicit choice (setup step 4 was skipped): auto-select the
            // Muse app when it is installed, so tapping the bubble goes
            // straight there instead of showing the chooser every time.
            resolveMuseTarget()?.let { (mp, mc) ->
                pkg = mp; cls = mc
                prefs.edit()
                    .putString(KEY_TARGET_PKG, mp)
                    .putString(KEY_TARGET_CLS, mc)
                    .apply()
            }
        }
        val targetPkg: String? = pkg
        val targetCls: String? = cls
        if (targetPkg != null && targetCls != null) {
            send.component = ComponentName(targetPkg, targetCls)
        }
        // Back from the target app returns to the app the user was in.
        // The chooser wrapper needs its own NEW_TASK flag: it is a separate
        // Intent launched from a Service, and does not inherit flags from `send`.
        val launch = if (send.component == null)
            Intent.createChooser(send, "Ask about this screenshot in…").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        else send
        try {
            startActivity(launch)
        } catch (e: android.content.ActivityNotFoundException) {
            // The saved target was uninstalled (or otherwise can't handle the
            // intent). Forget it so the next tap re-resolves, and fall back to
            // the chooser instead of crashing the service (which would kill
            // the bubble).
            prefs.edit()
                .remove(KEY_TARGET_PKG)
                .remove(KEY_TARGET_CLS)
                .apply()
            android.util.Log.w("SnapAsk",
                "Saved share target missing, falling back to chooser", e)
            val chooser = Intent.createChooser(send.apply { component = null },
                "Ask about this screenshot in…").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                startActivity(chooser)
            } catch (e2: android.content.ActivityNotFoundException) {
                handler.post {
                    Toast.makeText(this,
                        "No app available to receive the screenshot",
                        Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
