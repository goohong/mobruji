"""work-queue orchestration — enqueue (적재 + board + 지시 thread 댓글) + dispatch
(사이클 idle 시 다음 항목 launch). (#1388)

`work_queue.py` = pure 큐 ops. 본 모듈 = 큐 ↔ directive-board(state) ↔ Discord
댓글 ↔ launch_subagent 연결. agent_loop 가 매 tick `dispatch_once` 호출.

설계: docs/features/directive-work-queue.md
"""

from __future__ import annotations

import logging
from datetime import datetime, timedelta, timezone
from typing import Any

import events as ev
import tools_cycle as tc
import tools_discord as td
import tools_subagent as ts
import work_queue as wq
from state import CycleAlreadyRunningError, PausedError

logger = logging.getLogger("agent.work_queue")

IN_FLIGHT_STARTED_KEY = "in_flight_started"
STALE_THRESHOLD = timedelta(hours=2)
CYCLES = ("be", "fe", "rev", "plan")


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def _age_exceeds(ts_iso: str, threshold: timedelta, now: datetime) -> bool:
    try:
        started = datetime.fromisoformat(ts_iso)
    except (ValueError, TypeError):
        return False
    return (now - started) > threshold


def _comment(thread_id: str, body: str) -> None:
    """지시 thread 댓글 — graceful (실패해도 흐름 차단 X)."""
    if not thread_id:
        return
    try:
        td.forum_comment(thread_id, body)
    except Exception as exc:  # noqa: BLE001
        logger.warning("work-queue forum_comment 실패 thread=%s exc=%r", thread_id, exc)


def _set_board_assigned(directive_id: str, cycle: str, reason: str) -> None:
    """directive-board status → assigned(+cycle). graceful (state 없으면 skip)."""
    try:
        tc.update_directive_status(
            directive_id, "assigned",
            assigned_cycle=cycle, delegation_reason=reason or f"{cycle} 위임",
        )
    except Exception as exc:  # noqa: BLE001
        logger.warning(
            "work-queue board 갱신 실패 directive=%s cycle=%s exc=%r",
            directive_id, cycle, exc,
        )


def enqueue_directive(
    cycle: str,
    directive_id: str,
    title: str,
    task: str,
    *,
    thread_id: str = "",
    priority: int = wq.PRIORITY_NORMAL,
) -> dict[str, Any]:
    """적재 + board assigned + 지시 thread 댓글. 멱등 (중복 directive_id no-op)."""
    result = wq.enqueue(
        cycle, directive_id, title, task,
        enqueued_at=_now_iso(), thread_id=thread_id, priority=priority,
    )
    if not result["enqueued"]:
        return result  # 이미 큐/처리 중 — no-op
    _set_board_assigned(directive_id, cycle, task)
    ev.append_event(
        "work_enqueued",
        {"cycle": cycle, "directive_id": directive_id,
         "position": result["position"], "priority": priority},
    )
    urgent = " 🔴" if priority >= wq.PRIORITY_URGENT else ""
    _comment(
        thread_id,
        f"📥 **{cycle}**{urgent} 큐 {result['position']}번째로 적재했습니다 "
        f"(앞 대기 {result['ahead']}건). 사이클이 비면 자동으로 시작합니다.",
    )
    return result


def dispatch_once(*, now: datetime | None = None) -> list[dict[str, Any]]:
    """각 cycle: stale in_flight 회복 + idle 면 큐 다음 항목 launch.

    agent_loop tick 마다 호출. 반환 = 이번 tick 에 launch 된 항목 list (로그용).
    """
    launched: list[dict[str, Any]] = []
    if ev.get_state("paused") is True:
        return launched
    now = now or datetime.now(timezone.utc)
    started: dict[str, str] = ev.get_state(IN_FLIGHT_STARTED_KEY) or {}

    for cycle in CYCLES:
        in_flight = set(ev.get_state("in_flight_agents") or [])

        # stale in_flight 회복 (#1388 P2) — 완료 event 누락으로 lock 영구 점유 차단.
        if cycle in in_flight:
            ts_iso = started.get(cycle)
            if ts_iso and _age_exceeds(ts_iso, STALE_THRESHOLD, now):
                logger.warning(
                    "work-queue stale in_flight 회복: cycle=%s started=%s (>%s)",
                    cycle, ts_iso, STALE_THRESHOLD,
                )
                ts.mark_subagent_completed(cycle)
                started.pop(cycle, None)
                ev.set_state(IN_FLIGHT_STARTED_KEY, started)
            else:
                continue  # busy — 다음 cycle

        nxt = wq.peek_next(cycle)
        if nxt is None:
            continue

        directive_id = nxt["directive_id"]
        try:
            ts.launch_subagent(cycle, directive_id, nxt["title"], nxt["task"])
        except CycleAlreadyRunningError:
            continue  # race — 다음 tick 재시도
        except PausedError:
            break  # 전역 정지 — 이번 tick 중단
        except Exception as exc:  # noqa: BLE001
            logger.warning(
                "work-queue launch 실패 cycle=%s directive=%s exc=%r — 큐 유지(재시도)",
                cycle, directive_id, exc,
            )
            _comment(
                nxt.get("thread_id", ""),
                f"❌ **{cycle}** launch 실패 — 큐에 유지하고 다음 tick 에 재시도합니다 "
                f"(사유: {str(exc)[:120]}).",
            )
            continue

        # 성공 — 큐에서 제거 + 시작시각 기록 + board 갱신 + 댓글.
        wq.dequeue(cycle, directive_id)
        started[cycle] = _now_iso()
        ev.set_state(IN_FLIGHT_STARTED_KEY, started)
        ev.append_event(
            "work_dispatched", {"cycle": cycle, "directive_id": directive_id},
        )
        _set_board_assigned(directive_id, cycle, nxt.get("task", ""))
        _comment(
            nxt.get("thread_id", ""),
            f"🚀 **{cycle}** 사이클이 시작했습니다 — {nxt.get('title', '')}",
        )
        launched.append({"cycle": cycle, "directive_id": directive_id})

    return launched
