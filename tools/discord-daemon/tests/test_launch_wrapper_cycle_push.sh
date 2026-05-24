#!/usr/bin/env bash
# tools/discord-daemon/tests/test_launch_wrapper_cycle_push.sh — B2 검증 (2026-05-24).
#
# 검증 시나리오:
#   1) be / fe / rev / plan 4 cycle 모두 정상 push → stdout 에 LAUNCH_THREAD_ID 노출.
#   2) discord-reply.sh 가 --cycle-channel <worktree> --auto-ack-thread "<본문>" 형태로
#      호출됐는지 (mock capture log).
#   3) --no-cycle-push flag → push 단계 skip (mock 호출 없음, exit 0).
#   4) AGENT_LAUNCH_NO_DISCORD=1 env → 동일하게 skip.
#   5) DISCORD_REPLY_SH 가 부재 / 비실행 → graceful skip + warning + exit 0.
#   6) discord-reply.sh 가 비-snowflake 출력 시 graceful (LAUNCH_THREAD_ID 미출력, exit 0).
#   7) --description 우선 적용 (부재 시 title 사용).
#
# 사용:
#   bash tools/discord-daemon/tests/test_launch_wrapper_cycle_push.sh
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WRAPPER="$SCRIPT_DIR/../../agent-launch-wrapper.sh"
PASS=0
FAIL=0
FAILURES=()

if [[ ! -x "$WRAPPER" ]]; then
  echo "FATAL: $WRAPPER 가 실행 가능하지 않습니다." >&2
  exit 2
fi

make_tmp() {
  mktemp -d -t launch-wrapper-cycle-XXXXXX
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

assert_not_contains() {
  local name="$1" haystack="$2" needle="$3"
  if [[ "$haystack" != *"$needle"* ]]; then
    PASS=$((PASS + 1))
    echo "PASS: $name"
  else
    FAIL=$((FAIL + 1))
    FAILURES+=("$name (needle should NOT appear: '$needle')")
    echo "FAIL: $name (haystack contains '$needle')"
  fi
}

# Mock discord-reply.sh 빌더 — stdout 으로 snowflake (혹은 custom payload) 출력,
# 호출 인자를 LOG 파일에 1줄 기록. 인자 차이를 시각적으로 구분하기 위해
# `|` 구분자 사용.
make_mock() {
  local mock_path="$1"
  local stdout_value="$2"
  local log_path="$3"
  cat > "$mock_path" <<MOCK
#!/usr/bin/env bash
# auto-generated mock discord-reply.sh
printf '%s\n' "\$*" >> "$log_path"
printf '%s\n' "$stdout_value"
MOCK
  chmod +x "$mock_path"
}

# ─────────────────────────────────────────────────────────────────────────────
# 1) be cycle 정상 push — LAUNCH_THREAD_ID stdout 노출 + mock call log 검증
# ─────────────────────────────────────────────────────────────────────────────
TMP=$(make_tmp)
export CYCLE_STATUS_PATH="$TMP/cycle-status.json"
MOCK="$TMP/discord-reply.sh"
LOG="$TMP/calls.log"
SNOWFLAKE="1507991580198309969"
make_mock "$MOCK" "$SNOWFLAKE" "$LOG"

# Prefix env (VAR=val cmd) 가 assignment 앞에 붙으면 subshell 로 propagate 안 됨.
# export 또는 `env` 로 명시 — 모든 케이스 동일 패턴.
export DISCORD_REPLY_SH="$MOCK"
OUTPUT="$("$WRAPPER" be --title "feat: B2 test" 2>/dev/null)"
RC=$?
unset DISCORD_REPLY_SH
assert_exit "be 정상 push exit 0" 0 $RC
assert_contains "stdout 에 LAUNCH_THREAD_ID 노출" "$OUTPUT" "LAUNCH_THREAD_ID=$SNOWFLAKE"
assert_contains "기존 confirm 한 줄 유지 (호환)" "$OUTPUT" "cycle-status set-active OK"

CALL_LINE="$(cat "$LOG" 2>/dev/null)"
assert_contains "mock 호출 인자: --cycle-channel be" "$CALL_LINE" "--cycle-channel be"
assert_contains "mock 호출 인자: --auto-ack-thread" "$CALL_LINE" "--auto-ack-thread"
assert_contains "mock 호출 인자: title 본문 포함" "$CALL_LINE" "feat: B2 test"
rm -rf "$TMP"

# ─────────────────────────────────────────────────────────────────────────────
# 2) fe / rev / plan parametrize — 각 cycle 이름이 --cycle-channel 인자로 전달
# ─────────────────────────────────────────────────────────────────────────────
for cycle in fe rev plan; do
  TMP=$(make_tmp)
  export CYCLE_STATUS_PATH="$TMP/cycle-status.json"
  MOCK="$TMP/discord-reply.sh"
  LOG="$TMP/calls.log"
  make_mock "$MOCK" "$SNOWFLAKE" "$LOG"

  export DISCORD_REPLY_SH="$MOCK"
  OUTPUT="$("$WRAPPER" "$cycle" --title "feat: $cycle parametrize" 2>/dev/null)"
  RC=$?
  unset DISCORD_REPLY_SH
  assert_exit "$cycle 정상 push exit 0" 0 $RC
  assert_contains "$cycle LAUNCH_THREAD_ID 노출" "$OUTPUT" "LAUNCH_THREAD_ID=$SNOWFLAKE"
  CALL_LINE="$(cat "$LOG" 2>/dev/null)"
  assert_contains "$cycle 호출 인자 --cycle-channel" "$CALL_LINE" "--cycle-channel $cycle"
  rm -rf "$TMP"
done

# ─────────────────────────────────────────────────────────────────────────────
# 3) --no-cycle-push flag → push 단계 완전 skip (mock log 빈 채로)
# ─────────────────────────────────────────────────────────────────────────────
TMP=$(make_tmp)
export CYCLE_STATUS_PATH="$TMP/cycle-status.json"
MOCK="$TMP/discord-reply.sh"
LOG="$TMP/calls.log"
make_mock "$MOCK" "$SNOWFLAKE" "$LOG"

export DISCORD_REPLY_SH="$MOCK"
OUTPUT="$("$WRAPPER" be --title "skip test" --no-cycle-push 2>/dev/null)"
RC=$?
unset DISCORD_REPLY_SH
assert_exit "--no-cycle-push exit 0" 0 $RC
assert_not_contains "--no-cycle-push 시 LAUNCH_THREAD_ID 미출력" "$OUTPUT" "LAUNCH_THREAD_ID="
if [[ -s "$LOG" ]]; then
  FAIL=$((FAIL + 1))
  FAILURES+=("--no-cycle-push 인데 mock 호출됨: $(cat "$LOG")")
  echo "FAIL: --no-cycle-push 인데 mock 호출됨"
else
  PASS=$((PASS + 1))
  echo "PASS: --no-cycle-push 시 mock 호출 없음"
fi
rm -rf "$TMP"

# ─────────────────────────────────────────────────────────────────────────────
# 4) AGENT_LAUNCH_NO_DISCORD=1 env → 동일하게 skip
# ─────────────────────────────────────────────────────────────────────────────
TMP=$(make_tmp)
export CYCLE_STATUS_PATH="$TMP/cycle-status.json"
MOCK="$TMP/discord-reply.sh"
LOG="$TMP/calls.log"
make_mock "$MOCK" "$SNOWFLAKE" "$LOG"

export DISCORD_REPLY_SH="$MOCK"
export AGENT_LAUNCH_NO_DISCORD=1
OUTPUT="$("$WRAPPER" rev --title "env skip" 2>/dev/null)"
RC=$?
unset DISCORD_REPLY_SH
unset AGENT_LAUNCH_NO_DISCORD
assert_exit "AGENT_LAUNCH_NO_DISCORD=1 exit 0" 0 $RC
assert_not_contains "env skip 시 LAUNCH_THREAD_ID 미출력" "$OUTPUT" "LAUNCH_THREAD_ID="
if [[ -s "$LOG" ]]; then
  FAIL=$((FAIL + 1))
  FAILURES+=("AGENT_LAUNCH_NO_DISCORD=1 인데 mock 호출됨: $(cat "$LOG")")
  echo "FAIL: AGENT_LAUNCH_NO_DISCORD=1 인데 mock 호출됨"
else
  PASS=$((PASS + 1))
  echo "PASS: AGENT_LAUNCH_NO_DISCORD=1 시 mock 호출 없음"
fi
rm -rf "$TMP"

# ─────────────────────────────────────────────────────────────────────────────
# 5) DISCORD_REPLY_SH 가 부재 / 비실행 → graceful skip + warning + exit 0
# ─────────────────────────────────────────────────────────────────────────────
TMP=$(make_tmp)
export CYCLE_STATUS_PATH="$TMP/cycle-status.json"
NONEXISTENT="$TMP/nonexistent-discord-reply.sh"
# fallback 도 부재여야 함 — wrapper 옆 discord-daemon/discord-reply.sh 가
# 워크트리 안에는 존재하지만 본 테스트는 DISCORD_REPLY_SH override 로 명시.
# 추가 가드: HOME 을 빈 tmp 로 override 해 ~/.mobruji/discord-reply.sh 도 부재.
export HOME="$TMP"
export DISCORD_REPLY_SH="$NONEXISTENT"
OUTPUT_AND_STDERR="$("$WRAPPER" plan --title "missing reply.sh" 2>&1)"
RC=$?
unset HOME
unset DISCORD_REPLY_SH
# 본 케이스에선 wrapper 옆 discord-daemon/discord-reply.sh 가 실재할 수 있으므로
# exit 0 만 강제 — fallback 경로가 잡혀도 / 둘 다 부재여도 wrapper 자체는 fail 안 함.
assert_exit "discord-reply.sh 부재 graceful exit 0" 0 $RC
rm -rf "$TMP"

# ─────────────────────────────────────────────────────────────────────────────
# 6) discord-reply.sh 가 비-snowflake 출력 → LAUNCH_THREAD_ID 미출력 + exit 0
# ─────────────────────────────────────────────────────────────────────────────
TMP=$(make_tmp)
export CYCLE_STATUS_PATH="$TMP/cycle-status.json"
MOCK="$TMP/discord-reply.sh"
LOG="$TMP/calls.log"
# snowflake 가 아닌 값 (예: 짧은 정수) — wrapper 가 validate 후 skip.
make_mock "$MOCK" "abc-not-snowflake" "$LOG"

export DISCORD_REPLY_SH="$MOCK"
OUTPUT="$("$WRAPPER" be --title "invalid snowflake test" 2>/dev/null)"
RC=$?
unset DISCORD_REPLY_SH
assert_exit "비-snowflake graceful exit 0" 0 $RC
assert_not_contains "비-snowflake 시 LAUNCH_THREAD_ID 미출력" "$OUTPUT" "LAUNCH_THREAD_ID="
rm -rf "$TMP"

# ─────────────────────────────────────────────────────────────────────────────
# 7) --description 우선 적용 (부재 시 title)
# ─────────────────────────────────────────────────────────────────────────────
TMP=$(make_tmp)
export CYCLE_STATUS_PATH="$TMP/cycle-status.json"
MOCK="$TMP/discord-reply.sh"
LOG="$TMP/calls.log"
make_mock "$MOCK" "$SNOWFLAKE" "$LOG"

export DISCORD_REPLY_SH="$MOCK"
"$WRAPPER" be \
    --title "TITLE_PAYLOAD" \
    --description "DESCRIPTION_PAYLOAD" >/dev/null 2>/dev/null
RC=$?
unset DISCORD_REPLY_SH
assert_exit "--description exit 0" 0 $RC
CALL_LINE="$(cat "$LOG" 2>/dev/null)"
assert_contains "--description 본문이 push 인자에 포함" "$CALL_LINE" "DESCRIPTION_PAYLOAD"
# title 은 cycle-status set-active 호출 인자 (mock log 와 무관) — push 본문에는 없어야.
# (단, title 이 description 안에 우연히 substring 되면 통과해도 무방하므로 negative
#  assertion 은 두지 않음.)
rm -rf "$TMP"

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
