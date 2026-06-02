"""GlobalState — 계층 hierarchical state machine (옵션 B).

state = current snapshot. event log (events.py) 가 audit trail.
state 변경 시 두 path 동시 update:
  1. agent_state 테이블 (current snapshot, read O(1))
  2. events 테이블 (append-only log, audit / replay)

agent 의 모든 tool 이 state 를 read 해 결정 + transition event emit.

핵심 사고 path code 차단:
  - paused=True → launch_subagent 함수가 raise PausedError → nmae LLM 결정 자체 폐기
  - directives[id].status = "pending_polish" → bot.py polish loop 가 처리, polish 누락 시
    state 검사로 즉시 추적
  - in_flight_agents → 같은 cycle 중복 launch 방지 (워크트리 lock 사고 path)

spec: tools/agent/README.md (Phase 1.2).
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
from typing import Literal


CycleStatus = Literal["idle", "running", "waiting_for_pr_merge", "failed"]
DirectiveStatus = Literal[
    "pending_polish",  # 📌 등록 직후, polish 대기
    "polished",        # bot.py polish loop 완료, nmae 가 분배 가능
    "assigned",        # nmae 가 cycle 에 위임 + sub-agent launch 됨
    "completed",       # PR 머지 완료
    "closed",          # 사용자 정정 / cleanup 으로 종결
]
CycleName = Literal["be", "fe", "rev", "plan", "infra"]


@dataclass
class CycleState:
    """단일 cycle (be/fe/rev/plan) 의 current state."""
    status: CycleStatus = "idle"
    current_thread_id: str | None = None  # cycle forum thread (1 task = 1 thread)
    current_pr_url: str | None = None
    current_directive_id: str | None = None
    last_activity_at: datetime | None = None


@dataclass
class DirectiveState:
    """단일 directive 의 current state."""
    directive_id: str
    summary: str
    status: DirectiveStatus = "pending_polish"
    thread_id: str | None = None  # directive forum thread
    assigned_cycle: CycleName | None = None
    delegation_reason: str | None = None  # plan 위임 시 의무 (legacy 룰 일치)
    pr_url: str | None = None
    closed_reason: str | None = None
    created_at: datetime | None = None
    last_updated_at: datetime | None = None


@dataclass
class GlobalState:
    """전체 시스템의 state snapshot."""
    paused: bool = False  # 사용자 사이클 정지 명령 — launch_subagent 가 검사
    cycles: dict[CycleName, CycleState] = field(default_factory=dict)
    directives: dict[str, DirectiveState] = field(default_factory=dict)
    in_flight_agents: set[CycleName] = field(default_factory=set)

    @classmethod
    def initial(cls) -> GlobalState:
        """Phase 3 NCP 배포 시 초기 state."""
        return cls(
            paused=False,
            cycles={
                "be": CycleState(),
                "fe": CycleState(),
                "rev": CycleState(),
                "plan": CycleState(),
            },
            directives={},
            in_flight_agents=set(),
        )


class PausedError(RuntimeError):
    """사이클 정지 모드에서 launch 시도 시 raise."""


class CycleAlreadyRunningError(RuntimeError):
    """같은 cycle 중복 launch 시도 시 raise (워크트리 lock)."""
