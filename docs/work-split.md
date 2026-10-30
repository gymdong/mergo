# 머고 MVP 개발 분담 (2인)

기준 스펙: [`docs/mergo-mvp-spec.md`](./mergo-mvp-spec.md). 스펙과 이 문서가 다르면 스펙이 우선한다.

## 한눈에 보기

| | **A · 기기 담당** | **B · AI 담당** |
|---|---|---|
| 한 줄 역할 | 화면을 읽고, 막힘을 감지하고, 대신 조작해 되돌린다 | 말을 듣고 판단하고, 레시피 밖 상황은 화면을 보며 직접 조작한다 |
| 소유 패키지 | `service/` `memory/` `detect/` `ui/` `recipe/` `core/` | `ai/` `agent/` `settings/` `report/` `testpages/` |
| 데모 책임 | S1 · S2 · S3 · S4 · S11 · S8 | S6 · Brain 판단(모든 음성 경로) · 호출어 · 가족 리포트 |
| 사전 검증 | YouTube `uiautomator dump`(제목, 카운트다운, 이전 버튼, 탭 이벤트, 건너뛰기 버튼) | Gemini 키와 모델 ID, 한국어 JSON 응답 시험, 에뮬레이터 마이크, S6 페이지 |
| JVM 테스트 | AC-D1~D8, AC-R1, AC-R2 | AC-B5, AC-B6, AC-A1, AC-A2 |

**공동 소유:** `model/`(계약)과 `MainActivity`(권한 안내)는 두 사람이 함께 소유한다. 고칠 때는 반드시 상대에게 말한다.

---

## 0단계 · 함께 (첫 45분) — 계약 확정과 뼈대

1. **A**: Gradle 프로젝트 뼈대를 만든다(Wrapper, `app` 모듈, minSdk 29, target 36, `local.properties`는 gitignore). `main`에 push한다.
2. **둘이 같이**: `model/` 패키지의 아래 계약을 그대로 만들어 커밋한다. 이후에는 **각자 Fake 구현으로 개발**한다.

```kotlin
// model/Events.kt — A가 채우고 B가 읽음
data class ScreenEvent(val ts: Long, val pkg: String, val title: String?,
                       val texts: List<String>, val userAction: Boolean, val kind: EventKind)
enum class EventKind { WINDOW_CHANGED, CONTENT_CHANGED, CLICK, SCROLL, KEY, BACK }
data class NodeSnapshot(val id: Int, val text: String?, val desc: String?,
                        val clickable: Boolean, val bounds: Rect4, val visible: Boolean)
data class Rect4(val l: Int, val t: Int, val r: Int, val b: Int)
data class Baseline(val pkg: String, val mediaTitle: String?, val volume: Int,
                    val online: Boolean, val updatedAt: Long)

// model/Recipes.kt — A가 구현, B의 Brain이 id로 고름
interface Recipe { val id: String; suspend fun run(params: Map<String, String>): Boolean }
// id: restore_video, set_volume, open_previous_app, open_internet_panel, skip_ad, screen_agent, ask

// model/Brain.kt — B가 구현, A의 Orchestrator가 호출
data class BrainContext(val utterance: String?, val diff: List<String>, val timeline: List<String>,
                        val current: Baseline, val screenHasAd: Boolean)
data class BrainDecision(val diagnosis: String, val recipe: String,
                         val params: Map<String, String>, val say: String, val confirm: Boolean)
interface Brain { suspend fun decide(ctx: BrainContext): BrainDecision }

// model/Voice.kt — B가 구현, A의 OverlayUi/Orchestrator가 호출
enum class YesNo { YES, NO, NONE }
interface VoiceIO {
    suspend fun speak(text: String)
    suspend fun listen(timeoutMs: Long = 5000): String?        // 자유 발화
    suspend fun listenYesNo(timeoutMs: Long = 5000): YesNo      // 긍정/부정어 판정
}

// model/Screen.kt — A가 구현(접근성), B의 ScreenAgent가 호출
interface ScreenReader {
    fun nodes(): List<NodeSnapshot>        // 보이는 노드, 번호 부여
    fun click(nodeId: Int): Boolean        // 실패하면 bounds 중심 탭 제스처
    fun back(): Boolean
}

// model/Agent.kt — B가 구현, A의 레시피 체인 마지막 단계에서 호출
interface ScreenAgent { suspend fun achieve(goal: String): Boolean }

// model/Clock.kt — 둘 다 사용 (JVM 테스트용)
fun interface Clock { fun now(): Long }
```

3. **연결 지점(Orchestrator, A 소유)**: `core/MergoController`가 아래 두 흐름을 맡는다. B의 `Brain`·`VoiceIO`·`ScreenAgent`는 생성자로 주입받는다.
   - 감지 흐름: `StuckDetector`의 제안 → 말풍선과 `VoiceIO.listenYesNo` → 레시피 체인 → `Verifier` → 실패하면 FamilyLog
   - 도와줘 흐름: 도와줘 버튼 → `VoiceIO.listen` → `Brain.decide` → 레시피
4. **Git 규칙**: 브랜치는 `feat/device`(A)와 `feat/ai`(B)로 나눈다. **점검 시점마다 `main`에 merge**하고, `model/` 변경은 단독으로 PR한다.

---

## 1단계 · S1 관통 (메인 시나리오)

| A · 기기 | B · AI |
|---|---|
| `MergoService`: 이벤트 수신, 키 필터, 이벤트를 `ScreenEvent`로 변환, Logcat `Mergo` | `VoiceIO`: SpeechRecognizer ko-KR, TTS, `listenYesNo`(응/네/예/그래/어/맞아/좋아 · 아니/아니요/됐어/싫어) |
| `TimelineBuffer`: 10분 원형 버퍼, 중복 제거, 디버그용 JSON 덤프 | `Brain` 키워드 규칙(순수 함수, 사투리 포함) + **발화 테스트셋 38개**(AC-B5) |
| `BaselineStore`: 5초 유지·영상 30초 시청 기준, 스택 3개(AC-D7) | `LlmClient` 인터페이스, `GeminiClient`(기본), `ClaudeClient`(교체용), 타임아웃 8초, "잠시만요" 1.5초 |
| `StuckDetector` R1(카운트다운 우선 + 대체 모드)(AC-D1, D2) | 응답 파서: 코드펜스, 깨진 JSON → 키워드/ask로 대체(AC-B6) |
| `OverlayUi`: 도와줘 버튼(64dp+), 말풍선 [네]/[아니요](18sp+) | `FakeBrain`·`FakeVoiceIO`를 먼저 A에게 넘김(1단계 시작 30분 안) |
| `ScreenReader` 구현(노드 스냅샷, 클릭, 탭 제스처) — **B의 ScreenAgent가 쓰므로 1단계 안에 넘김** | |
| `RestoreVideo`(S1 순서: 이전 버튼 → 기록 → 에이전트), `Verifier` | |
| `MergoController`: 감지 흐름 연결 | |

**✅ 점검 1:** 에뮬레이터에서 S1이 끝까지 1회 성공해야 한다(말풍선 → "응" → 이전 영상 재생). Brain 테스트셋은 JVM에서 통과해야 한다. 이 시점에 `main`에 merge한다.

---

## 2단계 · S6 에이전트 + 쉬운 레시피

| A · 기기 | B · AI |
|---|---|
| `SetVolume`(S4, 베이스라인 0이면 최대의 절반) | `agent/ScreenSummarizer`: 보이는 노드 최대 60개(AC-A2) |
| R2 + `OpenPreviousApp`(S3, `<queries>` LAUNCHER) | `agent/SafetyGuard`: 위험 키워드 차단, 5단계·30초(AC-A1) |
| 도와줘 흐름 연결: 버튼 → listen → `Brain.decide` → 레시피, 선택지 버튼 4개 | `ScreenAgent` 루프: 요약 → LLM → 행동 → 1초 뒤 재요약 |
| 레시피 체인 실패 처리: 연 화면 닫기, "가족에게 알릴까요?", FamilyLog(AC-R1) | `testpages/s6.html`(+ `?hard=1`), `python3 -m http.server`, `10.0.2.2:8000` |
| | 목표 단위 1회 확인 문구(`confirm`) |

**✅ 점검 2:** S1과 S6이 각각 끝까지 1회 이상 성공하고, S3·S4가 동작해야 한다. **못 미치면 스펙의 자르는 순서를 바로 적용한다.**

---

## 3단계 · 오조작과 광고, 설정

| A · 기기 | B · AI |
|---|---|
| R3 오조작 혼란 감지(AC-D3), R2와 겹치면 R3 우선(AC-D4) | `settings/`: 호출어 텍스트, '확인 없이 실행' 토글(기본 꺼짐) → Controller에 반영 |
| R4 + `SkipAd`(대기 10초, 매처 AC-R2, AC-D8) | F9 후보 되묻기(최대 2번) |
| 거절 쿨다운: 같은 대상 재제안 금지, 규칙 5분(AC-D6). 거절하면 정상 상태 갱신 | `report/`: 가족 리포트 화면(목업 요약 + FamilyLog 목록)(AC-F1) |
| N1~N4 정상 사용 재생 테스트(AC-D5) | Brain 테스트셋에 S11 발화 3개 추가 |

---

## 4단계 · 스트레치 (시간 순서대로, 밀리면 여기부터 자름)

| A · 기기 | B · AI |
|---|---|
| S8 `OpenInternetPanel` + Wi-Fi 토글 클릭, 오프라인 키워드 연결 | 호출어(Vosk 소형 한국어 모델, 문법 제한, STT로 넘기기). 백그라운드 마이크가 안 되면 '앱 화면에서만'으로 축소 |

---

## 통합·인수 테스트·촬영 (기능 동결 이후)

| A | B |
|---|---|
| S1·S2·S3·S4 각 5회, N1·N3 각 5회 → `docs/acceptance.md`에 표로 기록 | S6 5회, S11(광고가 나오는 대로), S8 → 같은 표에 기록 |
| 시연 영상 촬영(에뮬레이터 화면) | 촬영 중 `adb logcat -s Mergo:*` 로그 창, 영상 편집 |

## AI 코딩 도구에 줄 시작 프롬프트 예시

- **A:** "`docs/mergo-mvp-spec.md`의 §1~§3과 `docs/work-split.md`의 A 열을 구현해. `model/` 계약은 바꾸지 말고, Brain/VoiceIO/ScreenAgent는 Fake를 써. 감지 로직은 안드로이드 타입 없이 순수 Kotlin으로 만들고 Clock 주입으로 JVM 테스트를 붙여."
- **B:** "`docs/mergo-mvp-spec.md`의 §4~§5와 `docs/work-split.md`의 B 열을 구현해. `model/` 계약은 바꾸지 말고, ScreenReader는 Fake를 써. Brain 키워드 규칙과 LLM 파서, Agent 안전 검사는 순수 Kotlin으로 만들고 JVM 테스트를 붙여."
