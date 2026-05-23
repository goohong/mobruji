#!/usr/bin/env bash
# tools/cycle-status/tests/test_validate.sh — validate.sh 단위 테스트.
#
# 검증 시나리오:
#   1. 모든 워크트리 active + valid timestamp → exit 0
#   2. idle + note 명시 → exit 0
#   3. idle + note 없음 → exit 1 (idle reason 누락)
#   4. completed_at 가 미래 (KST as Z 시나리오 — PR #970 회귀) → exit 1
#   5. completed_at 가 너무 과거 (7d 초과) → exit 1
#   6. idle_since 가 미래 → exit 1
#   7. in_progress.started_at 가 미래 → exit 1
#   8. cycle-status.json 부재 → exit 2
#   9. malformed JSON → exit 2
#
# spec: tools/cycle-status/validate.sh + #971 timestamp guard
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VALIDATE_SH="$SCRIPT_DIR/../validate.sh"
PASS=0
FAIL=0
FAILURES=()

run_case() {
  local name="$1"
  local expected_exit="$2"
  local payload="$3"
  local expect_stderr_contains="${4:-}"
  local tmp
  tmp=$(mktemp)
  printf '%s' "$payload" > "$tmp"
  local actual_stderr
  actual_stderr=$(bash "$VALIDATE_SH" --path "$tmp" 2>&1 >/dev/null)
  local actual_exit=$?
  local ok=1
  if [[ "$actual_exit" != "$expected_exit" ]]; then
    ok=0
  fi
  if [[ -n "$expect_stderr_contains" ]] \
     && ! grep -q -- "$expect_stderr_contains" <<<"$actual_stderr"; then
    ok=0
  fi
  rm -f "$tmp"
  if [[ "$ok" == "1" ]]; then
    PASS=$((PASS+1))
    printf "  PASS  %s (exit=%d)\n" "$name" "$actual_exit"
  else
    FAIL=$((FAIL+1))
    FAILURES+=("$name (expected exit=$expected_exit got=$actual_exit)")
    printf "  FAIL  %s (expected exit=%d got=%d)\n" \
      "$name" "$expected_exit" "$actual_exit"
    printf "        stderr: %s\n" "$actual_stderr"
  fi
}

run_missing_file_case() {
  local name="$1"
  local actual_stderr
  actual_stderr=$(bash "$VALIDATE_SH" --path /tmp/nonexistent-cycle-$$.json 2>&1 >/dev/null)
  local actual_exit=$?
  if [[ "$actual_exit" == "2" ]] && grep -q "부재" <<<"$actual_stderr"; then
    PASS=$((PASS+1))
    printf "  PASS  %s (exit=2 + 부재 메시지)\n" "$name"
  else
    FAIL=$((FAIL+1))
    FAILURES+=("$name (expected exit=2 + 부재 메시지)")
    printf "  FAIL  %s (exit=%d stderr=%s)\n" \
      "$name" "$actual_exit" "$actual_stderr"
  fi
}

now_iso() {
  python3 -c 'import datetime,sys; print(datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"))'
}

now_plus_iso() {
  python3 -c "import datetime,sys; print((datetime.datetime.now(datetime.timezone.utc)+datetime.timedelta(seconds=$1)).strftime('%Y-%m-%dT%H:%M:%SZ'))"
}

NOW=$(now_iso)
FUTURE_2H=$(now_plus_iso 7200)      # 2h 후
PAST_1H=$(now_plus_iso -3600)       # 1h 전 (정상 범위)
PAST_8D=$(now_plus_iso -691200)     # 8d 전 (초과)

echo "[1/9] all active + valid timestamp → exit 0"
run_case "all_active_valid" 0 "$(cat <<EOF
{
  "be":   {"in_progress": {"issue":"#1","title":"x","started_at":"$NOW"},
           "last_completed": {"pr":"#1","title":"y","completed_at":"$PAST_1H"}},
  "fe":   {"in_progress": {"issue":"#2","title":"y","started_at":"$NOW"},
           "last_completed": null},
  "rev":  {"in_progress": {"target":"X","title":"audit","started_at":"$NOW"},
           "last_completed": null},
  "plan": {"in_progress": {"issue":"#3","title":"z","started_at":"$NOW"},
           "last_completed": null}
}
EOF
)" "OK"

echo "[2/9] idle + note → exit 0"
run_case "idle_with_note" 0 "$(cat <<EOF
{
  "be":   {"in_progress": null, "note": "다음 후보: PR #957 follow-up",
           "idle_since":"$NOW",
           "last_completed": {"pr":"#1","title":"x","completed_at":"$PAST_1H"}},
  "fe":   {"in_progress": {"issue":"#2","title":"y","started_at":"$NOW"},
           "last_completed": null},
  "rev":  {"in_progress": {"target":"X","title":"audit","started_at":"$NOW"},
           "last_completed": null},
  "plan": {"in_progress": {"issue":"#3","title":"z","started_at":"$NOW"},
           "last_completed": null}
}
EOF
)" "OK"

echo "[3/9] idle + note 없음 → exit 1"
run_case "idle_without_note" 1 "$(cat <<EOF
{
  "be":   {"in_progress": null,
           "last_completed": {"pr":"#1","title":"x","completed_at":"$PAST_1H"}},
  "fe":   {"in_progress": {"issue":"#2","title":"y","started_at":"$NOW"},
           "last_completed": null},
  "rev":  {"in_progress": {"target":"X","title":"audit","started_at":"$NOW"},
           "last_completed": null},
  "plan": {"in_progress": {"issue":"#3","title":"z","started_at":"$NOW"},
           "last_completed": null}
}
EOF
)" "idle 인데 note 없음"

echo "[4/9] completed_at 가 미래 (KST as Z 시나리오) → exit 1"
run_case "completed_at_future" 1 "$(cat <<EOF
{
  "be":   {"in_progress": {"issue":"#1","title":"x","started_at":"$NOW"},
           "last_completed": {"pr":"#1","title":"future","completed_at":"$FUTURE_2H"}},
  "fe":   {"in_progress": {"issue":"#2","title":"y","started_at":"$NOW"},
           "last_completed": null},
  "rev":  {"in_progress": {"target":"X","title":"audit","started_at":"$NOW"},
           "last_completed": null},
  "plan": {"in_progress": {"issue":"#3","title":"z","started_at":"$NOW"},
           "last_completed": null}
}
EOF
)" "미래 timestamp"

echo "[5/9] completed_at 가 너무 과거 (8d 전) → exit 1"
run_case "completed_at_too_old" 1 "$(cat <<EOF
{
  "be":   {"in_progress": null, "note":"old",
           "last_completed": {"pr":"#1","title":"old","completed_at":"$PAST_8D"}},
  "fe":   {"in_progress": {"issue":"#2","title":"y","started_at":"$NOW"},
           "last_completed": null},
  "rev":  {"in_progress": {"target":"X","title":"audit","started_at":"$NOW"},
           "last_completed": null},
  "plan": {"in_progress": {"issue":"#3","title":"z","started_at":"$NOW"},
           "last_completed": null}
}
EOF
)" "과거 timestamp"

echo "[6/9] idle_since 가 미래 → exit 1"
run_case "idle_since_future" 1 "$(cat <<EOF
{
  "be":   {"in_progress": null, "note":"x", "idle_since":"$FUTURE_2H",
           "last_completed": {"pr":"#1","title":"x","completed_at":"$PAST_1H"}},
  "fe":   {"in_progress": {"issue":"#2","title":"y","started_at":"$NOW"},
           "last_completed": null},
  "rev":  {"in_progress": {"target":"X","title":"audit","started_at":"$NOW"},
           "last_completed": null},
  "plan": {"in_progress": {"issue":"#3","title":"z","started_at":"$NOW"},
           "last_completed": null}
}
EOF
)" "미래 timestamp"

echo "[7/9] in_progress.started_at 가 미래 → exit 1"
run_case "in_progress_started_at_future" 1 "$(cat <<EOF
{
  "be":   {"in_progress": {"issue":"#1","title":"x","started_at":"$FUTURE_2H"},
           "last_completed": null},
  "fe":   {"in_progress": {"issue":"#2","title":"y","started_at":"$NOW"},
           "last_completed": null},
  "rev":  {"in_progress": {"target":"X","title":"audit","started_at":"$NOW"},
           "last_completed": null},
  "plan": {"in_progress": {"issue":"#3","title":"z","started_at":"$NOW"},
           "last_completed": null}
}
EOF
)" "미래 timestamp"

echo "[8/9] cycle-status.json 부재 → exit 2"
run_missing_file_case "missing_file"

echo "[9/9] malformed JSON → exit 2"
run_case "malformed_json" 2 "{not valid json" "parse fail"

echo
echo "===================================="
echo "PASS=$PASS FAIL=$FAIL"
if [[ "$FAIL" -gt 0 ]]; then
  printf "Failures:\n"
  for f in "${FAILURES[@]}"; do
    printf "  - %s\n" "$f"
  done
  exit 1
fi
echo "All validate.sh tests passed."
