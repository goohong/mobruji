#!/usr/bin/env bash
# test_discord_reply_env_defense.sh — #1025 후속 read_env_value 사고 가드 회귀.
#
# 배경 (2026-05-24 사고):
#   PR #1025 (NOTIFY → DIGEST env 키 rename) 머지 후 production .env 에
#   신규 키 DIGEST_CHANNEL_ID 미설정 상태. discord-reply.sh 의
#   `grep -E "^DIGEST_CHANNEL_ID=" .env` 가 no-match → exit 1 → set -euo
#   pipefail + command substitution 안 → 스크립트 전체 즉시 종료. helper
#   본답 push 자체 silent fail. 사용자 "정신 없니" 사고의 root cause.
#
# 본 회귀 가드:
#   read_env_value 함수가 env 키 부재 / .env 파일 부재 시 빈 문자열 + exit 0
#   을 반환하는지 검증. 호출자 (TOKEN / CHANNEL resolve) 가 빈 값에 대해
#   명시적 에러 메시지를 띄우는 정상 경로로 진입.
#
# 실행:
#   bash tools/discord-daemon/tests/test_discord_reply_env_defense.sh
#
# 종속: bash 5+, grep, cut, tr (POSIX 기본).
#
# 룰: CLAUDE.md §16 검증 의무 — 모든 fix 는 회귀 가드 동반.

set -uo pipefail  # -e 의도적 제외 — case 실패가 후속 case 차단하면 안 됨.

SCRIPT_PATH="$(cd "$(dirname "$0")/../.." && pwd)/discord-daemon/discord-reply.sh"
if [[ ! -f "$SCRIPT_PATH" ]]; then
  # Fallback path — sub-agent / CI 환경에서 호출 시.
  SCRIPT_PATH="$(cd "$(dirname "$0")/.." && pwd)/discord-reply.sh"
fi

if [[ ! -f "$SCRIPT_PATH" ]]; then
  echo "FAIL: discord-reply.sh 경로 찾을 수 없음 (tried $SCRIPT_PATH)" >&2
  exit 1
fi

PASS_COUNT=0
FAIL_COUNT=0

assert_eq() {
  local expected="$1"
  local actual="$2"
  local label="$3"
  if [[ "$expected" == "$actual" ]]; then
    echo "  PASS: $label"
    PASS_COUNT=$((PASS_COUNT + 1))
  else
    echo "  FAIL: $label" >&2
    echo "    expected: ${expected@Q}" >&2
    echo "    actual:   ${actual@Q}" >&2
    FAIL_COUNT=$((FAIL_COUNT + 1))
  fi
}

# discord-reply.sh 로부터 read_env_value 함수만 추출해 in-process 실행.
# 전체 스크립트를 source 하면 .env 검증 / mode dispatch 가 끼어들어 부작용.
extract_read_env_value() {
  # 함수 정의 + 종료까지 추출 (line 100 부근 read_env_value() { ... }).
  # bash 함수 정의 닫기 (`^}$`) 까지 sed 로 추출.
  sed -n '/^read_env_value() {$/,/^}$/p' "$SCRIPT_PATH"
}

run_case() {
  local label="$1"
  shift
  echo "case: $label"
  "$@"
}

# ─── case 1: env 키 있음 → 값 반환 + exit 0 ──────────────────────────────────
case_key_present() {
  local tmp_env
  tmp_env=$(mktemp)
  printf 'DISCORD_BOT_TOKEN=abc123\nMOBRUJI_CHANNEL_ID=12345678901234567\n' > "$tmp_env"

  local output rc
  output=$(
    ENV_PATH="$tmp_env"
    eval "$(extract_read_env_value)"
    read_env_value MOBRUJI_CHANNEL_ID
  )
  rc=$?
  rm -f "$tmp_env"

  assert_eq "12345678901234567" "$output" "키 존재 시 값 반환"
  assert_eq "0" "$rc" "키 존재 시 exit 0"
}

# ─── case 2: env 키 없음 → 빈 문자열 + exit 0 (사고 가드 핵심) ──────────────
case_key_missing() {
  local tmp_env
  tmp_env=$(mktemp)
  printf 'DISCORD_BOT_TOKEN=abc123\n' > "$tmp_env"

  local output rc
  # set -e 환경 흉내 — 사고 재현. 실제 script 는 `set -euo pipefail`.
  output=$(
    set -euo pipefail
    ENV_PATH="$tmp_env"
    eval "$(extract_read_env_value)"
    read_env_value DIGEST_CHANNEL_ID  # ← #1025 사고 키.
  )
  rc=$?
  rm -f "$tmp_env"

  assert_eq "" "$output" "키 부재 시 빈 문자열"
  assert_eq "0" "$rc" "키 부재 시 exit 0 (set -e 무력화 — #1025 사고 가드)"
}

# ─── case 3: env 파일 없음 → 빈 문자열 + exit 0 ──────────────────────────────
case_env_file_missing() {
  local output rc
  output=$(
    set -euo pipefail
    ENV_PATH="/nonexistent/path/.env"
    eval "$(extract_read_env_value)"
    read_env_value ANY_KEY
  )
  rc=$?

  assert_eq "" "$output" ".env 부재 시 빈 문자열"
  assert_eq "0" "$rc" ".env 부재 시 exit 0"
}

# ─── case 4: pipefail + set -e + grep no-match → script 종료 X (E2E) ──────
# 실제 discord-reply.sh 를 .env 가 DIGEST_CHANNEL_ID 누락 상태로 호출했을 때
# read_env_value 가 graceful → CHANNEL fallback chain 으로 진입 → MOBRUJI_CHANNEL_ID
# 가 있으면 정상 push 시도 (token / 네트워크 없으면 push 자체는 실패하지만
# 그 fail 모드는 read_env_value 가드 회귀 가드와는 무관).
case_e2e_grep_no_match_does_not_kill_script() {
  local tmp_env tmp_out tmp_err
  tmp_env=$(mktemp)
  tmp_out=$(mktemp)
  tmp_err=$(mktemp)

  # DIGEST_CHANNEL_ID / NOTIFY_CHANNEL_ID 모두 없는 .env — #1025 사고 재현.
  # MOBRUJI_CHANNEL_ID 가 있어 채널 resolve 자체는 성공해야 함.
  printf 'DISCORD_BOT_TOKEN=fake_token\nMOBRUJI_CHANNEL_ID=12345678901234567\n' > "$tmp_env"

  # discord-reply.sh 실행. push 자체는 curl 가 fake_token / fake channel 로
  # 401/403 받지만, read_env_value 단계는 통과해야 함 (그 너머 단계의 종료
  # 코드는 본 회귀 가드와 무관).
  # 단순 reply mode + --no-reply 로 helper-current-target.txt 등 부수 파일
  # 의존 회피.
  DISCORD_DAEMON_ENV_PATH="$tmp_env" \
    HELPER_THREAD_FILE="/dev/null" \
    LAST_USER_MSG_ID_FILE="/dev/null" \
    HELPER_TARGET_FILE="/dev/null" \
    HELPER_QUEUE_FILE="/dev/null" \
    DISCORD_RETRY_MAX=1 \
    DISCORD_RETRY_BASE_SEC=0 \
    bash "$SCRIPT_PATH" --no-reply "test message" \
    > "$tmp_out" 2> "$tmp_err" || true  # push 자체 실패는 본 case 와 무관.

  local err_contents
  err_contents=$(cat "$tmp_err")

  # 핵심 검증: read_env_value 단계가 silent kill 했다면 stderr 가 비어
  # 있고 stdout 도 비어 있음 (script 가 그냥 죽음). 가드가 작동하면 stderr 에
  # curl 관련 메시지 / payload / retry warning 중 무엇이라도 있어야 함 (혹은
  # 정상 응답 본문이 stdout).
  local progressed=0
  if [[ -n "$err_contents" ]] || [[ -s "$tmp_out" ]]; then
    progressed=1
  fi

  # DIGEST_CHANNEL_ID 부재가 직접 에러로 출력되지 않아야 함 — graceful 가드
  # 의 의도는 "키 부재는 정상 fallback path, 명시적 에러 메시지 없음".
  local digest_blocked=0
  if echo "$err_contents" | grep -q "MOBRUJI_CHANNEL_ID / DIGEST_CHANNEL_ID"; then
    digest_blocked=1
  fi

  rm -f "$tmp_env" "$tmp_out" "$tmp_err"

  assert_eq "1" "$progressed" "E2E: read_env_value 가 silent kill 안 함 (script 진행)"
  assert_eq "0" "$digest_blocked" "E2E: DIGEST 부재가 명시적 에러 차단 안 함 (MOBRUJI fallback 진입)"
}

# ─── case 5: 호출자에서 `|| true` 가드 없어도 안전 (영구 가드 회귀) ────────
case_no_external_or_true_needed() {
  local tmp_env
  tmp_env=$(mktemp)
  printf 'DISCORD_BOT_TOKEN=abc\n' > "$tmp_env"

  # `|| true` 없이 호출 — 함수 자체가 graceful 이라 set -e 환경에서도 안전.
  local result rc
  result=$(
    set -euo pipefail
    ENV_PATH="$tmp_env"
    eval "$(extract_read_env_value)"
    # 핵심: 호출자가 `|| true` 를 안 붙임. 기존 패턴은 누락 시 사고 → 함수
    # 안으로 가드를 옮긴 의도가 본 case 로 검증.
    value=$(read_env_value NONEXISTENT_KEY)
    echo "after_call=ok value=${value:-empty}"
  )
  rc=$?
  rm -f "$tmp_env"

  assert_eq "after_call=ok value=empty" "$result" "호출자 || true 없이 set -e 환경 통과"
  assert_eq "0" "$rc" "호출자 || true 없이 exit 0"
}

# ─── run ──────────────────────────────────────────────────────────────────────

echo "=== discord-reply.sh read_env_value 회귀 가드 (#1025 후속) ==="
echo "SCRIPT_PATH=$SCRIPT_PATH"
echo

run_case "키 존재 → 값 반환"           case_key_present
run_case "키 부재 → 빈 문자열 (사고 가드)" case_key_missing
run_case ".env 파일 부재 → 빈 문자열"   case_env_file_missing
run_case "E2E grep no-match 가 script 안 죽임" case_e2e_grep_no_match_does_not_kill_script
run_case "호출자 || true 없이도 안전"    case_no_external_or_true_needed

echo
echo "=== 결과: PASS=$PASS_COUNT FAIL=$FAIL_COUNT ==="

if [[ "$FAIL_COUNT" -gt 0 ]]; then
  exit 1
fi
exit 0
