package app.mergo.ui

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.text.LineBreakConfig
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
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
 * - 도와줘 버튼은 누르고 있다가 끌면 옮길 수 있고, 옮긴 자리를 기억한다. 화면 내용을 가릴 때 쓴다.
 */
class OverlayUi(
    private val service: AccessibilityService,
    private val onHelp: () -> Unit,
) : OverlayPort {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val prefs = service.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var helpButton: View? = null
    private var sheet: View? = null
    private var progressCard: View? = null
    private var doneCard: View? = null
    private var noticeCard: View? = null

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
            contentDescription = "머고 도와줘 버튼"
        }
        val lp = params(WindowManager.LayoutParams.WRAP_CONTENT, dp(64), Gravity.END or Gravity.BOTTOM).apply {
            x = prefs.getInt(KEY_HELP_X, dp(16))
            y = prefs.getInt(KEY_HELP_Y, dp(64))
        }
        moveOnHold(b, lp)
        wm.addView(b, lp)
        helpButton = b
    }

    /**
     * 누르면 도와줘, [HOLD_TO_MOVE_MS] 이상 누른 뒤 일정 거리 움직이면 버튼을 옮긴다.
     * 오래 눌렀어도 움직이지 않았다면 도와줘를 실행한다. 옮긴 위치는 손을 놓을 때 기억한다.
     * 손떨림으로 조금 움직인 것은 누르기로 보고, 누르기 전에 크게 미끄러지면 아무것도 하지 않는다.
     */
    private fun moveOnHold(b: View, lp: WindowManager.LayoutParams) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moving = false
        var moveArmed = false
        var slid = false
        val slop = TAP_SLOP_DP * service.resources.displayMetrics.density
        val hold = Runnable {
            moveArmed = true
            b.alpha = 0.75f
            b.scaleX = 1.1f
            b.scaleY = 1.1f
        }
        b.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = lp.x
                    startY = lp.y
                    moving = false
                    moveArmed = false
                    slid = false
                    v.isPressed = true
                    v.postDelayed(hold, HOLD_TO_MOVE_MS)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (moveArmed && !moving && dx * dx + dy * dy > slop * slop) moving = true
                    if (moving) {
                        // 오른쪽 아래 기준이라 손가락이 오른쪽·아래로 가면 x·y가 줄어든다.
                        val screen = service.resources.displayMetrics
                        lp.x = (startX - dx.toInt()).coerceIn(0, (screen.widthPixels - v.width).coerceAtLeast(0))
                        lp.y = (startY - dy.toInt()).coerceIn(0, (screen.heightPixels - v.height).coerceAtLeast(0))
                        wm.updateViewLayout(v, lp)
                    } else if (dx * dx + dy * dy > slop * slop) {
                        slid = true
                        v.isPressed = false
                        v.removeCallbacks(hold)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(hold)
                    v.isPressed = false
                    if (moveArmed) {
                        b.alpha = 1f
                        b.scaleX = 1f
                        b.scaleY = 1f
                    }
                    if (moving) {
                        prefs.edit().putInt(KEY_HELP_X, lp.x).putInt(KEY_HELP_Y, lp.y).apply()
                    } else if (e.actionMasked == MotionEvent.ACTION_UP && !slid) {
                        v.performClick()
                    }
                }
            }
            true
        }
    }

    fun removeAll() {
        removeSheet()
        progressCard?.let(::remove)
        doneCard?.let(::remove)
        noticeCard?.let(::remove)
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

    override fun listening(prompt: String, ready: Boolean) {
        service.mainExecutor.execute {
            removeSheet()
            val box = sheetContainer(DEEP_GREEN).apply {
                addView(label(if (ready) "듣고 있어요" else "마이크 준비 중", 18f, LIME, center = true))
                addView(label(prompt, 28f, Color.WHITE, center = true))
            }
            wm.addView(box, params(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM, touchable = false))
            sheet = box
        }
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

    override fun notice(text: String) {
        service.mainExecutor.execute {
            noticeCard?.let(::remove)
            val card = card(BG_LIGHT).apply { addView(label(text, 22f, INK)) }
            wm.addView(card, params(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT, Gravity.TOP, touchable = false).apply {
                horizontalMargin = 0.04f
                y = dp(40)
            })
            noticeCard = card
            card.postDelayed({ if (noticeCard === card) { remove(card); noticeCard = null } }, NOTICE_MS)
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
        box.addView(label("말하거나 골라 주세요", 18f, LIME, center = true))
        box.addView(label(prompt, 28f, Color.WHITE, center = true).apply { setPadding(0, dp(4), 0, dp(4)) })
        box.addView(View(service), LinearLayout.LayoutParams(1, dp(12)))
        for (c in choices) {
            box.addView(secondaryButton(c, Color.WHITE, Color.parseColor("#5E8A79")) { removeSheet(); onPick(c) }, buttonLp(false))
        }
        // 잘못 눌러 열었을 때 바로 빠져나갈 수 있게, 닫기도 테두리가 보이는 큰 버튼으로 둔다.
        box.addView(secondaryButton("괜찮아, 닫아 줘", LIME, LIME) { removeSheet(); onPick(null) }, buttonLp(false))
        // 패널 바깥은 어둡게 덮는다. 바깥을 누르면 닫기만 하고, 그 터치가 아래 앱을 누르지 않게 한다.
        box.isClickable = true
        val scrim = FrameLayout(service).apply {
            setBackgroundColor(SCRIM)
            setOnClickListener { removeSheet(); onPick(null) }
            addView(box, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        }
        addSheet(scrim, fullScreen = true)
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

    private fun addSheet(v: View, fullScreen: Boolean = false) {
        val h = if (fullScreen) WindowManager.LayoutParams.MATCH_PARENT else WindowManager.LayoutParams.WRAP_CONTENT
        wm.addView(v, params(WindowManager.LayoutParams.MATCH_PARENT, h, Gravity.BOTTOM))
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
        // 어르신 폰은 글자를 키워 쓰므로 줄이 자주 바뀐다. 단어 중간에서 끊기지 않게 한다.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) lineBreakWordStyle = LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE
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
        const val PREFS = "mergo_overlay"
        const val KEY_HELP_X = "help_x"
        const val KEY_HELP_Y = "help_y"
        /** 이만큼 누른 뒤 움직여야 옮기기. 어르신이 천천히 누르는 것을 감안해 길게 둔다. */
        const val HOLD_TO_MOVE_MS = 2_000L
        /** 손떨림으로 이 안에서 움직인 것은 그대로 누르기로 본다. */
        const val TAP_SLOP_DP = 24f
        const val NOTICE_MS = 8_000L

        // mergo_UI.pdf 시안 색
        val GREEN = Color.parseColor("#24664F")
        val DEEP_GREEN = Color.parseColor("#1B3D33")
        val LIME = Color.parseColor("#C5E26A")
        val INK = Color.parseColor("#1F2A24")
        val MUTED = Color.parseColor("#5B6B63")
        val BG_LIGHT = Color.parseColor("#F7F8F4")
        val OUTLINE = Color.parseColor("#A8B5AE")
        val SCRIM = Color.parseColor("#66000000")
    }
}
