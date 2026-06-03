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

# (#1542) 합성 directive — enqueue 가 자동 생성하며 사용자 dialogue thread 없이
# 전용 cycle forum thread 에만 의존한다. cycle thread 동기 생성(_create_cycle_thread)
# 이 Discord flake 로 None 을 반환하면 pending_thread_id 가 빈 값이 되어 launch 가
# ValueError → dispatch_once 가 재시도(noise) → MAX 초과 시 큐 드롭(PR 리뷰 유실)으로
# 이어졌다. 이 prefix 의 directive 는 infra 와 동일하게 thread 면제 — 보고는 graceful
# degrade(cycle thread 있으면 거기로, 없으면 PR 코멘트). 표준 사이클 동작은 불변.
THREAD_OPTIONAL_DIRECTIVE_PREFIXES = ("rev-pr-", "pr-review-")


# ─── 6. launch_subagent ──────────────────────────────────────────────────────


def launch_subagent(
    cycle: CycleName,
    directive_id: str,
    title: str,
    task: str,
    *,
    cycle_thread_id: str | None = None,
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
    # (#1401) cycle_thread_id 가 주어지면(자율 큐 경로 — 전용 cycle forum thread)
    # 그걸 wrapper 의 pending-thread-id 로 사용 → 📌 dialogue thread 재사용 폐지.
    # 미지정 시 기존대로 directive thread (register_directive_pending 경로 호환).
    pending_thread_id = cycle_thread_id or directive.get("thread_id") or ""
    # (#1531) infra 는 전용 forum 채널이 없어 cycle thread 가 없다 — PR 코멘트로 보고하므로
    # pending thread 요구를 면제. (#1542) 합성 directive(rev-pr-/pr-review-)도 cycle thread
    # 동기 생성 flake 시 thread 면제 — 표준 사이클(register_directive_pending 경로)은 기존대로 필수.
    thread_optional = cycle == "infra" or directive_id.startswith(
        THREAD_OPTIONAL_DIRECTIVE_PREFIXES
    )
    if not pending_thread_id and not thread_optional:
        raise ValueError(
            f"directive {directive_id} 의 pending thread 미등록 — "
            f"register_directive_pending 먼저 호출 필요"
        )

    # 4. wrapper subprocess (legacy infrastructure 재사용, Phase 2+ 에서 SDK 로 migration)
    # (#1531) infra 는 wrapper(set-active/per-cycle 채널 알림 — standing 워크트리/채널
    # 가정)를 건너뛴다. infra 의 실제 실행은 subagent_runner 의 ephemeral 경로가 담당하고,
    # 여기선 lock + event 부기만. (wrapper 가 infra 워크트리·채널 부재로 hard-fail 하는
    # 사고 차단.)
    wrapper_stdout = "(infra: wrapper skipped — ephemeral path)"
    if cycle != "infra":
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
        wrapper_stdout = result.stdout[:500]

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
            "wrapper_stdout": wrapper_stdout,
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
