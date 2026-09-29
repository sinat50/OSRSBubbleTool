package com.sinat.osrsbubbletool

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.DecimalFormat

// A basic calculator. It also understands OSRS shorthand: 1.5k = 1,500, 2m = 2,000,000, 1b = 1,000,000,000.
// What you typed is kept while the bubble is running.
class CalculatorTool(private val context: Context) {

    companion object {
        private val PARCHMENT = Color.parseColor("#F2E3C0")
        private val DARK_BROWN = Color.parseColor("#3E2C12")
        private val KEY = Color.parseColor("#8B6B3E")
        private val OPERATOR = Color.parseColor("#5A4220")
        private val EQUALS = Color.parseColor("#3E7A2E")
        private val CLEAR = Color.parseColor("#B03A2E")
        private val FADED = Color.parseColor("#8C7B5E")
    }

    private var expression = ""      // what's been typed, e.g. "1.5m*3"
    private var lastSum = ""         // the previous calculation, shown small above
    private var justAnswered = false // after =, a digit starts fresh and an operator carries on from the answer

    private lateinit var sumView: TextView
    private lateinit var expressionView: TextView
    private lateinit var resultView: TextView

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    // Scrolls if a phone's window is too short to fit it all
    fun buildView(): View = android.widget.ScrollView(context).apply {
        setBackgroundColor(PARCHMENT)
        addView(keypad())
    }

    private fun keypad(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(PARCHMENT)
        setPadding(dp(4), dp(4), dp(4), dp(4))

        // The display: the sum (with the previous one small above it) and the answer
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(1), dp(6), dp(2))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FFF8E6")); setStroke(dp(1), KEY); cornerRadius = dp(6).toFloat()
            }
            sumView = text(9f, FADED)
            expressionView = text(13f, DARK_BROWN)
            resultView = text(17f, DARK_BROWN).apply { setTypeface(typeface, Typeface.BOLD) }
            addView(sumView); addView(expressionView); addView(resultView)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // The keys
        val rows = listOf(
            listOf("C", "⌫", "(", ")"),
            listOf("7", "8", "9", "÷"),
            listOf("4", "5", "6", "×"),
            listOf("1", "2", "3", "−"),
            listOf("0", ".", "=", "+"),
            listOf("k", "m", "b", "00")
        )
        for (row in rows) {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                for (k in row) addView(key(k), LinearLayout.LayoutParams(0, dp(34), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        refresh()
    }

    private fun text(size: Float, color: Int) = TextView(context).apply {
        textSize = size
        setTextColor(color)
        gravity = Gravity.END
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.START   // a long sum shows its end
    }

    private fun key(k: String) = TextView(context).apply {
        text = k
        textSize = 15f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        background = GradientDrawable().apply {
            setColor(when (k) { "=" -> EQUALS; "C" -> CLEAR; "÷", "×", "−", "+", "(", ")", "⌫" -> OPERATOR; else -> KEY })
            cornerRadius = dp(6).toFloat()
        }
        setOnClickListener { press(k) }
    }

    // ---------------- Keys ----------------

    private fun press(k: String) {
        when (k) {
            "C" -> { expression = ""; justAnswered = false }
            "⌫" -> { expression = expression.dropLast(1); justAnswered = false }
            "=" -> {
                val value = evaluate(expression) ?: return
                lastSum = display(expression) + " ="
                expression = plain(value)
                justAnswered = true
            }
            else -> {
                val op = k in listOf("÷", "×", "−", "+")
                // after =, a number starts a new sum; an operator carries on from the answer
                if (justAnswered && !op && k !in listOf("k", "m", "b")) expression = ""
                justAnswered = false
                val c = when (k) { "÷" -> "/"; "×" -> "*"; "−" -> "-"; else -> k }
                // don't allow two operators in a row (swap the old one), except a minus for a negative number
                if (op && expression.isNotEmpty() && expression.last() in "+-*/" && c != "-") expression = expression.dropLast(1)
                if (op && expression.isEmpty() && c != "-") expression = "0"
                expression += c
            }
        }
        refresh()
    }

    private fun refresh() {
        sumView.text = lastSum
        expressionView.text = if (expression.isEmpty()) "0" else display(expression)
        val value = evaluate(expression)
        resultView.text = when {
            expression.isEmpty() -> ""
            value == null -> if (justAnswered) "" else "…"
            else -> "= " + pretty(value)
        }
    }

    // What you typed, with proper maths signs
    private fun display(e: String) = e.replace("*", " × ").replace("/", " ÷ ").replace("+", " + ")
        .replace(Regex("(?<=[0-9kmb)])-"), " − ")

    // ---------------- Working it out ----------------

    // Works out the sum, or null if it isn't finished (e.g. ends in "+") or divides by zero
    fun evaluate(e: String): BigDecimal? = try {
        val parser = Parser(e)
        val v = parser.sum()
        if (parser.pos != e.length) null else v
    } catch (ex: Exception) { null }

    private class Parser(val s: String) {
        var pos = 0
        private val mc = MathContext(34)

        fun sum(): BigDecimal {
            var v = product()
            while (pos < s.length && s[pos] in "+-") {
                val op = s[pos++]
                val r = product()
                v = if (op == '+') v.add(r, mc) else v.subtract(r, mc)
            }
            return v
        }

        private fun product(): BigDecimal {
            var v = unary()
            while (pos < s.length && s[pos] in "*/") {
                val op = s[pos++]
                val r = unary()
                v = if (op == '*') v.multiply(r, mc) else {
                    if (r.signum() == 0) throw ArithmeticException()
                    v.divide(r, mc)
                }
            }
            return v
        }

        private fun unary(): BigDecimal {
            if (pos < s.length && s[pos] == '-') { pos++; return unary().negate() }
            return atom()
        }

        private fun atom(): BigDecimal {
            if (pos < s.length && s[pos] == '(') {
                pos++
                val v = sum()
                if (pos < s.length && s[pos] == ')') pos++   // a missing ")" at the end is fine
                return suffix(v)
            }
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
            if (pos == start) throw IllegalArgumentException()
            return suffix(BigDecimal(s.substring(start, pos)))
        }

        // 1.5k, 2m, 1b
        private fun suffix(v: BigDecimal): BigDecimal {
            var r = v
            while (pos < s.length && s[pos] in "kmb") {
                r = r.multiply(when (s[pos]) { 'k' -> BigDecimal(1_000); 'm' -> BigDecimal(1_000_000); else -> BigDecimal(1_000_000_000) })
                pos++
            }
            return r
        }
    }

    // The answer as it should be typed back in (no commas)
    private fun plain(v: BigDecimal): String {
        val r = v.round(MathContext(15)).stripTrailingZeros()
        return if (r.scale() <= 0) r.toBigInteger().toString() else r.toPlainString()
    }

    // The answer for reading: 1,234,567.5
    fun pretty(v: BigDecimal): String =
        DecimalFormat("#,##0.######").format(v.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros())
}
