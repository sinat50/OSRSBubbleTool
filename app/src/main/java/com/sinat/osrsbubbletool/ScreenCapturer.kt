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
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager

// Takes screenshots of the whole screen after you allow screen capture once.
//
// Android only lets an app use each "allow" once: one projection, and one capture display
// on it. Making a second capture display (for example after the screen rotates) makes
// Android end the capture, and the permission question comes back. So this sets up the
// capture display once and only resizes it afterwards, which keeps capture on until the
// bubble is closed or you stop it yourself.
class ScreenCapturer(
    private val context: Context,
    private val onStopped: () -> Unit   // capture ended (stopped from the status bar, etc.)
) {
    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var width = 0
    private var height = 0
    private var paused = false

    // When no tool has looked at the screen for a few seconds, the capture is paused: Android
    // stops copying the screen, which costs nothing, but the permission stays so there's no
    // need to ask again. It restarts by itself the next time a tool needs it.
    private val pauseWhenIdle = Runnable { pause() }

    val isActive: Boolean get() = projection != null && display != null

    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            handler.post {
                val wasActive = projection != null
                cleanUp()
                if (wasActive) onStopped()
            }
        }

        // Android 14+: the captured area changed size (for example the screen rotated)
        override fun onCapturedContentResize(width: Int, height: Int) {
            handler.post { resizeTo(width, height) }
        }
    }

    // Starts capture with the answer from the permission question
    fun start(resultCode: Int, data: Intent) {
        stop()
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        val p = manager.getMediaProjection(resultCode, data)
            ?: throw IllegalStateException("Android didn't allow screen capture")
        p.registerCallback(callback, handler)   // Android 14+ requires this before capturing
        projection = p

        val (w, h, dpi) = screenSize()
        width = w
        height = h
        val r = newReader(w, h)
        reader = r
        display = p.createVirtualDisplay(
            "OSRS Bubble Tool capture", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, handler
        ) ?: throw IllegalStateException("couldn't start screen capture")
        paused = false
        touch()
    }

    private companion object {
        const val IDLE_PAUSE_MS = 3_000L
    }

    // Called whenever a tool uses the capture: makes sure it's running, and pauses it again
    // once nothing has used it for a few seconds
    private fun touch() {
        if (paused) {
            val d = display
            val r = reader
            if (d != null && r != null) {
                d.surface = r.surface
                paused = false
            }
        }
        handler.removeCallbacks(pauseWhenIdle)
        handler.postDelayed(pauseWhenIdle, IDLE_PAUSE_MS)
    }

    private fun pause() {
        val d = display ?: return
        d.surface = null   // stops screen copying without ending the capture
        paused = true
        // throw away any old pictures so the next one is fresh
        val r = reader ?: return
        while (true) {
            val old = try { r.acquireNextImage() } catch (e: Exception) { null } ?: break
            old.close()
        }
    }

    // Makes the capture match the screen again (after the phone turns), without starting over
    fun matchScreenSize() {
        touch()
        val (w, h, _) = screenSize()
        resizeTo(w, h)
    }

    private fun resizeTo(w: Int, h: Int) {
        val d = display ?: return
        if (w <= 0 || h <= 0 || (w == width && h == height)) return
        val dpi = context.resources.displayMetrics.densityDpi
        val old = reader
        val r = newReader(w, h)
        d.resize(w, h, dpi)
        if (!paused) d.surface = r.surface
        reader = r
        width = w
        height = h
        old?.close()
    }

    // The newest picture of the screen, or null if nothing has changed since the last one
    fun grab(): Bitmap? {
        touch()
        val r = reader ?: return null
        val image = try {
            r.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return null
        try {
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val paddedWidth = rowStride / pixelStride   // rows can have extra padding at the end
            val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
            val buffer = plane.buffer
            if (buffer.remaining() < rowStride * image.height) {
                // some phones leave out the padding after the last row: pad it so the copy fits
                val full = java.nio.ByteBuffer.allocateDirect(rowStride * image.height)
                full.put(buffer)
                full.rewind()
                padded.copyPixelsFromBuffer(full)
            } else padded.copyPixelsFromBuffer(buffer)
            return if (paddedWidth == image.width) padded
                else Bitmap.createBitmap(padded, 0, 0, image.width, image.height).also { padded.recycle() }
        } catch (e: RuntimeException) {
            return null   // an odd picture from the phone: skip it rather than crash
        } finally {
            image.close()
        }
    }

    // The newest screen image, read straight from the capture without making a picture.
    // Much quicker when only a few pixels are needed. Null if nothing changed.
    class Frame(
        private val buffer: java.nio.ByteBuffer,
        private val rowStride: Int,
        private val pixelStride: Int,
        override val width: Int,
        override val height: Int
    ) : LightBoxReader.PixelSource {
        override fun rgb(x: Int, y: Int): Int {
            val i = y * rowStride + x * pixelStride
            return ((buffer.get(i).toInt() and 0xFF) shl 16) or
                ((buffer.get(i + 1).toInt() and 0xFF) shl 8) or
                (buffer.get(i + 2).toInt() and 0xFF)
        }
    }

    fun <T> sample(block: (Frame) -> T): T? {
        touch()
        val r = reader ?: return null
        val image = try {
            r.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return null
        try {
            val plane = image.planes[0]
            return block(Frame(plane.buffer, plane.rowStride, plane.pixelStride, image.width, image.height))
        } finally {
            image.close()
        }
    }

    // Ends capture for good
    fun stop() {
        val p = projection
        cleanUp()
        try {
            p?.unregisterCallback(callback)
            p?.stop()
        } catch (e: Exception) {
            // already stopped
        }
    }

    private fun cleanUp() {
        handler.removeCallbacks(pauseWhenIdle)
        paused = false
        display?.release()
        display = null
        reader?.close()
        reader = null
        projection = null
        width = 0
        height = 0
    }

    private fun newReader(w: Int, h: Int): ImageReader =
        ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)

    // The full screen size in pixels, including the status bar and navigation bar
    private fun screenSize(): Triple<Int, Int, Int> {
        val wm = context.getSystemService(WindowManager::class.java)
        val dpi = context.resources.displayMetrics.densityDpi
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            Triple(b.width(), b.height(), dpi)
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(m)
            Triple(m.widthPixels, m.heightPixels, dpi)
        }
    }
}
