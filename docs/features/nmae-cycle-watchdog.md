---
feature: nmae 사이클 watchdog (3중 안전망)
slug: nmae-cycle-watchdog
status: draft
owner: @mobruji-maestro
scope: infra
related_issues: [941]
related_prs: []
last_reviewed: 2026-05-24
---

# nmae 사이클 watchdog (3중 안전망)

## 1) 개요 (What / Why)
- bot.py 데몬에 `cycle_idle_watch_loop` 추가. 5분 polling 으로 `~/.mobruji/cycle-status.json` 4 워크트리(be/fe/rev/plan) 상태를 감시. idle 발견 시 자동으로 nmae tmux pane 에 알림 inject + Discord `#모부르지-알림` 채널 push.
- 사용자 2026-05-24 강조: "절대로 nmae 사이클이 멈춰서는 안돼 이게 왜 계속 누락되지? 대책을 세워줘". 메모리 [[feedback-keep-4-cycles-active]] 가 nmae 자기 점검 룰에 의존했는데 반복 누락 → 외부 데몬 안전망 도입.
- 대상 액터: nmae(NCP maestro), helper(맥 helper) — 둘 다 4 워크트리 가동 유지 책임이 있으나 본 watchdog 은 nmae 누락만 정정 (helper 는 `tmux send-keys` 같은 서버에서 직접 inject 가능).

## 2) 사용자 시나리오
- nmae 가 sub-agent 완료 통지를 처리하다 cycle-status.json 만 갱신하고 다음 launch 를 까먹는 상황 → bot.py watchdog 5분 내 발견 → tmux inject 로 "[watchdog] cycle-status.json idle 발견 — be. last_completed: ... — keep-4-cycles 룰 위반" 자동 알림 → nmae 다음 turn 에서 즉시 다음 백로그 launch.
- 사용자 부재 시간대(자율 사이클)에도 동일 동작 — 룰 학습이 휘발돼도 외부 데몬이 정정.

## 3) 요구사항
### 기능 요구사항
- [ ] `cycle_idle_watch_loop` — 5분 polling (env override 가능) cycle-status.json read.
- [ ] idle 정의: `in_progress` null/누락 AND `last_completed.completed_at` > `now - threshold_minutes` (default 10).
- [ ] idle ≥ 1 워크트리 발견 시 nmae tmux pane (`mobruji:0.0`) 에 알림 inject (`tmux send-keys -l`).
- [ ] Discord `CYCLE_NOTIFY_CHANNEL_ID` (default = `NOTIFY_CHANNEL_ID`) 에 경고 push.
- [ ] 같은 워크트리 재알림 debounce 15분.
- [ ] env toggle: `CYCLE_IDLE_WATCH=1` default. `0` 으로 비활성화.

### 비기능 요구사항
- 알림 spam 방지: 워크트리 별 last_alert_at 캐시 + 15분 debounce.
- Discord rate limit 준수: 기존 `send_with_retry` 사용 (429/5xx 재시도).
- graceful degradation: cycle-status.json 부재 / parse fail / tmux session 부재 시 warn 1회 후 skip — daemon crash 금지.
- 관측성: idle 발견 시 INFO 로그, 알림 push 결과 INFO 로그.

## 4) 범위 / 비범위
### 포함
- bot.py 데몬 내 watchdog loop 신설 (외부 추가 데몬 X).
- 5분 polling 기반 (real-time 이벤트 X — cycle-status.json 갱신 hook 사용 안 함).
- nmae(`mobruji:0.0`) 단일 inject target — helper 대상 inject 는 helper 자체 룰 책임.

### 제외 (Out of Scope)
- 자동 백로그 launch — watchdog 은 알림만, 실제 launch 는 nmae 가 함.
- cycle-status.json 스키마 변경 — 기존 형식 그대로 읽기만.
- Slack / 이메일 등 다른 알림 채널.
- digest_loop 대체 — digest 는 변동 push, watchdog 은 idle 정정. 책임 분리.

## 5) 설계
### 5-1) 도메인 모델
- 인프라 도메인 (`scope: infra`). 외부 사용자 도메인 무관.

### 5-2) API 엔드포인트
N/A (Discord webhook + tmux 만 사용).

### 5-3) 외부 연동
- **tmux** — `send-keys -t mobruji:0.0 -l "<text>"` + Enter. 기존 `tmux_send_payload` 재사용.
- **Discord** — `discord.Client.get_channel(NOTIFY_CHANNEL_ID).send(...)` + 기존 `send_with_retry` 429/5xx 보호.

### 5-4) 데이터 흐름 / 시퀀스
```
[bot.py daemon]
  └ on_ready → create_task(cycle_idle_watch_loop)
       └ loop: every CYCLE_IDLE_WATCH_INTERVAL_SECONDS (default 300)
            1. parse_cycle_status(path)  → dict | None
            2. detect_idle_worktrees(dict, threshold, now) → list[idle dict]
            3. debounce filter — last_alert_at 캐시 비교
            4. fresh idle ≥ 1:
                 a. tmux_inject_text(target, prompt)  ← nmae 알림
                 b. send_with_retry(channel, content) ← Discord push
                 c. last_alert_at 갱신
            5. graceful skip: tmux 부재 / channel 부재 / parse fail
```

### 5-5) DB 마이그레이션
없음 (파일 기반 — `~/.mobruji/cycle-status.json` 직접 read).

### 5-6) 프론트엔드 화면
없음 (백그라운드 데몬).

### 5-7) 3중 안전망 (핵심)
1. **bot.py `cycle_idle_watch_loop`** (외부 데몬 watchdog) — 본 spec. **최후 보루**. 메모리/룰 위반 시도 자동 정정.
2. **nmae 매 turn 종료 직전 자기 점검** — 기존 메모리 [[feedback-keep-4-cycles-active]] 룰. cycle-status.json 4 워크트리 active 검증, idle 시 즉시 launch.
3. **helper 우연 발견 시 직접 inject** — helper 가 사용자 메시지 처리 중 cycle-status.json 발견 시 같은 서버라 직접 `tmux send-keys -t mobruji:0.0` 가능. [[feedback-helper-role-boundary]] 위임 영역 (helper 가 nmae 권한 침범 X — 알림만).

세 layer 각각이 단독으로도 동작. 1번이 안전망의 핵심 — nmae 룰에 의존하지 않음.

## 6) 작업 분할 (예상 PR 리스트)
- [x] PR 1 (#941, 본): bot.py `cycle_idle_watch_loop` + pytest 17건 + 메모리 + CLAUDE.md §14.

## 7) 테스트 전략
- 단위 테스트 (pytest, asyncio mock):
  - `resolve_cycle_targets` 4건 (CSV 파싱).
  - `parse_cycle_status` 4건 (정상/null/missing/malformed).
  - `detect_idle_worktrees` 4건 (모두 idle / 일부 idle / 모두 active / in_progress dict).
  - `cycle_idle_watch_loop` 5건 (idle → inject+push / debounce / tmux 부재 / parse fail / threshold 0 disabled).
- E2E: NCP 서버에서 실제 cycle-status.json 조작 후 bot.py 로그 + tmux capture-pane 으로 inject 확인 (배포 후 manual smoke test).

## 8) 오픈 질문
| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | helper 도 같은 watchdog 대상 (`helper:0.0`) 으로 inject 해야 하나? | (a) nmae 만 (현재) / (b) 양쪽 — helper 도 4 워크트리 책임 | @user / TBD |
| Q2 | threshold 10분 → 5분 단축? sub-agent 보통 5-15분 걸리는데 false positive 위험 | (a) 10분 유지 (보수적) / (b) 5분 (agressive) | @user / TBD |

## 9) 결정 로그
- 2026-05-24: 초안 작성 (status=draft). #941 머지 후 status=shipped 로 갱신.
- 2026-05-24: 외부 데몬 watchdog 채택 — nmae cron/timer 도입 대안 기각 (기존 bot.py daemon 활용이 운영 복잡도 낮음).
- 2026-05-24: debounce 15분 — Discord rate limit 안전 마진 + idle 정정에 충분 (사용자 응답 turn 1회면 해소).
