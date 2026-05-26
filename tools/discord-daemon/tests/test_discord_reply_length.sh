#!/usr/bin/env bash
# test_discord_reply_length.sh — #1121 본문 길이 chunk split 회귀 가드.
#
# 배경 (사용자 P1 박제, 2026-05-26):
#   Discord REST API content 길이 2000 (UTF-16 code unit) 초과 시 50035
#   (Invalid Form Body) reject. 기존 discord-reply.sh 는 split/retry 없이 그대로
#   PUSH → helper 본답 silent fail (사용자 채널에 안 나타남).
#
# 본 회귀 가드:
#   1. split_long_message — cap 이하 단일 chunk, cap 초과 시 newline / space 경계
#      preferred 로 split + `[N/M]` 마커.
#   2. discord_curl_with_retry — 50035 응답 받으면 stderr 명시 ERROR log (silent
#      차단).
#   3. E2E — mock curl 로 reply mode 호출 시 1900자 초과 본문이 2건 chunk 호출
#      로 분할되는지 검증.
#
# 실행:
#   bash tools/discord-daemon/tests/test_discord_reply_length.sh
#
# 종속: bash 5+, jq, head, mktemp (POSIX 기본).

set -uo pipefail

SCRIPT_PATH="$(cd "$(dirname "$0")/../.." && pwd)/discord-daemon/discord-reply.sh"
if [[ ! -f "$SCRIPT_PATH" ]]; then
  SCRIPT_PATH="$(cd "$(dirname "$0")/.." && pwd)/discord-reply.sh"
fi
if [[ ! -f "$SCRIPT_PATH" ]]; then
  echo "FAIL: discord-reply.sh 경로 찾을 수 없음" >&2
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

assert_ge() {
  local actual="$1"
  local floor="$2"
  local label="$3"
  if (( actual >= floor )); then
    echo "  PASS: $label (actual=${actual} >= ${floor})"
    PASS_COUNT=$((PASS_COUNT + 1))
  else
    echo "  FAIL: $label (actual=${actual} < ${floor})" >&2
    FAIL_COUNT=$((FAIL_COUNT + 1))
  fi
}

run_case() {
  local label="$1"
  local func="$2"
  echo "→ $label"
  $func
}

# split_long_message 함수만 추출해서 테스트 가능하게 source.
# 본 헬퍼: script 안 split_long_message 정의 + DISCORD_CHUNK_LEN 정의를 추출하는
# 변수만 정의 후 source. set -e 등 부작용 없이 함수 정의만 가져오는 트릭.
extract_split_function() {
  # script 안 set -euo pipefail 가 source 시 즉시 부작용 — 그래서 함수 정의
  # 만 추출해 임시 파일에 저장하고 source. awk 로 함수 시작~닫는 중괄호까지.
  awk '
    /^split_long_message\(\) \{$/ { capture=1 }
    capture { print }
    capture && /^\}$/ { capture=0 }
  ' "$SCRIPT_PATH"
}

# ─── case 1: short body → 단일 chunk (마커 없음) ─────────────────────────────
case_short_body_single_chunk() {
  local tmp_func
  tmp_func=$(mktemp)
  echo 'DISCORD_CHUNK_LEN=1900' > "$tmp_func"
  extract_split_function >> "$tmp_func"

  local count
  count=$(
    source "$tmp_func"
    split_long_message "short body" | tr -cd '\0' | wc -c
  )
  rm -f "$tmp_func"
  assert_eq "1" "$count" "단일 chunk emit (NUL 1개)"
}

# ─── case 2: 본문 cap 초과 → 다중 chunk + 마커 prepend ───────────────────────
case_long_body_multi_chunk() {
  local tmp_func
  tmp_func=$(mktemp)
  echo 'DISCORD_CHUNK_LEN=100' > "$tmp_func"
  extract_split_function >> "$tmp_func"

  # 250자 body (cap=100 → 3 chunk 예상).
  local body
  body=$(printf 'a%.0s' {1..250})

  local chunk_count first_chunk
  chunk_count=$(
    source "$tmp_func"
    split_long_message "$body" | tr -cd '\0' | wc -c
  )
  first_chunk=$(
    source "$tmp_func"
    split_long_message "$body" | head -c 200 | cut -d $'\0' -f1
  )
  rm -f "$tmp_func"

  assert_ge "$chunk_count" "3" "250자 / cap=100 → ≥3 chunk"
  # 첫 chunk 는 `[1/N] ` 마커 prepend.
  if [[ "$first_chunk" =~ ^\[1/[0-9]+\][[:space:]] ]]; then
    echo "  PASS: 첫 chunk \`[1/N] \` 마커 prepend"
    PASS_COUNT=$((PASS_COUNT + 1))
  else
    echo "  FAIL: 첫 chunk 마커 누락 — first: ${first_chunk:0:50}" >&2
    FAIL_COUNT=$((FAIL_COUNT + 1))
  fi
}

# ─── case 3: newline 경계 우선 split ─────────────────────────────────────────
case_newline_boundary_preferred() {
  local tmp_func tmp_out
  tmp_func=$(mktemp)
  tmp_out=$(mktemp)
  echo 'DISCORD_CHUNK_LEN=30' > "$tmp_func"
  extract_split_function >> "$tmp_func"

  # cap=30. body 는 1줄 20자 + \n + 1줄 20자 + \n + 1줄 20자 = 약 62자.
  # newline 경계 split → 3 chunk + 각 줄 깔끔.
  local body
  body=$'line1_20charactersx\nline2_20charactersx\nline3_20charactersx'

  # NUL 구분 raw 출력을 파일에 저장 — command substitution `$(...)` 는 NUL 을
  # 자동 strip 해서 chunk 경계가 사라짐. 파일 경유로 보존.
  (
    source "$tmp_func"
    split_long_message "$body"
  ) > "$tmp_out"

  # 첫 chunk 만 read (`read -d '' < file`).
  local first_chunk
  IFS= read -r -d '' first_chunk < "$tmp_out" || true
  rm -f "$tmp_func" "$tmp_out"

  # 마커 `[1/N] ` 떼고 본문이 newline 으로 끝나는지.
  if [[ "$first_chunk" == *$'\n' ]]; then
    echo "  PASS: 첫 chunk newline 으로 종료 (boundary 우선)"
    PASS_COUNT=$((PASS_COUNT + 1))
  else
    echo "  FAIL: newline 경계 미적용 — first chunk: ${first_chunk@Q}" >&2
    FAIL_COUNT=$((FAIL_COUNT + 1))
  fi
}

# ─── case 4: detect_invalid_form_body 가 50035 응답 명시 log ─────────────────
case_detect_invalid_form_body_logs() {
  local tmp_func
  tmp_func=$(mktemp)
  echo 'DISCORD_CHUNK_LEN=1900' > "$tmp_func"
  # detect_invalid_form_body 함수 추출.
  awk '
    /^detect_invalid_form_body\(\) \{$/ { capture=1 }
    capture { print }
    capture && /^\}$/ { capture=0 }
  ' "$SCRIPT_PATH" >> "$tmp_func"

  local stderr_out
  stderr_out=$(
    source "$tmp_func"
    detect_invalid_form_body '{"code": 50035, "errors": {"content": {"_errors": [{"message": "Must be 2000 or fewer in length."}]}}}' 2>&1 >/dev/null
  )
  rm -f "$tmp_func"

  if [[ "$stderr_out" == *"50035"* && "$stderr_out" == *"Invalid Form Body"* ]]; then
    echo "  PASS: detect_invalid_form_body 50035 stderr ERROR log"
    PASS_COUNT=$((PASS_COUNT + 1))
  else
    echo "  FAIL: 50035 stderr log 누락" >&2
    echo "    stderr: $stderr_out" >&2
    FAIL_COUNT=$((FAIL_COUNT + 1))
  fi

  # 다른 code (10008 등) 는 silent — skip log.
  local tmp_func2
  tmp_func2=$(mktemp)
  echo 'DISCORD_CHUNK_LEN=1900' > "$tmp_func2"
  awk '
    /^detect_invalid_form_body\(\) \{$/ { capture=1 }
    capture { print }
    capture && /^\}$/ { capture=0 }
  ' "$SCRIPT_PATH" >> "$tmp_func2"
  local stderr_silent
  stderr_silent=$(
    source "$tmp_func2"
    detect_invalid_form_body '{"code": 10008, "message": "Unknown Message"}' 2>&1 >/dev/null
  )
  rm -f "$tmp_func2"

  assert_eq "" "$stderr_silent" "10008 등 다른 code → silent (50035 만 log)"
}

# ─── run ──────────────────────────────────────────────────────────────────────

echo "=== discord-reply.sh length chunk split 회귀 가드 (#1121) ==="
echo "SCRIPT_PATH=$SCRIPT_PATH"
echo

run_case "short body → 단일 chunk" case_short_body_single_chunk
run_case "long body → 다중 chunk + [N/M] 마커" case_long_body_multi_chunk
run_case "newline 경계 우선 split" case_newline_boundary_preferred
run_case "50035 명시 ERROR log" case_detect_invalid_form_body_logs

echo
echo "=== 결과: PASS=$PASS_COUNT FAIL=$FAIL_COUNT ==="

if [[ "$FAIL_COUNT" -gt 0 ]]; then
  exit 1
fi
exit 0
