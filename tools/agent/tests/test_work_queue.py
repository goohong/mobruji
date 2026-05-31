"""work_queue.py — 사이클별 우선순위 큐 단위 테스트 (#1388)."""

from __future__ import annotations

import pytest

TS = "2026-05-31T01:00:00+00:00"


def test_enqueue_and_position(isolated_db):
    import work_queue as wq

    result = wq.enqueue("rev", "d1", "t1", "task1", enqueued_at=TS)
    assert result["enqueued"] is True
    assert result["cycle"] == "rev"
    assert result["position"] == 1
    assert result["ahead"] == 0
    assert wq.position("rev", "d1") == 1


def test_fifo_order(isolated_db):
    import work_queue as wq

    wq.enqueue("be", "d1", "t", "x", enqueued_at="2026-05-31T01:00:00+00:00")
    wq.enqueue("be", "d2", "t", "x", enqueued_at="2026-05-31T01:01:00+00:00")
    nxt = wq.peek_next("be")
    assert nxt["directive_id"] == "d1"
    assert wq.position("be", "d2") == 2


def test_priority_jumps_ahead(isolated_db):
    import work_queue as wq

    wq.enqueue("fe", "d1", "t", "x", enqueued_at="2026-05-31T01:00:00+00:00")
    wq.enqueue(
        "fe", "urgent", "t", "x",
        enqueued_at="2026-05-31T01:05:00+00:00", priority=wq.PRIORITY_URGENT,
    )
    nxt = wq.peek_next("fe")
    assert nxt["directive_id"] == "urgent"
    assert wq.position("fe", "urgent") == 1
    assert wq.position("fe", "d1") == 2


def test_idempotent_enqueue_same_cycle(isolated_db):
    import work_queue as wq

    wq.enqueue("rev", "d1", "t", "x", enqueued_at=TS)
    again = wq.enqueue("rev", "d1", "t", "x", enqueued_at="2026-05-31T01:10:00+00:00")
    assert again["enqueued"] is False
    assert len(wq.snapshot()["rev"]) == 1


def test_idempotent_across_cycles(isolated_db):
    import work_queue as wq

    wq.enqueue("rev", "d1", "t", "x", enqueued_at=TS)
    moved = wq.enqueue("be", "d1", "t", "x", enqueued_at="2026-05-31T01:10:00+00:00")
    assert moved["enqueued"] is False
    assert moved["cycle"] == "rev"  # 기존 큐 위치 보존


def test_dequeue(isolated_db):
    import work_queue as wq

    wq.enqueue("plan", "d1", "t", "x", enqueued_at=TS)
    assert wq.dequeue("plan", "d1") is True
    assert wq.peek_next("plan") is None
    assert wq.dequeue("plan", "d1") is False


def test_invalid_cycle_raises(isolated_db):
    import work_queue as wq

    with pytest.raises(ValueError, match="invalid cycle"):
        wq.enqueue("web", "d1", "t", "x", enqueued_at=TS)


def test_snapshot_only_nonempty_and_ordered(isolated_db):
    import work_queue as wq

    wq.enqueue("rev", "d1", "t", "x", enqueued_at="2026-05-31T01:02:00+00:00")
    wq.enqueue(
        "rev", "d2", "t", "x",
        enqueued_at="2026-05-31T01:01:00+00:00", priority=wq.PRIORITY_URGENT,
    )
    snap = wq.snapshot()
    assert "be" not in snap
    assert [it["directive_id"] for it in snap["rev"]] == ["d2", "d1"]


def test_peek_skips_dispatched(isolated_db):
    import work_queue as wq
    import events

    wq.enqueue("rev", "d1", "t", "x", enqueued_at=TS)
    # 강제로 status=dispatched 로 바꿔도 peek 대상에서 제외되는지
    queue = events.get_state(wq.WORK_QUEUE_KEY)
    queue["rev"][0]["status"] = "dispatched"
    events.set_state(wq.WORK_QUEUE_KEY, queue)
    assert wq.peek_next("rev") is None
