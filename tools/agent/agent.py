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

# agent 가 처리할 event kind list (bot.py 는 'agent_*' 처리).
AGENT_EVENT_KINDS: frozenset[str] = frozenset({
    "user_message",
    "user_reaction",
    "user_pause",
    "user_resume",
    "pr_merged",
    "subagent_completed",
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

    try:
        from claude_agent_sdk import query, ClaudeAgentOptions  # type: ignore[import-not-found]
        from tool_definitions import ALL_TOOLS
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
    )

    user_prompt = (
        f"[사용자 메시지] (message_id={message_id}, channel_id={channel_id}, user_id={user_id})\n\n"
        f"{body}\n\n"
        f"위 메시지를 처리. 답이 필요하면 post_discord_message 호출 "
        f"(channel_id='{channel_id}', reply_to_msg_id='{message_id}')."
    )

    logger.info("user_message → SDK query: user=%s body=%r", user_id, body[:120])
    try:
        async for message in query(prompt=user_prompt, options=options):
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


HANDLERS = {
    "user_message": handle_user_message,
    "user_reaction": handle_user_message,  # Phase 1.4 stub — 동일 path
    "user_pause": handle_user_pause,
    "user_resume": handle_user_resume,
    "pr_merged": handle_pr_merged,
    "subagent_completed": handle_subagent_completed,
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
