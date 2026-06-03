---
feature: cycle forum thread launch 레이스 fix — 합성 directive threadless 보고 fallback
slug: cycle-thread-launch-race-fix
status: draft
owner: @mobruji-maestro
scope: infra
related_issues: [1538]
related_prs: []
last_reviewed: 2026-06-03
---

# cycle forum thread launch 레이스 fix — 합성 directive threadless 보고 fallback

## 1) 개요 (What / Why)

- **대상 액터**: work-queue dispatcher (`tools/agent/tools_queue.dispatch_once`) + `launch_subagent` (`tools/agent/tools_subagent.py`).
- **문제**: 합성 directive (`SyntheticDirective` — `rev-pr-<N>` / `pr-review-<N>`) 는 자율 흐름에서 `tools_queue.enqueue_rev_for_pr_if_any` 가 자동 생성하며, 사용자 채택 directive 와 달리 `thread_id=None` 으로 박힌다 (대화 thread 부재). 적재 시 `enqueue_directive` 가 cycle forum thread 를 **best-effort** 신설(`_create_cycle_thread` → `discord-reply.sh --forum-post-auto-tag`)하는데, 이 신설이 실패/지연(race)하면 directive 에 `cycle_thread_id` 도 안 박힌다.
- 그 결과 `dispatch_once` 가 launch 할 때 `launch_subagent` 의 `pending_thread_id = cycle_thread_id or directive.thread_id` 가 둘 다 비어 **`ValueError("pending thread 미등록")`** 를 던진다. dispatch 의 generic `except Exception` 가 이를 일반 launch 실패로 분류 → 매 tick 재시도(journal noise) → `MAX_LAUNCH_ATTEMPTS`(3) 초과 시 `wq.dequeue` 로 **큐에서 제외(드롭)** → 해당 PR 이 rev 검토를 영영 못 받고 자율 머지 루프가 끊긴다.
- 본 spec 은 이 레이스를 **core 디스패치 경로의 회귀 0** 으로 안전하게 해소하는 설계를 박는다 (코드 변경 없음 — 설계 SoT). 채택안 = 옵션 (a) `ThreadlessReportFallback`.

> 본 spec 은 dispatch/launch 경로 SoT `directive-work-queue.md` 의 4-2 dispatch 흐름과 placeholder thread 가드 SoT `cycle-forum-placeholder-guard.md` (wrapper 의 pending-thread 부재 DIGEST fallback) 의 사이를 잇는다. 전자는 "thread 가 있을 때" 의 흐름, 후자는 "wrapper 가 thread 를 못 받았을 때" 의 push 채널, 본 spec 은 "합성 directive 가 애초에 thread 를 못 가졌을 때 launch 를 막지 않는" 결정을 담당한다.

## 2) 사용자 시나리오

- be sub-agent 가 PR #N 을 내고 완료 → `enqueue_rev_for_pr_if_any` 가 `rev-pr-N` 합성 directive 생성 + rev 큐 적재. 이 순간 rev forum 채널이 일시적 응답 지연(또는 토큰/forum id env 미상속)으로 cycle thread 신설이 None 반환 → directive 에 thread 가 0건.
- dispatcher 가 다음 tick 에 rev 큐에서 `rev-pr-N` 을 꺼내 launch 시도 → `ValueError("pending thread 미등록")` → "❌ launch 실패 (1/3)" → 다음 tick (2/3) → (3/3) → **dequeue**. PR #N 은 reviewed:claude 도 못 받고 머지 게이트에 영구 정지. journal 엔 매 tick `work-queue launch 실패` warning 누적.
- **TO-BE**: `rev-pr-N` 은 합성 directive 이므로 thread 부재여도 launch 가 진행되고, rev sub-agent 가 **대상 PR #N 코멘트**(rev-gate 문자열 `✅ rev e2e PR pass` / `📝 rev no-op pass` 포함)로 보고한다. forum 가시성은 thread 신설이 성공했을 때만 추가로 얻는 best-effort 보너스로 강등 — 신설 실패가 자율 루프를 끊지 않는다.

## 3) 요구사항

### 기능 요구사항

- [ ] **F-1**: `launch_subagent` 가 `allow_empty_thread: bool = False` 인자를 받는다. 기본값 False 일 때는 **현행 그대로** — `pending_thread_id` 부재 시 `ValueError` raise (legacy 사고 fix 가드 보존, §5-3 참조).
- [ ] **F-2**: `allow_empty_thread=True` 이고 `pending_thread_id` 가 부재일 때 — `ValueError` 를 던지지 않고 빈 pending thread 로 launch 진행. wrapper 에는 `--pending-thread-id` 를 빈 값(또는 미전달)으로 넘기고, wrapper 의 placeholder fallback (`cycle-forum-placeholder-guard.md §5-4-1` `--digest` 단발) 경로에 위임한다.
- [ ] **F-3**: `dispatch_once` 가 directive_id 가 합성 prefix(`rev-pr-` / `pr-review-`)일 때만 `allow_empty_thread=True` 로 `launch_subagent` 를 호출한다. 합성 prefix 판정은 단일 상수 `SYNTHETIC_DIRECTIVE_PREFIXES = ("rev-pr-", "pr-review-")` 로 둔다 (`_ensure_pr_xrefs` 의 `"rev-pr-"` 하드코딩과 cross-ref — §5-5 일관성 항목).
- [ ] **F-4**: 합성 directive 가 thread 부재로 threadless launch 됐을 때 — dispatch 의 `report_thread` 가 빈 문자열이라 `_comment` 가 이미 no-op (Discord noise 0). 단, **journal 가시성**을 위해 `dispatch_once` 가 threadless launch 1건당 `logger.info("threadless launch (synthetic): cycle=%s directive=%s — PR 코멘트 보고", ...)` 1줄 emit (드롭/재시도 noise 와 구분되는 정상 경로 신호).
- [ ] **F-5**: thread 신설이 **성공**한 합성 directive 는 회귀 0 — 기존대로 `cycle_thread_id` 를 pending thread 로 사용해 forum thread 보고. `allow_empty_thread=True` 여도 thread 가 있으면 그걸 우선 사용(`cycle_thread_id or directive.thread_id` 순서 유지)하므로 가시성 손실 없음.

### 비기능 요구사항

- **회귀 안전성**: 사용자 채택(실) directive 의 launch 경로는 byte 단위 무변경 — `allow_empty_thread` 기본 False 라 `dispatch_once` 의 비합성 directive 호출은 인자조차 안 바뀐다. legacy 사고(`pending_thread_id` 누락) catch 능력 100% 보존 (§5-3).
- **관측성**: 합성 threadless launch = `logger.info` 정상 신호 1줄 (F-4). 기존 launch 실패 warning 과 명확히 구분. 드롭(MAX_LAUNCH_ATTEMPTS 초과)은 합성 directive 의 thread 부재로는 더 이상 발생 안 함 — 진짜 wrapper rc≠0 실패에서만 발생.
- **결합도**: dispatch/launch 가 Discord forum 가용성에 hard-depend 하지 않는다 — thread 신설은 best-effort 그대로, 실패가 자율 루프를 끊지 않는다. 옵션 (b)/(c) 대비 핵심 이점 (§5-2).
- **idempotency**: 본 변경은 큐 멱등성(`wq.enqueue` 중복 차단)·directive 멱등성을 건드리지 않는다.

## 4) 범위 / 비범위

### 포함
- `tools/agent/tools_subagent.py` `launch_subagent` 시그니처에 `allow_empty_thread` 추가 + 빈 thread 분기.
- `tools/agent/tools_queue.py` `dispatch_once` 의 합성 prefix 판정 + `allow_empty_thread` 전달 + threadless launch info 로그 + `SYNTHETIC_DIRECTIVE_PREFIXES` 상수.
- 단위 테스트 (`test_tools_subagent.py` / `test_tools_queue.py`) — threadless launch 통과 + 실 directive ValueError 보존.

### 제외 (Out of Scope)
- wrapper(`agent-launch-wrapper.sh`) 의 빈 pending-thread-id 처리 자체 — `cycle-forum-placeholder-guard.md` SoT (본 spec 은 launch_subagent 가 ValueError 로 그 경로를 **선점하지 않게** 하는 데 한정).
- `_create_cycle_thread` 의 신설 신뢰성 개선 / 재시도 — 별도 검토 (본 spec 은 신설 실패를 graceful 흡수만).
- cycle forum thread 신설을 동기 보장하는 인프라 변경 — 옵션 (b) 로 §5-2 에서 기각.
- `pr-review-<N>` 의 forum 가시화 흐름 자체 (register_directive_pending kind=pr_review) — 이미 thread_id=None graceful (`tools_cycle.py`). 본 spec 은 dispatch 되는 `rev-pr-<N>` 이 1차 대상이고, `pr-review-` prefix 는 동일 정책 적용 위해 상수에만 포함.

## 5) 설계

### 5-1) 도메인 모델

- `06-domain-model.md §4-2` 신규 등재 (본 PR): **`SyntheticDirective`**(합성 directive) / **`ThreadlessReportFallback`**(threadless 보고 fallback). 정의·출처는 §4-2 표 SoT.
- 기존 용어 cross-ref: `CycleLaunchThreadId`(§4-2) — 합성 directive 도 thread 신설 성공 시 이 thread 를 launch thread 로 사용. `PlaceholderThreadId` — 본 spec 은 placeholder 가 아니라 thread **부재(None)** 케이스라 별개지만, 둘 다 종착은 wrapper 의 DIGEST 단발 fallback (`cycle-forum-placeholder-guard.md §5-4-1`) 으로 수렴.

### 5-2) 옵션 분석 + 채택 결정 (회귀 위험 중심)

core 디스패치 경로이므로 3개 옵션을 회귀 blast radius 로 비교한다.

| 옵션 | 요지 | 회귀 위험 / blast radius | 판정 |
|---|---|---|---|
| **(a) 합성 directive 빈 thread 허용 (= ThreadlessReportFallback)** | `launch_subagent(allow_empty_thread=True)` 로 합성 directive 만 thread 부재 launch 허용. sub-agent 가 PR 코멘트로 보고. | 실 directive 경로 무변경(기본 False) → legacy 가드 100% 보존. wrapper 의 기존 DIGEST 단발 fallback 과 dovetail. forum 가시성은 thread 성공 시 그대로. 변경 = 좁은 조건 분기 2곳. | **채택** |
| (b) dispatch 전 thread 동기 확정 | 적재/launch 전 `_create_cycle_thread` 를 성공할 때까지 보장(재시도/blocking). | dispatch 가 Discord forum 가용성에 hard-depend → forum/env 영구 부재 시 rev 큐가 영구 정지(현 드롭보다 더 나쁨). 재시도 budget = 또 다른 noise. 비핵심 보고 채널을 핵심 실행의 선행조건으로 승격. | 기각 (결합도↑·실패모드 악화) |
| (c) launch 시 inline thread 생성 | `launch_subagent` 가 thread 부재 시 직접 `_create_cycle_thread` 호출. | `tools_subagent` → `tools_queue` 역참조 = **순환 import** (`tools_queue` 가 `tools_subagent` 를 import). launch primitive 가 Discord-aware 로 오염(관심사 혼합). 영구 실패 케이스 미해결 → 결국 (a) 의 빈-thread 경로 필요. | 기각 (순환 import·관심사 혼합·미완결) |

**채택 = (a)**. 근거: ① 실 directive 의 ValueError 가드(legacy 사고 fix)를 보존하면서 ② 합성 directive 의 noise+드롭을 동시 제거하고 ③ wrapper 의 기존 placeholder DIGEST fallback 과 자연스럽게 맞물리며(중복 인프라 0) ④ 변경 표면이 좁은 조건 분기 2곳으로 회귀 blast radius 가 최소다.

### 5-3) legacy 사고 가드 보존 근거 (왜 ValueError 를 통째로 없애면 안 되는가)

`launch_subagent` 의 `pending_thread_id` 부재 ValueError 는 원래 **`directive_id` 인자 강제 + pending_thread_id 누락 사고**(tools_subagent.py 모듈 docstring §1 "legacy 사고 root cause fix")를 code 로 박제한 가드다. 실 사용자 directive 가 thread 를 잃으면 forum 보고가 통째로 사일런스되므로 이 가드는 그대로 필요하다. 따라서 본 spec 은 가드를 **합성 directive 에 한해서만** 끄고(`allow_empty_thread=True`), 그 경우엔 PR 코멘트라는 대체 보고 채널이 보장되어 사일런스가 발생하지 않는다. 실 directive 는 PR 이 아직 없을 수도 있어 대체 채널이 없으므로 strict 유지가 맞다.

### 5-4) 데이터 흐름 / 시퀀스

```
[be sub-agent 완료] → enqueue_rev_for_pr_if_any
    ↓
ev.set_state("directive:rev-pr-N", {thread_id: None, ...})   # 합성, dialogue thread 없음
    ↓
enqueue_directive("rev", "rev-pr-N", ...)
    ├─ _create_cycle_thread("rev", ...)  # best-effort
    │     ├─ 성공 → directive.cycle_thread_id = <snowflake>   ──┐ (정상 경로, 회귀 0)
    │     └─ None (race/env) → cycle_thread_id 미설정          ──┤
    ↓                                                          │
dispatch_once: peek_next("rev") → rev-pr-N                     │
    ├─ is_synthetic = id.startswith(SYNTHETIC_DIRECTIVE_PREFIXES)  # True
    ├─ launch_subagent(..., cycle_thread_id=directive.cycle_thread_id or None,
    │                       allow_empty_thread=is_synthetic)   │
    │     pending = cycle_thread_id or directive.thread_id     │
    │     ├─ pending 있음 → 기존대로 forum thread 보고  ◀──────┘ (5-5 성공 경로)
    │     └─ pending 없음 + allow_empty_thread=True →
    │            ValueError 안 던짐 → wrapper 에 빈 --pending-thread-id
    │            → wrapper DIGEST 단발 fallback (cycle-forum-placeholder-guard §5-4-1)
    │            → rev sub-agent: PR #N 코멘트로 보고 (rev-gate 문자열 포함)
    │            → dispatch: logger.info("threadless launch (synthetic) ...")  # F-4
    ↓
launch 성공 → dequeue + in_flight lock + board in_progress
```

비합성(실) directive 경로: `is_synthetic=False` → `allow_empty_thread=False`(기본) → pending 부재 시 **ValueError 그대로** → 기존 재시도/드롭 가드 무변경.

### 5-5) 변경 표면 + 일관성

- `tools/agent/tools_queue.py`:
  - 상수 `SYNTHETIC_DIRECTIVE_PREFIXES = ("rev-pr-", "pr-review-")` 신설. `enqueue_rev_for_pr_if_any` 의 `rev-pr-` 하드코딩 + `_ensure_pr_xrefs`(`subagent_runner.py`)의 `directive_id.startswith("rev-pr-")` 와 의미 일치 — 향후 prefix 추가 시 단일 상수로 수렴(중복 하드코딩 정리는 구현 PR 재량).
  - `dispatch_once`: `is_synthetic = directive_id.startswith(SYNTHETIC_DIRECTIVE_PREFIXES)` 계산 → `launch_subagent(..., allow_empty_thread=is_synthetic)` → threadless 진입 시 F-4 info 로그.
- `tools/agent/tools_subagent.py`:
  - `launch_subagent(..., allow_empty_thread: bool = False)` 추가. `pending_thread_id` 부재 분기: `if not pending_thread_id and not allow_empty_thread: raise ValueError(...)`. 허용 시 wrapper argv 에서 `--pending-thread-id` 를 빈 값으로 전달(또는 해당 인자 페어 생략 — wrapper 가 부재를 placeholder fallback 으로 처리하는지 §7 검증).

### 5-6) DB 마이그레이션
해당 없음 (인프라 오케스트레이션 코드).

### 5-7) 프론트엔드 화면
해당 없음.

## 6) 작업 분할 (예상 PR 리스트)

- [ ] **PR A (be/infra)**: `launch_subagent` 에 `allow_empty_thread` 인자 + 빈 thread 분기 (F-1/F-2). 단위 테스트 — `allow_empty_thread=True` + pending 부재 → launch 진행(wrapper mock), `allow_empty_thread=False`(기본) + pending 부재 → ValueError 보존.
- [ ] **PR B (be/infra)**: `tools_queue.dispatch_once` 의 `SYNTHETIC_DIRECTIVE_PREFIXES` 상수 + 합성 판정 + `allow_empty_thread` 전달 + threadless info 로그 (F-3/F-4/F-5). 단위 테스트 — 합성 directive thread 부재 → 드롭 안 되고 launch + dequeue, 실 directive thread 부재 → 기존 재시도/드롭 가드 유지.
- [ ] **PR C (infra/docs, 선택)**: `_ensure_pr_xrefs`(`subagent_runner.py`) 등 `"rev-pr-"` 하드코딩을 `SYNTHETIC_DIRECTIVE_PREFIXES` 로 수렴(중복 제거). 회귀 위험 검토 후 별도 — 본 race fix 의 필수 아님.

> PR A·B 는 wrapper 의 빈 pending-thread-id 처리(`cycle-forum-placeholder-guard.md`)가 전제. §7 Q1 결과에 따라 PR A 가 wrapper 변경에 의존할 수 있음 — 의존 확인 후 순서 확정.

### 보호 영역 변경 여부 (필수 명시)

- 보호 영역 변경 여부: ☑ 없음 — `tools/agent/*.py` 오케스트레이션 코드 + 테스트. `.github/workflows`·DB·application*.yml·build 파일 무관. 단, 모든 type:* PR 은 rev 사이클 통과 의무 (CLAUDE.md §4) — 본 spec 단계는 docs only.

## 7) 테스트 전략

- **단위**:
  - `test_tools_subagent.py` — `allow_empty_thread=True` + pending 부재 → wrapper mock 호출 + 성공 반환 (사일런스 ValueError 없음). `allow_empty_thread=False` + pending 부재 → `ValueError` raise (legacy 가드 회귀 가드).
  - `test_tools_queue.py` — 합성 directive(`rev-pr-99`) + `cycle_thread_id`/`thread_id` 모두 부재 → `dispatch_once` 가 launch 성공(mock) + dequeue + `launch_attempts` 미증가. 실 directive(`1510...`) + thread 부재 → 기존대로 launch_attempts 증가 → 3회 후 dequeue + ❌ 코멘트.
  - thread 신설 성공한 합성 directive → 기존대로 `cycle_thread_id` 를 pending 으로 사용 (가시성 회귀 0 가드).
- **통합(배포 후)**: rev forum 채널 일시 차단(env 비우기 등)을 흉내내 cycle thread 신설 None 강제 → `rev-pr-N` 합성 directive 가 드롭 없이 launch + rev sub-agent 가 PR 코멘트로 rev-gate 문자열 보고하는지 journal + PR 코멘트로 확인.
- **회귀 가드**: 실 directive 의 `pending_thread_id` 누락 사고가 여전히 ValueError 로 catch 됨을 명시 단위 테스트로 박제 (legacy 사고 재현 방지).
- **e2e**: 인프라 오케스트레이션 코드라 rev 단계 1(코드 review) + 단계 2(dev helper turn 1회) 로 충분. 단계 3(production) 폐기 — `rev-e2e-2-stages.md §1-1`.

## 8) 오픈 질문

| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | wrapper(`agent-launch-wrapper.sh`)가 빈 `--pending-thread-id` 를 placeholder DIGEST fallback 으로 graceful 처리하는가 (현행), 아니면 PR A 가 wrapper 변경에 의존하는가 | (a) 현행 graceful — launch_subagent 만 변경 / (b) wrapper 도 빈 인자 분기 추가 필요 → `cycle-forum-placeholder-guard.md §6 PR F` 와 합본 | @goohong / PR A 구현 시 wrapper 코드 확인 |
| Q2 | `pr-review-<N>` 를 `SYNTHETIC_DIRECTIVE_PREFIXES` 에 포함할 실익 (현재 dispatch 안 됨 — forum 가시화 전용) | (a) 포함 — 향후 dispatch 대상 확장 대비 + 의미 일관 / (b) 제외 — 실제 dispatch 되는 `rev-pr-` 만 | @goohong / PR B 구현 시 |
| Q3 | threadless launch info 로그를 cron digest 에 24h 누적 수치로 노출할지 (`🧵 threadless launch: N건`) | (a) 노출 — thread 신설 신뢰성 저하 조기 detect / (b) journal info 만 — noise 회피 | @goohong / 다음 plan 사이클 |
| Q4 | `_create_cycle_thread` 신설 실패율이 높으면 best-effort 유지로 충분한가, 재시도/신뢰성 개선 별도 spec 필요한가 | (a) 본 fallback 으로 충분 (가시성은 PR 코멘트로 보장) / (b) 신설 신뢰성 별도 spec | @goohong / 운영 메트릭 관찰 후 |

## 9) 결정 로그

- 2026-06-03: 초안 작성 (status=draft). evidence — `tools_queue.enqueue_rev_for_pr_if_any` 가 `rev-pr-N` 합성 directive 를 `thread_id=None` 으로 생성(`tools_queue.py:214-218`) + `enqueue_directive` 의 `_create_cycle_thread` best-effort(`tools_queue.py:135-142`) + `launch_subagent` 의 `pending_thread_id` 부재 ValueError(`tools_subagent.py:78-83`) + `dispatch_once` 의 generic except → launch_attempts 증가 → MAX 3 초과 dequeue(`tools_queue.py:303-325`). root cause = 합성 directive 가 dialogue thread fallback 이 없어 cycle thread 신설 race 시 launch 가 막히고 드롭됨. 3개 옵션(빈 thread 허용 / dispatch 전 동기 확정 / launch 시 inline 생성) 회귀 분석 결과 옵션 (a) `ThreadlessReportFallback` 채택 — 실 directive ValueError 가드 보존 + wrapper placeholder DIGEST fallback 과 dovetail + 변경 표면 최소(좁은 조건 분기 2곳) + 옵션 (b) 결합도↑/(c) 순환 import 기각. `06-domain-model.md §4-2` 에 `SyntheticDirective` / `ThreadlessReportFallback` 등재.
