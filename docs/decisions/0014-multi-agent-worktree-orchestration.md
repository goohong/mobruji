---
id: 0014
title: 4 sub-agent 워크트리 + maestro 오케스트레이션 패턴
status: accepted
date: 2026-05-23
deciders: [@goohong]
---

# 0014. 4 sub-agent 워크트리 + maestro 오케스트레이션 패턴

## Context
초기 다중 AI 운영 모델(ADR 외 `02-agent-workflow.md §10`)은 Claude + Codex 2 에이전트가 같은 레포에서 영역/역할 분담하는 형태였다. 하지만 v0.2 메타 전환과 NCP 24/7 운영을 거치며 실 운영 형태는 다르게 진화했다: Codex 미사용, Claude 단일 모델로 **maestro 1 세션 + 워크트리 분리 sub-agent 4 세션**(be/fe/rev/plan) 패턴이 자리잡았다. 결정이 여러 문서(`§10`, `§11`, 메모리)에 흩어져 있어 영속 ADR로 고정할 필요가 생겼고, 결번 ADR-0014 슬롯이 이 용도에 적합했다.

## Decision
다중 AI 운영의 default를 다음으로 고정한다:

1. **5 워크트리 구조**: maestro(`mobruji`) 1개 + sub-agent 워크트리 4개(`mobruji-be`, `mobruji-fe`, `mobruji-rev`, `mobruji-plan`). sub-agent 워크트리는 모두 detached HEAD로 둔다 (develop은 maestro 점유).
2. **단일 오케스트레이터**: maestro Claude 세션 하나가 `Agent` 도구로 sub-agent를 background 가동한다(`run_in_background: true`). 사용자는 maestro 한 곳에서 진행 상황을 따라간다.
3. **항시 가동 룰**: maestro은 be/fe/rev/plan 4 워크트리에 sub-agent 1개씩 가동을 **항상 유지**한다. 1개 완료 통지가 들어오면 같은 워크트리에 즉시 다음 백로그를 launch한다 (워크트리 lock: 동시 2 sub-agent 금지).
4. **본진 작업 default = 메타**: maestro 본진은 spec/ADR/메모리/orchestration만 default. 코드/테스트/문서 본문 작성은 sub-agent 위임 (본진이 직접 하면 4 워크트리 중 하나가 idle).
5. **공통 prompt 룰**: sub-agent 공통 룰은 `docs/ai-harness/12-sub-agent-prompt-template.md`. 역할별 prompt에 한 줄 reference만.

## Consequences

### 긍정적
- 4 도메인 병렬로 wall-clock 단축. 본진이 직접 코드 작성하던 시점 대비 사이클 throughput 증가.
- 워크트리 분리로 작업 영역(`backend/**` vs `web/**` vs `docs/**`) 충돌 자연스럽게 회피.
- maestro 본진 컨텍스트가 메타(기획/ADR/리뷰 결정)에 집중되어 단일 세션 컨텍스트 폭증 완화.
- 사용자는 maestro 한 자리에서만 결정 → 컨텍스트 스위칭 비용 감소.
- 자율 사이클: 사용자 부재 시에도 maestro이 완료 통지 → 다음 사이클 launch 루프를 진행, release 머지만 사용자 확인.

### 부정적
- maestro 세션이 닫히면 background sub-agent도 모두 종료 → 사이클 정지. Discord webhook 모니터링이 부분 보완 (`docs/ai-harness/14-discord-notify-setup.md`).
- 4 워크트리 메모리 race 위험 (`MEMORY.md` symlink 공유 시). §11 §1-2 회피책 적용 (1인이 한 번에 1세션과만 대화).
- 본진 자체가 멀티태스킹 부하 (turn당 1~2 도구 호출 단위로 쪼개야 통지 처리 지연 최소화).
- sub-agent launch 누락 시 비용이 큼 (사용자 명시 강조: "절대로", `feedback-sub-agent-launch-mandatory`).

## Alternatives (considered)

- **(A) Claude + Codex 2 에이전트 영역 분담 (초기 §10 가정)** — Codex 도입 실현 안 됨 + 워크트리 분리가 더 유연한 영역 격리 제공. 폐기.
- **(B) maestro 단일 세션이 모든 작업 직접 처리** — 컨텍스트 폭증 + 병렬 부재 + 사용자 의도(도메인 분리) 위반. 본 ADR이 명시적으로 금지.
- **(C) 사용자가 워크트리마다 별 Claude 인스턴스를 띄움 (수동 터미널 모드)** — `§11 §0-2`에 대체 모드로 보존. 서브에이전트가 의도와 다르게 동작하거나 maestro 부담이 클 때만 임시 사용. default 아님.
- **(D) 4 sub-agent 외 plan 세션 미도입 (3 워크트리)** — be/fe/rev 3 워크트리 + maestro가 ADR/spec 작성. 메타 작업이 본진 부담을 폭증시켜 plan 세션 도입으로 분리됨(`project-plan-session-active`, 2026-05-21).

## References
- `docs/ai-harness/02-agent-workflow.md §10` — 다중 AI 운영 룰 (본 ADR의 정책 표현)
- `docs/ai-harness/11-multi-session-runbook.md` — 셋업·운영 런북 (본 ADR의 실행 형태)
  - §0-1 maestro 오케스트레이션 모드
  - §0-10 항시 4 워크트리 가동 룰
  - §1-1 워크트리 5개 생성
  - §2 세션별 역할
- `docs/ai-harness/12-sub-agent-prompt-template.md` — sub-agent 공통 prompt 룰
- 메모리: `feedback-orchestration-pattern`, `feedback-keep-4-cycles-active`, `feedback-sub-agent-launch-mandatory`, `feedback-worktree-lock`, `project-plan-session-active`, `project-multi-session-setup`
- PR #397, #398 — §11 5워크트리 + 항시 가동 룰 도입
- PR (본 ADR 신설 PR) — §10 동기화 + ADR-0014 결번 채움
