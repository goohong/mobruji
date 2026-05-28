---
feature: Loop heartbeat reliability — `record_loop_heartbeat` "skip on continue" 패턴 일소
slug: loop-heartbeat-reliability
status: draft
owner: @goohong
scope: infra
related_issues: [1087]
related_prs: [1215]
last_reviewed: 2026-05-28
---

# Loop heartbeat reliability — `record_loop_heartbeat` "skip on continue" 패턴 일소

## 1) 개요 (What / Why)

`bot.py` 의 watchdog 패밀리 (#1087) 는 모든 polling `*_loop` 함수가 매 iter
끝에 `record_loop_heartbeat(<name>)` 를 호출해 `.ts` 파일을 갱신한다는 전제
위에 설계되었다. 그러나 현 시점 (2026-05-28) bot.py 안 8 loop 중 **4 loop 가
조건 분기에서 `continue` 또는 `return` 으로 record 호출을 skip** 하거나
**try 블록 안에 record 가 있어 except 발생 시 skip** 하는 패턴을 보유한다.

PR #1215 (`docs/features/heartbeat-watchdog-initial-delay.md`) 는 watchdog 본체
`initial_delay` 를 420s 로 확장해 *부팅 윈도우* 한정 false-positive 를 가린다.
본 spec 은 **부팅 외 phase 의 false-positive 와 silent skip** 까지 일소하기
위해 `record_loop_heartbeat` 호출 위치 표준 패턴을 확정한다.

대상 actor:
- **nmae** — rev round false-positive 노이즈 감소.
- **be sub-agent** — 후속 impl PR 담당 (bot.py 4 loop refactor).
- **사용자** — DIGEST `🚨 loop heartbeat stale` push 가 진짜 incident 일 때만
  발화하도록 신뢰성 확보.

## 2) 사용자 시나리오

- daemon 운영 phase 에서 `AUTOCLEAR_PAUSED_FLAG` 가 켜진 동안
  `context_auto_clear_loop` 가 매 iter `continue` 만 호출 → record 미실행 → 5분 ×
  3 = 15분 후 heartbeat watchdog 가 `context_auto_clear_loop` 를 stale 분류 →
  DIGEST false-positive push. 사용자/rev sub-agent 가 incident 로 오인.
- `rev_post_merge_audit_loop` 가 develop 활성도 낮은 시간대 (예: 휴일) "후보 0"
  로 매 iter `continue` → record 미실행. 옵션 D (420s) 로 부팅 윈도우는 가렸지만
  운영 phase 의 누적 skip 으로 *최초 record 후 5분 + 4분 = 9분 누적* 시
  false-positive 위험.
- `thread_cleanup_loop` 가 Discord guild_id fetch 실패 (예: token 만료) 시 매
  iter `continue` → record 미실행. token 만료는 별 incident 라 가시화 필요한데
  heartbeat 미갱신은 token 만료 신호와 무관한 노이즈를 추가.
- `cycle_idle_watch_loop` 가 cycle-status.json 읽기 실패 시 `continue` → record
  미실행. cycle-status.json 자체가 사용자 P0 (사이클 절대 멈추면 안 됨) 의
  핵심이므로 이중 노이즈.

## 3) 요구사항

### 기능 요구사항
- [ ] 모든 bot.py `*_loop` 함수는 매 iter **단일 종점** 에서
      `record_loop_heartbeat(<name>)` 을 호출한다 (조건 분기 무관).
- [ ] `continue` / `return` 은 record 호출을 skip 하지 않는다 — `try/finally`
      패턴 또는 record 호출을 모든 `continue` 직전으로 분산.
- [ ] except 블록 도달 시에도 record 가 호출되도록 try 밖 finally 또는 except
      뒤에 위치.
- [ ] 단, **fatal disabled return** (`if poll_interval <= 0: return`) 은 예외 —
      loop 자체가 시작 전 종료되므로 watchdog 분류에서도 expected_intervals
      등록 자체를 안 해야 옳다 (별 Q3).

### 비기능 요구사항
- 변경 영향 범위 = bot.py 4 loop 함수 (`rev_post_merge_audit_loop`,
  `context_auto_clear_loop`, `cycle_idle_watch_loop`, `thread_cleanup_loop`)
  + pytest 회귀 가드 4건 (loop 당 1건). 보호 영역 없음 (`tools/` 외).
- false-positive push 노이즈 = 0 (1주 운영 DIGEST grep `loop heartbeat stale` 0건).
- 진짜 incident detect window 변동 없음 — 5min loop × 3 multiplier = 900s 유지.
- 단일 종점 패턴 강제 — 향후 신규 loop 추가 시 lint / pytest 로 회귀 가드.

## 4) 범위 / 비범위

### 포함
- bot.py 4 loop 의 record 호출 위치 표준화 (`try/finally` 또는 매 `continue` 전
  명시 호출).
- pytest 회귀 가드 — 각 loop 의 모든 분기 (success / continue / except) 도달 시
  record 가 호출되는지 stub 으로 검증.
- 표준 패턴 박제 (ADR-0024) — 신규 loop 추가 시 동일 패턴 강제.

### 제외 (Out of Scope)
- watchdog 본체 (`heartbeat_watch_loop`) 의 `initial_delay` 추가 조정 —
  PR #1215 의 `docs/features/heartbeat-watchdog-initial-delay.md` 가 cover.
- `detect_stale_heartbeats` 의 `reason="missing"` 과 `reason="stale"` 분리 push
  포맷 — PR #1215 Q2 가 cover (별 spec 가능성).
- 새 `*_loop` 추가 — 본 spec 은 기존 4 loop refactor 만.
- record 함수 자체 (`record_loop_heartbeat`) 시그니처 / atomic write 정책 변경 —
  현행 충분 (PR #1087).

## 5) 설계

### 5-1) 배경 — bot.py 8 loop 현 상태 (2026-05-28)

| Loop | record 위치 | continue/return skip | 위치 분류 |
|---|---|---|---|
| `digest_loop` | try 밖, sleep 전 (L2053) | 없음 | ✅ 표준 |
| `context_auto_clear_loop` | **try 안** (L2341) | **있음** — L2261 `AUTOCLEAR_PAUSED_FLAG` + L2271/L2277/L2313/L2315 inner `continue` | 🔴 패턴 위반 |
| `cycle_idle_watch_loop` | try 안 (L2768) | **있음** — L2777 `cycle-status.json` read 실패 시 `continue` | 🔴 패턴 위반 |
| `rev_post_merge_audit_loop` | try 밖 (L3258) | **있음** — L3203 후보 0 / L3217 debounce / L3227 tmux session 부재 시 `continue` | 🔴 패턴 위반 |
| `claude_usage_watch_loop` | try 밖 (L3359) | 없음 (continue 미사용) | ✅ 표준 |
| `directive_register_watch_loop` | try 밖 (L3478) | 없음 | ✅ 표준 |
| `heartbeat_watch_loop` | try 밖 (L3588) | 없음 | ✅ 표준 |
| `thread_cleanup_loop` | try 밖 (L4004) | **있음** — L3941 guild_id fetch 실패 시 `continue` | 🔴 패턴 위반 |

회귀 영향 4 loop. 모두 `*_loop` 가 watchdog `LOOP_HEARTBEAT_EXPECTED_INTERVALS`
(L415-426) 에 등록되어 stale 분류 대상.

### 5-2) 회귀 시퀀스 — 운영 phase false-positive 예시

`thread_cleanup_loop` 시나리오 (Discord token 만료):

```
t=0s          : daemon 정상 가동, 모든 loop 첫 record 완료.
                thread_cleanup_loop.ts = wall_time_0.
t=120s..N*120 : thread_cleanup_loop 매 iter:
                  guild_id_fetcher() → None (token 만료)
                  → L3941 continue
                  → record 호출 skip
                  → ts 파일 안 갱신 (마지막 갱신 = t=0).
t=360s        : heartbeat_watch_loop iter (poll_interval=600s, 이전 iter 후).
                LOOP_HEARTBEAT_EXPECTED_INTERVALS["thread_cleanup_loop"] = 120s.
                stale 임계 = 120 × multiplier(3) = 360s.
                age = 360 - 0 = 360s = threshold → stale 분류 (경계).
t=420s        : 다음 watchdog iter (interval=600s 후 실제로는 t=960s)
                age = 960 - 0 = 960s ≫ 360s → DIGEST push:
                  🚨 loop heartbeat stale — thread_cleanup_loop 응답 없음
                실제 root cause = token 만료 (별 가시 채널 권고)
                But heartbeat 알림은 token 만료를 안 알려줌 — 단순 "응답 없음".
                사용자가 DIGEST 보고 "loop crash 인가" 오인 → root cause 조사
                시간 소비.
```

이 사고는 PR #1215 의 `initial_delay=420s` 로는 **막을 수 없다** — 부팅 윈도우
가 아닌 운영 phase 노이즈.

### 5-3) 옵션 비교

| # | 옵션 | 변경 단위 | 장점 | 단점 |
|---|---|---|---|---|
| A | `try/finally` 안 `record_loop_heartbeat` 호출 (모든 loop) | loop 함수당 ~5줄 | 단일 종점 / 모든 분기 cover / except 도 cover / pytest 검증 쉬움 | finally 안 sleep 까지 들이려면 구조 변경 — 그러나 sleep 은 finally 밖에 두는 게 안전 (record 만 finally) |
| B | 매 iter unconditional record (모든 `continue` 전 명시 호출) | loop 함수당 ~3-5줄 (continue 마다) | 명시적 / try/finally 학습 부담 X | `continue` 위치 늘어나면 라인 증가 / 추가 누락 risk |
| C | record 책임을 loop 본체에서 외부 wrapper 로 분리 (예: `async def _heartbeat_wrap(name, body)`) | 신규 wrapper + loop body 재구조화 | 강제 / 신규 loop 추가 시 자동 적용 | bot.py 구조 대수술 / 테스트 자산 영향 / 우선 회피 |
| D | 현행 유지 + watchdog 본체 `reason="missing"` 과 `reason="stale"` 분리만 (옵션 D-1) | watchdog 본체 1곳 | 영향 최소 | 운영 phase 4 loop 의 stale 분류 자체는 유지 — false-positive 감소만, 근본 해결 X |
| E | `record_loop_heartbeat` 함수에 "auto-skip on continue" 데코레이터 / context manager 도입 | record 호출부 0줄 / decorator 1개 | 가장 우아 | Python `continue` 가 함수 경계 못 넘어 `with` 안 보장 X — `try/finally` 와 의미상 동치 (옵션 A 와 같음) |

### 5-4) 권장 옵션 — **A (try/finally)**

**선택 사유**:

1. **단일 종점 보장**: `try` 본체 / `continue` / `except` 모든 분기에서 finally
   가 실행됨 — 매 iter end 에서 정확히 1회 record.
2. **except 도 cover**: 현 `context_auto_clear_loop` (L2341 record 가 try 안)
   는 `except Exception as exc` 도달 시 record 호출 안 됨. try/finally 로
   이동하면 except 도 통과 가능.
3. **lint / pytest 검증 가능**: `ast.parse` 로 모든 `async def *_loop` 함수의
   `while True:` 본체에 `try/finally:` 안 `record_loop_heartbeat` 호출이
   존재하는지 회귀 가드 가능 (`tests/test_bot_loop_heartbeat_pattern.py`).
4. **변경 영향 최소**: 4 loop × 평균 5줄 변경 = 20줄. impl PR 단일 commit 으로
   가능.
5. **신규 loop 추가 시 강제 학습 비용 작음**: `try/finally` 는 Python 관용
   패턴. `12-template §1` sub-agent 룰에 한 줄 추가로 학습 비용 미미.

**옵션 B (unconditional record before continue) 비채택 사유**:
명시 호출을 모든 `continue` 분기마다 박는 패턴은 추가 `continue` 가 생길 때마다
누락 risk. `rev_post_merge_audit_loop` 만 해도 4 군데 `continue` — 한 곳 빠뜨리면
회귀. try/finally 가 구조적 강제.

**옵션 C (wrapper) 비채택 사유**: bot.py 구조 대수술 + 기존 pytest 자산 (각
loop 별 단위 테스트) 영향 큼. 본 spec 의 목적 = 신뢰성 회복, 구조 변경 X.

**옵션 D (watchdog reason 분리) 비채택 사유**: 근본 원인 (record 누락) 자체는
유지. 부분 개선이지만 본 spec 목적과 어긋남. **Q2 로 별도 spec 후속 가능.**

### 5-5) 표준 패턴 — ADR-0024 본문 박제

```python
async def example_loop(
    *args,
    poll_interval: int = DEFAULT_INTERVAL,
    initial_delay: int = DEFAULT_INITIAL_DELAY,
    sleeper=asyncio.sleep,
    **kwargs,
) -> None:
    """매 iter 끝에서 단일 종점 record_loop_heartbeat 호출 — ADR-0024."""
    if poll_interval <= 0:
        logger.info("example_loop disabled (poll_interval<=0)")
        return  # watchdog 등록 자체 안 해야 옳음 (Q3 참조).

    await sleeper(initial_delay)
    while True:
        try:
            # … iter 본체 (조건 분기에서 continue 자유롭게 사용 가능) …
            if should_skip:
                continue  # finally 가 record 호출 보장
            # … success path …
        except asyncio.CancelledError:
            raise  # bot 종료 신호는 finally 후 외부 전파.
        except Exception as exc:  # noqa: BLE001
            logger.warning("example_loop iter 실패: %s", exc)
        finally:
            record_loop_heartbeat("example_loop")
        await sleeper(poll_interval)
```

**핵심 룰**:
- `record_loop_heartbeat` 는 `finally` 안 — 모든 분기 cover.
- `await sleeper(poll_interval)` 은 `try/finally` 밖 — sleep 자체가 record
  완료 후 일어나야 다음 iter 진입 보장.
- `except asyncio.CancelledError: raise` 는 finally 후 외부 전파 — bot 종료 시
  마지막 record 1회 보장.

### 5-6) loop 별 변경 diff 요약

#### a. `rev_post_merge_audit_loop` (L3142-3259)
- 현재: try 밖 record (L3258) + continue 4건 (L3203/L3217/L3227) → continue 시 record skip.
- 변경: `try` 본체 ~ `except` 뒤에 `finally: record_loop_heartbeat("rev_post_merge_audit_loop")` 추가. `continue` 줄들의 `await asyncio.sleep(poll_interval)` 는 try 안에 두지 말고 finally 다음 줄 (또는 별 패턴) 로 재구성.
- 변경 라인 수: ~8줄.

#### b. `context_auto_clear_loop` (L2213-2345)
- 현재: try **안** record (L2341, except 발생 시 skip) + continue 4건 (L2261 / L2271 / L2277 / L2313 / L2315).
- 변경: record 를 try/finally 의 finally 로 이동. except 발생 시도 호출 보장.
- 변경 라인 수: ~5줄.

#### c. `cycle_idle_watch_loop` (L2692-3012)
- 현재: try 안 record (L2768) + continue (L2777 cycle-status 읽기 실패).
- 변경: record 를 finally 로 이동. cycle-status 읽기 실패에도 record 호출.
- 변경 라인 수: ~5줄.
- 주의: `await asyncio.sleep(poll_interval)` 이 try 본체 첫 줄 (L2767) 인 점 유의 — sleep 위치는 그대로, record 위치만 finally 이동.

#### d. `thread_cleanup_loop` (L3853-4005)
- 현재: try 밖 record (L4004) + continue 1건 (L3941 guild_id fetch 실패).
- 변경: finally 패턴으로 통일. continue 시도 record 호출.
- 변경 라인 수: ~5줄.

### 5-7) 표준 패턴 강제 — lint / pytest

#### 정적 회귀 가드 (선택 — Q1)
`tests/test_bot_loop_heartbeat_pattern.py` (신규):
```python
import ast
from pathlib import Path

def test_all_loops_record_in_finally():
    src = Path("tools/discord-daemon/bot.py").read_text(encoding="utf-8")
    tree = ast.parse(src)
    for node in ast.walk(tree):
        if not isinstance(node, ast.AsyncFunctionDef):
            continue
        if not node.name.endswith("_loop"):
            continue
        # while True: try: … finally: record_loop_heartbeat(<name>) 패턴 확인
        assert _has_record_in_finally(node), f"{node.name} 패턴 위반"
```

#### 단위 회귀 가드 (필수)
loop 당 1건 — stub `sleeper` / `record_loop_heartbeat` 로 continue / except 분기
각각 도달 시 record 호출 횟수 검증.

### 5-8) API / DB / FE
- 해당 없음. bot.py 4 loop refactor + pytest.

## 6) 작업 분할 (예상 PR 리스트)

본 spec PR (현재) 은 docs only. 후속 impl 분할:

- [x] **PR docs** (현재): `docs/features/loop-heartbeat-reliability.md` + `docs/decisions/0024-loop-heartbeat-reliability.md` + 인덱스 sync.
- [ ] **PR impl** (be 사이클): `tools/discord-daemon/bot.py` 4 loop refactor + pytest 회귀 가드 4건.
  - 변경 파일: `tools/discord-daemon/bot.py` (4 loop), `tests/test_bot.py` (회귀 가드).
  - 변경 라인 수: ~30줄 (loop 4 × 5-8줄) + pytest ~80줄.
  - 라벨: `type:fix`, `scope:infra`, `ai-generated`, `ai:claude`, `session:backend`.
  - **보호 영역 변경 없음** (`tools/` 외).
- [ ] **PR docs (선택, Q1)**: `tests/test_bot_loop_heartbeat_pattern.py` 정적 회귀 가드 (ast walk). impl PR 에 묶거나 별 PR 분리 — be sub-agent 판단.

### 보호 영역 변경 여부 (필수 명시)

- 보호 영역 변경 여부: ☒ 없음 — docs only PR + 후속 be impl PR 도 `tools/discord-daemon/bot.py` 만 (CLAUDE.md §4 보호 영역 외).

## 7) 테스트 전략

### 단위 (be impl PR 에서)
- `tests/test_bot.py` 신규 케이스 4건 (loop 당 1건):
  - `test_rev_post_merge_audit_loop_records_on_zero_candidates`
  - `test_context_auto_clear_loop_records_when_paused_flag_set`
  - `test_cycle_idle_watch_loop_records_on_cycle_status_read_failure`
  - `test_thread_cleanup_loop_records_when_guild_id_fetch_fails`
- 각 테스트 패턴: stub `record_loop_heartbeat` 호출 카운터 + stub `sleeper` /
  `candidate_fetcher` 등 → loop 1 iter 진입 후 cancel → record 카운터 ≥ 1 검증.

### 통합 (수동, be impl PR 후 NCP 배포 후)
- daemon restart 후 1시간 운영 → `sudo ls -la ~/.mobruji/heartbeat/` 로 각
  `.ts` 파일 mtime 이 expected interval × 1.2 이내인지 확인 (4 loop 모두).
- DIGEST channel grep `loop heartbeat stale` 1주 0건 확인.

### 회귀
- 옵션 D-1 (watchdog reason 분리) 미적용 상태에서도 본 spec impl PR 만으로
  false-positive 0 도달 가능한지 1주 운영 검증.
- 진짜 incident 시뮬 (예: `await asyncio.sleep(99999)` injection) → detect
  window 변동 없음 확인 (= 900s 유지).

## 8) 오픈 질문

| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | 정적 회귀 가드 (ast walk) 추가 여부 — 신규 loop 추가 시 패턴 위반 자동 fail. | (a) impl PR 에 포함 / (b) 별 PR 분리 / (c) 안 함 (단위 테스트만) | @goohong / 2026-06-04 |
| Q2 | disabled 분기 (`if poll_interval <= 0: return`) 가 watchdog `LOOP_HEARTBEAT_EXPECTED_INTERVALS` 등록 자체에서 제외되어야 하는가. 현행은 등록만 되고 record 안 됨 → boot 직후 stale 분류 위험. | (a) env 기반 enable/disable 시 등록도 동적으로 제외 / (b) 현행 유지 + initial_delay (PR #1215) 만으로 가림 / (c) "disabled" 상태도 명시적으로 watchdog 측에서 인지 (별 API) | @goohong / 2026-06-04 |
| Q3 | `cycle_idle_watch_loop` 의 sleep 위치 — `await asyncio.sleep(poll_interval)` 이 try 본체 첫 줄에 있음 (L2767). 다른 loop 와 다른 패턴 (record 도 try 안 L2768). finally 패턴 적용 시 sleep 도 위치 조정 필요. | (a) sleep 을 finally 다음 줄로 이동 (다른 loop 와 통일) / (b) sleep 위치 유지 + finally 만 도입 / (c) loop 별 구조 차이 인정 — 표준 패턴 ADR 에 sleep 위치 명시 X | @goohong / 2026-06-04 |
| Q4 | PR #1215 권장 `initial_delay=420s` 와 본 spec 의 try/finally refactor 의 **시간 순서**. | (a) #1215 impl 먼저 머지 → 본 spec impl 후속 / (b) 본 spec impl 먼저 → #1215 impl 후 (각 loop record 보장된 상태에서 initial_delay 산정 정확) / (c) 동시 develop merge (서로 독립) | @goohong / 2026-06-04 |

## 9) 결정 로그

- 2026-05-28: 초안 작성 (status=draft). PR #1215 Q1 후속.
  - bot.py 8 loop 중 4 loop 가 `record_loop_heartbeat` skip 패턴 보유 사실 박제.
  - 옵션 A (try/finally) 권장 — 단일 종점 / except cover / lint 가능.
  - 후속 be impl PR 분할 (~30줄 + pytest ~80줄).
  - ADR-0024 자율 신설 — 표준 패턴 박제 (신규 loop 추가 시 강제 학습).
