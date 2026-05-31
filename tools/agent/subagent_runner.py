"""NCP sub-agent 실제 실행 엔진 (#1396, Phase 2).

배경: `tools_subagent.launch_subagent` 는 set-active + cycle forum 부기만 하고
"Agent 도구 launch 진행하세요" 로 외부 호출자를 기다렸다 (Phase 1.3 stub). NCP
이벤트 경로엔 그 실제 실행 단계가 없어 자율 사이클이 일감을 처리하지 못했다.

본 모듈은 그 마지막 고리 — cycle 워크트리에서 role system prompt 를 입힌
`claude -p` sub-agent 를 **비동기 subprocess** 로 실제 실행하고, 완료 시
`mark_subagent_completed` 로 lock 해제 + cycle forum 완료 보고.

안전 가드: env `MOBRUJI_SUBAGENT_EXEC` 가 "1" 일 때만 실제 spawn (기본 off →
기존 부기-only 동작 유지). flag off 배포 → 검증 후 on 단계적 rollout.

설계: docs/features/directive-work-queue.md (dispatch), 이슈 #1396.
"""

from __future__ import annotations

import asyncio
import logging
import os
import shlex
from pathlib import Path

import events as ev
import tools_discord as td
import tools_subagent as ts

logger = logging.getLogger("agent.subagent_runner")

WORKTREE_ROOT = Path("/home/mobruji")
WORKTREE_PREFIX = "mobruji-"
# sub-agent 1 task 최대 실행 시간 — 초과 시 kill + 실패 보고 + lock 해제.
EXEC_TIMEOUT_SECONDS = 45 * 60
DEFAULT_MODEL = "claude-opus-4-8"


def exec_enabled() -> bool:
    """실제 sub-agent spawn 활성 여부 (기본 off — 안전 배포)."""
    return os.environ.get("MOBRUJI_SUBAGENT_EXEC", "").strip() == "1"


def worktree_path(cycle: str) -> Path:
    return WORKTREE_ROOT / f"{WORKTREE_PREFIX}{cycle}"


def role_prompt(cycle: str, worktree: Path | None = None) -> str:
    """`<worktree>/.claude/agents/<cycle>.md` 의 frontmatter 제거한 body.

    파일 부재 시 최소 fallback role 문자열.
    """
    wt = worktree or worktree_path(cycle)
    path = wt / ".claude" / "agents" / f"{cycle}.md"
    try:
        raw = path.read_text(encoding="utf-8")
    except OSError:
        return f"너는 mobruji {cycle} 사이클 sub-agent다. CLAUDE.md 룰을 따른다."
    # frontmatter (--- ... ---) 제거 → body.
    if raw.startswith("---"):
        parts = raw.split("---", 2)
        if len(parts) == 3:
            return parts[2].strip()
    return raw.strip()


def build_task_prompt(
    cycle: str,
    directive_id: str,
    title: str,
    task: str,
    thread_id: str,
    *,
    discord_reply_bin: str = "/home/mobruji/mobruji-bridge/tools/discord-daemon/discord-reply.sh",
) -> str:
    """sub-agent claude -p 에 줄 작업 prompt. 진행/완료 forum 보고 강제 지시 포함."""
    forum_line = (
        f"- **진행 보고는 cycle forum thread `{thread_id}` 에** (우선): "
        f"`{discord_reply_bin} --forum-comment {thread_id} \"<메시지>\"`.\n"
        f"  - milestone 만 (시작 / 핵심 발견 / PR 링크 / 완료) — 매 step 중계 금지.\n"
        f"  - **각 보고 2-4줄 이내, 줄바꿈으로 구조화, wall-of-text 금지.** "
        f"긴 설명·근거는 PR 본문에 적고 forum 엔 요약 1-2줄 + 링크. 정중체."
        if thread_id
        else "- forum thread id 미지정 — PR/이슈 링크로 결과 보고 (짧게, 줄바꿈)."
    )
    return (
        f"[자율 사이클 작업 — {cycle}]\n"
        f"directive_id: {directive_id}\n"
        f"제목: {title}\n"
        f"작업: {task}\n\n"
        "지시:\n"
        f"- 너는 {cycle} 사이클 sub-agent. CLAUDE.md + 역할 룰 준수.\n"
        f"{forum_line}\n"
        "- 품질 게이트 통과 후 PR 생성 (base develop). AskUserQuestion 금지 — 자율 진행.\n"
        "- 작업 완료 시 한 줄 완료 보고로 끝낸다."
    )


def _claude_argv(prompt: str, role: str) -> list[str]:
    """CLAUDE_BIN (multi-token 가능) + -p + role system prompt + model."""
    base = shlex.split(os.environ.get("CLAUDE_BIN") or "/usr/bin/claude")
    argv = list(base)
    if "--model" not in argv:
        argv += ["--model", DEFAULT_MODEL]
    argv += ["-p", prompt, "--append-system-prompt", role]
    return argv


async def run_subagent_execution(
    cycle: str,
    directive_id: str,
    title: str,
    task: str,
    thread_id: str,
    *,
    timeout: float = EXEC_TIMEOUT_SECONDS,
) -> None:
    """cycle 워크트리에서 claude -p sub-agent 비동기 실행 → 완료 시 lock 해제.

    fire-and-forget asyncio task 로 호출 (agent_loop). 예외/타임아웃 graceful —
    어떤 경우에도 `mark_subagent_completed` 로 lock 해제 (영구 점유 방지).
    """
    wt = worktree_path(cycle)
    logger.info("subagent exec 시작: cycle=%s directive=%s wt=%s", cycle, directive_id, wt)
    try:
        # (#1398 rev 🟡-1) argv 빌드(shlex.split CLAUDE_BIN)도 try 안에서 — unbalanced
        # quote 등 ValueError 가 finally 밖으로 탈출해 lock 미해제되는 사고 차단.
        prompt = build_task_prompt(cycle, directive_id, title, task, thread_id)
        role = role_prompt(cycle, wt)
        argv = _claude_argv(prompt, role)
        proc = await asyncio.create_subprocess_exec(
            *argv, cwd=str(wt),
            stdout=asyncio.subprocess.DEVNULL,
            stderr=asyncio.subprocess.PIPE,
        )
        try:
            _, stderr = await asyncio.wait_for(proc.communicate(), timeout=timeout)
            rc = proc.returncode
            logger.info("subagent exec 종료: cycle=%s rc=%s", cycle, rc)
            if rc != 0 and thread_id:
                _safe_comment(
                    thread_id,
                    f"⚠️ {cycle} sub-agent 비정상 종료 (rc={rc}). 로그 확인 필요.",
                )
            if rc == 0:
                # (#1403) 구현 sub-agent 가 만든 PR → rev 자동 감사 큐 적재 (자율 루프 완성).
                # blocking git/gh → to_thread 로 event loop 비차단. rev 자신은 skip.
                try:
                    import tools_queue as tq
                    pr_num = await asyncio.to_thread(
                        tq.enqueue_rev_for_pr_if_any, cycle, wt,
                    )
                    if pr_num:
                        logger.info("rev auto-trigger: %s → PR #%s rev 큐 적재", cycle, pr_num)
                except Exception as exc:  # noqa: BLE001
                    logger.warning("rev auto-trigger 호출 실패 cycle=%s exc=%r", cycle, exc)
        except asyncio.TimeoutError:
            proc.kill()
            logger.warning("subagent exec 타임아웃 kill: cycle=%s (%.0fs)", cycle, timeout)
            if thread_id:
                _safe_comment(thread_id, f"⏱ {cycle} sub-agent {timeout/60:.0f}분 타임아웃 — 중단.")
    except Exception as exc:  # noqa: BLE001
        logger.warning("subagent exec spawn 실패: cycle=%s exc=%r", cycle, exc)
        if thread_id:
            _safe_comment(thread_id, f"❌ {cycle} sub-agent 실행 실패: {str(exc)[:150]}")
    finally:
        # 어떤 경우에도 lock 해제 — dispatcher 가 큐 다음 항목 진행.
        try:
            ts.mark_subagent_completed(cycle)
            started = ev.get_state("in_flight_started") or {}
            started.pop(cycle, None)
            ev.set_state("in_flight_started", started)
        except Exception as exc:  # noqa: BLE001
            logger.warning("subagent exec lock 해제 실패: cycle=%s exc=%r", cycle, exc)


def _safe_comment(thread_id: str, body: str) -> None:
    try:
        td.forum_comment(thread_id, body)
    except Exception as exc:  # noqa: BLE001
        logger.warning("subagent exec forum_comment 실패 thread=%s exc=%r", thread_id, exc)
