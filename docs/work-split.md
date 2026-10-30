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

1. **A**: Gradle 프로젝트 뼈대를 만든다(Wrapper, `app` 모듈, minSdk 29, target 36, `local.properties`는 gitignore). `dev`에 PR로 올린다.
2. **계약 (확정, `feat/device`에서 작성)**: `app/src/main/java/app/mergo/model/`가 기준이다. 아래는 요약이며, 이후 변경은 반드시 상대에게 알린다.

| 파일 | 내용 | 구현 | 사용 |
|---|---|---|---|
| `Events.kt` | `ScreenEvent`(+`markers`), `EventKind`(+`TOUCH`), `Marker`(카운트다운·건너뛰기·광고·플레이어), `NodeSnapshot`(+`viewId`), `Rect4`, `Baseline` | A | A·B |
| `Recipes.kt` | `Recipe`, `RecipeIds`(restore_video, set_volume, open_previous_app, open_internet_panel, skip_ad, screen_agent, ask) | A | B(Brain이 id 선택) |
| `Brain.kt` | `BrainContext`, `BrainDecision`, `Brain` | **B** | A(Controller) |
| `Voice.kt` | `VoiceIO`(speak, listen, listenYesNo, cancelListening), `YesNo` | **B** | A |
| `Screen.kt` | `ScreenReader`(nodes, click, tap, back, currentPackage), `ScreenAgent`(achieve) | ScreenReader=A, **ScreenAgent=B** | B / A |
| `Settings.kt` | `SettingsSource`(autoExecute, wakeWord) | **B** | A |
| `Clock.kt` | 시간 주입 | - | A·B |
| ~~`Grace.kt`~~ | **삭제(2026-10-30).** 30초 유예 듣기를 폐지하고 호출어 뒤의 말만 도와줘 흐름(`onHelp`)으로 받는다 | — | — |

**유예 듣기 폐지 (2026-10-30):** `GraceListener`·`GracePhrases`는 삭제했다. 음성은 호출어 뒤의 말만 인식해 `controller.onHelp(text)`로 넘긴다(B 작업 중). 엔진 없이 시험하려면 디버그 빌드에서 `adb shell am broadcast -a app.mergo.DEBUG_HEAR --es text "소리가 안 나" -p app.mergo`를 쓴다.

**B가 교체할 곳:** `service/MergoService.kt`의 조립부에서 `ChoiceBrain`, `SilentVoice`, `NoAgent`, `DefaultSettings`(모두 `core/fakes/`)를 진짜 구현으로 바꾼다. `ScreenAgent`에는 `AccessibilityScreen`(ScreenReader 구현)을 넘기면 된다.

3. **연결 지점(Orchestrator, A 소유)**: `core/MergoController`가 아래 두 흐름을 맡는다. B의 `Brain`·`VoiceIO`·`ScreenAgent`는 생성자로 주입받는다.
   - 감지 흐름: `StuckDetector`의 제안 → 말풍선과 `VoiceIO.listenYesNo` → 레시피 체인 → `Verifier` → 실패하면 FamilyLog
   - 도와줘 흐름: 도와줘 버튼 → `VoiceIO.listen` → `Brain.decide` → 레시피
4. **Git 규칙**: 작업 브랜치는 `feat/device`(A)와 `feat/ai`(B)로 나눈다. **점검 시점마다 `dev`에 PR로 병합**하고, `main`에는 최종본만 올린다. `model/` 변경은 단독으로 PR한다.

---

## 1단계 · S1 관통 (메인 시나리오)

| A · 기기 | B · AI |
|---|---|
| `MergoService`: 이벤트 수신, 키 필터, 이벤트를 `ScreenEvent`로 변환, Logcat `Mergo` | `VoiceIO`: SpeechRecognizer ko-KR, TTS, `listenYesNo`(응/네/예/그래/어/맞아/좋아 · 아니/아니요/됐어/싫어) |
| `TimelineBuffer`: 10분 원형 버퍼, 중복 제거, 디버그용 JSON 덤프 | `Brain` 키워드 규칙(순수 함수, 사투리 포함) + **발화 테스트셋 38개**(AC-B5) |
| `BaselineStore`: 5초 유지·영상 30초 시청 기준, 스택 3개(AC-D7) | `LlmClient` 인터페이스, `GeminiClient`(기본), `ClaudeClient`(교체용), 타임아웃 8초, "잠시만요" 1.5초 |
| `StuckDetector` R1(카운트다운 우선 + 대체 모드)(AC-D1, D2) | 응답 파서: 코드펜스, 깨진 JSON → 키워드/ask로 대체(AC-B6) |
| `OverlayUi`: 도와줘 버튼(64dp+), 말풍선 [네]/[아니요](18sp+) | (A가 `core/fakes/`에 가짜 구현을 이미 만들어 둠 — B는 진짜 구현에 집중) |
| `ScreenReader` 구현(노드 스냅샷, 클릭, 탭 제스처) — **B의 ScreenAgent가 쓰므로 1단계 안에 넘김** | |
| `RestoreVideo`(S1 순서: 이전 버튼 → 기록 → 에이전트), `Verifier` | |
| `MergoController`: 감지 흐름 연결 | |

**✅ 점검 1:** 에뮬레이터에서 S1이 끝까지 1회 성공해야 한다(말풍선 → "응" → 이전 영상 재생). Brain 테스트셋은 JVM에서 통과해야 한다. 이 시점에 `dev`에 PR로 병합한다.

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

## 진행 현황

| 날짜 | 브랜치 | 내용 |
|---|---|---|
| 2026-10-30 | `feat/device` | 0단계 뼈대·계약, A 1단계(서비스·기록·정상 상태·R1~R4·오버레이·RestoreVideo·Verifier·Controller) + SetVolume·OpenPreviousApp·SkipAd·실패 기록. 코드 리뷰 반영, `mergo_UI.pdf` 시안 톤의 오버레이(막힘 감지 시트·도와줘 패널·복구 중/완료 카드·이 화면이 맞나요?)와 설정 화면. JVM 테스트 59개 통과, `assembleDebug` 성공 |
| 2026-10-30 | `feat/device` | 에뮬레이터(API 36) 검증과 그 결과 반영. JVM 테스트 65개 통과. 아래 "에뮬레이터 검증 결과" 참고 |

## 사전 검증 방법 (A)

1. `./gradlew installDebug` → 앱의 설정 화면에서 접근성, 알림 접근, 마이크 권한을 켠다.
2. YouTube에서 확인하려는 화면(재생 중, 컨트롤 표시, 카운트다운, 광고)을 띄우고 `adb shell am broadcast -a app.mergo.DEBUG_DUMP -p app.mergo`를 보낸다(디버그 빌드) → 노드 목록과 최근 기록이 저장된다. 도와줘 버튼 길게 누르기는 2026-10-30부터 버튼 옮기기로 바뀌었다.
3. `adb pull /sdcard/Android/data/app.mergo/files/ ./dumps/`로 가져와 `service/YouTubeSignals.kt`의 패턴을 보정한다. 카운트다운이 끝내 잡히지 않으면 `DetectorConfig.requireCountdown = false`로 바꾼다.
4. 로그: `adb logcat -s Mergo:*`
5. **뒤로 가기 확인(R3):** API 36 제스처 내비게이션에서는 뒤로 가기가 `KEYCODE_BACK`으로 안 올 수 있다. 로그에 BACK이 안 찍히면 에뮬레이터를 3버튼 내비게이션으로 바꿔 시연한다(설정 → 시스템 → 탐색 모드).

## 에뮬레이터 검증 결과 (2026-10-30, `Medium_Phone_API_36.1`, YouTube 영어 UI)

| 항목 | 결과 | 비고 |
|---|---|---|
| 서비스 시작·도와줘 버튼·선택지 패널 | ✅ | 시안 톤으로 표시 |
| S4 소리 복원 (도와줘 → 소리가 안 나) | ✅ | 복구 8~39ms |
| S1 자동재생 감지 → 제안 → 이전 동영상 버튼 복원 | ✅ | 카운트다운 신호 관측, 자동재생 10초 뒤 제안, 복원 1.26초 |
| S3 홈 이탈 20초 → 제안 → YouTube 복귀 | ✅ | 복원 0.66초 |
| N1 직접 다음 영상 고르고 계속 시청 | ✅ | 제안 0회 |
| 영상 제목 | ✅ MediaSession | 노드 대안: 플레이어 바로 아래 첫 글자 (viewId 없음) |
| 이전 동영상 버튼 | ✅ | `player_control_previous_button`, desc "Previous video" |
| 시청 기록에서 복원 | ✅ | Google 로그인 후 확인. 기록 화면에서 제목으로 찾아 눌러 1.4초에 복원. **로그인이 안 되어 있으면 이 경로는 실패한다(시연 기기는 로그인 필수)** |
| **뒤로 가기 연타(R3)** | ⚠️ 미검증 | adb 가상 키는 접근성 키 필터를 거치지 않아 확인 불가. 실기기·3버튼 내비게이션에서 확인 필요 |
| S11 광고 감지(R4) → 제안 → 건너뛰기 | ✅ | 건너뛰기 버튼이 뜨고 5초 뒤 제안, 1.02초에 넘김. 버튼은 viewId `skip_ad_button`(글자 없음), 광고 표시는 `ad_progress_text`("Sponsored · 1:46"), 건너뛰기 전 `countdown_text`는 버튼으로 보지 않음 |
| ~~유예 듣기 → 말로 복원~~ | 폐지 | 2026-10-30 유예 듣기 폐지로 삭제. 호출어 → 도와줘 흐름으로 대체 |

검증하며 고친 것:
- **판정 기준:** YouTube 플레이어 탭은 클릭 이벤트가 오지 않는다. 그래서 '카운트다운이 있으면 자동재생, 없으면 사용자 선택'으로 판정한다.
- **자동 스크롤 무시:** YouTube가 새 영상에 맞춰 목록을 스스로 스크롤하는데, 제목 변경 직후 3초 안의 SCROLL은 사용자 동작에서 뺀다.
- **복구 직후:** 5초 동안 제목 변화를 분류하지 않는다. 늦게 들어온 제목이 오조작으로 잘못 읽혔다.
- **플레이어 노드:** `watch_player`만 쓴다. 화면 전체 컨테이너를 플레이어로 오인해 댓글 창을 연 문제를 고쳤다.
- **시청 기록 정리:** 실패 후 정리는 기록 화면이 실제로 열렸을 때만 뒤로 간다. 영상 화면을 닫아 버린 문제를 고쳤다.
- **광고 판정:** 광고·건너뛰기는 플레이어 영역 안의 노드만 본다. 추천 목록의 'Sponsored' 카드와 제목에 'Skip'이 든 영상을 오인하지 않는다.
- **광고 판정:** 건너뛰기는 viewId로 찾고, 광고 중에는 노드에서 제목을 읽지 않는다. 광고 문구와 "6.1B views"가 영상 제목으로 들어가던 문제를 고쳤다.
- **디버그 명령:** 화면을 건드리지 않고 덤프하는 `adb shell am broadcast -a app.mergo.DEBUG_DUMP -p app.mergo`를 추가했다.
- **볼륨:** 머고가 바꾼 게 아니면 볼륨 변화를 사용자 동작으로 본다(가상 키 미수신 대응). 볼륨 변화는 시스템 방송으로도 받는다.
