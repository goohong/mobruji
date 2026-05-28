#!/usr/bin/env bash
# test_directive_status.sh — directive_status.sh + discord-reply.sh
# --update-status / --forum-state-dump mode 검증 (PR #1129 impl PR 2).
#
# 검증 케이스 5건 (spec §3 (b) (c) + §4):
#   1) status valid (in_progress / completed) — jsonl entry status 갱신 + discord-reply 호출
#   2) status invalid — exit 1, jsonl 무변동
#   3) id 미존재 — exit 1, jsonl 무변동
#   4) 멱등 (같은 status 재호출) — exit 0 + no-op (discord-reply 호출 0회)
#   5) forum-retag + update-status atomic 호출 — capture 에 두 호출 모두 기록
# 추가 케이스 2건 (discord-reply.sh mode 직접):
#   6) --update-status mode — PATCH starter message 호출 + status/timestamp/PR URL 본문
#   7) --forum-state-dump mode — guild active threads 조회 + jsonl 출력
#
# 외부 Discord REST / discord-reply.sh 호출은 fake bin 으로 대체 → 매개 변수
# 캡처 후 검증. 거울 룰: test_forum_modes.sh (PR #17) 패턴 재사용.
#
# 종료코드: 모든 케이스 pass → 0. 임의 케이스 fail → 1.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DISCORD_REPLY="$SCRIPT_DIR/discord-reply.sh"
DIRECTIVE_STATUS="$SCRIPT_DIR/directive_status.sh"

PASS=0
FAIL=0
FAIL_NAMES=()

# fake discord-reply.sh — directive_status.sh 가 호출하는 외부 효과 캡처.
# stdin 으로 capture file path 받지 않고 env var 로 받음 (실행 환경 격리).
_write_fake_discord_reply() {
  local tmpdir="$1"
  local capture="$2"
  cat > "$tmpdir/discord-reply.sh" <<EOF
#!/usr/bin/env bash
# fake discord-reply.sh — 호출 매개 변수를 capture file 에 append.
printf '%s\n' "\$*" >> "$capture"
# exit 0 default — 실패 시뮬레이션은 env DISCORD_REPLY_FAIL=1 로 강제.
if [[ "\${DISCORD_REPLY_FAIL:-0}" == "1" ]]; then
  exit 1
fi
exit 0
EOF
  chmod +x "$tmpdir/discord-reply.sh"
}

# fake curl — discord-reply.sh --update-status / --forum-state-dump 의 GET/PATCH
# 호출 캡처. test_forum_modes.sh 패턴.
_write_fake_curl() {
  local tmpdir="$1"
  local capture="$2"
  local response_body="${3:-{\"id\":\"thread-stub\"}}"
  local response_status="${4:-200}"
  cat > "$tmpdir/curl" <<EOF
#!/usr/bin/env bash
METHOD="GET"
URL=""
PAYLOAD=""
while [[ \$# -gt 0 ]]; do
  case "\$1" in
    -X) shift; METHOD="\$1";;
    -d) shift; PAYLOAD="\$1";;
    -w) shift;;
    -H) shift;;
    -sS) ;;
    http*) URL="\$1";;
  esac
  shift
done
printf '%s %s %s\n' "\$METHOD" "\$URL" "\$PAYLOAD" >> $capture
# GET 응답은 forum metadata 또는 guild threads 형식 — URL 패턴 분기.
if [[ "\$METHOD" == "GET" ]]; then
  if [[ "\$URL" == *"/threads/active"* ]]; then
    printf '{"threads":[{"id":"thread-A","name":"테스트 directive","parent_id":"forum-directive","applied_tags":["tag-prog"]},{"id":"thread-B","name":"다른 forum","parent_id":"forum-other","applied_tags":[]}]}\n200'
  else
    printf '{"id":"forum-directive","guild_id":"guild-stub","available_tags":[{"id":"tag-wait","name":"대기"},{"id":"tag-prog","name":"진행 중"},{"id":"tag-done","name":"완료"}]}\n200'
  fi
else
  printf '%s\n%s' "$response_body" "$response_status"
fi
EOF
  chmod +x "$tmpdir/curl"
}

_write_env() {
  local tmpdir="$1"
  local env_path="$tmpdir/test.env"
  {
    echo "DISCORD_BOT_TOKEN=stub-token"
    echo "DISCORD_RETRY_MAX=1"
    echo "DISCORD_RETRY_BASE_SEC=0"
    echo "MOBRUJI_CHANNEL_ID=42"
    echo "DIRECTIVE_BOARD_FORUM_ID=forum-directive"
    echo "BE_FORUM_ID=forum-be"
    echo "FE_FORUM_ID=forum-fe"
    echo "REV_FORUM_ID=forum-rev"
    echo "PLAN_FORUM_ID=forum-plan"
    echo "DISCORD_GUILD_ID=guild-stub"
  } > "$env_path"
  printf '%s' "$env_path"
}

# jsonl 초기 fixture — 3 entry (대기 / 진행 중 / 완료).
_write_jsonl_fixture() {
  local jsonl_path="$1"
  {
    printf '%s\n' '{"ts":"2026-05-27 10:00 KST","summary":"테스트 directive 1","status":"대기","message_id":"msg-001","last_updated_kst":"2026-05-27 10:00 KST","source_queue_msg_id":"msg-001","thread_id":"thread-A"}'
    printf '%s\n' '{"ts":"2026-05-27 10:30 KST","summary":"테스트 directive 2","status":"진행 중","message_id":"msg-002","last_updated_kst":"2026-05-27 10:30 KST","source_queue_msg_id":"msg-002","thread_id":"thread-B"}'
    printf '%s\n' '{"ts":"2026-05-27 11:00 KST","summary":"테스트 directive 3","status":"완료","message_id":"msg-003","last_updated_kst":"2026-05-27 11:00 KST","source_queue_msg_id":"msg-003","thread_id":"thread-C","completed_kst":"2026-05-27 11:30 KST"}'
  } > "$jsonl_path"
}

_run_directive_status() {
  local tmpdir="$1"; shift
  local jsonl_path="$1"; shift
  HOME="$tmpdir" \
    DIRECTIVE_BOARD_JSONL_PATH="$jsonl_path" \
    DISCORD_REPLY_BIN="$tmpdir/discord-reply.sh" \
    bash "$DIRECTIVE_STATUS" "$@"
}

_run_discord_reply() {
  local tmpdir="$1"; shift
  local env_path="$1"; shift
  PATH="$tmpdir:$PATH" \
    DISCORD_DAEMON_ENV_PATH="$env_path" \
    HOME="$tmpdir" \
    LAST_USER_MSG_ID_FILE="$tmpdir/nonexistent-last.txt" \
    HELPER_TARGET_FILE="$tmpdir/nonexistent-target.txt" \
    HELPER_QUEUE_FILE="$tmpdir/nonexistent-queue.jsonl" \
    HELPER_THREAD_FILE="$tmpdir/nonexistent-thread.txt" \
    LAUNCH_THREAD_FILE="$tmpdir/nonexistent-launch.txt" \
    bash "$DISCORD_REPLY" "$@"
}

_assert() {
  local name="$1"
  local condition="$2"
  if eval "$condition"; then
    PASS=$((PASS + 1))
    echo "  PASS: $name"
  else
    FAIL=$((FAIL + 1))
    FAIL_NAMES+=("$name")
    echo "  FAIL: $name (조건: $condition)"
  fi
}

# ── case 1: status valid (in_progress) — jsonl entry 갱신 + discord-reply 호출 ──
case1_status_valid_in_progress() {
  echo "[case1] status valid (in_progress) — 정상 전이"
  local tmpdir
  tmpdir=$(mktemp -d)
  trap "rm -rf $tmpdir" RETURN
  local capture="$tmpdir/capture.txt"
  : > "$capture"
  _write_fake_discord_reply "$tmpdir" "$capture"
  local jsonl_path="$tmpdir/directive-board.jsonl"
  _write_jsonl_fixture "$jsonl_path"

  _run_directive_status "$tmpdir" "$jsonl_path" msg-001 in_progress >/dev/null 2>&1
  local rc=$?

  _assert "case1 returncode 0" "[[ $rc -eq 0 ]]"
  _assert "case1 jsonl status 진행 중" \
    'grep -q "\"message_id\":\"msg-001\".*\"status\":\"진행 중\"" "$jsonl_path" || jq -e ". | select(.message_id == \"msg-001\") | select(.status == \"진행 중\")" "$jsonl_path" >/dev/null'
  _assert "case1 forum-retag 호출 캡처" "grep -q 'forum-retag thread-A directive' $capture"
  _assert "case1 update-status 호출 캡처" "grep -q 'update-status thread-A 진행 중' $capture"
}

# ── case 1b: status valid (completed) + pr_url ────────────────────────────────
case1b_status_valid_completed_with_pr() {
  echo "[case1b] status valid (completed) + pr_url"
  local tmpdir
  tmpdir=$(mktemp -d)
  trap "rm -rf $tmpdir" RETURN
  local capture="$tmpdir/capture.txt"
  : > "$capture"
  _write_fake_discord_reply "$tmpdir" "$capture"
  local jsonl_path="$tmpdir/directive-board.jsonl"
  _write_jsonl_fixture "$jsonl_path"

  _run_directive_status "$tmpdir" "$jsonl_path" msg-002 completed \
    "https://github.com/owner/repo/pull/9999" >/dev/null 2>&1
  local rc=$?

  _assert "case1b returncode 0" "[[ $rc -eq 0 ]]"
  _assert "case1b jsonl status 완료" \
    'jq -e ". | select(.message_id == \"msg-002\") | select(.status == \"완료\")" "$jsonl_path" >/dev/null'
  _assert "case1b jsonl completed_kst 갱신" \
    'jq -e ". | select(.message_id == \"msg-002\") | select(.completed_kst != null)" "$jsonl_path" >/dev/null'
  _assert "case1b jsonl related_pr 갱신" \
    'jq -e ". | select(.message_id == \"msg-002\") | select(.related_pr == \"https://github.com/owner/repo/pull/9999\")" "$jsonl_path" >/dev/null'
  _assert "case1b update-status 호출에 PR URL 포함" \
    "grep -q 'update-status thread-B 완료 https://github.com/owner/repo/pull/9999' $capture"
}

# ── case 2: status invalid — 거부, jsonl 무변동 ───────────────────────────────
case2_status_invalid() {
  echo "[case2] status invalid 거부"
  local tmpdir
  tmpdir=$(mktemp -d)
  trap "rm -rf $tmpdir" RETURN
  local capture="$tmpdir/capture.txt"
  : > "$capture"
  _write_fake_discord_reply "$tmpdir" "$capture"
  local jsonl_path="$tmpdir/directive-board.jsonl"
  _write_jsonl_fixture "$jsonl_path"
  local before
  before=$(sha256sum "$jsonl_path" | cut -d' ' -f1)

  _run_directive_status "$tmpdir" "$jsonl_path" msg-001 blocked >/dev/null 2>&1
  local rc=$?
  local after
  after=$(sha256sum "$jsonl_path" | cut -d' ' -f1)

  _assert "case2 returncode 1 (invalid status)" "[[ $rc -eq 1 ]]"
  _assert "case2 jsonl 무변동" "[[ '$after' == '$before' ]]"
  _assert "case2 discord-reply 미호출" "[[ ! -s $capture ]]"

  # cancel / wait 등 다른 미지원 값도 거부.
  _run_directive_status "$tmpdir" "$jsonl_path" msg-001 cancelled >/dev/null 2>&1
  local rc2=$?
  _assert "case2 cancelled status 거부" "[[ $rc2 -eq 1 ]]"
}

# ── case 3: id 미존재 — 거부, jsonl 무변동 ────────────────────────────────────
case3_id_missing() {
  echo "[case3] id 미존재 거부"
  local tmpdir
  tmpdir=$(mktemp -d)
  trap "rm -rf $tmpdir" RETURN
  local capture="$tmpdir/capture.txt"
  : > "$capture"
  _write_fake_discord_reply "$tmpdir" "$capture"
  local jsonl_path="$tmpdir/directive-board.jsonl"
  _write_jsonl_fixture "$jsonl_path"
  local before
  before=$(sha256sum "$jsonl_path" | cut -d' ' -f1)

  _run_directive_status "$tmpdir" "$jsonl_path" msg-999-nonexistent in_progress >/dev/null 2>&1
  local rc=$?
  local after
  after=$(sha256sum "$jsonl_path" | cut -d' ' -f1)

  _assert "case3 returncode 1 (id 미존재)" "[[ $rc -eq 1 ]]"
  _assert "case3 jsonl 무변동" "[[ '$after' == '$before' ]]"
  _assert "case3 discord-reply 미호출" "[[ ! -s $capture ]]"
}

# ── case 4: 멱등 (같은 status 재호출 no-op) ───────────────────────────────────
case4_idempotent() {
  echo "[case4] 같은 status 재호출 멱등 no-op"
  local tmpdir
  tmpdir=$(mktemp -d)
  trap "rm -rf $tmpdir" RETURN
  local capture="$tmpdir/capture.txt"
  : > "$capture"
  _write_fake_discord_reply "$tmpdir" "$capture"
  local jsonl_path="$tmpdir/directive-board.jsonl"
  _write_jsonl_fixture "$jsonl_path"

  # msg-002 는 fixture 에서 이미 "진행 중" — in_progress 재호출 = no-op.
  _run_directive_status "$tmpdir" "$jsonl_path" msg-002 in_progress >/dev/null 2>&1
  local rc=$?

  _assert "case4 returncode 0 (멱등)" "[[ $rc -eq 0 ]]"
  _assert "case4 discord-reply 미호출 (이미 같은 status)" "[[ ! -s $capture ]]"

  # msg-003 는 "완료" — completed 재호출 = no-op.
  _run_directive_status "$tmpdir" "$jsonl_path" msg-003 completed >/dev/null 2>&1
  local rc2=$?
  _assert "case4 completed 멱등" "[[ $rc2 -eq 0 ]]"
}

# ── case 5: forum-retag + update-status atomic 호출 검증 ──────────────────────
case5_forum_atomic_calls() {
  echo "[case5] forum-retag + update-status atomic 호출"
  local tmpdir
  tmpdir=$(mktemp -d)
  trap "rm -rf $tmpdir" RETURN
  local capture="$tmpdir/capture.txt"
  : > "$capture"
  _write_fake_discord_reply "$tmpdir" "$capture"
  local jsonl_path="$tmpdir/directive-board.jsonl"
  _write_jsonl_fixture "$jsonl_path"

  # thread_id 로 매칭 (id arg 가 thread_id 인 경우도 spec §3-b 에 정의됨).
  _run_directive_status "$tmpdir" "$jsonl_path" thread-A in_progress >/dev/null 2>&1
  local rc=$?

  _assert "case5 returncode 0" "[[ $rc -eq 0 ]]"
  _assert "case5 forum-retag 호출 정확히 1회" \
    "[[ \$(grep -c 'forum-retag' $capture) -eq 1 ]]"
  _assert "case5 update-status 호출 정확히 1회" \
    "[[ \$(grep -c 'update-status' $capture) -eq 1 ]]"
  _assert "case5 호출 순서 retag 먼저, update-status 다음" \
    "[[ \$(grep -n 'forum-retag' $capture | cut -d: -f1) -lt \$(grep -n 'update-status' $capture | cut -d: -f1) ]]"
  _assert "case5 jsonl status 진행 중 (thread_id 매칭)" \
    'jq -e ". | select(.thread_id == \"thread-A\") | select(.status == \"진행 중\")" "$jsonl_path" >/dev/null'
}

# ── case 6: discord-reply --update-status mode 직접 검증 ──────────────────────
case6_discord_reply_update_status() {
  echo "[case6] discord-reply.sh --update-status PATCH starter"
  local tmpdir
  tmpdir=$(mktemp -d)
  trap "rm -rf $tmpdir" RETURN
  local capture="$tmpdir/capture.txt"
  : > "$capture"
  _write_fake_curl "$tmpdir" "$capture"
  local env_path
  env_path=$(_write_env "$tmpdir")

  _run_discord_reply "$tmpdir" "$env_path" \
    --update-status thread-555 "진행 중" "https://github.com/owner/repo/pull/777" \
    >/dev/null 2>&1
  local rc=$?

  _assert "case6 returncode 0" "[[ $rc -eq 0 ]]"
  _assert "case6 PATCH starter message endpoint" \
    "grep -q '^PATCH https://discord.com/api/v10/channels/thread-555/messages/thread-555 ' $capture"
  _assert "case6 payload 안 status 본문" "grep -q '진행 중' $capture"
  _assert "case6 payload 안 PR URL" "grep -q 'pull/777' $capture"
  _assert "case6 payload 안 갱신 timestamp" "grep -q '갱신' $capture"
}

# ── case 7: discord-reply --forum-state-dump mode 직접 검증 ───────────────────
case7_discord_reply_forum_state_dump() {
  echo "[case7] discord-reply.sh --forum-state-dump jsonl 출력"
  local tmpdir
  tmpdir=$(mktemp -d)
  trap "rm -rf $tmpdir" RETURN
  local capture="$tmpdir/capture.txt"
  : > "$capture"
  _write_fake_curl "$tmpdir" "$capture"
  local env_path
  env_path=$(_write_env "$tmpdir")

  local stdout
  stdout=$(_run_discord_reply "$tmpdir" "$env_path" \
    --forum-state-dump directive 2>/dev/null)
  local rc=$?

  _assert "case7 returncode 0" "[[ $rc -eq 0 ]]"
  _assert "case7 GET forum metadata 호출" \
    "grep -q '^GET https://discord.com/api/v10/channels/forum-directive ' $capture"
  _assert "case7 GET guild threads/active 호출" \
    "grep -q '^GET https://discord.com/api/v10/guilds/guild-stub/threads/active ' $capture"
  _assert "case7 stdout jsonl 형식 thread-A 포함" \
    "echo '$stdout' | grep -q 'thread-A'"
  _assert "case7 stdout 다른 forum thread-B 제외" \
    "! echo '$stdout' | grep -q 'thread-B'"
  _assert "case7 stdout tag name 매핑 (tag-prog → 진행 중)" \
    "echo '$stdout' | grep -q '진행 중'"
}

# usage / arg 부족 케이스 (보조).
case8_usage_no_args() {
  echo "[case8] usage 인자 부족 거부"
  local tmpdir
  tmpdir=$(mktemp -d)
  trap "rm -rf $tmpdir" RETURN

  HOME="$tmpdir" bash "$DIRECTIVE_STATUS" >/dev/null 2>&1
  local rc=$?
  _assert "case8 no-arg exit 64" "[[ $rc -eq 64 ]]"

  HOME="$tmpdir" bash "$DIRECTIVE_STATUS" msg-only-id >/dev/null 2>&1
  local rc2=$?
  _assert "case8 missing status exit 64" "[[ $rc2 -eq 64 ]]"
}

# ─── run all ────────────────────────────────────────────────────────────────────
case1_status_valid_in_progress
case1b_status_valid_completed_with_pr
case2_status_invalid
case3_id_missing
case4_idempotent
case5_forum_atomic_calls
case6_discord_reply_update_status
case7_discord_reply_forum_state_dump
case8_usage_no_args

echo ""
echo "─── summary ────────────────────────────────────────────────"
echo "PASS: $PASS / FAIL: $FAIL"
if (( FAIL > 0 )); then
  echo "FAILED:"
  for name in "${FAIL_NAMES[@]}"; do
    echo "  - $name"
  done
  exit 1
fi
exit 0
