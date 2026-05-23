#!/usr/bin/env bash
# tools/cycle-status/validate.sh — idle 워크트리 note 누락 검증.
#
# 사용법:
#   validate.sh [--path ~/.mobruji/cycle-status.json]
#
# exit 0: 모두 OK (idle 워크트리 모두 note 명시 또는 active).
# exit 1: 누락 존재. stderr 에 누락 목록 출력.
# exit 2: cycle-status.json 부재 / parse fail.
#
# spec: docs/features/nmae-cycle-watchdog.md §5-7 strict mode
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

python3 - "$CYCLE_STATUS_PATH" <<'PY'
import json
import sys

path = sys.argv[1]
try:
    with open(path, "r", encoding="utf-8") as handle:
        data = json.load(handle)
except (json.JSONDecodeError, OSError) as exc:
    print(f"ERROR: parse fail {path}: {exc}", file=sys.stderr)
    sys.exit(2)

if not isinstance(data, dict):
    print(f"ERROR: top-level dict 아님: {path}", file=sys.stderr)
    sys.exit(2)

missing = []
for ws in ("be", "fe", "rev", "plan"):
    entry = data.get(ws)
    if not isinstance(entry, dict):
        # 워크트리 자체 누락 — idle 로 간주.
        missing.append((ws, "워크트리 entry 없음"))
        continue
    in_progress = entry.get("in_progress")
    if isinstance(in_progress, dict) and in_progress:
        continue
    if isinstance(in_progress, str) and in_progress.strip():
        continue
    note = entry.get("note")
    if not isinstance(note, str) or not note.strip():
        missing.append((ws, "idle 인데 note 없음"))

if missing:
    print("VIOLATION cycle-status.json idle reason 누락:", file=sys.stderr)
    for ws, reason in missing:
        print(f"  - {ws}: {reason}", file=sys.stderr)
    sys.exit(1)

print("OK 모든 idle 워크트리 note 명시 (또는 active)", file=sys.stderr)
sys.exit(0)
PY
