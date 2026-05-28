#!/usr/bin/env bash
# test_mark_polished.sh — mark-polished.sh + backlog-scan.sh polished filter 검증.
#
# spec: docs/features/directive-board-template-and-tags.md §5-6 polished flag race 가드
#
# 검증 케이스:
#   1. mark-polished — 매칭 entry polished=true update + last_updated_kst.
#   2. mark-polished — 이미 polished=true 면 no-op (멱등).
#   3. mark-polished — 매칭 entry 부재 시 exit 1.
#   4. mark-polished — jsonl 부재 시 exit 1.
#   5. mark-polished — usage error (인자 0) exit 64.
#   6. backlog-scan default — polished=false entry 무시.
#   7. backlog-scan default — polished=true entry list.
#   8. backlog-scan --include-unpolished — polished 무관 list.

set -uo pipefail

MARK_SCRIPT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)/tools/directive-board/mark-polished.sh"
SCAN_SCRIPT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)/tools/directive-board/backlog-scan.sh"
chmod +x "$MARK_SCRIPT" "$SCAN_SCRIPT" 2>/dev/null || true

PASS=0
FAIL=0
FAIL_NAMES=()

_assert_eq() {
  local name="$1" expected="$2" actual="$3"
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

_assert_contains() {
  local name="$1" needle="$2" haystack="$3"
  if [[ "$haystack" == *"$needle"* ]]; then
    PASS=$((PASS + 1))
    echo "  PASS: $name"
  else
    FAIL=$((FAIL + 1))
    FAIL_NAMES+=("$name")
    echo "  FAIL: $name"
    echo "    needle=<$needle> not in haystack"
  fi
}

# ─────────────────────────────────────────────────────────────────────────────
# Case 1: mark-polished — 정상 update.
# ─────────────────────────────────────────────────────────────────────────────
echo "Case 1: mark-polished 정상 update"
TMP1="$(mktemp -d)"
JSONL1="$TMP1/board.jsonl"
cat > "$JSONL1" <<'EOF'
{"ts":"2026-05-28","summary":"test","status":"대기","polished":false,"message_id":"M_AAA","source_queue_msg_id":"M_AAA"}
EOF

DIRECTIVE_BOARD_JSONL_PATH="$JSONL1" bash "$MARK_SCRIPT" "M_AAA" > /dev/null 2>&1
EXIT=$?
_assert_eq "Case 1 exit 0" "0" "$EXIT"
POLISHED=$(jq -r '.polished' "$JSONL1")
_assert_eq "Case 1 polished=true" "true" "$POLISHED"
HAS_TS=$(jq -r '.last_updated_kst // "none"' "$JSONL1")
case "$HAS_TS" in
  none) _assert_eq "Case 1 last_updated_kst 갱신" "set" "none" ;;
  *) _assert_eq "Case 1 last_updated_kst 갱신" "set" "set" ;;
esac
rm -rf "$TMP1"

# ─────────────────────────────────────────────────────────────────────────────
# Case 2: mark-polished — 이미 polished=true → no-op.
# ─────────────────────────────────────────────────────────────────────────────
echo "Case 2: mark-polished 멱등"
TMP2="$(mktemp -d)"
JSONL2="$TMP2/board.jsonl"
cat > "$JSONL2" <<'EOF'
{"ts":"2026-05-28","summary":"test","status":"대기","polished":true,"message_id":"M_BBB"}
EOF

OUT=$(DIRECTIVE_BOARD_JSONL_PATH="$JSONL2" bash "$MARK_SCRIPT" "M_BBB" 2>&1)
EXIT=$?
_assert_eq "Case 2 멱등 exit 0" "0" "$EXIT"
_assert_contains "Case 2 멱등 no-op 메시지" "이미 polished=true — no-op" "$OUT"
rm -rf "$TMP2"

# ─────────────────────────────────────────────────────────────────────────────
# Case 3: mark-polished — 매칭 entry 부재.
# ─────────────────────────────────────────────────────────────────────────────
echo "Case 3: mark-polished 매칭 entry 부재"
TMP3="$(mktemp -d)"
JSONL3="$TMP3/board.jsonl"
echo '{"message_id":"OTHER","polished":false}' > "$JSONL3"

DIRECTIVE_BOARD_JSONL_PATH="$JSONL3" bash "$MARK_SCRIPT" "MISSING" > /dev/null 2>&1
_assert_eq "Case 3 exit 1" "1" "$?"
rm -rf "$TMP3"

# ─────────────────────────────────────────────────────────────────────────────
# Case 4: mark-polished — jsonl 부재.
# ─────────────────────────────────────────────────────────────────────────────
echo "Case 4: mark-polished jsonl 부재"
TMP4="$(mktemp -d)"
DIRECTIVE_BOARD_JSONL_PATH="$TMP4/nonexistent.jsonl" bash "$MARK_SCRIPT" "x" > /dev/null 2>&1
_assert_eq "Case 4 jsonl 부재 exit 1" "1" "$?"
rm -rf "$TMP4"

# ─────────────────────────────────────────────────────────────────────────────
# Case 5: mark-polished — usage.
# ─────────────────────────────────────────────────────────────────────────────
echo "Case 5: mark-polished usage"
bash "$MARK_SCRIPT" > /dev/null 2>&1
_assert_eq "Case 5 인자 0 exit 64" "64" "$?"

# ─────────────────────────────────────────────────────────────────────────────
# Case 6: backlog-scan default — polished=false 무시.
# ─────────────────────────────────────────────────────────────────────────────
echo "Case 6: backlog-scan default polished filter"
TMP6="$(mktemp -d)"
JSONL6="$TMP6/board.jsonl"
cat > "$JSONL6" <<'EOF'
{"message_id":"M_unpolished","ts":"2026-05-28","status":"대기","polished":false,"summary":"raw"}
{"message_id":"M_polished","ts":"2026-05-28","status":"대기","polished":true,"summary":"refined"}
{"message_id":"M_legacy","ts":"2026-05-28","status":"대기","summary":"no_polished_field"}
EOF

OUT=$(DIRECTIVE_BOARD_JSONL_PATH="$JSONL6" bash "$SCAN_SCRIPT" 2>&1)
_assert_contains "Case 6 polished=true entry list" "M_polished" "$OUT"
case "$OUT" in
  *"M_unpolished"*) _assert_eq "Case 6 polished=false 제외" "exclude" "include" ;;
  *) _assert_eq "Case 6 polished=false 제외" "exclude" "exclude" ;;
esac
case "$OUT" in
  *"M_legacy"*) _assert_eq "Case 6 legacy (polished 부재) 제외" "exclude" "include" ;;
  *) _assert_eq "Case 6 legacy (polished 부재) 제외" "exclude" "exclude" ;;
esac
_assert_contains "Case 6 filter label 'polished=true' 표시" "polished=true" "$OUT"

# ─────────────────────────────────────────────────────────────────────────────
# Case 7: backlog-scan --include-unpolished — polished 무관 list.
# ─────────────────────────────────────────────────────────────────────────────
echo "Case 7: backlog-scan --include-unpolished"
OUT7=$(DIRECTIVE_BOARD_JSONL_PATH="$JSONL6" bash "$SCAN_SCRIPT" --include-unpolished 2>&1)
_assert_contains "Case 7 polished=true 포함" "M_polished" "$OUT7"
_assert_contains "Case 7 polished=false 포함" "M_unpolished" "$OUT7"
_assert_contains "Case 7 legacy 포함" "M_legacy" "$OUT7"
case "$OUT7" in
  *"polished=true"*) _assert_eq "Case 7 filter label polished 표시 없음" "no_polished_label" "polished_in_label" ;;
  *) _assert_eq "Case 7 filter label polished 표시 없음" "no_polished_label" "no_polished_label" ;;
esac
rm -rf "$TMP6"

# ─────────────────────────────────────────────────────────────────────────────
# Case 8: mark-polished — MOBRUJI_NMAE_INJECT_ENABLED=0 → inject skip (graceful).
# ─────────────────────────────────────────────────────────────────────────────
echo "Case 8: nmae inject disable (MOBRUJI_NMAE_INJECT_ENABLED=0)"
TMP8="$(mktemp -d)"
JSONL8="$TMP8/board.jsonl"
echo '{"message_id":"M_CCC","polished":false}' > "$JSONL8"

OUT8=$(DIRECTIVE_BOARD_JSONL_PATH="$JSONL8" MOBRUJI_NMAE_INJECT_ENABLED=0 bash "$MARK_SCRIPT" "M_CCC" 2>&1)
_assert_eq "Case 8 disable exit 0" "0" "$?"
_assert_contains "Case 8 inject disabled 메시지" "nmae inject disabled" "$OUT8"
POLISHED8=$(jq -r '.polished' "$JSONL8")
_assert_eq "Case 8 polished=true (jsonl update 정상)" "true" "$POLISHED8"
rm -rf "$TMP8"

# ─────────────────────────────────────────────────────────────────────────────
# Case 9: mark-polished — tmux 부재 / session 부재 graceful skip.
# (mac local 환경 default = tmux 있지만 session mobruji 부재 → skip)
# ─────────────────────────────────────────────────────────────────────────────
echo "Case 9: nmae inject 부재 session graceful skip"
TMP9="$(mktemp -d)"
JSONL9="$TMP9/board.jsonl"
echo '{"message_id":"M_DDD","polished":false}' > "$JSONL9"

# 부재 session 지정 → graceful skip.
OUT9=$(DIRECTIVE_BOARD_JSONL_PATH="$JSONL9" \
  MOBRUJI_NMAE_PANE="non-existent-session-xyz:0.0" \
  bash "$MARK_SCRIPT" "M_DDD" 2>&1)
_assert_eq "Case 9 부재 session exit 0 (graceful)" "0" "$?"
# tmux 가 있으면 "session 부재 skip" / 없으면 "tmux 부재 skip" — 둘 중 하나.
case "$OUT9" in
  *"session"*"부재"*|*"tmux"*"부재"*) _assert_eq "Case 9 graceful skip 메시지" "graceful" "graceful" ;;
  *) _assert_eq "Case 9 graceful skip 메시지" "graceful" "$(echo "$OUT9" | tail -c 200)" ;;
esac
POLISHED9=$(jq -r '.polished' "$JSONL9")
_assert_eq "Case 9 polished=true (jsonl update 정상)" "true" "$POLISHED9"
rm -rf "$TMP9"

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
