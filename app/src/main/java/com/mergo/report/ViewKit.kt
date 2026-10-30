package com.mergo.report

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** UI 가이드(mergo_UI.pdf)에서 뽑은 색. 글씨는 스펙대로 최소 18sp로 맞춘다. */
object Palette {
    val BACKGROUND = Color.parseColor("#FAFBF8")
    val PRIMARY = Color.parseColor("#2B684F")
    val ACCENT = Color.parseColor("#37745F")
    val INK = Color.parseColor("#1D302A")
    val BODY = Color.parseColor("#53635D")
    val MUTED = Color.parseColor("#7A8580")
    val LINE = Color.parseColor("#D5DDD8")
    val TINT = Color.parseColor("#E7EBE4")
}

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

fun Context.label(
    text: String,
    sp: Float = 18f,
    color: Int = Palette.BODY,
    bold: Boolean = false,
): TextView = TextView(this).apply {
    this.text = text
    textSize = sp
    setTextColor(color)
    if (bold) typeface = Typeface.DEFAULT_BOLD
}

fun Context.column(padding: Int = 0): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
}

fun Context.card(): LinearLayout = column(padding = 18).apply {
    background = GradientDrawable().apply {
        setColor(Color.WHITE)
        cornerRadius = dp(16).toFloat()
        setStroke(dp(1), Palette.LINE)
    }
    layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(14) }
}

fun Context.bigButton(text: String, filled: Boolean = true, onClick: () -> Unit): Button =
    Button(this).apply {
        this.text = text
        textSize = 18f
        isAllCaps = false
        minHeight = dp(64)
        stateListAnimator = null
        setTextColor(if (filled) Color.WHITE else Palette.INK)
        background = GradientDrawable().apply {
            setColor(if (filled) Palette.PRIMARY else Palette.TINT)
            cornerRadius = dp(14).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(12) }
        setOnClickListener { onClick() }
    }

fun Context.chip(text: String, selected: Boolean, onClick: () -> Unit): TextView =
    label(text, color = if (selected) Color.WHITE else Palette.BODY).apply {
        gravity = Gravity.CENTER
        minHeight = dp(48)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        background = GradientDrawable().apply {
            setColor(if (selected) Palette.PRIMARY else Palette.TINT)
            cornerRadius = dp(24).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginEnd = dp(8) }
        setOnClickListener { onClick() }
    }

fun View.fillWidth() {
    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
}
