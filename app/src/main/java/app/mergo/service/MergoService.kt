package app.mergo.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import app.mergo.BuildConfig
import app.mergo.ai.AndroidVoiceIO
import app.mergo.ai.MergoAi
import app.mergo.ai.WakeListener
import app.mergo.ai.WakeOptions
import app.mergo.core.LiveScreen
import app.mergo.core.MergoController
import app.mergo.detect.Cooldown
import app.mergo.detect.DetectorConfig
import app.mergo.detect.StuckDetector
import app.mergo.memory.BaselineStore
import app.mergo.memory.FamilyLog
import app.mergo.memory.TimelineBuffer
import app.mergo.model.Clock
import app.mergo.model.EventKind
import app.mergo.model.FailReason
import app.mergo.model.Marker
import app.mergo.model.Recipe
import app.mergo.model.RecipeIds
import app.mergo.model.RecipeOutcome
import app.mergo.model.ScreenAgent
import app.mergo.model.ScreenEvent
import app.mergo.recipe.OpenInternetPanel
import app.mergo.recipe.OpenPreviousApp
import app.mergo.recipe.AutoBrightness
import app.mergo.recipe.BrightenScreen
import app.mergo.recipe.IncreaseTextSize
import app.mergo.recipe.DisplayRescueMonitor
import app.mergo.recipe.RestoreVideo
import app.mergo.recipe.SetVolume
import app.mergo.recipe.SkipAd
import app.mergo.settings.MergoSettings
import app.mergo.ui.OverlayUi
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * 머고의 접근성 서비스. 이벤트를 텍스트 기록으로 바꿔 감지기에 넣고, 레시피가 화면을 조작하는 창구가 된다.
 * 부품 조립도 여기서 한다. 유예 듣기는 호출어로 대체해서 쓰지 않는다.
 */
class MergoService : AccessibilityService() {
    private val scope = MainScope()
    private val clock = Clock.SYSTEM
    /** 레시피·에이전트용. 노드 번호가 이벤트 처리 때문에 바뀌지 않도록 이벤트용과 따로 둔다. */
    private lateinit var screen: AccessibilityScreen
    private lateinit var eventScreen: AccessibilityScreen
    private lateinit var device: AndroidDevice
    private lateinit var display: AndroidDisplaySettings
    private lateinit var displayPreferences: MergoSettings
    private val displayMonitor = DisplayRescueMonitor()
    private var lowBrightnessCheck: Job? = null
    private lateinit var media: MediaTitleSource
    private lateinit var overlay: OverlayUi
    private lateinit var voice: AndroidVoiceIO
    private var wake: WakeListener? = null
    private lateinit var timeline: TimelineBuffer
    private lateinit var controller: MergoController

    private var foreground: String? = null
    private var ignored: Set<String> = emptySet()
    private var navBarPkgs: Set<String> = emptySet()
    private var lastKeyTs = 0L
    private var lastVolume = -1
    private var lastSnapshotTs = 0L
    private var lastMediaTitle: String? = null
    private var volumeObserver: ContentObserver? = null
    private var displayObserver: ContentObserver? = null
    private var volumeReceiver: BroadcastReceiver? = null
    private var debugReceiver: BroadcastReceiver? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 볼륨·뒤로 가기 키를 받으려면 키 필터 플래그가 켜져 있어야 한다. XML 설정이 빠져도 확실히 켠다.
        serviceInfo = serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS }
        log("serviceInfo flags=0x${Integer.toHexString(serviceInfo.flags)} caps=${serviceInfo.capabilities}")
        screen = AccessibilityScreen(this)
        eventScreen = AccessibilityScreen(this)
        device = AndroidDevice(this) { foreground }
        display = AndroidDisplaySettings(this)
        media = MediaTitleSource(this)
        timeline = TimelineBuffer(clock)

        // 기본 런처 하나만 홈으로 본다. HOME 처리 앱 전체를 쓰면 설정 앱(FallbackHome) 같은 것이 섞인다.
        val launchers = setOfNotNull(
            packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY,
            )?.activityInfo?.packageName,
        )
        val imes = getSystemService(InputMethodManager::class.java).enabledInputMethodList.map { it.packageName }.toSet()
        val config = DetectorConfig(
            launcherPkgs = launchers - packageName,
            ignoredPkgs = DetectorConfig().ignoredPkgs + imes + packageName,
        )
        ignored = config.ignoredPkgs
        navBarPkgs = config.launcherPkgs + "com.android.systemui"
        log("시작: launchers=${config.launcherPkgs} ignored=${config.ignoredPkgs}")

        val cooldown = Cooldown()
        val baseline = BaselineStore(
            clock, ignoredPkgs = config.launcherPkgs + config.ignoredPkgs, titleByCountdown = config.requireCountdown,
        )
        val detector = StuckDetector(clock, config, cooldown) { baseline.current?.mediaTitle }
        val settings = MergoSettings(this)
        displayPreferences = settings
        wake = MergoAi.speechToText()?.let { speech ->
            WakeListener(this, settings, speech, ::log) { command -> scope.launch { controller.onHelp(command) } }
        }
        voice = AndroidVoiceIO(this) { busy -> wake?.micBusy(busy) }
        val agent: ScreenAgent = MergoAi.screenAgent(screen, voice)
        val currentTitle = { currentVideoTitle() }
        val recipes: Map<String, Recipe> = listOf(
            RestoreVideo(screen, device, currentTitle, agent),
            SetVolume(device, baseline),
            OpenPreviousApp(device, baseline, screen),
            SkipAd(screen, voice),
            OpenInternetPanel(screen, device),
            BrightenScreen(display) { settings.preferredBrightness },
            IncreaseTextSize(display) { settings.preferredFontScale },
            object : Recipe {
                override val id = RecipeIds.SCREEN_AGENT
                // 에이전트의 '완료'는 LLM의 선언일 뿐이다. 목표별 검증 조건이 생기기 전까지는 검증 불가로 둔다.
                override suspend fun run(params: Map<String, String>) =
                    if (agent.achieve(params["goal"] ?: "화면 문제 해결")) RecipeOutcome.Unverifiable("에이전트 완료 선언만 있음")
                    else RecipeOutcome.Failed(FailReason.AGENT_GAVE_UP)
            },
        ).associateBy { it.id }

        overlay = OverlayUi(this, onHelp = { controller.launchHelp() })
        controller = MergoController(
            scope, clock, timeline, baseline, detector, cooldown, recipes,
            brain = MergoAi.brain(voice, settings), voice = voice, overlay = overlay, settings = settings,
            familyLog = FamilyLog(File(filesDir, "family_log.json")),
            liveVolume = { device.volume() }, liveOnline = { device.isOnline() },
            liveScreen = { LiveScreen(screen.currentPackage(), screen.nodes().flatMap { listOfNotNull(it.text, it.desc) }) },
        )
        // 서비스가 앱 사용 도중에 (재)시작돼도 지금 앱을 알도록 활성 창에서 읽어 둔다.
        foreground = screen.currentPackage()?.takeIf { it !in ignored }
        overlay.showHelpButton()
        wake?.start()
        watchDeviceState()
        displayMonitor.onSnapshot(display.read(), settings.displayAutoRescue, settings.preferredBrightness, settings.preferredFontScale)
        watchDisplayState()
        checkLowBrightness()
        if (BuildConfig.DEBUG) registerDebugHear()
        controller.onDeviceState(device.volume(), device.isOnline(), userAction = false)

        scope.launch {
            while (isActive) {
                delay(1_000)
                pollMediaTitle()
                sampleYouTube()
                controller.tick()
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED && isNavBack(pkg, event)) {
            // 3버튼 내비게이션의 뒤로 가기는 키 이벤트가 아니라 런처·시스템 UI의 클릭으로 온다(에뮬레이터 API 36 확인).
            // 뒤로 가기는 지금 앞에 있는 앱에 대한 동작이라 그 앱의 BACK으로 넘긴다.
            lastKeyTs = clock.now()
            controller.onScreenEvent(ScreenEvent(lastKeyTs, foreground ?: UNKNOWN_PKG, null, emptyList(), true, EventKind.BACK))
            return
        }
        val kind = when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> EventKind.WINDOW_CHANGED
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> EventKind.CONTENT_CHANGED
            AccessibilityEvent.TYPE_VIEW_CLICKED, AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> EventKind.CLICK
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> EventKind.SCROLL
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> EventKind.TOUCH
            else -> return
        }
        // 볼륨 패널·알림창·키보드 같은 창은 '지금 쓰는 앱'으로 보지 않는다.
        if (kind == EventKind.WINDOW_CHANGED && pkg !in ignored) foreground = pkg
        val now = clock.now()
        // 노드 읽기는 메인 스레드에서 비싸다. 창 전환과 0.5초에 한 번의 내용 변화(YouTube)에서만 읽는다.
        var title: String? = null
        var markers = emptySet<Marker>()
        var texts = emptyList<String>()
        val read = pkg !in ignored && when (kind) {
            EventKind.WINDOW_CHANGED -> true
            EventKind.CONTENT_CHANGED -> pkg == YouTubeSignals.PKG && now - lastSnapshotTs >= 500
            else -> false
        }
        if (read) {
            lastSnapshotTs = now
            val nodes = eventScreen.nodes()
            texts = nodes.mapNotNull { it.text }.filter { it.length in 2..60 }.distinct().take(10)
            if (pkg == YouTubeSignals.PKG) {
                markers = YouTubeSignals.markers(nodes)
                title = currentVideoTitle(nodes)
            }
        } else if (kind == EventKind.CONTENT_CHANGED) {
            return
        }
        val user = kind in USER_KINDS
        controller.onScreenEvent(ScreenEvent(now, pkg, title, texts, user, kind, markers))
    }

    /** 내비게이션 바의 뒤로 가기 단추. Pixel은 런처가, AOSP·삼성은 시스템 UI가 그리고 viewId는 둘 다 `:id/back`이다. */
    private fun isNavBack(pkg: String, event: AccessibilityEvent): Boolean {
        if (pkg !in navBarPkgs) return false
        val id = runCatching { event.source?.viewIdResourceName }.getOrNull() ?: return false
        return id.endsWith(":id/back")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val kind = when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> EventKind.BACK
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH -> EventKind.KEY
            else -> return false
        }
        lastKeyTs = clock.now()
        log("키 ${KeyEvent.keyCodeToString(event.keyCode)} → $kind")
        controller.onScreenEvent(ScreenEvent(lastKeyTs, foreground ?: UNKNOWN_PKG, null, emptyList(), true, kind))
        return false // 키는 그대로 앱에 전달한다.
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (::overlay.isInitialized) overlay.removeAll()
        if (::voice.isInitialized) voice.close()
        wake?.shutdown()
        volumeObserver?.let { contentResolver.unregisterContentObserver(it) }
        displayObserver?.let { contentResolver.unregisterContentObserver(it) }
        volumeReceiver?.let { runCatching { unregisterReceiver(it) } }
        debugReceiver?.let { runCatching { unregisterReceiver(it) } }
        netCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        scope.cancel()
        super.onDestroy()
    }

    /** 영상 제목: MediaSession이 있으면 그것을 쓰고(정확·전체 제목), 없으면 노드에서 찾는다. */
    private fun currentVideoTitle(nodes: List<app.mergo.model.NodeSnapshot>? = null): String? =
        media.read()?.title ?: YouTubeSignals.title(nodes ?: eventScreen.nodes())

    /** 화면 이벤트 없이 제목만 바뀌는 경우(자동재생)를 잡으려고 MediaSession 제목을 주기적으로 본다. */
    private fun pollMediaTitle() {
        if (foreground != YouTubeSignals.PKG) return
        val now = media.read() ?: return
        val t = now.title ?: return
        if (t == lastMediaTitle) return
        lastMediaTitle = t
        val markers = if (now.playing) setOf(Marker.VIDEO_PLAYER) else emptySet()
        controller.onScreenEvent(ScreenEvent(clock.now(), YouTubeSignals.PKG, t, emptyList(), false, EventKind.CONTENT_CHANGED, markers))
    }

    /**
     * YouTube가 화면 변화 이벤트를 안 보내도(정지 화면의 광고 등) 1초마다 신호를 읽는다.
     * R4(건너뛰기 5초)가 이벤트 간격에 흔들리지 않게 하기 위해서다.
     */
    private fun sampleYouTube() {
        if (foreground != YouTubeSignals.PKG || controller.automating) return
        val nodes = eventScreen.nodes()
        if (nodes.isEmpty()) return
        val markers = YouTubeSignals.markers(nodes)
        if (markers.isEmpty()) return
        controller.onScreenEvent(ScreenEvent(clock.now(), YouTubeSignals.PKG, currentVideoTitle(nodes), emptyList(), false, EventKind.CONTENT_CHANGED, markers))
    }

    private fun watchDeviceState() {
        val main = Handler(Looper.getMainLooper())
        val obs = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) {
                // 볼륨은 사용자나 머고만 바꾼다(YouTube는 스스로 바꾸지 않는다). 키 이벤트가 안 오는 기기도 있어
                // (adb·가상 키는 접근성 키 필터를 거치지 않음) 머고가 바꾼 게 아니면 사용자 동작으로 본다.
                val byUser = !controller.automating || clock.now() - lastKeyTs <= 2_000
                val v = device.volume()
                if (v != lastVolume) log("볼륨 $lastVolume → $v (사용자=$byUser)")
                lastVolume = v
                controller.onDeviceState(v, device.isOnline(), byUser)
            }
        }
        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, obs)
        volumeObserver = obs
        // 기기에 따라 Settings.System 감시로는 볼륨 변화가 안 오므로 시스템 방송도 함께 받는다.
        val rc = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = obs.onChange(false)
        }
        registerReceiver(rc, IntentFilter(VOLUME_CHANGED_ACTION), Context.RECEIVER_EXPORTED)
        volumeReceiver = rc
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { post() }
            override fun onLost(network: Network) { post() }
            private fun post() { main.post { controller.onDeviceState(device.volume(), device.isOnline(), false) } }
        }
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(cb)
        netCallback = cb
    }

    /** 값이 멈춘 뒤에도 너무 어두우면 먼저 밝게 하고 어르신께 묻는다. 어르신이 싫다고 하면 이 기기에서 끈다. */
    private fun checkLowBrightness() {
        lowBrightnessCheck?.cancel()
        if (!tooDark()) return
        lowBrightnessCheck = scope.launch {
            delay(AutoBrightness.SETTLE_MS)
            controller.launchAutomaticBrightness(AutoBrightness.COMFORT, ::tooDark) {
                displayPreferences.autoBrightness = false
            }
        }
    }

    private fun tooDark() =
        displayPreferences.autoBrightness && display.canWrite() && AutoBrightness.isTooDark(display.read())

    /** 밝기는 기본으로 지키고, 저장값으로의 복구는 가족이 켠 경우에만 한다. 저장값 복구는 급격한 하락만 살핀다. */
    private fun watchDisplayState() {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                checkLowBrightness()
                val prefs = displayPreferences
                val targets = displayMonitor.onSnapshot(
                    display.read(), prefs.displayAutoRescue && display.canWrite(),
                    prefs.preferredBrightness, prefs.preferredFontScale,
                ).filterNot { it == RecipeIds.BRIGHTEN_SCREEN && !prefs.autoBrightness }
                if (targets.isEmpty()) return
                scope.launch {
                    for (recipeId in targets) controller.launchAutomaticDisplayRescue(recipeId) {
                        val current = display.read()
                        prefs.displayAutoRescue && display.canWrite() && when (recipeId) {
                            RecipeIds.BRIGHTEN_SCREEN -> current.automaticBrightness != true &&
                                (current.brightness == null || current.brightness < (prefs.preferredBrightness ?: 0))
                            RecipeIds.INCREASE_TEXT_SIZE -> current.fontScale == null ||
                                current.fontScale < (prefs.preferredFontScale ?: 0f)
                            else -> false
                        }
                    }.join()
                }
            }
        }
        for (key in listOf(Settings.System.SCREEN_BRIGHTNESS, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.FONT_SCALE)) {
            contentResolver.registerContentObserver(Settings.System.getUriFor(key), false, observer)
        }
        displayObserver = observer
    }

    /**
     * 디버그 빌드 전용: 음성 엔진 없이 호출어 뒤에 '들은 문장'을 넣어 도와줘 흐름을 시험한다.
     * `adb shell am broadcast -a app.mergo.DEBUG_HEAR --es text "소리가 안 나" -p app.mergo`
     */
    private fun registerDebugHear() {
        val rc = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == DEBUG_DUMP) {
                    dumpForDebug()
                    return
                }
                if (intent.action == DEBUG_REPLAY) {
                    val name = intent.getStringExtra("name") ?: return
                    wake?.replay(File(getExternalFilesDir(null), name))
                    return
                }
                if (intent.action == DEBUG_WAKE) {
                    val options = WakeOptions.without(intent.getStringExtra("off"))
                    wake?.options = options
                    log("호출어 옵션: $options")
                    return
                }
                if (intent.action == DEBUG_TTS) {
                    val said = intent.getStringExtra("text") ?: return
                    voice.saveSpeech(said, File(getExternalFilesDir(null), intent.getStringExtra("name") ?: "speech.wav"))
                    return
                }
                val text = intent.getStringExtra("text") ?: return
                log("디버그 듣기: $text")
                scope.launch {
                    controller.onHelp(text)
                }
            }
        }
        registerReceiver(rc, IntentFilter(DEBUG_HEAR).apply {
            addAction(DEBUG_DUMP)
            addAction(DEBUG_WAKE)
            addAction(DEBUG_REPLAY)
            addAction(DEBUG_TTS)
        }, Context.RECEIVER_EXPORTED)
        debugReceiver = rc
    }

    /** 도와줘 버튼을 길게 누르면 사전 검증용으로 화면 노드와 최근 기록을 파일로 남긴다. */
    private fun dumpForDebug() {
        val dir = getExternalFilesDir(null) ?: filesDir
        val stamp = clock.now()
        File(dir, "nodes-$stamp.txt").writeText("pkg=$foreground media=${media.read()}\n" + eventScreen.dumpText())
        File(dir, "timeline-$stamp.json").writeText(timeline.toJson())
        log("덤프 저장: ${dir.absolutePath} (nodes-$stamp.txt, timeline-$stamp.json)")
        overlay.done("기록을 저장했어요", "adb pull ${dir.absolutePath}")
    }

    private fun log(msg: String) = Log.i("Mergo", msg)

    private companion object {
        const val UNKNOWN_PKG = "unknown"
        const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        const val DEBUG_HEAR = "app.mergo.DEBUG_HEAR"
        /** 디버그: 화면을 건드리지 않고 노드·기록 덤프. `adb shell am broadcast -a app.mergo.DEBUG_DUMP -p app.mergo` */
        const val DEBUG_DUMP = "app.mergo.DEBUG_DUMP"
        /** 디버그: 호출어 듣기 방법을 끈다. `--es off "second,trim,end,warm"` */
        const val DEBUG_WAKE = "app.mergo.DEBUG_WAKE"
        /** 디버그: 앱 외부 저장소의 16kHz WAV를 마이크 대신 듣는다. `--es name "voice.wav"` */
        const val DEBUG_REPLAY = "app.mergo.DEBUG_REPLAY"
        /** 디버그: 앱의 음성을 파일로 저장한다. `--es text "말" --es name "say.wav"` */
        const val DEBUG_TTS = "app.mergo.DEBUG_TTS"
        val USER_KINDS = setOf(EventKind.CLICK, EventKind.SCROLL, EventKind.TOUCH, EventKind.KEY, EventKind.BACK)
    }
}
