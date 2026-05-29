"""Cycle / directive state tools (4종) — state.py 의 GlobalState 와 events 통합 update.

read = events.get_state 직접. write = state update + audit event emit (atomic 호출자
책임).

사고 path code 차단:
  - directive_status transitions = 명시 Literal — 미정의 status 거부
  - update_directive_status 가 pr_url / closed_reason 인자 강제 — 옛 사고 (cleanup 시 사유 누락) 차단

spec: tools/agent/README.md (Phase 1.3).
"""

from __future__ import annotations

from typing import Any

import events as ev
from state import CycleName, CycleStatus, DirectiveStatus


# ─── 9. get_cycle_state ──────────────────────────────────────────────────────


def get_cycle_state(cycle: CycleName) -> dict[str, Any] | None:
    """단일 cycle 의 current state."""
    return ev.get_state(f"cycle:{cycle}")


# ─── 10. set_cycle_state ─────────────────────────────────────────────────────


def set_cycle_state(
    cycle: CycleName,
    *,
    status: CycleStatus | None = None,
    current_thread_id: str | None = None,
    current_pr_url: str | None = None,
    current_directive_id: str | None = None,
) -> dict[str, Any]:
    """cycle state partial update + cycle_state_changed event emit."""
    key = f"cycle:{cycle}"
    state = ev.get_state(key) or {
        "status": "idle",
        "current_thread_id": None,
        "current_pr_url": None,
        "current_directive_id": None,
    }
    changes: dict[str, Any] = {}
    if status is not None and status != state.get("status"):
        changes["status"] = status
        state["status"] = status
    if current_thread_id is not None:
        changes["current_thread_id"] = current_thread_id
        state["current_thread_id"] = current_thread_id
    if current_pr_url is not None:
        changes["current_pr_url"] = current_pr_url
        state["current_pr_url"] = current_pr_url
    if current_directive_id is not None:
        changes["current_directive_id"] = current_directive_id
        state["current_directive_id"] = current_directive_id

    if not changes:
        return {"cycle": cycle, "no_change": True}

    ev.set_state(key, state)
    event_id = ev.append_event(
        "cycle_state_changed",
        {"cycle": cycle, "changes": changes, "new_state": state},
    )
    return {"event_id": event_id, "cycle": cycle, "changes": changes}


# ─── 7. register_directive_pending ───────────────────────────────────────────


def register_directive_pending(
    directive_id: str,
    summary: str,
    *,
    cycle_hint: CycleName | None = None,
) -> dict[str, Any]:
    """📌 등록 직후 — directive entry + 🟡 forum thread (사용자 정정 path).

    Args:
        directive_id: Discord message_id (snowflake)
        summary: 사용자 메시지 첫 80자 또는 polish 결과
        cycle_hint: nmae 의 추천 cycle (be/fe/rev/plan) — optional, plan 위임 시 reason 필수
    """
    key = f"directive:{directive_id}"
    if ev.get_state(key) is not None:
        return {"directive_id": directive_id, "duplicate": True}

    state: dict[str, Any] = {
        "directive_id": directive_id,
        "summary": summary,
        "status": "pending_polish",
        "thread_id": None,
        "assigned_cycle": cycle_hint,
        "delegation_reason": None,
        "pr_url": None,
        "closed_reason": None,
    }
    ev.set_state(key, state)
    event_id = ev.append_event(
        "directive_registered",
        {"directive_id": directive_id, "summary": summary, "cycle_hint": cycle_hint},
    )
    return {"event_id": event_id, "directive_id": directive_id}


# ─── 8. update_directive_status ──────────────────────────────────────────────


def update_directive_status(
    directive_id: str,
    new_status: DirectiveStatus,
    *,
    pr_url: str | None = None,
    closed_reason: str | None = None,
    thread_id: str | None = None,
    assigned_cycle: CycleName | None = None,
    delegation_reason: str | None = None,
) -> dict[str, Any]:
    """directive state transition + directive_status_changed event emit.

    사고 path 차단:
      - closed → closed_reason 필수 (사용자 정정 path)
      - completed → pr_url 권장
      - assigned + plan → delegation_reason 필수 (legacy 룰 일치)
    """
    if new_status == "closed" and not closed_reason:
        raise ValueError(
            f"update_directive_status(closed) requires closed_reason — "
            f"directive_id={directive_id}"
        )
    if (
        new_status == "assigned"
        and assigned_cycle == "plan"
        and not delegation_reason
    ):
        raise ValueError(
            f"update_directive_status(assigned, plan) requires delegation_reason "
            f"— directive_id={directive_id}"
        )

    key = f"directive:{directive_id}"
    state = ev.get_state(key)
    if state is None:
        raise ValueError(f"directive not found: {directive_id}")

    old_status = state.get("status")
    state["status"] = new_status
    if pr_url:
        state["pr_url"] = pr_url
    if closed_reason:
        state["closed_reason"] = closed_reason
    if thread_id:
        state["thread_id"] = thread_id
    if assigned_cycle:
        state["assigned_cycle"] = assigned_cycle
    if delegation_reason:
        state["delegation_reason"] = delegation_reason

    ev.set_state(key, state)
    event_id = ev.append_event(
        "directive_status_changed",
        {
            "directive_id": directive_id,
            "old_status": old_status,
            "new_status": new_status,
            "new_state": state,
        },
    )
    return {"event_id": event_id, "directive_id": directive_id,
            "old_status": old_status, "new_status": new_status}
