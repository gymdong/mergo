package app.mergo.detect

/** 감지 규칙의 기준값. 숫자는 스펙 §2를 따른다. */
data class DetectorConfig(
    val youtubePkg: String = "com.google.android.youtube",
    val launcherPkgs: Set<String> = emptySet(),
    /** 감지에서 빼는 패키지. 머고 자신의 말풍선, 시스템 UI(알림창 등). */
    val ignoredPkgs: Set<String> = setOf("app.mergo", "com.android.systemui"),
    /**
     * true면 R1이 '다음 동영상' 카운트다운 관측을 증거로 요구한다.
     * 사전 검증에서 카운트다운이 접근성 트리에 잡히지 않으면 false로 바꿔 터치 부재 기준으로 대체한다.
     */
    val requireCountdown: Boolean = true,
    /**
     * R1(자동재생 뒤 되돌리기 제안)을 낼지. 영상을 끝까지 본 뒤 넘어간 것이라 '돌아갈까요?'가 어색해 기본은 끈다(2026-10-30).
     * 감지 판단(자동재생인지 직접 고른 것인지)은 그대로 해서 R3 오조작 구분에 쓴다.
     */
    val suggestAutoplay: Boolean = false,
    /** 광고 건너뛰기 버튼이 보이면 묻지 않고 넘긴다. 광고를 보려는 어르신은 거의 없고 매번 묻는 게 더 번거롭다. */
    val autoSkipAds: Boolean = true,
    val noActionBeforeChangeMs: Long = 2_000L,   // R1: 바뀌기 전 2초 안에 사용자 동작 없음
    val userCausedWindowMs: Long = 5_000L,       // R3: 이 시간 안의 동작이면 사용자가 바꾼 것
    val countdownLookbackMs: Long = 15_000L,     // R1: 바뀌기 직전 15초 안 카운트다운
    val r1WaitMs: Long = 10_000L,                // R1: 바뀐 뒤 10초 무동작
    val r2WaitMs: Long = 20_000L,                // R2: 홈에서 20초
    val r2RecentVideoMs: Long = 10_000L,         // R2: 홈으로 나가기 직전까지 영상을 보던 중
    val r3WindowMs: Long = 30_000L,              // R3: 바뀐 뒤 30초 관찰
    val r3BackCount: Int = 2,                    // R3: 뒤로 가기 2회 이상
    val r3BackWithinMs: Long = 3_000L,           //     3초 안에
    val r3ClickCount: Int = 3,                   // R3: 또는 같은 앱에서 3번 이상 연달아 누름
    val r3ClickWithinMs: Long = 3_000L,          //     3초 안에
    /**
     * R5: 이 앱들에서 누른 직후 브라우저·스토어가 열리면 오조작으로 본다(광고·설명 링크를 잘못 누름).
     * 카톡처럼 링크를 일부러 여는 앱은 넣지 않는다. 다른 앱에서 열린 경우는 어르신이 도와줘를 부르면 된다.
     */
    val linkSourcePkgs: Set<String> = setOf("com.google.android.youtube"),
    val linkTargetPkgs: Set<String> = setOf(
        "com.android.chrome", "com.sec.android.app.sbrowser", "com.naver.whale", "org.mozilla.firefox",
        "com.microsoft.emmx", "com.android.browser", "com.android.vending",
    ),
    val linkTouchWithinMs: Long = 3_000L,        // R5: 누르고 이 시간 안에 넘어감
    val linkWaitMs: Long = 5_000L,               // R5: 넘어간 화면에 5초 머묾(곧바로 돌아오면 제안하지 않음)
    val r4WaitMs: Long = 5_000L,                 // R4: 건너뛰기 버튼이 5초 보이고 무동작
    val r4GoneMs: Long = 2_000L,                 // R4: 이만큼 안 보이면 광고가 끝난 것으로 봄
)
