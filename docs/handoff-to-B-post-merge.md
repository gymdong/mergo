# B에게 전달 — A의 병합 후 변경 (2026-10-30)

> **용도:** B가 `fix/post-ai-merge`를 병합받기 전에 읽는 문서. 기준은 `ae86d7a` 이후 A가 올린 커밋이다. 결정 배경은 [`adaptive-agent-learning-handoff.md`](./adaptive-agent-learning-handoff.md), 시나리오와 결과 분류 기준은 [`persona-scenarios.md`](./persona-scenarios.md)에 있다. 이전 인계 문서 [`handoff-to-B.md`](./handoff-to-B.md)의 "AI 쪽은 모두 가짜", "테스트 72개", §5 유예 듣기는 더 이상 맞지 않는다.

## 1. 한눈에 보기

| 커밋 | 내용 | B에게 영향 |
|---|---|---|
| `b9c97da` | 자동 제안에 무응답이면 사용자가 다시 움직일 때까지 자동 제안을 멈춘다 | 호출어 → `onHelp`가 멈춘 제안을 다시 켠다(§3) |
| `16f551c` | 30초 유예 듣기 삭제 | **`model/Grace.kt` 삭제.** 디버그 방송이 항상 도와줘 흐름으로 간다(§2, §3) |
| `48e0530` | 복구 결과 4분류(`RecipeOutcome`) | **`model/Recipes.kt` 계약 변경.** 화면 에이전트 결과 처리 방식이 바뀌었다(§4) |
| `e1d1243` | "유튜브 어딨어"처럼 이름으로 앱 열기 | **`model/Apps.kt` 추가, `ai/` 3개 파일 수정**(§5) |
| `d4529e4`, `6e04d66` | 논의·페르소나 문서 | 없음 |

`./gradlew testDebugUnitTest` 기준 **143개 통과, 실패 0개**.

## 2. 공동 계약(`model/`) 변경

| 파일 | 변경 | B가 할 일 |
|---|---|---|
| `Grace.kt` | **삭제.** `GraceListener`, `GracePhrases` 없음 | B 브랜치에서 이 파일을 고쳤다면 병합 때 modify/delete 충돌이 난다. **삭제 쪽으로 정리**한다. `GraceListener`를 구현한 클래스가 있으면 함께 지운다 |
| `Recipes.kt` | `Recipe.run()`이 `Boolean` 대신 `RecipeOutcome`을 돌려준다. `RecipeOutcome`(Verified·Guided·Unverifiable·Failed)과 `FailReason` 추가 | `Recipe`를 직접 구현한 B 코드가 있으면 반환형을 바꾼다. 분류 기준은 `persona-scenarios.md` §4 |
| `Apps.kt` | **새 파일.** `KnownApps` — 이름으로 열 수 있는 앱 허용 목록(유튜브·카카오톡·사진첩·카메라) | 앱을 추가하려면 여기에 `id`·목적격 이름·발화 단어·패키지 후보를 넣는다 |

`Screen.kt`(`ScreenAgent.achieve(): Boolean`), `Voice.kt`, `Brain.kt`는 바꾸지 않았다.

## 3. 음성·호출어 작업과 맞물리는 것

- **호출어 뒤 문장은 `controller.onHelp(text)`로 넘긴다.** 유예 듣기 경로(`onGraceHeard`)는 없어졌다.
- **`onHelp`는 시작할 때 `detector.resumeSuggestions()`를 부른다.** 직접 불렀다는 것은 사용자가 깨어 있다는 뜻이라, 무응답으로 멈춘 자동 제안을 다시 켠다. 호출어 경로도 `onHelp`를 거치면 따로 할 일은 없다.
- **디버그 방송:** `adb shell am broadcast -a app.mergo.DEBUG_HEAR --es text "소리가 안 나" -p app.mergo`가 이제 항상 `onHelp(text)`로 간다. `--ez help true`는 필요 없다.
- **상시 듣기를 넣을 때 같이 정할 것**(A 의견):
  - 하루 종일 켜 둘 수 있는 배터리 소모
  - Android 12+ 마이크 사용 표시(초록 점)와 마이크용 포그라운드 서비스 타입
  - TV·영상 속 말소리가 호출어로 잡히는 오인식
  - "항상 듣지만 '머고야'만 찾는다"는 사용자·가족 안내
  - 호출어 뒤 받아쓰기를 기기 안에서만 할지(`SpeechRecognizer`는 기기에 따라 서버로 보낼 수 있다)

## 4. 화면 에이전트 결과 처리

- `MergoService`의 `SCREEN_AGENT` 레시피는 `achieve()`가 `true`면 **`Unverifiable`**, `false`면 `Failed(AGENT_GAVE_UP)`이다. LLM의 `done`은 독립 검증이 아니기 때문이다.
- 그래서 에이전트가 끝나면 컨트롤러는 "다 됐어요" 대신 **"제대로 됐는지 봐 주세요"** 하고 "이 화면이 맞나요?"를 묻는다. "예"면 해결, 무응답이면 미확인, "아니요"면 가족 알림 질문이다. S6 시연(AC-A3)에서 말이 바뀌는 점을 확인해 달라.
- `RestoreVideo` 안의 에이전트 단계는 영상 제목으로 검증하므로 그대로 `Verified`/`Failed`가 된다.
- **B에게 제안(결정 필요):**
  1. `achieve()`가 실패 사유(안전 중단·단계 초과·LLM 포기)를 돌려주도록 `Screen.kt` 계약을 바꿀지. 지금은 모두 `AGENT_GAVE_UP` 하나다.
  2. 목표별 검증 조건. 예: "광고 닫기"는 닫기 노드가 사라지고 같은 앱이 유지되면 성공. 생기면 그 목표는 `Verified`/`Failed`로 바뀐다.

## 5. B 소유 파일을 A가 고친 곳

| 파일 | 변경 |
|---|---|
| `ai/KeywordRules.kt` | `OPEN` 말 목록과 `openApp()` 추가. 앱 이름 + 요청 말("어딨어·열어·켜 주세요·틀어 줘")이면 `open_previous_app` 후보. 되돌리기(`BACK`)·불평(`VAGUE`) 말이 섞이면 앱 열기로 보지 않는다 |
| `ai/MergoBrain.kt` | `paramsFor`가 `params.app`을 넣고 `sayFor`가 "유튜브를 열까요?"를 만든다. `guard`가 `open_previous_app` LLM 결정에서 `pkg`를 버리고, 목록에 없는 `app`이면 `cannotOpen()`("그 앱은 제가 열어 드리기 어려워요.")으로 바꾼다 |
| `ai/LlmPrompt.kt` | 입력에 `apps`(허용 앱 id) 추가, 앱 열기 규칙 한 줄 추가 |
| `report/ReportActivity.kt` | 결과가 `guided`면 "안내함"으로 보인다 |
| `test/.../ai/MergoBrainTest.kt` | 앱 열기 관련 테스트 8개 추가 |

`ai/AndroidVoiceIO.kt` 등 음성 파일은 건드리지 않았다. `BrainUtteranceSetTest`(AC-B5 발화 정답률)는 그대로 통과한다.

## 6. B가 확인하거나 정해 줄 것

1. **`open_internet_panel` 미등록.** 레시피 목록에 구현이 없어, 도와줘에서 이 후보가 골라지면 `onHelp`가 되묻기처럼 처리해 "인터넷을 다시 연결해 볼까요?"라고 **말만 하고 아무것도 하지 않는다.** 구현할지(S8 스트레치), 후보에서 뺄지 정해야 한다.
2. **"소리가 안 나" + 볼륨 정상.** `KeywordRules`가 볼륨과 상관없이 `set_volume`을 고른다. 볼륨 > 0이면 다른 원인(일시정지·이어폰 출력)을 묻도록 바꾸는 것을 제안한다(`persona-scenarios.md` P1-3, P2-1).
3. **"전화가 안 돼".** 오프라인이면 "안 돼" 때문에 인터넷 패널 후보가 된다. 비행기 모드는 맞지만 통화 문제와 구분하지 못한다(P4-4). 발화 테스트셋에 추가를 제안한다.
4. 위 §4의 에이전트 계약·목표별 검증.
