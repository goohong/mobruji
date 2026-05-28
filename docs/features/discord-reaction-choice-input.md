---
feature: Discord reaction-기반 선택지 응답 + user mode toggle (AUTO / ASK)
slug: discord-reaction-choice-input
status: approved
owner: @mobruji-maestro
scope: infra
related_issues: []
related_prs: []
last_reviewed: 2026-05-28
---

# Discord reaction-기반 선택지 응답 + user mode toggle

## 1) 개요 (What / Why)

helper / nmae 가 사용자에게 **선택지를 던지는 경우** (예: "이 PR 어떻게 처리할까요? 1️⃣ 머지 2️⃣ 추가 review 3️⃣ close") 사용자가 1️⃣–🔟 keycap reaction 을 눌러 응답할 수 있게 한다. 사용자 입장에서 모바일 tap 1회 = 자유 텍스트 응답 1번 분량.

### Why event-driven (폴링 X)

사용자가 명시한 제약: "폴링으로 나의 신호를 기다려야되는 거면 너무 자원낭비". Discord Gateway 가 이미 `MESSAGE_REACTION_ADD` event 를 native push 해 준다 (`on_message` 와 동일 메커니즘). bot.py 는 추가 polling loop 없이 event handler 한 개만 추가하면 됨 — 자원 비용 0 에 가까움.

### Why mode toggle (사용자 추가 요구 2026-05-28)

[[feedback-autonomous-default]] 룰 — 기본 동작 은 helper 가 사용자에게 묻지 않고 자율 진행. 따라서 helper 가 임의로 `--choices` 를 던지면 자율 default 룰 위반. 사용자가 명시적으로 **질문 받고 싶은 시점** 을 toggle 할 수 있어야 의미가 있다.

**user mode** = `AUTO` (default, 자율) / `ASK` (질문 받는 mode). `~/.mobruji/user-mode.txt` 단일 file. helper turn-start 에서 mode 읽어 helper 가 `--choices` 사용 여부 결정.

### UX 결정 (사용자 2026-05-28)

mode toggle UX 옵션 4종 중 **Discord buttons (interaction)** 채택. 사유: 모바일에서 typing 0, 시각적 현재 상태 표시 명확, reaction-choice 의 click-only UX 와 일관.

**PR 분할**:
- **PR 1 (본 spec + 구현)**: reaction-choice input 메커니즘 + mode file infra (`~/.mobruji/user-mode.txt` read/write + helper-turn-start.sh marker). button UI 미포함 — 사용자가 직접 file 편집 또는 helper 가 read.
- **PR 2 (follow-up)**: Discord buttons 기반 mode toggle UI — `ModeToggleView` (discord.py components) + persistent view + setup 자동화. PR 1 이 deploy 된 후 시작.

## 2) 사용자 시나리오

- **시나리오 1 (ASK mode)**: 사용자가 `/mode ask` 로 mode 전환. helper turn-end 에서 "다음 우선순위 알려주세요" 를 묻고 싶다 → `discord-reply.sh --choices "다음 우선순위?" "#1191 web e2e" "#1192 rev flock" "둘 다" "보류"` 호출. 봇이 메시지 post + 1️⃣–4️⃣ reaction pre-attach. 사용자가 모바일에서 4️⃣ tap → helper 다음 turn 에 `[choice 4/4] 보류` 가 user message 로 도달 → helper 가 plain text 응답처럼 처리.
- **시나리오 2 (AUTO mode, default)**: 사용자가 mode toggle 안 함 (또는 `/mode auto`). helper 가 자율 진행 — `--choices` 호출 안 함. 사용자 부재 / 신뢰 위임 흐름 ([[feedback-autonomous-loop]]) 보존.
- **시나리오 3 (mode toggle)**: 사용자가 `/mode ask` 채널에 입력 → bot.py 가 `~/.mobruji/user-mode.txt` 를 `ASK` 로 write + reaction `👌` 로 ack. helper 다음 turn-start 에서 `===USER_MODE:ASK===` marker 받음 → helper 가 이후 결정 분기점에서 `--choices` 활용. `/mode auto` 로 다시 AUTO 전환.
- **시나리오 4**: nmae 가 사이클 결정 대기 — "release 머지 할까요?" 와 같은 binary 결정. ASK mode 일 때만 `--choices "release 머지?" "예" "아니오"` 로 2-옵션 prompt. 사용자가 빠르게 1️⃣ tap → nmae 다음 turn 에 결정 반영. AUTO mode 면 nmae 가 자율 결정 + [[feedback-rev-release-gate]] 룰만 사용자 확인.
- **시나리오 5 (자유 텍스트 fallback)**: 사용자가 ASK mode 에서 prompt 받은 후에도 reaction 안 누르고 자유 텍스트로 답함 → 기존 on_message 흐름. choice-prompt 는 active 한 상태로 남고 무시 가능 (timeout 없음).
- **시나리오 6 (동시 tap race)**: 사용자가 여러 옵션 동시 tap (3️⃣ 누른 직후 5️⃣ tap) → 첫 reaction event 만 consume 처리, 이후 reaction 은 ledger 가 dedup. 헬퍼는 첫 선택만 받음.

## 3) 요구사항

### 기능 요구사항

- [x] `discord-reply.sh --choices "<질문>" "<opt1>" "<opt2>" ... [<opt10>]` mode 신설. 메시지 post + 1️⃣–🔟 keycap reaction pre-attach + register entry append. stdout = bot message_id.
- [x] bot.py `on_raw_reaction_add` event handler 추가. choice-prompt 로 등록된 메시지의 keycap reaction 만 처리 — 다른 message / 다른 emoji 는 무시 (early return).
- [x] choice 응답 = synthetic user message (text = `[choice {N}/{total}] {label}`) 로 inbox.jsonl append + tmux send-keys → helper 는 자유 텍스트 user message 와 동일하게 처리.
- [x] event-sourcing 저장 — `~/.mobruji/choice-prompts.jsonl` 에 `event=register` + `event=consume` 2종 row. consume row 가 있으면 해당 message_id 의 prompt 는 used 처리. 단일 reactor 만 처리됨 (ledger.claim 가드).
- [x] dedup — SQLite ledger key = `choice:{message_id}:{choice_idx}`. Gateway reconnect / 사용자 toggle reaction (remove → add) 시 재진입 차단.
- [x] user mode file infra — `~/.mobruji/user-mode.txt` 단일 file (`AUTO` / `ASK`). 부재 / 잘못된 값 = `AUTO` (default).
- [x] `helper-turn-start.sh` 가 mode 읽어 turn 시작 header 에 `===USER_MODE:AUTO|ASK===` marker emit. helper LLM 이 이 marker 보고 `--choices` 사용 여부 결정.
- [ ] **(PR 2)** Discord buttons UI — `ModeToggleView` + persistent view + `on_interaction` handler + setup 자동화 (bot boot 시 채널 history scan → mode toggle message 없으면 자동 post).

### 비기능 요구사항

- **자원 비용**: 추가 polling loop 0. discord.py Gateway 가 native push.
- **반응 latency**: reaction tap → helper turn-start ≤ on_message 흐름과 동일 (수백 ms 수준).
- **graceful**: choice-prompts.jsonl 부재 / 손상 → silent skip. 기존 on_message 흐름 영향 없음.
- **idempotency**: 같은 reaction event 가 reconnect / Discord 재전송으로 두 번 도착 → ledger 가 1회만 통과.

## 4) 범위 / 비범위

### 포함

- `discord-reply.sh --choices` 신규 mode.
- bot.py `on_raw_reaction_add` event handler + 작은 helper module (`choice_prompts` 인라인 또는 분리).
- `~/.mobruji/choice-prompts.jsonl` 운영 + format 명시.
- 단위 테스트 (emoji parse, lookup/consume state machine).

### 제외 (Out of Scope)

- **timeout / 만료**: 본 spec 은 무한 active. 미응답 prompt 가 jsonl 에 누적되어도 lookup 비용은 file scan 1회 (작은 file 가정). 만료 / archive 는 follow-up.
- **다중 선택 (multi-select)**: single-select 만 지원. 다중은 별도 mode (예 `--multi-choices`) 로 추후.
- **편집 / 취소**: 한 번 push 된 choice-prompt 는 message 자체 delete / 새 prompt push 로만 갈음. UI 편집 없음.
- **반응 emoji custom**: 1️⃣–🔟 keycap 만 지원. custom guild emoji 는 비지원 (REST URL-encoding 복잡 + 사용 빈도 낮음).
- **사용자 자유 텍스트 ↔ choice 자동 매칭**: 사용자가 "둘 다" 라고 텍스트로 답해도 매칭하지 않음 — 기존 on_message 흐름만 처리. helper 가 LLM 으로 매칭 가능.

## 5) 설계

### 5-1) 데이터 모델

**File**: `~/.mobruji/choice-prompts.jsonl` (append-only event log).

**register event** (discord-reply.sh 가 append):
```json
{"event":"register","message_id":"1509...","channel_id":"1506...","choices":["머지","리뷰","close"],"ts":"2026-05-28T15:00:00Z"}
```

**consume event** (bot.py 가 append):
```json
{"event":"consume","message_id":"1509...","choice_idx":0,"user_id":"...","ts":"2026-05-28T15:00:05Z"}
```

**lookup 규칙**:
- 한 message_id 의 register entry 가 있고, 같은 message_id 의 consume entry 가 없으면 **active**.
- consume entry 가 있으면 used → 추가 reaction 무시.
- 동일 message_id 의 register 가 여러 row 있으면 마지막 register 가 SoT (사실상 불가능 — message_id 는 Discord 가 unique 발급).

**race 안전성**:
- discord-reply.sh = register writer 만. bot.py = consume writer 만. 서로 다른 process 가 같은 row 를 안 씀.
- bot.py 의 ledger.claim (SQLite, atomic) 이 첫 reactor 만 통과 → consume row 도 1회만 append.

### 5-2) emoji 매핑

`1️⃣` = `U+0031 U+FE0F U+20E3` (digit-1 + variation-selector-16 + keycap), `🔟` = `U+1F51F`.

| emoji | discord.py str | index |
|---|---|---|
| 1️⃣ | `"1️⃣"` | 0 |
| 2️⃣ | `"2️⃣"` | 1 |
| 3️⃣ | `"3️⃣"` | 2 |
| 4️⃣ | `"4️⃣"` | 3 |
| 5️⃣ | `"5️⃣"` | 4 |
| 6️⃣ | `"6️⃣"` | 5 |
| 7️⃣ | `"7️⃣"` | 6 |
| 8️⃣ | `"8️⃣"` | 7 |
| 9️⃣ | `"9️⃣"` | 8 |
| 🔟 | `"\U0001f51f"` | 9 |

discord-reply.sh REST `PUT /channels/{cid}/messages/{mid}/reactions/{emoji}/@me` 호출 시 URL-encoded form. 1️⃣ → `1%EF%B8%8F%E2%83%A3`, 🔟 → `%F0%9F%94%9F`.

### 5-3) bot.py event handler 흐름

```text
on_raw_reaction_add(raw_payload)
  → channel/user/bot-self filter (early return)
  → parse_choice_emoji(emoji_str) → idx | None
  → lookup_choice_prompt(message_id) → register dict | None (consume 가 있으면 None)
  → ledger.claim(f"choice:{msg_id}:{idx}") — dedup
  → mark_choice_consumed(append consume row)
  → append_inbox(synthetic payload: text=f"[choice {idx+1}/N] {label}")
  → write_last_user_msg_id(bot_msg_id)
  → tmux_send_payload(text)
```

기존 `on_message` 흐름과 거의 동일 — 마지막 3 줄은 같은 path 재사용.

### 5-4) discord-reply.sh `--choices` mode 흐름

```text
discord-reply.sh --choices "<질문>" <opt1> <opt2> ... <opt10>
  → 본문 build (질문 + \n\n + 1️⃣ opt1 + \n + 2️⃣ opt2 + ...)
  → resolve_reply_to_id (사용자 메시지 reply 형태 — 일관성)
  → post_channel_message(payload) → message_id
  → for i in 0..len-1: reaction_add_emoji(message_id, KEYCAP_URLENC[i])
  → append register row to ~/.mobruji/choice-prompts.jsonl
  → stdout: message_id
```

### 5-5) 새 helper 함수 / 모듈

**bot.py 추가** (대략 80 LOC):
- `CHOICE_PROMPTS_PATH: Final[Path]`
- `NUMBER_KEYCAP_TO_INDEX: Final[dict[str, int]]`
- `parse_choice_emoji(emoji_str) -> int | None`
- `lookup_choice_prompt(message_id, path=CHOICE_PROMPTS_PATH) -> dict | None`
- `mark_choice_consumed(message_id, choice_idx, user_id, path=CHOICE_PROMPTS_PATH) -> None`
- `@client.event on_raw_reaction_add(...)` — 약 40 LOC.

**discord-reply.sh 추가** (대략 60 LOC):
- arg parse `--choices`
- `MODE="choices"` case block (post + reactions + register append)
- `reaction_add_emoji <message_id> <url_encoded_emoji>` — 기존 `reaction_add()` 를 emoji 파라미터화 (signature 확장, 기본값 = `$BOT_WRITING_REACTION_EMOJI`).

### 5-6) 테스트

`test_bot.py` 에 추가 (대략 50 LOC):

- `parse_choice_emoji` — 9 keycap + 🔟 + 비매칭 emoji + 임의 string.
- `lookup_choice_prompt` — register only (active), register+consume (used), no register (None), 손상 line graceful skip.
- `mark_choice_consumed` — append + 후속 lookup 이 None 반환.

### 5-7) backward compat / rollout

- 기존 on_message 흐름 영향 없음 — 새 event handler 는 별도 path.
- `--choices` mode 미사용 시 `choice-prompts.jsonl` 부재 — `lookup_choice_prompt` 가 graceful None.
- helper / nmae 가 `--choices` 호출 안 하면 기존 자유 텍스트 흐름 그대로.
- feature flag 불필요 — 단일 toggle 없이 additive.

### 5-8) user mode toggle 흐름 (PR 1: file infra, PR 2: buttons UI)

#### PR 1 — file infra

```text
~/.mobruji/user-mode.txt = "AUTO" (default) | "ASK"
                                              ↑
                                              PR 2 에서 button click 으로 write.
                                              PR 1 에서는 사용자가 직접 file 편집 또는
                                              helper 가 임시로 write 가능.

helper turn-start (이미 helper-queue 에 user msg 도착)
  → helper-turn-start.sh 실행 (PR #1128)
  → 신규 step: read ~/.mobruji/user-mode.txt → emit `===USER_MODE:ASK===` (또는 AUTO)
  → 기존 step (target freeze, cycle-status, queue) 이후 출력
helper LLM
  → 본문 처리 + USER_MODE 마커 인식
  → mode=AUTO: 자율 진행 ([[feedback-autonomous-default]])
  → mode=ASK: 결정 분기점에서 `discord-reply.sh --choices ...` 호출 권장
```

**유효 mode**: `AUTO` (대문자) / `ASK` (대문자). 비교는 case-insensitive (대소문자 무시), write 는 항상 대문자.

**graceful**: file 부재 / 비어 있음 / 잘못된 값 → `AUTO` (default — autonomous 룰 보존).

#### PR 2 — buttons UI (follow-up)

```text
bot boot
  → on_ready event
  → MOBRUJI_CHANNEL_ID 채널 history scan (마지막 100건)
  → 자기가 post 한 mode-toggle message 검색 (marker text 매칭)
  → 없으면: 자동 post + add_view(ModeToggleView, message_id)
  → 있으면: add_view(ModeToggleView, message_id=existing) — persistent re-attach
사용자 button click
  → on_interaction (discord.py)
  → ModeToggleView.<button>_callback(interaction, button)
  → allowed_user_ids check
  → write user-mode.txt = "ASK"|"AUTO"
  → interaction.response.edit_message(view=ModeToggleView(new_mode))
       → 시각적 강조: 현재 mode button = success(green), 다른 button = secondary(grey)
```

button custom_id: `mobruji-mode-ask` / `mobruji-mode-auto`. message 본문 marker: `[MODE_TOGGLE_v1]` (bot 이 history scan 시 매칭). View timeout=None (persistent).

## 6) rollback plan

문제 시 즉시:
1. `--choices` mode 호출 중단 (helper / nmae 의 manual usage 만 — wrapper 자동 호출 없음).
2. bot.py `on_raw_reaction_add` 핸들러 비활성화: 함수 시작부에 `return` 추가 → 재시작.
3. 또는 develop 에서 PR revert + `tools/discord-daemon/deploy.sh` 로 NCP 봇 재기동.

`choice-prompts.jsonl` 은 남기더라도 영향 없음 (event log 뿐).

## 7) 작업 분할

- [x] PR 1 (본 spec + 구현): spec 작성 + bot.py event handler + discord-reply.sh `--choices` mode + helper-turn-start.sh USER_MODE marker + mode file read/write helper + 단위 테스트. mid-sized single PR.
- [ ] PR 2 (follow-up): Discord buttons mode toggle UI — `ModeToggleView` + persistent view + `on_interaction` + boot 시 history scan + 자동 post. PR 1 deploy 후 시작.
- [ ] PR 3 (follow-up, optional): timeout / 만료 cleanup loop — `choice-prompts.jsonl` rotate (실측 후 필요 시).
- [ ] PR 4 (follow-up, optional): emoji 의미 명료화 (예: 범례 push, [[feedback-discord-reaction-emoji-clarity]] 연계).

## 8) 테스트 전략

### 단위 (PR 1 포함)
- `parse_choice_emoji` 9+1 keycap + 비매칭.
- `lookup_choice_prompt` register-only / register+consume / 부재 / 손상 line.
- `mark_choice_consumed` append idempotency.

### 통합 (수동, NCP 배포 후)
1. NCP `tools/discord-daemon/deploy.sh` 로 develop 반영.
2. NCP helper pane 에서 `discord-reply.sh --choices "테스트" "예" "아니오"` 호출.
3. Discord 채널에 메시지 + 1️⃣ 2️⃣ reaction pre-attach 확인.
4. 모바일 / 데스크탑에서 1️⃣ tap.
5. helper pane 에 synthetic message `[choice 1/2] 예` 도달 확인.
6. helper 가 응답하는지 확인.
7. 동일 message 에 2️⃣ tap → bot 무시 (이미 consumed) 확인.

## 9) 결정 로그

- 2026-05-28 — 사용자 요청: helper 가 선택지 던질 때 reaction tap 으로 응답. 명시 제약: 폴링 X. **결정**: discord.py `on_raw_reaction_add` Gateway event 사용 (native push, 자원 0). single-select MVP, max 10 opts (keycap 한계).
- 2026-05-28 — **결정 1**: storage = event-sourced JSONL (register + consume 2종 row). 사유: append-only race-safe + bot.py / discord-reply.sh 가 disjoint writer. in-place rewrite 회피.
- 2026-05-28 — **결정 2**: dedup = SQLite ledger.claim (기존 on_message 흐름 재사용). 사유: Gateway reconnect race + 사용자 toggle reaction 모두 1 key 로 cover.
- 2026-05-28 — **결정 3**: synthetic text = `[choice N/total] <label>`. 사유: helper LLM 이 명시적으로 선택지 응답 인식 가능 + label 도 함께 — context.
- 2026-05-28 — **결정 4**: timeout 미설정 (MVP). 사유: file scan 비용 작음 + 미응답 누적 패턴 실측 후 cleanup loop 도입 가능.
- 2026-05-28 — **결정 5 (사용자)**: mode toggle UX = Discord buttons (interaction). 사유: 모바일 typing 0 + 시각적 현재 상태 명확 + reaction-choice click UX 와 일관. 옵션 4종 중 (reaction-pinned / slash-cmd / 자연어 / buttons) 선택.
- 2026-05-28 — **결정 6**: PR 분할 — PR 1 = reaction-choice + mode file infra (helper 가 mode 인식 가능한 minimal layer), PR 2 = buttons UI. 사유: buttons impl 이 별도 분량 (interaction handler + persistent view + setup 자동화) 이고 PR 1 머지만으로도 helper 가 mode 인식하는 minimum 가치 발생.

## 10) 자율 결정 (사유)

- 사용자가 명시한 핵심 (event-driven, 폴링 X) 외 detail 4건 자율. 분기점 변경 요청 시 frontmatter status 갱신 + PR 머지 후 자동 반영.

## 11) 사용자 확인 필요

- 없음 (MVP 자율 진행). 사용 중 feedback 가 누적되면 follow-up PR.

## 12) References

- `tools/discord-daemon/bot.py` `on_message` — 이번 신규 핸들러가 mirroring 하는 흐름.
- `tools/discord-daemon/discord-reply.sh` `reaction_add` / `post_channel_message` — 재사용.
- 메모리: [[feedback-discord-reaction-emoji-clarity]] (이전 사용자 confusion — 본 spec 의 UX 명료화 연관) / [[project-discord-channel]] / [[feedback-discord-tone-formal]].
- Discord docs — `MESSAGE_REACTION_ADD` Gateway event, `PUT /channels/{ch}/messages/{msg}/reactions/{emoji}/@me`.

## 13) 변경 이력

- 2026-05-28 — 초안 작성 + PR 1 (사양 + 구현 + 테스트). status=approved (사용자 요청 직접 응답).
