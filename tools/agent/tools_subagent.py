"""Subagent launch tool — be/fe/rev/plan 사이클 launch.

설계 원칙 (학습 의존 폐기, 옵션 1 SDK subagent primitive 활용):

1. **directive_id 인자 강제** — legacy 사고 (pending_thread_id 인자 누락) 의 root
   cause fix. 함수가 state.directives[directive_id].thread_id 자동 조회.
2. **paused check** — state.paused=True 면 PausedError raise. nmae LLM 의 사이클
   정지 명령 무시 사고 path 차단 (code 강제).
3. **워크트리 lock** — state.in_flight_agents 에 cycle 이 이미 있으면
   CycleAlreadyRunningError raise. 중복 launch 방지.

실제 sub-agent 실행 = legacy agent-launch-wrapper.sh 호출 (Phase 1.3 minimum) →
Phase 2+ 에서 SDK subagent primitive 로 점진 migration. legacy infrastructure 재
사용으로 Phase 1 작업량 ↓.

spec: tools/agent/README.md (Phase 1.3).
"""

from __future__ import annotations

import subprocess
from pathlib import Path
from typing import Any

import events as ev
from state import CycleName, PausedError, CycleAlreadyRunningError


WRAPPER_PATH = Path("/home/mobruji/mobruji/tools/agent-launch-wrapper.sh")


# ─── 6. launch_subagent ──────────────────────────────────────────────────────


def launch_subagent(
    cycle: CycleName,
    directive_id: str,
    title: str,
    task: str,
    *,
    wrapper_timeout: float = 60.0,
) -> dict[str, Any]:
    """sub-agent (be/fe/rev/plan) 사이클 시작.

    Args:
        cycle: be / fe / rev / plan
        directive_id: 부모 directive 의 id (pending_thread_id 자동 조회용)
        title: cycle forum thread title
        task: 작업 한 줄 요약

    Raises:
        PausedError: 사이클 정지 모드 (사용자 명령)
        CycleAlreadyRunningError: 같은 cycle 이미 in-flight
        ValueError: directive 미존재 또는 pending thread 미등록
    """
    # 1. paused check — nmae LLM 결정 자체 폐기 (code 강제)
    if ev.get_state("paused") is True:
        raise PausedError(
            f"사이클 정지 모드 — launch_subagent({cycle}) reject "
            f"(directive_id={directive_id})"
        )

    # 2. 워크트리 lock check
    in_flight: list[str] = ev.get_state("in_flight_agents") or []
    if cycle in in_flight:
        raise CycleAlreadyRunningError(
            f"cycle {cycle} 가 이미 running ({in_flight=}) — 중복 launch reject"
        )

    # 3. directive + pending thread 조회
    directive = ev.get_state(f"directive:{directive_id}")
    if directive is None:
        raise ValueError(f"directive not found: {directive_id}")
    pending_thread_id = directive.get("thread_id")
    if not pending_thread_id:
        raise ValueError(
            f"directive {directive_id} 의 pending thread 미등록 — "
            f"register_directive_pending 먼저 호출 필요"
        )

    # 4. wrapper subprocess (legacy infrastructure 재사용, Phase 2+ 에서 SDK 로 migration)
    if not WRAPPER_PATH.exists():
        raise FileNotFoundError(f"wrapper not found: {WRAPPER_PATH}")

    result = subprocess.run(  # noqa: S603 — explicit path
        [
            "bash",
            str(WRAPPER_PATH),
            cycle,
            "--title", title,
            "--task", task,
            "--directive-id", directive_id,
            "--pending-thread-id", pending_thread_id,
        ],
        capture_output=True,
        text=True,
        timeout=wrapper_timeout,
        check=False,
    )

    if result.returncode != 0:
        # wrapper 실패 시 lock 안 잡고 event emit
        ev.append_event(
            "subagent_launched",
            {
                "cycle": cycle,
                "directive_id": directive_id,
                "title": title,
                "task": task,
                "wrapper_rc": result.returncode,
                "wrapper_stderr": result.stderr[:500],
                "failed": True,
            },
        )
        raise RuntimeError(
            f"agent-launch-wrapper 실패 (rc={result.returncode}): {result.stderr[:200]}"
        )

    # 5. 성공 — lock 잡기 + event emit
    in_flight.append(cycle)
    ev.set_state("in_flight_agents", in_flight)
    event_id = ev.append_event(
        "subagent_launched",
        {
            "cycle": cycle,
            "directive_id": directive_id,
            "title": title,
            "task": task,
            "pending_thread_id": pending_thread_id,
            "wrapper_stdout": result.stdout[:500],
        },
    )

    return {
        "event_id": event_id,
        "cycle": cycle,
        "directive_id": directive_id,
        "pending_thread_id": pending_thread_id,
    }


# ─── helper — subagent 완료 표시 (외부 호출 — PR 머지 webhook 등에서) ─────


def mark_subagent_completed(cycle: CycleName) -> None:
    """in_flight_agents 에서 cycle 제거. PR 머지 등 외부 trigger 시 호출."""
    in_flight: list[str] = ev.get_state("in_flight_agents") or []
    if cycle in in_flight:
        in_flight.remove(cycle)
        ev.set_state("in_flight_agents", in_flight)
        ev.append_event("subagent_completed", {"cycle": cycle})
