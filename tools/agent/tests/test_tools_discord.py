"""tools_discord.py — Discord I/O event INSERT tests.

agent 가 직접 Discord API X — events 테이블 에 INSERT 만. bot.py 가 polling 으로
consume 해서 실제 push.
"""

from __future__ import annotations


def test_post_discord_message_inserts_event(isolated_db):
    import events, tools_discord as td

    r = td.post_discord_message("C1", "hello", thread_id=None)
    assert r["event_id"] > 0

    queued = events.read_unconsumed_events("bot")
    assert len(queued) == 1
    assert queued[0]["kind"] == "agent_reply"
    assert queued[0]["payload"]["body"] == "hello"


def test_post_discord_message_has_no_quote_reply_key(isolated_db):
    """#1631 회귀 가드: quote-reply 영구 제거 — payload 에 reply_to_msg_id 키 부재."""
    import events, tools_discord as td

    td.post_discord_message("C1", "hello", thread_id=None)
    queued = events.read_unconsumed_events("bot")
    assert "reply_to_msg_id" not in queued[0]["payload"]


def test_post_discord_message_rejects_reply_to_msg_id_kwarg(isolated_db):
    """#1631: reply_to_msg_id 인자 자체가 제거됨 — 전달 시 TypeError."""
    import pytest
    import tools_discord as td

    with pytest.raises(TypeError):
        td.post_discord_message("C1", "hello", reply_to_msg_id="M1")  # type: ignore[call-arg]


def test_forum_create_thread_inserts_action(isolated_db):
    import events, tools_discord as td

    r = td.forum_create_thread("F1", "title", "body", tags=["🟡"])
    assert r["event_id"] > 0

    queued = events.read_unconsumed_events("bot")
    assert queued[0]["kind"] == "agent_forum_action"
    assert queued[0]["payload"]["action"] == "create_thread"
    assert queued[0]["payload"]["tags"] == ["🟡"]


def test_forum_thread_title_truncation(isolated_db):
    import events, tools_discord as td
    long_title = "A" * 150
    td.forum_create_thread("F1", long_title, "body")
    queued = events.read_unconsumed_events("bot")
    assert len(queued[0]["payload"]["title"]) == 99


def test_forum_comment_requires_thread_id(isolated_db):
    """사고 path 차단: comment 는 thread_id 인자 강제 (별 thread 생성 X)."""
    import tools_discord as td
    # function signature 자체가 thread_id 강제 — TypeError 발생
    import pytest
    with pytest.raises(TypeError):
        td.forum_comment(body="x")  # type: ignore[call-arg]


def test_forum_retag_event(isolated_db):
    import events, tools_discord as td

    td.forum_retag("T1", "⏳")
    queued = events.read_unconsumed_events("bot")
    assert queued[0]["payload"]["action"] == "retag"
    assert queued[0]["payload"]["tag_name"] == "⏳"


def test_forum_edit_starter_event(isolated_db):
    import events, tools_discord as td

    td.forum_edit_starter("T1", "new body")
    queued = events.read_unconsumed_events("bot")
    assert queued[0]["payload"]["action"] == "edit_starter"
    assert queued[0]["payload"]["body"] == "new body"
