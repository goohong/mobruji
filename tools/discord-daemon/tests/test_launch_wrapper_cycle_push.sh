#!/usr/bin/env bash
# tools/discord-daemon/tests/test_launch_wrapper_cycle_push.sh — B2 검증 (2026-05-24)
#                                                                + #1106 (2026-05-26).
#
# 검증 시나리오 (#1106 forum-post 분기 이후, PR fix/digest-forum-push-leak-#1283 갱신):
#   1) be / fe / rev / plan 4 cycle 모두 정상 push → stdout 에 LAUNCH_THREAD_ID 노출
#      (단 AGENT_LAUNCH_CREATE_FORUM_THREAD=1 opt-in 필요 — 2026-05-29 정책 skip 후).
#   2) discord-reply.sh 가 --forum-post-auto-tag <worktree> "<title>" "<body>" 형태로
#      호출됐는지 (mock capture log, opt-in 시).
#   3) --no-cycle-push flag → push 단계 skip (mock 호출 없음, exit 0).
#   4) AGENT_LAUNCH_NO_DISCORD=1 env → 동일하게 skip.
#   5) DISCORD_REPLY_SH 가 부재 / 비실행 → graceful skip + warning + exit 0.
#   6) discord-reply.sh 가 비-snowflake 출력 시 graceful (LAUNCH_THREAD_ID 미출력, exit 0).
#   7) --description 우선 적용 (부재 시 title 사용).
#   8) (#1106) forum-post 실패 시 --status-channel (DIGEST) graceful fallback
#      → DIGEST 본문 suffix = "[DIGEST 라우팅] forum-post 실패 (rc=N)" (정확한 사유).
#   9) (PR #1283) pending-thread-id 부재 + opt-in 안 함 → 정책 skip → DIGEST 라우팅
#      본문 suffix = "[DIGEST 라우팅] cycle forum policy: pending 부재"
#      (사용자에게 "forum push 실패" 오해 유발 메시지 제거 회귀 가드).
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
# 1) be cycle 정상 push — AGENT_LAUNCH_CREATE_FORUM_THREAD=1 opt-in 시
#    LAUNCH_THREAD_ID stdout 노출 + mock call log 검증 (PR #1283 정책 반영).
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
export AGENT_LAUNCH_CREATE_FORUM_THREAD=1
OUTPUT="$("$WRAPPER" be --title "feat: B2 test" 2>/dev/null)"
RC=$?
unset DISCORD_REPLY_SH
unset AGENT_LAUNCH_CREATE_FORUM_THREAD
assert_exit "be 정상 push exit 0" 0 $RC
assert_contains "stdout 에 LAUNCH_THREAD_ID 노출" "$OUTPUT" "LAUNCH_THREAD_ID=$SNOWFLAKE"
assert_contains "기존 confirm 한 줄 유지 (호환)" "$OUTPUT" "cycle-status set-active OK"

CALL_LINE="$(cat "$LOG" 2>/dev/null)"
assert_contains "mock 호출 인자: --forum-post-auto-tag be" "$CALL_LINE" "--forum-post-auto-tag be"
assert_contains "mock 호출 인자: title 본문 포함" "$CALL_LINE" "feat: B2 test"
# #1106 — text-channel API 호출이 더 이상 발생하면 안 됨 (사이런스 회귀 가드).
assert_not_contains "회귀 가드: --cycle-channel 호출 안 함" "$CALL_LINE" "--cycle-channel"
assert_not_contains "회귀 가드: --auto-ack-thread 호출 안 함" "$CALL_LINE" "--auto-ack-thread"
rm -rf "$TMP"

# ─────────────────────────────────────────────────────────────────────────────
# 2) fe / rev / plan parametrize — opt-in 시 각 cycle 이름이 --forum-post-auto-tag
#    인자로 전달 (PR #1283 정책 반영, opt-in 명시).
# ─────────────────────────────────────────────────────────────────────────────
for cycle in fe rev plan; do
  TMP=$(make_tmp)
  export CYCLE_STATUS_PATH="$TMP/cycle-status.json"
  MOCK="$TMP/discord-reply.sh"
  LOG="$TMP/calls.log"
  make_mock "$MOCK" "$SNOWFLAKE" "$LOG"

  export DISCORD_REPLY_SH="$MOCK"
  export AGENT_LAUNCH_CREATE_FORUM_THREAD=1
  OUTPUT="$("$WRAPPER" "$cycle" --title "feat: $cycle parametrize" 2>/dev/null)"
  RC=$?
  unset DISCORD_REPLY_SH
  unset AGENT_LAUNCH_CREATE_FORUM_THREAD
  assert_exit "$cycle 정상 push exit 0" 0 $RC
  assert_contains "$cycle LAUNCH_THREAD_ID 노출" "$OUTPUT" "LAUNCH_THREAD_ID=$SNOWFLAKE"
  CALL_LINE="$(cat "$LOG" 2>/dev/null)"
  assert_contains "$cycle 호출 인자 --forum-post-auto-tag" "$CALL_LINE" "--forum-post-auto-tag $cycle"
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
# 8) (#1106) forum-post 실패 → --status-channel (DIGEST) graceful fallback
#    (PR #1283) DIGEST 본문 suffix = "[DIGEST 라우팅] forum-post 실패 (rc=N)"
# ─────────────────────────────────────────────────────────────────────────────
TMP=$(make_tmp)
export CYCLE_STATUS_PATH="$TMP/cycle-status.json"
MOCK="$TMP/discord-reply.sh"
LOG="$TMP/calls.log"
# Mock: --forum-post-auto-tag 면 exit 1, 그 외 (--status-channel) 는 exit 0.
# wrapper 가 fallback chain 으로 status-channel 을 호출하는지 검증.
cat > "$MOCK" <<'MOCK'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "__LOG__"
case "$1" in
  --forum-post-auto-tag)
    echo "forum push 실패 stub" >&2
    exit 1
    ;;
  *)
    # status-channel 호출 — 정상 응답 (메시지 id 같은 stub).
    echo "ok"
    exit 0
    ;;
esac
MOCK
# heredoc 안 변수 치환 회피 위해 sed 로 LOG 경로 주입.
sed -i.bak "s#__LOG__#$LOG#" "$MOCK" && rm -f "$MOCK.bak"
chmod +x "$MOCK"

export DISCORD_REPLY_SH="$MOCK"
export AGENT_LAUNCH_CREATE_FORUM_THREAD=1
OUTPUT_AND_STDERR="$("$WRAPPER" be --title "forum fail fallback" 2>&1)"
RC=$?
unset DISCORD_REPLY_SH
unset AGENT_LAUNCH_CREATE_FORUM_THREAD
assert_exit "forum 실패 시에도 wrapper exit 0 (Agent launch 차단 X)" 0 $RC
# LAUNCH_THREAD_ID 는 미출력 (forum 실패 → thread_id 없음).
assert_not_contains "forum 실패 시 LAUNCH_THREAD_ID 미출력" "$OUTPUT_AND_STDERR" "LAUNCH_THREAD_ID="
# fallback push 가 status-channel 로 발생했는지 (LOG 2번째 호출).
CALL_LINES="$(cat "$LOG" 2>/dev/null)"
assert_contains "DIGEST fallback: --status-channel 호출" "$CALL_LINES" "--status-channel"
assert_contains "DIGEST fallback: title 본문 포함" "$CALL_LINES" "forum fail fallback"
# PR #1283: 정확한 사유 suffix (forum-post 실제 실패 case)
assert_contains "DIGEST 본문 suffix: 정확한 사유 명시" "$CALL_LINES" "[DIGEST 라우팅] forum-post 실패"
# PR #1283: 오해 유발 표현 "forum push 실패" 가 본문에 박히면 안 됨 (회귀 가드).
assert_not_contains "회귀 가드: 오해 유발 'forum push 실패' suffix 제거" "$CALL_LINES" "DIGEST fallback (forum push 실패)"
rm -rf "$TMP"

# ─────────────────────────────────────────────────────────────────────────────
# 9) (PR #1283) pending-thread-id 부재 + AGENT_LAUNCH_CREATE_FORUM_THREAD 미설정
#    → 정책 skip → DIGEST 라우팅 본문 suffix = "cycle forum policy: pending 부재"
#    (사용자 directive 2026-05-29: "다이제스트에 forum push 실패라는데 실제로
#    포럼에 푸시도 없고" 오해 회귀 가드)
# ─────────────────────────────────────────────────────────────────────────────
TMP=$(make_tmp)
export CYCLE_STATUS_PATH="$TMP/cycle-status.json"
MOCK="$TMP/discord-reply.sh"
LOG="$TMP/calls.log"
# Mock: status-channel 호출 정상 응답 (forum-post-auto-tag 는 호출되면 안 됨).
make_mock "$MOCK" "$SNOWFLAKE" "$LOG"

export DISCORD_REPLY_SH="$MOCK"
# AGENT_LAUNCH_CREATE_FORUM_THREAD unset, --pending-thread-id 미전달.
OUTPUT_AND_STDERR="$("$WRAPPER" be --title "policy skip" 2>&1)"
RC=$?
unset DISCORD_REPLY_SH
assert_exit "정책 skip 시에도 wrapper exit 0" 0 $RC
assert_not_contains "정책 skip 시 LAUNCH_THREAD_ID 미출력" "$OUTPUT_AND_STDERR" "LAUNCH_THREAD_ID="
CALL_LINES="$(cat "$LOG" 2>/dev/null)"
# 정책 skip → forum-post 시도 자체 안 함 (회귀 가드)
assert_not_contains "정책 skip: --forum-post-auto-tag 호출 안 함" "$CALL_LINES" "--forum-post-auto-tag"
# DIGEST 라우팅만 발생
assert_contains "정책 skip: --status-channel 호출" "$CALL_LINES" "--status-channel"
# 정확한 사유 suffix
assert_contains "정책 skip: 사유 suffix 명시" "$CALL_LINES" "[DIGEST 라우팅] cycle forum policy"
# 오해 유발 표현 제거 (회귀 가드)
assert_not_contains "회귀 가드: 오해 유발 'forum push 실패' suffix 제거" "$CALL_LINES" "DIGEST fallback (forum push 실패)"
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
