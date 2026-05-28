---
feature: helper answer-first verification hook (본답 누락 inject)
slug: answer-first-verification-hook
status: draft
owner: @mobruji-maestro
scope: infra
related_issues: [1109]
related_prs: []
last_reviewed: 2026-05-26
---

# helper answer-first verification hook (본답 누락 inject)

## 1) 개요 (What / Why)
- helper 본체가 사용자 메시지 수신 후 `ANSWER_FIRST_VERIFICATION_TIMEOUT_SECONDS` (default 120) 안에 본답(구분선 ━ 시작 본답 push 또는 `--auto-thread` milestone) 을 한 건도 push 하지 않으면 bot.py 가 helper tmux pane(`helper:0.0`) 에 reminder 를 inject + `~/.mobruji/helper-current-target.txt` 의 메시지에 ⚠️ reaction 표시.
- 사용자 2026-05-26 정정: "helper 가 사용자 메시지 받고 reasoning 만 길게 하다가 본답 한 줄도 안 오는 case 가 반복". `discord-reply.sh` (#1095) 의 ✍️ writing marker 가 시작은 가시화하지만 **완료 = 본답 push 가 실제로 발사됐는가** 검증은 별개. answer-first 누락 hook 이 본답 missing path 자체를 차단.
- 대상 액터: helper 본체. nmae / sub-agent 는 본 hook 대상 아님 (cycle watchdog [[feedback-nmae-cycle-watchdog]] 가 별도 cover).

## 2) 사용자 시나리오
- 사용자가 `#모부르지` 채널에 질문 메시지를 보냄 → bot.py 가 1초 auto-ack push ([[feedback-helper-ack-removed]]).
- helper 본체 turn 이 reasoning + tool 호출에 묶여 본답 push 가 2분을 넘김 → bot.py `answer_first_watch_loop` 가 `helper-current-target.txt` 의 message_id + `helper-queue.jsonl` pending entry 비교 후 reminder inject.
- helper 다음 turn 첫 동작이 본답 push 가 되도록 `[answer-first reminder ...] message_id <id> 본답 push 누락 N초 — 즉시 한 줄 push 후 작업 계속` template inject.
- 같은 사용자 메시지에 reminder 1회만 (debounce). 본답 push 가 발사되면 자동 해소.

## 3) 요구사항
### 기능 요구사항
- [ ] `answer_first_watch_loop` 신설 — `ANSWER_FIRST_VERIFICATION_INTERVAL_SECONDS` (default 30) polling.
- [ ] pending detect: `~/.mobruji/helper-queue.jsonl` last entry `status == "pending"` AND `now - ts > ANSWER_FIRST_VERIFICATION_TIMEOUT_SECONDS`.
- [ ] reminder inject: `tmux_send_payload("helper:0.0", "[answer-first reminder ...] ...")` 1회 / 메시지.
- [ ] Discord reaction: `helper-current-target.txt` 의 message_id 에 ⚠️ reaction add (사용자 가시화).
- [ ] reminder 발사 후 `~/.mobruji/answer-first-warned.txt` 에 message_id append → 중복 차단.
- [ ] helper 본답 push 또는 queue entry `status: done` 갱신 시 reaction remove.
- [ ] env toggle: `ANSWER_FIRST_VERIFICATION_ENABLED=1` default. `0` 으로 비활성화.
- [ ] env: `ANSWER_FIRST_VERIFICATION_TIMEOUT_SECONDS` (default 120) / `ANSWER_FIRST_VERIFICATION_INTERVAL_SECONDS` (default 30).
- [ ] escalation: 같은 message_id 에 2번째 timeout (총 4분) 도달 시 helper-channel(`MOBRUJI_CHANNEL_ID`)에 `⚠️ helper 무응답 — <preview 30자>` push (debounce 1h 일치).

### 비기능 요구사항
- 관측성: timeout detect 시 INFO 로그 (`answer-first detected: msg_id=<id>, age=<sec>s`).
- graceful degradation: queue 파일 없음 / parse fail / tmux session 부재 시 warn 1회 후 skip — daemon crash 금지.
- 알림 spam 방지: message_id 별 1회 reminder + 1회 escalation. 같은 메시지 5분 polling 에서 재발사 X.
- helper 본체 turn 안에서 reminder 가 도착하면 다음 turn 첫 명령으로 인식되어 본답 push 직행 (`helper-turn-start.sh` 와 충돌 없음, queue 1차 본답 미해소 시 보강).

## 4) 범위 / 비범위
### 포함
- bot.py 데몬 단일 loop 추가 (외부 cron 신설 X).
- helper 본체만 대상 — sub-agent (be/fe/rev/plan) 의 본답 누락은 cycle watchdog 별도 cover.
- Discord reaction + tmux inject 만 사용. 외부 알림 (Slack/Email) 없음.

### 제외 (Out of Scope)
- helper 본체 turn 강제 termination — reminder 만, 강제 중단 X.
- reasoning 길이 측정 — turn-level reasoning 시간은 LLM harness 내부 신호라 bot.py 가 측정 불가. queue/file 기반 외부 신호만 사용.
- 본답 quality 검증 — push 발사 여부만 검증, 본답 본문 검증은 별도 hook (out-of-scope).
- helper sub-agent 본답 검증 — sub-agent 는 cycle-status.json + completion report 로 검증 (별도 룰).

## 5) 설계
### 5-1) 도메인 모델
- 인프라 도메인 (`scope: infra`). Discord bot 운영.

### 5-2) API 엔드포인트
N/A.

### 5-3) 외부 연동
- **tmux** — `tmux_send_payload("helper:0.0", text)` 기존 helper-channel 헬퍼 재사용.
- **Discord** — `discord.Client.get_channel(MOBRUJI_CHANNEL_ID).fetch_message(id).add_reaction("⚠️")` / `remove_reaction`. 기존 `send_with_retry` 헬퍼 보호.
- **파일** — `~/.mobruji/helper-queue.jsonl` (read-only), `~/.mobruji/helper-current-target.txt` (read-only), `~/.mobruji/answer-first-warned.txt` (read/write).

### 5-4) 데이터 흐름 / 시퀀스
```
[bot.py daemon]
  └ on_ready → create_task(answer_first_watch_loop)
       └ loop: every ANSWER_FIRST_VERIFICATION_INTERVAL_SECONDS (default 30)
            1. queue = read_lines("~/.mobruji/helper-queue.jsonl")
            2. last_pending = filter(status=="pending") → 최신순
            3. for entry in last_pending:
                 age = now - entry.ts
                 if age < ANSWER_FIRST_VERIFICATION_TIMEOUT_SECONDS: continue
                 if entry.message_id in answer_first_warned.txt:
                     if age > 2*timeout AND not escalated_yet:
                         escalate(message_id, preview)
                     continue
                 tmux_inject_text("helper:0.0", REMINDER_TEMPLATE)
                 add_reaction(message_id, "⚠️")
                 append("~/.mobruji/answer-first-warned.txt", message_id)
            4. cleanup: queue entry status=="done" 발견 시 reaction remove
            5. graceful skip
```

### 5-5) DB 마이그레이션
없음 (파일 기반).

### 5-6) 프론트엔드 화면
없음 (백그라운드 데몬 + Discord reaction).

### 5-7) reminder inject template (예시)
```
[answer-first reminder bot.py:answer_first_watch_loop] message_id=<id>
사용자 메시지 도착 N초 경과, 본답 push 누락 상태입니다.
- 즉시 한 줄 본답 push 후 작업 계속 (구분선 ━ 시작, --auto-thread 도 인정)
- 또는 thread 안 milestone 1줄 push 로 진행 가시화
- 미응답 시 +2분 후 MOBRUJI_CHANNEL_ID 에 escalation push
```

### 5-8) escalation push 형식 예시
```
⚠️ helper 무응답 — 본답 push 누락 4분
- 메시지 preview: "<message preview 30자>..."
- message_id: <id>
- helper-current-target.txt: <id>
→ helper 본체 행동 미관찰. 사용자 가시화만 — 자동 조치 없음.
```

## 6) 작업 분할 (예상 PR 리스트)
- [ ] PR 1: bot.py `answer_first_watch_loop` 신설 + pytest 6건 (timeout detect / debounce / reaction add+remove / escalation / graceful skip) + .env.example 갱신 + CLAUDE.md §12 보강.
- [ ] PR 2: helper 본답 push 시 reaction 자동 remove (discord-reply.sh `--writing-done` 호출 확장 — answer-first reaction 도 함께 떼기) + 통합 smoke test.
- [ ] PR 3 (옵션): escalation 채널 분리 — `MOBRUJI_CHANNEL_ID` 대신 DIGEST_CHANNEL_ID 로 옮길지 결정. 현재는 사용자 직접 가시화가 목적이라 MOBRUJI 유지.

## 7) 테스트 전략
- 단위 테스트 (pytest, asyncio mock):
  - `detect_pending_timeout` — queue jsonl 정상/empty/malformed/모두 done.
  - `should_warn` — debounce (already in warned.txt) / 정상 / age 미달.
  - `should_escalate` — age > 2*timeout / 첫 timeout / 이미 escalated.
  - `answer_first_watch_loop` 통합 — pending → reminder + reaction → done 갱신 → reaction remove.
- E2E: 실 helper tmux 환경에서 가짜 queue entry 삽입 후 reminder inject + reaction 확인.

## 8) 오픈 질문
| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | 본답 push 정의 — `--writing-done` 호출만 인정 vs queue `status: done` 갱신만 인정 vs 둘 중 하나라도 | (a) writing-done / (b) queue done / (c) OR | @user / TBD |
| Q2 | escalation 채널 — MOBRUJI vs DIGEST | (a) MOBRUJI 직접 사용자 가시 / (b) DIGEST 운영 진단 채널 | @user / TBD |
| Q3 | timeout default — 120s vs 90s vs 180s | reasoning 평균 시간 측정 후 결정 | @user / TBD |

## 9) 관련 spec / ADR / 메모리

- `docs/features/nmae-cycle-watchdog.md` — 본 spec 의 hook 패턴이 cycle-watchdog 의 외부 데몬 정정 패턴과 동형. 두 hook 모두 룰 학습 의존 ↓ + 데몬 안전망.
- `docs/features/helper-thread-stream.md` — 본 hook 이 인정하는 본답에 `--auto-thread` milestone 포함. thread stream 자체가 본답으로 인정.
- `docs/features/work-cycle-simplification.md` — 본 hook 이 Hook 4 (helper 본답 latency 측정) 의 실측 데이터 수집 채널.
- 메모리: [[feedback-helper-discord-newline]] (본답 = ZWSP+\n 시작) / [[feedback-helper-ack-removed]] (bot 1초 ack + helper 본답 직행).
- CLAUDE.md §12-3 step 6 — 본답 push + queue done. 본 hook 이 step 6 누락 자동 detect.

## 10) 결정 로그
- 2026-05-26: 초안 작성 (status=draft). 이슈 #1109 plan 사이클. B-1 task brief.
- 2026-05-26: 본답 정의에 `--auto-thread` milestone 포함 — 장시간 작업 시 본답 single shot 이 아닐 수 있음, milestone stream 도 본답 progression 으로 인정.
- 2026-05-26: escalation default = MOBRUJI_CHANNEL_ID — 사용자 직접 가시화가 1차 목적. Q2 결정 후 변경 가능.
