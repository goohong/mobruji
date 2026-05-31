"""work-queue orchestration — enqueue (적재 + board + 지시 thread 댓글) + dispatch
(사이클 idle 시 다음 항목 launch). (#1388)

`work_queue.py` = pure 큐 ops. 본 모듈 = 큐 ↔ directive-board(state) ↔ Discord
댓글 ↔ launch_subagent 연결. agent_loop 가 매 tick `dispatch_once` 호출.

설계: docs/features/directive-work-queue.md
"""

from __future__ import annotations

import json
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
GH_TIMEOUT_SECONDS = 25
# (#1390) launch 연속 실패 N회 초과 시 큐에서 제외 — 무한 재시도 + head-of-line block 차단.
MAX_LAUNCH_ATTEMPTS = 3
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


def enqueue_rev_for_pr_if_any(source_cycle: str, worktree: Any) -> str | None:
    """sub-agent 완료 후 그 워크트리 branch 의 열린 PR 을 찾아 rev 큐에 자동 적재 (#1403).

    자율 루프 완성: 구현 sub-agent → PR → **rev 자동 감사** → reviewed:claude → 자동 머지.

    가드:
      - source_cycle == 'rev' → skip (rev 는 PR 안 만들고 자기 감사 무한 루프 방지).
      - PR 에 이미 reviewed:claude → skip.
      - 멱등: directive_id = rev-pr-<N> (wq.enqueue 중복 차단).
      - branch 가 develop/main/HEAD → skip.

    blocking subprocess(git/gh) — 호출자(run_subagent_execution)가 asyncio.to_thread 로 감쌀 것.
    Returns: 적재한 PR 번호(str) 또는 None.
    """
    if source_cycle == "rev":
        return None
    wt = str(worktree)
    try:
        branch = subprocess.run(
            ["git", "-C", wt, "rev-parse", "--abbrev-ref", "HEAD"],
            capture_output=True, text=True, timeout=10, check=False,
        ).stdout.strip()
        if not branch or branch in ("develop", "main", "HEAD"):
            return None
        out = subprocess.run(
            ["gh", "pr", "list", "--head", branch, "--state", "open",
             "--json", "number,labels"],
            capture_output=True, text=True, timeout=GH_TIMEOUT_SECONDS,
            check=False, cwd=wt,
        )
        prs = json.loads(out.stdout or "[]")
        if not prs:
            return None
        pr = prs[0]
        num = pr.get("number")
        labels = [lbl.get("name") for lbl in pr.get("labels", [])]
        if not num or "reviewed:claude" in labels:
            return None
        did = f"rev-pr-{num}"
        if ev.get_state(f"directive:{did}") is None:
            ev.set_state(f"directive:{did}", {
                "directive_id": did, "summary": f"rev: PR #{num}",
                "status": "polished", "thread_id": None, "assigned_cycle": "rev",
                "delegation_reason": None, "pr_url": None, "closed_reason": None,
            })
        enqueue_directive(
            "rev", did, f"rev 감사 — PR #{num}",
            f"PR #{num} (branch {branch}) 3단계 e2e 감사 + reviewed:claude 판정/findings 블록. "
            f"구현은 하지 말고 감사·보고만.",
        )
        logger.info("rev auto-trigger: PR #%s (source=%s) → rev 큐 적재", num, source_cycle)
        return str(num)
    except (OSError, subprocess.TimeoutExpired, json.JSONDecodeError) as exc:
        logger.warning("rev auto-trigger 실패 source=%s exc=%r", source_cycle, exc)
        return None


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
            # (#1390) N회 연속 실패 → 큐에서 제외(격리) — 무한 재시도 + head-of-line block 차단.
            attempts = int(directive.get("launch_attempts", 0)) + 1
            directive["launch_attempts"] = attempts
            ev.set_state(f"directive:{directive_id}", directive)
            logger.warning(
                "work-queue launch 실패 cycle=%s directive=%s 시도=%d/%d exc=%r",
                cycle, directive_id, attempts, MAX_LAUNCH_ATTEMPTS, exc,
            )
            if attempts >= MAX_LAUNCH_ATTEMPTS:
                wq.dequeue(cycle, directive_id)
                _comment(
                    report_thread,
                    f"❌ **{cycle}** launch {attempts}회 연속 실패 — 큐에서 제외했습니다 "
                    f"(뒤 항목 진행). 수동 조치 필요. 사유: {str(exc)[:120]}.",
                )
            else:
                _comment(
                    report_thread,
                    f"❌ **{cycle}** launch 실패 ({attempts}/{MAX_LAUNCH_ATTEMPTS}) — "
                    f"큐 유지, 다음 tick 재시도. 사유: {str(exc)[:120]}.",
                )
            continue

        # 성공 — 큐에서 제거 + 시작시각 기록 + board 갱신 + 댓글.
        wq.dequeue(cycle, directive_id)
        if directive.get("launch_attempts"):  # 이전 실패 카운터 reset
            directive["launch_attempts"] = 0
            ev.set_state(f"directive:{directive_id}", directive)
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
