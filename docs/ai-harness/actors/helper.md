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
| 4 | (선택) thread 생성 | 장시간 작업 시 `discord-reply.sh --auto-ack-thread "🔍 …"` |
| 5 | 처리 + 본답 push | helper 책임. 본답 누락 = 사용자 깜깜이 ([[feedback-helper-empty-reply]]) |
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
- **AskUser 단독 사용 시**: 본문 `discord-reply.sh` push 의무. 안 그러면 Discord 채널에 답이 안 갑니다.
- **정중체 / 영어 push 동사 금지 / 줄임 표현 금지**: CLAUDE.md §4 "공통 행동 룰" 그대로 (helper 도 동일 적용).

---

## 부록) wrapper / script SoT

| 파일 | 역할 |
|---|---|
| `tools/discord-daemon/helper-turn-start.sh` | turn 첫 명령 의무 wrapper (target freeze + ✍️ ON + queue + cycle-status 요약 + reminder) |
| `tools/discord-daemon/bot.py` | Discord Gateway / on_message / auto-ack / secondary reaction / writing-auto-hook |
| `~/.mobruji/discord-reply.sh` | 본답 / thread / forum mode dispatcher (bare body / `--auto-thread` / `--auto-ack-thread` / `--forum-*` / `--writing-marker` / `--writing-done` / `--no-reply`) |
| `tools/discord-daemon/.env` | 채널 ID / token (`MOBRUJI_CHANNEL_ID` / `DIGEST_CHANNEL_ID` / `BE_/FE_/REV_/PLAN_CHANNEL_ID` / `DIRECTIVE_BOARD_FORUM_ID` / `BE_/FE_/REV_/PLAN_FORUM_ID` 등 — 구 `DIRECTIVE_BOARD_CHANNEL_ID` / `NOTIFY_CHANNEL_ID` 폐기) |
| `~/.mobruji/directive_append.sh` / `directive_status.sh` | directive board jsonl + Discord forum atomic 호출 |
