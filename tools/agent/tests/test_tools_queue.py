"""tools_queue.py — enqueue 오케스트레이션 + dispatcher 단위 테스트 (#1388)."""

from __future__ import annotations

from datetime import datetime, timedelta, timezone


def _seed_directive(directive_id: str, thread_id: str = "T1") -> None:
    import events as ev

    ev.set_state(f"directive:{directive_id}", {
        "directive_id": directive_id, "summary": "s", "status": "polished",
        "thread_id": thread_id, "assigned_cycle": None, "delegation_reason": None,
        "pr_url": None, "closed_reason": None,
    })


def test_enqueue_updates_board_and_comments(isolated_db, monkeypatch):
    import tools_queue as tq, tools_discord as td, events as ev

    comments: list[tuple[str, str]] = []
    monkeypatch.setattr(td, "forum_comment",
                        lambda tid, body: comments.append((tid, body)))
    _seed_directive("d1")

    result = tq.enqueue_directive("rev", "d1", "title", "task", thread_id="T1")
    assert result["enqueued"] is True
    state = ev.get_state("directive:d1")
    assert state["status"] == "assigned"
    assert state["assigned_cycle"] == "rev"
    assert any("큐 1번째" in body for _, body in comments)


def test_enqueue_idempotent_no_double_comment(isolated_db, monkeypatch):
    import tools_queue as tq, tools_discord as td

    comments: list = []
    monkeypatch.setattr(td, "forum_comment", lambda *a, **k: comments.append(a))
    _seed_directive("d1")
    tq.enqueue_directive("rev", "d1", "t", "task", thread_id="T1")
    tq.enqueue_directive("rev", "d1", "t", "task", thread_id="T1")  # 중복
    assert len(comments) == 1  # 두 번째는 no-op (댓글 1회)


def test_dispatch_launches_idle_cycle(isolated_db, monkeypatch):
    import tools_queue as tq, tools_subagent as ts, tools_discord as td
    import work_queue as wq

    calls: list[tuple] = []
    monkeypatch.setattr(ts, "launch_subagent",
                        lambda c, d, t, k: calls.append((c, d)) or {"ok": True})
    monkeypatch.setattr(td, "forum_comment", lambda *a, **k: None)
    _seed_directive("d1")
    tq.enqueue_directive("rev", "d1", "title", "task", thread_id="T1")

    launched = tq.dispatch_once()
    assert ("rev", "d1") in calls
    assert len(launched) == 1
    assert launched[0]["cycle"] == "rev" and launched[0]["directive_id"] == "d1"
    assert wq.peek_next("rev") is None  # 큐에서 제거됨


def test_dispatch_skips_busy_cycle(isolated_db, monkeypatch):
    import tools_queue as tq, tools_subagent as ts, tools_discord as td, events as ev

    calls: list = []
    monkeypatch.setattr(ts, "launch_subagent", lambda *a: calls.append(a))
    monkeypatch.setattr(td, "forum_comment", lambda *a, **k: None)
    _seed_directive("d1")
    tq.enqueue_directive("rev", "d1", "t", "task", thread_id="T1")
    ev.set_state("in_flight_agents", ["rev"])
    ev.set_state(tq.IN_FLIGHT_STARTED_KEY,
                 {"rev": datetime.now(timezone.utc).isoformat()})

    launched = tq.dispatch_once()
    assert calls == []  # busy → skip, 실패 아님
    assert launched == []


def test_dispatch_recovers_stale_lock_then_launches(isolated_db, monkeypatch):
    import tools_queue as tq, tools_subagent as ts, tools_discord as td, events as ev

    calls: list[tuple] = []
    monkeypatch.setattr(ts, "launch_subagent", lambda c, d, t, k: calls.append((c, d)))
    monkeypatch.setattr(td, "forum_comment", lambda *a, **k: None)
    ev.set_state("in_flight_agents", ["rev"])
    stale = (datetime.now(timezone.utc) - timedelta(hours=3)).isoformat()
    ev.set_state(tq.IN_FLIGHT_STARTED_KEY, {"rev": stale})
    _seed_directive("d1")
    tq.enqueue_directive("rev", "d1", "t", "task", thread_id="T1")

    launched = tq.dispatch_once()
    assert ("rev", "d1") in calls  # stale 회복 후 launch
    assert any(x["cycle"] == "rev" and x["directive_id"] == "d1" for x in launched)


def test_dispatch_noop_when_paused(isolated_db, monkeypatch):
    import tools_queue as tq, tools_subagent as ts, tools_discord as td, events as ev

    calls: list = []
    monkeypatch.setattr(ts, "launch_subagent", lambda *a: calls.append(a))
    monkeypatch.setattr(td, "forum_comment", lambda *a, **k: None)
    _seed_directive("d1")
    tq.enqueue_directive("rev", "d1", "t", "task", thread_id="T1")
    ev.set_state("paused", True)

    assert tq.dispatch_once() == []
    assert calls == []
