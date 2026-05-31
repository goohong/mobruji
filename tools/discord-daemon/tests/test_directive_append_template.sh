#!/usr/bin/env bash
# test_directive_append_template.sh — directive_append.sh 의 template body 검증.
#
# spec: docs/features/directive-board-template-and-tags.md §5-4
#
# 검증 케이스:
#   1. BODY 미명시 → template 본문 자동 빌드 (📌 / 💬 / 🆔 / 📋 / 🔖 마커 + 체크박스 3개 + footer).
#   2. user_id 5번째 arg → 👤 line 부착.
#   3. user_id env (DIRECTIVE_USER_ID) → 👤 line 부착.
#   4. user_id 부재 → 👤 line 생략.
#   5. BODY 명시 → template 자동 빌드 skip, 명시 body 그대로 사용 (override path).
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

_assert_contains() {
  local name="$1"
  local needle="$2"
  local haystack="$3"
  if [[ "$haystack" == *"$needle"* ]]; then
    PASS=$((PASS + 1))
    echo "  PASS: $name"
  else
    FAIL=$((FAIL + 1))
    FAIL_NAMES+=("$name")
    echo "  FAIL: $name"
    echo "    needle=<$needle>"
    echo "    haystack=<$(echo "$haystack" | head -c 300)>"
  fi
}

_assert_not_contains() {
  local name="$1"
  local needle="$2"
  local haystack="$3"
  if [[ "$haystack" != *"$needle"* ]]; then
    PASS=$((PASS + 1))
    echo "  PASS: $name"
  else
    FAIL=$((FAIL + 1))
    FAIL_NAMES+=("$name")
    echo "  FAIL: $name"
    echo "    needle=<$needle> should NOT be in haystack"
  fi
}

# fake discord-reply.sh — 4번째 args (body) 를 별도 file 로 캡처.
# args: "--forum-post-auto-tag" "directive" "<title>" "<body>"
_make_fake_discord_reply() {
  local tmpdir="$1"
  local thread_id="$2"
  cat > "$tmpdir/discord-reply.sh" <<EOF
#!/usr/bin/env bash
# 4번째 arg (body) 를 단독 file 로 저장 — newline 보존.
if [[ \$# -ge 4 ]]; then
  printf '%s' "\$4" > "$tmpdir/captured_body.txt"
fi
echo "\$@" > "$tmpdir/discord_args.log"
echo "$thread_id"
exit 0
EOF
  chmod +x "$tmpdir/discord-reply.sh"
}

# ─────────────────────────────────────────────────────────────────────────────
# Case 1: BODY 미명시 → template 자동 빌드.
# ─────────────────────────────────────────────────────────────────────────────
echo "Case 1: template 본문 자동 빌드"
TMP1="$(mktemp -d)"
_make_fake_discord_reply "$TMP1" "T1"
JSONL1="$TMP1/board.jsonl"

DIRECTIVE_BOARD_JSONL_PATH="$JSONL1" \
DISCORD_REPLY_BIN="$TMP1/discord-reply.sh" \
  bash "$SCRIPT_PATH" "msg_t1" "release 머지 가도 될까요?" \
  > /dev/null 2>&1

BODY1="$(cat "$TMP1/captured_body.txt")"
_assert_contains "Case 1 📌 marker"     "📌"                       "$BODY1"
_assert_contains "Case 1 💬 요약 헤더"   "💬 요약"                  "$BODY1"
_assert_contains "Case 1 title quote fallback" "> release 머지 가도 될까요?" "$BODY1"
_assert_contains "Case 1 🆔 directive_id" "🆔"                      "$BODY1"
_assert_contains "Case 1 msg_id 포함"    "msg_t1"                  "$BODY1"
_assert_contains "Case 1 🕐 timestamp"   "🕐"                      "$BODY1"
_assert_contains "Case 1 📋 진행 헤더"   "📋 진행 (🟡 대기)"        "$BODY1"
_assert_contains "Case 1 진행 체크 1"   "- [ ] 분석 / 위임 결정"   "$BODY1"
_assert_contains "Case 1 진행 체크 2"   "- [ ] 실행"               "$BODY1"
_assert_contains "Case 1 진행 체크 3"   "- [ ] 결과 반영"          "$BODY1"
_assert_contains "Case 1 🔖 관련 헤더"   "🔖 관련"                 "$BODY1"
_assert_contains "Case 1 footer 갱신"   "_갱신:"                  "$BODY1"
# user_id 미명시 → 👤 line 없어야 함.
_assert_not_contains "Case 1 👤 line 생략" "👤" "$BODY1"

rm -rf "$TMP1"

# ─────────────────────────────────────────────────────────────────────────────
# Case 2: user_id 5번째 arg → 👤 line 부착.
# ─────────────────────────────────────────────────────────────────────────────
echo "Case 2: user_id 5번째 arg"
TMP2="$(mktemp -d)"
_make_fake_discord_reply "$TMP2" "T2"
JSONL2="$TMP2/board.jsonl"

DIRECTIVE_BOARD_JSONL_PATH="$JSONL2" \
DISCORD_REPLY_BIN="$TMP2/discord-reply.sh" \
  bash "$SCRIPT_PATH" "msg_t2" "결정 시점" "" "" "428123456789" \
  > /dev/null 2>&1

BODY2="$(cat "$TMP2/captured_body.txt")"
_assert_contains "Case 2 👤 line 부착" "👤 <@428123456789>" "$BODY2"
_assert_contains "Case 2 template 마커 유지" "📌" "$BODY2"

rm -rf "$TMP2"

# ─────────────────────────────────────────────────────────────────────────────
# Case 3: DIRECTIVE_USER_ID env → 👤 line 부착.
# ─────────────────────────────────────────────────────────────────────────────
echo "Case 3: user_id env"
TMP3="$(mktemp -d)"
_make_fake_discord_reply "$TMP3" "T3"
JSONL3="$TMP3/board.jsonl"

DIRECTIVE_BOARD_JSONL_PATH="$JSONL3" \
DISCORD_REPLY_BIN="$TMP3/discord-reply.sh" \
DIRECTIVE_USER_ID="999000111" \
  bash "$SCRIPT_PATH" "msg_t3" "env 케이스" \
  > /dev/null 2>&1

BODY3="$(cat "$TMP3/captured_body.txt")"
_assert_contains "Case 3 env user_id 부착" "👤 <@999000111>" "$BODY3"

rm -rf "$TMP3"

# ─────────────────────────────────────────────────────────────────────────────
# Case 4: BODY 명시 → template skip (override path 보존).
# ─────────────────────────────────────────────────────────────────────────────
echo "Case 4: body override"
TMP4="$(mktemp -d)"
_make_fake_discord_reply "$TMP4" "T4"
JSONL4="$TMP4/board.jsonl"

CUSTOM_BODY="custom override body — no template markers"
DIRECTIVE_BOARD_JSONL_PATH="$JSONL4" \
DISCORD_REPLY_BIN="$TMP4/discord-reply.sh" \
  bash "$SCRIPT_PATH" "msg_t4" "title" "" "$CUSTOM_BODY" \
  > /dev/null 2>&1

BODY4="$(cat "$TMP4/captured_body.txt")"
_assert_contains "Case 4 override body 그대로" "custom override body" "$BODY4"
_assert_not_contains "Case 4 template 마커 미생성 (override)" "📌" "$BODY4"
_assert_not_contains "Case 4 진행 체크박스 미생성 (override)" "- [ ] 분석" "$BODY4"

rm -rf "$TMP4"

# ─────────────────────────────────────────────────────────────────────────────
# Case 5 (#1385): DIRECTIVE_SUMMARY_BODY env → template 💬 요약 섹션에 삽입.
#   6 marker 양식 유지 + 정제 본문 가독성 동시 확보.
# ─────────────────────────────────────────────────────────────────────────────
echo "Case 5: DIRECTIVE_SUMMARY_BODY env (#1385)"
TMP5="$(mktemp -d)"
_make_fake_discord_reply "$TMP5" "T5"
JSONL5="$TMP5/board.jsonl"

SUMMARY="- **요약**: 브라우저 자동 QA 환경 도입
- **유형**: 신규 기능
- **상태**: 대기"
DIRECTIVE_BOARD_JSONL_PATH="$JSONL5" \
DISCORD_REPLY_BIN="$TMP5/discord-reply.sh" \
DIRECTIVE_SUMMARY_BODY="$SUMMARY" \
  bash "$SCRIPT_PATH" "msg_t5" "브라우저 자동 QA 환경 도입" \
  > /dev/null 2>&1

BODY5="$(cat "$TMP5/captured_body.txt")"
_assert_contains "Case 5 💬 요약 헤더 유지"   "💬 요약"                       "$BODY5"
_assert_contains "Case 5 정제 본문 삽입"     "- **요약**: 브라우저 자동 QA"   "$BODY5"
_assert_contains "Case 5 📌 marker 유지"     "📌"                            "$BODY5"
_assert_contains "Case 5 📋 진행 체크박스 유지" "- [ ] 분석 / 위임 결정"       "$BODY5"
# 정제 본문이 들어가면 title quote fallback 은 없어야 함.
_assert_not_contains "Case 5 title quote fallback 미생성" "> 브라우저 자동 QA 환경 도입" "$BODY5"

rm -rf "$TMP5"

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
