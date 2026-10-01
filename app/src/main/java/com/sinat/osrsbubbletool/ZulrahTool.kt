/*
 * Zulrah rotation data and arena layout adapted from the "Zulrah Helper" RuneLite plugin
 * (github.com/while-loop/runelite-plugins).
 *
 * Copyright (c) 2020, Anthony Alves
 * Copyright (c) 2026, Ron Young <https://github.com/raiyni>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.sinat.osrsbubbletool

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Base64
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.edit
import androidx.core.graphics.toColorInt

// Zulrah Helper, drawn like the RuneLite plugin: a big picture of the current phase on top
// (Zulrah's coloured dot, where to stand, which prayer), and the possible next phases
// underneath. Tap the next phase that Zulrah comes up as.
class ZulrahTool(private val context: Context) {

    companion object {
        private val WINDOW_BG = "#1E1E1E".toColorInt()
        private val CARD_BG = "#2B2B2B".toColorInt()
        private val BUTTON_BG = "#3C3C3C".toColorInt()
        private val CURRENT_BORDER = "#FF9900".toColorInt()
        private val NEXT_BORDER = "#555555".toColorInt()
        private val ZULRAH_BORDER = Color.rgb(140, 140, 140)

        // Plugin picture size, in plugin pixels. Everything on a card is drawn in these units.
        private const val CARD = 105f
        private const val PAD = 2f
        private const val FLOOR_X = 13f   // where the floor picture sits on the card
        private const val FLOOR_Y = 22f

        // Pictures from the plugin (arena floor) and the game (prayer icons)
        private const val FLOOR_PNG =
            "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"
        private const val PRAY_MAGIC_PNG =
            "iVBORw0KGgoAAAANSUhEUgAAABsAAAAbBAMAAAB/+ulmAAAAElBMVEX////69wCpBu37AACELggAAAHVryFiAAAAAXRSTlMAQObYZgAAAI1JREFUeNpVkMEJBTEIBXNJAcYKFFKAmA6SAhbB/lv57H5ijLfHqIyWcsr9SakY5+jWngtm6tbSrF+wGl+Q4YIEPWJ1xtZTKwl7MCNsu7c6Mx7oTDAkoBHo3IvcGXRI8y1OOKZsBwPQNcPBUMfScDBca8boF/Xov1FC6R9D6V2lcm4zAICe/8ecTv1qpx/hzyOI5FedcgAAAABJRU5ErkJggg=="
        private const val PRAY_MISSILES_PNG =
            "iVBORw0KGgoAAAANSUhEUgAAABgAAAAYBAMAAAASWSDLAAAAFVBMVEX///93d3ckewmpBu2ELgg/LgsAAAFThWx1AAAAAXRSTlMAQObYZgAAAHNJREFUeNptj7ENhUAMQ1Mxwh8gEixgKSMwAAU1/wzefwQKSO4K3D29OErMnkzSYRkBBROD6qIxkhguRELzXR1mXyL7bXNG9i9foFy8i3GkIAaBPEe4cuYVdcq30CiIoUH9SxjPXwnjuZYwqv4wI9BBUlVuJ30pch5UtPcAAAAASUVORK5CYII="
    }

    // ---------------- The rotation data ----------------

    enum class Form(val color: Int) {
        GREEN(Color.rgb(25, 194, 4)),
        RED(Color.rgb(251, 0, 7)),
        BLUE(Color.rgb(0, 51, 255))
    }

    // Where Zulrah surfaces, in floor-picture pixels
    enum class Spot(val x: Float, val y: Float) {
        MIDDLE(40f, 30f),
        SOUTH(39f, -10f),
        WEST(82f, 25f),
        EAST(-5f, 35f)
    }

    // Places to stand, in floor-picture pixels
    enum class Stand(val x: Float, val y: Float) {
        START(19f, 52f),
        START_MAGMA(11f, 43f),
        PILLAR_1_SOUTH(20f, 23f),
        NORTH(41f, 10f),
        PILLAR_2_SOUTH(62f, 23f),
        PILLAR_2_EAST(63f, 16f)
    }

    enum class Pray { MISSILES, MAGIC }

    class Phase(
        val form: Form,
        val spot: Spot,
        val prayers: List<Pray>,
        val attacks: Int,
        val venom: Int,
        val snakelings: Int,
        val stands: List<Stand>
    ) {
        var title: String? = null
        fun named(t: String): Phase { title = t; return this }
        fun sameLook(other: Phase) = form == other.form && spot == other.spot
    }

    class Rotation(val phases: List<Phase>)

    private fun p(form: Form, spot: Spot, prayers: List<Pray>, attacks: Int, venom: Int, snakes: Int, vararg stands: Stand) =
        Phase(form, spot, prayers, attacks, venom, snakes, stands.toList())

    private val none = emptyList<Pray>()
    private val missiles = listOf(Pray.MISSILES)
    private val magic = listOf(Pray.MAGIC)

    private val first = p(Form.GREEN, Spot.MIDDLE, missiles, 5, 4, 0, Stand.START).named("Start")
    private val magmaStart = listOf(
        first,
        p(Form.RED, Spot.MIDDLE, none, 2, 0, 0, Stand.START, Stand.START_MAGMA).named("Magma"),
        p(Form.BLUE, Spot.MIDDLE, magic, 4, 0, 0, Stand.START)
    )

    private val rotations = listOf(
        Rotation(magmaStart + listOf(
            p(Form.GREEN, Spot.SOUTH, missiles, 5, 2, 2, Stand.PILLAR_2_SOUTH).named("Magma A"),
            p(Form.RED, Spot.MIDDLE, none, 2, 0, 0, Stand.PILLAR_2_SOUTH),
            p(Form.BLUE, Spot.WEST, magic, 5, 0, 0, Stand.NORTH),
            p(Form.GREEN, Spot.SOUTH, none, 0, 3, 2, Stand.PILLAR_1_SOUTH),
            p(Form.BLUE, Spot.SOUTH, magic, 5, 2, 2, Stand.PILLAR_1_SOUTH),
            p(Form.GREEN, Spot.WEST, listOf(Pray.MISSILES, Pray.MAGIC), 10, 4, 0, Stand.PILLAR_2_SOUTH),
            p(Form.RED, Spot.MIDDLE, none, 2, 0, 0, Stand.START, Stand.START_MAGMA)
        )),
        Rotation(magmaStart + listOf(
            p(Form.GREEN, Spot.WEST, none, 0, 3, 2, Stand.PILLAR_2_SOUTH).named("Magma B"),
            p(Form.BLUE, Spot.SOUTH, magic, 5, 2, 2, Stand.PILLAR_2_SOUTH),
            p(Form.RED, Spot.MIDDLE, none, 2, 0, 0, Stand.PILLAR_2_SOUTH),
            p(Form.GREEN, Spot.EAST, missiles, 5, 0, 0, Stand.NORTH),
            p(Form.BLUE, Spot.SOUTH, magic, 5, 2, 2, Stand.PILLAR_2_SOUTH),
            p(Form.GREEN, Spot.WEST, listOf(Pray.MISSILES, Pray.MAGIC), 10, 4, 0, Stand.PILLAR_2_SOUTH),
            p(Form.RED, Spot.MIDDLE, none, 2, 0, 0, Stand.START, Stand.START_MAGMA)
        )),
        Rotation(listOf(
            first,
            p(Form.GREEN, Spot.EAST, missiles, 5, 0, 2, Stand.START).named("Serp"),
            p(Form.RED, Spot.MIDDLE, none, 2, 3, 2, Stand.PILLAR_1_SOUTH),
            p(Form.BLUE, Spot.WEST, magic, 5, 0, 0, Stand.NORTH),
            p(Form.GREEN, Spot.SOUTH, missiles, 5, 0, 0, Stand.NORTH),
            p(Form.BLUE, Spot.EAST, magic, 5, 0, 0, Stand.NORTH),
            p(Form.GREEN, Spot.MIDDLE, none, 0, 3, 2, Stand.PILLAR_2_SOUTH),
            p(Form.GREEN, Spot.WEST, missiles, 5, 0, 0, Stand.PILLAR_2_SOUTH),
            p(Form.BLUE, Spot.MIDDLE, magic, 5, 2, 2, Stand.PILLAR_1_SOUTH),
            p(Form.GREEN, Spot.EAST, listOf(Pray.MAGIC, Pray.MISSILES), 10, 0, 0, Stand.PILLAR_1_SOUTH),
            p(Form.BLUE, Spot.MIDDLE, none, 0, 0, 2, Stand.START)
        )),
        Rotation(listOf(
            first,
            p(Form.BLUE, Spot.EAST, magic, 6, 0, 2, Stand.START).named("Tanz"),
            p(Form.GREEN, Spot.SOUTH, missiles, 4, 2, 0, Stand.PILLAR_2_SOUTH),
            p(Form.BLUE, Spot.WEST, magic, 4, 0, 2, Stand.PILLAR_2_SOUTH),
            p(Form.RED, Spot.MIDDLE, none, 2, 2, 0, Stand.PILLAR_1_SOUTH),
            p(Form.GREEN, Spot.EAST, missiles, 4, 0, 0, Stand.PILLAR_1_SOUTH),
            p(Form.GREEN, Spot.SOUTH, none, 0, 3, 2, Stand.PILLAR_1_SOUTH),
            p(Form.BLUE, Spot.WEST, magic, 5, 4, 0, Stand.PILLAR_2_EAST),
            p(Form.GREEN, Spot.MIDDLE, missiles, 5, 0, 0, Stand.PILLAR_1_SOUTH),
            p(Form.BLUE, Spot.MIDDLE, magic, 4, 3, 0, Stand.PILLAR_1_SOUTH),
            p(Form.GREEN, Spot.EAST, listOf(Pray.MAGIC, Pray.MISSILES), 8, 0, 0, Stand.PILLAR_1_SOUTH),
            p(Form.BLUE, Spot.MIDDLE, none, 0, 0, 2, Stand.START)
        ))
    )

    // ---------------- Where we are in the fight ----------------

    private class State(val candidates: List<Int>, val index: Int)

    // One possible next phase: what it looks like, and which rotations it would mean
    private class Option(val phase: Phase, val rotations: List<Int>, val restart: Boolean)

    private var state = State(rotations.indices.toList(), 0)
    private val history = ArrayDeque<State>()

    private val prefs = context.getSharedPreferences("zulrah", Context.MODE_PRIVATE)
    private var turned = prefs.getBoolean("north_up", false)

    private fun phasesOf(r: Int) = rotations[r].phases
    private fun current(): Phase = phasesOf(state.candidates.first())[state.index]

    private fun nextOptions(): List<Option> {
        val groups = mutableListOf<Pair<Phase, MutableList<Int>>>()
        for (r in state.candidates) {
            val phases = phasesOf(r)
            if (state.index + 1 >= phases.size) {
                // End of the rotation: Zulrah starts over, and it could be any rotation again
                return listOf(Option(first, rotations.indices.toList(), restart = true))
            }
            val next = phases[state.index + 1]
            val group = groups.firstOrNull { it.first.sameLook(next) }
            if (group != null) group.second.add(r) else groups.add(next to mutableListOf(r))
        }
        return groups.map { Option(it.first, it.second, restart = false) }
    }

    private fun choose(option: Option) {
        history.addLast(state)
        state = if (option.restart) State(rotations.indices.toList(), 0)
                else State(option.rotations, state.index + 1)
        refresh()
    }

    fun goBack() {
        if (history.isEmpty()) return
        state = history.removeLast()
        refresh()
    }

    private fun reset() {
        history.clear()
        state = State(rotations.indices.toList(), 0)
        refresh()
    }

    // ---------------- Screen ----------------

    private lateinit var titleText: TextView
    private lateinit var board: Board

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    private fun decode(b64: String): Bitmap {
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size).apply {
            density = Bitmap.DENSITY_NONE  // draw at exactly the size we ask for
        }
    }

    private val floorImg by lazy { decode(FLOOR_PNG) }
    private val magicImg by lazy { decode(PRAY_MAGIC_PNG) }
    private val missilesImg by lazy { decode(PRAY_MISSILES_PNG) }

    fun buildView(): View {
        titleText = TextView(context).apply {
            textSize = 11f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        board = Board()

        fun smallButton(label: String, onClick: () -> Unit) = TextView(context).apply {
            text = label
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(BUTTON_BG); cornerRadius = dp(5).toFloat() }
            setOnClickListener { onClick() }
        }

        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            fun add(v: View) = addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                leftMargin = dp(3); rightMargin = dp(3)
            })
            add(smallButton("Undo") { goBack() })
            add(smallButton("Turn") {
                turned = !turned
                prefs.edit { putBoolean("north_up", turned) }
                refresh()
            })
            add(smallButton("Reset") { reset() })
        }

        refresh()
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(WINDOW_BG)
            setPadding(dp(4), dp(3), dp(4), dp(4))
            addView(titleText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(board, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30)))
        }
    }

    private fun refresh() {
        if (!::titleText.isInitialized) return
        val phases = phasesOf(state.candidates.first())
        val title = (state.index downTo 0).firstNotNullOfOrNull { phases[it].title } ?: ""
        titleText.text = "$title #${state.index + 1}"
        board.show(current(), nextOptions())
    }

    // The big current-phase picture plus the smaller next-phase pictures, all in one view
    @SuppressLint("ViewConstructor")
    private inner class Board : View(this@ZulrahTool.context) {
        private var phase: Phase? = null
        private var options: List<Option> = emptyList()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val pixelPaint = Paint().apply { isFilterBitmap = false }  // keeps pixel art sharp

        fun show(phase: Phase, options: List<Option>) {
            this.phase = phase
            this.options = options
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val cur = phase ?: return
            val w = width.toFloat()
            val h = height.toFloat()
            val gap = dp(6).toFloat()
            val n = options.size.coerceAtLeast(1)

            // Sizes: next pictures are ~60% of the big one, everything must fit without scrolling
            var big = minOf(w, (h - gap) / 1.6f)
            val small = minOf(big * 0.6f, (w - gap * (n - 1)) / n)
            big = minOf(w, h - gap - small)

            val totalH = big + gap + small
            val top = (h - totalH) / 2f
            drawCard(canvas, cur, (w - big) / 2f, top, big, restart = false, border = CURRENT_BORDER)

            val rowW = small * n + gap * (n - 1)
            var x = (w - rowW) / 2f
            val y = top + big + gap
            // remembered so a tap can be matched to a picture
            optionsLeft = x; optionsTop = y; optionSize = small; optionStep = small + gap
            for (o in options) {
                drawCard(canvas, o.phase, x, y, small, o.restart, NEXT_BORDER)
                x += small + gap
            }
        }

        // Where the row of next-phase pictures is (set when it's drawn)
        private var optionsLeft = 0f
        private var optionsTop = 0f
        private var optionSize = 0f
        private var optionStep = 0f

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_UP && optionStep > 0f) {
                // which picture the tap is on (not in the gaps between them)
                val along = event.x - optionsLeft
                val i = (along / optionStep).toInt()
                val onPicture = along >= 0f && along - i * optionStep <= optionSize &&
                    event.y >= optionsTop && event.y <= optionsTop + optionSize
                if (onPicture && i < options.size) choose(options[i])
            }
            return true
        }

        // Draws one phase picture, the same way the plugin does, at the given place and size
        private fun drawCard(canvas: Canvas, ph: Phase, left: Float, top: Float, size: Float, restart: Boolean, border: Int) {
            canvas.save()
            canvas.translate(left, top)
            canvas.scale(size / CARD, size / CARD)

            paint.style = Paint.Style.FILL
            paint.color = CARD_BG
            canvas.drawRect(0f, 0f, CARD, CARD, paint)

            // Arena, stand spots and Zulrah (these turn with the map; the icons don't)
            canvas.save()
            if (turned) canvas.rotate(180f, CARD / 2f, CARD / 2f)
            canvas.drawBitmap(floorImg, FLOOR_X, FLOOR_Y, pixelPaint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.strokeCap = Paint.Cap.SQUARE
            paint.color = Color.WHITE
            for (s in ph.stands) {
                val cx = FLOOR_X + s.x
                val cy = FLOOR_Y + s.y
                canvas.drawLine(cx - 3.5f, cy - 3f, cx + 2.5f, cy + 3f, paint)
                canvas.drawLine(cx - 3.5f, cy + 3f, cx + 2.5f, cy - 3f, paint)
            }

            val zx = FLOOR_X + ph.spot.x + 0.5f
            val zy = FLOOR_Y + ph.spot.y + 0.5f
            paint.style = Paint.Style.FILL
            paint.color = ph.form.color
            canvas.drawCircle(zx, zy, 6.5f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f
            paint.color = ZULRAH_BORDER
            canvas.drawCircle(zx, zy, 6.5f, paint)
            canvas.restore()

            // Prayer icons in the top corners (second one is for the Jad phase swap)
            ph.prayers.forEachIndexed { i, pray ->
                val img = if (pray == Pray.MAGIC) magicImg else missilesImg
                val x = if (i == 1) CARD - img.width - PAD else PAD
                canvas.drawBitmap(img, x, PAD, pixelPaint)
            }

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f
            paint.color = border
            canvas.drawRect(0.75f, 0.75f, CARD - 0.75f, CARD - 0.75f, paint)
            canvas.restore()
        }

    }
}
