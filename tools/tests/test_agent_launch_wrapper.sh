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

# B2 (2026-05-24): wrapper 가 per-cycle 채널에 launch 알림 자동 push 한다.
# 본 테스트 스위트는 set-active / stdout / exit code 만 검증하므로 push 단계는
# 명시적으로 skip — 그렇지 않으면 운영 .env 에서 real Discord 채널에 spam.
export AGENT_LAUNCH_NO_DISCORD=1

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
# 7) --refresh-backlog (2026-05-26) — backlog upsert.sh 가 호출되는지 검증
# ─────────────────────────────────────────────────────────────────────────────
# wrapper 는 cycle-backlog/upsert.sh 를 호출 시도. discord push 가 fail 해도
# wrapper exit 0 (graceful). 검증: cycle-status.json set-active 는 정상 + backlog
# 단계는 errors swallow.
TMP=$(make_tmp)
export CYCLE_STATUS_PATH="$TMP/cycle-status.json"
# upsert.sh 가 호출되더라도 GitHub 인증 없을 수 있어 mock gh 를 PATH 에 prepend.
# 빈 JSON 배열 반환 → 백로그 본문이 빈 셈션 + discord 호출은 CYCLE_BACKLOG_NO_DISCORD=1
# 로 skip → dry-run mode → stdout 본문 print 하고 exit 0.
MOCK_BIN="$TMP/bin"
mkdir -p "$MOCK_BIN"
cat > "$MOCK_BIN/gh" <<'EOF'
#!/usr/bin/env bash
echo "[]"
EOF
chmod +x "$MOCK_BIN/gh"
PATH="$MOCK_BIN:$PATH" CYCLE_BACKLOG_NO_DISCORD=1 \
  "$WRAPPER" plan --title "backlog refresh test" --refresh-backlog \
  >/dev/null 2>&1
RC=$?
assert_exit "--refresh-backlog graceful (exit 0)" 0 $RC
# cycle-status.json 은 정상 set-active 됐어야 함.
if [[ -f "$CYCLE_STATUS_PATH" ]]; then
  CONTENT="$(cat "$CYCLE_STATUS_PATH")"
  assert_contains "--refresh-backlog + set-active 동시 수행" "$CONTENT" "backlog refresh test"
else
  FAIL=$((FAIL + 1))
  FAILURES+=("--refresh-backlog 케이스에서 cycle-status.json 없음")
  echo "FAIL: --refresh-backlog 케이스에서 cycle-status.json 없음"
fi
rm -rf "$TMP"

# ─────────────────────────────────────────────────────────────────────────────
# 8) --no-refresh-backlog — CYCLE_BACKLOG_REFRESH_DEFAULT=1 에서 skip 검증
# ─────────────────────────────────────────────────────────────────────────────
# default 가 ON 일 때 --no-refresh-backlog 가 강제 OFF 시키는지 확인.
TMP=$(make_tmp)
export CYCLE_STATUS_PATH="$TMP/cycle-status.json"
CYCLE_BACKLOG_REFRESH_DEFAULT=1 \
  "$WRAPPER" rev --title "no refresh test" --no-refresh-backlog \
  >/dev/null 2>&1
RC=$?
assert_exit "--no-refresh-backlog default ON override (exit 0)" 0 $RC
rm -rf "$TMP"

# ─────────────────────────────────────────────────────────────────────────────
# 9) directive-board mismatch detect — diff 헬퍼 stderr emit (#1129 impl PR 3)
# ─────────────────────────────────────────────────────────────────────────────
# wrapper 는 set-active 직후 jsonl-forum-diff.sh 호출. diff 헬퍼 stdout 은
# wrapper stderr 로 redirect (stdout 계약 보존). 본 테스트는:
#   (a) jsonl 에 mismatch entry 존재 시 stderr 에 [!] warning 라인 emit
#   (b) stdout 의 첫 블록은 confirm/echo-prompt 그대로 유지 (mismatch 미혼합)
TMP=$(make_tmp)
export CYCLE_STATUS_PATH="$TMP/cycle-status.json"
# jsonl: mismatch 1건 (jsonl=완료 vs forum=진행 중).
DIR_JSONL="$TMP/directive-board.jsonl"
printf '%s\n' '{"ts":"2026-05-27 10:00 KST","summary":"WRP mismatch","status":"완료","message_id":"m-wrp","thread_id":"t-wrp"}' > "$DIR_JSONL"
# fake discord-reply.sh — --forum-state-dump directive 응답.
DUMP_FILE="$TMP/dump.jsonl"
printf '%s\n' '{"thread_id":"t-wrp","name":"a","tags":["진행 중"]}' > "$DUMP_FILE"
FAKE_REPLY="$TMP/discord-reply.sh"
cat > "$FAKE_REPLY" <<EOF
#!/usr/bin/env bash
if [[ "\${1:-}" == "--forum-state-dump" && "\${2:-}" == "directive" ]]; then
  cat "$DUMP_FILE"
  exit 0
fi
exit 1
EOF
chmod +x "$FAKE_REPLY"

# diff 헬퍼는 DIRECTIVE_BOARD_JSONL_PATH + DISCORD_REPLY_BIN env 사용.
STDOUT_FILE="$TMP/stdout.txt"
STDERR_FILE="$TMP/stderr.txt"
DIRECTIVE_BOARD_JSONL_PATH="$DIR_JSONL" DISCORD_REPLY_BIN="$FAKE_REPLY" \
  "$WRAPPER" plan --title "wrapper mismatch detect" \
  > "$STDOUT_FILE" 2> "$STDERR_FILE"
RC=$?
assert_exit "mismatch 환경에서도 wrapper exit 0" 0 $RC
STDOUT_OUTPUT="$(cat "$STDOUT_FILE")"
STDERR_OUTPUT="$(cat "$STDERR_FILE")"
assert_contains "stderr 에 mismatch warning" "$STDERR_OUTPUT" "directive-board mismatch detected"
assert_contains "stderr 에 thread_id=t-wrp" "$STDERR_OUTPUT" "thread_id=t-wrp"
# stdout 첫 블록은 confirm 그대로.
assert_contains "stdout confirm 유지" "$STDOUT_OUTPUT" "cycle-status set-active OK"
# stdout 에는 mismatch warning 이 섞이면 안 됨 (호출자 stdout grep 시 prompt 오염 방지).
if [[ "$STDOUT_OUTPUT" == *"directive-board mismatch detected"* ]]; then
  FAIL=$((FAIL + 1))
  FAILURES+=("mismatch 가 stdout 으로 새어 나옴 (prompt 오염)")
  echo "FAIL: mismatch 가 stdout 으로 새어 나옴"
else
  PASS=$((PASS + 1))
  echo "PASS: stdout 에 mismatch 미혼합"
fi
rm -rf "$TMP"

# ─────────────────────────────────────────────────────────────────────────────
# #1385: --directive-id 가 있으면 directive-board.jsonl 의 정제 summary 를
#   forum 제목으로 사용 (sub-agent forum 제목도 LLM 정제 제목 — 가독성).
#   jq 있을 때만 검증 (graceful fallback 은 자명).
# ─────────────────────────────────────────────────────────────────────────────
if command -v jq >/dev/null 2>&1; then
  TMP=$(make_tmp)
  export CYCLE_STATUS_PATH="$TMP/cycle-status.json"
  DIR_JSONL="$TMP/directive-board.jsonl"
  printf '%s\n' '{"message_id":"mid-999","summary":"브라우저 자동 QA 환경 도입","status":"대기","last_updated_kst":"2026-05-30 18:00 KST","thread_id":"t-999"}' > "$DIR_JSONL"
  CAP="$TMP/cap.txt"
  FAKE_REPLY="$TMP/discord-reply.sh"
  cat > "$FAKE_REPLY" <<EOF
#!/usr/bin/env bash
printf '%s\n' "\$@" > "$CAP"
echo 555000111222333444
EOF
  chmod +x "$FAKE_REPLY"

  AGENT_LAUNCH_NO_DISCORD=0 \
  DIRECTIVE_BOARD_JSONL_PATH="$DIR_JSONL" \
  DISCORD_REPLY_SH="$FAKE_REPLY" \
    "$WRAPPER" --register-pending be \
      --title "장황하고 raw 한 원본 메시지 그대로의 제목 — 가독성 떨어짐" \
      --directive-id "mid-999" >/dev/null 2>&1
  CAP_CONTENT="$(cat "$CAP" 2>/dev/null || true)"
  assert_contains "#1385 정제 summary 가 forum 제목으로" "$CAP_CONTENT" "브라우저 자동 QA 환경 도입"
  if [[ "$CAP_CONTENT" == *"장황하고 raw 한 원본"* ]]; then
    FAIL=$((FAIL + 1))
    FAILURES+=("#1385 raw 제목이 정제 제목으로 대체되지 않음")
    echo "FAIL: #1385 raw 제목 미대체"
  else
    PASS=$((PASS + 1))
    echo "PASS: #1385 raw 제목 → 정제 제목 대체"
  fi
  rm -rf "$TMP"
fi

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
