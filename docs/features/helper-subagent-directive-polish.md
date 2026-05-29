# Feature Spec — Directive 본문 polish (bot.py nmae 우회 helper sub-agent)

- **status**: shipping (2026-05-29, PR feat/directive-polish-bot-loop)
- **scope**: infra
- **owner**: bot.py
- **메모리**: [[feedback-helper-relay-only]] [[feedback-autonomous-default]]

## 1) 배경

사용자 정정 (2026-05-29):
> "안의 내용은? 누가 관리해?"
> "원래 B로 가기로한거아니었나? 선택지들 객관적으로 우리 프로젝트에 맞추어 평가해줘"
> "nmae 우회로 진행"

기존 설계 (B 선택지):
- 📌 등록 시 bot.py 가 `helper-queue.jsonl` 에 `type=directive_polish` task append
- helper 본체가 다음 turn-start 에서 polish task 보고 sub-agent batch launch — 단 helper 본체 = relay only ([[feedback-helper-relay-only]]) 라 학습 의존 + 룰 위반.
- 결과로 실제 polish 가 안 일어남. forum thread 가 minimal template 만.

추가 제약:
- 사용자 사이클 정지 명령 (2026-05-29 01:25) → nmae 가 새 사이클 launch 금지 모드. nmae 가 sub-agent launch (agent-launch-wrapper.sh) 도 의도적 minimize.

## 2) 결정

**B (helper sub-agent 위임) 유지 + nmae 우회**: bot.py 가 직접 `claude -p` (one-shot mode) 호출해 polish.

근거:
- `claude -p` 가 mobruji user 인증 작동 확인.
- bot.py 가 기존 long-running daemon 이라 polling loop 추가 자연.
- nmae 부담 ↓ + 사용자 사이클 정지 모드와 무관 (routine task).
- 기존 인프라 (helper-queue.jsonl + directive_polish queue + mark-polished.sh) 재사용.

## 3) 설계

### 3-1) component

| 파일 | 역할 |
|---|---|
| `bot.py` `directive_polish_loop` | 1분 polling. helper-queue.jsonl read + pending directive_polish task 처리 |
| `bot.py` `_run_claude_polish` | claude CLI subprocess (-p mode) 호출. 180s timeout |
| `bot.py` `_process_directive_polish_queue` | queue read → polish → forum-edit → mark-polished → queue rewrite (atomic) |
| `tools/directive-board/mark-polished.sh` | jsonl polished=true update (기존) |
| `tools/discord-daemon/discord-reply.sh --forum-edit` | forum thread starter body PATCH (기존) |

### 3-2) 실행 흐름

```
📌 사용자 pin tap
  → bot.py on_raw_reaction_add 📌 branch
     → directive_append.sh (jsonl entry + forum thread post)
     → helper-queue.jsonl append {type=directive_polish, status=pending, ...}

[1분 후 / 다음 directive_polish_loop iter]
directive_polish_loop
  → queue read
  → pending directive_polish entries 마다:
     1. claude -p _polish_prompt(raw_body, directive_id) → polished markdown
     2. discord-reply.sh --forum-edit <directive_id> <polished> → starter body PATCH
     3. mark-polished.sh <directive_id> → jsonl polished=true
     4. queue entry status=done + polished_at append
  → queue atomic rewrite
```

### 3-3) Polish prompt

```
mobruji 프로젝트의 directive forum thread 본문을 정제해 주세요.

원본 사용자 메시지: <raw_body>
directive_id: <id>

다음 4 항목 한국어 markdown 으로 정제 (각 항목 짧게):
- **요약**: 1-2 줄 (사용자가 무엇을 원하는지)
- **유형**: 신규 기능 / 버그 fix / 운영 개선 / 의견 / 질문 중 하나
- **위임 권장**: be / fe / rev / plan / nmae 본진 중 하나 + 한 줄 사유
- **상태**: 대기

출력은 위 4 항목 markdown 만. 코드 펜스 / 부가 설명 / 메타코멘트 금지.
```

### 3-4) graceful

- claude -p 실패 / 빈 응답 → 그 task skip (다음 iter 재시도).
- forum-edit 실패 → 그 task skip (queue 안 마크 안 함, 재시도).
- mark-polished 실패 → log 만, 진행.
- queue 부재 → loop iter no-op.

## 4) 비목표

- PR 머지 시 forum body footer update — `directive_complete_on_merge_loop` 가 이미 처리.
- 사용자 follow-up 답변 정제 — 본 spec scope 아님 (추가 prompt 필요).
- 비-pin 메시지 polish — 📌 등록만 trigger.

## 5) 운영

- `DIRECTIVE_POLISH_POLL_INTERVAL_DEFAULT=60` (1분). 즉 등록 후 1분 안에 polish 시작.
- `DIRECTIVE_POLISH_CLAUDE_TIMEOUT=180` (3분). claude -p 한 호출 cap.
- env `CLAUDE_BIN` 으로 binary path override.

## 6) 검증

- helper-queue.jsonl 의 pending directive_polish entry 가 polished_at 으로 마크되는지.
- forum thread starter body 가 minimal template → 4 항목 markdown 으로 update 되는지.
- jsonl 의 polished=true 가 박히는지 (nmae backlog-scan 이 detect 가능하도록).

## 7) follow-up

- polish 결과의 "위임 권장" 을 nmae 가 사이클 재개 시 자동 inject (backlog-scan + assigned_cycle).
- claude -p 모델 / 출력 형식 tuning.
- queue 안 stale done 항목 cleanup (별 cron).
