#!/usr/bin/env bash
#
# tools/branch-cleanup/sweep.sh
#
# remote + local branch + .claude/worktrees/agent-* cleanup (issue #1127).
#
# 정책 (대상 선정 — read-only 분류):
#   1. **merged-branch**: PR 머지 후 (closed=merged) + merge 시점 30일+ 경과 → 삭제 후보
#   2. **stale-branch**: open PR 없음 + last commit 60일+ → 삭제 후보
#   3. **agent-worktree**: .claude/worktrees/agent-* path 존재 시 prune 후보
#   4. **protected**: develop / main / HEAD / 현재 checkout / 머지 안 됐고 PR open
#      → 삭제 제외 (whitelist)
#
# 기본 모드: dry-run (실 삭제 없음). `--apply` flag 로 실 삭제 모드 진입.
#
# 사용 예:
#   bash tools/branch-cleanup/sweep.sh                       # dry-run + 통계
#   bash tools/branch-cleanup/sweep.sh --apply               # 실 삭제 + 통계
#   bash tools/branch-cleanup/sweep.sh --merged-only         # 머지된 branch 만 (60일 stale 제외)
#   bash tools/branch-cleanup/sweep.sh --merged-days 30      # 머지 30일+ (default 30)
#   bash tools/branch-cleanup/sweep.sh --stale-days 60       # last-commit 60일+ (default 60)
#   bash tools/branch-cleanup/sweep.sh --remote-only         # remote 만 (local 건드리지 않음)
#   bash tools/branch-cleanup/sweep.sh --local-only          # local 만
#
# 검증:
#   - dry-run 결과 파일 sweep-report-<ts>.txt 로 저장 (--no-report 로 끔)
#   - --apply 적용 직전 sweep-report 의 head 5건 echo + 사용자 confirm
#     (`--yes` 로 confirm skip 가능, 자동화 용)
#
# 사고 박제:
#   2026-05-26 사용자 brief — remote 446 + local 419 누적. PR 머지 후 head branch
#   삭제 안 됐고, abandon 된 작업 브랜치들이 누적. 일괄 cleanup 필요.

set -euo pipefail

# ---- defaults ----
APPLY=0
MERGED_DAYS=30
STALE_DAYS=60
LIMIT_MERGED=500       # gh pr list --state merged --limit (default 500)
MODE_MERGED=1
MODE_STALE=1
MODE_REMOTE=1
MODE_LOCAL=1
MODE_WORKTREE=1
WRITE_REPORT=1
YES=0
REPO_ROOT=""

usage() {
    sed -n '3,30p' "$0"
    exit 0
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --apply) APPLY=1; shift ;;
        --merged-days) MERGED_DAYS="$2"; shift 2 ;;
        --stale-days) STALE_DAYS="$2"; shift 2 ;;
        --limit-merged) LIMIT_MERGED="$2"; shift 2 ;;
        --merged-only) MODE_STALE=0; shift ;;
        --stale-only) MODE_MERGED=0; shift ;;
        --remote-only) MODE_LOCAL=0; MODE_WORKTREE=0; shift ;;
        --local-only) MODE_REMOTE=0; MODE_WORKTREE=0; shift ;;
        --no-worktree) MODE_WORKTREE=0; shift ;;
        --no-report) WRITE_REPORT=0; shift ;;
        --yes) YES=1; shift ;;
        -h|--help) usage ;;
        *) echo "unknown arg: $1" >&2; exit 2 ;;
    esac
done

# ---- repo root 감지 ----
if ! REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null)"; then
    echo "error: not a git repo (run from inside repo)" >&2
    exit 2
fi
cd "$REPO_ROOT"

CURRENT_BRANCH="$(git branch --show-current)"
TS="$(date -u +%Y%m%dT%H%M%SZ)"
REPORT_FILE="${REPO_ROOT}/sweep-report-${TS}.txt"

# ---- whitelist (절대 삭제 금지 branch) ----
PROTECTED_BRANCHES=(
    "main"
    "develop"
    "HEAD"
    "$CURRENT_BRANCH"
)

is_protected() {
    local branch="$1"
    for p in "${PROTECTED_BRANCHES[@]}"; do
        [[ "$branch" == "$p" ]] && return 0
        [[ "$branch" == "origin/$p" ]] && return 0
    done
    return 1
}

# ---- helpers ----
log() { echo "[$(date -u +%H:%M:%S)] $*"; }

now_epoch() { date +%s; }

# branch last commit epoch (remote: origin/X 형식 / local: X)
branch_last_epoch() {
    git log -1 --format="%ct" "$1" 2>/dev/null || echo "0"
}

# ---- 분류 ----
# merged-branch: gh api 로 PR 머지 시점 받기
collect_merged_branches() {
    # gh pr list --state merged 는 default 30건 — pagination 으로 200건까지
    local cutoff_epoch
    cutoff_epoch=$(( $(now_epoch) - MERGED_DAYS * 86400 ))
    gh pr list --state merged --limit "$LIMIT_MERGED" \
        --json number,headRefName,mergedAt \
        --jq '.[] | "\(.mergedAt)\t\(.headRefName)\t\(.number)"' 2>/dev/null \
        | while IFS=$'\t' read -r merged_at branch num; do
            # mergedAt 은 ISO 8601 (e.g. 2026-05-26T06:17:14Z)
            local merged_epoch
            merged_epoch=$(date -d "$merged_at" +%s 2>/dev/null || echo "0")
            if (( merged_epoch > 0 && merged_epoch < cutoff_epoch )); then
                if ! is_protected "$branch"; then
                    echo "merged-${MERGED_DAYS}d+|${branch}|#${num}|${merged_at}"
                fi
            fi
        done
}

# stale-branch: open PR 없는 branch + last commit 60일+
collect_stale_branches() {
    local cutoff_epoch
    cutoff_epoch=$(( $(now_epoch) - STALE_DAYS * 86400 ))

    # open PR head branch 목록 (제외 대상)
    local open_pr_branches
    open_pr_branches="$(gh pr list --state open --limit 500 \
        --json headRefName --jq '.[].headRefName' 2>/dev/null | sort -u)"

    # remote branches (origin/*)
    git for-each-ref refs/remotes/origin \
        --format='%(refname:short)|%(committerdate:unix)' 2>/dev/null \
        | while IFS='|' read -r ref epoch; do
            local branch="${ref#origin/}"
            is_protected "$branch" && continue
            [[ "$branch" == "HEAD" ]] && continue
            # open PR 보유 시 skip
            if echo "$open_pr_branches" | grep -Fxq "$branch"; then
                continue
            fi
            if (( epoch > 0 && epoch < cutoff_epoch )); then
                echo "stale-${STALE_DAYS}d+|${branch}|-|${epoch}"
            fi
        done
}

# agent-worktree: .claude/worktrees/agent-* path
collect_worktrees() {
    git worktree list --porcelain 2>/dev/null | awk '
        /^worktree / { path=$2 }
        /^locked/ { is_locked=1 }
        /^$/ {
            if (path && path ~ /\.claude\/worktrees\/agent-/) {
                printf "agent-worktree|%s|%s|locked=%d\n", path, "-", (is_locked?1:0)
            }
            path=""; is_locked=0
        }
        END {
            if (path && path ~ /\.claude\/worktrees\/agent-/) {
                printf "agent-worktree|%s|%s|locked=%d\n", path, "-", (is_locked?1:0)
            }
        }
    '
}

# ---- collect + report ----
log "sweep started — REPO=${REPO_ROOT} APPLY=${APPLY} MERGED_DAYS=${MERGED_DAYS} STALE_DAYS=${STALE_DAYS}"

CANDIDATES_FILE="$(mktemp)"
trap 'rm -f "$CANDIDATES_FILE"' EXIT

if (( MODE_MERGED == 1 && MODE_REMOTE == 1 )); then
    log "collecting merged branches (gh API, can take 10-20s) ..."
    collect_merged_branches >> "$CANDIDATES_FILE" || true
fi

if (( MODE_STALE == 1 && MODE_REMOTE == 1 )); then
    log "collecting stale remote branches ..."
    collect_stale_branches >> "$CANDIDATES_FILE" || true
fi

if (( MODE_WORKTREE == 1 )); then
    log "collecting agent worktrees ..."
    collect_worktrees >> "$CANDIDATES_FILE" || true
fi

TOTAL="$(wc -l < "$CANDIDATES_FILE")"
log "candidates total: ${TOTAL}"

# 분류별 카운트 (grep 미일치 시 0 — || true 로 set -e 회피)
MERGED_CNT="$(grep -c '^merged-' "$CANDIDATES_FILE" || true)"
STALE_CNT="$(grep -c '^stale-' "$CANDIDATES_FILE" || true)"
WORKTREE_CNT="$(grep -c '^agent-worktree|' "$CANDIDATES_FILE" || true)"
MERGED_CNT="${MERGED_CNT:-0}"
STALE_CNT="${STALE_CNT:-0}"
WORKTREE_CNT="${WORKTREE_CNT:-0}"

log "  - merged-${MERGED_DAYS}d+: ${MERGED_CNT}"
log "  - stale-${STALE_DAYS}d+: ${STALE_CNT}"
log "  - agent-worktree: ${WORKTREE_CNT}"

# report 작성
if (( WRITE_REPORT == 1 )); then
    {
        echo "# sweep-report ${TS}"
        echo "# REPO=${REPO_ROOT}"
        echo "# APPLY=${APPLY} MERGED_DAYS=${MERGED_DAYS} STALE_DAYS=${STALE_DAYS}"
        echo "# 형식: <category>|<branch_or_path>|<pr_or_dash>|<extra>"
        echo ""
        cat "$CANDIDATES_FILE"
    } > "$REPORT_FILE"
    log "report: ${REPORT_FILE}"
fi

# dry-run 모드 — 통계만 출력 후 종료
if (( APPLY == 0 )); then
    log "[DRY-RUN] no deletion. re-run with --apply to delete."
    echo ""
    echo "=== sample (first 10) ==="
    head -10 "$CANDIDATES_FILE" || true
    exit 0
fi

# ---- apply 모드 ----
log "[APPLY] mode — about to delete ${TOTAL} items."

if (( YES == 0 )); then
    echo ""
    echo "=== first 5 candidates ==="
    head -5 "$CANDIDATES_FILE" || true
    echo ""
    read -r -p "proceed? [y/N] " confirm
    [[ "$confirm" =~ ^[Yy]$ ]] || { log "aborted by user"; exit 0; }
fi

DELETED=0
FAILED=0

while IFS='|' read -r category target extra1 extra2; do
    case "$category" in
        merged-*|stale-*)
            # remote branch 삭제
            if git push origin --delete "$target" 2>/dev/null; then
                ((DELETED += 1))
                log "deleted remote: $target"
            else
                ((FAILED += 1))
                log "  fail (already gone?): origin/$target"
            fi
            # local branch 도 있으면 같이 삭제
            if (( MODE_LOCAL == 1 )); then
                if git branch -D "$target" 2>/dev/null; then
                    log "  deleted local: $target"
                fi
            fi
            ;;
        agent-worktree)
            if git worktree remove --force "$target" 2>/dev/null; then
                ((DELETED += 1))
                log "removed worktree: $target"
            else
                ((FAILED += 1))
                log "  fail: worktree $target (locked? manual rm 필요)"
            fi
            ;;
    esac
done < "$CANDIDATES_FILE"

# prune
if (( MODE_WORKTREE == 1 )); then
    git worktree prune -v 2>&1 | sed 's/^/  prune: /' || true
fi
if (( MODE_REMOTE == 1 )); then
    git remote prune origin 2>&1 | sed 's/^/  remote-prune: /' || true
fi

log "summary: deleted=${DELETED} failed=${FAILED}"
