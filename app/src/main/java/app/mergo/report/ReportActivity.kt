package app.mergo.report

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import app.mergo.memory.FamilyLog
import app.mergo.settings.SettingsActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 가족 리포트 목업. 요약은 예시 값이고, 기록 탭에는 실제 FamilyLog 항목을 함께 보여 준다. */
class ReportActivity : Activity() {
    private lateinit var content: LinearLayout
    private lateinit var log: FamilyLog
    private var filter = Filter.ALL

    private enum class Filter(val title: String) {
        ALL("전체"), AUTO("자동 해결"), REVIEW("확인 필요")
    }

    private data class Item(val day: String, val title: String, val time: String, val outcome: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        log = FamilyLog(File(filesDir, "family_log.json"))
        content = column(padding = 20)

        val root = column().apply {
            fitsSystemWindows = true
            setBackgroundColor(Palette.BACKGROUND)
            addView(
                column(padding = 20).apply {
                    addView(label("머고", sp = 22f, color = Palette.PRIMARY, bold = true))
                    addView(label("어머니 폰 연결됨", color = Palette.MUTED))
                },
            )
            addView(
                ScrollView(context).apply { addView(content) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
            )
            addView(tabs())
        }
        setContentView(root)
        showHome()
    }

    private fun tabs(): LinearLayout = LinearLayout(this).apply {
        setPadding(dp(12), dp(4), dp(12), dp(8))
        listOf<Pair<String, () -> Unit>>(
            "홈" to ::showHome,
            "기록" to ::showRecords,
            "설정" to { startActivity(Intent(this@ReportActivity, SettingsActivity::class.java)) },
        ).forEach { (title, action) ->
            addView(bigButton(title, filled = false, onClick = action).apply { fillWidth() })
        }
    }

    private fun showHome() {
        content.removeAllViews()
        content.addView(label("이번 주도 잘 사용하셨어요", sp = 24f, color = Palette.INK, bold = true))
        content.addView(label("10월 21일 — 27일 (예시)", color = Palette.MUTED))

        content.addView(
            card().apply {
                addView(label("5번 중 4번을", sp = 22f, color = Palette.INK, bold = true))
                addView(label("머고가 도와드렸어요", sp = 22f, color = Palette.INK, bold = true))
                addView(label("자동 해결 4건 · 가족 도움 1건", color = Palette.ACCENT))
            },
        )
        content.addView(
            card().apply {
                addView(label("자주 막힌 일 TOP 3", color = Palette.INK, bold = true))
                listOf("1. 영상이 저절로 바뀌었어요", "2. 소리가 나지 않았어요", "3. 홈 화면으로 나갔어요")
                    .forEach { addView(label(it)) }
            },
        )
        content.addView(
            card().apply {
                addView(label("가장 최근 도움", color = Palette.MUTED))
                addView(label("소리가 나지 않았어요", sp = 20f, color = Palette.INK, bold = true))
                addView(label("오늘 오전 10:24 · 자동 해결"))
            },
        )
    }

    private fun showRecords() {
        content.removeAllViews()
        content.addView(label("도움 기록", sp = 24f, color = Palette.INK, bold = true))

        val chips = LinearLayout(this).apply { setPadding(0, dp(12), 0, 0) }
        Filter.entries.forEach { option ->
            chips.addView(
                chip(option.title, selected = option == filter) {
                    filter = option
                    showRecords()
                },
            )
        }
        content.addView(HorizontalScrollView(this).apply { addView(chips) })

        val items = realItems() + MOCK_ITEMS
        val shown = items.filter {
            when (filter) {
                Filter.ALL -> true
                Filter.AUTO -> it.outcome == AUTO_OUTCOME
                Filter.REVIEW -> it.outcome != AUTO_OUTCOME
            }
        }
        shown.groupBy { it.day }.forEach { (day, group) ->
            content.addView(label(day, color = Palette.MUTED).apply { setPadding(0, dp(16), 0, 0) })
            group.forEach { item ->
                content.addView(
                    card().apply {
                        addView(label(item.title, color = Palette.INK, bold = true))
                        addView(label("${item.time} · ${item.outcome}"))
                    },
                )
            }
        }
        if (shown.isEmpty()) content.addView(label("해당하는 기록이 없어요.").apply { setPadding(0, dp(16), 0, 0) })
    }

    private fun realItems(): List<Item> {
        val day = SimpleDateFormat("M월 d일", Locale.KOREAN)
        val time = SimpleDateFormat("a h:mm", Locale.KOREAN)
        return log.all().asReversed().map {
            val at = Date(it.ts)
            val outcome = when {
                it.resolved -> AUTO_OUTCOME
                it.notified -> "가족에게 알림"
                it.outcome == "guided" -> "안내함"
                else -> "확인 필요"
            }
            Item("실제 기록 · ${day.format(at)}", it.summary.ifBlank { it.situation }, time.format(at), outcome)
        }
    }

    private companion object {
        const val AUTO_OUTCOME = "자동 해결"

        val MOCK_ITEMS = listOf(
            Item("오늘 (예시)", "소리를 다시 켰어요", "오전 10:24", AUTO_OUTCOME),
            Item("오늘 (예시)", "보던 앱으로 돌아갔어요", "오전 8:12", AUTO_OUTCOME),
            Item("어제 (예시)", "이전 영상을 다시 찾았어요", "오후 7:42", AUTO_OUTCOME),
            Item("어제 (예시)", "의심스러운 광고를 닫았어요", "오후 2:18", "가족에게 알림"),
        )
    }
}
