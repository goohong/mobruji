"""Pause / resume tools (2종) — 사용자 사이클 정지 명령 처리.

사고 path code 차단:
  - paused=True → launch_subagent 가 PausedError raise (tools_subagent.py).
  - 즉 nmae LLM 이 사이클 정지 명령 무시하려 해도 code 가 차단.

호출자:
  - agent loop — 사용자 user_pause / user_resume event 수신 시
  - 또는 사용자 명시 명령 (Discord ⏹ 또는 '사이클 정지')

spec: tools/agent/README.md (Phase 1.3).
"""

from __future__ import annotations

import events as ev


# ─── 11. pause_global ────────────────────────────────────────────────────────


def pause_global(reason: str | None = None) -> dict[str, object]:
    """전체 시스템 paused 모드 진입.

    효과:
      - launch_subagent 가 PausedError raise (즉시)
      - in-flight sub-agent 는 그대로 진행 (강제 stop X — graceful)
      - 사용자 후속 'resume' 까지 대기

    Args:
        reason: 정지 사유 (audit 용)
    """
    if ev.get_state("paused") is True:
        return {"paused": True, "no_change": True}

    ev.set_state("paused", True)
    event_id = ev.append_event("user_pause", {"reason": reason or ""})
    return {"event_id": event_id, "paused": True}


# ─── 12. resume_global ───────────────────────────────────────────────────────


def resume_global() -> dict[str, object]:
    """paused 해제. launch_subagent 재허용."""
    if ev.get_state("paused") is not True:
        return {"paused": False, "no_change": True}

    ev.set_state("paused", False)
    event_id = ev.append_event("user_resume", {})
    return {"event_id": event_id, "paused": False}
