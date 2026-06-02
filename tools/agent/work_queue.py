"""Directive work-queue — 사이클별 우선순위 FIFO 큐 (#1388).

설계: docs/features/directive-work-queue.md

`handle_directive_approved` 가 즉시 `launch_subagent` 하던 것을 폐지하고, cycle 별
우선순위 큐에 적재 → dispatcher 가 사이클 idle 시 다음 항목 launch. busy 사이클은
실패가 아니라 대기열 순서를 기다린다 (사용자 정정 2026-05-31 — 큐 자체가 가시 지표).

storage: agent state key ``work_queue`` = ``{cycle: [item, ...]}``.
pure-ish — 모든 상태 접근은 events.get_state/set_state (path 주입 가능 → 단위 테스트).
"""

from __future__ import annotations

from pathlib import Path
from typing import Any, Final

import events as ev

WORK_QUEUE_KEY: Final[str] = "work_queue"
PRIORITY_URGENT: Final[int] = 10
PRIORITY_NORMAL: Final[int] = 0
# (#1531) infra = 온디맨드 ephemeral 사이클 (ADR-0027 옵션 D). 상시 워크트리 없이
# git worktree add/remove 로 처리 — subagent_runner 가 ephemeral lifecycle 담당.
VALID_CYCLES: Final[frozenset[str]] = frozenset({"be", "fe", "rev", "plan", "infra"})


def _load(path: Path | None = None) -> dict[str, list[dict[str, Any]]]:
    queue = ev.get_state(WORK_QUEUE_KEY, path=path)
    return queue if isinstance(queue, dict) else {}


def _save(queue: dict[str, list[dict[str, Any]]], path: Path | None = None) -> None:
    ev.set_state(WORK_QUEUE_KEY, queue, path=path)


def _ordered(items: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """우선순위 desc → enqueued_at asc (동률 FIFO)."""
    return sorted(
        items,
        key=lambda it: (-int(it.get("priority", 0)), it.get("enqueued_at", "")),
    )


def _position_in(items: list[dict[str, Any]], directive_id: str) -> int:
    """1-based 위치 (우선순위 정렬 기준). 미존재 시 0."""
    for idx, it in enumerate(_ordered(items), start=1):
        if it.get("directive_id") == directive_id:
            return idx
    return 0


def find(directive_id: str, *, path: Path | None = None) -> tuple[str, dict[str, Any]] | None:
    """모든 큐에서 directive_id 검색 → (cycle, item) 또는 None."""
    queue = _load(path)
    for cycle, items in queue.items():
        for it in items:
            if it.get("directive_id") == directive_id:
                return cycle, it
    return None


def enqueue(
    cycle: str,
    directive_id: str,
    title: str,
    task: str,
    *,
    enqueued_at: str,
    thread_id: str = "",
    priority: int = PRIORITY_NORMAL,
    path: Path | None = None,
) -> dict[str, Any]:
    """cycle 큐에 적재. 같은 directive_id 가 (어느 큐든) 이미 있으면 no-op (멱등).

    Returns:
        ``{"enqueued": bool, "cycle": str, "position": int(1-based), "ahead": int}``
    """
    if cycle not in VALID_CYCLES:
        raise ValueError(f"invalid cycle: {cycle}")
    queue = _load(path)

    existing = find(directive_id, path=path)
    if existing is not None:
        exist_cycle, _ = existing
        pos = _position_in(queue.get(exist_cycle) or [], directive_id)
        return {"enqueued": False, "cycle": exist_cycle, "position": pos,
                "ahead": max(0, pos - 1)}

    item = {
        "directive_id": directive_id,
        "title": title,
        "task": task,
        "thread_id": thread_id,
        "priority": int(priority),
        "enqueued_at": enqueued_at,
        "status": "queued",
    }
    queue.setdefault(cycle, []).append(item)
    _save(queue, path)
    pos = _position_in(queue[cycle], directive_id)
    return {"enqueued": True, "cycle": cycle, "position": pos, "ahead": max(0, pos - 1)}


def peek_next(cycle: str, *, path: Path | None = None) -> dict[str, Any] | None:
    """cycle 큐의 다음 처리 대상 (priority desc → FIFO). 빈 큐면 None."""
    queue = _load(path)
    queued = [it for it in (queue.get(cycle) or []) if it.get("status") == "queued"]
    ordered = _ordered(queued)
    return ordered[0] if ordered else None


def dequeue(cycle: str, directive_id: str, *, path: Path | None = None) -> bool:
    """cycle 큐에서 directive_id 제거. 제거됐으면 True."""
    queue = _load(path)
    items = queue.get(cycle) or []
    remaining = [it for it in items if it.get("directive_id") != directive_id]
    if len(remaining) == len(items):
        return False
    queue[cycle] = remaining
    _save(queue, path)
    return True


def position(cycle: str, directive_id: str, *, path: Path | None = None) -> int:
    """cycle 큐 안 1-based 위치. 미존재 시 0."""
    queue = _load(path)
    return _position_in(queue.get(cycle) or [], directive_id)


def snapshot(*, path: Path | None = None) -> dict[str, list[dict[str, Any]]]:
    """가시화용 — 비지 않은 큐만 우선순위 정렬해 반환."""
    queue = _load(path)
    return {cycle: _ordered(items) for cycle, items in queue.items() if items}
