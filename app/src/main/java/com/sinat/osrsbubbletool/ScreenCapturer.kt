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
import androidx.core.graphics.createBitmap

// Takes screenshots of the screen after you allow screen capture once.
//
// On Android 14+ you can choose to share only one app (Old School RuneScape). Its pictures are
// then just the game's window: they're placed where the game sits on the screen, so every tool
// still works in screen positions, and they're skipped while the game is hidden.
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

    // Android 14+: the size of what's being captured (0 until Android says), and whether it's
    // on screen. When sharing one app, it's hidden while you're in another app.
    private var contentW = 0
    private var contentH = 0
    private var visible = true

    // When no tool has looked at the screen for a few seconds, the capture is paused: Android
    // stops copying the screen, which costs nothing, but the permission stays so there's no
    // need to ask again. It restarts by itself the next time a tool needs it.
    private val pauseWhenIdle = Runnable { pause() }

    val isActive: Boolean get() = projection != null && display != null

    // True while only the game is shared and you've left it (you're in another app or on the home screen)
    val contentHidden: Boolean get() = !visible

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
            handler.post {
                contentW = width
                contentH = height
                resizeTo(width, height)
            }
        }

        // Android 14+: the shared app was hidden or shown again
        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            handler.post {
                visible = isVisible
                if (!isVisible) drain()   // don't keep the last picture from before it was hidden
            }
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
        packed = null   // (nothing to look at while paused: let its memory go)
        drain()
    }

    // Throws away any old pictures so the next one is fresh
    private fun drain() {
        val r = reader ?: return
        while (true) {
            val old = try { r.acquireNextImage() } catch (e: Exception) { null } ?: break
            old.close()
        }
    }

    // Makes the capture match the screen again (after the phone turns), without starting over
    fun matchScreenSize() {
        touch()
        if (singleApp()) return   // Android tells us the game's size itself
        val (w, h, _) = screenSize()
        resizeTo(w, h)
    }

    // True when only one app is shared and its window is smaller than the screen. (A single app
    // that fills the screen is treated just like the whole screen.)
    private fun singleApp(): Boolean {
        if (contentW <= 0 || contentH <= 0) return false
        val (w, h, _) = screenSize()
        return !((contentW == w && contentH == h) || (contentW == h && contentH == w))
    }

    // Where the shared app's top-left corner is on the screen. Android doesn't say, so it's
    // worked out from the gap: a game moved aside for the camera cutout sits next to it,
    // otherwise it's centred.
    private fun contentOffset(cw: Int, ch: Int): Pair<Int, Int> {
        val (w, h, _) = screenSize()
        val dx = (w - cw).coerceAtLeast(0)
        val dy = (h - ch).coerceAtLeast(0)
        var left = 0; var right = 0; var top = 0; var bottom = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val cut = context.getSystemService(DisplayManager::class.java)
                    .getDisplay(android.view.Display.DEFAULT_DISPLAY)?.cutout
                if (cut != null) { left = cut.safeInsetLeft; right = cut.safeInsetRight; top = cut.safeInsetTop; bottom = cut.safeInsetBottom }
            } catch (e: Exception) {
                // no cutout information: centre it
            }
        }
        fun place(gap: Int, before: Int, after: Int) = when {
            gap == 0 -> 0
            before > 0 && kotlin.math.abs(gap - before) <= 2 -> gap          // gap is on the cutout side before it
            after > 0 && kotlin.math.abs(gap - after) <= 2 -> 0               // gap is after it
            before + after > 0 && kotlin.math.abs(gap - before - after) <= 2 -> before
            else -> gap / 2
        }
        return Pair(place(dx, left, right), place(dy, top, bottom))
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
        if (!visible) { drain(); return null }   // the shared game is hidden
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
            val iw = image.width
            val ih = image.height
            val src = plane.buffer
            val rowBytes = iw * pixelStride
            // Only the game shared: the picture is placed where the game sits on the screen, the rest left black
            var w = iw; var h = ih; var ox = 0; var oy = 0
            if (singleApp()) {
                val (sw, sh, _) = screenSize()
                if (iw <= sw && ih <= sh) {   // (bigger: the phone is mid-turn, so it's used as it is)
                    val (x, y) = contentOffset(iw, ih)
                    if (x + iw <= sw && y + ih <= sh) { w = sw; h = sh; ox = x; oy = y }
                }
            }
            val shot = createBitmap(w, h)
            if (w == iw && rowStride == rowBytes && src.remaining() >= rowBytes * ih) {
                shot.copyPixelsFromBuffer(src)   // the usual case: the rows are already back to back
                return shot
            }
            // Otherwise the rows are copied one by one into a buffer kept for this (so each look makes one picture,
            // not three): skipping the padding some phones put at the end of each row, and placing the game.
            // (Made once and reused: the picture is copied out of it, so nothing keeps hold of it.)
            val size = w * h * 4
            val out = packed?.takeIf { it.capacity() >= size }
                ?: java.nio.ByteBuffer.allocateDirect(size).also { packed = it }
            out.clear()
            if (w != iw || h != ih) {
                // black everywhere first (red, green, blue 0, fully opaque)
                val black = ByteArray(w * 4)
                for (i in 3 until black.size step 4) black[i] = 0xFF.toByte()
                for (y in 0 until h) out.put(black)
            }
            val row = src.duplicate()
            for (y in 0 until ih) {
                row.limit(y * rowStride + rowBytes).position(y * rowStride)
                out.position(((oy + y) * w + ox) * 4)
                out.put(row)
            }
            out.position(0).limit(size)
            shot.copyPixelsFromBuffer(out)
            return shot
        } catch (e: RuntimeException) {
            return null   // an odd picture from the phone: skip it rather than crash
        } finally {
            image.close()
        }
    }

    // Holds one picture's rows while they're put in order (see grab), let go when capture pauses
    private var packed: java.nio.ByteBuffer? = null

    // The newest screen image, read straight from the capture without making a picture.
    // Much quicker when only a few pixels are needed. Null if nothing changed.
    // When only the game is shared, (offsetX, offsetY) is where its picture sits on the screen:
    // width and height are the screen's, and anything outside the game reads as black.
    class Frame(
        private val buffer: java.nio.ByteBuffer,
        private val rowStride: Int,
        private val pixelStride: Int,
        override val width: Int,
        override val height: Int,
        private val offsetX: Int = 0,
        private val offsetY: Int = 0,
        private val imageW: Int = width,
        private val imageH: Int = height
    ) : LightBoxReader.PixelSource {
        override fun rgb(x: Int, y: Int): Int {
            val px = x - offsetX
            val py = y - offsetY
            if (px < 0 || py < 0 || px >= imageW || py >= imageH) return 0
            val i = py * rowStride + px * pixelStride
            return ((buffer.get(i).toInt() and 0xFF) shl 16) or
                ((buffer.get(i + 1).toInt() and 0xFF) shl 8) or
                (buffer.get(i + 2).toInt() and 0xFF)
        }
    }

    fun <T> sample(block: (Frame) -> T): T? {
        touch()
        if (!visible) { drain(); return null }
        val r = reader ?: return null
        val image = try {
            r.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return null
        image.use {
            val plane = image.planes[0]
            if (singleApp()) {
                val (w, h, _) = screenSize()
                if (image.width <= w && image.height <= h) {
                    val (ox, oy) = contentOffset(image.width, image.height)
                    return block(Frame(plane.buffer, plane.rowStride, plane.pixelStride, w, h, ox, oy, image.width, image.height))
                }
            }
            return block(Frame(plane.buffer, plane.rowStride, plane.pixelStride, image.width, image.height))
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
        packed = null
        projection = null
        width = 0
        height = 0
        contentW = 0
        contentH = 0
        visible = true
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
