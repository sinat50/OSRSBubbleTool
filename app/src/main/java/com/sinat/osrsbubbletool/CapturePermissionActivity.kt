package com.sinat.osrsbubbletool

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle

// An invisible screen that only shows Android's "allow screen capture?" question,
// then hands the answer to the bubble.
class CapturePermissionActivity : Activity() {

    companion object {
        private const val REQUEST_CAPTURE = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return   // already asking (the screen was rebuilt)
        val manager = getSystemService(MediaProjectionManager::class.java)
        // Ask for the whole screen. (Android 14+ otherwise offers "a single app", which
        // captures a different area and can end on its own when you switch apps.)
        val ask = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            else manager.createScreenCaptureIntent()
        @Suppress("DEPRECATION")
        startActivityForResult(ask, REQUEST_CAPTURE)
    }

    @Deprecated("Needed for the capture question")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CAPTURE) {
            startService(
                Intent(this, BubbleService::class.java)
                    .setAction(BubbleService.ACTION_CAPTURE_RESULT)
                    .putExtra(BubbleService.EXTRA_RESULT_CODE, resultCode)
                    .putExtra(BubbleService.EXTRA_RESULT_DATA, data)
            )
        }
        finish()
    }
}
