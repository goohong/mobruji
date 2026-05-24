#!/usr/bin/env bash
# tools/tests/test_agent_launch_wrapper.sh — agent-launch-wrapper.sh 단위 테스트 (#1008).
#
# 검증 시나리오:
#   1. 정상 호출 (worktree + --title) → exit 0 + cycle-status.json in_progress 갱신
#   2. --echo-prompt 부재 → stdout 에 confirm 한 줄
#   3. --echo-prompt 주어짐 → stdout 에 emit (Agent prompt 용)
#   4. 잘못된 worktree → exit 2
#   5. --title 누락 → exit 2
#   6. update.sh 실패 (CYCLE_STATUS_PATH 가 write 불가) → exit 4
#
# 사용:
#   bash tools/tests/test_agent_launch_wrapper.sh
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WRAPPER="$SCRIPT_DIR/../agent-launch-wrapper.sh"
PASS=0
FAIL=0
FAILURES=()

if [[ ! -x "$WRAPPER" ]]; then
  echo "FATAL: $WRAPPER 가 실행 가능하지 않습니다 (chmod +x 필요)." >&2
  exit 2
fi

# Each test runs in its own tmp dir + isolated CYCLE_STATUS_PATH.
make_tmp() {
  mktemp -d -t agent-launch-wrapper-XXXXXX
}

assert_exit() {
  local name="$1" expected="$2" actual="$3"
  if [[ "$actual" == "$expected" ]]; then
    PASS=$((PASS + 1))
    echo "PASS: $name"
  else
    FAIL=$((FAIL + 1))
    FAILURES+=("$name (exit expected=$expected actual=$actual)")
    echo "FAIL: $name (exit expected=$expected actual=$actual)"
  fi
}

assert_contains() {
  local name="$1" haystack="$2" needle="$3"
  if [[ "$haystack" == *"$needle"* ]]; then
    PASS=$((PASS + 1))
    echo "PASS: $name"
  else
    FAIL=$((FAIL + 1))
    FAILURES+=("$name (needle missing: '$needle')")
    echo "FAIL: $name (haystack='$haystack')"
  fi
}

# ─────────────────────────────────────────────────────────────────────────────
# 1) 정상 호출 — exit 0 + cycle-status.json in_progress 갱신
# ─────────────────────────────────────────────────────────────────────────────
TMP=$(make_tmp)
export CYCLE_STATUS_PATH="$TMP/cycle-status.json"
"$WRAPPER" be --title "feat: 테스트 launch" >/dev/null 2>&1
assert_exit "정상 호출 exit 0" 0 $?
if [[ -f "$CYCLE_STATUS_PATH" ]]; then
  CONTENT="$(cat "$CYCLE_STATUS_PATH")"
  assert_contains "in_progress.title 기록" "$CONTENT" "feat: 테스트 launch"
else
  FAIL=$((FAIL + 1))
  FAILURES+=("cycle-status.json 생성 안 됨")
  echo "FAIL: cycle-status.json 생성 안 됨"
fi
rm -rf "$TMP"

# ─────────────────────────────────────────────────────────────────────────────
# 2) --echo-prompt 부재 → confirm 한 줄
# ─────────────────────────────────────────────────────────────────────────────
TMP=$(make_tmp)
export CYCLE_STATUS_PATH="$TMP/cycle-status.json"
OUTPUT="$("$WRAPPER" fe --title "fe task" 2>/dev/null)"
assert_exit "--echo-prompt 부재 exit 0" 0 $?
assert_contains "confirm 한 줄 emit" "$OUTPUT" "cycle-status set-active OK"
rm -rf "$TMP"

# ─────────────────────────────────────────────────────────────────────────────
# 3) --echo-prompt 주어짐 → 그대로 emit
# ─────────────────────────────────────────────────────────────────────────────
TMP=$(make_tmp)
export CYCLE_STATUS_PATH="$TMP/cycle-status.json"
OUTPUT="$("$WRAPPER" rev --title "rev audit" --echo-prompt "Agent prompt body 한 줄" 2>/dev/null)"
assert_exit "--echo-prompt emit exit 0" 0 $?
assert_contains "echo prompt 그대로 emit" "$OUTPUT" "Agent prompt body 한 줄"
# confirm 메시지는 안 떠야 함 (echo-prompt 모드).
if [[ "$OUTPUT" == *"cycle-status set-active OK"* ]]; then
  FAIL=$((FAIL + 1))
  FAILURES+=("--echo-prompt 모드에서 confirm 메시지 누출됨")
  echo "FAIL: --echo-prompt 모드에서 confirm 메시지 누출됨"
else
  PASS=$((PASS + 1))
  echo "PASS: --echo-prompt 모드 confirm 메시지 억제"
fi
rm -rf "$TMP"

# ─────────────────────────────────────────────────────────────────────────────
# 4) 잘못된 worktree → exit 2
# ─────────────────────────────────────────────────────────────────────────────
TMP=$(make_tmp)
export CYCLE_STATUS_PATH="$TMP/cycle-status.json"
"$WRAPPER" web --title "wrong worktree" >/dev/null 2>&1
assert_exit "잘못된 worktree exit 2" 2 $?
rm -rf "$TMP"

# ─────────────────────────────────────────────────────────────────────────────
# 5) --title 누락 → exit 2
# ─────────────────────────────────────────────────────────────────────────────
TMP=$(make_tmp)
export CYCLE_STATUS_PATH="$TMP/cycle-status.json"
"$WRAPPER" be >/dev/null 2>&1
assert_exit "--title 누락 exit 2" 2 $?
rm -rf "$TMP"

# ─────────────────────────────────────────────────────────────────────────────
# 6) update.sh 실패 (write 불가 경로) → exit 4
# ─────────────────────────────────────────────────────────────────────────────
# /proc 같이 write 불가 경로로 강제 fail. atomic_write 가 OSError → exit non-zero.
export CYCLE_STATUS_PATH="/proc/__nonexistent/cycle-status.json"
"$WRAPPER" be --title "write fail test" >/dev/null 2>&1
RC=$?
# update.sh 가 다양한 exit code 로 죽을 수 있으나 wrapper 는 4 로 변환.
assert_exit "update.sh 실패 시 wrapper exit 4" 4 $RC

# ─────────────────────────────────────────────────────────────────────────────
# 결과
# ─────────────────────────────────────────────────────────────────────────────
echo
echo "─── 결과 ───"
echo "PASS: $PASS"
echo "FAIL: $FAIL"
if [[ $FAIL -gt 0 ]]; then
  echo "실패 케이스:"
  for entry in "${FAILURES[@]}"; do
    echo "  - $entry"
  done
  exit 1
fi
exit 0
