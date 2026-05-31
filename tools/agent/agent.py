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

# (#1398 rev 🟡-2) fire-and-forget sub-agent exec task 참조 보관 — event loop 가
# task 를 weak ref 로만 유지해 고부하 시 중도 GC 취소되는 사고 차단. done 시 자동 제거.
_EXEC_TASKS: set = set()
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
    "enqueue_work": "📥",
    "register_directive_pending": "📌",
    "update_directive_status": "🔄",
    "get_cycle_state": "🔍",
    "get_pr_status": "📋",
    "set_cycle_state": "🎛️",
    "pause_global": "⏸️",
    "resume_global": "▶️",
}


# 기본 Claude tool (Bash / Read / Edit / Write / ...) noise skip 목록.
# helper-tool-progress.sh 와 동일 정책 — 매 turn 다수 발생하는 read-only tool 은 thread 가시화에서 제외.
_NOISY_BUILTIN_TOOLS = frozenset({"Read", "Glob", "Grep", "TaskList", "TaskGet", "TaskCreate", "TaskUpdate"})


def _format_tool_progress(tool_name: str, tool_input: dict) -> str | None:
    """ToolUseBlock 의 tool_name + input 을 사용자 친화 1-line 으로.

    post_discord_message 는 답 자체이므로 progress 표시 X (중복 push 방지).
    기본 Claude tool (Bash / Edit / Write / ...) 은 helper-tool-progress.sh 와
    동일한 포맷 분기로 핵심 파라미터까지 노출 (#1356 — 사용자 가시 디테일).
    """
    if tool_name == "post_discord_message":
        return None  # 답은 _push_agent_reply 가 처리 — 중복 X

    # 기본 Claude tool (mcp__nmae__ prefix 없음) — helper-tool-progress.sh 분기 거울.
    if not tool_name.startswith("mcp__nmae__"):
        if tool_name in _NOISY_BUILTIN_TOOLS:
            return None
        if tool_name == "Bash":
            command = str(tool_input.get("command", "")).splitlines()[0][:100]
            return f"💬 Bash: {command}"
        if tool_name in ("Edit", "Write", "NotebookEdit"):
            from os.path import basename
            file_path = basename(str(tool_input.get("file_path", "?")))
            return f"✏️ {tool_name}: {file_path}"
        if tool_name in ("WebFetch", "WebSearch"):
            target = str(tool_input.get("url") or tool_input.get("query") or "")[:100]
            return f"🌐 {tool_name}: {target}"
        if tool_name in ("Agent", "Task"):
            desc = str(tool_input.get("description", ""))[:100]
            return f"🤖 {tool_name}: {desc}"
        if tool_name == "ToolSearch":
            query_text = str(tool_input.get("query", ""))[:100]
            return f"🔍 ToolSearch: {query_text}"
        return f"🛠️ {tool_name}"

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


def _load_nmae_system_prompt() -> str:
    """nmae-role.md (repo SoT, NCP symlink ~/.mobruji/nmae-role.md) 에서 로드.

    2026-05-29 Phase D STRICT 룰 (자동 위임 폐기) — agent.py 에 하드코딩하면
    nmae-role.md 와 분리되어 룰 drift 발생 (사용자 정정: agent 가 자율 directive
    등록 시도). 단일 SoT 로 통일.

    fallback: file 부재 시 minimal STRICT 룰 (자동 위임 폐기 핵심만).
    """
    from pathlib import Path as _Path
    for candidate in (
        _Path.home() / ".mobruji" / "nmae-role.md",
        _Path(__file__).resolve().parent.parent / "discord-daemon" / "nmae-role.md",
    ):
        try:
            if candidate.exists():
                return candidate.read_text(encoding="utf-8")
        except OSError:
            continue
    return (
        "[STRICT] 너는 mobruji nmae. 사용자가 명시 등록 (events 'directive_approved') "
        "한 directive 만 처리. 사용자 메시지 직접 처리 X — 단순 답 또는 '📌 누르세요' "
        "안내. launch_subagent 는 directive_approved event 만 trigger."
    )


NMAE_SYSTEM_PROMPT = _load_nmae_system_prompt()


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

    # 2026-05-29 fix — invalid event (fake user_id / channel_id) drop.
    # events 테이블에 외부 INSERT 된 stale test data (user_id=111, channel=999 등)
    # 가 agent_loop 의 SDK query 호출을 방해 → 실제 사용자 메시지 처리 차단.
    # ALLOWED_USER_IDS env (bot.py 와 같은 .env) 기준 — 그 외 drop.
    import os as _os
    allowed_raw = _os.environ.get("ALLOWED_USER_IDS", "")
    if allowed_raw:
        allowed = {x.strip() for x in allowed_raw.split(",") if x.strip()}
        if str(user_id) not in allowed:
            logger.warning(
                "user_message drop: user_id=%s not in ALLOWED_USER_IDS (channel=%s body=%r)",
                user_id, channel_id, body[:60],
            )
            return

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
        model="claude-opus-4-8",
        system_prompt=NMAE_SYSTEM_PROMPT,
        permission_mode="acceptEdits",
        mcp_servers={"nmae": _get_mcp_server()},
        allowed_tools=_get_allowed_tools(),
    )

    thread_directive = (
        f"답 push 시 thread_id='{thread_id}' 사용." if thread_id
        else f"답 push 시 reply_to_msg_id='{message_id}' 사용 (thread 미생성)."
    )
    # E2 (2026-05-29) — forum thread 안 메시지면 context 명시.
    forum_kind = payload.get("forum_kind", "main")
    directive_id_ctx = payload.get("directive_id", "")
    forum_context = ""
    if forum_kind and forum_kind != "main":
        ctx_lines = [f"\n[forum context] 이 메시지는 **{forum_kind}** forum 의 thread 안 사용자 코멘트입니다."]
        if directive_id_ctx:
            ctx_lines.append(f"  - 매핑된 directive_id: {directive_id_ctx}")
            ctx_lines.append(
                "  - 사용자가 이 directive 의 진행 / sub-agent 작업에 대한 코멘트 / 정정 / 질문 가능."
            )
        else:
            ctx_lines.append(
                f"  - directive 매핑 미존재. 단순 {forum_kind} 사이클 thread 안 사용자 코멘트."
            )
        ctx_lines.append("  - 답은 같은 thread 안에서 (thread_id 명시 유지).")
        forum_context = "\n".join(ctx_lines)

    user_prompt = (
        f"[사용자 메시지] (message_id={message_id}, channel_id={channel_id}, user_id={user_id})\n\n"
        f"{body}\n{forum_context}\n\n"
        f"**STRICT 의무 (2026-05-29)**: 위 메시지에 **반드시** "
        f"mcp__nmae__post_discord_message tool 호출로 답하세요. "
        f"답 텍스트만 생성하고 tool 호출 안 하면 사용자에게 안 보임 = 사고.\n\n"
        f"- channel_id='{channel_id}'\n"
        f"- {thread_directive}\n"
        f"- 답이 짧아도 (예: \"OK\", \"확인했습니다\") 반드시 tool 호출.\n"
        f"- 작업 등록 의도면 \"이 메시지를 할 일로 등록하시려면 📌 reaction 부탁드립니다\" 라고 답 (자율 등록 X)."
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
    # (#1415) 지시 forum thread 태그 🔵 진행 중 → 🟢 완료 + board + body PATCH.
    tc.set_directive_forum_status(str(directive_id), "completed", pr_url=pr_url or "")


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

처리 (#1388 — 즉시 launch 폐지, 큐 적재):
1. 위 규칙으로 cycle 결정.
2. **enqueue_work tool 호출** — directive_id, cycle, title, task, priority 인자.
   - **launch_subagent 직접 호출 금지**. 사이클이 busy 여도 큐에 쌓이고 dispatcher 가
     사이클 idle 시 자동 launch 한다 (사용자 정정 2026-05-31: "큐에 쌓아 동시·효율 처리").
   - priority: description 에 🔴 / 시급 / 긴급 표현이 있으면 10, 아니면 0.
3. enqueue_work 가 directive-board status 갱신 + 지시 thread 에 "큐 N번째 적재" 댓글을
   자동으로 남긴다 (사용자 가시 지표). 별도 post_discord_message 불필요.

사용자 메시지 직접 처리 X (메시지는 이미 적재 완료 — 큐 적재만).
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

    # (#1388) directive 를 agent state 에 mirror — bot.py 📌 flow 로 등록된 directive
    # 는 agent state 에 없어, enqueue_work / launch_subagent 의 directive 조회가
    # 실패(ValueError "directive not found")하던 사고 동시 fix. 이미 있으면 thread_id 만 보강.
    dir_key = f"directive:{directive_id}"
    existing = ev.get_state(dir_key)
    if existing is None:
        ev.set_state(dir_key, {
            "directive_id": directive_id,
            "summary": summary,
            "status": "polished",
            "thread_id": payload.get("thread_id") or None,
            "assigned_cycle": cycle_hint or None,
            "delegation_reason": None,
            "pr_url": None,
            "closed_reason": None,
        })
    elif not existing.get("thread_id") and payload.get("thread_id"):
        existing["thread_id"] = payload.get("thread_id")
        ev.set_state(dir_key, existing)

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
        model="claude-opus-4-8",
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
        # (#1415) directive_approved 는 "cycle 판단 + enqueue_work" 내부 처리일 뿐 —
        # raw tool progress (🔍 ToolSearch / 📥 enqueue_work) 를 지시 thread 로
        # 스트리밍하면 "로그만 써놓고 감" 노이즈 (사용자 정정 2026-05-31). progress
        # 억제(thread="") — 사용자 가시 보고는 enqueue_directive 의 "→ {cycle} 큐 적재"
        # 댓글 + 지시 forum 태그 전이(🟡→🔵)가 담당.
        dir_channel_id = payload.get("channel_id", "")
        async for message in query(prompt=prompt, options=options):
            await _emit_progress_from_sdk_message(message, "", dir_channel_id)
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
            events = ev.read_unconsumed_events(
            "agent", limit=EVENTS_BATCH_SIZE, kinds=tuple(AGENT_EVENT_KINDS),
        )
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

        # (#1388) work-queue dispatcher — 매 tick 사이클 idle 체크 후 큐 다음 항목 launch
        # + stale in_flight 회복. graceful — 실패해도 loop 차단 X.
        try:
            import tools_queue as tq
            launched = tq.dispatch_once()
            if launched:
                logger.info("work-queue dispatched: %s",
                            [{"cycle": x["cycle"], "directive_id": x["directive_id"]} for x in launched])
                # (#1396) 실제 sub-agent 실행 — flag on 일 때만 (기본 off = 부기-only).
                import subagent_runner as sr
                if sr.exec_enabled():
                    for item in launched:
                        _t = asyncio.create_task(sr.run_subagent_execution(
                            item["cycle"], item["directive_id"],
                            item.get("title", ""), item.get("task", ""),
                            item.get("thread_id", ""),
                        ))
                        _EXEC_TASKS.add(_t)
                        _t.add_done_callback(_EXEC_TASKS.discard)
        except Exception as exc:  # noqa: BLE001
            logger.warning("work-queue dispatch_once 실패: %r", exc)

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
