"""Cycle / directive state tools (4종) — state.py 의 GlobalState 와 events 통합 update.

read = events.get_state 직접. write = state update + audit event emit (atomic 호출자
책임).

사고 path code 차단:
  - directive_status transitions = 명시 Literal — 미정의 status 거부
  - update_directive_status 가 pr_url / closed_reason 인자 강제 — 옛 사고 (cleanup 시 사유 누락) 차단

spec: tools/agent/README.md (Phase 1.3).
"""

from __future__ import annotations

import json
import os
import subprocess
from typing import Any

import events as ev
from state import CycleName, CycleStatus, DirectiveStatus


# ─── 9. get_cycle_state ──────────────────────────────────────────────────────


def get_cycle_state(cycle: CycleName) -> dict[str, Any] | None:
    """단일 cycle 의 current state."""
    return ev.get_state(f"cycle:{cycle}")


# ─── 9-b. get_pr_status (#1414 — 읽기 전용 PR/현황 조회) ──────────────────────

PR_STATUS_REPO = "goohong/mobruji"
PR_STATUS_TIMEOUT = 25


def get_pr_status(search: str = "", limit: int = 15) -> dict[str, Any]:
    """열린 PR 목록 조회 (읽기 전용 `gh pr list`). nmae 가 현황 질문 답변에 사용.

    raw Bash 대신 본 스코프 도구만 노출해 write 명령(git push/checkout 등) 사고 표면
    제거 (#1414 rev 🟡-1). `gh` 는 GH_TOKEN(.env) 인증. -R 로 repo 명시 — cwd 무관.
    실패 시 {"error": ..., "prs": []} graceful.
    """
    argv = [
        "gh", "pr", "list", "-R", PR_STATUS_REPO, "--state", "open",
        "--limit", str(max(1, min(int(limit), 50))),
        "--json", "number,title,labels,isDraft,headRefName",
    ]
    if search:
        argv += ["--search", search]
    try:
        result = subprocess.run(  # noqa: S603 — argv list, gh 고정
            argv, capture_output=True, text=True, timeout=PR_STATUS_TIMEOUT, check=False,
        )
        if result.returncode != 0:
            return {"error": (result.stderr or "").strip()[:200], "prs": []}
        raw = json.loads(result.stdout or "[]")
        prs = [
            {
                "number": p.get("number"),
                "title": p.get("title", ""),
                "labels": [lbl.get("name") for lbl in p.get("labels", [])],
                "draft": bool(p.get("isDraft")),
                "branch": p.get("headRefName", ""),
            }
            for p in raw
        ]
        return {"prs": prs, "count": len(prs)}
    except (OSError, subprocess.TimeoutExpired, json.JSONDecodeError) as exc:
        return {"error": str(exc)[:200], "prs": []}


# ─── 9-c. set_directive_forum_status (#1415 — 지시 forum 태그 전이) ────────────

DIRECTIVE_STATUS_SH = os.environ.get(
    "DIRECTIVE_STATUS_BIN",
    "/home/mobruji/mobruji/tools/discord-daemon/directive_status.sh",
)


def set_directive_forum_status(
    directive_id: str,
    new_status: str,
    *,
    cycle: str = "",
    reason: str = "",
    pr_url: str = "",
) -> None:
    """지시 forum thread 태그 전이(🟡→🔵→🟢) + directive-board.jsonl status + starter
    body PATCH 를 `directive_status.sh` 로 수행 (#1415).

    work-queue 는 agent state(`directive:{id}`) 만 갱신했고 directive-board.jsonl /
    Discord 태그는 안 건드려 지시 forum 이 🟡 대기 박제됐던 갭 fix. dispatch=in_progress,
    PR 머지=completed. 합성(rev-pr-*) / board 부재 directive 는 no-op. graceful.
    """
    if not directive_id or str(directive_id).startswith("rev-pr-"):
        return
    if new_status not in ("in_progress", "completed"):
        return
    argv = [
        DIRECTIVE_STATUS_SH, str(directive_id), new_status,
        pr_url or "", cycle or "", reason or "",
    ]
    try:
        subprocess.run(  # noqa: S603 — 고정 경로 + argv list
            argv, capture_output=True, text=True, timeout=20, check=False,
        )
    except (OSError, subprocess.TimeoutExpired):
        pass


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
    """📌 등록 직후 — directive entry (pending_polish). 멱등 (중복 directive_id no-op).

    Args:
        directive_id: Discord message_id (snowflake).
        summary: 사용자 메시지 첫 80자 또는 polish 결과.
        cycle_hint: nmae 의 추천 cycle (be/fe/rev/plan) — optional.
    """
    if ev.get_state(f"directive:{directive_id}") is not None:
        return {"directive_id": directive_id, "duplicate": True}

    key = f"directive:{directive_id}"
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
