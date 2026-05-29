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


async def handle_user_message(payload: dict[str, Any]) -> None:
    """사용자 Discord 메시지 처리.

    Phase 1.4: stub — 로그만. Phase 2 에서 Claude Agent SDK query() 호출 +
    tool dispatch (post_discord_message / launch_subagent 등).
    """
    body = payload.get("body", "")
    channel_id = payload.get("channel_id", "")
    user_id = payload.get("user_id", "")
    logger.info(
        "user_message received (Phase 1.4 stub) — channel=%s user=%s body=%r",
        channel_id, user_id, body[:120],
    )
    # TODO Phase 2: SDK query + tool dispatch.


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
