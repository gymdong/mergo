package app.mergo.settings

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Switch
import android.widget.Toast
import app.mergo.report.Palette
import app.mergo.report.ReportActivity
import app.mergo.report.bigButton
import app.mergo.report.card
import app.mergo.report.column
import app.mergo.report.label

class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = MergoSettings(this)

        val wakeWord = EditText(this).apply {
            setText(settings.wakeWord)
            textSize = 20f
            setSingleLine()
        }
        val autoExecute = Switch(this).apply {
            text = "확인 없이 실행"
            textSize = 18f
            isChecked = settings.autoExecute
            setOnCheckedChangeListener { _, checked -> settings.autoExecute = checked }
        }

        val page = column(padding = 20).apply {
            setBackgroundColor(Palette.BACKGROUND)
            addView(label("설정", sp = 24f, color = Palette.INK, bold = true))
            addView(
                card().apply {
                    addView(label("부르는 말", color = Palette.INK, bold = true))
                    addView(label("이 말로 머고를 부를 수 있어요."))
                    addView(wakeWord)
                    addView(
                        bigButton("저장하기") {
                            settings.wakeWord = wakeWord.text.toString()
                            Toast.makeText(this@SettingsActivity, "저장했어요", Toast.LENGTH_SHORT).show()
                        },
                    )
                },
            )
            addView(
                card().apply {
                    addView(autoExecute)
                    addView(label("켜면 묻지 않고 바로 고쳐요. 설치와 결제 같은 버튼은 켜도 누르지 않아요."))
                },
            )
            addView(
                bigButton("가족 리포트 보기", filled = false) {
                    startActivity(Intent(this@SettingsActivity, ReportActivity::class.java))
                },
            )
        }

        setContentView(
            ScrollView(this).apply {
                fitsSystemWindows = true
                setBackgroundColor(Palette.BACKGROUND)
                addView(page)
            },
        )
    }
}
