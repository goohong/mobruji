---
feature: Directive work-queue — 사이클별 우선순위 큐 + dispatcher (즉시 launch 폐지)
slug: directive-work-queue
status: approved
owner: @mobruji-maestro
scope: infra
related_issues: [1388]
related_prs: []
last_reviewed: 2026-05-31
---

# Directive work-queue — 사이클별 우선순위 큐 + dispatcher

## 1) 개요 (What / Why)

기존 `handle_directive_approved` 는 nmae LLM 이 곧장 `launch_subagent` 를 호출했다. 대상 사이클이 busy(`in_flight_agents`)면 `CycleAlreadyRunningError` 가 SDK query 안에서 **조용히 삼켜져** 아무 지표도 안 남았다 (directive-board 미갱신, 지시 thread 댓글 0, 백로그 0).

**사용자 정정 (2026-05-31)**: "사이클이 있건 말건 바로 직접 subagent 를 launch 하는 게 아니라, queue 에 쌓아야 해. 그래야 동시에 작업들을 효율적으로 나열하고 처리하지."

→ 위임을 **즉시 실행이 아니라 큐 적재 + dispatcher 처리** 로 전환. 큐 자체가 가시 지표가 되고, busy 사이클은 실패가 아니라 대기열에서 순서를 기다린다.

## 2) 설계 결정 (사용자 확정)

- **사이클별 우선순위 FIFO 큐 4개** (be / fe / rev / plan). 사이클 간 동시 실행 (워크트리 lock = 사이클당 1 sub-agent 유지).
- **🔴 시급 우선순위 지원** — 같은 사이클 큐 안에서 priority 높은 항목이 앞으로. 동률은 FIFO(enqueued_at).

## 3) 데이터 모델

agent state key `work_queue` = `{ "<cycle>": [ item, ... ] }`.

item:
```json
{
  "directive_id": "1510...",
  "title": "rev 브라우저 기반 QA 환경 도입",
  "task": "한 줄 작업 요약",
  "thread_id": "1510...",          // 지시 forum thread (댓글 대상)
  "priority": 0,                    // 높을수록 우선 (🔴 = 10, 기본 0)
  "enqueued_at": "ISO8601",
  "status": "queued"                // queued | dispatched
}
```

stale 가드용: `in_flight_started` = `{ "<cycle>": "ISO8601" }` (launch 시각).

## 4) 흐름

### 4-1) 적재 (enqueue) — 즉시 launch 폐지
1. `handle_directive_approved` payload 의 directive 를 agent state (`directive:{id}`) 에 mirror (없으면 — bot 측 등록분 호환, 옛 "directive not found" 사고 동시 fix).
2. SDK query 프롬프트가 cycle 결정 + `enqueue_work` tool 호출 (launch_subagent **직접 호출 금지**). priority 는 description 의 🔴/시급 표현 보고 결정.
3. `enqueue_work` (멱등 — 같은 directive_id 중복 적재 no-op):
   - `work_queue[cycle]` 에 item append.
   - `update_directive_status(directive_id, "queued", assigned_cycle=cycle)` → board status `큐 대기`.
   - 지시 thread 에 forum_comment: `📥 {cycle} 큐 {position}번째로 적재했습니다 (앞 대기 {N}건).`

### 4-2) dispatch — dispatcher 루프
`agent_loop` 안 주기 tick (`dispatch_interval`, 기본 5s):
- 각 cycle 별:
  - **stale 가드**: cycle 이 `in_flight` 인데 `in_flight_started[cycle]` 가 `STALE_THRESHOLD`(기본 2h) 초과 → `mark_subagent_completed(cycle)` 로 lock 회복 + 경고 로그.
  - cycle 이 `paused` 거나 `in_flight` 면 skip.
  - 큐 비었으면 skip.
  - `peek_next(cycle)` (priority desc → enqueued_at asc) → `launch_subagent(...)`:
    - 성공: 큐에서 dequeue + `in_flight_started[cycle]=now` + `update_directive_status("진행 중")` + thread 댓글 `🚀 {cycle} 시작했습니다.`
    - 실패(wrapper rc≠0 등): thread 댓글 `❌ launch 실패 — 큐에 유지, 재시도합니다 (사유: …).` (큐 유지 → 다음 tick 재시도, N회 초과 시 사용자 알림).

### 4-3) 완료
`subagent_completed` (PR 머지 webhook 등) → `mark_subagent_completed(cycle)` → lock 해제 → 다음 tick 이 큐 다음 항목 launch.

## 5) 구현 범위

- 신규 `tools/agent/work_queue.py` — pure 큐 ops (path 주입 가능, 단위 테스트). `enqueue` / `peek_next` / `dequeue` / `position` / `snapshot`.
- `tools/agent/events.py` — EventKind 에 `work_enqueued` / `work_dispatched` 추가.
- 신규 MCP tool `enqueue_work` (tool_definitions.py + agent.py `_get_allowed_tools` + 이모지 + dispatch).
- `tools/agent/agent.py` — `handle_directive_approved` 프롬프트를 enqueue_work 기반으로 + directive state mirror + dispatcher 루프 (`dispatch_work_queue`) + stale 가드.
- 단위 테스트: `tools/agent/tests/test_work_queue.py` (우선순위/FIFO/멱등/position/stale).

## 6) 비목표 (이번 범위 밖)
- 큐 영속 cross-restart (state sqlite 에 이미 보존되므로 기본 충족 — 별도 검토 불요).
- 사이클 내 병렬 2+ sub-agent (워크트리 lock 유지 — 사이클당 1).
- cycle-status.json ↔ in_flight_agents 일원화 (P2 stale 의 더 깊은 근본 — 별도 이슈).

## 7) 검증
- 단위: test_work_queue.py (우선순위 정렬, 멱등, position, stale 임계).
- 통합(배포 후): 사이클 busy 상태에서 directive 2건 적재 → 둘 다 큐에 쌓이고 지시 thread 에 "큐 N번째" 댓글 → 앞 작업 완료 시 dispatcher 가 다음 항목 자동 launch.
