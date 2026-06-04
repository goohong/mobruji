"""Cycle / directive state tools (4종) — state.py 의 GlobalState 와 events 통합 update.

read = events.get_state 직접. write = state update + audit event emit (atomic 호출자
책임).

사고 path code 차단:
  - directive_status transitions = 명시 Literal — 미정의 status 거부
  - update_directive_status 가 pr_url / closed_reason 인자 강제 — 옛 사고 (cleanup 시 사유 누락) 차단

spec: tools/agent/README.md (Phase 1.3).
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import events as ev
import tools_discord as td
from state import CycleName, CycleStatus, DirectiveStatus


# ─── 9. get_cycle_state ──────────────────────────────────────────────────────


def get_cycle_state(cycle: CycleName) -> dict[str, Any] | None:
    """단일 cycle 의 current state."""
    return ev.get_state(f"cycle:{cycle}")


# ─── 9-b. get_pr_status (#1414 — 읽기 전용 PR/현황 조회) ──────────────────────

PR_STATUS_REPO = "goohong/mobruji"
PR_STATUS_TIMEOUT = 25


def get_pr_status(search: str = "", limit: int = 15) -> dict[str, Any]:
    """열린 PR 목록 조회 (읽기 전용 `gh pr list`). nmae 가 현황 질문 답변에 사용.

    raw Bash 대신 본 스코프 도구만 노출해 write 명령(git push/checkout 등) 사고 표면
    제거 (#1414 rev 🟡-1). `gh` 는 GH_TOKEN(.env) 인증. -R 로 repo 명시 — cwd 무관.
    실패 시 {"error": ..., "prs": []} graceful.
    """
    argv = [
        "gh", "pr", "list", "-R", PR_STATUS_REPO, "--state", "open",
        "--limit", str(max(1, min(int(limit), 50))),
        "--json", "number,title,labels,isDraft,headRefName",
    ]
    if search:
        argv += ["--search", search]
    try:
        result = subprocess.run(  # noqa: S603 — argv list, gh 고정
            argv, capture_output=True, text=True, timeout=PR_STATUS_TIMEOUT, check=False,
        )
        if result.returncode != 0:
            return {"error": (result.stderr or "").strip()[:200], "prs": []}
        raw = json.loads(result.stdout or "[]")
        prs = [
            {
                "number": p.get("number"),
                "title": p.get("title", ""),
                "labels": [lbl.get("name") for lbl in p.get("labels", [])],
                "draft": bool(p.get("isDraft")),
                "branch": p.get("headRefName", ""),
            }
            for p in raw
        ]
        return {"prs": prs, "count": len(prs)}
    except (OSError, subprocess.TimeoutExpired, json.JSONDecodeError) as exc:
        return {"error": str(exc)[:200], "prs": []}


# ─── 9-c. set_directive_forum_status (#1415 — 지시 forum 태그 전이) ────────────

DIRECTIVE_STATUS_SH = os.environ.get(
    "DIRECTIVE_STATUS_BIN",
    "/home/mobruji/mobruji/tools/discord-daemon/directive_status.sh",
)


def set_directive_forum_status(
    directive_id: str,
    new_status: str,
    *,
    cycle: str = "",
    reason: str = "",
    pr_url: str = "",
) -> None:
    """지시 forum thread 태그 전이(🟡→🔵→🟢) + directive-board.jsonl status + starter
    body PATCH 를 `directive_status.sh` 로 수행 (#1415).

    work-queue 는 agent state(`directive:{id}`) 만 갱신했고 directive-board.jsonl /
    Discord 태그는 안 건드려 지시 forum 이 🟡 대기 박제됐던 갭 fix. dispatch=in_progress,
    PR 머지=completed. 합성(rev-pr-*) / board 부재 directive 는 no-op. graceful.
    """
    if not directive_id or str(directive_id).startswith("rev-pr-"):
        return
    if new_status not in ("in_progress", "completed"):
        return
    argv = [
        DIRECTIVE_STATUS_SH, str(directive_id), new_status,
        pr_url or "", cycle or "", reason or "",
    ]
    try:
        subprocess.run(  # noqa: S603 — 고정 경로 + argv list
            argv, capture_output=True, text=True, timeout=20, check=False,
        )
    except (OSError, subprocess.TimeoutExpired):
        pass


# ─── 10. set_cycle_state ─────────────────────────────────────────────────────


def set_cycle_state(
    cycle: CycleName,
    *,
    status: CycleStatus | None = None,
    current_thread_id: str | None = None,
    current_pr_url: str | None = None,
    current_directive_id: str | None = None,
) -> dict[str, Any]:
    """cycle state partial update + cycle_state_changed event emit."""
    key = f"cycle:{cycle}"
    state = ev.get_state(key) or {
        "status": "idle",
        "current_thread_id": None,
        "current_pr_url": None,
        "current_directive_id": None,
    }
    changes: dict[str, Any] = {}
    if status is not None and status != state.get("status"):
        changes["status"] = status
        state["status"] = status
    if current_thread_id is not None:
        changes["current_thread_id"] = current_thread_id
        state["current_thread_id"] = current_thread_id
    if current_pr_url is not None:
        changes["current_pr_url"] = current_pr_url
        state["current_pr_url"] = current_pr_url
    if current_directive_id is not None:
        changes["current_directive_id"] = current_directive_id
        state["current_directive_id"] = current_directive_id

    if not changes:
        return {"cycle": cycle, "no_change": True}

    ev.set_state(key, state)
    event_id = ev.append_event(
        "cycle_state_changed",
        {"cycle": cycle, "changes": changes, "new_state": state},
    )
    return {"event_id": event_id, "cycle": cycle, "changes": changes}


# ─── rev forum 태그명 (rev 포럼 available_tags 와 정확히 일치) ─────────────────
#
# rev 포럼 available_tags (#1805): 🟡 Pre-merge review / 🔵 Post-merge audit /
# ✅ rev pass / ❌ rev fail. 여기 값이 available_tags 와 다르면 Discord 가 신규 태그를
# 만들거나 무시해 태그가 안 붙는다 — 반드시 정확히 일치시킨다.
PR_REVIEW_TAG = "🟡 Pre-merge review"
PR_AUDIT_TAG = "🔵 Post-merge audit"


# ─── 7. register_directive_pending ───────────────────────────────────────────
#
# 시그니처 분기 (2026-05-30 PR 2-b, #1364):
#   - kind=None (legacy) → 기존 behavior — pending_polish + directive_registered event.
#   - kind="pr_review"   → rev forum (PR_REVIEW_FORUM_ID) 안 신규 thread 신설
#                          (forum_create_thread 호출) + state schema 확장 +
#                          dedupe (rev-forum-dedupe.jsonl) + event=pr_review_registered.
#   - kind="pr_audit"    → 같은 pr_url 의 기존 rev thread lookup (dedupe cache) →
#                          단계 전이 (forum_retag + forum_edit_starter +
#                          forum_comment) + state schema 확장 +
#                          event=pr_audit_registered.
#
# spec: docs/features/pr-webhook-rev-forum.md §6-2 / §7-2 / §8.
# template SoT: docs/features/directive-board-template-and-tags.md §5-6.
# 호출자: tools/discord-daemon/pr-register-rev.sh (PR #1361 머지, hook script).


def register_directive_pending(
    directive_id: str,
    summary: str,
    *,
    cycle_hint: CycleName | None = None,
    kind: str | None = None,
    pr_url: str | None = None,
    thread_id: str | None = None,
    parent_directive_id: str | None = None,
    source: str | None = None,
) -> dict[str, Any]:
    """📌 등록 직후 — directive entry + 🟡 forum thread (사용자 정정 path).

    Args:
        directive_id: Discord message_id (snowflake) 또는 PR 단위 ID (rev-<num>-open / rev-<num>-merge)
        summary: 사용자 메시지 첫 80자 또는 polish 결과
        cycle_hint: nmae 의 추천 cycle (be/fe/rev/plan) — optional, plan 위임 시 reason 필수
        kind: directive 분류 — None (legacy 사용자 directive) / "pr_review" (PR open) /
            "pr_audit" (PR merge 후 사후 E2E QA). 신규 키워드 (PR 2-b).
        pr_url: PR URL (kind != None 시 의무). 신규 키워드 (PR 2-b).
        thread_id: 사전 신설 thread_id (호출자 가 thread 직접 신설한 경우). 보통은 본 함수가
            kind="pr_review" 분기에서 신설 후 cache 에 박는다.
        parent_directive_id: pr_audit 시 같은 PR 의 pr_review directive_id (단계 1↔2 link).
        source: 호출 path 식별자 ("pr_register_rev_hook" / "user_pushpin" 등) — log + state.
    """
    if ev.get_state(f"directive:{directive_id}") is not None:
        return {"directive_id": directive_id, "duplicate": True}

    # 분기 1: legacy (kind=None) → 기존 behavior 유지 (회귀 가드).
    if kind is None:
        return _register_legacy(directive_id, summary, cycle_hint=cycle_hint)

    # 분기 2: kind 알 수 없음 → ValueError (사고 path 차단 — typo 방지).
    if kind not in ("pr_review", "pr_audit"):
        raise ValueError(
            f"register_directive_pending: unknown kind={kind!r} "
            f"(expected None / 'pr_review' / 'pr_audit') — directive_id={directive_id}"
        )

    # 분기 3: kind != None → pr_url 의무.
    if not pr_url:
        raise ValueError(
            f"register_directive_pending(kind={kind!r}) requires pr_url — "
            f"directive_id={directive_id}"
        )

    if kind == "pr_review":
        return _register_pr_review(
            directive_id, summary,
            pr_url=pr_url,
            thread_id=thread_id,
            cycle_hint=cycle_hint,
            source=source,
        )
    # kind == "pr_audit"
    return _register_pr_audit(
        directive_id, summary,
        pr_url=pr_url,
        thread_id=thread_id,
        cycle_hint=cycle_hint,
        parent_directive_id=parent_directive_id,
        source=source,
    )


def _register_legacy(
    directive_id: str,
    summary: str,
    *,
    cycle_hint: CycleName | None,
) -> dict[str, Any]:
    """legacy (kind=None) — 기존 behavior 그대로. 회귀 가드 path."""
    key = f"directive:{directive_id}"
    state: dict[str, Any] = {
        "directive_id": directive_id,
        "summary": summary,
        "status": "pending_polish",
        "thread_id": None,
        "assigned_cycle": cycle_hint,
        "delegation_reason": None,
        "pr_url": None,
        "closed_reason": None,
    }
    ev.set_state(key, state)
    event_id = ev.append_event(
        "directive_registered",
        {"directive_id": directive_id, "summary": summary, "cycle_hint": cycle_hint},
    )
    return {"event_id": event_id, "directive_id": directive_id}


def _register_pr_review(
    directive_id: str,
    summary: str,
    *,
    pr_url: str,
    thread_id: str | None,
    cycle_hint: CycleName | None,
    source: str | None,
) -> dict[str, Any]:
    """kind=pr_review — rev forum 신규 thread 신설 + state + event.

    PR_REVIEW_FORUM_ID env 부재 시 graceful warning + state 박지만 thread_id=None
    (호출자 가 추후 thread 신설 후 update_directive_status thread_id= 로 채움).
    """
    # dedupe (PR URL + kind) — 같은 pr_review 2회 hit 시 기존 thread reuse (race 가드).
    cached_thread_id = _dedupe_lookup(pr_url, "pr_review")

    forum_id = os.environ.get("PR_REVIEW_FORUM_ID")
    new_thread_id: str | None = thread_id or cached_thread_id

    if new_thread_id is None and forum_id:
        # rev forum thread 신설 — directive-board-template-and-tags.md §5-2 거울 +
        # PR 단위 양식.
        title, body = _build_pr_review_template(directive_id, summary, pr_url)
        try:
            td.forum_create_thread(forum_id, title, body, tags=[PR_REVIEW_TAG])
        except Exception as exc:  # noqa: BLE001
            # forum_create_thread 자체 실패 시 (NCP bot.py polling miss / sqlite IO) —
            # state 박지만 thread_id=None 으로 graceful. hook script 가 alert counter
            # 증가 + DIGEST push (Q11=b).
            _stderr_warn(
                f"register_directive_pending(pr_review): forum_create_thread 실패 "
                f"— {type(exc).__name__}: {exc} (pr_url={pr_url})"
            )
            new_thread_id = None
        else:
            # forum_create_thread 가 thread_id 를 즉시 반환 X (bot.py polling 후 박힘).
            # 실제 snowflake 는 bot.py 의 후속 갱신에 의존. 본 함수 단계에서는 None.
            new_thread_id = None
    elif new_thread_id is None and not forum_id:
        _stderr_warn(
            "register_directive_pending(pr_review): PR_REVIEW_FORUM_ID env 부재 — "
            f"thread 신설 skip (pr_url={pr_url})"
        )

    key = f"directive:{directive_id}"
    state: dict[str, Any] = {
        "directive_id": directive_id,
        "summary": summary,
        "status": "pending_polish",
        "thread_id": new_thread_id,
        "assigned_cycle": cycle_hint,
        "delegation_reason": None,
        "pr_url": pr_url,
        "closed_reason": None,
        # 신규 필드 (PR 2-b).
        "kind": "pr_review",
        "parent_directive_id": None,
        "source": source,
    }
    ev.set_state(key, state)
    event_id = ev.append_event(
        "pr_review_registered",
        {
            "directive_id": directive_id,
            "summary": summary,
            "pr_url": pr_url,
            "thread_id": new_thread_id,
            "cycle_hint": cycle_hint,
            "source": source,
        },
    )

    # dedupe cache append (선처리 — 같은 pr_url + pr_review 2번 hit 시 race 가드).
    _dedupe_append(pr_url, "pr_review", new_thread_id)

    return {"event_id": event_id, "directive_id": directive_id, "kind": "pr_review"}


def _register_pr_audit(
    directive_id: str,
    summary: str,
    *,
    pr_url: str,
    thread_id: str | None,
    cycle_hint: CycleName | None,
    parent_directive_id: str | None,
    source: str | None,
) -> dict[str, Any]:
    """kind=pr_audit — 같은 PR 의 기존 rev thread lookup → 단계 전이 (🟡 → 🔵) +
    body PATCH + comment append + state + event.

    cache miss 시 graceful — state 박지만 thread_id=None (호출자 가 추후 박는다).
    """
    # cache lookup — 같은 pr_url 의 pr_review entry 의 thread_id.
    lookup_thread_id = thread_id or _dedupe_lookup(pr_url, "pr_review")

    if lookup_thread_id:
        # 단계 전이: 🟡 → 🔵 retag + body PATCH + comment append.
        try:
            td.forum_retag(lookup_thread_id, PR_AUDIT_TAG)
        except Exception as exc:  # noqa: BLE001
            _stderr_warn(
                f"register_directive_pending(pr_audit): forum_retag 실패 — "
                f"{type(exc).__name__}: {exc} (thread_id={lookup_thread_id})"
            )
        try:
            new_body = _build_pr_audit_body(directive_id, summary, pr_url)
            td.forum_edit_starter(lookup_thread_id, new_body)
        except Exception as exc:  # noqa: BLE001
            _stderr_warn(
                f"register_directive_pending(pr_audit): forum_edit_starter 실패 — "
                f"{type(exc).__name__}: {exc} (thread_id={lookup_thread_id})"
            )
        try:
            td.forum_comment(
                lookup_thread_id,
                f"✅ 머지됨 — 사후 E2E QA 단계 진입 (pr_url={pr_url})",
            )
        except Exception as exc:  # noqa: BLE001
            _stderr_warn(
                f"register_directive_pending(pr_audit): forum_comment 실패 — "
                f"{type(exc).__name__}: {exc} (thread_id={lookup_thread_id})"
            )
    else:
        _stderr_warn(
            "register_directive_pending(pr_audit): 기존 thread cache miss — "
            f"단계 전이 skip (pr_url={pr_url})"
        )

    key = f"directive:{directive_id}"
    state: dict[str, Any] = {
        "directive_id": directive_id,
        "summary": summary,
        "status": "pending_polish",
        "thread_id": lookup_thread_id,
        "assigned_cycle": cycle_hint,
        "delegation_reason": None,
        "pr_url": pr_url,
        "closed_reason": None,
        # 신규 필드 (PR 2-b).
        "kind": "pr_audit",
        "parent_directive_id": parent_directive_id,
        "source": source,
    }
    ev.set_state(key, state)
    event_id = ev.append_event(
        "pr_audit_registered",
        {
            "directive_id": directive_id,
            "summary": summary,
            "pr_url": pr_url,
            "thread_id": lookup_thread_id,
            "parent_directive_id": parent_directive_id,
            "cycle_hint": cycle_hint,
            "source": source,
        },
    )

    _dedupe_append(pr_url, "pr_audit", lookup_thread_id)

    return {"event_id": event_id, "directive_id": directive_id, "kind": "pr_audit"}


# ─── 8. update_directive_status ──────────────────────────────────────────────


def update_directive_status(
    directive_id: str,
    new_status: DirectiveStatus,
    *,
    pr_url: str | None = None,
    closed_reason: str | None = None,
    thread_id: str | None = None,
    assigned_cycle: CycleName | None = None,
    delegation_reason: str | None = None,
) -> dict[str, Any]:
    """directive state transition + directive_status_changed event emit.

    사고 path 차단:
      - closed → closed_reason 필수 (사용자 정정 path)
      - completed → pr_url 권장
      - assigned + plan → delegation_reason 필수 (legacy 룰 일치)
    """
    if new_status == "closed" and not closed_reason:
        raise ValueError(
            f"update_directive_status(closed) requires closed_reason — "
            f"directive_id={directive_id}"
        )
    if (
        new_status == "assigned"
        and assigned_cycle == "plan"
        and not delegation_reason
    ):
        raise ValueError(
            f"update_directive_status(assigned, plan) requires delegation_reason "
            f"— directive_id={directive_id}"
        )

    key = f"directive:{directive_id}"
    state = ev.get_state(key)
    if state is None:
        raise ValueError(f"directive not found: {directive_id}")

    old_status = state.get("status")
    state["status"] = new_status
    if pr_url:
        state["pr_url"] = pr_url
    if closed_reason:
        state["closed_reason"] = closed_reason
    if thread_id:
        state["thread_id"] = thread_id
    if assigned_cycle:
        state["assigned_cycle"] = assigned_cycle
    if delegation_reason:
        state["delegation_reason"] = delegation_reason

    ev.set_state(key, state)
    event_id = ev.append_event(
        "directive_status_changed",
        {
            "directive_id": directive_id,
            "old_status": old_status,
            "new_status": new_status,
            "new_state": state,
        },
    )
    return {"event_id": event_id, "directive_id": directive_id,
            "old_status": old_status, "new_status": new_status}


# ─── PR 2-b helpers — template + dedupe cache ────────────────────────────────


_DEDUPE_FILENAME = "rev-forum-dedupe.jsonl"
_DEDUPE_CAP = 1000


def _mobruji_dir() -> Path:
    """~/.mobruji/ — env override (테스트). 미설정 시 HOME 기준."""
    override = os.environ.get("MOBRUJI_DIR")
    if override:
        return Path(override).expanduser()
    return Path("~/.mobruji").expanduser()


def _dedupe_path() -> Path:
    return _mobruji_dir() / _DEDUPE_FILENAME


def _dedupe_lookup(pr_url: str, kind: str) -> str | None:
    """rev-forum-dedupe.jsonl 에서 같은 (pr_url, kind) 가장 최근 entry 의 thread_id."""
    path = _dedupe_path()
    if not path.exists():
        return None
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError:
        return None
    for line in reversed(lines):  # 최신 entry 먼저.
        line = line.strip()
        if not line:
            continue
        try:
            entry = json.loads(line)
        except json.JSONDecodeError:
            continue
        if entry.get("pr_url") == pr_url and entry.get("kind") == kind:
            tid = entry.get("thread_id")
            return tid if tid else None
    return None


def _dedupe_append(pr_url: str, kind: str, thread_id: str | None) -> None:
    """rev-forum-dedupe.jsonl 에 entry append + cap 1000 FIFO truncate."""
    path = _dedupe_path()
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
    except OSError:
        return
    entry = {
        "pr_url": pr_url,
        "kind": kind,
        "thread_id": thread_id,
        "ts": int(time.time()),
    }
    try:
        with path.open("a", encoding="utf-8") as fh:
            fh.write(json.dumps(entry, ensure_ascii=False) + "\n")
    except OSError:
        return

    # cap FIFO truncate (best-effort).
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
        if len(lines) > _DEDUPE_CAP:
            tail = lines[-_DEDUPE_CAP:]
            path.write_text("\n".join(tail) + "\n", encoding="utf-8")
    except OSError:
        return


def _now_kst() -> str:
    """KST timestamp (YYYY-MM-DD HH:MM KST)."""
    now_utc = datetime.now(timezone.utc)
    # KST = UTC+9. tzdata 없이 offset 만 더한다 (graceful).
    from datetime import timedelta
    kst = now_utc + timedelta(hours=9)
    return kst.strftime("%Y-%m-%d %H:%M KST")


def _build_pr_review_template(
    directive_id: str, summary: str, pr_url: str,
) -> tuple[str, str]:
    """rev forum thread 신설 시 title + body.

    SoT: docs/features/directive-board-template-and-tags.md §5-2 거울 + PR 단위 양식.
    """
    pr_num = _extract_pr_num(pr_url)
    title_prefix = f"📌 PR #{pr_num} rev review" if pr_num else "📌 PR rev review"
    # forum thread name = 100자 cap (tools_discord.forum_create_thread 가 99자 truncate).
    # title 은 forum 의 thread title — 본 body 의 📌 line 과 별.
    title = title_prefix
    if summary:
        title = f"{title_prefix} — {summary[:60]}"

    now = _now_kst()
    body = (
        f"📌 **{title_prefix}**\n"
        f"\n"
        f"💬 원본\n"
        f"> {summary}\n"
        f"> URL: {pr_url}\n"
        f"\n"
        f"🆔 `{directive_id}` · 🕐 {now}\n"
        f"\n"
        f"📋 진행 (🟡 대기)\n"
        f"- [ ] 정적 review (CLAUDE.md / spec 준수)\n"
        f"- [ ] 테스트 / 검증\n"
        f"- [ ] 머지 가능 결정\n"
        f"\n"
        f"🔖 관련\n"
        f"- directive_id: `{directive_id}`\n"
        f"- cycle: rev\n"
        f"- 신청자: PR 2-a hook script (pr-register-rev.sh)\n"
        f"\n"
        f"🤖 rev sub-agent 분배 대기\n"
        f"\n"
        f"---\n"
        f"_갱신: {now}_\n"
    )
    return title, body


def _build_pr_audit_body(
    directive_id: str, summary: str, pr_url: str,
) -> str:
    """kind=pr_audit 시 thread starter body PATCH 본문 — 단계 1 결과 + 단계 2 체크리스트."""
    pr_num = _extract_pr_num(pr_url)
    title_prefix = f"📌 PR #{pr_num} rev review" if pr_num else "📌 PR rev review"
    now = _now_kst()
    return (
        f"📌 **{title_prefix}** (🔵 사후 E2E QA)\n"
        f"\n"
        f"💬 원본\n"
        f"> {summary}\n"
        f"> URL: {pr_url}\n"
        f"\n"
        f"🆔 `{directive_id}` · 🕐 {now}\n"
        f"\n"
        f"✅ 단계 1 결과 *(머지 시점)*\n"
        f"- 머지 directive_id: `{directive_id}`\n"
        f"\n"
        f"📋 단계 2 진행 (🔵 사후 E2E QA)\n"
        f"- [ ] dev 환경 deploy 반영 확인\n"
        f"- [ ] e2e 회귀 (변경 영향 범위)\n"
        f"- [ ] regression label 부착 (`regression:dev` / `rev-post-merge-pass`)\n"
        f"\n"
        f"🔖 관련\n"
        f"- directive_id: `{directive_id}`\n"
        f"- cycle: rev\n"
        f"- 신청자: PR 2-a hook script (pr-register-rev.sh)\n"
        f"\n"
        f"---\n"
        f"_갱신: {now} (단계 1 → 단계 2 전이)_\n"
    )


def _extract_pr_num(pr_url: str) -> str | None:
    """https://github.com/<owner>/<repo>/pull/<num> → <num>."""
    if not pr_url:
        return None
    parts = pr_url.rstrip("/").rsplit("/", 1)
    if len(parts) != 2:
        return None
    num = parts[1]
    return num if num.isdigit() else None


def _stderr_warn(msg: str) -> None:
    """graceful stderr — register_directive_pending 호출 실패가 actor 흐름 차단 X."""
    try:
        print(f"WARN: {msg}", file=sys.stderr)
    except OSError:
        pass
