"""SDK @tool wrapper — 12 tool 을 Claude Agent SDK 의 tool primitive 로 노출.

tools_*.py 의 sync function 을 async @tool decorator 로 wrap.

SDK API (2026-05-29 확인):
- @tool(name, description, input_schema) decorator
- input_schema = dict[str, type] (예: {"channel_id": str, "body": str})
- handler async function 가 args dict 받음
- return = {"content": [{"type": "text", "text": "..."}]}
"""

from __future__ import annotations

import json
from typing import Any

try:
    from claude_agent_sdk import tool
except ImportError:  # CI / 개발 환경에서 SDK 미설치 시 graceful
    def tool(name: str, description: str, input_schema: dict):  # type: ignore[no-redef]
        def decorator(fn):
            return fn
        return decorator

import events as ev
import tools_cycle as tc
import tools_discord as td
import tools_pause as tp
import tools_subagent as ts


def _wrap_result(result: dict[str, Any]) -> dict[str, Any]:
    """tools_*.py 결과 dict → SDK 의 tool result format."""
    return {"content": [{"type": "text", "text": json.dumps(result, ensure_ascii=False)}]}


def _wrap_error(exc: Exception) -> dict[str, Any]:
    """예외 → SDK 의 tool error format."""
    return {
        "content": [{"type": "text", "text": f"ERROR: {type(exc).__name__}: {exc}"}],
        "is_error": True,
    }


# ─── 1. post_discord_message ─────────────────────────────────────────────────


@tool(
    name="post_discord_message",
    description=(
        "Discord 채널 또는 thread 에 message push. "
        "선택지 묻는 케이스 (cycle 결정, 우선순위 등) 는 `choices` 인자에 "
        "최대 10 선택지 list 전달 — bot 가 keycap reaction (1️⃣–🔟) 미리 부착, "
        "사용자 tap 시 그 value 가 새 user_message 로 들어옴. "
        "**directive 등록 dialogue 케이스 (📌 → 사용자에게 할 일 등록 확인)** "
        "는 `dialogue_style='register'` 명시 — bot 가 keycap 대신 "
        "⭕ 등록 / ✏️ 수정 / 🗑️ 제거 3 button 부착. choices 는 정확히 3개 "
        "(`['등록','수정','제거']`) 로 보내고 body 는 '이 지시를 할 일로 "
        "등록할까요?' 같이 자연 한국어. 단순 답이면 choices 생략."
    ),
    input_schema={
        "channel_id": str,
        "body": str,
        "reply_to_msg_id": str,
        "thread_id": str,
        "choices": list,  # optional — 선택지 list (str). 최대 10.
        "dialogue_style": str,  # optional — "register" 또는 "default".
    },
)
async def post_discord_message(args: dict[str, Any]) -> dict[str, Any]:
    try:
        # B안 가시화 + 선택지 UI (2026-05-29) — choices payload 에 포함.
        # bot.py _push_agent_reply 가 choices + dialogue_style 보고 emoji 부착.
        choices_raw = args.get("choices")
        choices = (
            [str(c)[:80] for c in choices_raw[:10]]
            if isinstance(choices_raw, list) and choices_raw
            else None
        )
        result = td.post_discord_message(
            args["channel_id"],
            args["body"],
            reply_to_msg_id=args.get("reply_to_msg_id") or None,
            thread_id=args.get("thread_id") or None,
            choices=choices,
            dialogue_style=args.get("dialogue_style") or None,
        )
        return _wrap_result(result)
    except Exception as exc:  # noqa: BLE001
        return _wrap_error(exc)


# ─── 2. forum_create_thread ──────────────────────────────────────────────────


@tool(
    name="forum_create_thread",
    description="Forum 채널 에 신규 thread. register_directive_pending 용도만 (자유 생성 금지).",
    input_schema={"forum_id": str, "title": str, "body": str, "tags": list},
)
async def forum_create_thread(args: dict[str, Any]) -> dict[str, Any]:
    try:
        result = td.forum_create_thread(
            args["forum_id"], args["title"], args["body"], tags=args.get("tags"),
        )
        return _wrap_result(result)
    except Exception as exc:  # noqa: BLE001
        return _wrap_error(exc)


# ─── 3. forum_comment ────────────────────────────────────────────────────────


@tool(
    name="forum_comment",
    description="Forum thread 안 comment. thread_id 필수 (별 thread 생성 X).",
    input_schema={"thread_id": str, "body": str},
)
async def forum_comment(args: dict[str, Any]) -> dict[str, Any]:
    try:
        result = td.forum_comment(args["thread_id"], args["body"])
        return _wrap_result(result)
    except Exception as exc:  # noqa: BLE001
        return _wrap_error(exc)


# ─── 4. forum_retag ──────────────────────────────────────────────────────────


@tool(
    name="forum_retag",
    description="Forum thread tag 변경 (🟡 → ⏳ → ✅ lifecycle).",
    input_schema={"thread_id": str, "tag_name": str},
)
async def forum_retag(args: dict[str, Any]) -> dict[str, Any]:
    try:
        result = td.forum_retag(args["thread_id"], args["tag_name"])
        return _wrap_result(result)
    except Exception as exc:  # noqa: BLE001
        return _wrap_error(exc)


# ─── 5. forum_edit_starter ───────────────────────────────────────────────────


@tool(
    name="forum_edit_starter",
    description=(
        "Forum thread starter body PATCH. "
        "**template 양식 의무** (PR F, docs/features/forum-starter-template-guard.md §5-2 SoT) — "
        "기존 starter body 를 read 한 뒤 6 marker 유지한 채 update. "
        "marker = 📌 또는 🛠️ title / 💬 본문(원본) / 🆔 id / 📋 진행 / 🔖 관련 / footer (---/_갱신:). "
        "PASS_THRESHOLD = 5/6 — 1 marker 누락 허용 (graceful), 2개+ 누락 시 bot.py 가 graceful reject + DIGEST alert + cycle thread 댓글 + violation jsonl 박제. "
        "정상 예 (5/6 또는 6/6): cat <<EOF 안 6 marker 모두 유지한 milestone PATCH "
        "(📌 **title** / 💬 원본 / 🆔 id / 📋 진행 [x] / 🔖 관련 PR #1234 / --- _갱신: ts_). "
        "위배 예 (0/6): 'PR #1234 작업 끝' (모든 marker 없음 — 즉시 reject)."
    ),
    input_schema={"thread_id": str, "body": str},
)
async def forum_edit_starter(args: dict[str, Any]) -> dict[str, Any]:
    try:
        result = td.forum_edit_starter(args["thread_id"], args["body"])
        return _wrap_result(result)
    except Exception as exc:  # noqa: BLE001
        return _wrap_error(exc)


# ─── 6. launch_subagent ──────────────────────────────────────────────────────


@tool(
    name="launch_subagent",
    description=(
        "be/fe/rev/plan 사이클 launch. directive_id 인자 필수. "
        "paused 모드 또는 in_flight cycle 이면 ERROR. pending_thread_id 자동 조회."
    ),
    input_schema={
        "cycle": str,
        "directive_id": str,
        "title": str,
        "task": str,
    },
)
async def launch_subagent(args: dict[str, Any]) -> dict[str, Any]:
    try:
        result = ts.launch_subagent(
            args["cycle"], args["directive_id"], args["title"], args["task"],
        )
        return _wrap_result(result)
    except Exception as exc:  # noqa: BLE001
        return _wrap_error(exc)


# ─── 6-b. enqueue_work (#1388) ────────────────────────────────────────────────


@tool(
    name="enqueue_work",
    description=(
        "directive 를 cycle 작업 큐에 적재 (즉시 launch 폐지 — dispatcher 가 사이클 "
        "idle 시 자동 시작). 사이클이 busy 여도 ERROR 아님 — 대기열에 쌓인다. "
        "priority: 🔴 시급은 10, 기본 0. cycle: be/fe/rev/plan. "
        "directive_approved 처리는 launch_subagent 직접 호출 금지 — 본 tool 사용."
    ),
    input_schema={
        "cycle": str,
        "directive_id": str,
        "title": str,
        "task": str,
        "priority": int,  # optional — 🔴 시급=10, 기본 0
    },
)
async def enqueue_work(args: dict[str, Any]) -> dict[str, Any]:
    import tools_queue as tq
    try:
        directive = ev.get_state(f"directive:{args['directive_id']}") or {}
        result = tq.enqueue_directive(
            args["cycle"], args["directive_id"], args["title"], args["task"],
            thread_id=directive.get("thread_id") or "",
            priority=int(args.get("priority") or 0),
        )
        return _wrap_result(result)
    except Exception as exc:  # noqa: BLE001
        return _wrap_error(exc)


# ─── 7. register_directive_pending ───────────────────────────────────────────


@tool(
    name="register_directive_pending",
    description="📌 등록 — directive entry + 🟡 forum thread.",
    input_schema={"directive_id": str, "summary": str, "cycle_hint": str},
)
async def register_directive_pending(args: dict[str, Any]) -> dict[str, Any]:
    try:
        result = tc.register_directive_pending(
            args["directive_id"], args["summary"],
            cycle_hint=args.get("cycle_hint") or None,
        )
        return _wrap_result(result)
    except Exception as exc:  # noqa: BLE001
        return _wrap_error(exc)


# ─── 8. update_directive_status ──────────────────────────────────────────────


@tool(
    name="update_directive_status",
    description=(
        "directive status transition. closed → closed_reason 필수, "
        "assigned + plan → delegation_reason 필수."
    ),
    input_schema={
        "directive_id": str,
        "new_status": str,
        "pr_url": str,
        "closed_reason": str,
        "thread_id": str,
        "assigned_cycle": str,
        "delegation_reason": str,
    },
)
async def update_directive_status(args: dict[str, Any]) -> dict[str, Any]:
    try:
        result = tc.update_directive_status(
            args["directive_id"], args["new_status"],  # type: ignore[arg-type]
            pr_url=args.get("pr_url") or None,
            closed_reason=args.get("closed_reason") or None,
            thread_id=args.get("thread_id") or None,
            assigned_cycle=args.get("assigned_cycle") or None,  # type: ignore[arg-type]
            delegation_reason=args.get("delegation_reason") or None,
        )
        return _wrap_result(result)
    except Exception as exc:  # noqa: BLE001
        return _wrap_error(exc)


# ─── 9. get_cycle_state ──────────────────────────────────────────────────────


@tool(
    name="get_cycle_state",
    description="be/fe/rev/plan cycle state read.",
    input_schema={"cycle": str},
)
async def get_cycle_state(args: dict[str, Any]) -> dict[str, Any]:
    try:
        result = tc.get_cycle_state(args["cycle"])  # type: ignore[arg-type]
        return _wrap_result(result or {"status": "idle"})
    except Exception as exc:  # noqa: BLE001
        return _wrap_error(exc)


# ─── 10. set_cycle_state ─────────────────────────────────────────────────────


@tool(
    name="set_cycle_state",
    description="cycle state partial update.",
    input_schema={
        "cycle": str,
        "status": str,
        "current_thread_id": str,
        "current_pr_url": str,
        "current_directive_id": str,
    },
)
async def set_cycle_state(args: dict[str, Any]) -> dict[str, Any]:
    try:
        result = tc.set_cycle_state(
            args["cycle"],  # type: ignore[arg-type]
            status=args.get("status") or None,  # type: ignore[arg-type]
            current_thread_id=args.get("current_thread_id") or None,
            current_pr_url=args.get("current_pr_url") or None,
            current_directive_id=args.get("current_directive_id") or None,
        )
        return _wrap_result(result)
    except Exception as exc:  # noqa: BLE001
        return _wrap_error(exc)


# ─── 11. pause_global ────────────────────────────────────────────────────────


@tool(
    name="pause_global",
    description="사이클 정지. launch_subagent 가 reject. 사용자 명령 시만.",
    input_schema={"reason": str},
)
async def pause_global(args: dict[str, Any]) -> dict[str, Any]:
    try:
        result = tp.pause_global(reason=args.get("reason") or None)
        return _wrap_result(result)
    except Exception as exc:  # noqa: BLE001
        return _wrap_error(exc)


# ─── 12. resume_global ───────────────────────────────────────────────────────


@tool(
    name="resume_global",
    description="paused 해제.",
    input_schema={},
)
async def resume_global(args: dict[str, Any]) -> dict[str, Any]:
    try:
        result = tp.resume_global()
        return _wrap_result(result)
    except Exception as exc:  # noqa: BLE001
        return _wrap_error(exc)


ALL_TOOLS = [
    post_discord_message,
    forum_create_thread,
    forum_comment,
    forum_retag,
    forum_edit_starter,
    launch_subagent,
    enqueue_work,
    register_directive_pending,
    update_directive_status,
    get_cycle_state,
    set_cycle_state,
    pause_global,
    resume_global,
]
