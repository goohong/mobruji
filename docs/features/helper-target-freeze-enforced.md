---
feature: helper-current-target.txt freeze 강제 (wrapper 호출 의무화)
slug: helper-target-freeze-enforced
status: draft
owner: @mobruji-maestro
scope: infra
related_issues: [1109]
related_prs: []
last_reviewed: 2026-05-26
last_user_msg_id_freeze: true
---

# helper-current-target.txt freeze 강제 (wrapper 호출 의무화)

## 1) 개요 (What / Why)
- 현재 `helper-turn-start.sh` (#1014) 가 step 1 에서 `cp ~/.mobruji/last-user-msg-id.txt ~/.mobruji/helper-current-target.txt` 으로 target freeze 를 수행. 그러나 wrapper 호출 자체가 학습 의존 (helper 본체 첫 명령에서 wrapper 실행 룰) — 누락 시 freeze 안 됨 → `discord-reply.sh` 가 turn 중간에 bot.py 가 새로 갱신한 `last-user-msg-id.txt` 를 읽어 잘못된 메시지에 reply.
- 사용자 2026-05-26 정정: "wrapper 호출 의무가 학습으로 강제되니까 종종 누락 — 더 강한 메커니즘 필요". wrapper 호출 누락 자체를 detect 하는 외부 가드 + 누락 시 자동 freeze 수행 메커니즘 도입.
- 대상 액터: helper 본체. nmae / sub-agent 는 target freeze 대상 아님.

## 2) 사용자 시나리오
- 사용자가 메시지 A 보냄 → `last-user-msg-id.txt = A_id` → helper turn 시작 → wrapper 호출 누락 → 사용자가 같은 turn 안 메시지 B 추가 보냄 → `last-user-msg-id.txt = B_id` → helper 가 답 push 시 B 에 reply (잘못된 anchor).
- 본 spec 도입 후: wrapper 호출 누락 시 `discord-reply.sh` 본답 모드가 자체 freeze 검증 (helper-current-target.txt 존재 + ts 신선도) → 미freeze 면 stderr warn + bot.py 가 helper tmux pane 에 `[target-freeze missed] wrapper 호출 누락 감지 — 자동 freeze 수행` inject.

## 3) 요구사항
### 기능 요구사항
- [ ] `discord-reply.sh` 본답 push 모드 (구분선 ━ 시작 본답) 호출 시 다음 검증 추가:
  - [ ] `helper-current-target.txt` 존재 확인.
  - [ ] 파일 mtime 이 현재 helper turn 시작 이후인지 확인 (`HELPER_TURN_START_TIMESTAMP` env 또는 `helper-turn-start.txt` 파일).
  - [ ] 검증 fail 시: stderr warn `[target-freeze missed]` + `last-user-msg-id.txt` 를 `helper-current-target.txt` 로 즉시 copy (rescue freeze).
- [ ] bot.py `helper_turn_start_watch_loop` (또는 기존 `answer_first_watch_loop` 통합) 추가:
  - [ ] `helper-queue.jsonl` 에 신규 pending entry 추가됐는데 `helper-current-target.txt` 가 wrapper 호출 흔적 (마커 파일 `~/.mobruji/wrapper-last-call.txt`) 없이 30초 경과 시 → tmux inject `[wrapper-missed] helper-turn-start.sh 호출 누락 — 다음 명령으로 실행: bash /home/mobruji/.mobruji/helper-turn-start.sh`.
- [ ] `helper-turn-start.sh` 가 자기 호출 마커 atomic write — `~/.mobruji/wrapper-last-call.txt` 에 `<turn_start_ts> <queue_last_msg_id>` 기록.
- [ ] env toggle: `HELPER_TARGET_FREEZE_ENFORCED=1` default.
- [ ] env: `HELPER_TURN_START_WATCH_INTERVAL_SECONDS` (default 15) / `HELPER_WRAPPER_TIMEOUT_SECONDS` (default 30).

### 비기능 요구사항
- 무손실: rescue freeze 시 race condition 방어 — `discord-reply.sh` 가 freeze 와 reply payload 빌드 사이 시간 차이 < 100ms 유지.
- 관측성: wrapper 호출 누락 INFO 로그 + Discord push 무관 (운영 진단만).
- graceful: 마커 파일 부재 시 (배포 직후 / wrapper 첫 호출 전) detect 안 — `wrapper-last-call.txt` 부재 = 정상 첫 호출 사이클로 간주, inject X.
- 멱등성: 같은 turn 안 wrapper 호출이 다시 일어나도 freeze 결과 동일 (idempotent).

## 4) 범위 / 비범위
### 포함
- `discord-reply.sh` 본답 모드 검증 단계 1개 추가.
- bot.py wrapper-missed watch loop 1개 추가 (또는 기존 loop 통합).
- `helper-turn-start.sh` 마커 atomic write 1줄 추가.

### 제외 (Out of Scope)
- wrapper 자체 LLM 호출 강제 — bot.py 가 helper turn 시작 시점을 정확히 알 수 없음 (LLM harness 내부). queue jsonl + 마커 file 으로 우회 detect.
- sub-agent target freeze — sub-agent 는 LAUNCH_THREAD_ID env 로 thread 명시, target freeze 패턴 미적용.
- discord-reply.sh 가 직접 wrapper 호출 trigger — 무한 recursion 위험. rescue freeze 만, wrapper 호출은 inject 로 helper 본체에 요청.
- 다른 wrapper (`nmae` 측) 강제 — nmae 는 cycle-status.json 기반 별도 watchdog (out-of-scope).

## 5) 설계
### 5-1) 도메인 모델
- 인프라 도메인. discord-reply.sh + bot.py 운영 영역.

### 5-2) API 엔드포인트
N/A.

### 5-3) 외부 연동
- **파일** — `~/.mobruji/{last-user-msg-id.txt, helper-current-target.txt, helper-queue.jsonl, wrapper-last-call.txt}`.
- **tmux** — `tmux_send_payload("helper:0.0", inject_template)` (기존 헬퍼 재사용).
- **stderr** — `discord-reply.sh` warn 출력 (helper tmux pane 가 stderr 받음 → 사용자 가시는 안 됨, 운영 진단).

### 5-4) 데이터 흐름 / 시퀀스
```
[정상 흐름]
  1. 사용자 메시지 도착 → bot.py 가 last-user-msg-id.txt = A_id + helper-queue.jsonl pending append
  2. helper 본체 turn start → helper-turn-start.sh 호출
       ├ step 1: cp last-user-msg-id.txt helper-current-target.txt
       └ step N (신규): echo "$(date +%s) $A_id" > wrapper-last-call.txt
  3. helper 본답 push → discord-reply.sh
       ├ 검증: helper-current-target.txt 존재 + mtime > wrapper-last-call.txt ts → OK
       └ payload build → POST

[누락 흐름 — rescue]
  1. 사용자 메시지 도착 → A_id pending
  2. helper turn start (wrapper 미호출)
  3. (30초 후) bot.py wrapper-missed watch loop detect
       ├ queue.jsonl pending 최신 entry ts vs wrapper-last-call.txt ts 비교
       ├ pending 신선 + wrapper 호출 오래됨 → 누락 감지
       └ tmux inject "[wrapper-missed] helper-turn-start.sh 호출 누락"
  4. helper 본답 push 시 (만약 inject 전 push 가 이미 발사):
       ├ discord-reply.sh 검증: helper-current-target.txt mtime < queue pending ts → fail
       └ rescue freeze: cp last-user-msg-id.txt helper-current-target.txt + stderr warn
```

### 5-5) DB 마이그레이션
없음.

### 5-6) 프론트엔드 화면
없음.

### 5-7) discord-reply.sh 검증 로직 (예시)

```bash
# discord-reply.sh 본답 모드 시작 부분
if [[ "$BODY_MODE" == "main" ]]; then
  target_file="$HOME/.mobruji/helper-current-target.txt"
  marker_file="$HOME/.mobruji/wrapper-last-call.txt"
  last_msg_file="$HOME/.mobruji/last-user-msg-id.txt"

  if [[ ! -f "$target_file" ]] || [[ "$last_msg_file" -nt "$target_file" ]]; then
    echo "[target-freeze missed] rescue freeze" >&2
    cp "$last_msg_file" "$target_file"
  fi
fi
```

### 5-8) bot.py watch loop (예시)

```python
async def wrapper_call_watch_loop():
    while True:
        await asyncio.sleep(HELPER_TURN_START_WATCH_INTERVAL_SECONDS)
        try:
            pending = read_queue_pending_last()
            if not pending:
                continue
            pending_age = time.time() - pending["ts"]
            if pending_age < HELPER_WRAPPER_TIMEOUT_SECONDS:
                continue
            wrapper_ts = read_wrapper_last_call_ts()  # int or None
            if wrapper_ts is None or wrapper_ts < pending["ts"]:
                tmux_send_payload("helper:0.0", WRAPPER_MISSED_TEMPLATE)
        except Exception as e:
            logger.warning("wrapper_call_watch_loop skip: %s", e)
```

### 5-9) marker file schema (`wrapper-last-call.txt`)

```
<unix_ts> <last_user_msg_id>
```

예: `1716700123 1234567890123456789`.

## 6) 작업 분할 (예상 PR 리스트)
- [ ] PR 1: `discord-reply.sh` 본답 모드 freeze 검증 + rescue freeze 1단계 추가 + 단위 테스트 (bash test).
- [ ] PR 2: bot.py `wrapper_call_watch_loop` 추가 + helper-turn-start.sh 마커 write 1줄 + pytest 4건 (정상 / wrapper 누락 detect / marker 부재 / parse fail) + .env.example 갱신.
- [ ] PR 3: CLAUDE.md §12-3 step 2 보강 — wrapper 강제 메커니즘 명시.

## 7) 테스트 전략
- 단위 테스트:
  - `discord-reply.sh` rescue freeze — last-user-msg-id 신선 + target 부재 / target stale / target 신선 3 케이스.
  - `wrapper_call_watch_loop` — pending 신선 + wrapper 호출 stale → inject / pending stale → skip / marker 부재 → skip.
- E2E: 실 helper tmux 환경에서 wrapper 호출 안 한 상태 강제 → 30초 후 inject 도착 확인.

## 8) 오픈 질문
| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | rescue freeze 시 사용자 가시 reaction 추가? | (a) 운영 진단 stderr only / (b) ⚠️ reaction add | @user / TBD |
| Q2 | wrapper 누락 escalation — Discord push 도 추가? | (a) tmux inject only / (b) escalation push | @user / TBD |
| Q3 | timeout default — 30초 vs 60초 | (a) 30 (빠른 detect) / (b) 60 (helper 부팅 시간 여유) | @user / TBD |

## 9) 관련 spec / ADR / 메모리

- `docs/features/answer-first-verification-hook.md` (B-1) — 본 spec 의 wrapper-missed watch loop 가 answer-first watch loop 와 통합 가능 (구현 시점 결정).
- `docs/features/discord-realtime-bidirectional.md` — bot.py + discord-reply.sh 인프라.
- 메모리: [[feedback-helper-reply-target-freeze]] (turn 시작 freeze) / [[feedback-helper-discord-reply-to]] (자동 reply) / [[feedback-verify-and-iterate]].
- CLAUDE.md §12-3 step 2 — target msg freeze. 본 spec 이 wrapper 누락 시 강제 메커니즘 추가.

## 10) 결정 로그
- 2026-05-26: 초안 작성 (status=draft). 이슈 #1109 plan 사이클. B-4 task brief.
- 2026-05-26: 2단 방어 — discord-reply.sh rescue freeze (적시 정정) + bot.py wrapper-missed inject (선제 정정). 각각 다른 timing 의 누락 cover.
- 2026-05-26: `wrapper-last-call.txt` 마커 도입 — wrapper 호출 사실 자체를 외부에서 검증 가능. helper-current-target.txt 만으로는 wrapper 호출 누락 vs 정상 wrapper 호출 + 사용자 메시지 동시 도착을 구분 불가.
