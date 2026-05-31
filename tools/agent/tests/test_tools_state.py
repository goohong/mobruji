"""tools_cycle.py / tools_pause.py — state transition + 사고 path 차단 tests."""

from __future__ import annotations

import pytest


def test_register_directive_pending_creates_state(isolated_db):
    import events, tools_cycle as tc
    r = tc.register_directive_pending("msg-1", "summary text", cycle_hint="be")
    assert r["directive_id"] == "msg-1"
    state = events.get_state("directive:msg-1")
    assert state["summary"] == "summary text"
    assert state["status"] == "pending_polish"
    assert state["assigned_cycle"] == "be"


def test_register_directive_pending_duplicate_skip(isolated_db):
    import tools_cycle as tc
    tc.register_directive_pending("msg-2", "first")
    r2 = tc.register_directive_pending("msg-2", "second")
    assert r2.get("duplicate") is True


def test_update_directive_status_transitions(isolated_db):
    import events, tools_cycle as tc
    tc.register_directive_pending("msg-3", "x")
    r = tc.update_directive_status("msg-3", "polished")
    assert r["new_status"] == "polished"
    state = events.get_state("directive:msg-3")
    assert state["status"] == "polished"


def test_update_directive_closed_requires_reason(isolated_db):
    """사고 path 차단: closed → closed_reason 누락 시 ValueError."""
    import tools_cycle as tc
    tc.register_directive_pending("msg-4", "x")
    with pytest.raises(ValueError, match="closed_reason"):
        tc.update_directive_status("msg-4", "closed")


def test_update_directive_plan_assigned_requires_reason(isolated_db):
    """사고 path 차단: assigned + plan → delegation_reason 누락 시 ValueError."""
    import tools_cycle as tc
    tc.register_directive_pending("msg-5", "x")
    with pytest.raises(ValueError, match="delegation_reason"):
        tc.update_directive_status(
            "msg-5", "assigned", assigned_cycle="plan",
        )


def test_set_cycle_state_no_change(isolated_db):
    import tools_cycle as tc
    r1 = tc.set_cycle_state("be", status="running")
    assert r1["changes"]["status"] == "running"
    r2 = tc.set_cycle_state("be", status="running")
    assert r2.get("no_change") is True


def test_pause_resume(isolated_db):
    import events, tools_pause as tp
    assert events.get_state("paused") is None
    tp.pause_global(reason="test")
    assert events.get_state("paused") is True
    tp.resume_global()
    assert events.get_state("paused") is False


def test_pause_idempotent(isolated_db):
    import tools_pause as tp
    tp.pause_global()
    r2 = tp.pause_global()
    assert r2.get("no_change") is True
