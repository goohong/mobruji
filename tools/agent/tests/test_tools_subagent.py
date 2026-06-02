"""tools_subagent.py — 사고 path code 차단 tests.

핵심: paused / in_flight_lock / directive 검증 path 가 LLM 학습 의존 없이
function 안에서 raise.
"""

from __future__ import annotations

import pytest


def test_launch_paused_raises(isolated_db):
    """사고 path 차단: paused=True → PausedError."""
    import tools_subagent as ts, tools_pause as tp, tools_cycle as tc
    from state import PausedError

    tc.register_directive_pending("msg-1", "x")
    events_module = __import__("events")
    events_module.set_state("directive:msg-1", {**events_module.get_state("directive:msg-1"), "thread_id": "T1"})
    tp.pause_global()

    with pytest.raises(PausedError):
        ts.launch_subagent("be", "msg-1", "title", "task")


def test_launch_in_flight_raises(isolated_db):
    """사고 path 차단: 같은 cycle 중복 launch → CycleAlreadyRunningError."""
    import tools_subagent as ts
    from state import CycleAlreadyRunningError
    import events
    events.set_state("in_flight_agents", ["be"])

    # paused 풀린 상태 + in_flight 만 set
    with pytest.raises(CycleAlreadyRunningError):
        ts.launch_subagent("be", "msg-1", "title", "task")


def test_launch_missing_directive_raises(isolated_db):
    """사고 path 차단: directive 미존재 → ValueError."""
    import tools_subagent as ts

    with pytest.raises(ValueError, match="directive not found"):
        ts.launch_subagent("be", "no-such-msg", "title", "task")


def test_launch_missing_pending_thread_raises(isolated_db):
    """사고 path 차단: pending_thread_id 미등록 → ValueError."""
    import tools_subagent as ts, tools_cycle as tc

    tc.register_directive_pending("msg-1", "x")
    # thread_id 미설정 — 사고 path

    with pytest.raises(ValueError, match="pending thread 미등록"):
        ts.launch_subagent("be", "msg-1", "title", "task")


def test_mark_subagent_completed_removes_lock(isolated_db):
    import events, tools_subagent as ts

    events.set_state("in_flight_agents", ["be", "fe"])
    ts.mark_subagent_completed("be")
    remaining = events.get_state("in_flight_agents")
    assert remaining == ["fe"]


def test_launch_cycle_thread_id_satisfies_pending_requirement(isolated_db):
    """#1401: cycle_thread_id 제공 시 directive thread 부재여도 'pending 미등록' 통과."""
    import tools_subagent as ts, tools_cycle as tc
    import pytest as _pt

    tc.register_directive_pending("msg-1", "x")  # thread_id 미설정
    # cycle_thread_id 가 pending 역할 → ValueError(미등록) 대신 wrapper 부재로 진행.
    with _pt.raises(FileNotFoundError):
        ts.launch_subagent("be", "msg-1", "t", "k", cycle_thread_id="T-CYCLE")


def test_launch_infra_skips_wrapper_and_no_thread_required(isolated_db, monkeypatch):
    """#1531: infra launch — wrapper subprocess 건너뜀(standing 워크트리/채널 부재) +
    pending thread 면제(PR 코멘트 보고). lock + event 만."""
    import tools_subagent as ts
    import events as ev

    # directive 는 thread 없이 등록 (infra 는 전용 forum 없음)
    ev.set_state("directive:infra-d1", {
        "directive_id": "infra-d1", "summary": "infra task", "status": "polished",
        "thread_id": None, "assigned_cycle": "infra",
        "delegation_reason": None, "pr_url": None, "closed_reason": None,
    })
    wrapper_calls = []
    monkeypatch.setattr(ts.subprocess, "run",
                        lambda *a, **k: wrapper_calls.append(a) or None)

    result = ts.launch_subagent("infra", "infra-d1", "title", "task")
    # wrapper subprocess 미호출 (infra 는 skip)
    assert wrapper_calls == []
    # lock + event 부기는 됨
    assert "infra" in (ev.get_state("in_flight_agents") or [])
    assert result["cycle"] == "infra"
