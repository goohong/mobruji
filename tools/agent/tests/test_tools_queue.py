"""tools_queue.py — enqueue 오케스트레이션 + dispatcher 단위 테스트 (#1388)."""

from __future__ import annotations

import pytest

from datetime import datetime, timedelta, timezone


@pytest.fixture(autouse=True)
def _no_real_cycle_thread(monkeypatch):
    """기본: cycle forum thread 동기 생성(subprocess) 무력화 — 결정성. 개별 테스트가 override."""
    import tools_queue as tq
    monkeypatch.setattr(tq, "_create_cycle_thread", lambda *a, **k: None)


@pytest.fixture(autouse=True)
def _no_disk_pressure(request, monkeypatch):
    """(#1770) 기본: 디스크 압박 없음(여유) — 결정성. 테스트 host root 실사용률(예 94%)에
    좌우되지 않도록 dispatch 디스크 가드를 평시로 고정. 가드 테스트가 개별 override.
    `real_disk` 마커 테스트는 실제 root_disk_pct 를 검증하므로 이 패치를 건너뛴다."""
    if request.node.get_closest_marker("real_disk"):
        return
    import tools_queue as tq
    monkeypatch.setattr(tq, "root_disk_pct", lambda: 10)


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
                        lambda c, d, t, k, **kw: calls.append((c, d)) or {"ok": True})
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
    monkeypatch.setattr(ts, "launch_subagent", lambda *a, **kw: calls.append(a))
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
    monkeypatch.setattr(ts, "launch_subagent", lambda c, d, t, k, **kw: calls.append((c, d)))
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
    monkeypatch.setattr(ts, "launch_subagent", lambda *a, **kw: calls.append(a))
    monkeypatch.setattr(td, "forum_comment", lambda *a, **k: None)
    _seed_directive("d1")
    tq.enqueue_directive("rev", "d1", "t", "task", thread_id="T1")
    ev.set_state("paused", True)

    assert tq.dispatch_once() == []
    assert calls == []


def test_rev_autotrigger_enqueues_for_pr(isolated_db, monkeypatch):
    """#1403: 구현 sub-agent 완료 → 그 branch PR → rev 큐 자동 적재."""
    import tools_queue as tq, tools_discord as td, work_queue as wq
    import types
    monkeypatch.setattr(td, "forum_comment", lambda *a, **k: None)

    def fake_run(argv, **kw):
        if "rev-parse" in argv:
            return types.SimpleNamespace(stdout="feat/x-1\n", returncode=0, stderr="")
        return types.SimpleNamespace(
            stdout='[{"number":1234,"labels":[]}]', returncode=0, stderr="")
    monkeypatch.setattr(tq.subprocess, "run", fake_run)

    pr = tq.enqueue_rev_for_pr_if_any("be", "/tmp/wt")
    assert pr == "1234"
    queued = wq.peek_next("rev")
    assert queued["directive_id"] == "rev-pr-1234"
    # (#1499) rev-gate 머지 게이트 계약 — 자동 rev task 는 scope:web/backend PR 머지에
    # 필요한 정확한 통과 문자열 지시를 포함해야 한다 (없으면 영구 머지 차단 사고).
    assert "rev e2e PR pass" in queued["task"]
    assert "rev no-op pass" in queued["task"]


def test_rev_autotrigger_skips_rev_source(isolated_db, monkeypatch):
    import tools_queue as tq
    called = []
    monkeypatch.setattr(tq.subprocess, "run", lambda *a, **k: called.append(a))
    assert tq.enqueue_rev_for_pr_if_any("rev", "/tmp/wt") is None
    assert called == []  # subprocess 미호출 (rev 는 즉시 skip)


def test_rev_autotrigger_skips_already_reviewed(isolated_db, monkeypatch):
    import tools_queue as tq, work_queue as wq
    import types

    def fake_run(argv, **kw):
        if "rev-parse" in argv:
            return types.SimpleNamespace(stdout="feat/x-1\n", returncode=0, stderr="")
        return types.SimpleNamespace(
            stdout='[{"number":9,"labels":[{"name":"reviewed:claude"}]}]',
            returncode=0, stderr="")
    monkeypatch.setattr(tq.subprocess, "run", fake_run)
    assert tq.enqueue_rev_for_pr_if_any("be", "/tmp/wt") is None
    assert wq.peek_next("rev") is None


def test_rev_autotrigger_no_pr(isolated_db, monkeypatch):
    import tools_queue as tq
    import types

    def fake_run(argv, **kw):
        if "rev-parse" in argv:
            return types.SimpleNamespace(stdout="feat/x-1\n", returncode=0, stderr="")
        return types.SimpleNamespace(stdout="[]", returncode=0, stderr="")
    monkeypatch.setattr(tq.subprocess, "run", fake_run)
    assert tq.enqueue_rev_for_pr_if_any("be", "/tmp/wt") is None


def test_enqueue_creates_cycle_thread_and_reports_there(isolated_db, monkeypatch):
    """#1401: cycle forum thread 신설 → directive state 저장 + 거기로 📥 보고."""
    import tools_queue as tq, tools_discord as td, events as ev

    monkeypatch.setattr(tq, "_create_cycle_thread", lambda *a, **k: "999000111222333444")
    comments: list[tuple[str, str]] = []
    monkeypatch.setattr(td, "forum_comment", lambda tid, body: comments.append((tid, body)))
    _seed_directive("d1", thread_id="DLG1")  # dialogue thread

    tq.enqueue_directive("plan", "d1", "스펙 작성", "task", thread_id="DLG1")

    # directive state 에 cycle_thread_id 저장
    assert ev.get_state("directive:d1")["cycle_thread_id"] == "999000111222333444"
    # 📥 적재 댓글이 cycle thread 로
    assert any(tid == "999000111222333444" and "큐" in body for tid, body in comments)
    # 지시(dialogue) thread 엔 cycle 배정 pointer
    assert any(tid == "DLG1" and "plan" in body for tid, body in comments)


def test_dispatch_passes_cycle_thread_to_launch_and_exec(isolated_db, monkeypatch):
    """#1401: dispatch 가 cycle_thread_id 를 launch + launched.thread_id 로 전달."""
    import tools_queue as tq, tools_subagent as ts, tools_discord as td, events as ev

    monkeypatch.setattr(tq, "_create_cycle_thread", lambda *a, **k: "555")
    monkeypatch.setattr(td, "forum_comment", lambda *a, **k: None)
    launch_kw: dict = {}
    monkeypatch.setattr(ts, "launch_subagent",
                        lambda c, d, t, k, **kw: launch_kw.update(kw))
    _seed_directive("d1", thread_id="DLG1")
    tq.enqueue_directive("plan", "d1", "t", "task", thread_id="DLG1")

    launched = tq.dispatch_once()
    assert launch_kw.get("cycle_thread_id") == "555"  # wrapper 가 cycle thread 재사용
    assert launched[0]["thread_id"] == "555"  # exec 도 cycle thread 로 보고


def test_dispatch_launch_retry_cap_dequeues(isolated_db, monkeypatch):
    """#1390: launch 연속 실패 MAX 초과 시 큐에서 제외 (head-of-line block 차단)."""
    import tools_queue as tq, tools_subagent as ts, tools_discord as td, work_queue as wq

    monkeypatch.setattr(td, "forum_comment", lambda *a, **k: None)

    def boom(*a, **k):
        raise RuntimeError("wrapper fail")
    monkeypatch.setattr(ts, "launch_subagent", boom)
    _seed_directive("d1")
    tq.enqueue_directive("be", "d1", "t", "task", thread_id="T1")

    for _ in range(tq.MAX_LAUNCH_ATTEMPTS - 1):
        tq.dispatch_once()
        assert wq.peek_next("be") is not None  # MAX 전엔 큐 유지
    tq.dispatch_once()  # MAX 번째 → 제외
    assert wq.peek_next("be") is None


# ─── #1770 디스크 가드 ─────────────────────────────────────────────────────────


@pytest.mark.real_disk
def test_root_disk_pct_graceful_on_error(monkeypatch):
    """shutil.disk_usage 실패 시 0 반환 — 가드 미발동(안전 fallback)."""
    import tools_queue as tq
    import shutil as _sh

    def boom(_):
        raise OSError("no such mount")
    monkeypatch.setattr(_sh, "disk_usage", boom)
    assert tq.root_disk_pct() == 0


def test_disk_pressure_hold_threshold(monkeypatch):
    """root 사용률 >= DISK_HOLD_PCT 면 hold True, 미만이면 False."""
    import tools_queue as tq
    monkeypatch.setattr(tq, "DISK_HOLD_PCT", 90)
    monkeypatch.setattr(tq, "root_disk_pct", lambda: 92)
    assert tq.disk_pressure_hold() is True
    monkeypatch.setattr(tq, "root_disk_pct", lambda: 88)
    assert tq.disk_pressure_hold() is False


def test_dispatch_holds_new_launch_under_disk_pressure(isolated_db, monkeypatch):
    """디스크 압박 시 idle 사이클이어도 신규 launch 보류 (큐 유지)."""
    import tools_queue as tq, tools_subagent as ts, tools_discord as td, work_queue as wq

    calls: list = []
    monkeypatch.setattr(ts, "launch_subagent", lambda *a, **k: calls.append(a))
    monkeypatch.setattr(td, "forum_comment", lambda *a, **k: None)
    monkeypatch.setattr(tq, "root_disk_pct", lambda: 95)  # 압박
    _seed_directive("d1")
    tq.enqueue_directive("rev", "d1", "t", "task", thread_id="T1")

    launched = tq.dispatch_once()
    assert calls == []           # 신규 launch 안 함
    assert launched == []
    assert wq.peek_next("rev") is not None  # 큐 유지 (압박 완화 후 재개)


def test_dispatch_recovers_stale_even_under_disk_pressure(isolated_db, monkeypatch):
    """디스크 압박이어도 stale in_flight 회복은 진행 — lock/디스크 정리로 압박 완화."""
    import tools_queue as tq, tools_subagent as ts, tools_discord as td, events as ev

    monkeypatch.setattr(ts, "launch_subagent", lambda *a, **k: None)
    monkeypatch.setattr(td, "forum_comment", lambda *a, **k: None)
    monkeypatch.setattr(tq, "root_disk_pct", lambda: 96)  # 압박
    ev.set_state("in_flight_agents", ["rev"])
    stale = (datetime.now(timezone.utc) - timedelta(hours=3)).isoformat()
    ev.set_state(tq.IN_FLIGHT_STARTED_KEY, {"rev": stale})

    tq.dispatch_once()
    # stale lock 회복됨(신규 launch 는 보류하더라도).
    assert "rev" not in set(ev.get_state("in_flight_agents") or [])
