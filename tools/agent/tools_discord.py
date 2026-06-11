"""Discord I/O tools (5종) — agent → bot.py 통신.

설계: agent 가 직접 Discord REST API 호출 X. events 테이블 에 'agent_reply' /
'agent_forum_action' kind 로 INSERT — bot.py 가 polling 으로 SELECT + Discord
push. **사고 path code 차단**:
  - agent 가 별 forum thread 만들지 X — forum_comment 안에서 thread_id 강제
  - reply_to_msg_id 가 명시 인자 — 옛 메시지 reply 사고 차단
  - Discord push 실패 = events.consumed_by 미설정 + retry path

spec: tools/agent/README.md (Phase 1.3).
"""

from __future__ import annotations

from typing import Any

import events as ev


# ─── 1. post_discord_message ─────────────────────────────────────────────────


def post_discord_message(
    channel_id: str,
    body: str,
    *,
    reply_to_msg_id: str | None = None,
    thread_id: str | None = None,
    choices: list[str] | None = None,
    dialogue_style: str | None = None,
) -> dict[str, Any]:
    """Discord 채널 (또는 thread) 에 message push.

    Args:
        channel_id: Discord 채널 ID (snowflake)
        body: 메시지 본문
        reply_to_msg_id: 사용자 메시지 reply 형태 (선택)
        thread_id: thread 안 push 시 (선택)
        choices: 선택지 list (최대 10). bot 가 push 후 reaction 미리
            부착, 사용자 tap 시 그 value 가 user_message 로 들어옴.
        dialogue_style: "register" 시 ⭕ 등록 / ✏️ 수정 / 🗑️ 제거 3 button
            UI (choices 최대 3개). 그 외 None / "default" 시 keycap 1️⃣–🔟.
    """
    payload: dict[str, Any] = {
        "channel_id": channel_id,
        "body": body,
        "reply_to_msg_id": reply_to_msg_id,
        "thread_id": thread_id,
    }
    if choices:
        payload["choices"] = choices
    if dialogue_style:
        payload["dialogue_style"] = dialogue_style
    event_id = ev.append_event("agent_reply", payload)
    return {"event_id": event_id, "channel_id": channel_id}


# ─── 2. forum_create_thread ──────────────────────────────────────────────────


def forum_create_thread(
    forum_id: str,
    title: str,
    body: str,
    tags: list[str] | None = None,
) -> dict[str, Any]:
    """Forum 채널 (type=15) 에 신규 thread 생성.

    **사용 제한**: register_directive_pending 또는 사용자 명시 시점만. 자유 사용
    금지 (사고 path — "[plan] 분석 완료" 같은 noise thread 생성 방지).

    Args:
        forum_id: forum 채널 ID
        title: thread 제목 (100자 cap)
        body: starter message 본문
        tags: applied tag name list (forum 의 available_tags 중)
    """
    payload = {
        "action": "create_thread",
        "forum_id": forum_id,
        "title": title[:99],
        "body": body,
        "tags": tags or [],
    }
    event_id = ev.append_event("agent_forum_action", payload)
    return {"event_id": event_id, "forum_id": forum_id}


# ─── 3. forum_comment ────────────────────────────────────────────────────────


def forum_comment(thread_id: str, body: str) -> dict[str, Any]:
    """Forum thread 안에 comment 추가. **thread_id 강제** = 별 thread 생성 X."""
    payload = {
        "action": "comment",
        "thread_id": thread_id,
        "body": body,
    }
    event_id = ev.append_event("agent_forum_action", payload)
    return {"event_id": event_id, "thread_id": thread_id}


# ─── 4. forum_retag ──────────────────────────────────────────────────────────


def forum_retag(thread_id: str, tag_name: str) -> dict[str, Any]:
    """Forum thread 의 tag 변경 (🟡 → ⏳ → ✅ lifecycle).

    Args:
        thread_id: 변경할 thread
        tag_name: 새 tag (forum 의 available_tags 의 name)
    """
    payload = {
        "action": "retag",
        "thread_id": thread_id,
        "tag_name": tag_name,
    }
    event_id = ev.append_event("agent_forum_action", payload)
    return {"event_id": event_id, "thread_id": thread_id, "tag_name": tag_name}


# ─── 5. forum_edit_starter ───────────────────────────────────────────────────


def forum_edit_starter(thread_id: str, body: str) -> dict[str, Any]:
    """Forum thread starter message (thread 의 message_id = thread_id) 본문 PATCH.

    legacy 의 `discord-reply.sh --forum-edit` 와 동등 — 진행 상황 / 완료 footer.
    """
    payload = {
        "action": "edit_starter",
        "thread_id": thread_id,
        "body": body,
    }
    event_id = ev.append_event("agent_forum_action", payload)
    return {"event_id": event_id, "thread_id": thread_id}
