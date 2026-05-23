---
feature: nmae 사이클 watchdog (4중 안전망 + STRICT mode + escalation)
slug: nmae-cycle-watchdog
status: draft
owner: @mobruji-maestro
scope: infra
related_issues: [941, 956, 972]
related_prs: [941, 950, 956, 966, 972, 977]
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
- [x] `cycle_idle_watch_loop` — 5분 polling (env override 가능) cycle-status.json read.
- [x] idle 정의: `in_progress` null/누락 AND `last_completed.completed_at` > `now - threshold_minutes` (default 10).
- [x] idle ≥ 1 워크트리 발견 시 nmae tmux pane (`mobruji:0.0`) 에 알림 inject (`tmux send-keys -l`).
- [x] Discord `CYCLE_NOTIFY_CHANNEL_ID` (default = `NOTIFY_CHANNEL_ID`) 에 경고 push.
- [x] 같은 워크트리 재알림 debounce 15분.
- [x] env toggle: `CYCLE_IDLE_WATCH=1` default. `0` 으로 비활성화.
- [x] (#956) **idle 시 `note` 필드 의무** — 미명시 시 STRICT relaunch prompt.
- [x] (#956) env toggle: `CYCLE_REASON_REQUIRED=1` default. `0` 으로 strict mode off.
- [x] (#956) Discord push 에 reason / idle_since 노출 — STRICT 라벨 분리.
- [x] (#972) **escalation**: 같은 워크트리 inject 3회 연속 후 in_progress 여전히 NULL → MOBRUJI_CHANNEL_ID 직접 사용자 push (debounce 1h, env `CYCLE_INJECT_ESCALATION_THRESHOLD=3` default).
- [x] (#972) nmae 가 inject 받았을 때 in_progress 갱신하면 escalation counter 자동 리셋.
- [x] (#972) sub-agent prompt template (`docs/ai-harness/12-sub-agent-prompt-template.md` §1) 에 watchdog inject 대응 4단계 의무 절차 명문화.

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

### 5-7) 4중 안전망 (핵심)
1. **bot.py `cycle_idle_watch_loop`** (외부 데몬 watchdog) — 본 spec. **최후 보루**. 메모리/룰 위반 시도 자동 정정.
2. **nmae 매 turn 종료 직전 자기 점검** — 기존 메모리 [[feedback-keep-4-cycles-active]] 룰. cycle-status.json 4 워크트리 active 검증, idle 시 즉시 launch.
3. **helper 우연 발견 시 직접 inject** — helper 가 사용자 메시지 처리 중 cycle-status.json 발견 시 같은 서버라 직접 `tmux send-keys -t mobruji:0.0` 가능. [[feedback-helper-role-boundary]] 위임 영역 (helper 가 nmae 권한 침범 X — 알림만).
4. **escalation 사용자 직접 push (#972)** — 1번 watchdog 가 같은 워크트리 inject **3회 연속** 후에도 in_progress 가 여전히 NULL 이면 MOBRUJI_CHANNEL_ID (사용자 채널) 에 `🚨 nmae 무응답` 직접 push. nmae 자체가 룰 위반 중이라는 신호 — 사용자 개입 트리거.

네 layer 각각이 단독으로도 동작. 1번이 안전망의 핵심 — nmae 룰에 의존하지 않음. 4번은 1번이 효과 없을 때의 최종 escalation — 사용자 가시성 확보.

### 5-8) STRICT mode + reason 의무 (#956)

사용자 2026-05-24 정정: "왜 사이클 멈췄나 / idle 시 digest 에 사유 명시 / 타당한 사유 없으면 relaunch 강제".

#### cycle-status.json 스키마 확장

```json
{
  "be": {
    "in_progress": null,                                // dict 또는 null
    "last_completed": { "pr": "#937", "title": "...", "completed_at": "..." },
    "note": "idle 사유 또는 다음 launch 후보",          // idle 시 의무 (#956)
    "idle_since": "2026-05-24T02:14:00Z"                // idle 진입 시각 (옵션)
  }
}
```

| 필드 | 타입 | 의무 | 설명 |
|---|---|---|---|
| `note` | str | **idle 시 의무** | 사유 명시 또는 다음 launch 후보. 미명시 시 watchdog STRICT relaunch |
| `idle_since` | ISO8601 | optional | idle 진입 시각 |

#### 갱신 헬퍼

`tools/cycle-status/update.sh` — nmae 가 sub-agent launch/완료/idle 시 호출.

```bash
update.sh be set-active   --title "PR #N follow-up" --task "..."
update.sh fe set-idle     --note "다음 launch 후보: PR #857 audit"
update.sh rev set-completed --pr "#940" --title "audit PASS"
```

`set-idle 는 --note 필수`. note 빈 string → 에러 (STRICT 강제).

#### watchdog 분기

| 상태 | inject 동작 | Discord push |
|---|---|---|
| active (`in_progress` dict) | skip | skip |
| idle + `note` 명시 | 일반 template inject ("watchdog ... keep-4-cycles 위반") | reason 표시 (한 줄에 워크트리별) |
| idle + `note` 미명시 (`CYCLE_REASON_REQUIRED=1`) | **STRICT** template inject ("note 필드 기록 의무 + 즉시 launch") | "STRICT relaunch (N): ws1, ws2" 라벨 + 워크트리별 "reason 없음" 표시 |

같은 iter 에 strict + soft idle 공존하면 두 inject 각각 발사.

#### Discord push 형식 예시

```
⚠️ nmae watchdog — 3 워크트리 idle
STRICT relaunch (1): fe
- be: IDLE 2026-05-24T02:17:00Z~ (reason: be cache eviction 완료 후 audit)
- fe: IDLE (reason 없음 — STRICT relaunch)
- plan: IDLE 2026-05-24T01:30:00Z~ (reason: ADR-0017 옵션 결정 대기)
→ nmae 에 알림 inject 완료
```

#### 검증 스크립트

`tools/cycle-status/validate.sh` — idle 워크트리 note 누락 detect. exit 1 + 누락 목록 stderr. nmae self-check 용.

#### 트레이드오프

- nmae 가 set-idle 자주 호출해야 함 → wrapper 명령 정착 부담. 보상: idle 디버깅 용이성 (사이클 멈춤 원인 즉시 파악).
- false STRICT 위험: nmae 가 transition 중 (active → completed → 다음 launch 직전) note 갱신 안 한 5분 polling 에 걸림. 보상: 5분 + 15분 debounce 로 실제로는 안전.

## 5-9) Escalation (#972)

사용자 2026-05-24 정정: "watchdog detect + inject 정상이나 nmae 가 inject 받고 행동 안 함 → cycle-status in_progress NULL 유지 → 무한 idle inject loop. 진짜 사이클 멈춤".

### 동작
- `cycle_idle_watch_loop` 가 워크트리 별 **escalation counter** 유지 (`inject_count_at_idle: dict[str, int]`).
- 같은 워크트리에 idle inject 1회 발사할 때마다 counter += 1.
- counter 가 `CYCLE_INJECT_ESCALATION_THRESHOLD` (default 3) 도달 시 MOBRUJI_CHANNEL_ID 에 다음 메시지 push:
  ```
  🚨 nmae 무응답 — <ws> 워크트리 watchdog inject 3회 연속 후 in_progress 여전히 NULL. nmae 룰 위반 — 사용자 확인 필요
  ```
- escalation push 자체도 debounce 1h (`CYCLE_INJECT_ESCALATION_DEBOUNCE_SECONDS`).
- 워크트리가 active 로 돌아오면 (in_progress dict 보유) counter 자동 0 리셋.

### env
| 변수 | default | 의미 |
|---|---|---|
| `CYCLE_INJECT_ESCALATION_THRESHOLD` | 3 | 같은 워크트리 inject 연속 N회 후 escalate |
| `CYCLE_INJECT_ESCALATION_DEBOUNCE_SECONDS` | 3600 | 같은 워크트리 escalate push 사이 최소 간격 |

### push 채널 분리
- watchdog inject 알림 → `CYCLE_NOTIFY_CHANNEL_ID` (#모부르지-알림, 운영 진단용)
- escalation push → `MOBRUJI_CHANNEL_ID` (#모부르지, 사용자 직접 채널) — 깜깜이 방지.

## 6) 작업 분할 (예상 PR 리스트)
- [x] PR 1 (#941, #950): bot.py `cycle_idle_watch_loop` + pytest 17건 + 메모리 + CLAUDE.md §14.
- [x] PR 2 (#956): STRICT mode + reason 의무 + `tools/cycle-status/` (update.sh / validate.sh / README) + pytest +7 (총 24) + CLAUDE.md §14 보강.
- [x] PR 3 (#972): escalation 카운터 + MOBRUJI_CHANNEL_ID 직접 push + sub-agent prompt §1 nmae watchdog inject 대응 4단계 절차 명문화 + pytest +4.

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
- 2026-05-24 (#956): STRICT mode 도입 — note 미명시 idle 은 즉시 relaunch + 의무 강제 prompt. 사용자 정정: "idle 시 digest 에 사유 명시 / 타당한 사유 없으면 relaunch 강제". rev/plan 은 note 명시 패턴 정착, fe 는 누락 → 강제화 필요.
- 2026-05-24 (#956): `CYCLE_REASON_REQUIRED=1` default — 후방호환 off 가능. `tools/cycle-status/update.sh` 도입 — nmae 수동 JSON 편집 부담 해소.
