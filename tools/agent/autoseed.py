"""유휴 시 백로그 자동 시드 (#1523).

cycle 이 idle 이고 그 cycle 큐가 비면 GitHub 백로그(open 이슈, scope 라벨)에서
1건을 골라 directive 큐에 enqueue → dispatcher 가 평소처럼 launch. nmae STRICT 정합:
"무엇을 launch 하라" 지시가 아니라 **큐를 채우기만** 한다 (picking 권한은 코드/엔진).

spec: docs/features/roadmap-queue-autoseed.md (가드 G1~G5, scope→cycle 매핑).

설계 정합 메모: spec 은 bot.py→directive-board→nmae-tmux inject 흐름을 전제하나, 실제
라이브 자율 엔진은 agent 의 enqueue_directive→dispatch 경로다(directive_approved 와
동일 종착). 따라서 본 모듈은 **enqueue_directive 로 직접 큐 보충** — scope→cycle 이
결정적이라 SDK(claude) 호출 불요(예산 0).

안전: env ``AUTOSEED_ENABLED`` 가 "1" 일 때만 동작 (기본 off → 기존 동작 무변경).
flag off 배포 → 검증 후 on 단계적 rollout (#1531 패턴).
"""

from __future__ import annotations

import json
import logging
import os
import subprocess
from typing import Any, Callable

import events as ev
import tools_queue as tq
import work_queue as wq

logger = logging.getLogger("agent.autoseed")

# ── scope → cycle 매핑 (spec §5-2) ────────────────────────────────────────────
_SCOPE_CYCLE: dict[str, str] = {
    "scope:web": "fe",
    "scope:recommendation": "be",
    "scope:song": "be",
    "scope:user": "be",
    "scope:voice": "be",
    "scope:feedback": "be",
    "scope:infra": "infra",
}
# autoseed 가 자동 보충할 cycle. rev 는 PR 리뷰 전용이라 제외.
# infra 제외 (사용자 2026-06-03): infra 는 ephemeral 라 항상 비어 autoseed 가 매번
# 먼저 집어 오래된 저가치 이슈만 반복 churn + infra forum 부재로 cycle thread 생성
# rc=1 noise. infra 는 가치 있는 이슈만 사람/nmae 가 수동 큐레이션해 dispatch.
SEEDABLE_CYCLES: tuple[str, ...] = ("be", "fe", "plan")

# ── G3 high-stakes 제외 (spec §5-3) ───────────────────────────────────────────
_EXCLUDE_LABELS = frozenset({"type:release", "security", "do-not-merge"})
_EXCLUDE_KEYWORDS = (
    "secret", "credential", "password", "token", "breaking",
    "마이그레이션", "migration", ".env", "docker-compose", "dockerfile",
    "build.gradle", "운영 db", "production db", "license",
)

GH_TIMEOUT = 25


def autoseed_enabled() -> bool:
    """자동 시드 활성 여부 (기본 off — 안전 배포)."""
    return os.environ.get("AUTOSEED_ENABLED", "").strip() == "1"


def _max_pending() -> int:
    try:
        return int(os.environ.get("AUTOSEED_MAX_PENDING", "4"))
    except ValueError:
        return 4


def _batch() -> int:
    try:
        return int(os.environ.get("AUTOSEED_BATCH", "1"))
    except ValueError:
        return 1


def _scope_to_cycle(labels: list[str]) -> str | None:
    """이슈 라벨 → assigned cycle. 모호(scope 없음/복수 scope)면 None(plan fallback).

    type:docs 단독은 plan (spec §5-2 type 보조). scope 라벨 우선.
    """
    scopes = [lbl for lbl in labels if lbl.startswith("scope:")]
    if len(scopes) == 1 and scopes[0] in _SCOPE_CYCLE:
        return _SCOPE_CYCLE[scopes[0]]
    if "type:docs" in labels and not scopes:
        return "plan"
    return None  # 모호 → plan 분배(또는 skip)


def _is_high_stakes(issue: dict[str, Any]) -> bool:
    """G3 — release/secret/보호영역/BREAKING 이슈는 자동 시드 제외."""
    labels = [lbl.get("name", "") for lbl in issue.get("labels", [])]
    if any(lbl in _EXCLUDE_LABELS for lbl in labels):
        return True
    text = f"{issue.get('title', '')}\n{issue.get('body', '') or ''}".lower()
    return any(kw in text for kw in _EXCLUDE_KEYWORDS)


def _already_seeded_or_busy(issue: dict[str, Any]) -> bool:
    """G4 — 이미 시드됨(directive:autoseed-N 존재) / assignee 보유 시 skip.

    (이슈 list 가 --state open 이라 closed 는 자연 제외. PR 매핑 dedup 은 enqueue
    멱등 + seed_issue 키로 1차 차단 — 정밀 PR 검색은 후속.)
    """
    num = issue.get("number")
    if num is None:
        return True
    if ev.get_state(f"directive:autoseed-{num}") is not None:
        return True  # 이미 시드(멱등)
    if issue.get("assignees"):
        return True  # 사람 작업 중
    return False


def _seeded_pending_count() -> int:
    """G1 — 큐에 적재된 seeded(autoseed-*) 대기 entry 수 (사람 directive 미포함)."""
    snap = wq.snapshot()
    count = 0
    for items in snap.values():
        count += sum(
            1 for it in items if str(it.get("directive_id", "")).startswith("autoseed-")
        )
    return count


def _seeded_for_cycle(cycle: str) -> bool:
    """그 cycle 에 이미 seeded 대기 entry 가 있나 (G2 — cycle 당 1건)."""
    snap = wq.snapshot()
    return any(
        str(it.get("directive_id", "")).startswith("autoseed-")
        for it in snap.get(cycle, [])
    )


def _fetch_backlog(cycle: str, runner: Callable[..., Any]) -> list[dict[str, Any]]:
    """cycle scope 매칭 open 이슈 (created_at asc — 백로그 소진 방향)."""
    scopes = [s for s, c in _SCOPE_CYCLE.items() if c == cycle]
    if not scopes and cycle != "plan":
        return []
    cmd = [
        "gh", "issue", "list", "--state", "open",
        "--json", "number,title,labels,createdAt,assignees,body",
        "--limit", "60",
    ]
    for s in scopes:
        cmd += ["--label", s]
    try:
        result = runner(cmd, capture_output=True, text=True, timeout=GH_TIMEOUT, check=False)
    except (OSError, subprocess.TimeoutExpired) as exc:
        logger.warning("autoseed: gh issue list 실패 cycle=%s: %r", cycle, exc)
        return []
    if result.returncode != 0:
        logger.warning("autoseed: gh issue list rc=%d cycle=%s", result.returncode, cycle)
        return []
    try:
        issues = json.loads(result.stdout or "[]")
    except json.JSONDecodeError:
        return []
    # 라벨 정규화 + created_at asc 정렬 (오래된 것부터 — 백로그 소진).
    for iss in issues:
        iss["_labels"] = [lbl.get("name", "") for lbl in iss.get("labels", [])]
    issues.sort(key=lambda i: (i.get("createdAt", ""), i.get("number", 0)))
    return issues


def _task_body(issue: dict[str, Any]) -> str:
    body = (issue.get("body") or "").strip()[:400]
    return (
        f"GitHub 백로그 이슈 #{issue['number']} 자동 시드.\n"
        f"제목: {issue.get('title', '')}\n"
        f"본문: {body}\n\n"
        f"이슈 요구사항 구현. 품질 게이트 통과 후 PR(base develop, 이슈 #{issue['number']} 참조). 자율."
    )


def autoseed_once(runner: Callable[..., Any] = subprocess.run) -> list[dict[str, Any]]:
    """idle cycle 큐 보충 1회. 반환 = 이번에 seed 한 항목 list (로그용).

    가드: G1 pending cap, G2 batch/cycle당 1, G3 high-stakes 제외, G4 중복/assignee,
    G5 per-seed try/except 격리. flag off 면 즉시 [].
    """
    if not autoseed_enabled():
        return []
    if ev.get_state("paused") is True:
        return []
    seeded: list[dict[str, Any]] = []
    if _seeded_pending_count() >= _max_pending():  # G1
        return []
    in_flight = set(ev.get_state("in_flight_agents") or [])
    batch = _batch()

    for cycle in SEEDABLE_CYCLES:
        if len(seeded) >= batch:  # G2
            break
        if cycle in in_flight:  # busy 워크트리
            continue
        if wq.peek_next(cycle) is not None:  # 이미 대기 작업 있음 → 보충 불요
            continue
        if _seeded_for_cycle(cycle):  # G2 — cycle 당 seeded 1건
            continue
        try:
            issues = _fetch_backlog(cycle, runner)
            picked = None
            for iss in issues:
                if _is_high_stakes(iss):  # G3
                    continue
                if _already_seeded_or_busy(iss):  # G4
                    continue
                # scope→cycle 재확인 (복수 scope 모호 이슈 배제)
                if _scope_to_cycle(iss.get("_labels", [])) != cycle:
                    continue
                picked = iss
                break
            if picked is None:
                continue
            num = picked["number"]
            did = f"autoseed-{num}"
            ev.set_state(f"directive:{did}", {
                "directive_id": did, "summary": picked.get("title", "")[:80],
                "status": "polished", "thread_id": None, "assigned_cycle": cycle,
                "delegation_reason": "백로그 자동 시드", "pr_url": None,
                "closed_reason": None, "source": "autoseed", "seed_issue": num,
            })
            tq.enqueue_directive(
                cycle, did, f"🌱 {picked.get('title', '')[:70]}", _task_body(picked),
            )
            logger.info("autoseed: 이슈 #%s → %s 큐 seed", num, cycle)
            seeded.append({"issue": num, "cycle": cycle})
        except Exception as exc:  # noqa: BLE001 — G5 per-seed 격리
            logger.warning("autoseed: cycle=%s seed 실패 (격리): %r", cycle, exc)
            continue
    return seeded
