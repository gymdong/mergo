package app.mergo

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.mergo.report.ReportActivity
import app.mergo.report.bigButton
import app.mergo.service.MediaListener
import app.mergo.service.MergoService
import app.mergo.settings.SettingsActivity

/**
 * 처음 설치할 때 가족이 보는 화면. 시안의 '머고 소개'와 '권한 설정'을 한 화면에 담았다.
 * 권한마다 이유를 함께 보여 주고, 누르면 해당 설정 화면으로 보낸다.
 */
class MainActivity : Activity() {
    private val rows = mutableListOf<Pair<TextView, () -> Boolean>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(40), dp(24), dp(24))
            setBackgroundColor(BG)
        }
        col.addView(text("✦", 32f, Color.WHITE, center = true).apply { background = round(GREEN, dp(36).toFloat()) },
            LinearLayout.LayoutParams(dp(72), dp(72)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        col.addView(text("머고", 16f, GREEN, bold = true, center = true).apply { setPadding(0, dp(12), 0, 0) })
        col.addView(text("부모님의 스마트폰이\n막히지 않도록", 28f, INK, center = true).apply { setPadding(0, dp(4), 0, dp(8)) })
        col.addView(text("잘못 눌렀거나 화면이 바뀌었을 때\n머고가 먼저 알아채고 도와드려요.", 18f, MUTED, center = true))
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = round(Color.parseColor("#E8EEE9"), dp(12).toFloat())
            addView(text("✓  되돌릴 수 있는 일만 해요", 18f, INK))
            addView(text("✓  결제와 인증은 대신하지 않아요", 18f, INK))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(20); bottomMargin = dp(28)
        })

        col.addView(text("도움을 위해 권한이 필요해요", 24f, INK))
        col.addView(text("필요한 순간에만 사용하며 언제든 설정에서 끌 수 있어요.", 16f, MUTED).apply { setPadding(0, dp(4), 0, dp(12)) })
        col.addView(permission("화면 상태 확인", "어디서 막혔는지 알아봐요 (접근성 → 머고 도우미)", {
            enabled(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, ComponentName(this, MergoService::class.java))
        }) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        col.addView(permission("보던 영상 기억하기", "영상 제목을 읽어 아까 보던 영상을 찾아요 (알림 접근 → 머고 영상 제목 읽기)", {
            enabled("enabled_notification_listeners", ComponentName(this, MediaListener::class.java))
        }) { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) })
        col.addView(permission("음성 듣기", "도와줘 버튼을 누를 때만 들어요", {
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        }) { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1) })
        col.addView(permission("화면 설정 바꾸기", "밝기와 글자 크기를 직접 되돌려요 (설정에서 머고를 허용)", {
            Settings.System.canWrite(this)
        }) {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))) }
                .onFailure { Toast.makeText(this, "설정 화면을 열 수 없어요", Toast.LENGTH_SHORT).show() }
        })
        col.addView(bigButton("설정") { startActivity(Intent(this, SettingsActivity::class.java)) })
        col.addView(bigButton("가족 리포트", filled = false) { startActivity(Intent(this, ReportActivity::class.java)) })
        setContentView(ScrollView(this).apply { setBackgroundColor(BG); addView(col) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun refresh() {
        for ((badge, check) in rows) {
            val ok = check()
            badge.text = if (ok) "켜짐" else "켜기"
            badge.setTextColor(if (ok) Color.WHITE else GREEN)
            badge.background = round(if (ok) GREEN else Color.TRANSPARENT, dp(16).toFloat()).apply { setStroke(dp(2), GREEN) }
        }
    }

    private fun permission(title: String, reason: String, check: () -> Boolean, open: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = round(Color.WHITE, dp(12).toFloat()).apply { setStroke(dp(1), Color.parseColor("#DDE3DF")) }
        minimumHeight = dp(72)
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(title, 20f, INK, bold = true))
            addView(text(reason, 15f, MUTED))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val badge = Button(context).apply {
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setOnClickListener { open() }
        }
        addView(badge, LinearLayout.LayoutParams(dp(88), dp(52)))
        setOnClickListener { open() }
        rows += badge to check
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10)
        }
    }

    private fun enabled(key: String, cn: ComponentName): Boolean {
        val list = Settings.Secure.getString(contentResolver, key) ?: return false
        return list.split(':').any { ComponentName.unflattenFromString(it) == cn }
    }

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false, center: Boolean = false) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        if (center) gravity = Gravity.CENTER_HORIZONTAL
    }

    private fun round(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private companion object {
        val GREEN = Color.parseColor("#24664F")
        val INK = Color.parseColor("#1F2A24")
        val MUTED = Color.parseColor("#5B6B63")
        val BG = Color.parseColor("#F4F5F0")
    }
}
