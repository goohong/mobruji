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
from datetime import datetime, timezone


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
    print(f"OK {args.worktree} {args.action}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
