package app.mergo.report

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.mergo.MainActivity
import app.mergo.R
import app.mergo.memory.FamilyLog
import app.mergo.model.RecipeIds
import app.mergo.settings.SettingsActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 보호자가 보는 화면(시안 10~12쪽). 서버가 없어 요약과 제안은 예시 값이고,
 * 도움 기록에는 이 기기의 실제 FamilyLog 항목을 예시와 함께 보여 준다.
 */
class ReportActivity : Activity() {
    private lateinit var content: LinearLayout
    private lateinit var nav: LinearLayout
    private lateinit var log: FamilyLog
    private lateinit var status: TextView
    private lateinit var elderChips: LinearLayout
    private var filter = Filter.ALL
    private var elder = ELDERS.first()
    private var tab = Tab.HOME

    private enum class Filter(val title: String) {
        ALL("전체"), AUTO("자동 해결"), REVIEW("확인 필요")
    }

    private enum class Tab(val title: String, val icon: Int) {
        HOME("홈", R.drawable.ic_home), RECORDS("기록", R.drawable.ic_records), SETTINGS("설정", R.drawable.ic_shield)
    }

    private data class Item(val day: String, val title: String, val time: String, val outcome: String, val icon: Int)

    /** 보호자가 살피는 어르신 한 분의 이번 주 요약. [ownPhone]이면 이 기기의 실제 기록을 함께 보여 준다. */
    private data class Elder(
        val name: String, val helped: Int, val family: Int, val bars: List<Int>, val busyDays: Set<Int>,
        val items: List<Item>, val ownPhone: Boolean = false,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        log = FamilyLog(File(filesDir, "family_log.json"))
        content = column(padding = 20)
        nav = LinearLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            elevation = dp(8).toFloat()
            setPadding(0, dp(8), 0, dp(8))
        }
        setContentView(
            column().apply {
                fitsSystemWindows = true
                setBackgroundColor(Palette.BACKGROUND)
                addView(header())
                addView(
                    ScrollView(context).apply { addView(content) },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
                )
                addView(nav)
            },
        )
        show(Tab.HOME)
    }

    private fun header(): LinearLayout = column(padding = 20).apply {
        setPadding(dp(20), dp(16), dp(20), 0)
        val logo = label("✦  머고", sp = 22f, color = Palette.PRIMARY, bold = true)
        val bell = iconCircle(R.drawable.ic_bell, sizeDp = 48).apply {
            contentDescription = "알림"
            setOnClickListener { Toast.makeText(context, "새 알림이 없어요", Toast.LENGTH_SHORT).show() }
        }
        addView(row(logo, bell, middle = logo))
        val dot = View(context).apply {
            background = rounded(Palette.ACCENT, 5)
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(8) }
        }
        status = label("", color = Palette.BODY)
        addView(row(dot, status).apply { setPadding(0, dp(12), 0, 0) })
        elderChips = LinearLayout(context).apply { setPadding(0, dp(10), 0, 0) }
        addView(elderChips)
        showElders()
    }

    /** 살피는 어르신을 고른다. 어머니 외의 어르신은 예시 데이터다. */
    private fun showElders() {
        status.text = "${elder.name} 폰 연결됨"
        elderChips.removeAllViews()
        ELDERS.forEach { option ->
            elderChips.addView(
                chip(option.name, selected = option == elder) {
                    elder = option
                    showElders()
                    show(tab)
                },
            )
        }
    }

    private fun show(next: Tab) {
        tab = next
        val tab = next
        nav.removeAllViews()
        Tab.entries.forEach { item ->
            val color = if (item == tab) Palette.PRIMARY else Palette.MUTED
            nav.addView(
                column().apply {
                    gravity = Gravity.CENTER
                    minimumHeight = dp(64)
                    contentDescription = item.title
                    addView(icon(item.icon, color, 26))
                    addView(label(item.title, color = color, bold = item == tab).apply { gravity = Gravity.CENTER })
                    setOnClickListener { show(item) }
                    fillWidth()
                },
            )
        }
        when (tab) {
            Tab.HOME -> showHome()
            Tab.RECORDS -> showRecords()
            Tab.SETTINGS -> showSuggestion()
        }
    }

    /** 시안 10쪽: 이번 주 리포트. */
    private fun showHome() {
        content.removeAllViews()
        content.addView(label("이번 주도 잘 사용하셨어요", sp = 26f, color = Palette.INK))
        content.addView(weekCard())
        content.addView(
            LinearLayout(this).apply {
                setPadding(0, dp(10), 0, 0)
                addView(statTile("자동 해결", "${elder.helped}건").apply { fillWidth() })
                addView(View(context), LinearLayout.LayoutParams(dp(10), 0))
                addView(statTile("가족 도움", "${elder.family}건").apply { fillWidth() })
            },
        )
        content.addView(label("가장 최근 도움", color = Palette.INK, bold = true).apply { setPadding(0, dp(20), 0, 0) })
        val latest = items().first()
        content.addView(
            listRow(latest.icon, Palette.ACCENT, Palette.SOFT, latest.title, "${latest.day} ${latest.time} · ${latest.outcome}").apply {
                addView(icon(R.drawable.ic_chevron, Palette.MUTED))
                setOnClickListener { show(Tab.RECORDS) }
            },
        )
    }

    private fun weekCard(): LinearLayout = column(padding = 18).apply {
        background = rounded(Palette.DEEP, 4)
        layoutParams = spaced(14)
        addView(label("10월 21일 — 27일 (예시)", color = Palette.SOFT))
        val headline = "${elder.helped + elder.family}번 중 ${elder.helped}번을\n머고가 도와드렸어요"
        val start = headline.lastIndexOf("${elder.helped}번")
        addView(
            label("", sp = 24f, color = Color.WHITE, bold = true).apply {
                text = SpannableString(headline).apply {
                    setSpan(ForegroundColorSpan(Palette.LIME), start, start + 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                setPadding(0, dp(10), 0, dp(14))
            },
        )
        addView(
            LinearLayout(context).apply {
                gravity = Gravity.BOTTOM
                elder.bars.forEachIndexed { day, height ->
                    addView(
                        View(context).apply { setBackgroundColor(if (day in elder.busyDays) Palette.LIME else Palette.BAR) },
                        LinearLayout.LayoutParams(0, dp(height), 1f).apply { marginEnd = if (day < elder.bars.lastIndex) dp(10) else 0 },
                    )
                }
            },
        )
    }

    private fun statTile(name: String, value: String): LinearLayout {
        val title = label(name, color = Palette.BODY)
        return row(title, label(value, sp = 22f, color = Palette.PRIMARY, bold = true), middle = title).apply {
            background = rounded(Color.WHITE, 4)
            setPadding(dp(16), dp(18), dp(16), dp(18))
        }
    }

    /** 시안 11쪽: 도움 기록. */
    private fun showRecords() {
        content.removeAllViews()
        content.addView(label("도움 기록", sp = 26f, color = Palette.INK))

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

        val shown = items().filter {
            when (filter) {
                Filter.ALL -> true
                Filter.AUTO -> it.outcome == AUTO_OUTCOME
                Filter.REVIEW -> it.outcome != AUTO_OUTCOME
            }
        }
        shown.groupBy { it.day }.forEach { (day, group) ->
            content.addView(label(day, color = Palette.BODY).apply { setPadding(0, dp(18), 0, 0) })
            group.forEach { item ->
                val alert = item.outcome == NOTIFIED_OUTCOME
                content.addView(
                    listRow(
                        item.icon, if (alert) Palette.ALERT else Palette.ACCENT, if (alert) Palette.ALERT_TINT else Palette.SOFT,
                        item.title, item.time,
                    ).apply {
                        addView(label(item.outcome, sp = 16f, color = if (alert) Palette.ALERT else Palette.ACCENT, bold = true))
                    },
                )
            }
        }
        if (shown.isEmpty()) content.addView(label("해당하는 기록이 없어요.").apply { setPadding(0, dp(16), 0, 0) })
    }

    /** 시안 12쪽: 재발 방지 제안. 예시 화면이라 실제 설정은 바꾸지 않는다. */
    private fun showSuggestion() {
        content.removeAllViews()
        content.addView(label("더 편하게 바꿔볼까요?", sp = 26f, color = Palette.INK))
        content.addView(
            column(padding = 18).apply {
                background = rounded(Palette.SOFT, 4)
                layoutParams = spaced(14)
                addView(
                    FrameLayout(context).apply {
                        background = rounded(Palette.PRIMARY, 24)
                        addView(label("✦", sp = 22f, color = Color.WHITE), FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
                        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                    },
                )
                addView(label("이번 주 발견 (예시)", color = Palette.BODY).apply { setPadding(0, dp(12), 0, dp(4)) })
                addView(label("영상 자동재생 때문에\n3번 어려움을 겪으셨어요", sp = 22f, color = Palette.INK, bold = true))
                addView(label("자동재생을 끄면 같은 문제가\n다시 생기는 것을 줄일 수 있어요.").apply { setPadding(0, dp(8), 0, 0) })
            },
        )
        val name = label("동영상 자동재생", sp = 20f, color = Palette.INK, bold = true)
        content.addView(
            column(padding = 18).apply {
                background = rounded(Color.WHITE, 4)
                layoutParams = spaced(10)
                addView(label("변경할 설정", color = Palette.MUTED))
                addView(row(name, greenSwitch(true), middle = name).apply { setPadding(0, dp(10), 0, dp(10) ) })
                addView(label("다음 영상이 저절로 재생되지 않아요"))
            },
        )
        val notice = label("${elder.name}께도 변경 내용을 알려드려요")
        content.addView(row(icon(R.drawable.ic_shield, Palette.ACCENT), notice, middle = notice).apply { setPadding(dp(4), dp(14), 0, dp(4)) })
        content.addView(bigButton("${elder.name}와 함께 변경하기") { demoOnly() })
        content.addView(bigButton("지금은 괜찮아요", filled = false) { show(Tab.HOME) })
        content.addView(
            column(padding = 18).apply {
                background = rounded(Color.WHITE, 4)
                layoutParams = spaced(14)
                addView(label("함께 보는 가족 (예시)", color = Palette.INK, bold = true))
                FAMILY.forEach { addView(label(it).apply { setPadding(0, dp(6), 0, 0) }) }
                addView(label("어르신이 동의한 가족만 볼 수 있어요.", color = Palette.MUTED).apply { setPadding(0, dp(10), 0, 0) })
            },
        )
        content.addView(
            bigButton("머고 설정", filled = false) { startActivity(Intent(this, SettingsActivity::class.java)) },
        )
        content.addView(
            bigButton("권한 다시 보기", filled = false) {
                startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_STEP, 2))
            },
        )
    }

    private fun demoOnly() = Toast.makeText(this, "예시 화면이라 실제 설정은 바꾸지 않아요", Toast.LENGTH_LONG).show()

    private fun listRow(iconRes: Int, tint: Int, fill: Int, title: String, sub: String): LinearLayout {
        val text = column().apply {
            addView(label(title, color = Palette.INK, bold = true))
            addView(label(sub, sp = 16f, color = Palette.MUTED))
        }
        return row(iconCircle(iconRes, tint, fill), text, middle = text).apply {
            background = rounded(Color.WHITE, 4)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            minimumHeight = dp(72)
            layoutParams = spaced(10)
        }
    }

    private fun spaced(top: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    private fun items(): List<Item> = (if (elder.ownPhone) realItems() else emptyList()) + elder.items

    private fun realItems(): List<Item> {
        val time = SimpleDateFormat("a h:mm", Locale.KOREAN)
        return log.all().asReversed().map {
            val outcome = when {
                it.resolved -> AUTO_OUTCOME
                it.notified -> NOTIFIED_OUTCOME
                it.outcome == "guided" -> "안내함"
                else -> "확인 필요"
            }
            Item(dayName(it.ts), it.summary.ifBlank { it.situation }, time.format(Date(it.ts)), outcome, iconFor(it.recipe))
        }
    }

    private fun dayName(ts: Long): String {
        val day = Calendar.getInstance().apply { timeInMillis = ts }
        val today = Calendar.getInstance()
        val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        fun Calendar.same(other: Calendar) = get(Calendar.YEAR) == other.get(Calendar.YEAR) &&
            get(Calendar.DAY_OF_YEAR) == other.get(Calendar.DAY_OF_YEAR)
        return when {
            day.same(today) -> "오늘"
            day.same(yesterday) -> "어제"
            else -> SimpleDateFormat("M월 d일", Locale.KOREAN).format(Date(ts))
        }
    }

    private fun iconFor(recipe: String): Int = when (recipe) {
        RecipeIds.SET_VOLUME -> R.drawable.ic_volume
        RecipeIds.OPEN_PREVIOUS_APP -> R.drawable.ic_home
        RecipeIds.RESTORE_VIDEO -> R.drawable.ic_play
        RecipeIds.BRIGHTEN_SCREEN, RecipeIds.INCREASE_TEXT_SIZE -> R.drawable.ic_sun
        else -> R.drawable.ic_shield
    }

    private companion object {
        const val AUTO_OUTCOME = "자동 해결"
        const val NOTIFIED_OUTCOME = "가족에게 알림"

        val FAMILY = listOf("민 · 어머니, 아버지를 함께 봐요", "지 · 어머니를 함께 봐요")

        val ELDERS = listOf(
            Elder(
                "어머니", helped = 4, family = 1, bars = listOf(24, 39, 20, 47, 30, 16, 38), busyDays = setOf(3, 6),
                items = listOf(
                    Item("오늘 (예시)", "소리를 다시 켰어요", "오전 10:24", AUTO_OUTCOME, R.drawable.ic_volume),
                    Item("오늘 (예시)", "보던 앱으로 돌아갔어요", "오전 8:12", AUTO_OUTCOME, R.drawable.ic_home),
                    Item("어제 (예시)", "이전 영상을 다시 찾았어요", "오후 7:42", AUTO_OUTCOME, R.drawable.ic_play),
                    Item("어제 (예시)", "의심스러운 광고를 닫았어요", "오후 2:18", NOTIFIED_OUTCOME, R.drawable.ic_shield),
                ),
                ownPhone = true,
            ),
            Elder(
                "아버지", helped = 3, family = 0, bars = listOf(12, 30, 18, 24, 10, 36, 16), busyDays = setOf(5),
                items = listOf(
                    Item("오늘 (예시)", "화면을 밝게 했어요", "오전 9:05", AUTO_OUTCOME, R.drawable.ic_sun),
                    Item("어제 (예시)", "보던 앱으로 돌아갔어요", "오후 6:30", AUTO_OUTCOME, R.drawable.ic_home),
                    Item("어제 (예시)", "소리를 다시 켰어요", "오전 11:10", AUTO_OUTCOME, R.drawable.ic_volume),
                ),
            ),
        )
    }
}
