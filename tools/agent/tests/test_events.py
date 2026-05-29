"""events.py — SQLite event log + state table tests."""

from __future__ import annotations


def test_append_and_read_events(isolated_db):
    import events
    e1 = events.append_event("user_message", {"body": "hi", "channel_id": "C1"})
    e2 = events.append_event("user_pause", {"reason": "test"})
    assert e1 == 1 and e2 == 2

    unconsumed = events.read_unconsumed_events("agent")
    assert len(unconsumed) == 2
    assert unconsumed[0]["kind"] == "user_message"
    assert unconsumed[1]["kind"] == "user_pause"


def test_mark_consumed_excludes_from_next_read(isolated_db):
    import events
    eid = events.append_event("user_message", {"body": "x"})
    events.mark_consumed(eid, "agent")
    assert events.read_unconsumed_events("agent") == []


def test_state_get_set_delete(isolated_db):
    import events
    assert events.get_state("paused") is None
    events.set_state("paused", True)
    assert events.get_state("paused") is True
    events.set_state("paused", False)
    assert events.get_state("paused") is False
    events.delete_state("paused")
    assert events.get_state("paused") is None


def test_state_complex_value(isolated_db):
    import events
    cycle_state = {"status": "running", "current_pr_url": "https://x/x/pull/1"}
    events.set_state("cycle:be", cycle_state)
    assert events.get_state("cycle:be") == cycle_state


def test_partial_index_uses_unconsumed(isolated_db):
    """consumed event 가 늘어나도 read_unconsumed 가 빠른지 (이 test 는 smoke)."""
    import events
    for i in range(100):
        eid = events.append_event("user_message", {"i": i})
        events.mark_consumed(eid, "agent")
    events.append_event("user_message", {"i": "fresh"})

    fresh = events.read_unconsumed_events("agent")
    assert len(fresh) == 1
    assert fresh[0]["payload"]["i"] == "fresh"
