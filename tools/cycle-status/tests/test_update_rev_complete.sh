#!/usr/bin/env bash
# tools/cycle-status/tests/test_update_rev_complete.sh — update.sh 의 rev 완료 retag 단위 테스트.
#
# 배경 (#1514): 사이클 forum 태그 ✅ 완료 전이는 본래 머지 PR 본문 cycle-forum
# 교차참조(bot.py cycle_thread_complete_on_merge_loop)에만 의존했는데, rev 는
# 자체 PR 을 머지하지 않아 launch thread 가 ⏳ 진행에 영구 고정됐다. set-idle(rev)
# 시점에 active thread 를 ✅ 완료로 retag 하는 보강을 검증한다.
#
# 검증 시나리오:
#   1. rev set-idle + valid active-thread → mock 이 `--forum-retag <id> rev 완료` 로 호출 + 파일 정리 + exit 0
#   2. be set-idle + active-thread 존재 → mock 미호출 (rev 스코프 한정) + 파일 유지
#   3. rev set-idle + active-thread 부재 → graceful (mock 미호출) + exit 0
#   4. rev set-idle + 비-snowflake active-thread → graceful skip (mock 미호출) + exit 0
#   5. rev set-active → retag 안 함 (set-idle 만 trigger) + exit 0
#
# 사용:
#   bash tools/cycle-status/tests/test_update_rev_complete.sh
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UPDATE_SH="$SCRIPT_DIR/../update.sh"
SNOWFLAKE="1511401682351952123"
PASS=0
FAIL=0
FAILURES=()

if [[ ! -f "$UPDATE_SH" ]]; then
  echo "FATAL: $UPDATE_SH 부재" >&2
  exit 2
fi

make_mock() {
  # 호출 인자를 $1 (log path) 에 한 줄로 기록하는 mock discord-reply.sh.
  local mock_path="$1" log_path="$2"
  cat > "$mock_path" <<MOCK
#!/usr/bin/env bash
printf '%s\n' "\$*" >> "$log_path"
exit 0
MOCK
  chmod +x "$mock_path"
}

assert_eq() {
  local name="$1" expected="$2" actual="$3"
  if [[ "$expected" == "$actual" ]]; then
    PASS=$((PASS + 1)); echo "PASS: $name"
  else
    FAIL=$((FAIL + 1)); FAILURES+=("$name (expected='$expected' actual='$actual')"); echo "FAIL: $name (expected='$expected' actual='$actual')"
  fi
}

assert_contains() {
  local name="$1" haystack="$2" needle="$3"
  if [[ "$haystack" == *"$needle"* ]]; then
    PASS=$((PASS + 1)); echo "PASS: $name"
  else
    FAIL=$((FAIL + 1)); FAILURES+=("$name (needle missing: '$needle')"); echo "FAIL: $name (haystack='$haystack')"
  fi
}

setup_case() {
  # 새 tmp HOME + cycle-status + mock 환경 구성. echo 로 TMP 경로 반환.
  local tmp; tmp=$(mktemp -d -t update-rev-complete-XXXXXX)
  mkdir -p "$tmp/.mobruji/cycle-active-thread"
  make_mock "$tmp/discord-reply.sh" "$tmp/mock.log"
  : > "$tmp/mock.log"
  echo "$tmp"
}

run_update() {
  # $1=TMP, 나머지 = update.sh 인자. 환경 격리해 실행, exit code 반환.
  local tmp="$1"; shift
  HOME="$tmp" \
  CYCLE_STATUS_PATH="$tmp/cycle-status.json" \
  DISCORD_REPLY_SH="$tmp/discord-reply.sh" \
    bash "$UPDATE_SH" "$@" >/dev/null 2>&1
}

# ── 1) rev set-idle + valid active-thread → retag 완료 + 파일 정리 ──────────────
TMP=$(setup_case)
printf '%s\n' "$SNOWFLAKE" > "$TMP/.mobruji/cycle-active-thread/rev.txt"
run_update "$TMP" rev set-idle --note "다음 launch 후보: PR audit"
assert_eq "1) rev set-idle exit 0" "0" "$?"
assert_contains "1) mock 이 --forum-retag <id> rev 완료 로 호출" "$(cat "$TMP/mock.log")" "--forum-retag $SNOWFLAKE rev 완료"
if [[ -e "$TMP/.mobruji/cycle-active-thread/rev.txt" ]]; then
  FAIL=$((FAIL + 1)); FAILURES+=("1) active-thread 파일이 정리되지 않음"); echo "FAIL: 1) active-thread 파일 미정리"
else
  PASS=$((PASS + 1)); echo "PASS: 1) active-thread 파일 정리됨"
fi
rm -rf "$TMP"

# ── 2) be set-idle + active-thread 존재 → rev 아니므로 retag 안 함 ─────────────
TMP=$(setup_case)
printf '%s\n' "$SNOWFLAKE" > "$TMP/.mobruji/cycle-active-thread/be.txt"
run_update "$TMP" be set-idle --note "다음 launch 후보"
assert_eq "2) be set-idle exit 0" "0" "$?"
assert_eq "2) mock 미호출 (rev 스코프 한정)" "" "$(cat "$TMP/mock.log")"
if [[ -e "$TMP/.mobruji/cycle-active-thread/be.txt" ]]; then
  PASS=$((PASS + 1)); echo "PASS: 2) be active-thread 파일 유지"
else
  FAIL=$((FAIL + 1)); FAILURES+=("2) be active-thread 파일이 잘못 정리됨"); echo "FAIL: 2) be active-thread 파일 잘못 정리"
fi
rm -rf "$TMP"

# ── 3) rev set-idle + active-thread 부재 → graceful (mock 미호출) ──────────────
TMP=$(setup_case)
run_update "$TMP" rev set-idle --note "다음 launch 후보"
assert_eq "3) active-thread 부재 exit 0" "0" "$?"
assert_eq "3) mock 미호출 (핸들 없음)" "" "$(cat "$TMP/mock.log")"
rm -rf "$TMP"

# ── 4) rev set-idle + 비-snowflake active-thread → graceful skip ──────────────
TMP=$(setup_case)
printf '%s\n' "not-a-snowflake" > "$TMP/.mobruji/cycle-active-thread/rev.txt"
run_update "$TMP" rev set-idle --note "다음 launch 후보"
assert_eq "4) 비-snowflake exit 0" "0" "$?"
assert_eq "4) mock 미호출 (비-snowflake)" "" "$(cat "$TMP/mock.log")"
rm -rf "$TMP"

# ── 5) rev set-active → set-idle 아니므로 retag 안 함 ──────────────────────────
TMP=$(setup_case)
printf '%s\n' "$SNOWFLAKE" > "$TMP/.mobruji/cycle-active-thread/rev.txt"
run_update "$TMP" rev set-active --title "PR #1500 audit"
assert_eq "5) rev set-active exit 0" "0" "$?"
assert_eq "5) mock 미호출 (set-active 는 trigger 아님)" "" "$(cat "$TMP/mock.log")"
rm -rf "$TMP"

echo
echo "──────────────────────────────────────────"
echo "PASS=$PASS FAIL=$FAIL"
if [[ "$FAIL" -ne 0 ]]; then
  printf 'FAILURE: %s\n' "${FAILURES[@]}" >&2
  exit 1
fi
echo "모든 케이스 통과."
