package com.snapask.app

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity

class MainActivity : ComponentActivity() {

    private lateinit var pillOverlay: TextView
    private lateinit var pillBubble: TextView
    private lateinit var pillTarget: TextView
    private lateinit var btnOverlay: Button
    private lateinit var btnCapture: Button
    private lateinit var btnToggle: Button
    private lateinit var step4Desc: TextView

    private val captureLauncher =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
        ) { res ->
            if (res.resultCode == RESULT_OK && res.data != null) {
                val svc = Intent(this, BubbleService::class.java).apply {
                    putExtra(BubbleService.EXTRA_RESULT_CODE, res.resultCode)
                    putExtra(BubbleService.EXTRA_DATA, res.data)
                }
                if (Build.VERSION.SDK_INT >= 29) {
                    startForegroundService(svc)
                } else {
                    startService(svc)
                }
                Toast.makeText(this, "Bubble started — look for the camera icon",
                    Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Screen capture was not allowed", Toast.LENGTH_LONG).show()
            }
            refresh()
        }

    private val notifLauncher =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
        ) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        pillOverlay = findViewById(R.id.pillOverlay)
        pillBubble = findViewById(R.id.pillBubble)
        pillTarget = findViewById(R.id.pillTarget)
        btnOverlay = findViewById(R.id.btnOverlay)
        btnCapture = findViewById(R.id.btnCapture)
        btnToggle = findViewById(R.id.btnToggle)
        step4Desc = findViewById(R.id.step4Desc)
        val btnNotif: Button = findViewById(R.id.btnNotif)
        val btnTarget: Button = findViewById(R.id.btnTarget)

        btnOverlay.setOnClickListener {
            startActivity(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ))
        }
        btnNotif.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) {
                notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            } else {
                Toast.makeText(this, "Not needed on this Android version",
                    Toast.LENGTH_SHORT).show()
            }
        }
        btnCapture.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this,
                    "Do step 1 first (overlay permission)", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                    as MediaProjectionManager
            captureLauncher.launch(mpm.createScreenCaptureIntent())
        }
        btnTarget.setOnClickListener { pickTarget() }
        btnToggle.setOnClickListener {
            if (BubbleService.running) {
                stopService(Intent(this, BubbleService::class.java))
            }
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun setPill(pill: TextView, text: String, ok: Boolean) {
        pill.text = text
        pill.setBackgroundResource(if (ok) R.drawable.pill_ok else R.drawable.pill_warn)
        pill.setTextColor(getColor(if (ok) R.color.success else R.color.warning))
    }

    private fun setStepDone(circleId: Int, numId: Int, done: Boolean) {
        findViewById<FrameLayout>(circleId)
            .setBackgroundResource(if (done) R.drawable.step_circle_done else R.drawable.step_circle)
        val num = findViewById<TextView>(numId)
        if (done) {
            num.text = "✓"
            num.setTextColor(getColor(R.color.success))
        }
    }

    private fun refresh() {
        val overlayOk = Settings.canDrawOverlays(this)
        val prefs = getSharedPreferences(BubbleService.PREFS, MODE_PRIVATE)
        val target = prefs.getString(BubbleService.KEY_TARGET_PKG, null)

        setPill(pillOverlay, getString(if (overlayOk) R.string.granted else R.string.missing), overlayOk)
        setPill(pillBubble, getString(if (BubbleService.running) R.string.running else R.string.stopped),
            BubbleService.running)

        if (target != null) {
            // Show the chosen app's label.
            val label = appLabelFor(target)
            pillTarget.text = label
            setPill(pillTarget, label, true)
            step4Desc.text = "Screenshots open in $label."
        } else {
            setPill(pillTarget, "Muse (auto)", true)
            step4Desc.text = getString(R.string.step4_desc)
        }

        setStepDone(R.id.stepCircle1, R.id.stepNum1, overlayOk)
        // Step 2 (notifications) has no reliable runtime check here; leave numbered.
        setStepDone(R.id.stepCircle3, R.id.stepNum3, BubbleService.running)

        btnOverlay.isEnabled = !overlayOk
        btnOverlay.alpha = if (overlayOk) 0.4f else 1f
        btnToggle.visibility = if (BubbleService.running) View.VISIBLE else View.GONE
    }

    private fun appLabelFor(pkg: String): String {
        return try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) { pkg }
    }

    private fun pickTarget() {
        val probe = Intent(Intent.ACTION_SEND).apply { type = "image/png" }
        val infos = packageManager
            .queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
            .filter { it.activityInfo.exported }
            .sortedBy { it.loadLabel(packageManager).toString().lowercase() }
        if (infos.isEmpty()) {
            Toast.makeText(this, "No apps can receive images", Toast.LENGTH_LONG).show()
            return
        }
        val names = infos.map { "${it.loadLabel(packageManager)}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Ask screenshots in…")
            .setItems(names) { _, which ->
                val ai = infos[which].activityInfo
                getSharedPreferences(BubbleService.PREFS, MODE_PRIVATE).edit()
                    .putString(BubbleService.KEY_TARGET_PKG, ai.packageName)
                    .putString(BubbleService.KEY_TARGET_CLS, ai.name)
                    .apply()
                Toast.makeText(this, "Screenshots will open in ${names[which]}",
                    Toast.LENGTH_SHORT).show()
                refresh()
            }
            .setNeutralButton("Auto (Muse)") { _, _ ->
                getSharedPreferences(BubbleService.PREFS, MODE_PRIVATE).edit()
                    .remove(BubbleService.KEY_TARGET_PKG)
                    .remove(BubbleService.KEY_TARGET_CLS)
                    .apply()
                refresh()
            }
            .show()
    }
}
