#!/usr/bin/env bash
# test_flock_fallback.sh — flock-fallback.sh 스모크 테스트
#
# 사용: bash tools/rev-queue/tests/test_flock_fallback.sh
# 종료 코드: 0 = 전체 통과, 1 = 하나라도 실패
#
# 시나리오:
#   1. detect — flock 의존 스크립트 → exit 0
#   2. detect — flock 비의존 스크립트 → exit 1
#   3. detect — 파일 없음 → exit 2
#   4. detect — 주석 안 flock 무시 (flock 의존 아님 분류)
#   5. exec — 로컬 flock 가용 시 local_exec 호출 + stdout/exit code 전달
#   6. exec — 로컬 flock 부재 + 스크립트 flock 비의존 → local_exec (불필요 ssh 회피)
#   7. exec — 로컬 flock 부재 + flock 의존 + NCP 미설정 → exit 3 + 안내 메시지
#   8. exec — 강제 remote 모드 + NCP 설정 → ssh mock 호출 + stdout/exit code 전달
#   9. exec — 강제 remote 모드 + NCP 미설정 → exit 3
#   10. exec — 인자 forward (특수문자 quote)

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FLOCK_FALLBACK="$SCRIPT_DIR/../flock-fallback.sh"

if [[ ! -x "$FLOCK_FALLBACK" ]]; then
  echo "FAIL: $FLOCK_FALLBACK not executable" >&2
  exit 1
fi

TMP_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_DIR"' EXIT

PASS=0
FAIL=0

assert_eq() {
  local name="$1"
  local expected="$2"
  local actual="$3"
  if [[ "$expected" == "$actual" ]]; then
    echo "PASS: $name"
    PASS=$((PASS + 1))
  else
    echo "FAIL: $name"
    echo "  expected: $expected"
    echo "  actual:   $actual"
    FAIL=$((FAIL + 1))
  fi
}

assert_contains() {
  local name="$1"
  local pattern="$2"
  local actual="$3"
  if echo "$actual" | grep -qE "$pattern"; then
    echo "PASS: $name"
    PASS=$((PASS + 1))
  else
    echo "FAIL: $name"
    echo "  pattern:  $pattern"
    echo "  actual:"
    echo "$actual" | sed 's/^/    /'
    FAIL=$((FAIL + 1))
  fi
}

# 테스트 fixture 스크립트 생성.
make_flock_script() {
  local path="$1"
  cat > "$path" <<'SH'
#!/usr/bin/env bash
# 이 스크립트는 flock 명령에 의존한다.
exec 9>/tmp/test.lock
flock -n 9 || exit 1
echo "locked OK $*"
SH
  chmod +x "$path"
}

make_no_flock_script() {
  local path="$1"
  cat > "$path" <<'SH'
#!/usr/bin/env bash
# 이 스크립트는 flock 비의존.
echo "no-lock OK $*"
SH
  chmod +x "$path"
}

make_commented_flock_script() {
  local path="$1"
  cat > "$path" <<'SH'
#!/usr/bin/env bash
# flock 언급은 주석에만 — 실제 flock 호출 없음.
echo "comment-only OK"
SH
  chmod +x "$path"
}

# ssh mock — args + stdin 을 echo 하고 환경변수 SSH_MOCK_EXIT 으로 exit code 결정.
make_ssh_mock() {
  local mock_path="$1"
  cat > "$mock_path" <<'MOCK'
#!/usr/bin/env bash
# ssh mock — 호출 args 를 로그 파일에 기록.
echo "SSH_MOCK_INVOKED $*" >&2
echo "ssh-mock-stdout"
exit "${SSH_MOCK_EXIT:-0}"
MOCK
  chmod +x "$mock_path"
}

# ────────────────────────────────────────────────────────────
# Test 1: detect — flock 의존 스크립트 → exit 0
# ────────────────────────────────────────────────────────────
SCRIPT_A="$TMP_DIR/uses_flock.sh"
make_flock_script "$SCRIPT_A"
"$FLOCK_FALLBACK" detect "$SCRIPT_A" >/dev/null 2>&1
assert_eq "detect — flock 의존 스크립트 exit 0" "0" "$?"

# ────────────────────────────────────────────────────────────
# Test 2: detect — flock 비의존 스크립트 → exit 1
# ────────────────────────────────────────────────────────────
SCRIPT_B="$TMP_DIR/no_flock.sh"
make_no_flock_script "$SCRIPT_B"
"$FLOCK_FALLBACK" detect "$SCRIPT_B" >/dev/null 2>&1
assert_eq "detect — flock 비의존 스크립트 exit 1" "1" "$?"

# ────────────────────────────────────────────────────────────
# Test 3: detect — 파일 없음 → exit 2
# ────────────────────────────────────────────────────────────
"$FLOCK_FALLBACK" detect "$TMP_DIR/nonexistent.sh" >/dev/null 2>&1
assert_eq "detect — 파일 없음 exit 2" "2" "$?"

# ────────────────────────────────────────────────────────────
# Test 4: detect — 주석 안 flock 무시
# ────────────────────────────────────────────────────────────
SCRIPT_C="$TMP_DIR/commented_flock.sh"
make_commented_flock_script "$SCRIPT_C"
"$FLOCK_FALLBACK" detect "$SCRIPT_C" >/dev/null 2>&1
assert_eq "detect — 주석 안 flock 무시 exit 1" "1" "$?"

# ────────────────────────────────────────────────────────────
# Test 5: exec — 로컬 flock 가용 시 local_exec
# (로컬 시스템에 flock 이 있다면 실제 실행 검증, 없으면 skip)
# ────────────────────────────────────────────────────────────
if command -v flock >/dev/null 2>&1; then
  SCRIPT_D="$TMP_DIR/exec_no_flock.sh"
  make_no_flock_script "$SCRIPT_D"
  actual=$("$FLOCK_FALLBACK" exec "$SCRIPT_D" arg1 arg2 2>&1)
  assert_contains "exec — 로컬 flock 가용 시 stdout 전달" "no-lock OK arg1 arg2" "$actual"
else
  echo "SKIP: exec — 로컬 flock 부재 환경 (Test 5 skip)"
fi

# ────────────────────────────────────────────────────────────
# flock 부재 시뮬레이션:
#   기존 PATH 유지 (bash/env/grep 등 표준 도구 필요) + flock 가리는 false wrapper 추가.
#   isolated bin 디렉토리에 'flock' 이라는 이름의 stub (exit 127) 을 두지 않고,
#   대신 command -v 가 false 를 반환하도록 PATH 에 비어있는 디렉토리만 prepend 하는 것은
#   기존 PATH 상의 실제 flock 을 가리지 못한다. 따라서 PATH 자체를 우리 dir 만 두고
#   필요 도구 (bash/env/grep) 의 심볼릭 링크를 별도 dir 에 함께 둔다.
# ────────────────────────────────────────────────────────────
NOFLOCK_BIN="$TMP_DIR/noflock_bin"
mkdir -p "$NOFLOCK_BIN"
# 표준 유틸 링크 (flock 만 의도적으로 제외).
for tool in bash env grep sh sed awk cat date jq ssh dirname basename pwd cd command sort uniq tr cut; do
  resolved=$(command -v "$tool" 2>/dev/null || true)
  if [[ -n "$resolved" ]]; then
    ln -sf "$resolved" "$NOFLOCK_BIN/$tool"
  fi
done

# ────────────────────────────────────────────────────────────
# Test 6: exec — 로컬 flock 부재 + flock 비의존 → local_exec
# ────────────────────────────────────────────────────────────
SCRIPT_E="$TMP_DIR/no_flock_exec.sh"
make_no_flock_script "$SCRIPT_E"
actual=$(PATH="$NOFLOCK_BIN" "$FLOCK_FALLBACK" exec "$SCRIPT_E" hello 2>&1)
exit_code=$?
assert_contains "exec — flock 부재 + 비의존 → 로컬 실행" "no-lock OK hello" "$actual"
assert_eq "exec — flock 부재 + 비의존 → exit 0" "0" "$exit_code"

# ────────────────────────────────────────────────────────────
# Test 7: exec — 로컬 flock 부재 + flock 의존 + NCP 미설정 → exit 3 + 안내
# ────────────────────────────────────────────────────────────
SCRIPT_F="$TMP_DIR/uses_flock_exec.sh"
make_flock_script "$SCRIPT_F"
actual=$(PATH="$NOFLOCK_BIN" env -u MOBRUJI_NCP_HOST "$FLOCK_FALLBACK" exec "$SCRIPT_F" 2>&1)
exit_code=$?
assert_eq "exec — flock 부재 + 의존 + NCP 미설정 exit 3" "3" "$exit_code"
assert_contains "exec — graceful warning (MOBRUJI_NCP_HOST 안내)" "MOBRUJI_NCP_HOST" "$actual"
assert_contains "exec — 수동 우회 안내 포함" "ssh.*ncp" "$actual"

# ────────────────────────────────────────────────────────────
# Test 8: exec — 강제 remote 모드 + NCP 설정 → ssh mock 호출
# ────────────────────────────────────────────────────────────
SSH_MOCK="$TMP_DIR/ssh_mock"
make_ssh_mock "$SSH_MOCK"
actual=$(SSH_BIN="$SSH_MOCK" \
         MOBRUJI_NCP_HOST="testuser@ncp-test" \
         FLOCK_FALLBACK_FORCE_REMOTE=1 \
         "$FLOCK_FALLBACK" exec "$SCRIPT_F" 2>&1)
exit_code=$?
assert_eq "exec — 강제 remote + NCP 설정 exit 0 (ssh mock)" "0" "$exit_code"
assert_contains "exec — ssh mock 호출 확인" "SSH_MOCK_INVOKED" "$actual"
assert_contains "exec — ssh mock host 인자" "testuser@ncp-test" "$actual"
assert_contains "exec — ssh mock stdout 전달" "ssh-mock-stdout" "$actual"

# ssh mock exit code 보존 검증.
actual=$(SSH_BIN="$SSH_MOCK" \
         SSH_MOCK_EXIT=42 \
         MOBRUJI_NCP_HOST="testuser@ncp-test" \
         FLOCK_FALLBACK_FORCE_REMOTE=1 \
         "$FLOCK_FALLBACK" exec "$SCRIPT_F" 2>&1)
exit_code=$?
assert_eq "exec — ssh mock exit code 보존 (42)" "42" "$exit_code"

# ────────────────────────────────────────────────────────────
# Test 9: exec — 강제 remote 모드 + NCP 미설정 → exit 3
# ────────────────────────────────────────────────────────────
actual=$(env -u MOBRUJI_NCP_HOST \
         FLOCK_FALLBACK_FORCE_REMOTE=1 \
         "$FLOCK_FALLBACK" exec "$SCRIPT_F" 2>&1)
exit_code=$?
assert_eq "exec — 강제 remote + NCP 미설정 exit 3" "3" "$exit_code"

# ────────────────────────────────────────────────────────────
# Test 10: exec — 인자 forward (특수문자 quote)
# ────────────────────────────────────────────────────────────
actual=$(SSH_BIN="$SSH_MOCK" \
         MOBRUJI_NCP_HOST="testuser@ncp-test" \
         FLOCK_FALLBACK_FORCE_REMOTE=1 \
         "$FLOCK_FALLBACK" exec "$SCRIPT_F" "arg with space" "arg2" 2>&1)
assert_contains "exec — 인자 quote forward (공백 포함)" "arg with space" "$actual"

# ────────────────────────────────────────────────────────────
# Test 11: 잘못된 mode → exit 2
# ────────────────────────────────────────────────────────────
"$FLOCK_FALLBACK" garbage 2>/dev/null
assert_eq "잘못된 mode exit 2" "2" "$?"

# ────────────────────────────────────────────────────────────
# Test 12: 인자 없음 → exit 2
# ────────────────────────────────────────────────────────────
"$FLOCK_FALLBACK" 2>/dev/null
assert_eq "인자 없음 exit 2" "2" "$?"

# ────────────────────────────────────────────────────────────
# 결과 요약
# ────────────────────────────────────────────────────────────
echo
echo "─────────────────────────────────"
echo "Total: $((PASS + FAIL)) / Pass: $PASS / Fail: $FAIL"
if [[ "$FAIL" -gt 0 ]]; then
  exit 1
fi
exit 0
