"""work-queue orchestration — enqueue (적재 + board + 지시 thread 댓글) + dispatch
(사이클 idle 시 다음 항목 launch). (#1388)

`work_queue.py` = pure 큐 ops. 본 모듈 = 큐 ↔ directive-board(state) ↔ Discord
댓글 ↔ launch_subagent 연결. agent_loop 가 매 tick `dispatch_once` 호출.

설계: docs/features/directive-work-queue.md
"""

from __future__ import annotations

import logging
import os
import subprocess
from datetime import datetime, timedelta, timezone
from typing import Any

import events as ev
import tools_cycle as tc
import tools_discord as td
import tools_subagent as ts
import work_queue as wq
from state import CycleAlreadyRunningError, PausedError

logger = logging.getLogger("agent.work_queue")

IN_FLIGHT_STARTED_KEY = "in_flight_started"
STALE_THRESHOLD = timedelta(hours=2)
CYCLES = ("be", "fe", "rev", "plan")
# (#1401) cycle forum thread 동기 생성 — discord-reply.sh 가 thread_id 를 stdout 으로
# 즉시 반환 (forum_create_thread 는 비동기 event 라 id 즉시 미수신). 자율 경로가
# 📌 dialogue thread 대신 전용 cycle forum thread 에 보고하게 하는 핵심.
DISCORD_REPLY_BIN = os.environ.get(
    "DISCORD_REPLY_BIN",
    "/home/mobruji/mobruji-bridge/tools/discord-daemon/discord-reply.sh",
)


def _create_cycle_thread(cycle: str, title: str, body: str) -> str | None:
    """cycle forum 에 thread 신설 → thread_id 반환. 실패 시 None (graceful).

    discord-reply.sh --forum-post-auto-tag <cycle> 동기 호출 (서비스 env 의 토큰/
    forum id 상속). stdout 마지막 숫자줄 = thread_id (snowflake).
    """
    try:
        result = subprocess.run(  # noqa: S603 — 고정 경로 + argv list
            [DISCORD_REPLY_BIN, "--forum-post-auto-tag", cycle, title[:99], body],
            capture_output=True, text=True, timeout=20, check=False,
        )
        if result.returncode != 0:
            logger.warning(
                "cycle thread 생성 rc=%d cycle=%s stderr=%r",
                result.returncode, cycle, (result.stderr or "")[:200],
            )
            return None
        for line in reversed((result.stdout or "").strip().splitlines()):
            digits = "".join(ch for ch in line if ch.isdigit())
            if len(digits) >= 17:  # Discord snowflake
                return digits
    except (OSError, subprocess.TimeoutExpired) as exc:
        logger.warning("cycle thread 생성 실패 cycle=%s exc=%r", cycle, exc)
    return None


def _cycle_thread_body(cycle: str, directive_id: str, title: str, task: str) -> str:
    """cycle forum thread starter 본문 (6 marker 양식)."""
    return (
        f"🛠️ **{title}**\n\n"
        f"💬 작업\n{task}\n\n"
        f"🆔 사이클: `{cycle}` · directive: `{directive_id}`\n\n"
        f"📋 진행 (🟡 대기)\n- [ ] launch\n- [ ] 구현/분석\n- [ ] PR\n\n"
        f"🔖 관련 *(진행되며 추가)*\n\n---\n_갱신: 적재 시점_"
    )


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def _age_exceeds(ts_iso: str, threshold: timedelta, now: datetime) -> bool:
    try:
        started = datetime.fromisoformat(ts_iso)
    except (ValueError, TypeError):
        return False
    return (now - started) > threshold


def _comment(thread_id: str, body: str) -> None:
    """지시 thread 댓글 — graceful (실패해도 흐름 차단 X)."""
    if not thread_id:
        return
    try:
        td.forum_comment(thread_id, body)
    except Exception as exc:  # noqa: BLE001
        logger.warning("work-queue forum_comment 실패 thread=%s exc=%r", thread_id, exc)


def _set_board_assigned(directive_id: str, cycle: str, reason: str) -> None:
    """directive-board status → assigned(+cycle). graceful (state 없으면 skip)."""
    try:
        tc.update_directive_status(
            directive_id, "assigned",
            assigned_cycle=cycle, delegation_reason=reason or f"{cycle} 위임",
        )
    except Exception as exc:  # noqa: BLE001
        logger.warning(
            "work-queue board 갱신 실패 directive=%s cycle=%s exc=%r",
            directive_id, cycle, exc,
        )


def enqueue_directive(
    cycle: str,
    directive_id: str,
    title: str,
    task: str,
    *,
    thread_id: str = "",
    priority: int = wq.PRIORITY_NORMAL,
) -> dict[str, Any]:
    """적재 + board assigned + 지시 thread 댓글. 멱등 (중복 directive_id no-op)."""
    result = wq.enqueue(
        cycle, directive_id, title, task,
        enqueued_at=_now_iso(), thread_id=thread_id, priority=priority,
    )
    if not result["enqueued"]:
        return result  # 이미 큐/처리 중 — no-op

    # (#1401) 전용 cycle forum thread 신설 → 적재/시작/진행/완료 보고를 여기로 통일
    # (📌 dialogue thread 대신 — 사용자가 be/fe/rev/plan forum 에서 1 task = 1 thread 로 본다).
    cycle_thread = _create_cycle_thread(
        cycle, title, _cycle_thread_body(cycle, directive_id, title, task),
    )
    directive = ev.get_state(f"directive:{directive_id}") or {}
    if cycle_thread:
        directive["cycle_thread_id"] = cycle_thread
        ev.set_state(f"directive:{directive_id}", directive)
    report_thread = cycle_thread or thread_id  # 생성 실패 시 dialogue thread fallback

    _set_board_assigned(directive_id, cycle, task)
    ev.append_event(
        "work_enqueued",
        {"cycle": cycle, "directive_id": directive_id,
         "position": result["position"], "priority": priority,
         "cycle_thread_id": cycle_thread or ""},
    )
    urgent = " 🔴" if priority >= wq.PRIORITY_URGENT else ""
    _comment(
        report_thread,
        f"📥 **{cycle}**{urgent} 큐 {result['position']}번째로 적재했습니다 "
        f"(앞 대기 {result['ahead']}건). 사이클이 비면 자동으로 시작합니다.",
    )
    # 사용자가 보는 지시 thread 에도 cycle 배정 1줄 (cycle thread 와 다를 때만).
    if cycle_thread and thread_id and thread_id != cycle_thread:
        _comment(thread_id, f"→ **{cycle}** 사이클 큐에 적재했습니다 (진행은 {cycle} forum thread 에서).")
    return result


def dispatch_once(*, now: datetime | None = None) -> list[dict[str, Any]]:
    """각 cycle: stale in_flight 회복 + idle 면 큐 다음 항목 launch.

    agent_loop tick 마다 호출. 반환 = 이번 tick 에 launch 된 항목 list (로그용).
    """
    launched: list[dict[str, Any]] = []
    if ev.get_state("paused") is True:
        return launched
    now = now or datetime.now(timezone.utc)
    started: dict[str, str] = ev.get_state(IN_FLIGHT_STARTED_KEY) or {}

    for cycle in CYCLES:
        in_flight = set(ev.get_state("in_flight_agents") or [])

        # stale in_flight 회복 (#1388 P2) — 완료 event 누락으로 lock 영구 점유 차단.
        if cycle in in_flight:
            ts_iso = started.get(cycle)
            if ts_iso and _age_exceeds(ts_iso, STALE_THRESHOLD, now):
                logger.warning(
                    "work-queue stale in_flight 회복: cycle=%s started=%s (>%s)",
                    cycle, ts_iso, STALE_THRESHOLD,
                )
                ts.mark_subagent_completed(cycle)
                started.pop(cycle, None)
                ev.set_state(IN_FLIGHT_STARTED_KEY, started)
            else:
                continue  # busy — 다음 cycle

        nxt = wq.peek_next(cycle)
        if nxt is None:
            continue

        directive_id = nxt["directive_id"]
        # (#1401) 보고 대상 = 전용 cycle forum thread (enqueue 가 directive state 에 저장).
        # 없으면 dialogue thread fallback.
        directive = ev.get_state(f"directive:{directive_id}") or {}
        report_thread = directive.get("cycle_thread_id") or nxt.get("thread_id", "")
        try:
            ts.launch_subagent(
                cycle, directive_id, nxt["title"], nxt["task"],
                cycle_thread_id=directive.get("cycle_thread_id") or None,
            )
        except CycleAlreadyRunningError:
            continue  # race — 다음 tick 재시도
        except PausedError:
            break  # 전역 정지 — 이번 tick 중단
        except Exception as exc:  # noqa: BLE001
            logger.warning(
                "work-queue launch 실패 cycle=%s directive=%s exc=%r — 큐 유지(재시도)",
                cycle, directive_id, exc,
            )
            _comment(
                report_thread,
                f"❌ **{cycle}** launch 실패 — 큐에 유지하고 다음 tick 에 재시도합니다 "
                f"(사유: {str(exc)[:120]}).",
            )
            continue

        # 성공 — 큐에서 제거 + 시작시각 기록 + board 갱신 + 댓글.
        wq.dequeue(cycle, directive_id)
        started[cycle] = _now_iso()
        ev.set_state(IN_FLIGHT_STARTED_KEY, started)
        ev.append_event(
            "work_dispatched", {"cycle": cycle, "directive_id": directive_id},
        )
        _set_board_assigned(directive_id, cycle, nxt.get("task", ""))
        _comment(
            report_thread,
            f"🚀 **{cycle}** 사이클이 시작했습니다 — {nxt.get('title', '')}",
        )
        launched.append({
            "cycle": cycle,
            "directive_id": directive_id,
            "title": nxt.get("title", ""),
            "task": nxt.get("task", ""),
            # exec sub-agent 가 보고할 thread = 전용 cycle forum thread (#1401).
            "thread_id": report_thread,
        })

    return launched
