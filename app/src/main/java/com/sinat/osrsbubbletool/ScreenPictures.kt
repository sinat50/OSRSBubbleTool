package com.sinat.osrsbubbletool

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore

// Working with pictures of the screen, shared by the tools that read it (Puzzle Box, Light Box, DPS).

// One patch of touching marked squares: how many, their total position (for the middle) and the box around them
class ScreenPatch {
    var count = 0; var sumX = 0L; var sumY = 0L
    var minX = 0; var maxX = 0; var minY = 0; var maxY = 0
}

// Finds each patch of touching squares marked in `mask` (w by h, cleared as it goes) and hands it to `onPatch`,
// which says whether to carry on. Shared by the Light Box and the Puzzle Box. (onPatch runs once per patch,
// not per pixel, so it's fine as a function type here.)
fun forEachPatch(mask: BooleanArray, w: Int, h: Int, onPatch: (ScreenPatch) -> Boolean) {
    val stack = IntArray(w * h)
    val patch = ScreenPatch()
    for (start in mask.indices) {
        if (!mask[start]) continue
        var top = 0
        stack[top++] = start
        mask[start] = false
        var count = 0; var sumX = 0L; var sumY = 0L
        var minX = w; var maxX = 0; var minY = h; var maxY = 0
        while (top > 0) {
            val i = stack[--top]
            val x = i % w
            val y = i / w
            count++; sumX += x; sumY += y
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
            if (x > 0 && mask[i - 1]) { mask[i - 1] = false; stack[top++] = i - 1 }
            if (x < w - 1 && mask[i + 1]) { mask[i + 1] = false; stack[top++] = i + 1 }
            if (y > 0 && mask[i - w]) { mask[i - w] = false; stack[top++] = i - w }
            if (y < h - 1 && mask[i + w]) { mask[i + w] = false; stack[top++] = i + w }
        }
        patch.count = count; patch.sumX = sumX; patch.sumY = sumY
        patch.minX = minX; patch.maxX = maxX; patch.minY = minY; patch.maxY = maxY
        if (!onPatch(patch)) return
    }
}

// The part of the screenshot inside `area`, or null if there's no area, or it's off the screen or smaller than
// minW × minH once clipped to the screen
fun cutArea(shot: Bitmap, area: Rect?, minW: Int, minH: Int): Bitmap? {
    val r = area?.let { Rect(it) } ?: return null
    if (!r.intersect(0, 0, shot.width, shot.height) || r.width() < minW || r.height() < minH) return null
    return Bitmap.createBitmap(shot, r.left, r.top, r.width(), r.height())
}

// Saves a picture as a PNG in Pictures/OSRS Bubble Tool, named `name` and the time. Squeezing a big picture into a
// PNG takes a moment, so it's done in the background; the picture is hidden from the gallery until it's fully
// written, and nothing is left behind if it fails. `done` gets what to tell you, on the main thread. `recycle`: let
// go of the picture afterwards (when nothing else shows it).
fun savePictureToPhone(context: Context, picture: Bitmap, name: String, recycle: Boolean, done: (String) -> Unit) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        done("Saving pictures needs Android 10 or newer.")
        if (recycle) picture.recycle()
        return
    }
    val resolver = context.applicationContext.contentResolver
    val main = Handler(Looper.getMainLooper())
    Thread {
        var uri: android.net.Uri? = null
        val message = try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$name-${System.currentTimeMillis()}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/OSRS Bubble Tool")
                put(MediaStore.Images.Media.IS_PENDING, 1)   // hidden from the gallery until it's fully written
            }
            uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("couldn't create the file")
            val stream = resolver.openOutputStream(uri) ?: throw IllegalStateException("couldn't open the file")
            stream.use {
                if (!picture.compress(Bitmap.CompressFormat.PNG, 100, it)) throw IllegalStateException("couldn't write the picture")
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            "Saved to Pictures/OSRS Bubble Tool."
        } catch (e: Exception) {
            // don't leave an empty picture behind in the gallery
            uri?.let { try { resolver.delete(it, null, null) } catch (e2: Exception) { } }
            "Couldn't save the picture: ${e.message}"
        } finally {
            if (recycle) picture.recycle()
        }
        main.post { done(message) }
    }.start()
}
