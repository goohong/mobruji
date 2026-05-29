"""Agent loop — events polling + tool dispatch.

설계:
1. asyncio polling — events 테이블 read (kind in [user_*, pr_merged, subagent_completed])
2. event 별 handler:
   - user_message → (Phase 2+) Claude Agent SDK query() + tool dispatch
   - user_pause → tools_pause.pause_global
   - user_resume → tools_pause.resume_global
   - pr_merged → tools_cycle.update_directive_status(completed)
   - subagent_completed → tools_subagent.mark_subagent_completed
3. mark_consumed

Phase 1.4 (본 PR):
- agent loop skeleton + event dispatch (SDK 미사용)
- SDK query 통합은 Phase 2 (NCP 배포 직전)

graceful:
- handler 예외 격리 — loop 차단 X
- SIGTERM (systemd stop) 시 깔끔 shutdown
"""

from __future__ import annotations

import asyncio
import logging
import signal
from typing import Any

import events as ev
import tools_cycle as tc
import tools_pause as tp
import tools_subagent as ts


logger = logging.getLogger(__name__)

POLL_INTERVAL_SECONDS = 1.0
EVENTS_BATCH_SIZE = 10


_MCP_SERVER_CACHE: object | None = None


def _get_mcp_server():  # noqa: ANN202 — SDK return type 미공개
    """MCP server (12 tool 묶음) lazy singleton.

    SDK docs: create_sdk_mcp_server(name, version, tools=[@tool decorated fns]).
    """
    global _MCP_SERVER_CACHE
    if _MCP_SERVER_CACHE is not None:
        return _MCP_SERVER_CACHE
    from claude_agent_sdk import create_sdk_mcp_server  # type: ignore[import-not-found]
    from tool_definitions import ALL_TOOLS
    _MCP_SERVER_CACHE = create_sdk_mcp_server(
        name="nmae", version="1.0.0", tools=ALL_TOOLS,
    )
    return _MCP_SERVER_CACHE


def _get_allowed_tools() -> list[str]:
    """allowed_tools list — MCP 명명 규칙 (mcp__<server>__<tool>)."""
    from tool_definitions import ALL_TOOLS
    names: list[str] = []
    for t in ALL_TOOLS:
        tool_name = getattr(t, "name", None) or getattr(t, "__name__", "")
        if tool_name:
            names.append(f"mcp__nmae__{tool_name}")
    return names


# ─── B안 가시화 — SDK message stream → events 'agent_progress' INSERT ────────
# 사용자 정정 (2026-05-29): "helper 가 뭐하고 있는지 보고 싶다" → agent 도 동일.
# SDK 의 async generator 가 yield 하는 message (TextBlock / ToolUseBlock / 등) 를
# 받아 사용자 메시지 thread 안 stream. 채널 noise 0, 사용자가 thread 열어 추적.

TOOL_EMOJI_MAP = {
    "post_discord_message": "💬",
    "forum_create_thread": "📂",
    "forum_comment": "📝",
    "forum_retag": "🏷️",
    "forum_edit_starter": "✏️",
    "launch_subagent": "🚀",
    "register_directive_pending": "📌",
    "update_directive_status": "🔄",
    "get_cycle_state": "🔍",
    "set_cycle_state": "🎛️",
    "pause_global": "⏸️",
    "resume_global": "▶️",
}


def _format_tool_progress(tool_name: str, tool_input: dict) -> str | None:
    """ToolUseBlock 의 tool_name + input 을 사용자 친화 1-line 으로.

    post_discord_message 는 답 자체이므로 progress 표시 X (중복 push 방지).
    """
    if tool_name == "post_discord_message":
        return None  # 답은 _push_agent_reply 가 처리 — 중복 X
    base = tool_name.replace("mcp__nmae__", "")
    emoji = TOOL_EMOJI_MAP.get(base, "🔧")

    if base == "launch_subagent":
        return f"{emoji} {base}: cycle={tool_input.get('cycle', '?')}, title={tool_input.get('title', '')[:60]}"
    if base == "register_directive_pending":
        return f"{emoji} {base}: directive_id={tool_input.get('directive_id', '?')[:20]}"
    if base == "update_directive_status":
        return f"{emoji} {base}: {tool_input.get('directive_id', '?')[:20]} → {tool_input.get('new_status', '?')}"
    if base in ("forum_comment", "forum_retag", "forum_edit_starter"):
        return f"{emoji} {base}: thread_id={tool_input.get('thread_id', '?')[:20]}"
    if base == "forum_create_thread":
        return f"{emoji} {base}: forum={tool_input.get('forum_id', '?')[:20]}, title={tool_input.get('title', '')[:50]}"
    if base in ("get_cycle_state", "set_cycle_state"):
        return f"{emoji} {base}: cycle={tool_input.get('cycle', '?')}"
    if base in ("pause_global", "resume_global"):
        return f"{emoji} {base}"
    return f"{emoji} {base}"


async def _emit_progress_from_sdk_message(
    message: object, thread_id: str, channel_id: str,
) -> None:
    """SDK message → events 'agent_reply' INSERT (thread_id 포함).

    SDK message type:
    - AssistantMessage(content=[TextBlock, ToolUseBlock, ...])
    - ResultMessage
    - UserMessage (tool_result)
    각 ToolUseBlock 만 progress 로 표시. 답 (TextBlock) 은 LLM 이 직접 tool 호출
    하는 post_discord_message 가 처리.
    """
    if thread_id == "" or not thread_id:
        return  # thread 미생성 시 progress stream X (채널 noise 방지)
    content = getattr(message, "content", None)
    if not content or not isinstance(content, list):
        return
    for block in content:
        block_type = type(block).__name__
        if block_type != "ToolUseBlock":
            continue
        tool_name = getattr(block, "name", "")
        tool_input = getattr(block, "input", {})
        if not isinstance(tool_input, dict):
            continue
        progress_text = _format_tool_progress(tool_name, tool_input)
        if progress_text is None:
            continue
        try:
            ev.append_event("agent_reply", {
                "channel_id": channel_id,
                "thread_id": thread_id,
                "body": progress_text,
            })
            logger.info("agent_progress: %s", progress_text[:80])
        except Exception as exc:  # noqa: BLE001
            logger.warning("agent_progress emit 실패: %r", exc)

# agent 가 처리할 event kind list (bot.py 는 'agent_*' 처리).
AGENT_EVENT_KINDS: frozenset[str] = frozenset({
    "user_message",
    "user_reaction",
    "user_pause",
    "user_resume",
    "pr_merged",
    "subagent_completed",
    # Phase E (2026-05-29) — 사용자 적재 directive → cycle 분배 + launch_subagent.
    "directive_approved",
})


# ─── event handlers ──────────────────────────────────────────────────────────


NMAE_SYSTEM_PROMPT = """\
너는 mobruji 의 nmae — Discord 기반 노래방 추천 서비스의 orchestration agent.

[역할]
- 사용자 Discord 메시지를 받아 작업을 4 cycle (be / fe / rev / plan) 에 위임.
- be: Spring Boot 백엔드. fe: Next.js 프론트엔드. rev: 코드 리뷰 / QA. plan: 큰 spec / ADR.
- 단순 정보 / 답변 가능한 질문이면 직접 답 (post_discord_message).
- 작업 위임이 필요하면 (1) register_directive_pending 으로 directive 등록 →
  (2) launch_subagent 로 cycle 시작.

[규칙]
1. 사용자 메시지 받으면 **항상** post_discord_message 한 번 호출 (답 또는 진행 알림).
2. paused 모드 (사이클 정지) 면 launch_subagent reject — 사용자 정정 / 단순 답만.
3. plan cycle 위임 시 delegation_reason 명시.
4. release / 파괴적 작업은 사용자 확인 받기 (직접 launch 금지).
5. 4 cycle 중복 launch 금지 — in_flight_agents lock 확인.

[도구 사용]
- 12 tool 만 사용 (정의 안 된 작업 불가).
- launch_subagent 의 directive_id 인자는 register_directive_pending 의 결과.
- forum_comment 만 사용, forum_create_thread 는 register_directive_pending 안에서만 호출됨.
"""


async def handle_user_message(payload: dict[str, Any]) -> None:
    """사용자 Discord 메시지 → Claude Agent SDK query() → tool dispatch.

    SDK 가 nmae LLM 호출 + tool_use 실행 + result 처리. 답 push 는 LLM 이
    post_discord_message tool 호출로 처리 (학습 의존 X — system prompt 명시).
    """
    body = payload.get("body", "")
    channel_id = payload.get("channel_id", "")
    user_id = payload.get("user_id", "")
    message_id = payload.get("message_id", "")
    # B안 가시화 — bot.py 가 사용자 메시지 thread 자동 생성. agent 의 답/진행
    # stream 은 모두 그 thread 안. fallback: thread_id 빈 문자열이면 채널 push.
    thread_id = payload.get("thread_id", "")

    try:
        from claude_agent_sdk import query, ClaudeAgentOptions  # type: ignore[import-not-found]
    except ImportError as exc:
        logger.warning(
            "claude_agent_sdk import 실패 (Phase 2 미설치) — fallback to log only: %r", exc,
        )
        logger.info(
            "user_message Phase 1.4 fallback — channel=%s user=%s body=%r",
            channel_id, user_id, body[:120],
        )
        return

    options = ClaudeAgentOptions(
        system_prompt=NMAE_SYSTEM_PROMPT,
        permission_mode="acceptEdits",
        mcp_servers={"nmae": _get_mcp_server()},
        allowed_tools=_get_allowed_tools(),
    )

    thread_directive = (
        f"답 push 시 thread_id='{thread_id}' 사용." if thread_id
        else f"답 push 시 reply_to_msg_id='{message_id}' 사용 (thread 미생성)."
    )
    user_prompt = (
        f"[사용자 메시지] (message_id={message_id}, channel_id={channel_id}, user_id={user_id})\n\n"
        f"{body}\n\n"
        f"위 메시지를 처리. 답이 필요하면 mcp__nmae__post_discord_message 호출 "
        f"(channel_id='{channel_id}'). {thread_directive}"
    )

    logger.info(
        "user_message → SDK query: user=%s thread=%s body=%r",
        user_id, thread_id or "(채널)", body[:120],
    )
    try:
        async for message in query(prompt=user_prompt, options=options):
            await _emit_progress_from_sdk_message(message, thread_id, channel_id)
            logger.debug("SDK message: %r", message)
        logger.info("user_message handled: message_id=%s", message_id)
    except Exception as exc:  # noqa: BLE001
        logger.exception("SDK query 실패 message_id=%s: %r", message_id, exc)


async def handle_user_pause(payload: dict[str, Any]) -> None:
    reason = payload.get("reason") or "사용자 명시 정지"
    result = tp.pause_global(reason=reason)
    logger.info("user_pause processed: %s", result)


async def handle_user_resume(payload: dict[str, Any]) -> None:
    result = tp.resume_global()
    logger.info("user_resume processed: %s", result)


async def handle_pr_merged(payload: dict[str, Any]) -> None:
    """bot.py 의 PR 머지 webhook 결과. directive 자동 completed 전이."""
    directive_id = payload.get("directive_id")
    pr_url = payload.get("pr_url")
    if not directive_id:
        logger.warning("pr_merged: directive_id 누락 — skip payload=%s", payload)
        return
    try:
        result = tc.update_directive_status(
            str(directive_id), "completed", pr_url=pr_url,
        )
        logger.info("pr_merged → completed: %s", result)
    except Exception as exc:  # noqa: BLE001
        logger.warning("pr_merged handle 실패: %s exc=%r", payload, exc)


async def handle_subagent_completed(payload: dict[str, Any]) -> None:
    cycle = payload.get("cycle")
    if cycle:
        ts.mark_subagent_completed(cycle)  # type: ignore[arg-type]
        logger.info("subagent_completed: %s", cycle)


DIRECTIVE_APPROVED_PROMPT = """\
[directive_approved event] 사용자가 다음 directive 를 적재했습니다 (📌 → O/X dialogue → ⭕ 등록 click).

directive_id: {directive_id}
summary: {summary}
polished_description: {description}
user_cycle_hint: {cycle_hint}

cycle 결정 규칙:
- user_cycle_hint 가 "auto" 가 아니면 (사용자 명시 위임) → **그 cycle 강제 사용, 판단 X**.
- "auto" 이면 summary + description 보고 적절한 cycle (be / fe / rev / plan) 판단.

처리:
1. 위 규칙으로 cycle 결정.
2. plan 위임 시 delegation_reason 명시 (신규 도메인, 다중 PR, 사용자 의도 분석 필요 등).
3. launch_subagent tool 호출 — directive_id, cycle, title, task 인자.
4. paused 모드면 launch_subagent 가 PausedError raise — 사용자에게 알림 (post_discord_message).

사용자 메시지 직접 처리 X (메시지는 이미 적재 완료 — 단순 launch 만).
"""


async def handle_directive_approved(payload: dict[str, Any]) -> None:
    """사용자 O click 으로 적재된 directive → cycle 분배 + launch_subagent.

    payload 예시 (bot.py 의 PinDialogueView ⭕ button 이 INSERT):
    {
        "directive_id": "1509...",
        "summary": "추천 API 400 fix",
        "description": "...polished 4 항목 markdown...",
        "cycle_hint": "be",  # optional
        "user_id": "123",
        "channel_id": "1506...",
    }
    """
    directive_id = payload.get("directive_id")
    if not directive_id:
        logger.warning("directive_approved: directive_id 누락 — skip payload=%s", payload)
        return

    summary = payload.get("summary", "")
    description = payload.get("description", "")
    cycle_hint = payload.get("cycle_hint", "")

    logger.info(
        "directive_approved: directive_id=%s cycle_hint=%s — SDK query 시작",
        directive_id, cycle_hint,
    )

    try:
        from claude_agent_sdk import query, ClaudeAgentOptions  # type: ignore[import-not-found]
    except ImportError as exc:
        logger.warning(
            "claude_agent_sdk 미설치 — directive_approved fallback (log only): %r", exc,
        )
        return

    options = ClaudeAgentOptions(
        system_prompt=NMAE_SYSTEM_PROMPT,
        permission_mode="acceptEdits",
        mcp_servers={"nmae": _get_mcp_server()},
        allowed_tools=_get_allowed_tools(),
    )

    prompt = DIRECTIVE_APPROVED_PROMPT.format(
        directive_id=directive_id,
        summary=summary,
        description=description,
        cycle_hint=cycle_hint or "(자동 판단)",
    )

    try:
        # directive_approved 시 thread_id = directive 의 PinDialogueView thread
        # (payload.get("thread_id") = bot.py 가 전달). 없으면 채널 push fallback.
        dir_thread_id = payload.get("thread_id", "")
        dir_channel_id = payload.get("channel_id", "")
        async for message in query(prompt=prompt, options=options):
            await _emit_progress_from_sdk_message(message, dir_thread_id, dir_channel_id)
            logger.debug("SDK directive_approved message: %r", message)
        logger.info("directive_approved handled: directive_id=%s", directive_id)
    except Exception as exc:  # noqa: BLE001
        logger.exception("SDK query 실패 directive_id=%s: %r", directive_id, exc)


HANDLERS = {
    "user_message": handle_user_message,
    "user_reaction": handle_user_message,  # Phase 1.4 stub — 동일 path
    "user_pause": handle_user_pause,
    "user_resume": handle_user_resume,
    "pr_merged": handle_pr_merged,
    "subagent_completed": handle_subagent_completed,
    # Phase E (2026-05-29) — 사용자 적재 directive 처리
    "directive_approved": handle_directive_approved,
}


# ─── main loop ───────────────────────────────────────────────────────────────


async def agent_loop(stop_event: asyncio.Event) -> None:
    """events polling + handler dispatch. graceful shutdown via stop_event."""
    logger.info("agent_loop started — poll interval=%.1fs", POLL_INTERVAL_SECONDS)
    while not stop_event.is_set():
        try:
            events = ev.read_unconsumed_events("agent", limit=EVENTS_BATCH_SIZE)
        except Exception as exc:  # noqa: BLE001
            logger.warning("read_unconsumed_events 실패: %r — sleep & retry", exc)
            await asyncio.sleep(POLL_INTERVAL_SECONDS * 5)
            continue

        for event in events:
            event_id = event["id"]
            kind = event["kind"]
            if kind not in AGENT_EVENT_KINDS:
                # bot.py 처리 kind — skip (mark X)
                continue
            handler = HANDLERS.get(kind)
            if handler is None:
                logger.warning("unknown agent event kind: %s id=%s", kind, event_id)
                ev.mark_consumed(event_id, "agent")
                continue
            try:
                await handler(event["payload"])
            except Exception as exc:  # noqa: BLE001
                logger.exception(
                    "handler 실패 kind=%s id=%s exc=%r — mark consumed (drop)",
                    kind, event_id, exc,
                )
            ev.mark_consumed(event_id, "agent")

        try:
            await asyncio.wait_for(stop_event.wait(), timeout=POLL_INTERVAL_SECONDS)
        except asyncio.TimeoutError:
            pass

    logger.info("agent_loop stopped")


def _install_signal_handlers(loop: asyncio.AbstractEventLoop, stop_event: asyncio.Event) -> None:
    """SIGTERM / SIGINT 받으면 graceful shutdown."""
    for sig in (signal.SIGTERM, signal.SIGINT):
        loop.add_signal_handler(sig, stop_event.set)


async def run() -> int:
    """entry — main.py 가 호출."""
    ev.init_schema()
    stop_event = asyncio.Event()
    _install_signal_handlers(asyncio.get_running_loop(), stop_event)
    await agent_loop(stop_event)
    return 0
