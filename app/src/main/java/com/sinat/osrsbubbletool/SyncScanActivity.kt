package com.sinat.osrsbubbletool

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Size
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// The camera, for Bubble Sync: point it at the QR code the Bubble Tool Sync plugin shows in RuneLite.
// It saves the account and closes as soon as it reads a good code. The camera only runs while this is open,
// and the camera permission is asked for the first time you scan.
class SyncScanActivity : ComponentActivity() {

    private lateinit var status: TextView
    private val work: ExecutorService = Executors.newSingleThreadExecutor()   // reads the camera's pictures
    private val reader = QRCodeReader()
    private val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)
    @Volatile private var finished = false
    private var lastBadCode = ""   // a code that couldn't be read, so its message isn't shown again every picture

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera()
        else status.text = "The app needs the camera to scan the code. You can allow it in the phone's Settings → Apps → " +
            "OSRS Bubble Tool → Permissions, or use Paste code on the Bubble Sync page instead."
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preview = PreviewView(this)
        status = TextView(this).apply {
            text = "Point the camera at the QR code in RuneLite's Bubble Tool Sync panel."
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply { setColor("#B0000000".toColorInt()); cornerRadius = dp(8).toFloat() }
        }
        val cancel = TextView(this).apply {
            text = "Cancel"
            textSize = 15f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(10), dp(24), dp(10))
            background = GradientDrawable().apply { setColor(Palette.BUTTON_BROWN); cornerRadius = dp(8).toFloat() }
            setOnClickListener { finish() }
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(cancel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(10) })
        }
        // keep the text and button clear of the status bar, navigation bar and camera cutout
        ViewCompat.setOnApplyWindowInsetsListener(controls) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(dp(16) + bars.left, dp(16) + bars.top, dp(16) + bars.right, dp(16) + bars.bottom)
            insets
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(controls, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        })
        this.preview = preview

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else askCamera.launch(Manifest.permission.CAMERA)
    }

    private lateinit var preview: PreviewView

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = try { future.get() } catch (e: Exception) { status.text = "The camera couldn't start."; return@addListener }
            val show = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
            // pictures about 1280 × 720: sharp enough for the plugin's code from arm's length, small enough to read quickly
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(ResolutionStrategy(Size(1280, 720),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build())
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)   // skip pictures while one is being read
                .build()
            analysis.setAnalyzer(work) { image -> image.use { read(it) } }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, show, analysis)
            } catch (e: Exception) {
                status.text = "The camera couldn't start."
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // Looks for a QR code in one camera picture (its brightness part is all ZXing needs)
    private fun read(image: ImageProxy) {
        if (finished) return
        val plane = image.planes[0]
        val buffer = plane.buffer
        val data = ByteArray(buffer.remaining()).also { buffer.get(it) }
        val source = PlanarYUVLuminanceSource(data, plane.rowStride, image.height, 0, 0, image.width, image.height, false)
        val text = try {
            reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        } catch (e: ReaderException) {
            null
        } finally {
            reader.reset()
        } ?: return
        runOnUiThread { found(text) }
    }

    private fun found(text: String) {
        if (finished || text == lastBadCode) return
        try {
            val account = AccountSync(this).save(text)
            finished = true
            Toast.makeText(this, "Synced " + account.name.ifEmpty { "your account" }, Toast.LENGTH_SHORT).show()
            finish()
        } catch (e: SyncCode.BadCode) {
            lastBadCode = text
            status.text = e.message
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        work.shutdown()
        // closed (scanned, or Cancel / back): bring the bubble's Bubble Sync window back, if that's where this came
        // from (from the app's main screen, that screen is simply back in front)
        if (isFinishing && intent.getBooleanExtra(SyncFileActivity.EXTRA_FROM_BUBBLE, false) && BubbleService.isRunning) {
            startService(Intent(this, BubbleService::class.java).setAction(BubbleService.ACTION_SYNC_SCAN_DONE))
        }
    }
}
