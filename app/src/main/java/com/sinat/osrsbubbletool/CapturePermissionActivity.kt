package com.sinat.osrsbubbletool

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

// An invisible screen that asks Android for screen-capture permission,
// then passes the answer back to the bubble.
class CapturePermissionActivity : ComponentActivity() {

    private val permissionRequest =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val reply = Intent(this, BubbleService::class.java).apply {
                action = BubbleService.ACTION_CAPTURE_RESULT
                putExtra(BubbleService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(BubbleService.EXTRA_RESULT_DATA, result.data)
            }
            startService(reply)
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            val manager = getSystemService(MediaProjectionManager::class.java)
            permissionRequest.launch(manager.createScreenCaptureIntent())
        }
    }
}