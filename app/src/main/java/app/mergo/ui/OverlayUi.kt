package app.mergo.ui

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import app.mergo.model.YesNo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 접근성 오버레이 창에 도와줘 버튼, 막힘 감지 시트, 도와줘 선택 화면, 복구 중·완료 카드를 띄운다.
 * 모양은 `mergo_UI.pdf` 시안(진초록·라임, 큰 글씨, 48dp 이상 버튼)을 따른다.
 *
 * - TYPE_ACCESSIBILITY_OVERLAY라 '다른 앱 위 표시' 권한이 필요 없다.
 * - 모든 창은 포커스를 받지 않아 아래 앱이 계속 '활성 창'으로 읽힌다.
 * - 복구 중·완료 카드는 터치를 통과시킨다. 레시피의 탭 제스처가 오버레이에 막히지 않게 하기 위해서다.
 */
class OverlayUi(
    private val service: AccessibilityService,
    private val onHelp: () -> Unit,
    private val onHelpLongPress: () -> Unit,
) : OverlayPort {
    private val wm = service.getSystemService(WindowManager::class.java)
    private var helpButton: View? = null
    private var sheet: View? = null
    private var progressCard: View? = null
    private var doneCard: View? = null

    fun showHelpButton() {
        if (helpButton != null) return
        val b = Button(service).apply {
            text = "✦  도와줘"
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = round(GREEN, dp(32).toFloat())
            elevation = dp(6).toFloat()
            setPadding(dp(24), 0, dp(24), 0)
            setOnClickListener { onHelp() }
            setOnLongClickListener { onHelpLongPress(); true }
            contentDescription = "머고 도와줘 버튼"
        }
        val lp = params(WindowManager.LayoutParams.WRAP_CONTENT, dp(64), Gravity.END or Gravity.BOTTOM).apply {
            x = dp(16)
            y = dp(96)
        }
        wm.addView(b, lp)
        helpButton = b
    }

    fun removeAll() {
        removeSheet()
        progressCard?.let(::remove)
        doneCard?.let(::remove)
        helpButton?.let(::remove)
        progressCard = null
        doneCard = null
        helpButton = null
    }

    override suspend fun ask(prompt: Prompt, timeoutMs: Long): YesNo = withContext(Dispatchers.Main) {
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                showAskSheet(prompt) { yes -> if (cont.isActive) cont.resume(if (yes) YesNo.YES else YesNo.NO) }
                cont.invokeOnCancellation { service.mainExecutor.execute { removeSheet() } }
            }
        } ?: YesNo.NONE.also { removeSheet() }
    }

    override suspend fun choose(prompt: String, choices: List<String>, timeoutMs: Long): String? =
        withContext(Dispatchers.Main) {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<String?> { cont ->
                    showChoosePanel(prompt, choices) { picked -> if (cont.isActive) cont.resume(picked) }
                    cont.invokeOnCancellation { service.mainExecutor.execute { removeSheet() } }
                }
            }.also { if (it == null) removeSheet() }
        }

    override fun progress(text: String?) {
        service.mainExecutor.execute {
            progressCard?.let(::remove)
            progressCard = null
            if (text == null) return@execute
            val card = card(BG_LIGHT).apply {
                addView(label("잠시만 기다려 주세요", 16f, GREEN, bold = true))
                addView(label(text, 24f, INK).apply { setPadding(0, dp(4), 0, dp(12)) })
                addView(ProgressBar(service, null, android.R.attr.progressBarStyleHorizontal).apply {
                    isIndeterminate = true
                    indeterminateTintList = android.content.res.ColorStateList.valueOf(GREEN)
                })
            }
            wm.addView(card, params(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT, Gravity.TOP, touchable = false).apply {
                horizontalMargin = 0.04f
                y = dp(40)
            })
            progressCard = card
        }
    }

    override fun done(title: String, body: String) {
        service.mainExecutor.execute {
            doneCard?.let(::remove)
            val row = LinearLayout(service).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20), dp(16), dp(20), dp(16))
                background = round(BG_LIGHT, dp(20).toFloat()).apply { setStroke(dp(1), OUTLINE) }
                elevation = dp(6).toFloat()
                addView(TextView(service).apply {
                    text = "✓"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    background = round(GREEN, dp(24).toFloat())
                }, LinearLayout.LayoutParams(dp(48), dp(48)))
                addView(LinearLayout(service).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), 0, 0, 0)
                    addView(label(title, 20f, INK, bold = true))
                    addView(label(body, 16f, MUTED))
                })
            }
            wm.addView(row, params(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT, Gravity.TOP, touchable = false).apply {
                horizontalMargin = 0.04f
                y = dp(40)
            })
            doneCard = row
            row.postDelayed({ if (doneCard === row) { remove(row); doneCard = null } }, 4_000)
        }
    }

    override fun dismiss() {
        service.mainExecutor.execute { removeSheet() }
    }

    /** 시안 02(막힘 감지)·05(이 화면이 맞나요?) — 하단 시트. 본문이 없으면 버튼을 나란히 둔다. */
    private fun showAskSheet(p: Prompt, onAnswer: (Boolean) -> Unit) {
        removeSheet()
        val box = sheetContainer(BG_LIGHT)
        if (p.eyebrow != null) {
            box.addView(label("✦", 22f, Color.WHITE, center = true).apply { background = round(GREEN, dp(24).toFloat()) },
                LinearLayout.LayoutParams(dp(48), dp(48)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(12) })
            box.addView(label(p.eyebrow, 16f, GREEN, bold = true, center = true))
        }
        box.addView(label(p.title, 28f, INK, bold = p.body == null, center = p.eyebrow != null).apply { setPadding(0, dp(4), 0, dp(4)) })
        if (p.body != null) box.addView(label(p.body, 20f, MUTED, center = p.eyebrow != null).apply { setPadding(0, 0, 0, dp(16)) })
        val sideBySide = p.body == null
        val row = LinearLayout(service).apply { orientation = if (sideBySide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        row.addView(primaryButton(p.yes) { removeSheet(); onAnswer(true) }, buttonLp(sideBySide))
        row.addView(secondaryButton(p.no, INK, OUTLINE) { removeSheet(); onAnswer(false) }, buttonLp(sideBySide))
        box.addView(row)
        addSheet(box)
    }

    /** 시안 03(도와줘·음성) — 진초록 패널. 음성은 VoiceIO가 따로 듣고, 여기서는 큰 선택지를 함께 보여 준다. */
    private fun showChoosePanel(prompt: String, choices: List<String>, onPick: (String?) -> Unit) {
        removeSheet()
        val box = sheetContainer(DEEP_GREEN)
        box.addView(label("듣고 있어요", 18f, LIME, center = true))
        box.addView(label(prompt, 28f, Color.WHITE, center = true).apply { setPadding(0, dp(4), 0, dp(4)) })
        box.addView(label("편하게 말씀하세요. 짧게 말해도 알아들을게요.", 18f, Color.parseColor("#CFE0D6"), center = true).apply {
            setPadding(0, 0, 0, dp(16))
        })
        for (c in choices) {
            box.addView(secondaryButton(c, Color.WHITE, Color.parseColor("#5E8A79")) { removeSheet(); onPick(c) }, buttonLp(false))
        }
        box.addView(secondaryButton("✕  닫기", Color.parseColor("#CFE0D6"), Color.TRANSPARENT) { removeSheet(); onPick(null) }, buttonLp(false))
        addSheet(box)
    }

    private fun sheetContainer(bg: Int) = LinearLayout(service).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(24), dp(24), dp(24))
        background = GradientDrawable().apply {
            setColor(bg)
            cornerRadii = floatArrayOf(dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat(), 0f, 0f, 0f, 0f)
        }
        elevation = dp(12).toFloat()
    }

    private fun addSheet(v: View) {
        wm.addView(v, params(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        sheet = v
    }

    private fun removeSheet() {
        sheet?.let(::remove)
        sheet = null
    }

    private fun card(bg: Int) = LinearLayout(service).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(16), dp(20), dp(16))
        background = round(bg, dp(20).toFloat()).apply { setStroke(dp(1), OUTLINE) }
        elevation = dp(6).toFloat()
    }

    private fun label(text: String, sp: Float, color: Int, bold: Boolean = false, center: Boolean = false) = TextView(service).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        if (center) gravity = Gravity.CENTER_HORIZONTAL
    }

    private fun primaryButton(text: String, onClick: () -> Unit) = Button(service).apply {
        this.text = text
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        background = round(GREEN, dp(12).toFloat())
        setOnClickListener { onClick() }
    }

    private fun secondaryButton(text: String, fg: Int, stroke: Int, onClick: () -> Unit) = Button(service).apply {
        this.text = text
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        setTextColor(fg)
        background = round(Color.TRANSPARENT, dp(12).toFloat()).apply { setStroke(dp(2), stroke) }
        setOnClickListener { onClick() }
    }

    private fun buttonLp(sideBySide: Boolean) =
        LinearLayout.LayoutParams(if (sideBySide) 0 else LinearLayout.LayoutParams.MATCH_PARENT, dp(64), if (sideBySide) 1f else 0f).apply {
            setMargins(dp(4), dp(6), dp(4), dp(6))
        }

    private fun params(w: Int, h: Int, gravity: Int, touchable: Boolean = true) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT,
    ).apply { this.gravity = gravity }

    private fun remove(v: View) {
        runCatching { wm.removeView(v) }
    }

    private fun round(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), service.resources.displayMetrics).toInt()

    private companion object {
        // mergo_UI.pdf 시안 색
        val GREEN = Color.parseColor("#24664F")
        val DEEP_GREEN = Color.parseColor("#1B3D33")
        val LIME = Color.parseColor("#C5E26A")
        val INK = Color.parseColor("#1F2A24")
        val MUTED = Color.parseColor("#5B6B63")
        val BG_LIGHT = Color.parseColor("#F7F8F4")
        val OUTLINE = Color.parseColor("#A8B5AE")
    }
}
