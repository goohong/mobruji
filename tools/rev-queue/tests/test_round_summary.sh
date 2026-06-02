#!/usr/bin/env bash
# test_round_summary.sh — round-summary.sh 단위 테스트 (dry-run mode).
#
# spec: docs/features/rev-qa-protocol.md §5-9-5
#
# 검증 케이스:
#   1. 단순 round — 모든 stage 0 PASS / 0 HOLD / 0 FAIL → DIGEST format 본문 build.
#   2. PASS 만 — 단계 1: 3 PASS / stage 2-3 0 0 0 → "3 PASS / 0 HOLD / 0 FAIL".
#   3. FAIL detail 부착 — FAIL 있는 단계에 — {detail} 부착.
#   4. follow CSV — "후속: #N,#M" line 부착.
#   5. usage — round_id 부재 시 exit 64.
#   6. 잘못된 P/H/F format — graceful 0/0/0 fallback.

set -uo pipefail

SCRIPT_PATH="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/round-summary.sh"
if [[ ! -x "$SCRIPT_PATH" ]]; then
  chmod +x "$SCRIPT_PATH" 2>/dev/null || true
fi

PASS=0
FAIL=0
FAIL_NAMES=()

_assert_contains() {
  local name="$1" needle="$2" haystack="$3"
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

# ─────────────────────────────────────────────────────────────────────────────
# Case 1: 단순 round — 모든 stage 0.
# ─────────────────────────────────────────────────────────────────────────────
echo "Case 1: 단순 round"
OUT1=$(ROUND_SUMMARY_DRY_RUN=1 bash "$SCRIPT_PATH" "round-1" 2>&1)
_assert_contains "Case 1 round 헤더" "rev round round-1 종료" "$OUT1"
_assert_contains "Case 1 구분선" "─────────────────" "$OUT1"
_assert_contains "Case 1 단계 1 default" "단계 1 (PR 머지 전): 0 PASS / 0 HOLD / 0 FAIL" "$OUT1"
_assert_contains "Case 1 단계 2 default" "단계 2 (develop 후): 0 PASS / 0 HOLD / 0 FAIL" "$OUT1"
_assert_contains "Case 1 단계 3 default" "단계 3 (release 후): 0 PASS / 0 HOLD / 0 FAIL" "$OUT1"
# follow / fail-detail 없으면 그 줄 안 나옴.
case "$OUT1" in
  *"후속:"*) _assert_eq "Case 1 후속 line 없음" "no" "yes" ;;
  *) _assert_eq "Case 1 후속 line 없음" "no" "no" ;;
esac

# ─────────────────────────────────────────────────────────────────────────────
# Case 2: PASS 만 — 단계 1 = 3 PASS.
# ─────────────────────────────────────────────────────────────────────────────
echo "Case 2: PASS 만"
OUT2=$(ROUND_SUMMARY_DRY_RUN=1 bash "$SCRIPT_PATH" "12" --s1 "3/0/0" 2>&1)
_assert_contains "Case 2 단계 1 = 3/0/0" "단계 1 (PR 머지 전): 3 PASS / 0 HOLD / 0 FAIL" "$OUT2"

# ─────────────────────────────────────────────────────────────────────────────
# Case 3: FAIL detail 부착 — FAIL 있는 단계에.
# ─────────────────────────────────────────────────────────────────────────────
echo "Case 3: FAIL detail"
OUT3=$(ROUND_SUMMARY_DRY_RUN=1 bash "$SCRIPT_PATH" "12" \
  --s1 "3/1/0" --s2 "5/0/1" --s3 "2/0/0" \
  --fail-detail "#1196 develop 회귀" 2>&1)
_assert_contains "Case 3 단계 1 FAIL=0 detail 없음" "단계 1 (PR 머지 전): 3 PASS / 1 HOLD / 0 FAIL" "$OUT3"
_assert_contains "Case 3 단계 2 FAIL=1 + detail" "단계 2 (develop 후): 5 PASS / 0 HOLD / 1 FAIL — #1196 develop 회귀" "$OUT3"
_assert_contains "Case 3 단계 3 FAIL=0 detail 없음" "단계 3 (release 후): 2 PASS / 0 HOLD / 0 FAIL" "$OUT3"

# ─────────────────────────────────────────────────────────────────────────────
# Case 4: follow CSV — "후속: ..." line.
# ─────────────────────────────────────────────────────────────────────────────
echo "Case 4: follow CSV"
OUT4=$(ROUND_SUMMARY_DRY_RUN=1 bash "$SCRIPT_PATH" "12" \
  --follow "#1234,#1235,#1236" 2>&1)
_assert_contains "Case 4 후속 line" "후속: #1234,#1235,#1236" "$OUT4"

# ─────────────────────────────────────────────────────────────────────────────
# Case 5: usage — round_id 부재.
# ─────────────────────────────────────────────────────────────────────────────
echo "Case 5: usage"
bash "$SCRIPT_PATH" > /dev/null 2>&1
_assert_eq "Case 5 인자 0 exit 64" "64" "$?"

# ─────────────────────────────────────────────────────────────────────────────
# Case 6: 잘못된 P/H/F format → graceful 0/0/0.
# ─────────────────────────────────────────────────────────────────────────────
echo "Case 6: malformed P/H/F"
OUT6=$(ROUND_SUMMARY_DRY_RUN=1 bash "$SCRIPT_PATH" "12" --s1 "abc/def/ghi" 2>&1)
_assert_contains "Case 6 비숫자 → 0/0/0 fallback" "단계 1 (PR 머지 전): 0 PASS / 0 HOLD / 0 FAIL" "$OUT6"

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
