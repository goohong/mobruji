"""handle_post_merge_review_requested 단위 테스트 (#1447).

bridge merge 감지 → post_merge_review_requested event → rev 큐 적재 검증.
"""
from __future__ import annotations
import asyncio
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import agent  # noqa: E402


def test_enqueues_rev_postmerge_directive(monkeypatch):
    calls = {}
    import tools_queue as tq
    monkeypatch.setattr(tq, "enqueue_directive",
                        lambda **k: calls.update(k) or {"enqueued": True})
    asyncio.run(agent.handle_post_merge_review_requested({
        "pr_number": 1434,
        "pr_url": "https://github.com/goohong/mobruji/pull/1434",
        "pr_title": "테스트 PR",
    }))
    assert calls["cycle"] == "rev"
    assert calls["directive_id"] == "rev-postmerge-1434"
    assert "1434" in calls["task"]
    assert "rev-post-merge-pass" in calls["task"]  # 라벨 부여 지시 포함
    # (#1457) 단계 2 재정의 — dev 배포본 E2E 검증으로 교체, 옛 "develop checkout + 시나리오 재실행" 절차 제거
    assert "dev 배포 E2E 검증" in calls["task"]
    assert "checkout" not in calls["task"]
    assert "시나리오를 재실행" not in calls["task"]
    assert "Playwright" in calls["task"]
    assert "regression:dev" in calls["task"]  # 회귀 시 라벨 지시


def test_missing_pr_number_skips(monkeypatch):
    called = {"n": 0}
    import tools_queue as tq
    monkeypatch.setattr(tq, "enqueue_directive", lambda **k: called.update(n=called["n"]+1))
    asyncio.run(agent.handle_post_merge_review_requested({}))
    assert called["n"] == 0  # pr_number 없으면 적재 안 함


def test_registered_in_handlers_and_kinds():
    assert "post_merge_review_requested" in agent.AGENT_EVENT_KINDS
    assert "post_merge_review_requested" in agent.HANDLERS
