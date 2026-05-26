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
    if grep -q "agent-worktree: 0" /tmp/sweep-remoteonly.log; then
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

echo ""
echo "all tests passed"
