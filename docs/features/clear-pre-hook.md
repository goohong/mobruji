---
feature: /clear pre-hook 강제 (4 액션 자동 수행 + graceful fallback)
slug: clear-pre-hook
status: draft
owner: @mobruji-maestro
scope: infra
related_issues: [1063]
related_prs: []
last_reviewed: 2026-05-24
---

# /clear pre-hook 강제 (4 액션 자동 수행)

## 1) 개요 (What / Why)

- helper 또는 nmae 가 `/clear` 또는 세션 종료 시점에 **4 액션을 자동 수행**하는 코드 hook 을 신설한다.
- 4 액션 = (1) 4-way 문서화 일치 체크 / (2) cycle-status.json sanity / (3) directive forum 상태 갱신 / (4) 핸드오프 메모리 자동 작성.
- 사용자 2026-05-24 강력 inject: "/clear 직전 컨텍스트 lost 자체가 문제. /clear 직전 메모리 핸드오프 + cycle-status sanity + directive forum 상태 갱신 의무 강제 - 코드 hook 으로".
- §13-memory-and-enforcement 패턴 적용: 룰의 **"왜"** 는 메모리 ([[feedback-session-close-doc-check]] + [[feedback-keep-promises]]) 가 담고, **"어떻게"** 는 본 spec 이 정의하는 코드 (script + bot.py 함수 + wrapper integration) 가 강제한다.

### 1-1) 트리거 사고

- CLAUDE.md §15 4-way doc-check 룰이 학습 의존 — 실제 `/clear` 직전 누락 빈도 ↑.
- 직전 사고: 핸드오프 메모리 누락 시 다음 세션 helper 가 컨텍스트 lost (handoff_v3 작성 후 helper-current-target.txt freeze 룰 학습 못 해 잘못된 user msg 에 reply 한 사례).
- 메모리 [[feedback-session-close-doc-check]] 학습 후에도 nmae /clear 직전 cycle-status.json sanity 누락 → 다음 세션에서 watchdog inject 로 복구하는 비효율.

### 1-2) 책임 분리

| 영역 | 채널 | 담당 |
|---|---|---|
| "왜" — 룰 사유 / 사고 인용 | 메모리 ([[feedback-session-close-doc-check]] + [[feedback-keep-promises]]) | 학습 의존 |
| "어떻게" — 4 액션 자동 수행 | 본 spec — `clear-pre-hook.sh` + `pre_clear_enforce()` | 코드 강제 (학습 무관) |
| graceful fallback | hook 실패 시 /clear 차단 X + Discord 알림 + 후속 보강 PR 자동 launch | 코드 강제 |

## 2) 사용자 시나리오

### 2-1) helper 측 시나리오

1. helper 본체가 사용자 메시지 처리 완료 후 `/clear` 명령 받음 (또는 context 95% 도달로 `===CLEAR_READY===` emit).
2. `tools/discord-daemon/clear-pre-hook.sh` 가 bot.py 측 hook 또는 helper-turn-start.sh 의 graceful integration 으로 호출됨.
3. 4 액션 자동 수행:
   - (a) **4-way doc-check** — 메모리 / CLAUDE.md / docs/ai-harness / docs/features drift 감지 (mtime > N hours 기준 신규 메모리 vs CLAUDE.md grep).
   - (b) **cycle-status.json sanity** — 4 워크트리 in_progress 검증 + idle 시 note 필드 확인. future timestamp 검출.
   - (c) **directive forum 상태 갱신** — `directive_board_sync_loop` 5분 주기 대기 대신 즉시 PATCH trigger (HTTP signal 또는 file flag).
   - (d) **핸드오프 메모리 자동 작성** — `project_session_handoff_<date>_v<n>.md` template 으로 직전 세션 요약 (PR/이슈/사이클/사고/오픈 결정 자동 수집).
4. 4 액션 결과를 Discord DIGEST_CHANNEL_ID 에 1줄 push (`[clear-pre-hook] 4 액션 완료 — drift N건 / cycle-status sanity OK / directive sync ✅ / handoff 메모리 작성 v<n>`).
5. 실패 항목 있으면 graceful — /clear 막지 않음. 단 사용자 가시화 push + 후속 보강 PR 자동 launch (drift 보강 / cycle-status 정정 / 누락 메모리 fix).

### 2-2) nmae 측 시나리오

1. nmae 가 자기 turn 마지막에 `/clear` 의도 (context 95% 도달 또는 의도적 정리).
2. 동일 hook 호출 — nmae 측 wrapper (`tools/agent-launch-wrapper.sh` 에 `--pre-clear` flag 추가 또는 별도 `tools/nmae-pre-clear.sh`) 통합.
3. nmae 는 helper 보다 작업 범위가 넓어 (4 워크트리 사이클 + 머지 + 룰 갱신), 핸드오프 메모리 작성이 더 critical. 자동화 효과 ↑.

## 3) 요구사항

### 기능 요구사항

#### 3-1) hook script (`tools/discord-daemon/clear-pre-hook.sh`)

- [ ] **위치**: `tools/discord-daemon/clear-pre-hook.sh` (기존 helper-turn-start.sh 와 인접 — 동일 actor 관리 + bot.py 가 spawn).
- [ ] **호출 방식**: `bash tools/discord-daemon/clear-pre-hook.sh [--actor helper|nmae] [--reason auto-95|manual|session-end]`. graceful — exit 0 보장.
- [ ] **4 액션 순차 실행**:
  - 액션 1 — `run_4way_doc_check`: 메모리 mtime > 1h 신규 파일 grep + CLAUDE.md / docs grep 비교 → drift count 출력.
  - 액션 2 — `run_cycle_status_sanity`: `tools/cycle-status/validate.sh` 호출 + 4 워크트리 in_progress 검증 + future timestamp 검출.
  - 액션 3 — `trigger_directive_sync`: file flag (`~/.mobruji/directive-sync-trigger`) touch 또는 HTTP signal 로 `directive_board_sync_loop` 즉시 한 사이클 강제 실행.
  - 액션 4 — `write_handoff_memory`: `project_session_handoff_<YYYY-MM-DD>_v<auto-increment>.md` 자동 생성. content 자동 수집 (직전 N PR / 이슈 / cycle-status snapshot / directive-board pending).
- [ ] **결과 emit**: stdout 으로 1줄 summary + Discord DIGEST_CHANNEL_ID push (각 액션 ✅/❌ + 다음 액션 권고).
- [ ] **graceful fallback**: 액션 1 실패해도 액션 2-4 진행. 모든 실패는 stderr 로 warn + Discord push 에 포함. exit 0.

#### 3-2) bot.py 측 hook (`pre_clear_enforce()`)

- [ ] **신규 함수**: bot.py 에 `pre_clear_enforce(pane: str, reason: str) -> dict` 추가.
- [ ] **트리거 조건**: 기존 `context_auto_clear_loop` 가 pane log 에서 `===CLEAR_READY===` 또는 사용자 `/clear` 텍스트 감지 시 — `/clear` 송신 **직전** 본 함수 호출.
- [ ] **동작**: subprocess 로 `clear-pre-hook.sh --actor <helper|nmae> --reason <auto-95|manual>` 실행 + 결과 dict 수집 + Discord push.
- [ ] **timeout**: 60초 hard timeout (4 액션 각 15초 평균). timeout 시 partial 결과로 push + /clear 계속 진행.
- [ ] **idempotency**: 같은 세션 안에서 5분 안에 두 번 호출되면 두 번째는 skip (이중 핸드오프 메모리 방지).

#### 3-3) helper-turn-start.sh / nmae wrapper integration

- [ ] **helper-turn-start.sh**: 매 turn 시작 시점이 아니라 helper 본체가 `/clear` 의도 명령 받았을 때 별도 호출 (helper-turn-start.sh 흐름 안에는 박지 않음 — 매 turn 호출되면 비효율).
- [ ] **nmae wrapper**: nmae 측 `tools/agent-launch-wrapper.sh` 또는 nmae 전용 `tools/nmae-pre-clear.sh` 신설. nmae 가 /clear 의도 시 명시 호출.
- [ ] **CLAUDE.md §15 갱신**: 룰의 "어떻게" 라인을 wrapper 호출 1줄로 단순화 — "/clear 직전 `bash tools/discord-daemon/clear-pre-hook.sh --actor <helper|nmae>` 1회 호출 의무" (학습 의존 ↓).

### 비기능 요구사항

- **graceful**: hook 실패 시 /clear 자체 차단 금지 (exit 0). 사용자 turn 흐름 깨지지 않음 — [[feedback-helper-turn-start-graceful]] 패턴 ([[feedback-discord-reply-script]] 와 동일).
- **observability**: 4 액션 각각 stdout `[clear-pre-hook] <action> ✅/❌ — <상세>` 로깅. Discord push 본문에도 포함.
- **timeout 보호**: 4 액션 합산 60초 안에 완료. 초과 시 partial 결과로 진행.
- **idempotency**: 5분 debounce — 같은 세션 두 번 호출 시 두 번째 skip.
- **race safety**: handoff 메모리 작성은 atomic write (mktemp + rename). directive sync trigger 는 단일 file flag (touch).
- **cron digest signature**: 5분 cron digest 에 "마지막 /clear 시점 + 4 액션 완료 여부 + drift 카운트" 1줄 추가.

## 4) 범위 / 비범위

### 포함

- `tools/discord-daemon/clear-pre-hook.sh` 신설 (코드 강제 채널).
- bot.py `pre_clear_enforce()` 함수 + `context_auto_clear_loop` 연결.
- helper-turn-start.sh / nmae wrapper integration (graceful 호출).
- 4 액션 각각의 구현 (4-way doc-check / cycle-status sanity / directive sync trigger / handoff 메모리 작성).
- cron digest signature 1줄 추가 (검증 채널).

### 제외 (Out of Scope)

- **구현 코드 본 PR 안에서 작성 X** — 본 PR 은 spec 만. 구현은 별도 be 사이클 위임 (작업 분할 §6 참조).
- 4-way doc-check 의 drift 자동 보강 PR (현재는 detect 만 + Discord push; 자동 보강 PR launch 는 추후 §3 [[feedback-autonomous-default]] 자율 처리 룰로 처리).
- /clear 자체 차단 (hard gate) — 본 spec 은 graceful 만 다룸. /clear 막는 hard gate 는 사용자 경험 ↓ + race condition 위험.
- 사용자 측 메시지 입력 hook — helper/nmae 본체 측만.

## 5) 설계

### 5-1) 도메인 모델

- 인프라 도메인 (`scope: infra`). 외부 사용자 도메인 무관.
- 4 액션 각각의 dependency:
  - 액션 1 (doc-check) ← `~/.claude/projects/.../memory/` mtime + CLAUDE.md / docs grep
  - 액션 2 (cycle-status) ← `~/.mobruji/cycle-status.json` + `tools/cycle-status/validate.sh`
  - 액션 3 (directive sync) ← `~/.mobruji/directive-board.jsonl` + `directive_board_sync_loop`
  - 액션 4 (handoff 메모리) ← gh PR/이슈 list + cycle-status snapshot + directive-board pending

### 5-2) API 엔드포인트

N/A (script + bot.py 함수만).

### 5-3) 외부 연동

- **gh CLI** — 액션 4 handoff 메모리 자동 생성 시 직전 N PR / 이슈 list 수집.
- **Discord** — DIGEST_CHANNEL_ID push (`discord-reply.sh --status-channel`).
- **tmux** — graceful skip (hook 자체는 tmux 사용 안 함, bot.py 측 wrapper 만 tmux pane 알림).

### 5-4) 데이터 흐름 / 시퀀스

```
[helper / nmae LLM]
   ↓ /clear 의도 (사용자 명령 또는 context 95%)
[bot.py context_auto_clear_loop]
   ↓ ===CLEAR_READY=== 감지
   ↓ pre_clear_enforce(pane, reason)
[clear-pre-hook.sh --actor <helper|nmae>]
   ├ 액션 1: run_4way_doc_check
   │    ├ memory mtime > 1h 신규 grep
   │    ├ CLAUDE.md / docs grep (drift 검출)
   │    └ output: drift_count + drift_files
   ├ 액션 2: run_cycle_status_sanity
   │    ├ tools/cycle-status/validate.sh
   │    ├ 4 워크트리 in_progress 검증
   │    └ output: idle_count + future_timestamp_count
   ├ 액션 3: trigger_directive_sync
   │    ├ touch ~/.mobruji/directive-sync-trigger
   │    └ output: trigger_ok (boolean)
   └ 액션 4: write_handoff_memory
        ├ gh pr list --search 'merged:>=<last_clear_ts>'
        ├ gh issue list --state all --search 'updated:>=<last_clear_ts>'
        ├ cycle-status.json snapshot
        ├ directive-board.jsonl pending count
        └ output: ~/.claude/projects/.../memory/project_session_handoff_<date>_v<n>.md
   ↓ Discord push: [clear-pre-hook] 4 액션 결과 1줄
[bot.py]
   ↓ tmux send-keys "/clear"
[LLM /clear executed]
```

### 5-5) DB 마이그레이션

없음 (파일 기반).

### 5-6) 프론트엔드 화면

없음.

### 5-7) handoff 메모리 template

```markdown
---
name: session-handoff-<date>-v<n>
description: <actor> /clear 직전 세션 핸드오프 — <YYYY-MM-DD HH:MM KST>
metadata:
  actor: <helper|nmae>
  node_type: handoff
  type: session-handoff
  originSessionId: <자동 수집 or "unknown">
---

# <actor> 세션 핸드오프 (<date> v<n>)

## 1) 직전 세션 요약
- 시작 시각: <last_clear_ts or session_start>
- 종료 시각: <now KST>
- 처리한 사용자 메시지: N건 (helper queue done count)
- 처리한 사이클: N건 (cycle-status.json completed delta)

## 2) 머지된 PR (gh pr list --search 'merged:>=<last_clear_ts>')
- PR #<N1> — <title> (<scope>/<type>)
- PR #<N2> — ...

## 3) 신규 이슈 / 오픈 결정
- 이슈 #<N> — <title>
- 오픈 결정: <directive-board.jsonl pending entries>

## 4) cycle-status snapshot
- be: in_progress=<x> / last_completed=<y>
- fe: ...
- rev: ...
- plan: ...

## 5) 다음 세션 인계 사항
- (자동 수집 — directive-board pending + cycle-status idle + drift 보강 후보)

## 6) 검증 결과
- 4-way doc-check: drift N건 (목록 첨부)
- cycle-status sanity: ✅/❌ <상세>
- directive sync trigger: ✅/❌
```

### 5-8) graceful fallback 패턴

본 hook 은 [[feedback-helper-turn-start-graceful]] 와 동일 graceful 패턴 적용:

| 실패 케이스 | 동작 | 결과 |
|---|---|---|
| 액션 1 실패 (memory grep crash) | stderr warn + 다음 액션 진행 | Discord push 에 "❌ doc-check skipped" |
| 액션 2 실패 (cycle-status.json 부재) | stderr warn + 다음 액션 진행 | Discord push 에 "❌ cycle-status skipped" |
| 액션 3 실패 (touch 실패) | stderr warn + 다음 액션 진행 | Discord push 에 "❌ directive sync trigger skipped" |
| 액션 4 실패 (handoff 메모리 write 실패) | stderr warn + exit 0 | Discord push 에 "❌ handoff memory skipped — 사용자 인계 필요" |
| 전체 60초 timeout | partial 결과로 Discord push + exit 0 | LLM /clear 진행 |
| Discord push 실패 | stderr warn + exit 0 | LLM /clear 진행 |

핵심 — **/clear 자체는 절대 막지 않음**. 사용자 경험 우선 + race condition 회피.

### 5-9) cron digest signature 추가

기존 `tools/discord-daemon/bot.py` digest_loop 5분 cron 에 신규 1줄 추가:

```
[clear-pre-hook 마지막 실행]
- 시각: <last_clear_ts>
- 4 액션: doc-check N drift / cycle-status ✅ / directive sync ✅ / handoff v<n>
- actor: <helper|nmae>
```

`~/.mobruji/last-clear-pre-hook.json` 파일에 atomic write 후 cron digest 가 read. 검증 채널 — 사용자가 digest 만 봐도 /clear 직전 sanity 확인 가능.

## 6) 작업 분할 (예상 PR 리스트)

### PR 1: clear-pre-hook.sh script + tests (be 사이클)

- 신설: `tools/discord-daemon/clear-pre-hook.sh`
- 4 액션 각각 함수화 (`run_4way_doc_check` / `run_cycle_status_sanity` / `trigger_directive_sync` / `write_handoff_memory`)
- bats 또는 pytest 회귀 테스트 (`tools/discord-daemon/tests/test_clear_pre_hook.py`)
- graceful fallback 검증 (모든 액션 mock fail 시 exit 0 보장)
- session: `session:backend`

### PR 2: bot.py pre_clear_enforce() 함수 + context_auto_clear_loop 연결 (be 사이클)

- bot.py `pre_clear_enforce(pane, reason) -> dict` 신규 함수
- `context_auto_clear_loop` 의 `/clear` 송신 직전 본 함수 호출
- subprocess 60초 hard timeout
- 5분 debounce (idempotency)
- 회귀 테스트 (`tests/test_pre_clear_enforce.py`)
- session: `session:backend`

### PR 3: helper-turn-start.sh / nmae wrapper integration (be 또는 helper 사이클)

- helper-turn-start.sh 는 graceful 호출만 (매 turn X)
- nmae wrapper (`tools/nmae-pre-clear.sh` 또는 `tools/agent-launch-wrapper.sh --pre-clear` flag)
- CLAUDE.md §15 본문 갱신 — wrapper 호출 1줄 룰
- session: `session:helper` 또는 `session:backend`

### PR 4: cron digest signature 추가 (be 사이클)

- `~/.mobruji/last-clear-pre-hook.json` schema 정의
- digest_loop 본문에 1줄 render 추가
- 회귀 테스트
- session: `session:backend`

### PR 5: 메모리 + CLAUDE.md 갱신 (plan 사이클)

- 메모리 `feedback_clear_pre_hook_enforce.md` 신설 (또는 `feedback-session-close-doc-check` 보강)
- CLAUDE.md §15 본문 — wrapper 호출 의무 명시
- session: `session:plan`

## 7) 테스트 전략

### 7-1) 단위 (PR 1-2)

- 액션 1: memory 파일 mtime mock + grep 결과 검증
- 액션 2: cycle-status.json fixture (idle / future timestamp / valid) 각각 입력 → 출력 검증
- 액션 3: touch 결과 + sync_loop 즉시 trigger 검증
- 액션 4: gh CLI mock + template render 검증

### 7-2) 통합 (PR 2-3)

- bot.py `pre_clear_enforce` 가 subprocess 로 hook 호출 시 60초 timeout 검증
- 5분 debounce 검증
- graceful fallback — 액션 1 mock fail 시도 액션 2-4 진행 + exit 0 검증

### 7-3) E2E (PR 4)

- 실제 helper /clear 시뮬레이션 (test daemon) → hook 호출 → 4 액션 실행 → Discord push 1회 + handoff 메모리 1건 작성 검증
- cron digest signature 1줄 표시 검증

### 7-4) 외부 연동 mock

- gh CLI — fixture JSON response
- Discord webhook — `discord-reply.sh --dry-run` flag 추가 또는 기존 mock 패턴 재사용

## 8) 오픈 질문

| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | 4-way doc-check 의 drift 자동 보강 PR launch 시점 | (a) 본 spec 안에 포함 / (b) 별도 spec (현재) | nmae / 2026-05-25 |
| Q2 | handoff 메모리 v<n> auto-increment 충돌 처리 | (a) timestamp suffix / (b) sha hash / (c) lock file | be / 2026-05-25 |
| Q3 | nmae wrapper 위치 — agent-launch-wrapper.sh 확장 vs 신설 nmae-pre-clear.sh | (a) 확장 / (b) 신설 | nmae / 2026-05-25 |
| Q4 | 5분 debounce 가 사용자 의도적 재 /clear 막을 risk | (a) flag 으로 override 가능 / (b) 그대로 / (c) 1분 단축 | helper / 2026-05-25 |

## 9) 결정 로그

- 2026-05-24: 초안 작성 (status=draft). 사용자 강력 inject + §16 패턴 적용.

## 10) 관련 문서

- `CLAUDE.md §15` — 세션 종료 전 문서화 일치 체크 (룰 본체)
- `docs/ai-harness/13-memory-and-enforcement.md` — 메모리 vs 코드 분리 원칙
- `docs/features/context-auto-clear.md` — context 95% 자율 정리 (본 hook 의 트리거)
- `docs/features/nmae-cycle-watchdog.md` — cycle-status.json sanity 검증 (액션 2 의존)
- `tools/cycle-status/README.md` — `validate.sh` 헬퍼
- `tools/discord-daemon/helper-turn-start.sh` — graceful 호출 패턴 참고
- 메모리 [[feedback-session-close-doc-check]] [[feedback-keep-promises]] [[feedback-autonomous-default]]
