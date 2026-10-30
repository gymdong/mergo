package app.mergo

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.Toast
import app.mergo.report.Palette
import app.mergo.report.ReportActivity
import app.mergo.report.bigButton
import app.mergo.report.column
import app.mergo.report.dp
import app.mergo.report.fillWidth
import app.mergo.report.greenSwitch
import app.mergo.report.icon
import app.mergo.report.iconCircle
import app.mergo.report.label
import app.mergo.report.rounded
import app.mergo.report.row
import app.mergo.service.MediaListener
import app.mergo.service.MergoService
import app.mergo.settings.MergoSettings

/**
 * 처음 설치할 때 가족이 보는 3단계 화면(시안 7~9쪽): 머고 소개 → 권한 설정 → 가족 연결.
 * 권한은 실제로 필요한 것을 이유와 함께 보여 주고, 누르면 해당 설정 화면으로 보낸다.
 * 가족 연결은 서버가 없어 번호를 기기 안에만 저장한다.
 */
class MainActivity : Activity() {
    private lateinit var settings: MergoSettings
    private lateinit var page: LinearLayout
    private lateinit var footer: FrameLayout
    private val rows = mutableListOf<Pair<Switch, () -> Boolean>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = MergoSettings(this)
        if (settings.setupDone && !intent.hasExtra(EXTRA_STEP)) return openReport()
        page = column(padding = 24)
        footer = FrameLayout(this).apply { setPadding(dp(24), 0, dp(24), dp(4)) }
        setContentView(
            column().apply {
                fitsSystemWindows = true
                setBackgroundColor(Palette.BACKGROUND)
                addView(
                    ScrollView(context).apply { addView(page) },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
                )
                addView(footer)
            },
        )
        when (intent.getIntExtra(EXTRA_STEP, 1)) {
            2 -> showPermissions()
            3 -> showFamily()
            else -> showIntro()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    /** 시안 7쪽: 머고 소개. */
    private fun showIntro() {
        page.removeAllViews()
        page.gravity = Gravity.CENTER_HORIZONTAL
        page.addView(
            FrameLayout(this).apply {
                background = rounded(Palette.SOFT, 54)
                addView(
                    FrameLayout(context).apply {
                        background = rounded(Palette.PRIMARY, 42)
                        addView(label("✦", sp = 34f, color = Color.WHITE), FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
                    },
                    FrameLayout.LayoutParams(dp(84), dp(84), Gravity.CENTER),
                )
            },
            LinearLayout.LayoutParams(dp(108), dp(108)).apply { topMargin = dp(48) },
        )
        page.addView(label("머고", color = Palette.PRIMARY, bold = true).apply { centered(); setPadding(0, dp(18), 0, 0) })
        page.addView(label("부모님의 스마트폰이\n막히지 않도록", sp = 28f, color = Palette.INK).apply { centered(); setPadding(0, dp(6), 0, dp(10)) })
        page.addView(label("잘못 눌렀거나 화면이 바뀌었을 때\n머고가 먼저 알아채고 도와드려요.").apply { centered() })
        page.addView(
            column(padding = 16).apply {
                background = rounded(Palette.SOFT, 4)
                addView(promise("되돌릴 수 있는 일만 해요"))
                addView(promise("결제와 인증은 대신하지 않아요").apply { setPadding(0, dp(10), 0, 0) })
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(32) },
        )
        setNext("시작하기") { showPermissions() }
    }

    private fun promise(text: String): LinearLayout {
        val words = label(text, color = Palette.INK)
        return row(icon(R.drawable.ic_check, Palette.ACCENT), words, middle = words)
    }

    /** 시안 8쪽: 권한 설정. 시안의 세 줄 대신 앱이 실제로 쓰는 권한 네 가지를 보여 준다. */
    private fun showPermissions() {
        page.removeAllViews()
        page.gravity = Gravity.START
        rows.clear()
        page.addView(label("2 / 3", color = Palette.PRIMARY, bold = true).apply { setPadding(0, dp(16), 0, dp(12)) })
        page.addView(label("도움을 위해\n권한이 필요해요", sp = 28f, color = Palette.INK))
        page.addView(label("필요한 순간에만 사용하며\n언제든 설정에서 끌 수 있어요.").apply { setPadding(0, dp(8), 0, dp(14)) })
        page.addView(permission(R.drawable.ic_phone, "화면 상태 확인", "어디서 막혔는지 알아봐요", {
            enabled(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, ComponentName(this, MergoService::class.java))
        }) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        page.addView(permission(R.drawable.ic_play, "보던 영상 기억하기", "아까 보던 영상을 찾아요", {
            enabled("enabled_notification_listeners", ComponentName(this, MediaListener::class.java))
        }) { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) })
        page.addView(permission(R.drawable.ic_mic, "음성 듣기", "머고를 부르는 말을 들어요", {
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        }) { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1) })
        page.addView(permission(R.drawable.ic_sun, "화면 설정 바꾸기", "밝기와 글자 크기를 맞춰요", {
            Settings.System.canWrite(this)
        }) {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))) }
                .onFailure { Toast.makeText(this, "설정 화면을 열 수 없어요", Toast.LENGTH_SHORT).show() }
        })
        refresh()
        setNext("다음") { showFamily() }
    }

    private fun permission(iconRes: Int, title: String, reason: String, check: () -> Boolean, open: () -> Unit): LinearLayout {
        val text = column().apply {
            addView(label(title, color = Palette.INK, bold = true))
            addView(label(reason, sp = 16f, color = Palette.MUTED))
        }
        val toggle = greenSwitch(check()).apply {
            isClickable = false
            isFocusable = false
        }
        rows += toggle to check
        return row(iconCircle(iconRes), text, toggle, middle = text).apply {
            background = rounded(Color.WHITE, 4).apply { setStroke(dp(1), Palette.LINE) }
            setPadding(dp(14), dp(14), dp(14), dp(14))
            minimumHeight = dp(76)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
            setOnClickListener { open() }
        }
    }

    /** 시안 9쪽: 가족 연결. */
    private fun showFamily() {
        page.removeAllViews()
        page.gravity = Gravity.START
        rows.clear()
        page.addView(label("3 / 3", color = Palette.PRIMARY, bold = true).apply { setPadding(0, dp(16), 0, dp(12)) })
        page.addView(label("가족을 연결해 주세요", sp = 28f, color = Palette.INK))
        page.addView(label("반복되는 어려움과 위험한 상황만\n연결된 가족에게 알려드려요.").apply { setPadding(0, dp(8), 0, dp(24)) })
        page.addView(
            LinearLayout(this).apply {
                gravity = Gravity.CENTER
                addView(avatar("순", Palette.BAR))
                addView(
                    FrameLayout(context).apply {
                        addView(View(context).apply { setBackgroundColor(Palette.LINE) }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(2), Gravity.CENTER))
                        addView(icon(R.drawable.ic_link, Palette.ACCENT), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
                    },
                    LinearLayout.LayoutParams(dp(72), dp(28)),
                )
                addView(avatar("민", Palette.ALERT_AVATAR))
                addView(avatar("지", Palette.PRIMARY))
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(24) },
        )
        page.addView(label("가족 휴대폰 번호", color = Palette.INK, bold = true))
        val phones = column()
        settings.familyPhones.ifEmpty { listOf("") }.forEach { phones.addView(phoneField(it)) }
        page.addView(phones)
        page.addView(bigButton("가족 더 추가", filled = false) { phones.addView(phoneField("")) })
        val privacy = label("화면 내용은 공유하지 않아요.\n도움 기록만 요약해서 보내요.\n어르신이 동의한 가족만 볼 수 있어요.")
        page.addView(
            row(icon(R.drawable.ic_shield, Palette.ACCENT), privacy, middle = privacy).apply {
                background = rounded(Palette.SOFT, 4)
                setPadding(dp(14), dp(14), dp(14), dp(14))
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) },
        )
        setNext("연결 요청 보내기") {
            settings.familyPhones = (0 until phones.childCount).map { (phones.getChildAt(it) as EditText).text.toString() }
            settings.setupDone = true
            Toast.makeText(this, "예시 화면이라 실제로 보내지는 않아요", Toast.LENGTH_LONG).show()
            openReport()
        }
    }

    private fun phoneField(number: String) = EditText(this).apply {
        setText(number)
        hint = "010 - 1234 - 5678"
        inputType = InputType.TYPE_CLASS_PHONE
        textSize = 20f
        setSingleLine()
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = rounded(Color.WHITE, 4).apply { setStroke(dp(1), Palette.LINE) }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
    }

    private fun avatar(name: String, color: Int) = FrameLayout(this).apply {
        background = rounded(Color.WHITE, 36)
        elevation = dp(3).toFloat()
        addView(
            label(name, sp = 22f, color = Color.WHITE, bold = true).apply {
                gravity = Gravity.CENTER
                background = rounded(color, 30)
            },
            FrameLayout.LayoutParams(dp(60), dp(60), Gravity.CENTER),
        )
        layoutParams = LinearLayout.LayoutParams(dp(72), dp(72))
    }

    private fun setNext(title: String, onClick: () -> Unit) {
        footer.removeAllViews()
        footer.addView(bigButton(title, onClick = onClick))
    }

    private fun openReport() {
        startActivity(Intent(this, ReportActivity::class.java))
        finish()
    }

    private fun refresh() {
        for ((toggle, check) in rows) toggle.isChecked = check()
    }

    private fun enabled(key: String, cn: ComponentName): Boolean {
        val list = Settings.Secure.getString(contentResolver, key) ?: return false
        return list.split(':').any { ComponentName.unflattenFromString(it) == cn }
    }

    private fun android.widget.TextView.centered() {
        gravity = Gravity.CENTER_HORIZONTAL
    }

    companion object {
        /** 시작할 단계. 주면 첫 설정을 마친 뒤에도 그 단계를 보여 준다. */
        const val EXTRA_STEP = "step"
    }
}
