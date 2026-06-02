#!/usr/bin/env bash
# tools/tests/test_directive_autoseed.sh — 유휴 시 백로그 자동 시드
# (autoseed.sh) 단위 테스트.
#
# spec: docs/features/roadmap-queue-autoseed.md §7 테스트 전략
#
# 검증 시나리오:
#   1. happy path — be idle + 큐 고갈 + 후보 1건 → seeded (source/seed_issue/
#      assigned_cycle/polished 박제) + "🌱 autoseed" 1줄.
#   2. G1 pending cap — seeded 대기 == cap → 추가 시드 skip.
#   3. G1 cap 은 seeded-only — 사람 directive 가 많아도 cap 무영향.
#   4. G2 batch — be/fe 둘 다 idle + AUTOSEED_BATCH=1 → 1건만 seed.
#   5. G3 high-stakes — type:release 라벨 / 보호영역 키워드 이슈 제외.
#   6. G4 seed_issue dedup — 이미 seed_issue=N → skip.
#   7. G4 매핑 PR 존재 — skip.
#   8. G4 assignee 보유 — skip.
#   9. not-idle (in_progress 있음 / idle_since 최근) → skip.
#  10. cycle_has_pending — 이미 대기 entry → skip.
#  11. scope→cycle 매핑 — fe←scope:web seed / rev (매핑 없음) skip.
#  12. G5 실패 격리 — append 실패 → loop exit 0 + 0 seed.
#  13. --dry-run — board 불변.
#
# 외부 gh / discord-reply 는 fake bin 으로 대체.
#
# 사용:
#   bash tools/tests/test_directive_autoseed.sh

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AUTOSEED_SH="$SCRIPT_DIR/../directive-board/autoseed.sh"
APPEND_SH="$SCRIPT_DIR/../discord-daemon/directive_append.sh"

PASS=0
FAIL=0
FAILURES=()

if [[ ! -x "$AUTOSEED_SH" ]]; then
  echo "FATAL: $AUTOSEED_SH 실행 불가 (chmod +x 필요)." >&2
  exit 2
fi
if ! command -v jq >/dev/null 2>&1; then
  echo "SKIP: jq 미설치 — 본 테스트는 jq 의존입니다." >&2
  exit 0
fi

make_tmp() { mktemp -d -t autoseed-test-XXXXXX; }

# fake gh — issue list/view + pr list 를 fixture 디렉토리로 응답.
#   $GH_FIXTURE_DIR/issues-<label>.json  — gh issue list --label <label> 결과
#   $GH_FIXTURE_DIR/mapped-prs.txt       — 한 줄 1 이슈번호 (매핑 PR 존재로 간주)
make_fake_gh() {
  local target="$1"
  cat > "$target" <<'EOF'
#!/usr/bin/env bash
FIX="$GH_FIXTURE_DIR"
cmd="${1:-}"; sub="${2:-}"; shift 2 2>/dev/null || true
if [[ "$cmd" == "issue" && "$sub" == "list" ]]; then
  label=""
  while [[ $# -gt 0 ]]; do [[ "$1" == "--label" ]] && label="${2:-}"; shift; done
  f="$FIX/issues-$label.json"
  [[ -f "$f" ]] && cat "$f" || echo "[]"
  exit 0
fi
if [[ "$cmd" == "issue" && "$sub" == "view" ]]; then
  num="${1:-}"
  ALL=$(cat "$FIX"/issues-*.json 2>/dev/null | jq -s 'add // []')
  echo "$ALL" | jq -r --argjson n "$num" 'map(select(.number==$n))|.[0].title // ""'
  exit 0
fi
if [[ "$cmd" == "pr" && "$sub" == "list" ]]; then
  num=""
  while [[ $# -gt 0 ]]; do [[ "$1" == "--search" ]] && num="${2%% *}"; shift; done
  if [[ -f "$FIX/mapped-prs.txt" ]] && grep -qx "$num" "$FIX/mapped-prs.txt"; then
    echo '[{"number":999}]'
  else
    echo '[]'
  fi
  exit 0
fi
echo "[]"
EOF
  chmod +x "$target"
}

# fake append 가 항상 exit 1 (G5 실패 격리 케이스).
make_failing_append() {
  local target="$1"
  cat > "$target" <<'EOF'
#!/usr/bin/env bash
exit 1
EOF
  chmod +x "$target"
}

iso_minutes_ago() {
  date -u -d "$1 minutes ago" '+%Y-%m-%dT%H:%M:%SZ'
}

# 공통 fixture 셋업 — cycle-status.json + 빈 board + fake gh.
# 인자: <tmp> <cycle-status-json>
setup_env() {
  local tmp="$1" cs_json="$2"
  echo "$cs_json" > "$tmp/cycle-status.json"
  : > "$tmp/board.jsonl"
  mkdir -p "$tmp/fix"
  make_fake_gh "$tmp/gh"
}

# autoseed 실행 (공통 env). stdout 반환, $RC 세팅.
# 첫 인자 = tmp, 나머지 = autoseed.sh 에 전달할 플래그 (--dry-run 등).
run_autoseed() {
  local tmp="$1"; shift
  CYCLE_STATUS_PATH="$tmp/cycle-status.json" \
  DIRECTIVE_BOARD_JSONL_PATH="$tmp/board.jsonl" \
  GH_FIXTURE_DIR="$tmp/fix" \
  AUTOSEED_GH_BIN="$tmp/gh" \
  AUTOSEED_APPEND_BIN="${APPEND_BIN_OVERRIDE:-$APPEND_SH}" \
  AUTOSEED_MARK_POLISHED_BIN="/bin/true" \
  AUTOSEED_CYCLE_UPDATE_BIN="/bin/true" \
  DISCORD_REPLY_BIN="$tmp/nodiscord.sh" \
  "$AUTOSEED_SH" "$@" 2>/dev/null
}

assert_eq() {
  local name="$1" expected="$2" actual="$3"
  if [[ "$actual" == "$expected" ]]; then
    PASS=$((PASS + 1)); echo "PASS: $name"
  else
    FAIL=$((FAIL + 1)); FAILURES+=("$name (expected=$expected actual=$actual)")
    echo "FAIL: $name (expected=$expected actual=$actual)"
  fi
}
assert_contains() {
  local name="$1" haystack="$2" needle="$3"
  if [[ "$haystack" == *"$needle"* ]]; then
    PASS=$((PASS + 1)); echo "PASS: $name"
  else
    FAIL=$((FAIL + 1)); FAILURES+=("$name (needle missing: '$needle')")
    echo "FAIL: $name (haystack='$haystack')"
  fi
}

# board 안 source=autoseed status=대기 entry 수.
seeded_count() { jq -s '[.[]|select(.status=="대기" and .source=="autoseed")]|length' "$1" 2>/dev/null || echo 0; }

OLD_IDLE="$(iso_minutes_ago 60)"
RECENT_IDLE="$(iso_minutes_ago 1)"
ALL_IDLE="{\"be\":{\"in_progress\":null,\"idle_since\":\"$OLD_IDLE\"},\"fe\":{\"in_progress\":null,\"idle_since\":\"$OLD_IDLE\"},\"rev\":{\"in_progress\":null,\"idle_since\":\"$OLD_IDLE\"},\"plan\":{\"in_progress\":null,\"idle_since\":\"$OLD_IDLE\"}}"

# 후보 1건 (scope:recommendation, assignee 없음, 평범한 제목).
CAND_REC='[{"number":1502,"title":"추천 ageGroup 가중 회귀 테스트 보강","labels":[{"name":"scope:recommendation"}],"createdAt":"2026-05-01T00:00:00Z","assignees":[],"body":"백로그 항목"}]'

# ── 1) happy path ────────────────────────────────────────────────────────────
TMP=$(make_tmp); setup_env "$TMP" "$ALL_IDLE"
echo "$CAND_REC" > "$TMP/fix/issues-scope:recommendation.json"
OUT=$(run_autoseed "$TMP"); RC=$?
assert_eq "1 happy exit 0" 0 "$RC"
assert_contains "1 happy stdout 🌱 #1502 → be" "$OUT" "🌱 autoseed: #1502 → be"
ENTRY=$(jq -c 'select(.seed_issue==1502)' "$TMP/board.jsonl")
assert_contains "1 happy source=autoseed" "$ENTRY" '"source":"autoseed"'
assert_contains "1 happy assigned_cycle=be" "$ENTRY" '"assigned_cycle":"be"'
assert_contains "1 happy polished=true" "$ENTRY" '"polished":true'
assert_contains "1 happy status=대기" "$ENTRY" '"status":"대기"'
rm -rf "$TMP"

# ── 2) G1 pending cap ────────────────────────────────────────────────────────
TMP=$(make_tmp); setup_env "$TMP" "$ALL_IDLE"
echo "$CAND_REC" > "$TMP/fix/issues-scope:recommendation.json"
# board 에 seeded 대기 cap(4)건 선적재 (다른 이슈번호).
for n in 9001 9002 9003 9004; do
  jq -nc --argjson n "$n" '{summary:"s",status:"대기",source:"autoseed",seed_issue:$n,assigned_cycle:"plan"}' >> "$TMP/board.jsonl"
done
BEFORE=$(seeded_count "$TMP/board.jsonl")
OUT=$(AUTOSEED_MAX_PENDING=4 run_autoseed "$TMP"); RC=$?
AFTER=$(seeded_count "$TMP/board.jsonl")
assert_eq "2 G1 cap exit 0" 0 "$RC"
assert_eq "2 G1 cap 추가 시드 0 (before=after)" "$BEFORE" "$AFTER"
rm -rf "$TMP"

# ── 3) G1 cap 은 seeded-only (사람 directive 무영향) ──────────────────────────
TMP=$(make_tmp); setup_env "$TMP" "$ALL_IDLE"
echo "$CAND_REC" > "$TMP/fix/issues-scope:recommendation.json"
# 사람 등록 대기 entry 10건 (source 없음) — cap 카운트에서 제외돼야 함.
for i in $(seq 1 10); do
  jq -nc --arg s "사람 directive $i" '{summary:$s,status:"대기",assigned_cycle:"plan"}' >> "$TMP/board.jsonl"
done
OUT=$(AUTOSEED_MAX_PENDING=4 run_autoseed "$TMP"); RC=$?
assert_eq "3 seeded-only exit 0" 0 "$RC"
assert_contains "3 사람 directive 多 → seed 정상 진행" "$OUT" "🌱 autoseed: #1502 → be"
rm -rf "$TMP"

# ── 4) G2 batch ──────────────────────────────────────────────────────────────
TMP=$(make_tmp); setup_env "$TMP" "$ALL_IDLE"
echo "$CAND_REC" > "$TMP/fix/issues-scope:recommendation.json"
echo '[{"number":1601,"title":"웹 다크모드 토글 잔여 fix","labels":[{"name":"scope:web"}],"createdAt":"2026-05-02T00:00:00Z","assignees":[],"body":"x"}]' > "$TMP/fix/issues-scope:web.json"
OUT=$(AUTOSEED_BATCH=1 run_autoseed "$TMP"); RC=$?
N_SEED=$(seeded_count "$TMP/board.jsonl")
assert_eq "4 G2 batch exit 0" 0 "$RC"
assert_eq "4 G2 batch=1 → 1건만 seed" 1 "$N_SEED"
rm -rf "$TMP"

# ── 5) G3 high-stakes 제외 ───────────────────────────────────────────────────
TMP=$(make_tmp); setup_env "$TMP" "$ALL_IDLE"
# 후보 2건: release 라벨 / 키워드(마이그레이션) — 둘 다 제외 → 0 seed.
echo '[{"number":1700,"title":"릴리즈 컷","labels":[{"name":"scope:recommendation"},{"name":"type:release"}],"createdAt":"2026-05-01T00:00:00Z","assignees":[],"body":"x"},{"number":1701,"title":"추천 스키마 변경","labels":[{"name":"scope:recommendation"}],"createdAt":"2026-05-02T00:00:00Z","assignees":[],"body":"DB 마이그레이션 동반"}]' > "$TMP/fix/issues-scope:recommendation.json"
OUT=$(run_autoseed "$TMP"); RC=$?
N_SEED=$(seeded_count "$TMP/board.jsonl")
assert_eq "5 G3 high-stakes exit 0" 0 "$RC"
assert_eq "5 G3 release/키워드 제외 → 0 seed" 0 "$N_SEED"
rm -rf "$TMP"

# ── 6) G4 seed_issue dedup ───────────────────────────────────────────────────
TMP=$(make_tmp); setup_env "$TMP" "$ALL_IDLE"
echo "$CAND_REC" > "$TMP/fix/issues-scope:recommendation.json"
jq -nc '{summary:"기시드",status:"완료",source:"autoseed",seed_issue:1502,assigned_cycle:"be"}' >> "$TMP/board.jsonl"
OUT=$(run_autoseed "$TMP"); RC=$?
N_NEW=$(jq -s '[.[]|select(.seed_issue==1502)]|length' "$TMP/board.jsonl")
assert_eq "6 G4 dedup exit 0" 0 "$RC"
assert_eq "6 G4 seed_issue=1502 재시드 안 함 (여전히 1건)" 1 "$N_NEW"
rm -rf "$TMP"

# ── 7) G4 매핑 PR 존재 ───────────────────────────────────────────────────────
TMP=$(make_tmp); setup_env "$TMP" "$ALL_IDLE"
echo "$CAND_REC" > "$TMP/fix/issues-scope:recommendation.json"
echo "1502" > "$TMP/fix/mapped-prs.txt"
OUT=$(run_autoseed "$TMP"); RC=$?
N_SEED=$(seeded_count "$TMP/board.jsonl")
assert_eq "7 G4 매핑 PR exit 0" 0 "$RC"
assert_eq "7 G4 매핑 PR 존재 → 0 seed" 0 "$N_SEED"
rm -rf "$TMP"

# ── 8) G4 assignee 보유 ──────────────────────────────────────────────────────
TMP=$(make_tmp); setup_env "$TMP" "$ALL_IDLE"
echo '[{"number":1502,"title":"t","labels":[{"name":"scope:recommendation"}],"createdAt":"2026-05-01T00:00:00Z","assignees":[{"login":"someone"}],"body":"x"}]' > "$TMP/fix/issues-scope:recommendation.json"
OUT=$(run_autoseed "$TMP"); RC=$?
N_SEED=$(seeded_count "$TMP/board.jsonl")
assert_eq "8 G4 assignee exit 0" 0 "$RC"
assert_eq "8 G4 assignee 보유 → 0 seed" 0 "$N_SEED"
rm -rf "$TMP"

# ── 9) not-idle ──────────────────────────────────────────────────────────────
TMP=$(make_tmp)
NOT_IDLE="{\"be\":{\"in_progress\":{\"title\":\"작업중\"}},\"fe\":{\"in_progress\":null,\"idle_since\":\"$RECENT_IDLE\"},\"rev\":{\"in_progress\":null},\"plan\":{\"in_progress\":null}}"
setup_env "$TMP" "$NOT_IDLE"
echo "$CAND_REC" > "$TMP/fix/issues-scope:recommendation.json"
echo '[{"number":1601,"title":"웹","labels":[{"name":"scope:web"}],"createdAt":"2026-05-02T00:00:00Z","assignees":[],"body":"x"}]' > "$TMP/fix/issues-scope:web.json"
OUT=$(run_autoseed "$TMP"); RC=$?
N_SEED=$(seeded_count "$TMP/board.jsonl")
assert_eq "9 not-idle exit 0" 0 "$RC"
assert_eq "9 be 작업중 + fe idle_since 최근 → 0 seed" 0 "$N_SEED"
rm -rf "$TMP"

# ── 10) cycle_has_pending ────────────────────────────────────────────────────
TMP=$(make_tmp); setup_env "$TMP" "$ALL_IDLE"
echo "$CAND_REC" > "$TMP/fix/issues-scope:recommendation.json"
jq -nc '{summary:"기존 대기",status:"대기",assigned_cycle:"be"}' >> "$TMP/board.jsonl"
OUT=$(run_autoseed "$TMP"); RC=$?
N_SEED=$(seeded_count "$TMP/board.jsonl")
assert_eq "10 has-pending exit 0" 0 "$RC"
assert_eq "10 be 이미 대기 entry → seed 0" 0 "$N_SEED"
rm -rf "$TMP"

# ── 11) scope→cycle 매핑 (fe seed / rev 매핑 없음) ───────────────────────────
TMP=$(make_tmp)
# rev 만 idle, be/fe 작업중 → rev 는 매핑 없으므로 0 seed.
REV_ONLY="{\"be\":{\"in_progress\":{\"title\":\"x\"}},\"fe\":{\"in_progress\":{\"title\":\"x\"}},\"rev\":{\"in_progress\":null,\"idle_since\":\"$OLD_IDLE\"},\"plan\":{\"in_progress\":{\"title\":\"x\"}}}"
setup_env "$TMP" "$REV_ONLY"
echo '[{"number":1502,"title":"t","labels":[{"name":"scope:recommendation"}],"createdAt":"2026-05-01T00:00:00Z","assignees":[],"body":"x"}]' > "$TMP/fix/issues-scope:recommendation.json"
OUT=$(run_autoseed "$TMP"); RC=$?
N_SEED=$(seeded_count "$TMP/board.jsonl")
assert_eq "11a rev-only exit 0" 0 "$RC"
assert_eq "11a rev 매핑 없음 → 0 seed" 0 "$N_SEED"
rm -rf "$TMP"
# fe idle + scope:web 후보 → assigned_cycle=fe seed.
TMP=$(make_tmp)
FE_ONLY="{\"be\":{\"in_progress\":{\"title\":\"x\"}},\"fe\":{\"in_progress\":null,\"idle_since\":\"$OLD_IDLE\"},\"rev\":{\"in_progress\":{\"title\":\"x\"}},\"plan\":{\"in_progress\":{\"title\":\"x\"}}}"
setup_env "$TMP" "$FE_ONLY"
echo '[{"number":1601,"title":"웹 다크모드 fix","labels":[{"name":"scope:web"}],"createdAt":"2026-05-02T00:00:00Z","assignees":[],"body":"x"}]' > "$TMP/fix/issues-scope:web.json"
OUT=$(run_autoseed "$TMP"); RC=$?
ENTRY=$(jq -c 'select(.seed_issue==1601)' "$TMP/board.jsonl")
assert_eq "11b fe-only exit 0" 0 "$RC"
assert_contains "11b fe seed assigned_cycle=fe" "$ENTRY" '"assigned_cycle":"fe"'
rm -rf "$TMP"

# ── 12) G5 실패 격리 ─────────────────────────────────────────────────────────
TMP=$(make_tmp); setup_env "$TMP" "$ALL_IDLE"
echo "$CAND_REC" > "$TMP/fix/issues-scope:recommendation.json"
make_failing_append "$TMP/fail-append.sh"
OUT=$(APPEND_BIN_OVERRIDE="$TMP/fail-append.sh" run_autoseed "$TMP"); RC=$?
N_SEED=$(seeded_count "$TMP/board.jsonl")
assert_eq "12 G5 append 실패 → loop exit 0" 0 "$RC"
assert_eq "12 G5 실패 → 0 seed (격리)" 0 "$N_SEED"
rm -rf "$TMP"

# ── 13) --dry-run ────────────────────────────────────────────────────────────
TMP=$(make_tmp); setup_env "$TMP" "$ALL_IDLE"
echo "$CAND_REC" > "$TMP/fix/issues-scope:recommendation.json"
OUT=$(run_autoseed "$TMP" --dry-run); RC=$?
N_LINES=$(wc -l < "$TMP/board.jsonl" | tr -d ' ')
assert_eq "13 dry-run exit 0" 0 "$RC"
assert_contains "13 dry-run stdout [dry-run]" "$OUT" "[dry-run] seed #1502"
assert_eq "13 dry-run board 불변 (0 줄)" 0 "$N_LINES"
rm -rf "$TMP"

# ── 결과 ─────────────────────────────────────────────────────────────────────
echo
echo "─── 결과 ───"
echo "PASS: $PASS"
echo "FAIL: $FAIL"
if [[ $FAIL -gt 0 ]]; then
  echo "실패 케이스:"
  for entry in "${FAILURES[@]}"; do echo "  - $entry"; done
  exit 1
fi
exit 0
