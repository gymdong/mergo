package com.mergo

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import com.mergo.ai.AndroidVoiceIO
import com.mergo.ai.MergoAi
import com.mergo.model.Baseline
import com.mergo.model.BrainContext
import com.mergo.report.Palette
import com.mergo.report.ReportActivity
import com.mergo.report.bigButton
import com.mergo.report.column
import com.mergo.report.label
import com.mergo.settings.MergoSettings
import com.mergo.settings.SettingsActivity
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** 권한 안내 화면이 들어오기 전까지 B 화면으로 가는 입구와 음성 판단 시험을 둔다. */
class MainActivity : Activity() {
    private val scope = MainScope()
    private lateinit var voice: AndroidVoiceIO
    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        voice = AndroidVoiceIO(this)
        output = label("말을 시험하면 판단 결과가 여기에 나와요.")

        val page = column(padding = 20).apply {
            addView(label("머고", sp = 28f, color = Palette.PRIMARY, bold = true))
            addView(bigButton("설정") { open(SettingsActivity::class.java) })
            addView(bigButton("가족 리포트") { open(ReportActivity::class.java) })
            addView(bigButton("말로 판단 시험", filled = false) { tryVoice() })
            addView(output)
        }
        setContentView(
            ScrollView(this).apply {
                fitsSystemWindows = true
                setBackgroundColor(Palette.BACKGROUND)
                addView(page)
            },
        )
        // 디버그 빌드에서는 adb로 발화를 넣어 음성 없이 판단을 시험한다.
        if (BuildConfig.DEBUG) intent.getStringExtra(EXTRA_SAID)?.let { scope.launch { judge(it) } }
    }

    override fun onDestroy() {
        scope.cancel()
        voice.close()
        super.onDestroy()
    }

    private fun open(screen: Class<out Activity>) {
        startActivity(Intent(this, screen))
    }

    private fun tryVoice() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
            return
        }
        scope.launch {
            voice.speak("무엇을 도와드릴까요?")
            val said = voice.listen()
            if (said == null) {
                output.text = "말을 듣지 못했어요."
                return@launch
            }
            judge(said)
        }
    }

    private suspend fun judge(said: String) {
        val decision = MergoAi.brain(voice, MergoSettings(this)).decide(sampleContext(said))
        output.text = "들은 말: $said\n레시피: ${decision.recipe}\n안내: ${decision.say}"
        voice.speak(decision.say)
    }

    private fun sampleContext(said: String) = BrainContext(
        utterance = said,
        diff = emptyList(),
        timeline = emptyList(),
        current = Baseline("com.mergo", null, 8, true, System.currentTimeMillis()),
        screenHasAd = false,
    )

    private companion object {
        const val REQUEST_MIC = 1
        const val EXTRA_SAID = "said"
    }
}
