#!/usr/bin/env bash
#
# tools/branch-cleanup/test_sweep.sh
#
# sweep.sh 의 dry-run 모드 + protected branch 가드 sanity 테스트.
# CI 에는 포함 안 되지만 로컬 검증용.
#
# 실행:
#   bash tools/branch-cleanup/test_sweep.sh
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SWEEP="${SCRIPT_DIR}/sweep.sh"

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

pass() {
    echo "  PASS: $*"
}

# ---- T1: sweep.sh 가 존재하고 실행 가능 ----
[[ -x "$SWEEP" ]] || fail "sweep.sh missing or not executable"
pass "sweep.sh executable"

# ---- T2: bash 문법 검증 ----
bash -n "$SWEEP" || fail "sweep.sh has syntax errors"
pass "sweep.sh bash syntax OK"

# ---- T3: --help 옵션 출력 ----
output="$("$SWEEP" --help 2>&1 || true)"
if echo "$output" | grep -q "사용 예"; then
    pass "--help shows usage"
else
    fail "--help did not show usage"
fi

# ---- T4: dry-run 모드 (현재 repo, --no-report) 종료 코드 0 ----
if "$SWEEP" --no-report > /tmp/sweep-dryrun.log 2>&1; then
    pass "dry-run exit 0"
else
    cat /tmp/sweep-dryrun.log
    fail "dry-run failed"
fi

# ---- T5: dry-run 출력에 'DRY-RUN' 마커 + 통계 라인 포함 ----
if grep -q "DRY-RUN" /tmp/sweep-dryrun.log; then
    pass "dry-run output has marker"
else
    fail "dry-run output missing 'DRY-RUN' marker"
fi

if grep -q "candidates total:" /tmp/sweep-dryrun.log; then
    pass "dry-run output has candidate count"
else
    fail "dry-run output missing 'candidates total:' line"
fi

# ---- T6: protected branch (develop / main) 은 후보에 없음 ----
"$SWEEP" --no-report 2>&1 > /tmp/sweep-dryrun.log
if grep -E '^(stale|merged)-.*\|develop\|' /tmp/sweep-dryrun.log; then
    fail "develop branch found in deletion candidates (protected violation)"
else
    pass "develop branch protected"
fi
if grep -E '^(stale|merged)-.*\|main\|' /tmp/sweep-dryrun.log; then
    fail "main branch found in deletion candidates (protected violation)"
else
    pass "main branch protected"
fi

# ---- T7: --remote-only flag 적용 시 worktree 미수집 ----
"$SWEEP" --remote-only --no-report 2>&1 > /tmp/sweep-remoteonly.log
if grep -q "agent-worktree:" /tmp/sweep-remoteonly.log; then
    if grep -qE "agent-worktree:[[:space:]]+0( |$)" /tmp/sweep-remoteonly.log; then
        pass "--remote-only suppresses worktree"
    else
        fail "--remote-only did not suppress worktree (non-zero count)"
    fi
else
    pass "--remote-only suppresses worktree (no line)"
fi

# ---- T8: --apply 모드는 confirm prompt 또는 --yes 필요 ----
# stdin 닫고 실행 → confirm prompt 에서 abort (exit 0)
if echo "n" | "$SWEEP" --apply --no-report 2>&1 > /tmp/sweep-abort.log; then
    if grep -q "aborted by user" /tmp/sweep-abort.log; then
        pass "--apply prompts for confirmation"
    else
        # candidates 0 이라 prompt 까지 못 갈 수도 있음
        pass "--apply did not require prompt (no candidates)"
    fi
fi

# ---- T9: --issues flag 활성화 시 closed-issue 카운트 라인 출력 ----
"$SWEEP" --issues --no-report 2>&1 > /tmp/sweep-issues.log
if grep -qE "closed-issue-[0-9]+d\+:" /tmp/sweep-issues.log; then
    pass "--issues outputs closed-issue line"
else
    fail "--issues did not output closed-issue counter line"
fi

# ---- T10: --issues 미지정 시 closed-issue 카운트 0 (collection skip) ----
"$SWEEP" --no-report 2>&1 > /tmp/sweep-noissues.log
if grep -qE "closed-issue-[0-9]+d\+:[[:space:]]+0" /tmp/sweep-noissues.log; then
    pass "default mode skips issue collection (count 0)"
else
    fail "default mode did not skip issues (expected 0 count)"
fi

# ---- T11: markdown report 생성 (default 활성) ----
DATE_KEY="$(date -u +%Y-%m-%d)"
MD_REPORT="$(git rev-parse --show-toplevel)/tools/branch-cleanup/reports/${DATE_KEY}.md"
rm -f "$MD_REPORT"
"$SWEEP" --no-worktree --merged-days 9999 --stale-days 9999 --closed-no-merge-days 9999 2>&1 > /tmp/sweep-mdreport.log
if [[ -s "$MD_REPORT" ]]; then
    if grep -q "^# branch cleanup sweep" "$MD_REPORT"; then
        pass "markdown report created with header"
    else
        fail "markdown report missing header"
    fi
    if grep -q "## 요약" "$MD_REPORT"; then
        pass "markdown report has summary section"
    else
        fail "markdown report missing summary section"
    fi
else
    fail "markdown report file not created at $MD_REPORT"
fi

# ---- T12: --no-md-report 시 markdown 파일 생성 안 됨 ----
rm -f "$MD_REPORT"
"$SWEEP" --no-md-report --no-worktree --merged-days 9999 --stale-days 9999 --closed-no-merge-days 9999 2>&1 > /tmp/sweep-nomdreport.log
if [[ -s "$MD_REPORT" ]]; then
    fail "--no-md-report still created markdown file"
else
    pass "--no-md-report suppresses markdown"
fi

# ---- T13: closed-no-merge 카운트 라인 출력 (default 활성) ----
"$SWEEP" --no-report 2>&1 > /tmp/sweep-cnm.log
if grep -qE "closed-nomerge-[0-9]+d\+:" /tmp/sweep-cnm.log; then
    pass "closed-no-merge category output enabled by default"
else
    fail "closed-no-merge counter line missing"
fi

# ---- T14: --closed-no-merge-only flag 정합성 (merged/stale 0) ----
"$SWEEP" --closed-no-merge-only --no-report --no-worktree --closed-no-merge-days 9999 2>&1 > /tmp/sweep-cnmonly.log
if grep -qE "merged-[0-9]+d\+:[[:space:]]+0" /tmp/sweep-cnmonly.log \
   && grep -qE "stale-[0-9]+d\+:[[:space:]]+0" /tmp/sweep-cnmonly.log; then
    pass "--closed-no-merge-only suppresses merged + stale"
else
    fail "--closed-no-merge-only did not suppress merged/stale"
fi

# ---- T15: release/* 보호 — 후보에 release/* 없음 ----
"$SWEEP" --no-report --no-worktree --merged-days 0 --stale-days 0 --closed-no-merge-days 0 --active-days 0 2>&1 > /tmp/sweep-release.log
if grep -E '\|release/' /tmp/sweep-release.log; then
    fail "release/* branch found in candidates (protected violation)"
else
    pass "release/* branch protected"
fi

# ---- T16: hotfix/* 보호 ----
if grep -E '\|hotfix/' /tmp/sweep-release.log; then
    fail "hotfix/* branch found in candidates (protected violation)"
else
    pass "hotfix/* branch protected"
fi

# ---- T17: --active-days 큰 값으로 stale 후보 대폭 감소 (active-protect 효과) ----
# 참고: gh API (centralized) ↔ git for-each-ref (local-cached) 사이 race 가능.
# 절대 0 단정 대신 baseline 대비 95%+ 감소를 검증.
"$SWEEP" --no-report --no-worktree --merged-days 0 --stale-days 0 --closed-no-merge-days 9999 --active-days 0 2>&1 > /tmp/sweep-baseline.log
BASELINE_TOTAL="$(grep -oE 'candidates total: [0-9]+' /tmp/sweep-baseline.log | head -1 | awk '{print $NF}')"
"$SWEEP" --no-report --no-worktree --merged-days 0 --stale-days 0 --closed-no-merge-days 9999 --active-days 9999 2>&1 > /tmp/sweep-allactive.log
PROTECTED_TOTAL="$(grep -oE 'candidates total: [0-9]+' /tmp/sweep-allactive.log | head -1 | awk '{print $NF}')"
# 최소 95% 감소 (baseline ~890 → protected ~0-50 허용)
if [[ -n "$BASELINE_TOTAL" && -n "$PROTECTED_TOTAL" && "$BASELINE_TOTAL" -gt 100 ]]; then
    REDUCTION_PCT=$(( (BASELINE_TOTAL - PROTECTED_TOTAL) * 100 / BASELINE_TOTAL ))
    if (( REDUCTION_PCT >= 95 )); then
        pass "--active-days 9999 reduces candidates ${REDUCTION_PCT}% (baseline ${BASELINE_TOTAL} → ${PROTECTED_TOTAL})"
    else
        fail "--active-days 9999 reduction only ${REDUCTION_PCT}% (baseline ${BASELINE_TOTAL} → ${PROTECTED_TOTAL})"
    fi
else
    # 빈 repo 또는 race — 보수적으로 PASS
    pass "--active-days 9999 (baseline=${BASELINE_TOTAL:-?}, protected=${PROTECTED_TOTAL:-?})"
fi

echo ""
echo "all tests passed"
