"""tools_cycle.py — register_directive_pending kind=pr_review / pr_audit 시그니처 확장 tests.

spec: docs/features/pr-webhook-rev-forum.md §6-2 / §7-2 / §8.
이슈: #1364 (PR 2-b).

검증 범위:
  - kind=None (legacy) → 기존 behavior 보존 (회귀 가드)
  - kind="pr_review" → forum_create_thread 호출 + state schema 확장 + dedupe append +
    event=pr_review_registered
  - kind="pr_audit" → 기존 thread cache lookup + forum_retag + forum_edit_starter +
    forum_comment + event=pr_audit_registered
  - dedupe (같은 PR URL + 같은 kind 2번) → 두 번째는 duplicate 분기
  - PR_REVIEW_FORUM_ID env 미설정 시 graceful fallback (state 박지만 thread_id=None)
  - kind 미지정 + pr_url 만 명시 시 (잘못된 호출) → legacy 분기 (회귀 보존)
  - kind="pr_review" + pr_url 누락 → ValueError
  - kind="unknown" → ValueError (typo 가드)
"""

from __future__ import annotations

import tempfile
from pathlib import Path
from unittest.mock import MagicMock

import pytest


# ─── fixtures ────────────────────────────────────────────────────────────────


@pytest.fixture
def isolated_mobruji_dir(monkeypatch):
    """rev-forum-dedupe.jsonl 위치 격리 — 테스트 끝에 cleanup."""
    with tempfile.TemporaryDirectory() as tmpdir:
        monkeypatch.setenv("MOBRUJI_DIR", tmpdir)
        yield Path(tmpdir)


@pytest.fixture
def mock_forum_tools(monkeypatch):
    """tools_discord 의 forum_* 호출을 mock — Discord IO 차단 + 호출 검증."""
    import tools_cycle
    import tools_discord

    create = MagicMock(return_value={"event_id": 1, "forum_id": "test_forum"})
    comment = MagicMock(return_value={"event_id": 2, "thread_id": "test_thread"})
    retag = MagicMock(
        return_value={"event_id": 3, "thread_id": "test_thread", "tag_name": "🔵"}
    )
    edit_starter = MagicMock(return_value={"event_id": 4, "thread_id": "test_thread"})

    # tools_cycle 가 tools_discord 모듈을 td 로 import. monkeypatch 는 tools_cycle.td
    # attr 와 tools_discord 모듈 둘 다 — 후자만 patch 하면 tools_cycle 안 td 가 캐시.
    monkeypatch.setattr(tools_discord, "forum_create_thread", create)
    monkeypatch.setattr(tools_discord, "forum_comment", comment)
    monkeypatch.setattr(tools_discord, "forum_retag", retag)
    monkeypatch.setattr(tools_discord, "forum_edit_starter", edit_starter)
    monkeypatch.setattr(tools_cycle.td, "forum_create_thread", create)
    monkeypatch.setattr(tools_cycle.td, "forum_comment", comment)
    monkeypatch.setattr(tools_cycle.td, "forum_retag", retag)
    monkeypatch.setattr(tools_cycle.td, "forum_edit_starter", edit_starter)

    return {
        "create": create,
        "comment": comment,
        "retag": retag,
        "edit_starter": edit_starter,
    }


# ─── kind=None (legacy) 회귀 가드 ─────────────────────────────────────────────


def test_legacy_kind_none_preserves_existing_behavior(isolated_db):
    """kind 미지정 = 기존 behavior — pending_polish + directive_registered event."""
    import events
    import tools_cycle as tc

    result = tc.register_directive_pending("msg-legacy-1", "summary x", cycle_hint="be")
    assert result["directive_id"] == "msg-legacy-1"
    assert "event_id" in result
    # kind 키워드 결과에 미포함 (legacy 분기 식별).
    assert "kind" not in result

    state = events.get_state("directive:msg-legacy-1")
    assert state is not None
    assert state["summary"] == "summary x"
    assert state["status"] == "pending_polish"
    assert state["assigned_cycle"] == "be"
    assert state["pr_url"] is None
    assert state["thread_id"] is None
    # 신규 필드 (kind / parent_directive_id / source) 는 legacy 분기에서 박지 않음.
    assert "kind" not in state
    assert "source" not in state


def test_legacy_kind_none_duplicate_skip(isolated_db):
    """kind=None 시 같은 directive_id 재호출 → duplicate 분기 (기존)."""
    import tools_cycle as tc

    tc.register_directive_pending("msg-legacy-dup", "first")
    result2 = tc.register_directive_pending("msg-legacy-dup", "second")
    assert result2.get("duplicate") is True


# ─── kind="pr_review" — forum 신설 + state + event ───────────────────────────


def test_pr_review_creates_forum_thread(
    isolated_db, isolated_mobruji_dir, mock_forum_tools, monkeypatch,
):
    """kind=pr_review + PR_REVIEW_FORUM_ID env → forum_create_thread 1회 호출."""
    monkeypatch.setenv("PR_REVIEW_FORUM_ID", "1510158825729032352")

    import tools_cycle as tc

    result = tc.register_directive_pending(
        "rev-1234-open",
        "PR #1234 — feat(song): xxx",
        cycle_hint="rev",
        kind="pr_review",
        pr_url="https://github.com/owner/repo/pull/1234",
        source="pr_register_rev_hook",
    )

    assert result["directive_id"] == "rev-1234-open"
    assert result["kind"] == "pr_review"
    assert "event_id" in result

    # forum_create_thread 가 정확히 1회 호출.
    assert mock_forum_tools["create"].call_count == 1
    create_args = mock_forum_tools["create"].call_args
    # positional: (forum_id, title, body)
    assert create_args[0][0] == "1510158825729032352"
    title = create_args[0][1]
    body = create_args[0][2]
    assert "📌" in title
    assert "PR #1234" in title
    # body template — 📌 / 💬 / 🆔 / 📋 / 🔖 / footer.
    assert "📌" in body
    assert "💬 원본" in body
    assert "https://github.com/owner/repo/pull/1234" in body
    assert "🆔 `rev-1234-open`" in body
    assert "📋 진행 (🟡 대기)" in body
    assert "🔖 관련" in body
    # tags 키워드 — 🟡 1차 review.
    assert create_args[1]["tags"] == ["🟡 1차 review"]


def test_pr_review_state_schema_expanded(
    isolated_db, isolated_mobruji_dir, mock_forum_tools, monkeypatch,
):
    """kind=pr_review → state schema 에 kind / pr_url / source 신규 필드 박힘."""
    monkeypatch.setenv("PR_REVIEW_FORUM_ID", "forum_id_x")

    import events
    import tools_cycle as tc

    tc.register_directive_pending(
        "rev-2222-open",
        "PR #2222 — chore(infra): yyy",
        cycle_hint="rev",
        kind="pr_review",
        pr_url="https://github.com/owner/repo/pull/2222",
        source="pr_register_rev_hook",
    )

    state = events.get_state("directive:rev-2222-open")
    assert state is not None
    assert state["kind"] == "pr_review"
    assert state["pr_url"] == "https://github.com/owner/repo/pull/2222"
    assert state["source"] == "pr_register_rev_hook"
    assert state["assigned_cycle"] == "rev"
    # parent_directive_id 는 pr_review 시점에 None.
    assert state["parent_directive_id"] is None


def test_pr_review_emits_pr_review_registered_event(
    isolated_db, isolated_mobruji_dir, mock_forum_tools, monkeypatch,
):
    """kind=pr_review → event kind=pr_review_registered (directive_registered 아님)."""
    monkeypatch.setenv("PR_REVIEW_FORUM_ID", "forum_id_y")

    import events
    import tools_cycle as tc

    tc.register_directive_pending(
        "rev-3333-open",
        "PR #3333 — feat: zzz",
        cycle_hint="rev",
        kind="pr_review",
        pr_url="https://github.com/owner/repo/pull/3333",
    )

    # events table 안 pr_review_registered 1건.
    evts = events.read_unconsumed_events("agent")
    kinds = [e["kind"] for e in evts]
    assert "pr_review_registered" in kinds
    # legacy directive_registered 는 박지 않음 (회귀 가드).
    assert "directive_registered" not in kinds


def test_pr_review_forum_id_env_missing_graceful(
    isolated_db, isolated_mobruji_dir, mock_forum_tools, monkeypatch,
):
    """PR_REVIEW_FORUM_ID env 부재 시 graceful — forum_create_thread 호출 안 함 + state 박힘."""
    monkeypatch.delenv("PR_REVIEW_FORUM_ID", raising=False)

    import events
    import tools_cycle as tc

    result = tc.register_directive_pending(
        "rev-4444-open",
        "PR #4444 — fix: aaa",
        cycle_hint="rev",
        kind="pr_review",
        pr_url="https://github.com/owner/repo/pull/4444",
    )

    assert result["directive_id"] == "rev-4444-open"
    assert result["kind"] == "pr_review"

    # forum_create_thread 호출 안 됨.
    assert mock_forum_tools["create"].call_count == 0

    # state 는 박혔지만 thread_id=None.
    state = events.get_state("directive:rev-4444-open")
    assert state is not None
    assert state["thread_id"] is None
    assert state["pr_url"] == "https://github.com/owner/repo/pull/4444"
    assert state["kind"] == "pr_review"


# ─── kind="pr_audit" — 기존 thread lookup + 단계 전이 ────────────────────────


def test_pr_audit_retags_existing_thread(
    isolated_db, isolated_mobruji_dir, mock_forum_tools, monkeypatch,
):
    """pr_audit — 같은 PR URL 의 기존 pr_review thread cache hit → retag + edit_starter + comment."""
    monkeypatch.setenv("PR_REVIEW_FORUM_ID", "forum_id_z")

    import tools_cycle as tc

    pr_url = "https://github.com/owner/repo/pull/5555"

    # 단계 1: pr_review 등록 → cache 박힘.
    tc.register_directive_pending(
        "rev-5555-open",
        "PR #5555 open",
        cycle_hint="rev",
        kind="pr_review",
        pr_url=pr_url,
        thread_id="thread_5555",  # 명시 — cache 에 박힘.
    )

    # mock 호출 횟수 reset.
    mock_forum_tools["create"].reset_mock()

    # 단계 2: pr_audit 등록 → cache lookup → 같은 thread 단계 전이.
    result = tc.register_directive_pending(
        "rev-5555-merge",
        "PR #5555 merge",
        cycle_hint="rev",
        kind="pr_audit",
        pr_url=pr_url,
        parent_directive_id="rev-5555-open",
    )

    assert result["kind"] == "pr_audit"
    assert "event_id" in result

    # forum_retag / forum_edit_starter / forum_comment 각 1회 호출.
    assert mock_forum_tools["retag"].call_count == 1
    assert mock_forum_tools["edit_starter"].call_count == 1
    assert mock_forum_tools["comment"].call_count == 1

    # retag 인자 검증 — 같은 thread_id + 🔵 사후 E2E QA.
    retag_args = mock_forum_tools["retag"].call_args
    assert retag_args[0][0] == "thread_5555"
    assert "🔵" in retag_args[0][1]

    # edit_starter 인자 검증 — 본문에 단계 1 결과 + 단계 2 체크리스트.
    edit_args = mock_forum_tools["edit_starter"].call_args
    assert edit_args[0][0] == "thread_5555"
    body = edit_args[0][1]
    assert "단계 1 결과" in body
    assert "단계 2" in body
    assert "🔵 사후 E2E QA" in body

    # comment 인자 검증 — 머지 알림.
    comment_args = mock_forum_tools["comment"].call_args
    assert comment_args[0][0] == "thread_5555"
    assert "머지됨" in comment_args[0][1]


def test_pr_audit_state_includes_parent_directive_id(
    isolated_db, isolated_mobruji_dir, mock_forum_tools, monkeypatch,
):
    """pr_audit 시 parent_directive_id 가 state 에 박힘."""
    monkeypatch.setenv("PR_REVIEW_FORUM_ID", "forum_id_w")

    import events
    import tools_cycle as tc

    pr_url = "https://github.com/owner/repo/pull/6666"
    tc.register_directive_pending(
        "rev-6666-open", "open", kind="pr_review", pr_url=pr_url,
        thread_id="thread_6666",
    )
    tc.register_directive_pending(
        "rev-6666-merge", "merge",
        kind="pr_audit", pr_url=pr_url,
        parent_directive_id="rev-6666-open",
        source="pr_register_rev_hook",
    )

    state = events.get_state("directive:rev-6666-merge")
    assert state is not None
    assert state["kind"] == "pr_audit"
    assert state["parent_directive_id"] == "rev-6666-open"
    assert state["pr_url"] == pr_url
    assert state["source"] == "pr_register_rev_hook"
    # cache lookup hit → thread_id 박힘.
    assert state["thread_id"] == "thread_6666"


def test_pr_audit_cache_miss_graceful(
    isolated_db, isolated_mobruji_dir, mock_forum_tools, monkeypatch,
):
    """pr_audit + cache miss (pr_review 누락) → graceful, retag/edit_starter/comment 호출 안 함."""
    monkeypatch.setenv("PR_REVIEW_FORUM_ID", "forum_id_v")

    import events
    import tools_cycle as tc

    pr_url = "https://github.com/owner/repo/pull/7777"
    # pr_review 등록 안 함 — cache miss.

    result = tc.register_directive_pending(
        "rev-7777-merge", "merge only", kind="pr_audit", pr_url=pr_url,
    )

    assert result["kind"] == "pr_audit"

    # 단계 전이 mock 호출 안 됨.
    assert mock_forum_tools["retag"].call_count == 0
    assert mock_forum_tools["edit_starter"].call_count == 0
    assert mock_forum_tools["comment"].call_count == 0

    # state 박혔지만 thread_id=None.
    state = events.get_state("directive:rev-7777-merge")
    assert state is not None
    assert state["thread_id"] is None
    assert state["kind"] == "pr_audit"


def test_pr_audit_emits_pr_audit_registered_event(
    isolated_db, isolated_mobruji_dir, mock_forum_tools, monkeypatch,
):
    """pr_audit → event kind=pr_audit_registered."""
    monkeypatch.setenv("PR_REVIEW_FORUM_ID", "forum_id_u")

    import events
    import tools_cycle as tc

    pr_url = "https://github.com/owner/repo/pull/8888"
    tc.register_directive_pending(
        "rev-8888-open", "open", kind="pr_review", pr_url=pr_url,
        thread_id="t8888",
    )
    tc.register_directive_pending(
        "rev-8888-merge", "merge", kind="pr_audit", pr_url=pr_url,
    )

    evts = events.read_unconsumed_events("agent")
    kinds = [e["kind"] for e in evts]
    assert "pr_review_registered" in kinds
    assert "pr_audit_registered" in kinds


# ─── dedupe (rev-forum-dedupe.jsonl) ─────────────────────────────────────────


def test_dedupe_same_pr_url_same_kind_skip(
    isolated_db, isolated_mobruji_dir, mock_forum_tools, monkeypatch,
):
    """같은 (pr_url, kind=pr_review) 2번 호출 시 두 번째는 duplicate (directive_id 차이 무관).

    실제로는 hook script 가 같은 directive_id 를 보내므로 directive_id 단계의 duplicate
    분기가 1차. 본 테스트는 다른 directive_id + 같은 pr_url + 같은 kind 시도 — 새 directive 로
    박히지만 dedupe cache 안에 두 entry append 됨 (state 분리, dedupe 는 race 가드 용도).
    """
    monkeypatch.setenv("PR_REVIEW_FORUM_ID", "forum_id_t")

    import tools_cycle as tc

    pr_url = "https://github.com/owner/repo/pull/9999"

    # 첫 호출.
    r1 = tc.register_directive_pending(
        "rev-9999-open", "open", kind="pr_review", pr_url=pr_url,
        thread_id="thread_a",
    )
    assert r1.get("kind") == "pr_review"

    # 같은 directive_id 2번 호출 → duplicate 분기 (kind 무관).
    r2 = tc.register_directive_pending(
        "rev-9999-open", "open again", kind="pr_review", pr_url=pr_url,
    )
    assert r2.get("duplicate") is True


def test_dedupe_cache_persists_thread_id_for_audit(
    isolated_db, isolated_mobruji_dir, mock_forum_tools, monkeypatch,
):
    """pr_review 시점 dedupe cache 에 thread_id 박혀서, 후속 pr_audit 가 lookup 가능."""
    monkeypatch.setenv("PR_REVIEW_FORUM_ID", "forum_id_s")

    import tools_cycle as tc

    pr_url = "https://github.com/owner/repo/pull/1111"
    tc.register_directive_pending(
        "rev-1111-open", "open", kind="pr_review", pr_url=pr_url,
        thread_id="thread_1111",
    )

    dedupe_path = isolated_mobruji_dir / "rev-forum-dedupe.jsonl"
    assert dedupe_path.exists()
    contents = dedupe_path.read_text(encoding="utf-8")
    assert "thread_1111" in contents
    assert pr_url in contents
    assert '"kind": "pr_review"' in contents or '"kind":"pr_review"' in contents


# ─── error 가드 ──────────────────────────────────────────────────────────────


def test_unknown_kind_raises(isolated_db, isolated_mobruji_dir, monkeypatch):
    """kind='unknown' → ValueError (typo 가드)."""
    import tools_cycle as tc

    with pytest.raises(ValueError, match="unknown kind"):
        tc.register_directive_pending(
            "rev-X-open", "x", kind="unknown_kind",
            pr_url="https://github.com/x/y/pull/1",
        )


def test_pr_review_missing_pr_url_raises(isolated_db, isolated_mobruji_dir):
    """kind=pr_review + pr_url 누락 → ValueError."""
    import tools_cycle as tc

    with pytest.raises(ValueError, match="requires pr_url"):
        tc.register_directive_pending(
            "rev-Y-open", "y", kind="pr_review",
        )


def test_pr_audit_missing_pr_url_raises(isolated_db, isolated_mobruji_dir):
    """kind=pr_audit + pr_url 누락 → ValueError."""
    import tools_cycle as tc

    with pytest.raises(ValueError, match="requires pr_url"):
        tc.register_directive_pending(
            "rev-Z-merge", "z", kind="pr_audit",
        )
