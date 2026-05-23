#!/usr/bin/env bash
# tools/cycle-status/validate.sh — idle 워크트리 note 누락 + timestamp sanity 검증.
#
# 사용법:
#   validate.sh [--path ~/.mobruji/cycle-status.json]
#
# exit 0: 모두 OK (idle 워크트리 모두 note 명시 또는 active, timestamp 정상 범위).
# exit 1: 누락 또는 timestamp 이상 존재. stderr 에 상세 출력.
# exit 2: cycle-status.json 부재 / parse fail.
#
# 검증 항목:
#   1. idle (in_progress null/missing) 워크트리는 note 필드 명시 (기존, #956).
#   2. completed_at / started_at / idle_since ISO timestamp 가 [now-7d, now+5min]
#      범위 안 (#971). 범위 밖이면 fail — KST 시각을 Z suffix 로 hand-edit 한
#      경우 9h future 로 보여 fail. PR #970 회귀 사고 (KST as Z) 재발 방지.
#
# 환경변수:
#   CYCLE_STATUS_PATH                 — 기본 ~/.mobruji/cycle-status.json
#   CYCLE_TS_PAST_LIMIT_SECONDS       — 과거 허용 (기본 604800 = 7d)
#   CYCLE_TS_FUTURE_LIMIT_SECONDS     — 미래 허용 (기본 300 = 5min)
#
# spec: docs/features/nmae-cycle-watchdog.md §5-7 strict mode + #971 timestamp guard
set -euo pipefail

CYCLE_STATUS_PATH="${CYCLE_STATUS_PATH:-$HOME/.mobruji/cycle-status.json}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --path) CYCLE_STATUS_PATH="$2"; shift 2 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

if [[ ! -f "$CYCLE_STATUS_PATH" ]]; then
  echo "ERROR: cycle-status.json 부재: $CYCLE_STATUS_PATH" >&2
  exit 2
fi

export CYCLE_TS_PAST_LIMIT_SECONDS="${CYCLE_TS_PAST_LIMIT_SECONDS:-604800}"
export CYCLE_TS_FUTURE_LIMIT_SECONDS="${CYCLE_TS_FUTURE_LIMIT_SECONDS:-300}"

python3 - "$CYCLE_STATUS_PATH" <<'PY'
import json
import os
import sys
from datetime import datetime, timezone

path = sys.argv[1]
past_limit = int(os.environ.get("CYCLE_TS_PAST_LIMIT_SECONDS", "604800"))
future_limit = int(os.environ.get("CYCLE_TS_FUTURE_LIMIT_SECONDS", "300"))

try:
    with open(path, "r", encoding="utf-8") as handle:
        data = json.load(handle)
except (json.JSONDecodeError, OSError) as exc:
    print(f"ERROR: parse fail {path}: {exc}", file=sys.stderr)
    sys.exit(2)

if not isinstance(data, dict):
    print(f"ERROR: top-level dict 아님: {path}", file=sys.stderr)
    sys.exit(2)

now = datetime.now(timezone.utc)
TIMESTAMP_KEYS = ("completed_at", "started_at", "idle_since")
WORKSPACES = ("be", "fe", "rev", "plan")


def parse_iso(text):
    if not isinstance(text, str) or not text.strip():
        return None
    candidate = text.strip()
    if candidate.endswith("Z"):
        candidate = candidate[:-1] + "+00:00"
    try:
        parsed = datetime.fromisoformat(candidate)
    except ValueError:
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed


missing = []
ts_violations = []

for ws in WORKSPACES:
    entry = data.get(ws)
    if not isinstance(entry, dict):
        # 워크트리 자체 누락 — idle 로 간주.
        missing.append((ws, "워크트리 entry 없음"))
        continue

    # ── note 검증 (#956) ──────────────────────────────────────────────────
    in_progress = entry.get("in_progress")
    is_active = False
    if isinstance(in_progress, dict) and in_progress:
        is_active = True
    elif isinstance(in_progress, str) and in_progress.strip():
        is_active = True
    if not is_active:
        note = entry.get("note")
        if not isinstance(note, str) or not note.strip():
            missing.append((ws, "idle 인데 note 없음"))

    # ── timestamp sanity (#971) ──────────────────────────────────────────
    # entry 직속 (idle_since 등) + last_completed.* + in_progress.* (dict 형식).
    candidate_pairs = []
    for key in TIMESTAMP_KEYS:
        value = entry.get(key)
        if value is not None:
            candidate_pairs.append((f"{ws}.{key}", value))

    last_completed = entry.get("last_completed")
    if isinstance(last_completed, dict):
        for key in TIMESTAMP_KEYS:
            value = last_completed.get(key)
            if value is not None:
                candidate_pairs.append(
                    (f"{ws}.last_completed.{key}", value)
                )

    if isinstance(in_progress, dict):
        for key in TIMESTAMP_KEYS:
            value = in_progress.get(key)
            if value is not None:
                candidate_pairs.append(
                    (f"{ws}.in_progress.{key}", value)
                )

    for label, raw_value in candidate_pairs:
        parsed = parse_iso(raw_value)
        if parsed is None:
            ts_violations.append(
                (label, raw_value, "parse 실패 (ISO8601 아님)")
            )
            continue
        delta_seconds = (now - parsed).total_seconds()
        if delta_seconds < -future_limit:
            ts_violations.append(
                (
                    label,
                    raw_value,
                    f"미래 timestamp (now 보다 {-delta_seconds:.0f}s 후). "
                    "KST 시각을 Z suffix 로 hand-edit 한 경우 의심 — "
                    "update.sh 만 사용",
                )
            )
        elif delta_seconds > past_limit:
            ts_violations.append(
                (
                    label,
                    raw_value,
                    f"과거 timestamp (now 보다 {delta_seconds:.0f}s 전, "
                    f"한도 {past_limit}s 초과)",
                )
            )

exit_code = 0
if missing:
    print("VIOLATION cycle-status.json idle reason 누락:", file=sys.stderr)
    for ws, reason in missing:
        print(f"  - {ws}: {reason}", file=sys.stderr)
    exit_code = 1

if ts_violations:
    print(
        "VIOLATION cycle-status.json timestamp sanity (PR #971):",
        file=sys.stderr,
    )
    for label, raw_value, reason in ts_violations:
        print(f"  - {label} = {raw_value!r}: {reason}", file=sys.stderr)
    exit_code = 1

if exit_code != 0:
    sys.exit(exit_code)

print(
    "OK 모든 idle 워크트리 note 명시 + timestamp sanity 정상",
    file=sys.stderr,
)
sys.exit(0)
PY
