"""autoseed (#1523) 단위 — 유휴 백로그 자동 시드 가드 G1~G5 + scope→cycle 매핑.

spec: docs/features/roadmap-queue-autoseed.md.

isolated_db fixture (conftest) 로 events/work_queue 상태 격리. gh 호출은 fake runner
주입, Discord 부수효과(enqueue_directive)는 스텁으로 대체 — 큐 적재는 wq.enqueue 직접.
"""

from __future__ import annotations

import json
import types

import pytest

import autoseed
import events as ev
import work_queue as wq


def _gh_runner(issues: list[dict]):
    """fake subprocess.run — gh issue list 호출에 canned JSON 반환."""

    def _run(cmd, capture_output=True, text=True, timeout=None, check=False):
        return types.SimpleNamespace(returncode=0, stdout=json.dumps(issues), stderr="")

    return _run


def _stub_enqueue(monkeypatch, recorded: list):
    """enqueue_directive 스텁 — Discord 부수효과 제거, 큐엔 직접 적재."""

    def _fake(cycle, directive_id, title, task, *, thread_id="", priority=0):
        recorded.append({"cycle": cycle, "directive_id": directive_id, "title": title})
        return wq.enqueue(
            cycle, directive_id, title, task, enqueued_at="2026-06-03T00:00:00Z",
        )

    monkeypatch.setattr(autoseed.tq, "enqueue_directive", _fake)


def _issue(number, *, labels, title="제목", body="", assignees=None):
    return {
        "number": number,
        "title": title,
        "body": body,
        "labels": [{"name": lbl} for lbl in labels],
        "assignees": assignees or [],
        "createdAt": f"2026-06-0{number % 9 + 1}T00:00:00Z",
    }


# ── scope → cycle 매핑 (spec §5-2) ────────────────────────────────────────────
def test_scope_to_cycle_single():
    assert autoseed._scope_to_cycle(["scope:web", "type:feat"]) == "fe"
    assert autoseed._scope_to_cycle(["scope:recommendation"]) == "be"
    assert autoseed._scope_to_cycle(["scope:infra"]) == "infra"


def test_scope_to_cycle_ambiguous_multi_scope_none():
    # 복수 scope = 모호 → None (배제).
    assert autoseed._scope_to_cycle(["scope:web", "scope:song"]) is None
    assert autoseed._scope_to_cycle(["type:feat"]) is None


def test_scope_to_cycle_docs_to_plan():
    assert autoseed._scope_to_cycle(["type:docs"]) == "plan"


# ── G3 high-stakes 제외 (spec §5-3) ───────────────────────────────────────────
def test_high_stakes_release_label():
    assert autoseed._is_high_stakes(_issue(1, labels=["type:release", "scope:web"]))


def test_high_stakes_secret_keyword_in_body():
    assert autoseed._is_high_stakes(
        _issue(2, labels=["scope:user"], body="이 작업은 secret 토큰 회전이 필요")
    )


def test_high_stakes_normal_issue_ok():
    assert not autoseed._is_high_stakes(_issue(3, labels=["scope:web"], title="버튼 추가"))


# ── flag off → no-op (안전 배포) ──────────────────────────────────────────────
def test_disabled_returns_empty(isolated_db, monkeypatch):
    monkeypatch.delenv("AUTOSEED_ENABLED", raising=False)
    called = []
    monkeypatch.setattr(autoseed, "_fetch_backlog", lambda *a, **k: called.append(1) or [])
    assert autoseed.autoseed_once() == []
    assert not called  # gh 조회조차 안 함


# ── 정상 시드 (be 이슈 1건 pick → enqueue) ────────────────────────────────────
def test_seeds_be_issue(isolated_db, monkeypatch):
    monkeypatch.setenv("AUTOSEED_ENABLED", "1")
    recorded = []
    _stub_enqueue(monkeypatch, recorded)
    issues = [_issue(42, labels=["scope:recommendation"], title="추천 정확도 개선")]
    seeded = autoseed.autoseed_once(runner=_gh_runner(issues))
    assert {"issue": 42, "cycle": "be"} in seeded
    assert recorded[0]["directive_id"] == "autoseed-42"
    assert recorded[0]["title"].startswith("🌱")
    # directive 상태에 source/seed_issue 박혀 dedup 가능.
    state = ev.get_state("directive:autoseed-42")
    assert state["source"] == "autoseed" and state["seed_issue"] == 42


# ── G4 dedup — 이미 시드된 이슈 skip ──────────────────────────────────────────
def test_dedup_already_seeded(isolated_db, monkeypatch):
    monkeypatch.setenv("AUTOSEED_ENABLED", "1")
    ev.set_state("directive:autoseed-42", {"source": "autoseed", "seed_issue": 42})
    recorded = []
    _stub_enqueue(monkeypatch, recorded)
    issues = [_issue(42, labels=["scope:recommendation"])]
    # be 에서 42 는 이미 시드 → 다른 be 이슈 없으면 시드 0.
    seeded = autoseed.autoseed_once(runner=_gh_runner(issues))
    assert all(s["issue"] != 42 for s in seeded)


# ── G4 dedup — assignee 있으면 skip ───────────────────────────────────────────
def test_dedup_assignee(isolated_db, monkeypatch):
    monkeypatch.setenv("AUTOSEED_ENABLED", "1")
    recorded = []
    _stub_enqueue(monkeypatch, recorded)
    issues = [_issue(7, labels=["scope:recommendation"], assignees=[{"login": "someone"}])]
    seeded = autoseed.autoseed_once(runner=_gh_runner(issues))
    assert all(s["issue"] != 7 for s in seeded)


# ── G1 pending cap — 한도 도달 시 no-op ───────────────────────────────────────
def test_pending_cap(isolated_db, monkeypatch):
    monkeypatch.setenv("AUTOSEED_ENABLED", "1")
    monkeypatch.setenv("AUTOSEED_MAX_PENDING", "2")
    # 큐에 seeded 2건 선적재 → cap 도달.
    for n in (101, 102):
        wq.enqueue("be", f"autoseed-{n}", "t", "task", enqueued_at="2026-06-03T00:00:00Z")
    recorded = []
    _stub_enqueue(monkeypatch, recorded)
    issues = [_issue(50, labels=["scope:web"])]
    assert autoseed.autoseed_once(runner=_gh_runner(issues)) == []


# ── G2 — busy(in_flight) 사이클 skip ──────────────────────────────────────────
def test_busy_cycle_skipped(isolated_db, monkeypatch):
    monkeypatch.setenv("AUTOSEED_ENABLED", "1")
    ev.set_state("in_flight_agents", ["fe"])
    recorded = []
    _stub_enqueue(monkeypatch, recorded)
    # fe 이슈만 존재 + fe busy → fe skip → 시드 0.
    issues = [_issue(60, labels=["scope:web"])]
    seeded = autoseed.autoseed_once(runner=_gh_runner(issues))
    assert all(s["cycle"] != "fe" for s in seeded)


# ── G2 — 이미 대기 작업 있는 cycle 은 보충 안 함 ─────────────────────────────
def test_cycle_with_pending_not_topped_up(isolated_db, monkeypatch):
    monkeypatch.setenv("AUTOSEED_ENABLED", "1")
    # be 에 사람 directive 대기 → peek_next non-None → be 보충 skip.
    wq.enqueue("be", "human-dir-1", "t", "task", enqueued_at="2026-06-03T00:00:00Z")
    recorded = []
    _stub_enqueue(monkeypatch, recorded)
    issues = [_issue(70, labels=["scope:recommendation"])]
    seeded = autoseed.autoseed_once(runner=_gh_runner(issues))
    assert all(s["cycle"] != "be" for s in seeded)
