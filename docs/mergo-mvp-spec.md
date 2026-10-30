# Deep Interview Spec: 머고(mergo) 해커톤 MVP — 어르신 폰 막힘 감지·복구 앱

## Metadata
- Interview ID: mergo-di-20261030-01
- Rounds: 13 (+ Round 0 토폴로지 확인)
- Final Ambiguity Score: 10.0%
- Type: greenfield (저장소에는 README.md만 있음)
- Generated: 2026-10-30
- Threshold: 0.2 (기본값). 사용자가 Round 9 이후 **10% 이하**로 목표를 올림
- Threshold Source: default (+ 사용자 요청으로 0.10 목표)
- Initial Context Summarized: yes (PRD 10쪽 + 구현 계획 7쪽 PDF를 요약)
- 원본 문서: `머고 PRD — 어르신 스마트폰 막힘 감지·복구 AI.pdf`, `머고 구현 계획.pdf`. 이 스펙이 두 문서보다 우선한다.
- Status: PASSED — pending approval

## Clarity Breakdown (구성요소별 최솟값 기준)
| Dimension | Score | Weight | Weighted |
|-----------|-------|--------|----------|
| Goal Clarity | 0.90 | 0.40 | 0.36 |
| Constraint Clarity | 0.90 | 0.30 | 0.27 |
| Success Criteria | 0.90 | 0.30 | 0.27 |
| **Total Clarity** | | | **0.90** |
| **Ambiguity** | | | **0.10** |

남은 모호도는 에뮬레이터에서 실제로 확인해야 하는 사실이다(§9 사전 검증). 스펙에는 검증 결과에 따른 분기를 미리 넣었다.

## Topology
| Component | Status | Description | Coverage / Deferral Note |
|-----------|--------|-------------|--------------------------|
| 1. 감지 파이프라인 | active | MergoService, TimelineBuffer, BaselineStore, StuckDetector (R1~R4) | AC-D1~D8 |
| 2. 복구 레시피·검증 | active | RestoreVideo, SetVolume, OpenPreviousApp, OpenInternetPanel, SkipAd, Verifier | AC-R1~R7 |
| 3. 대화·판단 | active | OverlayUi, VoiceIO, WakeWord, Brain, LlmClient, Settings | AC-B1~B7 |
| 4. 화면 조작 에이전트 | active | ScreenAgent(F13), S6 테스트 페이지 | AC-A1~A4 |
| 5. 시연 자료 | 부분 보류 (2026-10-30 Round 0 확정) | 가족 리포트 목업만 포함. 비교 벤치마크와 시연 영상 편집은 코드 범위 밖 | AC-F1 |

## Goal
**해커톤이 진행 중인 지금**, 이 저장소(`/Users/byami/dev/mergo`)에 Kotlin 안드로이드 앱 "머고"를 구현한다. 머고는 접근성 서비스로 화면을 텍스트로만 기록하고, 막힘을 스스로 감지하거나 어르신의 부름(도와줘 버튼, 음성, 호출어)을 받는다. 그다음 되돌릴 수 있는 행동만으로 원래 상태를 복구하고 결과를 확인한다.

시연 대상은 **S2·S3·S4·S6**이고(2026-10-30: S1 자동재생 제안은 뺐다. 아래 §2 참고), 스트레치는 **S11 → S8 → 호출어 → 가족 리포트 목업** 순서다. 정상 사용(N1·N3·N4)에는 끼어들지 않는다.

## Constraints
- **환경**:
  - 대상 기기: macOS, JDK 17(Zulu), Android SDK `~/Library/Android/sdk`(platform android-36, build-tools 36.1.0)
  - 에뮬레이터: AVD `Medium_Phone_API_36.1`(google_apis_playstore, arm64-v8a)
  - Gradle: 전역 설치가 없으므로 **Gradle Wrapper를 포함**한다. `ANDROID_HOME`이 설정되어 있지 않으므로 `local.properties`의 `sdk.dir`로 지정한다.
- **프로젝트**:
  - 구성: Kotlin, 단일 `app` 모듈, compileSdk/targetSdk 36, minSdk 29(Settings.Panel 때문에 Android 10 이상)
  - 패키지: 구현 계획의 폴더 구조(`service/ memory/ detect/ ui/ recipe/ ai/`)를 따르고, 추가로 `settings/`, `report/`를 둔다.
  - 배포: APK 직접 설치
- **서버 없음**: 기기에서 LLM API를 직접 호출한다. PRD의 서버 구조는 2단계 설계로만 설명한다.
- **LLM**:
  - 기본 모델: **Gemini Flash 계열**. 정확한 모델 ID는 사전 준비 때 최신 Flash로 고정한다.
  - 교체 기준: Brain 테스트셋(AC-B5)에서 90% 미만이거나 JSON 파싱 실패가 나면 **Claude Haiku 5.5(`claude-haiku-5-5`)**로 교체한다. 교체는 **빌드 설정값 하나**로 한다.
  - 실행 중 자동 폴백은 넣지 않는다. 응답 10초 목표를 지키기 위해서다.
  - `LlmClient`는 인터페이스로 두고 `GeminiClient`와 `ClaudeClient`를 구현한다. 타임아웃은 8초다.
  - API 키는 `local.properties`에 두고 `BuildConfig`로 주입한다. **Git에 커밋하지 않는다.** `.gitignore`에 `local.properties`를 넣는다.
- **개인정보**: 화면은 텍스트로만 기록하고, 기기 메모리에 최근 10분만 보관한다. 마스킹은 구현하지 않는다(PRD '빼는 것'). LLM에는 타임라인 요약과 화면 노드 요약만 보낸다.
- **안전**:
  - 자동 행동: 탭, 뒤로 가기, 앱 열기, 볼륨, 패널 열기, Wi-Fi 토글을 기본 범위로 한다. 시각 접근성 복구는 별도 예외로 아래의 사전 권한·명시적 발화·가족 설정 조건에 따른다.
  - 금지: 글자 입력을 하지 않는다.
  - 위험 노드: 노드의 글자·설명에 `설치·결제·구매·보내기·동의·삭제·로그인·인증`이 들어 있으면 **설정과 관계없이 누르지 않고 멈춘다**.
- **확인 정책**:
  - 기본값: **목표 단위로 1회 확인**한다. 예: "광고를 닫아 드릴까요?" → 네.
  - 설정: '확인 없이 실행'을 켜면 자동 제안(R1~R4)과 도와줘 호출 모두에서 확인 없이 바로 실행한다. 기본값은 꺼짐이다.
- **응답 속도**: 동의 시점부터 Verifier 성공까지 10초 이내로 한다. LLM이 1.5초 넘게 걸리면 TTS로 "잠시만요"라고 안내한다.
- **접근성 UI**:
  - 글씨는 최소 18sp, 도와줘 버튼은 64dp 이상으로 한다.
  - 모든 안내는 음성(TTS ko-KR)과 화면에 동시에 낸다.
  - 오버레이는 `TYPE_ACCESSIBILITY_OVERLAY`를 쓰므로 별도 '다른 앱 위 표시' 권한이 필요 없다.
- **배터리**: 백그라운드에서는 AI 모델을 돌리지 않는다. **예외는 호출어 키워드 인식(Vosk) 하나뿐**이다(PRD 조항 수정).
- **시연 순서 보호**: S2 오조작 감지·복원과 S6 에이전트는 끝까지 지킨다(2026-10-30, S1에서 S2로 바꿈).

## Non-Goals
- iOS, 서버, 개인정보 마스킹 구현, 재생 위치 직접 복원(YouTube 자체 이어보기에 맡김)
- 실제 가족 메시지 전송(MVP는 기기 안 기록으로 대신), F5 개인별 학습, F15 기기 안 소형 모델, F17 범용 설정 변경 제안(밝기·글자 크기 접근성 복구는 별도 구현), F18 위험 알림
- S5·S7·S9·S10 구현(발표에서 설계로만 설명)
- 비전 모델 좌표 폴백(시간이 남을 때만, MVP 합격 기준 아님), 플레이스토어 배포
- 비교 벤치마크 실행과 시연 영상 편집(팀이 한다)

## 핵심 규칙 (구현 기준)

### 1. 정상 상태(Baseline)
- **갱신 조건**: 사용자 동작 직후 만들어진 상태가 5초 이상 유지되면 갱신한다. 대상은 앱, 볼륨, 연결 상태다.
- **영상 제목**: 영상 제목만은 **30초 이상 시청**해야 확정한다.
- **보관**: 직전 정상 상태 **3개를 스택으로** 보관한다(BaselineStack).
- **자동 변화**: 자동으로 생긴 변화는 저장하지 않는다. 제안을 **거절한 경우에는 현재 상태를 새 정상 상태로 인정**한다.
- **사용자 동작 판정**: 직전 2초 안에 클릭·스크롤 이벤트나 키 이벤트(볼륨 버튼 등)가 있었는지로 판정한다. 키 이벤트를 받으려면 서비스 설정에서 `flagRequestFilterKeyEvents`를 켜야 한다.

### 2. 막힘 감지 규칙
| 규칙 | 시나리오 | 조건 | 제안 |
|---|---|---|---|
| **R1 자동재생** | S1 | ① YouTube에서 영상 제목이 바뀜 ② 바뀌기 직전 15초 안에 **'다음 동영상' 카운트다운 노드가 관측됨** ③ 직전 2초 안에 사용자 동작이 없음 ④ 이전 영상이 정상 상태(30초 이상 시청)임 ⑤ 바뀐 뒤 10초 동안 사용자 동작이 없음. 카운트다운이 접근성 트리에 잡히지 않으면 ②를 빼고 원래 R1(터치 부재 기준)으로 대체한다. | "아까 보시던 영상으로 돌아갈까요?" → RestoreVideo(S1 순서) |
| **R2 홈 이탈** | S3 | ① 직전 앱에서 영상을 재생 중이었음 ② 홈 화면(시작할 때 조회한 기본 런처 패키지)으로 바뀜 ③ 20초 동안 다른 앱을 열지 않음 | "아까 보시던 유튜브로 돌아갈까요?" → OpenPreviousApp |
| **R3 오조작 후 혼란** | S2 | ① 사용자 터치로 영상 제목이 바뀜(이 시점에는 개입하지 않음) ② 바뀐 뒤 30초 안에 **뒤로 가기를 3초 안에 2회 이상** 누르거나 **홈으로 나감**. 30초 동안 혼란 신호가 없으면 일부러 고른 것(N1)으로 보고 정상 상태를 갱신한다. | "아까 보시던 영상으로 돌아갈까요?" → RestoreVideo(S2 순서) |
| **R4 광고 건너뛰기** | S11 | 건너뛰기 버튼 노드('건너뛰기'·'Skip'이 들어간 클릭 가능한 노드)가 보이고 5초 동안 사용자 동작이 없음 | "광고 넘길까요?" → SkipAd |

> **변경(2026-10-30):** R1은 자동 제안에서 뺐다(`DetectorConfig.suggestAutoplay = false`). 영상을 끝까지 본 뒤 넘어간 것이라 "돌아갈까요?"가 어색하고, 에뮬레이터에서 영상 끝 광고 → 자동재생은 카운트다운이 안 보여 잡히지도 않았다. 자동재생 판정 자체는 R3(오조작) 구분에 계속 쓴다. R4는 묻지 않고 건너뛰기 버튼만 누르고 "광고를 넘겼어요" 카드만 띄운다(`autoSkipAds = true`).

- **S4(볼륨 0)는 자동 제안하지 않는다.** 볼륨 버튼 입력은 사용자 동작이라 N3과 구분되지 않기 때문이다.
- **말풍선은 한 번에 하나만** 띄운다. R2와 R3가 동시에 성립하면 R3를 우선한다.
- **거절**: '아니요'를 누르거나 15초 동안 무응답이면 거절로 본다. 거절된 규칙은 같은 대상(영상 제목이나 앱)에 다시 제안하지 않고, 그 규칙 전체는 **5분 쿨다운**한다. 도와줘 버튼은 쿨다운 중에도 항상 동작한다.

### 3. 복구 레시피와 순서
| 레시피 | 순서 | Verifier |
|---|---|---|
| `restore_video` (S1) | ① 플레이어 중앙을 탭해 컨트롤 표시 → contentDescription `이전 동영상`/`Previous video` 노드 클릭 ② **시청 기록**: '나' 탭 → 기록 → 제목이 Baseline.mediaTitle과 같은 항목 클릭(없으면 두 번째 항목) ③ ScreenAgent(목표 "'{제목}' 영상 재생") | 재생 화면 제목 == Baseline.mediaTitle |
| `restore_video` (S2) | 시청 기록 → 이전 동영상 버튼 → ScreenAgent | 같음 |
| `set_volume` (S4) | STREAM_MUSIC을 Baseline.volume으로 맞춤. Baseline 볼륨이 0이거나 없으면 최대값의 절반으로 맞춤 | 현재 볼륨 > 0이고 목표값과 같음 |
| `open_previous_app` (S3) | `getLaunchIntentForPackage(baseline.pkg)`로 실행(`<queries>`에 LAUNCHER 인텐트 선언) | 포그라운드 pkg == baseline.pkg |
| `open_internet_panel` (S8) | `Settings.Panel.ACTION_INTERNET_CONNECTIVITY`를 연 뒤 패널 안 Wi-Fi 토글 노드를 접근성 클릭 | 10초 안에 `online=true` |
| `skip_ad` (S11) | 건너뛰기 노드 클릭. 노드가 없으면 최대 10초 기다리며 "잠시만요, 곧 넘길 수 있어요"라고 안내. 끝내 없으면 "광고가 끝나면 이어서 나와요"라고 안내하고 종료 | 광고 노드가 사라짐 |
| `screen_agent` | §5 참조 | LLM `done` + 목표 상태 |

- **실패 처리**: 레시피 순서와 ScreenAgent까지 모두 실패하면 추가 조작을 멈추고, 실행 중 연 화면(기록, 패널)은 뒤로 가기로 닫는다. 그다음 "제가 해결하지 못했어요. 가족에게 알릴까요?"라고 묻는다. '네'라고 하면 **기기 안 기록(FamilyLog)에 남기고 "가족에게 알렸어요"라고 안내**한다. 실제 전송은 하지 않는다.

### 4. 대화·판단
- **자동 제안**: 말풍선과 TTS로 질문한다. 말풍선에는 큰 [네]/[아니요] 버튼이 있다. 동시에 STT로 5초 동안 기다리며, 긍정어(`응/네/예/그래/어/맞아/좋아`)와 부정어(`아니/아니요/됐어/싫어`)를 받는다. **먼저 들어온 쪽**을 답으로 쓴다. 15초 동안 무응답이면 조용히 닫고 거절로 처리한다.
- **도와줘 버튼**: 정상 상태와 지금 화면의 차이로 짐작 가는 도움이 있으면 그것 하나만 먼저 짧게 묻는다(예: "보던 거 다시 틀까요?" [응, 틀어 줘] [아니, 다른 거]). "응"이면 바로 실행하고, "아니"면 아래 선택지를 보여 주며, 답이 없으면 닫는다(2026-10-30 변경). 짐작이 없으면 TTS로 "무엇을 도와드릴까요?"라고 묻고 바로 STT를 시작한다. 동시에 큰 선택지 버튼 **4개**(아까 보던 거 / 소리가 안 나 / 이거 안 꺼져 / 이거 이상해)를 보여 준다. "광고 안 꺼져"는 2026-10-30에 "이거 이상해"로 바꿨다. 광고인지는 "이거 안 꺼져"에서 머고가 화면을 보고 가린다. 어르신이 누르는 답 버튼은 어르신의 말이라 반말로 쓴다("응, 맞아", "아니").
- **호출어(스트레치)**:
  - 엔진: Vosk 오프라인 한국어 소형 모델을 assets에 포함하고, 문법을 `[호출어, "[unk]"]`로 제한한다.
  - 동작: 호출어를 감지하면 Vosk를 정지하고 SpeechRecognizer로 뒤에 이어진 말을 받아 Brain에 넘긴 뒤 Vosk를 재시작한다.
  - 설정: 기본 호출어는 **"머고야"**이고, 설정 화면에서 가족이 텍스트로 바꿀 수 있다.
- **후보가 여럿일 때(F9)**: 가능성이 높은 후보부터 예/아니오로 **최대 2번** 묻는다.
- **Brain**:
  - 처리 순서: **키워드 규칙(순수 함수)을 먼저** 적용한다. 후보가 하나로 정해지면 오프라인으로 바로 레시피를 고르고, 아니면 LLM을 호출한다.
  - 사투리: 경상도 사투리를 포함한다. 예: "뭐고 이거", "와 안 나노", "이거 와이라노".
  - LLM 요청·응답 JSON: 구현 계획 3장 형식을 따르고, 응답에 `confirm` 필드를 포함한다.
  - 프롬프트 규칙: `recipes` 중 하나만 고른다. 판단하기 어려우면 `ask`로 둔다. `say`는 존댓말 2문장 이내로 쓴다. JSON 외의 글자는 출력하지 않는다.
  - 오류 처리: JSON 파싱에 실패하면 키워드 결과를 쓰고, 그것도 없으면 `ask`로 처리한다.
- **설정 화면**: 호출어, '확인 없이 실행' 토글, 가족 리포트 보기 진입점을 둔다.

### 5. 화면 조작 에이전트 (F13)
- **루프**: 화면 요약(보이는 노드 최대 60개, `번호·글자·설명·클릭가능·위치`) → LLM → 행동 1개 → 1초 뒤 재요약을 반복한다. **최대 5단계·30초**로 제한한다.
- **행동**: `click|back|done|fail` 중 하나다. 클릭을 못 받는 노드면 그 위치 중심에 탭 제스처를 보낸다.
- **중단**: 위험 노드를 선택하면 즉시 중단한다. 5단계나 30초를 넘기면 §3의 실패 처리로 넘어간다.
- **S6 테스트 페이지** (`testpages/s6.html`):
  - 띄우는 방법: 맥에서 `python3 -m http.server`로 띄우고, 에뮬레이터 Chrome에서 `http://10.0.2.2:8000/s6.html`로 연다.
  - 화면 구성: 가상의 일반 기사 본문 위에, 페이지를 연 뒤 2초가 지나면 전체화면 덮개가 나타난다. 가운데에는 큰 '설치하기' 버튼이 있다.
  - × 버튼: 오른쪽 위 20px `<button>`이며 글자는 '×'만 있고 aria-label은 없다.
  - 판정 표시: ×를 누르면 덮개가 사라지고 하단에 `✅ 닫힘`이 표시된다. '설치하기'를 누르면 `❌ 설치 눌림`이 표시된다.
  - 어려운 변형: `?hard=1`이면 ×가 라벨 없는 div가 된다(MVP 기준 아님).
  - 실제 브랜드를 흉내 내지 않는다.

### 6. 기타 산출물
- **가족 리포트 화면**: 앱 안의 화면으로 만든다. 정적 목업 요약(반복 막힘 TOP 3 예시)과 실제 FamilyLog·제안 기록 목록을 함께 보여 준다.
- **로그**:
  - 모든 판단은 Logcat 태그 `Mergo`로 남긴다. 감지 규칙, Brain/LLM JSON, 레시피 단계, Verifier 결과, 소요 시간(ms)이 대상이다.
  - 촬영할 때는 `adb logcat -s Mergo:*`로 옆에 띄운다.
- **이벤트 덤프**: 디버그 빌드에서는 TimelineBuffer를 JSON으로 내보낸다. 내보낸 파일은 테스트 자료(`app/src/test/resources/timelines/`)로 쓴다.

## Acceptance Criteria

### 1층: JVM 자동 테스트 (`./gradlew test`, 안드로이드 의존성 없음, Clock 주입)
- [ ] AC-D1: S1 이벤트 시퀀스(카운트다운 → 터치 없는 제목 변경 → 10초 무동작)를 재생하면 R1 제안이 정확히 1회 나온다.
- [ ] AC-D2: S1 시퀀스에서 카운트다운이 없고 터치도 없으면 대체 모드에서 R1 제안이 1회 나온다. 카운트다운 우선 모드에서는 0회다.
- [ ] AC-D3: S2 시퀀스(터치로 제목 변경 → 3초 안에 뒤로 가기 2회)를 재생하면 R3 제안이 1회 나온다.
- [ ] AC-D4: S3 시퀀스(재생 중 → 런처 → 20초 무동작)를 재생하면 R2 제안이 1회 나온다. R3 조건이 함께 성립하면 R3만 1회 나온다.
- [ ] AC-D5: N1(직접 다음 영상을 누르고 30초 시청), N2(설정 화면에서 60초 멈춤), N3(볼륨 키로 0), N4(광고를 보는 중 '아니요')에서 추가 제안이 0회다.
- [ ] AC-D6: 거절한 뒤 같은 대상에는 재제안이 0회이고, 5분 안에는 같은 규칙으로 제안하지 않는다.
- [ ] AC-D7: BaselineStore에서 영상은 30초 미만 시청이면 정상 상태가 갱신되지 않고, 30초 이상이면 갱신된다. 스택은 최대 3개다.
- [ ] AC-D8: R4는 건너뛰기 노드가 5초 동안 보이고 무동작이면 1회 제안한다.
- [ ] AC-B5: Brain 발화 테스트셋 35개(시나리오 S1·S2·S3·S4·S6·S8에 각 5개, 이 중 각 2개는 경상도 사투리. 범위 밖 발화 5개)에서 **키워드와 Fake LLM 경로 정답률이 90% 이상(35개 중 32개 이상)**이다. 범위 밖 발화에 레시피를 실행하는 경우는 0건이다. S11 발화 3개를 추가로 포함한다.
- [ ] AC-B6: LLM 응답 파서는 정상 JSON, 코드펜스로 감싼 JSON, 깨진 JSON을 모두 처리한다. 깨지면 키워드 결과나 `ask`로 대체한다.
- [ ] AC-A1: ScreenAgent 안전 검사는 위험 키워드 노드 클릭을 거부한다(단위 테스트). 6단계째나 30초를 넘기면 중단한다.
- [ ] AC-A2: 화면 요약기는 노드를 최대 60개만 남기고 보이지 않는 노드를 제외한다.
- [ ] AC-R1: 레시피 체인은 앞 단계가 실패(Verifier false)했을 때만 다음 단계로 넘어간다. 모두 실패하면 FamilyLog에 1건 기록된다(Fake 레시피로 테스트).
- [ ] AC-R2: SkipAd 노드 매처는 '건너뛰기', '광고 건너뛰기', 'Skip', 'Skip ad'에 매칭되고 '설치하기'에는 매칭되지 않는다.

### 2층: 에뮬레이터 인수 테스트 (사람이 수행, 결과는 `.omc/research/acceptance.md`에 표로 기록)
- [ ] AC-R3: S1에서 자동재생을 켜고 1~2분짜리 영상을 30초 이상 시청한 뒤 끝까지 재생하면, 말풍선이 뜨고 "응"으로 동의했을 때 이전 영상 제목이 재생된다. **5회 중 4회 이상** 성공해야 한다.
- [ ] AC-R4: S2에서 추천 영상을 잘못 누르고 뒤로 가기를 연타하면, 말풍선이 뜨고 동의했을 때 이전 영상이 재생된다. 5회 중 4회 이상 성공해야 한다.
- [ ] AC-R5: S3에서 재생 중 홈 버튼을 누르면, 20초 뒤 말풍선이 뜨거나 "아까 그거 어디 갔어"라고 말했을 때 YouTube로 복귀한다. 5회 중 4회 이상 성공해야 한다.
- [ ] AC-R6: S4에서 재생 중 볼륨을 0으로 만든 뒤 도와줘 → "소리가 안 나"라고 하면 이전 볼륨으로 복원된다. 5회 중 4회 이상 성공해야 한다.
- [ ] AC-A3: S6에서 도와줘 → "이거 안 꺼져" → 확인을 하면, **30초 안에 `✅ 닫힘`이 나오고 `❌`는 한 번도 나오지 않는다.** 5회 중 4회 이상 성공해야 한다.
- [ ] AC-R7 (스트레치): S11에서 광고가 나오면 제안이나 도와줘로 건너뛴다. 광고가 나오는 대로 3회 중 3회 성공해야 한다.
- [ ] AC-R8 (스트레치): S8에서 와이파이와 데이터를 끄고 "유튜브가 안 나와"라고 하면, 서버 없이 패널이 열리고 Wi-Fi가 켜진다.
- [ ] AC-N1: N1과 N3에서 말풍선이 0회 뜬다(각 5회).
- [ ] AC-T1: 위 성공 케이스 모두에서 동의 시점부터 Verifier 성공까지 걸린 시간이 Logcat에서 10초 이내다.
- [ ] AC-B7 (스트레치): 앱이 백그라운드에 있을 때 "머고야 이거 와이라노"라고 하면 Brain이 호출된다.
- [ ] AC-F1: 가족 리포트 화면에 목업 요약과 실제 FamilyLog 항목이 보인다.

### 빌드
- [ ] `./gradlew assembleDebug`와 `./gradlew test`가 깨끗한 체크아웃에서 성공한다. 단, `local.properties`에 API 키가 없어도 빌드는 성공해야 하며, LLM 호출만 비활성화된다.
- [ ] TODO 자리표시, `@Ignore`, 빈 테스트가 없다.

## 구현 순서 (확정)
1. ① Gradle 프로젝트 뼈대(Wrapper 포함), MergoService(이벤트 수신·로그), TimelineBuffer, OverlayUi 도와줘 버튼, 권한 안내 화면
2. ② S1: BaselineStore, StuckDetector R1, RestoreVideo(이전 버튼 → 기록 → 에이전트), Verifier, VoiceIO, Brain, LlmClient
3. ③ S6: ScreenAgent, 테스트 페이지
4. ④ S4(SetVolume), S3(R2, OpenPreviousApp)
5. ⑤ S2(R3)
6. ⑥ S11(R4, SkipAd)
7. ⑦ S8(OpenInternetPanel, 오프라인 키워드)
8. ⑧ 호출어(Vosk)
9. ⑨ 가족 리포트 화면

**일정이 밀릴 때 자르는 순서**는 호출어 → S8 → S11 → R2 자동 감지 → 음성 입력(버튼으로 대체, TTS는 유지) → 가족 리포트다. **S2와 S6은 자르지 않는다**(2026-10-30, S1 대신 S2).

## 팀 사전 검증 (구현 ①과 병행, 결과에 따라 분기)
| 항목 | 방법 | 결과에 따른 분기 |
|---|---|---|
| YouTube 영상 제목 노드 | `adb shell uiautomator dump`, 재생 화면 | 잡히지 않으면 MediaSession(`MediaSessionManager` + 알림 리스너 권한)에서 제목을 얻음 |
| '다음 동영상' 카운트다운 노드 | 영상 끝 무렵 dump | 잡히지 않으면 R1을 터치 부재 기준으로 대체 |
| '이전 동영상' 버튼 노드와 동작 | 컨트롤 표시 상태에서 dump, 눌러 보기 | 없거나 현재 영상을 처음부터 다시 틀면 기록 경로를 1순위로 |
| 영상 위 탭의 클릭 이벤트 | 이벤트 로그 | 나오지 않으면 `TYPE_TOUCH_INTERACTION_START`를 쓰거나 R3/N1 기준을 보정 |
| 시청 기록 이어보기 | 기록에서 영상 재생 | 결과를 기록만 함(재생 위치 복원은 목표가 아님) |
| 건너뛰기 버튼 노드 | 광고가 나올 때 dump 저장 | 저장한 dump를 AC-R2 테스트 자료로 씀 |
| 백그라운드 마이크(호출어) | 에뮬레이터 Mac 마이크 입력을 켜고 시험 | 안 되면 호출어는 '앱 화면을 켰을 때만'으로 축소 |
| Gemini API 키와 모델 ID | 한국어 발화와 JSON 응답 5분 시험 | 불안정하면 Claude Haiku 5.5로 교체 |

## Assumptions Exposed & Resolved
| Assumption | Challenge | Resolution |
|---|---|---|
| 이전 영상은 '어떻게든' 복원된다 | 접근성으로는 영상 ID를 얻을 수 없고, 뒤로 가기를 누르면 미니플레이어로 내려간다 | 이전 동영상 버튼 → 시청 기록(제목 일치) → ScreenAgent (사용자 제안) |
| 제목 검색으로 복원하면 된다 | 같은 제목의 다른 영상이 재생될 위험 | 검색 대신 시청 기록 사용 (사용자) |
| 터치가 없으면 자동재생이다 (R1) | Contrarian: 영상 위 탭은 클릭 이벤트가 안 나올 수 있음 | 카운트다운 관측을 우선 증거로, 터치 부재는 대체 기준으로 |
| 자동재생만 막힘이다 | 오조작으로 영상이 넘어가기도 함 (사용자) | R3 추가, S2를 데모에 포함. 영상 정상 상태는 30초 시청 후 확정하고 스택 3개 보관 |
| 동의는 '응' 한 가지면 된다 | 입력 수단이 정해지지 않음 | 버튼 + 음성 병행, 먼저 들어온 쪽 채택 |
| 부르는 방법은 버튼과 음성이면 충분하다 | 사용자가 호출어를 요구 | Vosk 호출어(가족이 설정 가능)를 스트레치로, 배터리 조항에 예외 추가 |
| S8은 와이파이를 켠다 / 패널만 연다 | 두 문서 충돌, Android 10 이상 API 제약 | Simplifier: 패널을 열고 토글을 접근성 클릭 |
| 서버가 판단한다 | 구현 계획에는 서버가 없음 | 서버 없음, 기기에서 직접 호출. Gemini Flash가 기본이고 기준 미달이면 Haiku 5.5 |
| F13은 매번 확인한다 / 확인하지 않는다 | 두 문서 충돌 | 목표 단위 1회 확인(기본), 설정으로 '확인 없이 실행' 가능 |
| 거절 이후 처리는 신경 쓸 필요 없다 | 오탐이 반복되면 앱이 삭제됨 | 같은 대상에는 재제안 금지, 규칙 5분 쿨다운, 거절한 상태를 정상 상태로 인정 |
| 가족 연결은 실제로 연락한다 | MVP 범위 | 기기 안 기록 + 리포트 화면 표시 |
| 광고는 S6 웹페이지로만 다룬다 | 사용자: YouTube 광고 스킵도 필요 | S11 + R4 자동 제안 + SkipAd 레시피 |
| 해커톤 당일 계획용 스펙이다 | 산출물이 정해지지 않음 | 해커톤 진행 중이므로 이 저장소에 지금 구현 |

## Technical Context
- **저장소 상태**: 커밋 1개(`54aa0b9 Initial commit`), README와 PDF 2개만 있다.
- **API 근거**:
  - `AccessibilityService`: `performGlobalAction`, `AccessibilityNodeInfo.performAction`, `dispatchGesture`
  - 오버레이: `TYPE_ACCESSIBILITY_OVERLAY`
  - STT·TTS: `SpeechRecognizer`(ko-KR), `TextToSpeech`
  - 기타: `AudioManager`, `ConnectivityManager`, `Settings.Panel.ACTION_INTERNET_CONNECTIVITY`, `PackageManager.getLaunchIntentForPackage`, `<queries>`
- **외부 의존성(최소)**: kotlinx-coroutines, kotlinx-serialization(JSON), OkHttp(LLM HTTP), Vosk Android(스트레치), JUnit
- **테스트 설계**: StuckDetector, BaselineStore, Brain 키워드, LLM 파서, Agent 안전 검사, 화면 요약기, 레시피 체인은 안드로이드 타입에 의존하지 않는 순수 Kotlin으로 분리한다(`ScreenEvent`/`NodeSnapshot` 데이터 클래스). Clock은 주입한다.

## Ontology (Key Entities)
| Entity | Type | Fields | Relationships |
|---|---|---|---|
| ScreenEvent | core domain | ts, pkg, title, texts[≤10], userAction, kind | TimelineBuffer가 보관(10분), StuckDetector가 소비 |
| Baseline | core domain | pkg, mediaTitle, volume, online, updatedAt | BaselineStack에 쌓임, Verifier 비교 기준 |
| BaselineStack | core domain | items[≤3] | Baseline을 3개까지 보관 |
| StuckRule | core domain | id(R1~R4), conditions, cooldown | ScreenEvent를 보고 Suggestion을 만듦 |
| Suggestion | core domain | ruleId, target, say, recipeId | OverlayUi가 표시, 응답에 따라 Recipe 실행 |
| RejectionCooldown | supporting | ruleId, target, until | Suggestion 생성을 억제 |
| Recipe | core domain | id, steps(chain), run() | Verifier로 확인, 실패하면 다음 단계 또는 FamilyLog |
| BrainDecision | core domain | diagnosis, recipe, params, say, confirm | Brain/LLM이 만들고 Recipe를 선택 |
| AgentAction | core domain | action, node, reason, say | ScreenAgent 루프의 한 단계 |
| PlayerControl | external system | 이전 동영상 버튼, 카운트다운, 제목 노드 | RestoreVideo와 R1이 사용 |
| AdSkipTarget | external system | 건너뛰기 노드 | R4와 SkipAd가 사용 |
| WakeWord | supporting | phrase(기본 "머고야") | Settings에 저장, VoiceIO를 트리거 |
| Settings | supporting | wakeWord, autoExecute | 확인 정책과 호출어를 제어 |
| LlmProvider | external system | gemini-flash(기본), claude-haiku-5-5 | LlmClient가 구현 |
| UtteranceTestSet | supporting | 35+3 발화, 기대 레시피 | Brain을 검증 |

## Ontology Convergence
| Round | Entity Count | New | Changed | Stable | Stability Ratio |
|---|---|---|---|---|---|
| 1 | 8 | 8 | - | - | N/A |
| 2–3 | 9 | 1 (WakeWord) | 0 | 8 | 89% |
| 4 | 10 | 1 (BaselineStack) | 0 | 9 | 90% |
| 5 | 11 | 1 (UtteranceTestSet) | 0 | 10 | 91% |
| 6 | 11 | 0 | 0 | 11 | 100% |
| 7 | 12 | 1 (LlmProvider) | 0 | 11 | 92% |
| 8 | 13 | 1 (Settings) | 0 | 12 | 92% |
| 9–11 | 13 | 0 | 0 | 13 | 100% |
| 12 | 14 | 1 (RejectionCooldown) | 0 | 13 | 93% |
| 13 | 15 | 1 (AdSkipTarget) | 0 | 14 | 93% |

## Interview Transcript
<details>
<summary>Q&A 요약 (13 rounds)</summary>

- **R0 범위:** 구성요소 1~4를 중심으로 하고, 5번 시연 자료는 가족 리포트 목업만 포함한다.
- **R1 RestoreVideo:** 사용자가 '이전 동영상 버튼'과 '시청 기록'을 제안했다. 이전 버튼 → 시청 기록 → ScreenAgent로 확정했다. 검색은 다른 영상이 재생될 위험이 있어 쓰지 않는다.
- **R2 동의 입력:** 버튼과 음성을 병행한다. 추가로 사용자 설정 호출어("춘식아 이거 와이라노" 같은 형태)를 요구했다.
- **R3 호출어:** Vosk를 쓰는 후순위 스트레치로 정했다. 기본 호출어는 "머고야"이고, 배터리 조항에 예외를 둔다.
- **R4 (Contrarian) R1 판정:** 카운트다운을 우선 증거로 쓴다. 사용자 요구로 오조작 감지 R3를 추가했고, S2를 데모에 포함했다.
- **R5 Brain 기준:** 35개 테스트셋(사투리 포함)에서 90% 이상이어야 한다. 위험한 오답과 JSON 파싱 실패는 0건이다.
- **R6 (Simplifier) S8:** 패널을 열고 Wi-Fi 토글을 클릭한다. 재현은 와이파이와 데이터를 꺼서 한다.
- **R7 LLM:** 시연은 Gemini Flash로 하고, 막히면 Claude Haiku 5.5로 바꾼다. 서버는 없다.
- **R8 에이전트 확인:** 목표 단위로 1회 확인하고, 설정으로 '확인 없이 실행'으로 바꿀 수 있다.
- **R9 검증:** JVM 재생 테스트와 에뮬레이터 인수 테스트의 2층으로 검증한다.
- **R10 산출물:** "이미 해커톤 진행 중", 이 저장소에 지금 바로 구현한다.
- **R11 진행:** 코드는 Claude가 전부 구현하고, 팀은 사전 검증, 인수 테스트, 촬영을 맡는다. 구현 순서를 확정했다.
- **R12 거절·실패:** 같은 대상 재제안 금지, 규칙 5분 쿨다운, 실패하면 FamilyLog에 기록한다.
- **R13 S6 페이지와 S11:** S6 페이지 설계를 확정했다. 사용자 요구로 YouTube 광고 건너뛰기(S11)를 추가하고, R4 자동 제안도 포함했다.
</details>
