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
import subprocess
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

# (#1413) sub-agent 작업 보고 양식 — Discord 마크다운, AS-IS/TO-BE (나열 금지,
# 제목/내용 구분, 줄바꿈). 사용자 정정 2026-05-31: "정리라기보다 나열 — AS-IS/TO-BE
# 같은 가독성 양식 필요 + Discord 마크다운으로 제목·내용 구분".
REPORT_TEMPLATE = """\
## <제목> · <✅ 완료 | 🟡 진행 | ⛔ 차단>

**AS-IS** — 원래
- <변경 전 상태 / 무엇이 문제였나>

**TO-BE** — 적용
- <무엇을 어떻게 바꿨나 (진행·차단이면 바꿀 목표)>

**다음** *(진행·차단 시만)*
- <다음 액션 / 차단 사유>

🔗 PR #<N>"""


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
    if thread_id:
        forum_line = (
            f"- **진행/완료 보고는 cycle forum thread `{thread_id}` 에** "
            f"`{discord_reply_bin} --forum-comment {thread_id} \"<메시지>\"`.\n"
            f"  - milestone 마다만 (시작 / 핵심 발견 / PR / 완료) — 매 step 중계 금지.\n"
            f"  - **아래 AS-IS/TO-BE 양식 그대로** (Discord 마크다운 — `##`/`**`/줄바꿈으로 "
            f"제목·섹션 구분, 나열 금지). **코드펜스(```)로 감싸지 말 것** — 마크다운이 렌더되게:\n"
            f"{REPORT_TEMPLATE}\n"
            f"  - 긴 근거·전체 목록은 PR 본문에. forum 은 이 양식 압축본만. 정중체."
        )
    else:
        forum_line = (
            "- forum thread id 미지정 — PR/이슈 링크로 아래 AS-IS/TO-BE 양식 보고:\n"
            f"{REPORT_TEMPLATE}"
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
            if rc != 0:
                # (#1427) 과거엔 stderr 를 캡처만 하고 버려 rc!=0 원인이 journal 에
                # 안 남았다 (rev rc=1 자동 launch 실패 진단 불가). stderr tail 을 로그.
                err_tail = (
                    stderr.decode("utf-8", errors="replace")[-1000:] if stderr else "(stderr 없음)"
                )
                logger.warning(
                    "subagent exec 비정상 종료: cycle=%s rc=%s stderr_tail=%s",
                    cycle, rc, err_tail,
                )
                if thread_id:
                    _safe_comment(
                        thread_id,
                        f"⚠️ {cycle} sub-agent 비정상 종료 (rc={rc}). 로그 확인 필요.",
                    )
            if rc == 0:
                try:
                    await asyncio.to_thread(
                        _on_exec_success, cycle, directive_id, title, thread_id, wt,
                    )
                except Exception as exc:  # noqa: BLE001
                    logger.warning("exec 완료 후처리 실패 cycle=%s exc=%r", cycle, exc)
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


def _find_pr_number(worktree) -> str | None:  # noqa: ANN001
    """워크트리 branch 의 열린 PR 번호 (read-only). 없으면 None."""
    import json as _json
    try:
        branch = subprocess.run(
            ["git", "-C", str(worktree), "rev-parse", "--abbrev-ref", "HEAD"],
            capture_output=True, text=True, timeout=10, check=False,
        ).stdout.strip()
        if not branch or branch in ("develop", "main", "HEAD"):
            return None
        out = subprocess.run(
            ["gh", "pr", "list", "--head", branch, "--state", "open", "--json", "number"],
            capture_output=True, text=True, timeout=25, check=False, cwd=str(worktree),
        )
        prs = _json.loads(out.stdout or "[]")
        return str(prs[0]["number"]) if prs else None
    except (OSError, subprocess.TimeoutExpired, _json.JSONDecodeError):
        return None


def _notify_user_done(
    title: str,
    body: str,
    thread_id: str = "",
    *,
    pr_url: str = "",
    cta: str = "",
) -> None:
    """#모부르지 채널에 작업 알림 (#1417, 문구 정정 #1427). graceful.

    사용자 정정 2026-05-31: 알림이 '감사 후 머지됩니다 / 확인 부탁' 처럼 모호하면 안 됨.
    (1) '감사' jargon 금지 — 'rev(검토)' 로 풀이.
    (2) develop 자동 머지는 사용자 할 일 없음을 분명히 (확인 필요한 건 release 뿐).
    (3) PR 링크 포함 (pr_url).
    (4) 행동 요청(cta)은 정말 필요할 때만 — 불필요한 '확인 부탁' 금지.
    """
    bin_ = "/home/mobruji/mobruji-bridge/tools/discord-daemon/discord-reply.sh"
    guild = os.environ.get("DISCORD_GUILD_ID", "")
    links = []
    if pr_url:
        links.append(f"PR: {pr_url}")
    if guild and thread_id:
        links.append(f"진행: https://discord.com/channels/{guild}/{thread_id}")
    link_block = ("\n" + "\n".join(links)) if links else ""
    cta_block = f"\n{cta}" if cta else ""
    msg = f"✅ {title}\n{body}{link_block}{cta_block}"
    try:
        subprocess.run([bin_, msg], capture_output=True, text=True, timeout=15, check=False)
    except (OSError, subprocess.TimeoutExpired) as exc:
        logger.warning("완료 알림 push 실패 title=%s exc=%r", title[:40], exc)


def _on_exec_success(cycle: str, directive_id: str, title: str, thread_id: str, worktree) -> None:  # noqa: ANN001
    """sub-agent rc==0 후처리 (#1403 #1417):
    - PR 있으면 → rev 자동 검토 (cycle != rev). directive 는 PR 머지 webhook 에서 완료.
    - PR 없으면 (조회/분석 등) → 보고가 결과물 → directive 완료(🟢) 전이 + #모부르지 알림.
    """
    import tools_queue as tq
    import tools_cycle as tc
    pr_num = _find_pr_number(worktree)
    if pr_num:
        try:
            tq.enqueue_rev_for_pr_if_any(cycle, worktree)
        except Exception as exc:  # noqa: BLE001
            logger.warning("rev auto-trigger 실패 cycle=%s exc=%r", cycle, exc)
        pr_url = f"https://github.com/goohong/mobruji/pull/{pr_num}"
        _notify_user_done(
            title,
            f"PR #{pr_num} 을 만들었습니다. 이제 rev(코드 검토)가 자동으로 돌고, "
            f"통과하면 develop 에 자동 머지됩니다. develop 머지는 사용자 확인이 필요 없습니다 "
            f"(확인이 필요한 건 release 머지뿐) — 따로 하실 일은 없습니다.",
            thread_id,
            pr_url=pr_url,
        )
        logger.info("exec 완료 cycle=%s → PR #%s", cycle, pr_num)
    else:
        try:
            tc.set_directive_forum_status(directive_id, "completed")
        except Exception as exc:  # noqa: BLE001
            logger.warning("directive 완료 전이 실패 id=%s exc=%r", directive_id, exc)
        _notify_user_done(
            title,
            "작업이 끝났습니다. 결과는 아래 링크에서 보실 수 있습니다.",
            thread_id,
        )
        logger.info("exec 완료 cycle=%s directive=%s (PR 없음 → 완료)", cycle, directive_id)
