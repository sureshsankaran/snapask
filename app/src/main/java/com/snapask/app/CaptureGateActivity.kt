package com.snapask.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity

/**
 * One-shot capture gate: launched from the bubble on every tap when instant
 * capture is off. Asks the system for fresh screen-capture consent, hands the
 * result to BubbleService for a single screenshot, then finishes. Nothing is
 * kept alive, so Android shows no persistent recording indicator.
 */
class CaptureGateActivity : ComponentActivity() {

    private val launcher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK && res.data != null) {
            val svc = Intent(this, BubbleService::class.java).apply {
                putExtra(BubbleService.EXTRA_RESULT_CODE, res.resultCode)
                putExtra(BubbleService.EXTRA_DATA, res.data)
                putExtra(BubbleService.EXTRA_ONE_SHOT, true)
            }
            if (Build.VERSION.SDK_INT >= 29) startForegroundService(svc)
            else startService(svc)
        } else {
            Toast.makeText(this, "Screen capture was not allowed",
                Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
        launcher.launch(mpm.createScreenCaptureIntent())
    }
}
