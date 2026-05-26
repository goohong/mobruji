---
status: draft
date: 2026-05-26
deciders: plan sub-agent (회고 ADR — Phase 3 완료 후 본문 완성 예정)
related_issues: [1109]
related_prs: []
supersedes: []
superseded_by: []
---

# ADR-0022: work-cycle-simplification 회고 (stub — Phase 3 완료 후 본문)

## Context

본 ADR 은 `docs/features/work-cycle-simplification.md` (이슈 #1109) 의 회고 자리. 동 spec 의 3 phase 마이그 완료 후 측정값 + 7 항목 + 5 hook 박제 결과 + ADR-0019 트랙 A (메모리 → 코드 강제) 의 누적 효과 분석.

본 stub 은 **자리 박제** 만 — Phase 3.4 (회고 PR) 에서 본문 완성. Phase 별 metric (digest cron / response_watch_loop / channel grep 호출 회수) 누적 후 평가.

## Decision

(stub — Phase 3 완료 후 결정 본문 작성)

후보 measurement 항목 (`docs/features/work-cycle-simplification.md §4-6 metric 표` 참조):
- 후속/회귀/drift commit 비율 변화 (baseline 32% → 목표 < 15%)
- 메모리 67 → 50 건 목표 달성 여부 (ADR-0019 + 본 spec 합산)
- sub-agent 평균 컨텍스트 줄 수 변화
- 사용자 메시지 → 본답 latency p95 변화 (Hook 4)
- cycle channel 메시지 / sub-agent launch 비율 (Hook 1, 2)
- 사용자 정정 빈도 — "같은 룰 두 번 정정" 패턴 감소 측정

## Alternatives Considered

(stub)

## Consequences

(stub)

## References

- 이슈 #1109 — 본 ADR + work-cycle-simplification 통합 spec
- `docs/features/work-cycle-simplification.md` — 본 ADR 의 동반 spec
- ADR-0019 (`docs/decisions/0019-event-driven-architecture-v2.md`) — 본 ADR 의 부모

## 변경 이력
- 2026-05-26 — stub 생성 (회고 자리 박제, plan sub-agent, 이슈 #1109 Phase 1 사이클). 본문은 Phase 3 (3.4) PR 에서 완성.
