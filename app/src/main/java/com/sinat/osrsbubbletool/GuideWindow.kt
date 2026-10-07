package com.sinat.osrsbubbletool

import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager

// The see-through windows the Puzzle Box and Light Box guides draw their outlines in (MoveGuideOverlay,
// LightBoxGuideOverlay): set up the same way, and lined up the same way once drawn.

// A see-through window over `area` of the screen that taps pass straight through, allowed beside the camera cutout
fun guideWindowParams(area: Rect) = WindowManager.LayoutParams(
    area.width(), area.height(),
    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
    PixelFormat.TRANSLUCENT
).apply {
    gravity = Gravity.TOP or Gravity.START
    x = area.left
    y = area.top
    alpha = 0.8f   // Android only lets taps pass through overlays that are a little see-through
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        fitInsetsTypes = 0
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }
}

// The overlay may land a little away from where it was asked to be (status bar, camera cutout). Called once it's
// drawn: measures where it really is, and moves it to line up with `area`.
fun lineUpGuide(view: View, params: WindowManager.LayoutParams, area: Rect, windowManager: WindowManager) {
    val loc = IntArray(2)
    view.getLocationOnScreen(loc)
    if (loc[0] != area.left || loc[1] != area.top) {
        params.x += area.left - loc[0]
        params.y += area.top - loc[1]
        windowManager.updateViewLayout(view, params)
    }
}
