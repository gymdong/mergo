package app.mergo.settings

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
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
        val displayAutoRescue = Switch(this).apply {
            text = "화면 밝기·글자 크기 자동 복구"
            textSize = 18f
            isChecked = settings.displayAutoRescue
            setOnCheckedChangeListener { button, checked ->
                if (checked && (settings.preferredBrightness == null || settings.preferredFontScale == null ||
                            !Settings.System.canWrite(this@SettingsActivity))) {
                    button.isChecked = false
                    Toast.makeText(this@SettingsActivity, "먼저 화면 설정 권한을 켜고 편한 값을 저장해 주세요", Toast.LENGTH_LONG).show()
                } else {
                    settings.displayAutoRescue = checked
                }
            }
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
                card().apply {
                    addView(label("편한 화면 저장", color = Palette.INK, bold = true))
                    addView(label("가족이 먼저 밝기와 글자 크기를 편하게 맞춰 주세요. 화면 설정 바꾸기 권한은 첫 화면에서 켤 수 있어요."))
                    addView(bigButton("지금 밝기·글자 크기 저장") {
                        val saved = settings.saveCurrentDisplayPreferences()
                        Toast.makeText(this@SettingsActivity, if (saved) "편한 화면으로 저장했어요" else "현재 화면 값을 읽지 못했어요", Toast.LENGTH_SHORT).show()
                    })
                    addView(displayAutoRescue)
                    addView(label("켜면 화면이 갑자기 너무 어두워지거나 글자가 작아졌을 때 저장한 값으로 되돌려요. 밝기 자동 복구는 휴대전화의 자동 밝기가 꺼져 있을 때만 동작해요. 도와줘를 눌러 어둡다고 말씀하시면 자동 밝기를 끄고 직접 밝게 할 수 있어요."))
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
