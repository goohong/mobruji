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
import signal
import subprocess
from pathlib import Path

import events as ev
import tools_discord as td
import tools_subagent as ts

logger = logging.getLogger("agent.subagent_runner")

WORKTREE_ROOT = Path("/home/mobruji")
WORKTREE_PREFIX = "mobruji-"
# (#1531) 표준 사이클(be/fe/rev/plan)은 상시 워크트리. infra 는 온디맨드 ephemeral —
# 아래 MAIN_CHECKOUT 에서 git worktree add 로 생성, 작업 후 teardown (ADR-0027 옵션 D).
MAIN_CHECKOUT = WORKTREE_ROOT / "mobruji"
# sub-agent 1 task 최대 실행 시간 — 초과 시 kill + 실패 보고 + lock 해제.
EXEC_TIMEOUT_SECONDS = 45 * 60
DEFAULT_MODEL = "claude-opus-4-8"

# (#1413) sub-agent 작업 보고 양식 — Discord 마크다운, AS-IS/TO-BE (나열 금지,
# 제목/내용 구분, 줄바꿈). 사용자 정정 2026-05-31: "정리라기보다 나열 — AS-IS/TO-BE
# 같은 가독성 양식 필요 + Discord 마크다운으로 제목·내용 구분".
# 상태 문구 주의 (#1586): sub-agent 의 "작업 완료" 는 **구현 끝 + PR 제출(머지 대기)**
# 를 뜻한다. forum **태그**의 "완료"(머지 시 loop 가 자동 전이)와 다른 의미라, 같은
# 단어 "완료" 를 쓰면 댓글(작업 완료)과 태그(진행=미머지)가 어긋나 사용자가 혼란
# (2026-06-03 사용자 정정). 따라서 보고 상태에 "(머지 대기)" 를 명시해 구분한다.
REPORT_TEMPLATE = """\
## <제목> · <✅ 작업 완료(PR 제출 · 머지 대기) | 🟡 진행 | ⛔ 차단>

**AS-IS** — 원래
- <변경 전 상태 / 무엇이 문제였나>

**TO-BE** — 적용
- <무엇을 어떻게 바꿨나 (진행·차단이면 바꿀 목표)>

**다음** *(진행·차단 시만)*
- <다음 액션 / 차단 사유>

🔗 PR #<N>  *(머지되면 위 forum 태그가 자동으로 '완료' 로 바뀝니다)*"""


def exec_enabled() -> bool:
    """실제 sub-agent spawn 활성 여부 (기본 off — 안전 배포)."""
    return os.environ.get("MOBRUJI_SUBAGENT_EXEC", "").strip() == "1"


def worktree_path(cycle: str) -> Path:
    return WORKTREE_ROOT / f"{WORKTREE_PREFIX}{cycle}"


def _ephemeral_worktree_add(directive_id: str) -> Path:
    """(#1531) infra 온디맨드 ephemeral 워크트리 생성 — `git worktree add origin/develop`.

    spec: docs/features/on-demand-infra-dispatch.md. directive 별 고유 경로
    (standing 워크트리/develop 점유와 충돌 없음). 생성 전 stale prune + 동일 경로
    제거(재사용/crash 잔존 가드). 실패 시 RuntimeError → 호출부 finally 가 lock 해제.
    """
    safe = "".join(ch for ch in str(directive_id) if ch.isalnum() or ch in "-_")[:40]
    wt = WORKTREE_ROOT / f"{WORKTREE_PREFIX}infra-{safe}"
    g = ["git", "-C", str(MAIN_CHECKOUT), "worktree"]
    subprocess.run([*g, "prune"], capture_output=True, text=True, timeout=30, check=False)
    subprocess.run([*g, "remove", "--force", str(wt)], capture_output=True, text=True, timeout=30, check=False)
    result = subprocess.run(
        [*g, "add", "--detach", str(wt), "origin/develop"],
        capture_output=True, text=True, timeout=60, check=False,
    )
    if result.returncode != 0:
        raise RuntimeError(f"ephemeral worktree add 실패: {(result.stderr or '')[:200]}")
    logger.info("infra ephemeral worktree 생성: %s", wt)
    return wt


def _ephemeral_worktree_remove(wt: Path) -> None:
    """(#1531) infra ephemeral 워크트리 teardown — graceful. 성공/실패/타임아웃 무관 호출."""
    g = ["git", "-C", str(MAIN_CHECKOUT), "worktree"]
    try:
        subprocess.run([*g, "remove", "--force", str(wt)], capture_output=True, text=True, timeout=30, check=False)
        subprocess.run([*g, "prune"], capture_output=True, text=True, timeout=30, check=False)
        logger.info("infra ephemeral worktree teardown: %s", wt)
    except (OSError, subprocess.TimeoutExpired) as exc:
        logger.warning("ephemeral worktree teardown 실패 wt=%s exc=%r", wt, exc)


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
    ephemeral_infra = cycle == "infra"
    wt = worktree_path(cycle)
    logger.info(
        "subagent exec 시작: cycle=%s directive=%s wt=%s%s",
        cycle, directive_id, wt, " (ephemeral infra)" if ephemeral_infra else "",
    )
    try:
        # (#1531) infra 는 상시 워크트리가 없다 — ephemeral 생성(git worktree add
        # origin/develop). 생성 실패 시 예외 → 아래 except + finally(lock 해제) 경로.
        if ephemeral_infra:
            wt = await asyncio.to_thread(_ephemeral_worktree_add, directive_id)
        # (#1398 rev 🟡-1) argv 빌드(shlex.split CLAUDE_BIN)도 try 안에서 — unbalanced
        # quote 등 ValueError 가 finally 밖으로 탈출해 lock 미해제되는 사고 차단.
        prompt = build_task_prompt(cycle, directive_id, title, task, thread_id)
        role = role_prompt(cycle, wt)
        argv = _claude_argv(prompt, role)
        # (#1481) start_new_session=True → claude 가 새 세션·프로세스 그룹 리더가 된다
        # (pgid == pid). sub-agent 가 background 로 띄운 자식(next dev / bootRun /
        # GradleDaemon 등)도 같은 그룹에 속해, 종료 시 그룹 전체를 kill 해 고아(PPID 1)
        # 누수를 막는다. 과거엔 proc.kill() 이 claude 본체만 죽이고 자식은 reparent 돼
        # 며칠씩 잔존(450MB next dev -p 4322), NCP RAM 잠식 → #1453 재유발.
        proc = await asyncio.create_subprocess_exec(
            *argv, cwd=str(wt),
            stdout=asyncio.subprocess.DEVNULL,
            stderr=asyncio.subprocess.PIPE,
            start_new_session=True,
        )
        pgid = proc.pid  # 새 세션 리더라 pgid == pid.
        try:
            _, stderr = await asyncio.wait_for(proc.communicate(), timeout=timeout)
            rc = proc.returncode
            logger.info("subagent exec 종료: cycle=%s rc=%s", cycle, rc)
            # (#1481) claude 종료 직후 그룹 잔여 자식(background next dev/bootRun 등) 즉시
            # 정리 — _on_exec_success(git/gh) 지연 전에 reap 해 PID 재사용 창 최소화.
            await _kill_process_group(pgid, cycle, reason="exec 종료 후 잔여 자식")
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
            # (#1481) 그룹 전체 kill — claude 본체 + background 자식 동시 정리.
            await _kill_process_group(pgid, cycle, reason=f"{timeout/60:.0f}분 타임아웃")
            logger.warning("subagent exec 타임아웃 kill: cycle=%s (%.0fs)", cycle, timeout)
            if thread_id:
                _safe_comment(thread_id, f"⏱ {cycle} sub-agent {timeout/60:.0f}분 타임아웃 — 중단.")
    except Exception as exc:  # noqa: BLE001
        logger.warning("subagent exec spawn 실패: cycle=%s exc=%r", cycle, exc)
        if thread_id:
            _safe_comment(thread_id, f"❌ {cycle} sub-agent 실행 실패: {str(exc)[:150]}")
    finally:
        # (#1531) infra ephemeral 워크트리 teardown — 성공/실패/타임아웃 무관. claude
        # 프로세스 그룹은 위에서 이미 kill 됐으므로 워크트리 제거 안전. lock 해제와 동일하게 보장.
        if ephemeral_infra:
            try:
                await asyncio.to_thread(_ephemeral_worktree_remove, wt)
            except Exception as exc:  # noqa: BLE001
                logger.warning("infra ephemeral teardown 실패 wt=%s exc=%r", wt, exc)
        # 어떤 경우에도 lock 해제 — dispatcher 가 큐 다음 항목 진행.
        try:
            ts.mark_subagent_completed(cycle)
            started = ev.get_state("in_flight_started") or {}
            started.pop(cycle, None)
            ev.set_state("in_flight_started", started)
        except Exception as exc:  # noqa: BLE001
            logger.warning("subagent exec lock 해제 실패: cycle=%s exc=%r", cycle, exc)


async def _kill_process_group(pgid: int, cycle: str, *, reason: str) -> None:
    """(#1481) sub-agent 프로세스 그룹 전체 종료 — claude 본체 + 그것이 background 로
    띄운 자식(next dev / bootRun / GradleDaemon 등)을 함께 reap 해 고아 누수 방지.

    SIGTERM 으로 정중히 → 짧게 대기 → 남으면 SIGKILL. 이미 비어 있으면(자식 없음)
    ProcessLookupError → 정상(죽일 게 없음). graceful — 실패해도 호출부 흐름 막지 않는다
    (lock 해제는 finally 가 보장). async — SIGTERM 유예 대기에 event loop 안 막음.
    """
    if not pgid or pgid <= 1:
        return
    try:
        os.killpg(pgid, signal.SIGTERM)
    except ProcessLookupError:
        return  # 그룹에 남은 프로세스 없음 — 누수 없음.
    except OSError as exc:
        logger.warning("그룹 SIGTERM 실패 cycle=%s pgid=%s: %r", cycle, pgid, exc)
        return
    # SIGTERM 후 잠깐 — 정상 종료 여유. 남으면 SIGKILL.
    try:
        await asyncio.sleep(1.5)
        os.killpg(pgid, signal.SIGKILL)
        logger.info("sub-agent 그룹 SIGKILL cycle=%s pgid=%s (%s) — 잔여 자식 강제 정리", cycle, pgid, reason)
    except ProcessLookupError:
        logger.info("sub-agent 그룹 정리 cycle=%s pgid=%s (%s) — SIGTERM 으로 종료", cycle, pgid, reason)
    except OSError as exc:
        logger.warning("그룹 SIGKILL 실패 cycle=%s pgid=%s: %r", cycle, pgid, exc)


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


def _ensure_pr_xrefs(
    pr_num: str,
    directive_id: str,
    cycle: str,
    cycle_thread_id: str,
    worktree,  # noqa: ANN001
) -> None:
    """(#1427/#1440) PR 본문에 머지 자동완료용 크로스레프 멱등 보강.

    bot.py 의 두 loop 가 PR 본문 ref 로 forum thread 를 찾아 머지 시 자동 완료한다:
    - `directive: <id>` → directive_complete_on_merge_loop → directive forum 태그 ✅ + 본문.
    - `cycle-forum: <cycle>:<thread_id>` → cycle_thread_complete_on_merge_loop → cycle forum ✅ retag.
    sub-agent 가 본문에 누락하면 머지해도 forum 태그가 안 바뀌고 댓글만 남던 사고
    (2026-05-31 사용자 정정). rev-pr-* 합성 directive id 는 forum directive 아니라 제외.
    """
    markers: list[str] = []
    if directive_id and not directive_id.startswith("rev-pr-"):
        markers.append(f"directive: {directive_id}")
    if cycle in ("be", "fe", "rev", "plan") and cycle_thread_id and str(cycle_thread_id).isdigit():
        markers.append(f"cycle-forum: {cycle}:{cycle_thread_id}")
    if not markers:
        return
    try:
        cur = subprocess.run(
            ["gh", "pr", "view", str(pr_num), "--json", "body", "-q", ".body"],
            capture_output=True, text=True, timeout=15, check=False, cwd=str(worktree),
        ).stdout or ""
        missing = [m for m in markers if m not in cur]
        if not missing:
            return
        block = "\n".join(missing)
        new_body = (cur.rstrip() + f"\n\n{block}") if cur.strip() else block
        subprocess.run(
            ["gh", "pr", "edit", str(pr_num), "--body", new_body],
            capture_output=True, text=True, timeout=15, check=False, cwd=str(worktree),
        )
        logger.info("PR #%s 본문 xref 보강: %s", pr_num, missing)
    except (OSError, subprocess.TimeoutExpired) as exc:
        logger.warning("PR xref 보강 실패 pr=#%s exc=%r", pr_num, exc)


def _notify_user_done(
    title: str,
    body: str,
    thread_id: str = "",
    *,
    pr_url: str = "",
    cta: str = "",
) -> None:
    """digest 채널에 sub-agent 작업 완료 알림 (#1417, 문구 정정 #1427, 채널 이전 #7). graceful.

    사용자 정정 2026-05-31: 알림이 '검토 후 머지됩니다 / 확인 부탁' 처럼 모호하면 안 됨.
    (1) rev 를 모호어로 부르지 말 것 — 'rev(코드 리뷰)' 로 풀이.
    (2) develop 자동 머지는 사용자 할 일 없음을 분명히 (확인 필요한 건 release 뿐).
    (3) PR 링크 포함 (pr_url).
    (4) 행동 요청(cta)은 정말 필요할 때만 — 불필요한 '확인 부탁' 금지.

    채널 이전 (#7, 2026-06-03 사용자 요청): sub-agent 완료/진행 류 알림이
    사용자 대화 채널(#모부르지)을 도배해 대화가 묻혔다. ``--status-channel`` 로
    DIGEST_CHANNEL_ID(완료/진행 집約 채널)에 보낸다 → #모부르지 는 사용자 대화
    전용으로 비운다. release 등 사용자 확인이 필요한 알림은 nmae 가 #모부르지 로
    직접 보내며 본 sub-agent 완료 경로에는 release 알림이 포함되지 않는다.
    ``--status-channel`` 은 내부에서 ``--no-reply`` 를 강제하므로 사용자 메시지
    answer 형태로 붙지 않는다.
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
        subprocess.run(
            [bin_, "--status-channel", msg],
            capture_output=True, text=True, timeout=15, check=False,
        )
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
        # (#1427/#1440) PR 본문에 directive: + cycle-forum: 크로스레프 보강 →
        # 머지 시 directive forum 완료 + cycle forum ✅ retag 자동화. sub-agent 가
        # 누락하면 forum 태그가 안 바뀌던 사고 (사용자 정정 2026-05-31).
        try:
            cycle_thread_id = str((ev.get_state(f"directive:{directive_id}") or {}).get("cycle_thread_id") or "")
        except Exception:  # noqa: BLE001 — state 읽기 실패해도 xref 보강은 directive 만이라도 진행
            cycle_thread_id = ""
        _ensure_pr_xrefs(pr_num, directive_id, cycle, cycle_thread_id, worktree)
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
    elif cycle in ("be", "fe"):
        # (#1455) be/fe 는 **구현 사이클** — PR 이 결과물이다. rc==0 이어도 PR 이
        # 없으면 작업이 끝난 게 아니라 미완이다 (claude -p 가 NCP thrash·내부 한도로
        # PR 없이 빠져나온 사고, #1453). 과거엔 아래 else 분기가 cycle 무관하게
        # `completed` 전이해 **미완 작업이 '완료' 로 둔갑**, 사용자가 안 된 걸 됐다고
        # 오인했다 (2026-06-02 live 음역대 directive). 완료 전이 금지 — directive 는
        # 'in_progress' 로 유지하고 ⚠️ 미완을 알려 재시도 대상임을 분명히 한다
        # (자동 무한 재시도는 thrash 악순환 위험이라 사람/nmae 가 재트리거).
        if thread_id:
            _safe_comment(
                thread_id,
                f"⚠️ {cycle} sub-agent 가 PR 없이 종료했습니다 — 미완 (타임아웃·thrash 추정, #1453). "
                f"directive 를 '진행 중' 으로 유지하며 완료 처리하지 않습니다. 재시도가 필요합니다.",
            )
        logger.warning(
            "exec 종료 cycle=%s directive=%s — 구현 사이클인데 PR 없음 → 미완 (완료 전이 안 함)",
            cycle, directive_id,
        )
    else:
        # plan/rev 등 비구현 사이클 — 보고·검토가 결과물이라 PR 없이 종료해도 정상 완료.
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
