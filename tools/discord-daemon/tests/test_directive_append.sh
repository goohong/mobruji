#!/usr/bin/env bash
# test_directive_append.sh — directive_append.sh 단위 테스트 (PR #1140).
#
# 검증 케이스:
#   1. 정상 append — JSONL 신규 entry + discord-reply.sh forum-post-auto-tag
#      호출 + thread_id JSONL 반영.
#   2. 멱등성 — 동일 msg_id 두 번째 호출 시 no-op (JSONL 1줄 유지).
#   3. discord-reply.sh 실패 — JSONL append 는 여전히 수행 (graceful).
#   4. title 90자 cap — 초과 시 ... 으로 잘림.
#   5. usage — 인자 부족 시 exit 64.
#
# 종료코드: 모든 case pass → 0. 임의 case fail → 1.

set -uo pipefail

SCRIPT_PATH="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/directive_append.sh"
if [[ ! -x "$SCRIPT_PATH" ]]; then
  chmod +x "$SCRIPT_PATH" 2>/dev/null || true
fi

PASS=0
FAIL=0
FAIL_NAMES=()

_assert_eq() {
  local name="$1"
  local expected="$2"
  local actual="$3"
  if [[ "$expected" == "$actual" ]]; then
    PASS=$((PASS + 1))
    echo "  PASS: $name"
  else
    FAIL=$((FAIL + 1))
    FAIL_NAMES+=("$name")
    echo "  FAIL: $name"
    echo "    expected=<$expected>"
    echo "    actual=<$actual>"
  fi
}

_make_fake_discord_reply() {
  # 인자: <tmpdir> <thread_id> [exit_code]
  local tmpdir="$1"
  local thread_id="$2"
  local exit_code="${3:-0}"
  cat > "$tmpdir/discord-reply.sh" <<EOF
#!/usr/bin/env bash
# fake discord-reply.sh — args 캡처 + thread_id 출력.
echo "\$@" > "$tmpdir/discord_args.log"
echo "$thread_id"
exit $exit_code
EOF
  chmod +x "$tmpdir/discord-reply.sh"
}

# ─────────────────────────────────────────────────────────────────────────────
# Case 1: 정상 append — 신규 msg_id.
# ─────────────────────────────────────────────────────────────────────────────
echo "Case 1: 정상 append"
TMP1="$(mktemp -d)"
_make_fake_discord_reply "$TMP1" "1234567890"
JSONL1="$TMP1/board.jsonl"

DIRECTIVE_BOARD_JSONL_PATH="$JSONL1" \
DISCORD_REPLY_BIN="$TMP1/discord-reply.sh" \
  bash "$SCRIPT_PATH" "msg_111" "테스트 directive 본문 요약" \
  > /dev/null 2>&1
EXIT=$?

_assert_eq "Case 1 exit" "0" "$EXIT"
_assert_eq "Case 1 JSONL 1줄" "1" "$(wc -l < "$JSONL1" | tr -d ' ')"
ENTRY="$(cat "$JSONL1")"
_assert_eq "Case 1 message_id" "msg_111" \
  "$(echo "$ENTRY" | jq -r '.message_id')"
_assert_eq "Case 1 status=대기" "대기" \
  "$(echo "$ENTRY" | jq -r '.status')"
_assert_eq "Case 1 thread_id=1234567890" "1234567890" \
  "$(echo "$ENTRY" | jq -r '.thread_id')"
_assert_eq "Case 1 source_queue_msg_id=msg_111" "msg_111" \
  "$(echo "$ENTRY" | jq -r '.source_queue_msg_id')"
# discord-reply.sh 호출 args 확인.
DISCORD_ARGS="$(cat "$TMP1/discord_args.log")"
case "$DISCORD_ARGS" in
  *"--forum-post-auto-tag directive"*) _assert_eq "Case 1 forum-post-auto-tag 호출" "yes" "yes" ;;
  *) _assert_eq "Case 1 forum-post-auto-tag 호출" "yes" "no" ;;
esac
rm -rf "$TMP1"

# ─────────────────────────────────────────────────────────────────────────────
# Case 2: 멱등성 — 동일 msg_id 두 번째 호출 시 no-op.
# ─────────────────────────────────────────────────────────────────────────────
echo "Case 2: 멱등성"
TMP2="$(mktemp -d)"
_make_fake_discord_reply "$TMP2" "9999"
JSONL2="$TMP2/board.jsonl"

DIRECTIVE_BOARD_JSONL_PATH="$JSONL2" \
DISCORD_REPLY_BIN="$TMP2/discord-reply.sh" \
  bash "$SCRIPT_PATH" "msg_222" "첫 호출" > /dev/null 2>&1

DIRECTIVE_BOARD_JSONL_PATH="$JSONL2" \
DISCORD_REPLY_BIN="$TMP2/discord-reply.sh" \
  bash "$SCRIPT_PATH" "msg_222" "두 번째 호출" > /dev/null 2>&1
EXIT=$?

_assert_eq "Case 2 두 번째 호출 exit 0" "0" "$EXIT"
_assert_eq "Case 2 JSONL 여전히 1줄" "1" "$(wc -l < "$JSONL2" | tr -d ' ')"
rm -rf "$TMP2"

# ─────────────────────────────────────────────────────────────────────────────
# Case 3: discord-reply.sh 실패 — JSONL append 는 여전히 수행 (graceful).
# ─────────────────────────────────────────────────────────────────────────────
echo "Case 3: discord-reply 실패 graceful"
TMP3="$(mktemp -d)"
_make_fake_discord_reply "$TMP3" "" 1   # exit_code=1
JSONL3="$TMP3/board.jsonl"

DIRECTIVE_BOARD_JSONL_PATH="$JSONL3" \
DISCORD_REPLY_BIN="$TMP3/discord-reply.sh" \
  bash "$SCRIPT_PATH" "msg_333" "discord fail 케이스" \
  > /dev/null 2>&1
EXIT=$?

_assert_eq "Case 3 exit 0 (graceful)" "0" "$EXIT"
_assert_eq "Case 3 JSONL 여전히 1줄 추가" "1" "$(wc -l < "$JSONL3" | tr -d ' ')"
ENTRY3="$(cat "$JSONL3")"
# thread_id 필드는 없어야 함 (discord 실패).
TID3="$(echo "$ENTRY3" | jq -r '.thread_id // "none"')"
_assert_eq "Case 3 thread_id=none" "none" "$TID3"
rm -rf "$TMP3"

# ─────────────────────────────────────────────────────────────────────────────
# Case 4: title 90자 cap.
# ─────────────────────────────────────────────────────────────────────────────
echo "Case 4: title 90자 cap"
TMP4="$(mktemp -d)"
_make_fake_discord_reply "$TMP4" "T4"
JSONL4="$TMP4/board.jsonl"
LONG_TITLE="$(python3 -c "print('a' * 200)")"

DIRECTIVE_BOARD_JSONL_PATH="$JSONL4" \
DISCORD_REPLY_BIN="$TMP4/discord-reply.sh" \
  bash "$SCRIPT_PATH" "msg_444" "$LONG_TITLE" > /dev/null 2>&1
EXIT=$?

SUMMARY4="$(jq -r '.summary' "$JSONL4")"
SUMMARY_LEN=${#SUMMARY4}
_assert_eq "Case 4 exit 0" "0" "$EXIT"
# 90자 cap (87 + ...).
if [[ "$SUMMARY_LEN" -le 90 ]]; then
  _assert_eq "Case 4 summary <=90자" "ok" "ok"
else
  _assert_eq "Case 4 summary <=90자 (len=$SUMMARY_LEN)" "ok" "fail"
fi
case "$SUMMARY4" in
  *"...") _assert_eq "Case 4 summary ... suffix" "yes" "yes" ;;
  *) _assert_eq "Case 4 summary ... suffix" "yes" "no" ;;
esac
rm -rf "$TMP4"

# ─────────────────────────────────────────────────────────────────────────────
# Case 5: usage — 인자 부족.
# ─────────────────────────────────────────────────────────────────────────────
echo "Case 5: usage error"
bash "$SCRIPT_PATH" > /dev/null 2>&1
_assert_eq "Case 5 인자 0 exit 64" "64" "$?"

bash "$SCRIPT_PATH" "msg_only" > /dev/null 2>&1
_assert_eq "Case 5 인자 1 exit 64" "64" "$?"

# ─────────────────────────────────────────────────────────────────────────────
# 결과
# ─────────────────────────────────────────────────────────────────────────────
echo ""
echo "─────────────────────────────────────"
echo "PASS=$PASS FAIL=$FAIL"
if [[ "$FAIL" -gt 0 ]]; then
  printf "FAILED:\n"
  for name in "${FAIL_NAMES[@]}"; do
    printf "  - %s\n" "$name"
  done
  exit 1
fi
exit 0
