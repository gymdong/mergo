package app.mergo.report

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/** UI 가이드(mergo_UI.pdf)에서 뽑은 색. 글씨는 스펙대로 최소 18sp로 맞춘다. */
object Palette {
    val BACKGROUND = Color.parseColor("#F4F5F0")
    val PRIMARY = Color.parseColor("#2B684F")
    val ACCENT = Color.parseColor("#37745F")
    val INK = Color.parseColor("#1D302A")
    val BODY = Color.parseColor("#53635D")
    val MUTED = Color.parseColor("#7A8580")
    val LINE = Color.parseColor("#D5DDD8")
    val TINT = Color.parseColor("#E7EBE4")
    val DEEP = Color.parseColor("#225646")
    val LIME = Color.parseColor("#C5E26B")
    val BAR = Color.parseColor("#7BA392")
    val SOFT = Color.parseColor("#E1E9E3")
    val ALERT = Color.parseColor("#B4532A")
    val ALERT_TINT = Color.parseColor("#F6E4DB")
    val ALERT_AVATAR = Color.parseColor("#D98B5F")
}

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

fun Context.rounded(color: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
    setColor(color)
    cornerRadius = dp(radiusDp).toFloat()
}

fun Context.icon(resId: Int, tint: Int, sizeDp: Int = 24): ImageView = ImageView(this).apply {
    setImageResource(resId)
    imageTintList = ColorStateList.valueOf(tint)
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
}

/** 시안의 동그란 아이콘 배경. */
fun Context.iconCircle(resId: Int, tint: Int = Palette.ACCENT, fill: Int = Palette.SOFT, sizeDp: Int = 52): FrameLayout =
    FrameLayout(this).apply {
        background = rounded(fill, sizeDp / 2)
        addView(icon(resId, tint), FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

/** 가로 한 줄. [middle]이 남는 폭을 차지한다. */
fun Context.row(vararg parts: View, middle: View? = null): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    parts.forEach { addView(it) }
    middle?.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
        if (middle !== parts.first()) marginStart = dp(14)
        marginEnd = dp(10)
    }
}

/** 시안의 초록 스위치. 켜고 끄는 일은 줄을 눌러 처리하는 곳에서도 쓰도록 모양만 맞춘다. */
fun Context.greenSwitch(on: Boolean): Switch = Switch(this).apply {
    isChecked = on
    fun track(color: Int) = rounded(color, 14).apply { setSize(dp(48), dp(28)) }
    trackDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_checked), track(Palette.PRIMARY))
        addState(intArrayOf(), track(Palette.LINE))
    }
    thumbDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.WHITE)
        setSize(dp(28), dp(28))
        setStroke(dp(4), Color.TRANSPARENT)
    }
}

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
        background = rounded(if (selected) Palette.PRIMARY else Color.WHITE, 24).apply {
            if (!selected) setStroke(dp(1), Palette.LINE)
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
