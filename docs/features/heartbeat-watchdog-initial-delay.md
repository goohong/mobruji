---
feature: Heartbeat watchdog initial delay 확장
slug: heartbeat-watchdog-initial-delay
status: draft
owner: @goohong
scope: infra
related_issues: []
related_prs: [1087, 1207]
last_reviewed: 2026-05-28
---

# Heartbeat watchdog initial delay 확장

## 1) 개요 (What / Why)

`bot.py` 의 `heartbeat_watch_loop` (#1087) 가 daemon restart 직후 첫 polling
시점 (t≈180s) 에 다른 watchdog loop 들의 heartbeat 파일을 검사하지만, 일부 loop
는 그 시점까지 첫 `record_loop_heartbeat` 호출을 끝내지 못해 **false-positive
"missing" stale** 가 DIGEST_CHANNEL_ID 로 push 되는 회귀가 rev round 14/15 에서
포착되었다.

본 spec 은 (a) 회귀 시퀀스 박제, (b) 옵션 비교, (c) 권장값 + 사유, (d) 후속
impl PR 분할을 정리한다. **구현 변경은 별도 be 사이클 PR 에서 진행** — 본
spec 은 docs only.

대상 actor: nmae (rev 보고 noise 감소) / be sub-agent (impl 담당) /
사용자 (false-positive DIGEST push 노이즈 제거).

## 2) 사용자 시나리오

- 사용자/nmae 가 `bot.py` 변경 머지 후 `mobruji-discord-daemon.service` 재시작.
  현재 동작에서는 재시작 직후 약 3분 시점에 DIGEST 채널에 `🚨 loop heartbeat
  stale — N개 loop 응답 없음 (#1087)` 메시지가 false-positive 로 1회 push 된다.
- rev sub-agent 가 round 종료 시 DIGEST 로그를 reading 하다가 본 false-positive
  를 실 incident 로 오인 → 불필요한 root cause 조사 시간 소비.

## 3) 요구사항

### 기능 요구사항
- [ ] daemon 재시작 직후 첫 heartbeat watch iter 시점에는 정상 동작 중인
  loop 의 heartbeat 파일이 모두 존재해야 한다 (혹은 `missing` 도 정상으로
  분류).
- [ ] `heartbeat_watch_loop` 의 첫 stale check 시점은 모든 등록 loop 가 최소
  1회 `record_loop_heartbeat` 를 호출했을 시점 이후가 보장되어야 한다.
- [ ] env override (`HEARTBEAT_WATCH_INITIAL_DELAY_SECONDS`) 로 hotfix 가능.

### 비기능 요구사항
- 변경 영향 범위 = bot.py 1 파일 상수 1줄 + 회귀 가드 pytest. 보호 영역 없음.
- false-positive push 노이즈 = 0 (`tools/discord-daemon/journalctl` 검증).
- 진짜 stale (실 incident) detect 까지의 추가 지연 = ≤ 5분 (사용자 P0
  "사이클 절대 멈추면 안 됨" 제약 위배 X).

## 4) 범위 / 비범위

### 포함
- `HEARTBEAT_WATCH_DEFAULT_INITIAL_DELAY_SECONDS` 상수값 조정.
- `heartbeat_watch_loop` 첫 iter 시점 보장 분석.
- 후속 be impl PR 분할 권고.

### 제외 (Out of Scope)
- 개별 loop 의 `initial_delay` 값 재조정 (`rev_post_merge_audit_loop` 90s,
  `claude_usage_watch_loop` 120s, `digest_loop` 60s 등) — 본 spec 은
  watchdog 측만 손본다. loop 자체 warmup 조정은 별도 spec.
- `record_loop_heartbeat` "skip on continue" 패턴 (rev_post_merge_audit_loop
  L3199-3217) 의 리팩토링 — §8 Q1 에 open 으로 등록.
- `detect_stale_heartbeats` 가 "missing" 과 "stale" 을 같은 push 로 묶는
  포맷 — §8 Q2 에 open.

## 5) 설계

### 5-1) 배경 — 회귀 시퀀스 (rev round 14/15 발견)

`bot.py` 상수 (2026-05-28 현재):

| 상수 | 값 | 의미 |
|---|---|---|
| `HEARTBEAT_WATCH_DEFAULT_INTERVAL_SECONDS` | `600` | watchdog 본체 poll interval (10분) |
| `HEARTBEAT_WATCH_DEFAULT_INITIAL_DELAY_SECONDS` | `180` | watchdog 본체 boot warmup (3분) |
| `HEARTBEAT_STALE_MULTIPLIER_DEFAULT` | `3` | stale 임계 = expected × 3 |
| `DEFAULT_DIGEST_INTERVAL_SECONDS` | `300` | digest_loop |
| `DIGEST_INITIAL_DELAY_SECONDS` | `60` | digest_loop warmup |
| `CONTEXT_AUTO_CLEAR_POLL_INTERVAL_SECONDS` | `5` | context_auto_clear_loop |
| `CYCLE_IDLE_WATCH_DEFAULT_INTERVAL_SECONDS` | `300` | cycle_idle_watch_loop |
| `REV_POST_MERGE_AUDIT_DEFAULT_INTERVAL_SECONDS` | `300` | rev_post_merge_audit_loop |
| `REV_POST_MERGE_AUDIT_DEFAULT_INITIAL_DELAY_SECONDS` | `90` | rev_post_merge_audit_loop warmup |
| `CLAUDE_USAGE_DEFAULT_INTERVAL_SECONDS` | `300` | claude_usage_watch_loop |
| `CLAUDE_USAGE_DEFAULT_INITIAL_DELAY_SECONDS` | `120` | claude_usage_watch_loop warmup |
| `THREAD_CLEANUP_DEFAULT_INTERVAL_SECONDS` | `120` | thread_cleanup_loop |
| `DIRECTIVE_DETECT_WATCH_DEFAULT_INTERVAL_SECONDS` | `600` | directive_detect_register_watch_loop |

**회귀 시퀀스 (관측됨)**:

```
t=0s   : daemon 재시작.
         모든 loop task spawn — 각자 자기 initial_delay 만큼 sleep.

t=60s  : digest_loop sleep 만료 → 첫 iter 진입.
         후보 처리 후 record_loop_heartbeat("digest_loop") 호출.
         → ~/.mobruji/heartbeat/digest_loop.ts 생성.

t=90s  : rev_post_merge_audit_loop sleep 만료 → 첫 iter 진입.
         gh PR list 호출 결과 "후보 0" 이면 continue → record_loop_heartbeat
         호출하지 않은 채 sleep(300s) — 다음 record 는 t=390s.
         (관측: round 14 시점 develop 활성도 낮음, 후보 0 빈도 ↑.)

t=120s : claude_usage_watch_loop sleep 만료 → 첫 iter.
         API call → record_loop_heartbeat.
         → claude_usage_watch_loop.ts 생성.

t=180s : heartbeat_watch_loop sleep 만료 → 첫 iter.
         detect_stale_heartbeats() 호출 (L3550).
         intervals = LOOP_HEARTBEAT_EXPECTED_INTERVALS (8 loop).
         각 loop 의 ts 파일 검사:
           - rev_post_merge_audit_loop.ts : FILE NOT FOUND
             → reason="missing", threshold=900s
             → stale 리스트에 추가
           - cycle_idle_watch_loop.ts : (initial_delay 명시 안 됨,
             값 확인 필요) → 부재 가능
           - thread_cleanup_loop.ts : interval 120s, 첫 record 가 늦으면
             부재 가능
           - directive_detect_register_watch_loop.ts : interval 600s,
             initial_delay 명시 안 됨, 부재 가능
         debounce 미적용 (첫 push) → DIGEST_CHANNEL_ID push:
           🚨 loop heartbeat stale — N개 loop 응답 없음 (#1087)
             - rev_post_merge_audit_loop: heartbeat 파일 부재 (임계 900s)
             ...
         사용자/rev sub-agent 에 false-positive 가시.

t=390s : rev_post_merge_audit_loop 두 번째 iter 직후 record → ts 생성.

t=780s : heartbeat_watch_loop 두 번째 iter (180+600=780s).
         이제 모든 ts 존재 → stale 0 → push 없음.
```

**근본 원인**:

1. `HEARTBEAT_WATCH_DEFAULT_INITIAL_DELAY_SECONDS = 180s` 는 단일 loop 의 평균
   warmup 보다 짧다. 가장 늦은 loop (특히 `record on continue=skip` 패턴이
   있는 loop) 의 첫 record 시점이 `loop initial_delay + 1×poll_interval` 일 수
   있으므로 보수적으로 `max(loop initial_delay + loop interval) = max(90+300,
   120+300, 60+300, …) ≈ 420s` 까지 보장해야 안전.
2. `detect_stale_heartbeats` 가 `reason="missing"` 을 `reason="stale"` 과 동등
   하게 push 한다 — daemon boot 직후의 "아직 안 만들어진" 상태와 "한참 안 들어
   온" 상태가 구분 불가. (별도 Q 로 §8.)

### 5-2) 옵션 비교

| # | 옵션 | initial_delay | 장점 | 단점 |
|---|---|---|---|---|
| A | 현행 유지 (`180s`) | 180s | 변경 0 | 회귀 지속 → false-positive 노이즈 |
| B | **5분 (`300s`)** | 300s | 단순 / 모든 5min polling loop 의 첫 iter 직후 안정 | claude_usage (120+300=420s) 후 첫 record 인 worst-case 는 여전히 cover X |
| C | **6분 (`360s`)** | 360s | digest(60+300=360s) / context(5+...) / cycle_idle / rev_post_merge(90+300=390s 직전) 까지 cover. simple 정수. | rev_post_merge 의 "continue=skip" 패턴이 worst-case 일 때 390s > 360s — 여전히 marginal |
| D | **7분 (`420s`)** | 420s | claude_usage worst-case (120+300=420s) 까지 cover. 모든 현행 loop "continue=skip" 가정 cover. | 진짜 incident detect 가 t=420s 까지 지연. 그러나 stale 임계 (expected × multiplier=3, 즉 5min loop 기준 900s) 보다 작아 incident detect 의미 손실 X |
| E | 10분 (`600s` = poll_interval 동일) | 600s | 모든 loop 한 번 record 보장 + 단순 (poll = initial_delay) | 보수적 과다 — incident detect 6 → 11min 으로 늦춤 |
| F | loop 별 dynamic delay (`max(intervals.values()) + grace`) | 동적 | 정확 | 코드 복잡 / record on continue skip 패턴까지 cover 보장 안 됨 |
| G | "missing" reason 만 첫 N iter 동안 suppress | 180s 유지 + grace 1 iter | initial_delay 안 건드림 | watchdog 본체 로직 복잡 (state) — 진짜 missing 인 영구 dead loop 도 1 iter 동안 silent |

### 5-3) 권장 옵션 — **D (`420s`)**

**선택 사유**:

1. **현행 loop 의 worst-case warmup cover**: `claude_usage_watch_loop` (120s
   initial + 300s poll) 와 `rev_post_merge_audit_loop` (90s initial + 300s
   poll + "skip on continue" 0건 시 next iter) 둘 다 첫 record 시점 ≤ 420s.
2. **simple integer + 환경변수 override 여지**: env
   `HEARTBEAT_WATCH_INITIAL_DELAY_SECONDS=NNN` 로 hotfix 가능 — 향후 새 loop
   추가 시 코드 재배포 없이 운영 조정.
3. **incident detect 지연 비용 ≤ 4분**: 현행 (180s) → 420s. stale 임계 =
   5min loop × 3 = 900s 이므로 실제로 detect 가능한 incident 는 *이미 15분
   이상 응답 없는* loop. 240s 추가 지연은 비례적으로 무시 가능.
4. **option F (dynamic) 보다 단순**: dynamic 은 `LOOP_HEARTBEAT_EXPECTED_INTERVALS`
   값에 의존 — 새 loop 추가 시마다 자동 재계산되지만, `initial_delay` 와
   loop interval 의 합 정보가 등록 안 됨 → 누락 가능성. 정적 420s 가 안전.
5. **회귀 가드 pytest 추가 비용 미미**: `test_heartbeat_watch_loop` 기존 케이스
   에 `initial_delay` 변경 검증 1건 추가.

**옵션 G (missing suppress) 비채택 사유**: 진짜 영구 dead loop (코드 변경 후
import 실패 등) 의 detect 가 늦어진다. initial_delay 확장은 *부팅 윈도우*
한정 보호 — 운영 phase 의 missing 은 그대로 detect.

### 5-4) 데이터 흐름 / 시퀀스 (개선 후)

```
t=0s   : daemon restart.
t=60s  : digest_loop 첫 record.
t=90s  : rev_post_merge_audit_loop 첫 iter (후보 0 → skip → record 미실행).
t=120s : claude_usage_watch_loop 첫 record.
t=390s : rev_post_merge_audit_loop 두 번째 iter → record.
t=420s : heartbeat_watch_loop 첫 iter → 모든 ts 존재 → stale 0.
t=1020s: heartbeat_watch_loop 두 번째 iter.
```

**진짜 incident 시나리오** (rev_post_merge_audit_loop silent crash at t=400s):

```
t=400s : rev_post_merge_audit_loop 마지막 record (t=390s) 직후 crash.
         이후 record 호출 없음.
t=420s : heartbeat_watch_loop 첫 iter — age=30s, threshold=900s → 정상.
t=1020s: heartbeat_watch_loop 두 번째 iter — age=630s, threshold=900s → 정상.
t=1620s: heartbeat_watch_loop 세 번째 iter — age=1230s, threshold=900s
         → stale detect → DIGEST push (정상).
```

→ 진짜 incident 는 *약 20분* 안에 detect (현행 ~15분 → 개선 후 ~20분).
사용자 P0 "사이클 절대 멈추면 안 됨" 의 5분 polling 기반 idle 검출
(`cycle_idle_watch_loop`) 과는 별도 — heartbeat watchdog 은 idle 자체가 아닌
*다른 watchdog 의 silent crash* 를 detect 한다. 따라서 20분 detect window 는
허용 범위.

### 5-5) API / DB / FE

- 해당 없음. bot.py 상수 1줄 + pytest.

## 6) 작업 분할 (예상 PR 리스트)

본 spec PR (현재) 은 docs only. 후속 impl 분할:

- [x] **PR docs** (현재): `docs/features/heartbeat-watchdog-initial-delay.md`
      + `docs/features/_template.md` "보호 영역" 항목 추가. type:docs scope:infra.
- [ ] **PR impl** (be 사이클): `tools/discord-daemon/bot.py`
      `HEARTBEAT_WATCH_DEFAULT_INITIAL_DELAY_SECONDS = 180` → `420`.
      env override `HEARTBEAT_WATCH_INITIAL_DELAY_SECONDS` 도입 (기존
      `heartbeat_watch_loop` 호출부에서 env 읽기). pytest 회귀 가드:
        - `test_heartbeat_watch_loop_first_iter_after_warmup_finds_all_loops_recorded`
        - `test_heartbeat_watch_initial_delay_env_override`
      type:fix scope:infra.

본 spec 자체는 **보호 영역 변경 없음**. 후속 impl PR 도 `bot.py` 만 — `tools/`
아래라 보호 영역 X. rev 단계 1/2/3 정상 적용.

## 7) 테스트 전략

### 단위 (be impl PR 에서)
- `tests/test_bot.py` 기존 `test_heartbeat_watch_loop_*` 케이스 보완:
  - `initial_delay=420` 인자가 default 인지 확인 (`inspect.signature`).
  - env `HEARTBEAT_WATCH_INITIAL_DELAY_SECONDS=10` 설정 시 callable 인자가
    10 으로 override 되는지 (stub `asyncio.sleep` 으로 호출 인자 capture).

### 통합 (수동, be impl PR 후 NCP 배포 후)
- daemon restart 후 `sudo journalctl -u mobruji-discord-daemon -f --since "1 min ago"`
  로 첫 `heartbeat_watch_loop` iter 시점이 t≈420s 인지 확인.
- 같은 시점 DIGEST_CHANNEL_ID 에 false-positive push 없는지 확인 (Discord
  메시지 history grep `loop heartbeat stale` 1h window).

### 회귀
- 진짜 silent crash 시뮬 (`asyncio.sleep(99999)` injection) 후 detect 까지
  최대 ~20분 확인 — 본 spec 의 trade-off 가 spec 의도와 일치하는지 sanity.

## 8) 오픈 질문

| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | `record_loop_heartbeat` "skip on continue" 패턴 (`rev_post_merge_audit_loop` L3200-3217 등) 리팩토링 — `try/finally` 또는 매 iter 끝단 unconditional record. | (a) 본 spec 후속 impl PR 에 포함 / (b) 별도 spec (loop heartbeat reliability) / (c) 미루기 | @goohong / 2026-06-04 |
| Q2 | `detect_stale_heartbeats` 가 `reason="missing"` 과 `reason="stale"` 을 동일 push 로 묶음. boot 직후 / 영구 dead 구분 가능하도록 분리할지. | (a) 분리 push (boot 후 N분 grace) / (b) 본 spec impl PR 에 grace 1 iter (옵션 G) 동반 / (c) 현행 유지 | @goohong / 2026-06-04 |
| Q3 | env override 변수명 — `HEARTBEAT_WATCH_INITIAL_DELAY_SECONDS` vs `HEARTBEAT_WATCH_INITIAL_DELAY` (suffix 생략). | (a) `_SECONDS` suffix (기존 상수명 일관) / (b) `_DELAY` (env 관례 짧게) | @goohong / 2026-06-04 |

## 9) 결정 로그

- 2026-05-28: 초안 작성 (status=draft). round 14/15 false-positive 회귀 박제.
  옵션 D (420s) 권장. 후속 impl PR 분할 (be 사이클 1건).
