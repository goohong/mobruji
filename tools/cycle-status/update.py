#!/usr/bin/env python3
"""cycle-status.json 갱신 헬퍼.

`tools/cycle-status/update.sh` 가 forward 한다. atomic write (temp + rename)
로 동시 갱신 race 보호.

spec: docs/features/nmae-cycle-watchdog.md
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import tempfile
from datetime import datetime, timedelta, timezone

# KST 자정 기준 cycle counter (사용자 요청 #996, 2026-05-24).
# set-active 호출마다 해당 워크트리 카운트 ++. 날짜 바뀜 (KST) 감지 시 0 reset.
# digest embed 가 read_cycle_counts() 로 읽어 한 줄 추가 push.
CYCLE_COUNTER_DEFAULT_PATH = os.path.expanduser("~/.mobruji/cycle-counter.json")
CYCLE_COUNTER_WORKSPACES = ("be", "fe", "rev", "plan")
KST_TZ = timezone(timedelta(hours=9))


def iso_now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def load_existing(path: str) -> dict:
    if not os.path.exists(path):
        return {}
    try:
        with open(path, "r", encoding="utf-8") as handle:
            data = json.load(handle)
            if not isinstance(data, dict):
                return {}
            return data
    except (json.JSONDecodeError, OSError):
        return {}


def atomic_write(path: str, payload: dict) -> None:
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    fd, tmp_path = tempfile.mkstemp(
        prefix=".cycle-status-", suffix=".tmp", dir=os.path.dirname(path) or "."
    )
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            json.dump(payload, handle, ensure_ascii=False, indent=2)
            handle.write("\n")
        os.replace(tmp_path, path)
    except Exception:
        if os.path.exists(tmp_path):
            os.unlink(tmp_path)
        raise


# ─────────────────────────────────────────────────────────────────────────────
# cycle counter (사용자 요청 #996, 2026-05-24)
# ─────────────────────────────────────────────────────────────────────────────


def _empty_counter_payload(date_str: str) -> dict:
    return {
        "date": date_str,
        "counts": {ws: 0 for ws in CYCLE_COUNTER_WORKSPACES},
    }


def _kst_today_str(now: datetime | None = None) -> str:
    """KST 기준 YYYY-MM-DD. 자정(00:00 KST) 기준 일자 경계."""
    current = now if now is not None else datetime.now(timezone.utc)
    return current.astimezone(KST_TZ).strftime("%Y-%m-%d")


def _atomic_write_counter(path: str, payload: dict) -> None:
    """counter 파일 전용 atomic write — `.cycle-counter-` prefix 로 cycle-status 와 분리."""
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    fd, tmp_path = tempfile.mkstemp(
        prefix=".cycle-counter-", suffix=".tmp", dir=os.path.dirname(path) or "."
    )
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            json.dump(payload, handle, ensure_ascii=False, indent=2)
            handle.write("\n")
        os.replace(tmp_path, path)
    except Exception:
        if os.path.exists(tmp_path):
            os.unlink(tmp_path)
        raise


def increment_cycle_counter(
    workspace: str,
    *,
    path: str | None = None,
    now: datetime | None = None,
) -> dict:
    """`~/.mobruji/cycle-counter.json` 의 ``counts[workspace]`` 를 ++ 합니다.

    동작:
        - workspace 가 be/fe/rev/plan 이 아니면 silent skip (반환만 함).
        - 파일 부재/깨짐 → 오늘(KST) 기준 신규 payload 로 초기화 후 1.
        - 파일에 기록된 date 와 오늘(KST) 가 동일 → 해당 워크트리 ++.
        - 다르면 (자정 경과) 모든 카운트 0 reset → 해당 워크트리 = 1.
        - atomic write (tempfile + os.replace) — 동시 update.sh 호출 race 방지.

    인자:
        workspace: be/fe/rev/plan 중 하나. 그 외는 skip.
        path: counter 파일 경로 override (테스트용). None 이면 default.
        now: 현재 시각 주입 (테스트용). None 이면 ``datetime.now(UTC)``.

    반환:
        write 완료 후 counter dict 전체 (호출자가 검증 가능). skip 시 현재 dict.
    """
    counter_path = path if path is not None else CYCLE_COUNTER_DEFAULT_PATH
    today_str = _kst_today_str(now)
    existing = load_existing(counter_path)
    existing_date = existing.get("date") if isinstance(existing, dict) else None
    existing_counts = (
        existing.get("counts") if isinstance(existing, dict) else None
    )

    if workspace not in CYCLE_COUNTER_WORKSPACES:
        # 미지원 워크트리 → 카운트 안 함. 파일은 그대로.
        if isinstance(existing, dict) and existing:
            return existing
        return _empty_counter_payload(today_str)

    if existing_date == today_str and isinstance(existing_counts, dict):
        # 같은 날 → 누적.
        payload = _empty_counter_payload(today_str)
        # 기존 카운트 보존 (다른 워크트리 값 유지).
        for ws in CYCLE_COUNTER_WORKSPACES:
            value = existing_counts.get(ws)
            payload["counts"][ws] = value if isinstance(value, int) and value >= 0 else 0
        payload["counts"][workspace] = payload["counts"][workspace] + 1
    else:
        # 날짜 바뀜 / 첫 호출 → reset 후 1.
        payload = _empty_counter_payload(today_str)
        payload["counts"][workspace] = 1

    _atomic_write_counter(counter_path, payload)
    return payload


def read_cycle_counts(
    path: str | None = None, now: datetime | None = None
) -> dict | None:
    """`~/.mobruji/cycle-counter.json` 을 읽어 dict 로 반환합니다.

    digest embed 가 호출. 부재/깨짐/타입 이상이면 None — caller (bot.py) 가
    graceful skip 합니다.

    Lazy reset: 파일에 기록된 date 가 오늘(KST) 와 다르면 카운트는 모두 0 이라고
    간주한 payload 를 반환합니다 (디스크 write 는 안 함 — read 책임 외).

    반환:
        {"date": "YYYY-MM-DD", "counts": {"be": int, "fe": int, "rev": int, "plan": int}}
        또는 None (파일 부재/JSON 깨짐).
    """
    counter_path = path if path is not None else CYCLE_COUNTER_DEFAULT_PATH
    if not os.path.exists(counter_path):
        return None
    try:
        with open(counter_path, "r", encoding="utf-8") as handle:
            data = json.load(handle)
    except (json.JSONDecodeError, OSError):
        return None
    if not isinstance(data, dict):
        return None

    today_str = _kst_today_str(now)
    existing_date = data.get("date")
    existing_counts = data.get("counts")
    if existing_date == today_str and isinstance(existing_counts, dict):
        normalized = _empty_counter_payload(today_str)
        for ws in CYCLE_COUNTER_WORKSPACES:
            value = existing_counts.get(ws)
            normalized["counts"][ws] = (
                value if isinstance(value, int) and value >= 0 else 0
            )
        return normalized
    # 날짜 바뀜 — 자정 경과로 카운트 무효. 모두 0 반환.
    return _empty_counter_payload(today_str)


def apply_set_active(entry: dict, args: argparse.Namespace) -> dict:
    if not args.title:
        raise SystemExit("set-active 는 --title 필수")
    in_progress = {
        "title": args.title,
        "started_at": iso_now(),
    }
    if args.task:
        in_progress["task"] = args.task
    entry["in_progress"] = in_progress
    # active 진입 시 note / idle_since 정리.
    entry.pop("note", None)
    entry.pop("idle_since", None)
    return entry


def apply_set_idle(entry: dict, args: argparse.Namespace) -> dict:
    if not args.note or not args.note.strip():
        raise SystemExit(
            "set-idle 는 --note 필수 (사유 미명시 시 watchdog STRICT relaunch). "
            "예: '다음 launch 후보: PR #857 audit'"
        )
    entry["in_progress"] = None
    entry["note"] = args.note.strip()
    # idle 진입 시각 기록 (이미 있으면 유지).
    if not entry.get("idle_since"):
        entry["idle_since"] = iso_now()
    return entry


def apply_set_completed(entry: dict, args: argparse.Namespace) -> dict:
    if not args.title:
        raise SystemExit("set-completed 는 --title 필수")
    last_completed = {
        "title": args.title,
        "completed_at": iso_now(),
    }
    if args.pr:
        last_completed["pr"] = args.pr
    entry["last_completed"] = last_completed
    return entry


def main() -> int:
    parser = argparse.ArgumentParser(description="cycle-status.json 갱신")
    parser.add_argument("--path", required=True, help="cycle-status.json 경로")
    parser.add_argument(
        "--worktree", required=True, choices=["be", "fe", "rev", "plan"]
    )
    parser.add_argument(
        "--action",
        required=True,
        choices=["set-active", "set-idle", "set-completed"],
    )
    parser.add_argument("--title", default=None)
    parser.add_argument("--task", default=None)
    parser.add_argument("--note", default=None)
    parser.add_argument("--pr", default=None)
    parser.add_argument(
        "--counter-path",
        default=None,
        help="cycle-counter.json 경로 override (테스트용, default ~/.mobruji/cycle-counter.json)",
    )
    args = parser.parse_args()

    data = load_existing(args.path)
    entry = data.get(args.worktree)
    if not isinstance(entry, dict):
        entry = {"in_progress": None, "last_completed": None}

    if args.action == "set-active":
        entry = apply_set_active(entry, args)
    elif args.action == "set-idle":
        entry = apply_set_idle(entry, args)
    elif args.action == "set-completed":
        entry = apply_set_completed(entry, args)

    data[args.worktree] = entry
    atomic_write(args.path, data)

    # set-active 시 cycle counter ++ (사용자 요청 #996).
    # 실패해도 cycle-status.json write 는 이미 완료 → caller 흐름 안 막음.
    if args.action == "set-active":
        try:
            increment_cycle_counter(args.worktree, path=args.counter_path)
        except OSError as exc:
            print(
                f"WARN cycle counter increment 실패 (계속 진행): {exc}",
                file=sys.stderr,
            )

    print(f"OK {args.worktree} {args.action}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
