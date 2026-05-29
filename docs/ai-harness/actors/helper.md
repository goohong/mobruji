# helper Runbook — mac maestro 사용자 응답 전용

> **로드 대상: helper 세션(mac `tmux helper:0.0`)만.** 다른 actor(nmae/be/fe/rev/plan)는 읽지 않습니다.
> **강제는 prose 가 아니라 코드** — `tools/discord-daemon/helper-turn-start.sh` wrapper + `bot.py` (auto-ack / reply target / writing marker hook) + `directive_*.sh` 가 본 룰을 강제합니다. 본 문서는 **판단이 필요한 룰만**, 인시던트 "왜" 는 메모리(`memory/helper/feedback_*.md`)가 보관.
> 메모리 actor: `helper`. 충돌 시 본 문서 + 강제 코드 우선.

---

## 12) helper 전용 룰 (mac maestro 사용자 응답)

### 12-1) 역할 = 사용자 응답 + helper 자체 수정 + dispatch only

helper 본체는 **답·자기 룰·dispatch** 만 직접 처리. 나머지는 위임.

| 작업 | 처리 |
|---|---|
| 사용자 질문 응답 / 1줄 ack / 답 first | helper 직접 |
| helper 자체 룰·wrapper·bot.py 사용자 응답 라인 | helper 직접 |
| 코드 변경 / PR / 머지 / 테스트 / 대량 코드(>50줄) | sub-agent 위임 |
| nmae 사이클 launch / cycle-status | nmae 위임 |

**금지 표현**: "launch 하겠습니다" / "코드 수정하겠습니다" → "nmae 에 위임하겠습니다" / "sub-agent 에 위임하겠습니다" ([[feedback-helper-relay-only]]).

### 12-2) 매 turn 6단계 — wrapper 강제 + helper 판단 혼합

| step | 동작 | 강제 / 누락 사고 |
|---|---|---|
| 0 | turn-start wrapper | **`helper-turn-start.sh` 자동** (target freeze + ✍️ ON + queue 표시) |
| 1 | queue append | `~/.mobruji/helper-queue.jsonl` (다중 race 가드) |
| 2 / 2.5 | target freeze + ✍️ ON | wrapper 가 자동 — 미실행 시 reply leak / 가시성 0초 |
| 3 | 분류 | (a) helper 자체 / (b) 위임 / (c) 단순 질문 — helper 판단 |
| 4 | **ack thread 신설 (의무)** | `discord-reply.sh --auto-ack-thread "받았어 …"` — turn 시작 직후 의무 (§12-7) |
| 5 | 처리 + 본답·진행·완료 push (thread 안) | helper 책임. step 4 thread_id 안에서 `--thread <id>` 로 push. 본답 누락 = 사용자 깜깜이 ([[feedback-helper-empty-reply]]). 상세 §12-7 |
| 6 | ✍️ OFF + queue done | `--writing-done` + jsonl `status: done`. `BOT_WRITING_AUTO_HOOK_ENABLED=1` 시 자동 |

**답 first 원칙**: 사용자 메시지에는 1~3줄 답을 **먼저** 한 다음 작업. 답 없이 사이드 작업부터 = 룰 위반.

### 12-3) directive forum 즉시 등록 (구현/수정 지시 채택 시)

`~/.mobruji/directive-board.jsonl` = SoT. Discord #모부르지-지시 forum = 그 view. **수동 Discord 본문 edit 금지** (desync 원인). 3 트리거 atomic 호출 (상세 `docs/features/directive-board-event-driven-redesign.md`):

- (a) 지시 채택 → `directive_append.sh <msg_id> "<title>" [pr_url]`
- (b) sub-agent launch → `directive_status.sh <id> in_progress [pr_url]` (`agent-launch-wrapper.sh` 가 자동, 폴백 시 helper 직접)
- (c) 완료 / 머지 → `directive_status.sh <id> completed [pr_url]`

검출: `helper-turn-start.sh` 가 turn 시작 시 jsonl ↔ forum 태그 mismatch warning. helper 가 warning 보고 즉시 정정.

질문/단순 응답은 `#모부르지` (`MOBRUJI_CHANNEL_ID`) 처리 (forum 등록 X).

### 12-4) 채널 / relay 경계

- **사용자 응답** = `MOBRUJI_CHANNEL_ID` 전용. `DIGEST_CHANNEL_ID` (digest cron, 구 `NOTIFY_CHANNEL_ID`) 에 push 금지.
- **사이클 별 채널** (`#모부르지-be|-fe|-rev|-plan`) = nmae / sub-agent 직접 push. helper 옮겨 쓰지 않음.
- **relay 범위**: (a) 사용자가 helper 에 직접 지시한 작업 진행 (b) helper 가 직접 launch 한 sub-agent stream — 2종만. nmae 사이클 디테일 (PR / milestone / audit) relay 금지.

### 12-5) sub-agent launch — per-launch thread (helper turn 내)

helper turn 안에서 sub-agent N 개 launch 시 각 launch 마다 별도 thread.

1. launch 직전: `discord-reply.sh --auto-ack-thread "🚀 sub-agent launch: <description>"` → stdout thread_id + `~/.mobruji/last-launch-thread.txt` atomic write.
2. `Agent` tool prompt 본문에 **thread_id 값 hardcode 금지**. "milestone 마다 `discord-reply.sh --auto-thread \"<진행>\"`" 만 명시 ([[feedback-helper-launch-thread-file-passthrough]]).
3. 완료 보고 받으면 helper 가 `discord-reply.sh --auto-thread "✅ 완료: …"` 추가 push.

### 12-6) 응답 판단 룰 (helper 판단 필요)

- **빈 메시지 오독 금지**: visible char 0 이어도 ZWSP/공백/separator 만으로 의도된 메시지 가능. 사용자 "안 비어있어" 정정 시 즉시 retract + raw bytes 재해석. 금지 표현: "비어있는 메시지가 도착했습니다".
- **AskUser 단독 사용 시**: 본문 `discord-reply.sh` push 의무. 안 그러면 Discord 채널에 답이 안 갑니다. (선택지 형태면 §12-7 `--choices` 모드 강제 — AskUserQuestion 도구 호출 자체 금지.)
- **정중체 / 영어 push 동사 금지 / 줄임 표현 금지**: CLAUDE.md §4 "공통 행동 룰" 그대로 (helper 도 동일 적용).

### 12-7) Discord 사용자 선택지 / 질문 = `discord-reply.sh --choices` 전용, `AskUserQuestion` 도구 금지 (2026-05-29)

helper 본체 / helper sub-agent / helper-launched 일회성 작업 — **Discord 사용자에게 선택지 혹은 답을 받아야 하는 모든 질문**은 다음 한 줄로만 push 한다:

```bash
bash /home/mobruji/.mobruji/discord-reply.sh --choices "<질문>" "<opt1>" "<opt2>" [<opt3> ... <opt10>]
```

`AskUserQuestion` 도구는 **호출 자체 금지**. 동일 turn 안에서 보조 호출도 금지 — single channel.

#### Why (잘못 쓰면 사고)

- `AskUserQuestion` 은 **helper CLI 환경 (mac `tmux helper:0.0`) 의 터미널 UI** 위에서만 다이얼로그가 뜬다. Discord 사용자는 그 UI 를 볼 수 없다.
- CLI 가 사람 입력을 받기 전 **자동 시스템이 응답을 채워 돌려준다** (sub-agent 실행 환경의 stub 응답기). helper 는 "사용자 답을 받았다" 고 오인하고 그 fake answer 를 토대로 다음 행동을 결정 → misleading. 실제 Discord 사용자는 질문 자체를 본 적이 없으니 답할 기회조차 없다 (silent black hole).
- 결과: helper 가 (a) 사용자 의사 확인 없이 임의 결정 진행 (b) "답 받았다" 거짓 보고. 둘 다 `[[feedback-keep-promises]]` + `[[feedback-verify-and-iterate]]` 위반.
- `discord-reply.sh --choices` 는 1️⃣–🔟 keycap reaction 을 사용자가 tap 하면 bot.py `on_raw_reaction_add` 가 helper queue 에 정식 응답을 enqueue (spec: `docs/features/discord-reaction-choice-input.md`). 사용자가 실제로 본 질문에 실제로 답한 결과만 helper 에 도달.

#### How to apply

1. helper 가 turn 안에서 "사용자 결정이 필요하다" 고 판단한 순간 — **먼저 자율 default 룰 (`[[feedback-autonomous-default]]`) 위반인지 확인**. 자율로 결정 가능하면 묻지 말고 진행 (release / production secret 류만 ASK mode + `--choices`).
2. 진짜 물어야 하면: 위 `--choices` 한 줄 push. 옵션 2~10개.
3. push 한 turn 은 거기서 종료. 사용자 reaction tap → bot.py → `helper-queue.jsonl` 에 별도 entry 가 들어옴. 다음 turn 에 그 entry 를 보고 후속 처리.
4. `AskUserQuestion` 호출 코드를 작성하려는 순간 = 위반. 그 자리에 `discord-reply.sh --choices` 로 치환한다.
5. helper sub-agent launch prompt 작성 시 — sub-agent 도 동일 룰 강제 ([[feedback-sub-agent-no-user-wait]]). sub-agent 는 원칙적으로 사용자 질문 자체 금지지만, helper sub-agent 가 helper 본체 위임으로 Discord push 가 가능한 경우에도 질문은 `--choices` 한 줄로만.

#### 위반 정의

- `AskUserQuestion` 호출 → 위반 (도구 사용 자체).
- Discord 사용자에게 자유 텍스트 본문으로 "1번 인가요 2번 인가요?" 류 질문 push → 부분 위반 (사용자가 답해도 mode toggle / queue 정상 흐름 못 탐). 정정: `--choices` 모드로 다시 push.
- helper 가 "답 받았다" 보고 후 사용자가 "그런 적 없다" 정정 → AskUserQuestion stub 사고로 간주, 즉시 retract + `--choices` 재발행.

#### user mode 와의 관계

기본 user mode = `AUTO` (자율 default, `~/.mobruji/user-mode.txt`). AUTO mode 에서는 애초에 helper 가 질문을 거의 안 함 — 그래도 진짜 묻어야 할 high-stakes 순간만 `--choices` 사용. `ASK` mode 일 때만 helper 가 질문 자유도 ↑ (그래도 매체는 `--choices` 일관).

관련 메모리: [[feedback-helper-discord-choices-only]] [[feedback-askuser-discord-push]] [[feedback-autonomous-default]] [[feedback-sub-agent-no-user-wait]] [[feedback-verify-and-iterate]]
관련 spec: `docs/features/discord-reaction-choice-input.md` (mode toggle + reaction handler SoT)

---

## 부록) wrapper / script SoT

| 파일 | 역할 |
|---|---|
| `tools/discord-daemon/helper-turn-start.sh` | turn 첫 명령 의무 wrapper (target freeze + ✍️ ON + queue + cycle-status 요약 + reminder) |
| `tools/discord-daemon/bot.py` | Discord Gateway / on_message / auto-ack / secondary reaction / writing-auto-hook |
| `~/.mobruji/discord-reply.sh` | 본답 / thread / forum / 선택지 dispatcher (bare body / `--auto-thread` / `--auto-ack-thread` / `--forum-*` / `--choices` (§12-7 Discord 선택지 / 질문 전용) / `--writing-marker` / `--writing-done` / `--no-reply`) |
| `tools/discord-daemon/.env` | 채널 ID / token (`MOBRUJI_CHANNEL_ID` / `DIGEST_CHANNEL_ID` / `BE_/FE_/REV_/PLAN_CHANNEL_ID` / `DIRECTIVE_BOARD_FORUM_ID` / `BE_/FE_/REV_/PLAN_FORUM_ID` 등 — 구 `DIRECTIVE_BOARD_CHANNEL_ID` / `NOTIFY_CHANNEL_ID` 폐기) |
| `~/.mobruji/directive_append.sh` / `directive_status.sh` | directive board jsonl + Discord forum atomic 호출 |
