package com.sinat.osrsbubbletool

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display

// Takes screenshots of the whole screen using Android's screen-capture feature.
class ScreenCapturer(private val context: Context, private val onStopped: () -> Unit) {

    private data class ScreenSize(val width: Int, val height: Int, val dpi: Int)

    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    val isActive: Boolean get() = projection != null

    fun start(resultCode: Int, data: Intent) {
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        val proj = manager.getMediaProjection(resultCode, data)
            ?: throw IllegalStateException("Android didn't allow screen capture")

        // Android requires this callback to be set before capturing starts
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                release()
                onStopped()
            }
        }, handler)
        projection = proj

        val size = screenSize()
        val reader = ImageReader.newInstance(size.width, size.height, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        virtualDisplay = proj.createVirtualDisplay(
            "OSRSBubbleCapture", size.width, size.height, size.dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, handler
        )
    }

    fun stop() {
        val proj = projection ?: return
        release()
        proj.stop()
    }

    // Call before grabbing, so a rotated screen is captured at the right size
    fun matchScreenSize() {
        val reader = imageReader ?: return
        val size = screenSize()
        if (size.width == reader.width && size.height == reader.height) return
        val newReader = ImageReader.newInstance(size.width, size.height, PixelFormat.RGBA_8888, 2)
        virtualDisplay?.resize(size.width, size.height, size.dpi)
        virtualDisplay?.surface = newReader.surface
        reader.close()
        imageReader = newReader
    }

    // Returns the most recent screen image, or null if none is ready yet
    fun grab(): Bitmap? {
        val reader = imageReader ?: return null
        val image = reader.acquireLatestImage() ?: return null
        try {
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowPadding = plane.rowStride - pixelStride * image.width
            val padded = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888
            )
            padded.copyPixelsFromBuffer(plane.buffer)
            return Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        } finally {
            image.close()
        }
    }

    private fun release() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        projection = null
    }

    private fun screenSize(): ScreenSize {
        val display = context.getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        return ScreenSize(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
    }
}