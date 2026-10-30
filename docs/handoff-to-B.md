# B(AI 담당) 인계 — 2026-10-30 기준

A(기기 담당) 작업은 `feat/device` 브랜치(`3360efe`)에 있다. 이 문서는 B가 바로 작업을 시작하는 데 필요한 것만 담는다.
- 스펙: [`mergo-mvp-spec.md`](./mergo-mvp-spec.md)
- 분담: [`work-split.md`](./work-split.md)
- 에뮬레이터 검증표: `work-split.md` 맨 아래 "에뮬레이터 검증 결과"

## 1. 지금 상태 한 줄 요약

기기 쪽(감지·복구·오버레이·흐름 연결)은 동작하고, 에뮬레이터에서 **S1·S2·S3·S4·S11·N1을 확인**했다. AI 쪽 자리는 모두 **가짜 구현**으로 채워져 있다. B는 가짜를 진짜로 바꾸고 S6 에이전트를 만들면 된다.

## 2. 시작하기

```bash
git fetch && git checkout feat/device && git checkout -b feat/ai
# local.properties (커밋 금지, .gitignore 처리됨)
sdk.dir=/Users/<you>/Library/Android/sdk
GEMINI_API_KEY=...
GEMINI_MODEL=gemini-flash-latest        # 사전 준비 때 실제 최신 Flash ID로 고정
LLM_PROVIDER=gemini                     # 기준 미달 시 claude 로 교체
CLAUDE_API_KEY=...                      # 교체용
CLAUDE_MODEL=claude-haiku-5-5

./gradlew testDebugUnitTest   # JVM 테스트 72개 통과해야 함
./gradlew installDebug
```

에뮬레이터 권한은 앱 설정 화면에서 켜거나 adb로 켠다.

```bash
adb shell pm grant app.mergo android.permission.RECORD_AUDIO
adb shell cmd notification allow_listener app.mergo/app.mergo.service.MediaListener
adb shell settings put secure enabled_accessibility_services app.mergo/app.mergo.service.MergoService
adb shell settings put secure accessibility_enabled 1
adb logcat -s 'Mergo:*'
```

**에뮬레이터에 Google 로그인이 필수다.** 로그인이 없으면 시청 기록 복원이 실패한다.

## 3. B가 교체할 가짜 구현

조립은 `service/MergoService.kt`의 `onServiceConnected()` 한 곳에서 한다. 아래 가짜를 진짜로 바꾸면 된다.

| 가짜 (`core/fakes/Fakes.kt`) | 계약 (`model/`) | B가 만들 것 | 우선순위 |
|---|---|---|---|
| `ChoiceBrain` | `Brain.decide(BrainContext): BrainDecision` | 키워드 규칙(순수 함수, 사투리) → 안 되면 LLM. 발화 테스트셋 38개 90% 이상 | ① |
| `SilentVoice` | `VoiceIO` | STT(ko-KR) + TTS | ① |
| `NoAgent` | `ScreenAgent.achieve(goal): Boolean` | S6 화면 조작 에이전트(최대 5단계·30초, 위험 노드 차단) | ② **시연 줄기** |
| `DefaultSettings` | `SettingsSource` | 설정 화면(호출어, '확인 없이 실행') | ③ |
| `NoGraceListener` | `GraceListener` | 유예 듣기(Vosk, 문법 제한) — 아래 §5 | ④ |
| (없음) | — | 가족 리포트 화면(`report/`), S6 테스트 페이지 | ③ |

## 4. 계약을 구현할 때 꼭 지킬 것 (A 코드가 이렇게 부른다)

### VoiceIO
- **`speak()`는 TTS가 끝날 때까지 suspend해야 한다.** Controller가 `speak(질문)` 다음에 바로 `listenYesNo()`를 부른다. 말하는 동안 마이크가 열리면 머고 자신의 목소리를 듣는다.
- **`listenYesNo()`와 화면 버튼은 동시에 기다린다.** 버튼이 먼저 눌리면 Controller가 `cancelListening()`을 부르므로, 이때 인식을 즉시 멈춰야 한다.
- **판정 기준:** 긍정어는 `응/네/예/그래/어/맞아/좋아`, 부정어는 `아니/아니요/됐어/싫어`다. 못 알아들으면 `NONE`을 돌려준다. 그러면 Controller가 버튼을 15초까지 기다린다.
- **`listen()`:** 자유 발화를 받는다. 도와줘 버튼을 눌렀을 때만 부르며, 5초 안에 못 들으면 `null`을 돌려준다.

### Brain
- **입력(`BrainContext`):**
  - `utterance`: 어르신의 말
  - `diff`: 예) `"영상 제목: A → B"`, `"볼륨: 8 → 0"`
  - `timeline`: 요약 12줄
  - `current`: 현재 상태(`pkg`, `mediaTitle`, `volume`, `online`)
  - `screenHasAd`: 광고 중인지
- **출력:**
  - `recipe`는 `RecipeIds`의 값 중 하나를 쓴다. 판단하기 어려우면 `ask`를 쓰고, `ask`면 Controller가 `say`만 말하고 끝낸다.
  - `params`는 비워도 된다. `restore_video`의 `title`과 `open_previous_app`의 `pkg`는 Controller가 정상 상태로 채운다.
  - `screen_agent`에는 `params.goal`이 필요하다. 예: `"광고 닫기"`
  - `say`는 시트의 큰 제목으로 그대로 쓰이므로 짧은 존댓말 한 문장으로 쓴다. 예: "소리를 다시 켤까요?"
  - `confirm=true`면 Controller가 [예, 해 주세요]/[아니요] 시트로 묻는다. **확인 UI는 B가 만들지 않는다.**
- **현재 가짜(`ChoiceBrain`) 매핑:** 도와줘 선택지 4개가 기준이다. 진짜 Brain도 이 4개는 키워드만으로 오프라인에서 맞혀야 한다.
  - "아까 보던 거" → restore_video (YouTube가 아니면 open_previous_app)
  - "소리가 안 나" → set_volume
  - "이거 안 꺼져" → screen_agent(goal "광고 닫기")
  - "광고 안 꺼져" → skip_ad

### ScreenAgent (S6)
- **화면 읽기:** `AccessibilityScreen`(이미 구현된 `ScreenReader`)을 생성자로 받아 쓴다. `MergoService`의 `screen` 필드가 그것이다.
  - `nodes()`는 보이는 노드를 최대 400개, `NodeSnapshot(id, text, desc, viewId, clickable, bounds)`로 돌려준다.
  - LLM에 보낼 요약(최대 60개)은 B가 줄인다.
  - `click(id)`는 클릭 가능한 조상을 찾아 누르고, 안 되면 탭 제스처를 보낸다.
  - **번호는 다음 `nodes()` 호출 전까지만 유효하다.** LLM 응답을 기다린 뒤에는 다시 읽지 말고 그 번호로 바로 누른다. 이벤트 처리는 별도 인스턴스를 쓰므로 번호가 바뀌지 않는다.
- **진행 안내:** Controller가 상단 카드("화면을 살펴보고 있어요")를 띄운다. 이 카드는 터치를 통과시키므로 탭 제스처가 막히지 않는다.
- **안전:** `설치·결제·구매·보내기·동의·삭제·로그인·인증`이 들어간 노드는 설정과 관계없이 누르지 않는다.
- **S6 테스트 페이지:** `testpages/s6.html`. 맥에서 `python3 -m http.server`로 띄우고, 에뮬레이터 Chrome에서 `http://10.0.2.2:8000/s6.html`로 연다(스펙 §5).

### SettingsSource
- Controller가 `autoExecute`를 **매번 읽는다.** SharedPreferences를 읽는 getter로 구현하면 설정 변경이 바로 반영된다.

### 가족 리포트
- 데이터는 `memory/FamilyLog`가 앱 내부 `filesDir/family_log.json`에 쓴다.
  - `Entry(ts, situation, recipe, notified, resolved, summary)`
  - `resolved=true`면 '자동 해결', `notified=true`면 '가족에게 알림'으로 표시한다.
  - `summary`는 리포트 한 줄이다. 예: "소리를 다시 켰어요"
- 리포트 화면에서는 `FamilyLog(File(filesDir, "family_log.json")).all()`로 읽는다. 시안은 `mergo_UI.pdf`의 '이번 주 리포트'와 '도움 기록' 화면이다.

## 5. 새로 생긴 일: 유예 듣기 (`GraceListener`)

- **동작:** 영상이 바뀌면(자동재생이든 직접 바꿨든) A가 `open(30초, onHeard)`를 부르고, 끝나면 `close()`를 부른다. 그 사이 들은 문장을 `onHeard(text)`로 넘기면, A가 `GracePhrases`에 맞는 말인지 보고 **확인 없이** 이전 영상으로 되돌린다.
- **왜 필요한가:** 에뮬레이터에서 확인해 보니 YouTube 플레이어 탭은 클릭 이벤트가 오지 않는다. 그래서 오조작을 화면만으로는 확신할 수 없다.
- **반드시 지킬 것:** 영상 소리가 마이크로 들어오므로 `SpeechRecognizer` 자유 받아쓰기는 쓰면 안 된다. **Vosk 문법 제한**으로 `GracePhrases.DEFAULT`(+호출어)만 알아듣게 한다. 같은 엔진을 호출어(스트레치)에도 그대로 쓰면 된다.
- **엔진 없이 시험하기:**

```bash
adb shell am broadcast -a app.mergo.DEBUG_HEAR --es text "'머고야 잘못 눌렀다'" -p app.mergo     # 유예 듣기
adb shell am broadcast -a app.mergo.DEBUG_HEAR --es text "'소리가 안 나'" --ez help true -p app.mergo  # 도와줘 흐름
adb shell am broadcast -a app.mergo.DEBUG_DUMP -p app.mergo   # 화면 노드·기록 덤프 → /sdcard/Android/data/app.mergo/files/
```

## 6. 에뮬레이터에서 알게 된 사실 (B에 영향 있는 것)

| 사실 | 영향 |
|---|---|
| 에뮬레이터 YouTube가 **영어 UI**다(버튼: "Previous video", "Skip" 등) | 에이전트 프롬프트와 화면 요약이 한·영 혼용 화면을 다뤄야 한다 |
| adb `input keyevent`로 넣은 키는 접근성 키 필터에 오지 않는다 | 키 입력에 의존하는 기능은 adb로 시험할 수 없다 |
| 광고 중에도 MediaSession 제목은 본 영상 그대로다 | `BrainContext.current.mediaTitle`은 믿어도 된다 |
| 복구는 빠르다(0.6~1.4초). 그러면 LLM 응답 시간이 10초 목표를 좌우한다 | `LlmClient` 타임아웃 8초. 1.5초가 넘으면 "잠시만요"를 말한다(스펙) |

## 7. B 체크리스트 (분담표 기준)

- [ ] `VoiceIO` (STT·TTS, `listenYesNo`, `cancelListening`)
- [ ] `Brain` 키워드 규칙 + 발화 테스트셋 38개(JVM, 90% 이상, 위험 오답 0)
- [ ] `LlmClient` 인터페이스 + `GeminiClient` + `ClaudeClient`, 파서(코드펜스·깨진 JSON → 키워드/ask)
- [ ] `ScreenSummarizer`(60개), `SafetyGuard`(위험 키워드, 5단계·30초), `ScreenAgent` 루프
- [ ] `testpages/s6.html`(+`?hard=1`) — 실제 브랜드를 흉내 내지 않는다
- [ ] 설정 화면(`SettingsSource`), F9 후보 되묻기(최대 2번)
- [ ] 가족 리포트 화면(`FamilyLog` 읽기)
- [ ] `GraceListener`(Vosk) → 호출어(스트레치)
- [ ] `MergoService` 조립부에서 가짜를 교체한 뒤 S6 5회 중 4회 성공, Brain 테스트셋 통과
