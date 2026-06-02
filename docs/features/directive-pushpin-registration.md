---
feature: Directive board — 📌 pushpin reaction 기반 명시 등록 (classify 자동 등록 폐지)
slug: directive-pushpin-registration
status: approved
owner: @mobruji-maestro
scope: infra
related_issues: []
related_prs: []
last_reviewed: 2026-05-28
---

# Directive board — 📌 pushpin 명시 등록 (classify 자동 등록 폐지)

> **갱신 (#1385, 2026-05-30)** — 빈 본문 사고 fix + 쓰레드 맥락 요약:
> - **사고**: bot 답장 메시지에 (쓰레드 안에서) 📌 → `(빈 본문)` 으로 등록. root cause: `PinConfirmView._register` 가 확보한 summary 를 안 넘기고 `_do_register_directive` 의 fallback 재조회가 `text_channels` 만 훑어 쓰레드 안 메시지를 못 찾음.
> - **fix (A)**: `_do_register_directive` fallback 이 active threads 도 조회 + `PinConfirmView._register` 가 즉시 raw 등록이 아니라 정리 → O/X → 수정 loop dialogue 경유 (매칭 없는 경로와 통일). `PinDialogueView._revise` 가드는 실제 thread 면 수정 loop 허용 (main 채널 fallback 만 차단). bot 자기 메시지에 📌 도 정상 등록.
> - **요약 (B/C)**: 등록 직전 정리는 단건 메시지가 아니라 **핀 메시지가 속한 쓰레드 전체 맥락**(`_fetch_thread_context`)을 요약. `_run_claude_summarize` 가 `{짧은 제목, 정제 본문}` 동시 산출 — 제목은 forum thread name, 본문은 template `💬 요약` 섹션. 자세히: [[directive-board-template-and-tags]].

## 1) 개요 (What / Why)

`bot.py` `on_message` 의 **classify 기반 자동 directive 등록** (PR #1173) 이 한국어 regex 한계로 false-positive 다발. 사용자 직접 정정 (2026-05-28): "무슨 말을 하면 바로 지시 포럼에 그말 그대로 추가되고 관리가 안되는 거 같은데 의도한바 맞아?".

자동 등록 의도와 다른 흐름:
- "잔존 작업들 어떻게 정리할래??" → `directive-mixed` 분류 → 자동 등록 (사용자는 질문 의도)
- "rev만 재개해봐" → `directive-ambiguous` → 자동 등록
- summary = raw 본문 80자 절단 (의도 정제 X)
- owner / status / related 필드 미설정 → 관리/추적 dashboard 역할 상실, 발화 archive 로 전락

### 사용자 결정 (2026-05-28)
"내가 관리 추적하고 싶은 것들을 모으는 거 어때" + "이모지로 가자. 너가 미리 하나는 넣어두고 내가 그걸 누르면 등록".

→ **bot 이 매 사용자 메시지에 📌 자동 부착** (passive marker) + **사용자가 추적 원하는 메시지에서 📌 tap 시 등록** (active signal). emoji 입력 0, 1 tap UX, retro-register 가능 (시간 지난 메시지도 박을 수 있음).

## 2) 사용자 시나리오

- **시나리오 1 (즉시 등록)**: 사용자 "release 머지 가도 될까요?" 메시지 → bot 이 📌 자동 부착 → 사용자가 즉시 📌 tap → `directive_append.sh` 호출 → forum thread 신설 → bot 이 ✅ 추가 부착 ("등록됨" 시각화).
- **시나리오 2 (retro 등록)**: 사용자가 한 시간 전 보낸 결정 메시지를 다시 발견 → 그 메시지의 📌 (이미 부착돼 있음) tap → 등록. 시간 무관.
- **시나리오 3 (등록 안 함)**: 사용자가 평소 일반 대화 → bot 📌 부착되지만 사용자 tap 안 함 → 등록 X. forum noise 0.
- **시나리오 4 (재 tap)**: 이미 등록된 메시지에 📌 다시 tap → jsonl lookup 으로 idempotent skip (중복 등록 차단).
- **시나리오 5 (helper / 다른 사람 답변 박기)**: 사용자가 helper 답변 메시지에 📌 tap → bot 이 그 메시지를 directive 로 등록 (사용자가 helper 의 특정 답을 archive 하고 싶을 때).

## 3) 요구사항

### 기능 요구사항

- [x] `bot.py` `on_message` 의 **classify 기반 자동 `directive_append.sh` 호출 path 제거**. classify 자체 (`directive_detect.py`) 는 유지 — `directive-detect.jsonl` 에 class 기록만 (회고 / 통계 / 미래 LLM 추천용).
- [x] `bot.py` `on_message` 에 **📌 자동 reaction add** 추가 (auto-ack 👀 / secondary 🕐 / writing-marker ✍️ 와 동일 path).
- [x] `bot.py` `on_raw_reaction_add` 에 **📌 분기 신설**:
  - emoji == 📌
  - 메시지 author == reaction author (자기 메시지 self-tap) — 단 본 spec §4 비범위 의 "helper / 다른 사람 답변 박기" 시나리오를 위해 author 검증은 allowed_user_ids 안에 있는 사람이면 OK (자기 메시지 + 다른 author 메시지 모두 가능)
  - jsonl lookup → 이미 등록된 message_id 면 idempotent skip
  - `directive_append.sh <message_id> <summary>` 호출 → forum thread 신설
  - 등록 완료 시 bot 이 ✅ 추가 reaction add (시각적 확인)
- [x] dedup — SQLite ledger key `pin:{message_id}` 로 race 가드 (다중 사용자 / Gateway reconnect 시 1회만).

### 비기능 요구사항

- **자원 비용**: 📌 자동 부착은 매 메시지 REST 1회. 기존 auto-ack / secondary reaction 과 같은 batch. polling loop 0.
- **graceful**: `directive_append.sh` 호출 실패 → warning 만, ✅ 부착 skip. on_raw_reaction_add 흐름 차단 금지.
- **사용자 학습 부담**: 1줄 ("메시지에 📌 tap 하면 directive 등록"). emoji picker 부담 0 (bot 미리 부착).

## 4) 범위 / 비범위

### 포함

- bot.py `on_message` 의 classify 자동 등록 path 제거.
- bot.py `on_message` 에 📌 자동 reaction add.
- bot.py `on_raw_reaction_add` 의 📌 분기.
- `directive_append.sh` 호출 후 ✅ 부착.
- SQLite ledger dedup.

### 제외 (Out of Scope)

- **📌 제거 시 directive entry 취소** — 단방향 등록. 등록 후 취소는 별도 mechanism (PR 머지 webhook / 사람 수동 정정).
- **status 전이 자동화** — `directive_status.sh in_progress|completed` 의 자동 호출 (PR 머지 webhook 등) 은 follow-up. 본 spec 은 등록만.
- **summary 정제** — 본문 그대로 (raw 80자 truncate). helper LLM 정제 호출은 follow-up — 사용자가 박은 후 thread 안에서 helper 가 정제 가능.
- **classify (`directive_detect.py`) 자체 폐기** — 유지. `directive-detect.jsonl` 로그만 계속 (회고 / 미래 LLM 추천용).

## 5) 설계

### 5-1) emoji 결정

- **📌 (pushpin, `U+1F4CC`)** — "박아둠" 의미 직관, 한국어 "박는다" 와 자연 매칭.
- **✅ (check, `U+2705`)** — 등록 완료 시각화.
- 기존 자동 reaction (`👀` auto-ack / `🕐⏳⚡` secondary / `✍️` writing) 과 의미 / 색상 모두 명확히 구분.

### 5-2) bot.py on_message 흐름 변경

```diff
  async def on_message(message):
      # ... 기존 filter / dedup / payload build / append_inbox / write_last_user_msg_id ...

-     # directive 자동 분류 + 자동 등록 (false-positive source — 폐지)
-     detect_class: str | None = None
-     ...
-     if detect_class is not None:
-         subprocess.run([..., "directive_append.sh", ...])  # ← 이 호출 제거

+     # directive 분류 로그만 (참고 신호, 자동 등록 X)
      try:
          detect_entry = make_detect_entry(...)
          append_detect_entry(DIRECTIVE_DETECT_PATH_DEFAULT, detect_entry)
      except OSError as exc:
          logger.warning("directive-detect append 실패: %s", exc)

      # 기존: auto-ack 👀 + secondary 🕐 / ⏳ / ⚡
      if bot_auto_ack_enabled: ...
      if bot_secondary_reaction_enabled: ...

+     # 신규: 📌 directive 등록 후보 marker (사용자 tap 으로 등록)
+     try:
+         await message.add_reaction("📌")
+     except Exception as exc:
+         logger.warning("📌 부착 실패: %s", exc)

      # 기존: tmux send 흐름
      ...
```

### 5-3) bot.py on_raw_reaction_add 흐름 (📌 분기 신설)

```text
on_raw_reaction_add(raw_payload)
  → channel/allowed_user/bot-self filter (기존)
  → emoji 분기:
      - keycap (1️⃣–🔟) → 기존 choice handler (PR 1)
      - 📌 → 신규 pin handler (본 spec)
      - 그 외 → return

pin handler:
  → fetch message (channel.fetch_message)
  → SQLite ledger.claim(f"pin:{message_id}") — dedup
  → directive_jsonl lookup (`directive-board.jsonl` 에 이미 message_id 있나)
      → 있으면: idempotent skip + (선택) bot 이 ℹ️ 추가 부착 또는 무시
  → directive_append.sh <message_id> <message_content[:80]> 호출 (subprocess timeout 5s)
  → 성공 시 ✅ reaction add
  → 실패 시 warning + ❌ reaction add (선택)
```

### 5-4) dedup / idempotency

- SQLite ledger key = `pin:{message_id}` — Gateway reconnect / 다중 사용자 동시 tap 가드.
- `directive-board.jsonl` grep — 같은 message_id 이미 등록 시 skip (script `directive_append.sh` 자체가 멱등성 보장 — line 안 grep 가드, PR #1140 spec).

### 5-5) summary

- 본 PR MVP: 본문 그대로 80자 truncate (`directive_append.sh` 가 호출 시 사용).
- follow-up: helper 가 thread 안에서 발화 시 thread starter 본문 PATCH 로 정제 (사용자가 박은 후 helper 가 LLM 으로 의도 정제 호출 가능).

### 5-6) 사용자 검증 (NCP 배포 후)

1. `tools/discord-daemon/deploy.sh` 로 develop 반영.
2. `#모부르지` 채널에 임의 메시지 발송 → bot 이 📌 자동 부착 확인.
3. 그 메시지에 📌 tap → forum 채널에 thread 신설 + 봇이 ✅ 추가 부착 확인.
4. directive-board.jsonl tail 에 새 entry 확인.
5. 동일 메시지에 📌 다시 tap (제거 후 재 부착) → 추가 등록 안 됨 + ✅ 유지 확인 (idempotent).

## 6) rollback plan

- `tools/discord-daemon/deploy.sh` 로 develop revert 후 봇 재시작.
- 또는 `bot.py` 에서 📌 분기 / classify path 제거 변경을 단순 revert PR.
- directive-board.jsonl 의 잘못 등록된 entry 는 사람 수동 정정 (별도 mechanism).

## 7) 작업 분할

- [x] PR 1 (본 spec + 구현): bot.py classify 자동 등록 path 제거 + 📌 auto-attach + on_raw_reaction_add 📌 분기 + ✅ 부착 + 단위 테스트. spec 동반.
- [ ] PR 2 (follow-up, optional): summary 정제 (helper LLM thread starter PATCH) — 박은 후 thread 안에서 helper 가 의도 압축.
- [ ] PR 3 (follow-up, optional): status 자동 전이 — PR 머지 webhook → `directive_status.sh completed` 호출.
- [ ] PR 4 (follow-up, optional): 📌 제거 시 directive entry 취소 / archive.

## 8) 테스트 전략

### 단위 (PR 1 포함)
- `on_raw_reaction_add` 의 📌 분기 — emoji 매칭 / channel filter / user filter / dedup.
- idempotent — 같은 message_id 에 2회 tap 시 두 번째 skip.
- ✅ 부착 — 성공 path.

### 통합 (수동, NCP 배포 후)
- §5-6 5단계.

## 9) 결정 로그

- 2026-05-28 — 사용자 정정: "지금 무슨 말을 하면 바로 지시 포럼에 그말 그대로 추가되고 관리가 안되는 거 같은데". classify 자동 등록 false-positive 다발 진단. **결정**: classify 자동 등록 path 폐지 + 📌 reaction 기반 명시 등록.
- 2026-05-28 — **결정 1**: emoji = 📌 (pushpin) + ✅ (완료 표시). 사유: 한국어 "박는다" 의미 자연, 기존 자동 reaction 들과 충돌 X.
- 2026-05-28 — **결정 2**: bot 이 매 메시지 📌 미리 부착 + 사용자 tap. 사유: 사용자 emoji picker 부담 0, retro-register 가능.
- 2026-05-28 — **결정 3**: classify (`directive_detect.py`) 자체는 유지 (jsonl 로그만). 사유: 회고 / 통계 / 미래 LLM 추천에 활용 가능 — 자동 등록만 제거.
- 2026-05-28 — **결정 4**: dedup = SQLite ledger key `pin:{message_id}` + `directive_append.sh` 자체 grep 가드. 사유: PR #1201 의 reaction-choice 와 일관 mechanism.

## 10) 자율 결정 (사유)

- summary 정제 (helper LLM) / status 전이 자동화 / 제거 시 취소 — 모두 follow-up PR 분리. 사유: 본 PR MVP 최소화 + 사용자 1주일 사용 후 실측 데이터로 priority 결정.

## 11) 사용자 확인 필요

- 없음 (사용자 결정 완료).

## 12) References

- PR #1173 (event-driven impl PR 1) — 본 spec 이 폐지하는 자동 등록 path 도입 PR.
- PR #1201 (reaction-choice + mode infra) — 본 PR 이 확장하는 `on_raw_reaction_add` 핸들러 base.
- `tools/discord-daemon/directive_append.sh` — 본 PR 이 호출하는 명시 등록 helper.
- `tools/discord-daemon/directive_detect.py` — classify 유지 (jsonl 로그만).
- 메모리: [[feedback-autonomous-default]] / [[feedback-evidence-based-root-cause]] / [[feedback-verify-and-iterate]].

## 13) 변경 이력

- 2026-05-28 — 초안 작성 + PR 1 (사양 + 구현 + 단위 테스트). status=approved (사용자 정정 직접 응답). classify 자동 등록 폐지 + 📌 명시 등록.
