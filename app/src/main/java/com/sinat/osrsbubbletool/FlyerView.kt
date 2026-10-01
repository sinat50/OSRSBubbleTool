package com.sinat.osrsbubbletool

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.toColorInt
import androidx.core.graphics.withRotation
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

// "Wing It": tap to flap a little bird through the gaps between pillars. The app's own game (nothing
// from Old School RuneScape). Everything is sized from the window, so it plays the same in a small window
// as a big one. It only draws while you're playing, and pauses when the window is hidden.
@SuppressLint("ViewConstructor")
class FlyerView(context: Context, private val best: () -> Int, private val onScore: (score: Int, over: Boolean) -> Unit) : View(context) {

    private enum class State { READY, PLAYING, PAUSED, OVER }
    private var state = State.READY

    // The world, in pixels (worked out from the window size)
    private var w = 0f
    private var h = 0f
    private var ground = 0f          // top of the ground strip
    private var r = 0f               // the bird's size

    private var birdX = 0f
    private var birdY = 0f
    private var speedY = 0f          // up is negative
    private var flapAt = 0L          // for the wing animation

    private class Pillar(var x: Float, val gapTop: Float, val gapBottom: Float, var passed: Boolean = false)
    private val pillars = ArrayList<Pillar>()
    private var pillarW = 0f
    private var groundShift = 0f

    var score = 0
        private set
    private var lastFrame = 0L
    private var overAt = 0L

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val path = Path()
    private var sky: LinearGradient? = null

    companion object {
        private val SKY_TOP = "#F6E7C4".toColorInt()
        private val SKY_BOTTOM = "#E9D3A0".toColorInt()
        private val PILLAR = "#6B4B24".toColorInt()
        private val PILLAR_EDGE = "#3E2C12".toColorInt()
        private val PILLAR_CAP = "#C9A24A".toColorInt()
        private val GROUND = "#8B6B3E".toColorInt()
        private val GROUND_DARK = "#5E4424".toColorInt()
        private val BIRD = "#D8573A".toColorInt()
        private val BIRD_BELLY = "#F2C9A0".toColorInt()
        private val WING = "#A8402A".toColorInt()
        private val BEAK = "#E8A62C".toColorInt()
        private val MESSAGE_BG = "#E6FFF8E6".toColorInt()
        private val INK = "#3E2C12".toColorInt()
    }

    // How the game feels, as parts of the window's height (and width for the sideways parts)
    private val gravity get() = h * 2.9f          // per second, per second
    private val flap get() = -h * 0.82f            // speed straight after a tap
    private val runSpeed get() = w * 0.52f + h * 0.08f
    private val spacing get() = max(w * 0.78f, r * 9f)   // between pillars
    private fun gapSize() = h * max(0.25f, 0.33f - score * 0.003f)   // the gap slowly closes as your score grows

    override fun onSizeChanged(nw: Int, nh: Int, ow: Int, oh: Int) {
        super.onSizeChanged(nw, nh, ow, oh)
        w = nw.toFloat(); h = nh.toFloat()
        ground = h * 0.9f
        r = min(h * 0.035f, w * 0.06f).coerceAtLeast(6f)
        pillarW = max(r * 2.8f, w * 0.14f)
        sky = LinearGradient(0f, 0f, 0f, ground, SKY_TOP, SKY_BOTTOM, Shader.TileMode.CLAMP)
        // the window was resized: start over, since the pillars were placed for the old size
        if (state != State.READY || ow == 0) reset()
    }

    private fun reset() {
        state = State.READY
        score = 0
        birdX = w * 0.28f
        birdY = ground * 0.45f
        speedY = 0f
        pillars.clear()
        invalidate()
    }

    private fun start() {
        state = State.PLAYING
        score = 0
        pillars.clear()
        var x = w + pillarW
        repeat(3) { pillars.add(newPillar(x)); x += spacing }
        speedY = flap
        flapAt = System.nanoTime()
        lastFrame = System.nanoTime()
        onScore(0, false)
        postInvalidateOnAnimation()
    }

    private fun newPillar(x: Float): Pillar {
        val gap = gapSize()
        val margin = h * 0.08f
        val top = margin + Random.nextFloat() * max(1f, ground - margin * 2 - gap)
        return Pillar(x, top, top + gap)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked != MotionEvent.ACTION_DOWN) return true
        when (state) {
            State.READY -> start()
            State.PAUSED -> { state = State.PLAYING; lastFrame = System.nanoTime(); speedY = flap; flapAt = System.nanoTime(); postInvalidateOnAnimation() }
            State.PLAYING -> { speedY = flap; flapAt = System.nanoTime() }
            State.OVER -> if (System.nanoTime() - overAt > 600_000_000L) reset()   // a short wait, so a late tap doesn't skip the score
        }
        return true
    }

    // Hidden (window closed or another tool opened): pause instead of carrying on unseen
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (state == State.PLAYING) state = State.PAUSED
    }

    fun pauseIfPlaying() {
        if (state == State.PLAYING) { state = State.PAUSED; invalidate() }
    }

    private fun step(dt: Float) {
        speedY += gravity * dt
        birdY += speedY * dt
        if (birdY < r) { birdY = r; speedY = 0f }   // bump the top gently
        val dx = runSpeed * dt
        groundShift = (groundShift + dx) % (r * 4)
        for (p in pillars) p.x -= dx
        if (pillars.isNotEmpty() && pillars.first().x + pillarW < 0) {
            pillars.removeAt(0)
            pillars.add(newPillar(pillars.last().x + spacing))
        }
        for (p in pillars) if (!p.passed && p.x + pillarW < birdX - r) {
            p.passed = true
            score++
            onScore(score, false)
        }
        // hit the ground or a pillar?
        val hitGround = birdY + r >= ground
        val hitPillar = pillars.any { p ->
            birdX + r * 0.8f > p.x && birdX - r * 0.8f < p.x + pillarW && (birdY - r * 0.8f < p.gapTop || birdY + r * 0.8f > p.gapBottom)
        }
        if (hitGround || hitPillar) {
            if (hitGround) birdY = ground - r
            state = State.OVER
            overAt = System.nanoTime()
            onScore(score, true)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (w <= 0f) return
        if (state == State.PLAYING) {
            val now = System.nanoTime()
            val dt = ((now - lastFrame) / 1e9f).coerceIn(0f, 1 / 30f)   // a slow frame doesn't teleport the bird
            lastFrame = now
            step(dt)
        }

        // sky
        paint.shader = sky
        canvas.drawRect(0f, 0f, w, ground, paint)
        paint.shader = null

        // pillars, with gold caps at the gap
        for (p in pillars) {
            val cap = r * 0.9f
            paint.color = PILLAR
            canvas.drawRect(p.x, 0f, p.x + pillarW, p.gapTop, paint)
            canvas.drawRect(p.x, p.gapBottom, p.x + pillarW, ground, paint)
            paint.color = PILLAR_EDGE
            canvas.drawRect(p.x, 0f, p.x + pillarW * 0.12f, p.gapTop, paint)
            canvas.drawRect(p.x, p.gapBottom, p.x + pillarW * 0.12f, ground, paint)
            paint.color = PILLAR_CAP
            canvas.drawRect(p.x - pillarW * 0.08f, p.gapTop - cap, p.x + pillarW * 1.08f, p.gapTop, paint)
            canvas.drawRect(p.x - pillarW * 0.08f, p.gapBottom, p.x + pillarW * 1.08f, p.gapBottom + cap, paint)
        }

        // ground, with stripes that scroll
        paint.color = GROUND
        canvas.drawRect(0f, ground, w, h, paint)
        paint.color = GROUND_DARK
        canvas.drawRect(0f, ground, w, ground + r * 0.35f, paint)
        var sx = -groundShift
        while (sx < w) { canvas.drawRect(sx, ground + r * 0.9f, sx + r * 1.6f, ground + r * 1.3f, paint); sx += r * 4 }

        drawBird(canvas)

        // score, big at the top
        text.color = INK
        text.textSize = h * 0.09f
        if (state != State.READY) canvas.drawText(score.toString(), w / 2, h * 0.13f, text)

        when (state) {
            State.READY -> message(canvas, "Tap to fly", "Tap to flap through the gaps")
            State.PAUSED -> message(canvas, "Paused", "Tap to carry on")
            State.OVER -> message(canvas, "Game over", "Score $score · Best ${max(best(), score)}\nTap to play again")
            State.PLAYING -> {}
        }

        if (state == State.PLAYING) postInvalidateOnAnimation()   // next frame, only while playing
    }

    private fun drawBird(canvas: Canvas) {
        // tilt: nose up just after a flap, down when falling
        val tilt = (speedY / (h * 1.2f) * 55f).coerceIn(-25f, 70f)
        canvas.withRotation(if (state == State.READY) 0f else tilt, birdX, birdY) {
            paint.color = BIRD
            canvas.drawOval(birdX - r * 1.15f, birdY - r, birdX + r * 1.15f, birdY + r, paint)
            paint.color = BIRD_BELLY
            canvas.drawOval(birdX - r * 0.6f, birdY, birdX + r * 0.9f, birdY + r * 0.85f, paint)
            // wing: up for a moment after each flap
            val up = state == State.PLAYING && System.nanoTime() - flapAt < 150_000_000L
            paint.color = WING
            path.reset()
            path.moveTo(birdX - r * 0.9f, birdY)
            path.lineTo(birdX + r * 0.2f, birdY)
            path.lineTo(birdX - r * 0.5f, if (up) birdY - r * 1.2f else birdY + r * 0.9f)
            path.close()
            canvas.drawPath(path, paint)
            // eye and beak
            paint.color = Color.WHITE
            canvas.drawCircle(birdX + r * 0.45f, birdY - r * 0.35f, r * 0.32f, paint)
            paint.color = INK
            canvas.drawCircle(birdX + r * 0.55f, birdY - r * 0.35f, r * 0.15f, paint)
            paint.color = BEAK
            path.reset()
            path.moveTo(birdX + r * 1.0f, birdY - r * 0.15f)
            path.lineTo(birdX + r * 1.65f, birdY + r * 0.05f)
            path.lineTo(birdX + r * 1.0f, birdY + r * 0.3f)
            path.close()
            canvas.drawPath(path, paint)
        }
    }

    private fun message(canvas: Canvas, title: String, sub: String) {
        val lines = sub.split('\n')
        val big = h * 0.075f; val small = h * 0.042f
        val boxH = big + small * lines.size * 1.35f + h * 0.05f
        val top = ground * 0.42f - boxH / 2
        paint.color = MESSAGE_BG
        canvas.drawRoundRect(w * 0.08f, top, w * 0.92f, top + boxH, r, r, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = max(2f, r * 0.15f); paint.color = PILLAR
        canvas.drawRoundRect(w * 0.08f, top, w * 0.92f, top + boxH, r, r, paint)
        paint.style = Paint.Style.FILL
        text.color = INK
        text.textSize = min(big, w * 0.1f)
        canvas.drawText(title, w / 2, top + h * 0.025f + big * 0.85f, text)
        text.textSize = min(small, w * 0.055f)
        lines.forEachIndexed { i, l -> canvas.drawText(l, w / 2, top + h * 0.025f + big + small * 1.3f * (i + 1), text) }
    }
}
