#!/usr/bin/env bash
#
# tools/branch-cleanup/sweep.sh
#
# remote + local branch + .claude/worktrees/agent-* + closed issue cleanup (#1127 IMPL).
#
# 정책 — 5 카테고리 (read-only 분류 → --apply 로 실 삭제):
#   1. **merged-branch**: PR `merged` + merge 시점 30일+ 경과 → remote+local 삭제 후보
#   2. **closed-no-merge-branch**: PR `closed` 인데 머지 안 됨 (cancelled / superseded)
#                                   + closedAt 30일+ → remote+local 삭제 후보
#   3. **stale-branch**: open PR 없음 + last commit 60일+ → remote+local 삭제 후보
#   4. **agent-worktree**: .claude/worktrees/agent-* path → git worktree remove --force
#   5. **closed-issue** (`--issues` flag): closed 30일+ + label / milestone 정리 후보
#
# 보호 (whitelist, 모든 모드 공통):
#   - main / develop / HEAD / 현재 checkout / release/* / hotfix/* / open PR head
#   - 최근 N일 (default 7일) active commit branch (git for-each-ref committerdate)
#
# 기본 모드: dry-run (실 변경 없음). `--apply` flag 로 실 변경 모드.
#
# 사용 예:
#   bash tools/branch-cleanup/sweep.sh                       # dry-run + 통계 + markdown report
#   bash tools/branch-cleanup/sweep.sh --apply --yes         # 실 삭제 (branch 5 카테고리)
#   bash tools/branch-cleanup/sweep.sh --merged-only         # 머지된 branch 만
#   bash tools/branch-cleanup/sweep.sh --closed-no-merge-only # closed-not-merged 만
#   bash tools/branch-cleanup/sweep.sh --merged-days 30      # 머지 30일+ (default 30)
#   bash tools/branch-cleanup/sweep.sh --stale-days 60       # last-commit 60일+ (default 60)
#   bash tools/branch-cleanup/sweep.sh --active-days 7       # 최근 N일 active 보호 (default 7)
#   bash tools/branch-cleanup/sweep.sh --remote-only         # remote 만 (local 건드리지 않음)
#   bash tools/branch-cleanup/sweep.sh --local-only          # local 만
#   bash tools/branch-cleanup/sweep.sh --issues              # 추가로 closed issue 도 분류
#   bash tools/branch-cleanup/sweep.sh --issues --issues-days 30      # 30일+ closed issue
#   bash tools/branch-cleanup/sweep.sh --issues --apply               # stale 라벨 부착
#   bash tools/branch-cleanup/sweep.sh --issues --apply --archive-milestone "archived-2026Q2"
#                                                            # milestone 으로 묶기
#
# 검증:
#   - dry-run 결과 markdown 파일 reports/<YYYY-MM-DD>.md 로 저장 (default)
#   - 추가 plain text sweep-report-<ts>.txt 도 (--no-report 로 둘 다 끔)
#   - --apply 적용 직전 candidates head 5건 echo + confirm (--yes 로 skip)
#
# 사고 박제:
#   2026-05-26 사용자 16:04 directive — remote 451 / closed issue 416 / open 142 누적.
#   PR 머지 후 head branch 삭제 안 되어 누적 + closed issue archive 안 함.

set -euo pipefail

# ---- defaults ----
APPLY=0
MERGED_DAYS=30
CLOSED_NO_MERGE_DAYS=30
STALE_DAYS=60
ACTIVE_DAYS=7
LIMIT_MERGED=1000
LIMIT_CLOSED=1000
LIMIT_OPEN=500
LIMIT_ISSUES=1000
MODE_MERGED=1
MODE_CLOSED_NO_MERGE=1
MODE_STALE=1
MODE_REMOTE=1
MODE_LOCAL=1
MODE_WORKTREE=1
MODE_ISSUES=0
ISSUES_DAYS=30
ISSUES_STALE_LABEL="stale"
ARCHIVE_MILESTONE=""
WRITE_REPORT=1
WRITE_MD_REPORT=1
YES=0
REPO_ROOT=""

usage() {
    sed -n '3,45p' "$0"
    exit 0
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --apply) APPLY=1; shift ;;
        --merged-days) MERGED_DAYS="$2"; shift 2 ;;
        --closed-no-merge-days) CLOSED_NO_MERGE_DAYS="$2"; shift 2 ;;
        --stale-days) STALE_DAYS="$2"; shift 2 ;;
        --active-days) ACTIVE_DAYS="$2"; shift 2 ;;
        --limit-merged) LIMIT_MERGED="$2"; shift 2 ;;
        --limit-closed) LIMIT_CLOSED="$2"; shift 2 ;;
        --merged-only) MODE_STALE=0; MODE_CLOSED_NO_MERGE=0; shift ;;
        --closed-no-merge-only) MODE_MERGED=0; MODE_STALE=0; shift ;;
        --stale-only) MODE_MERGED=0; MODE_CLOSED_NO_MERGE=0; shift ;;
        --remote-only) MODE_LOCAL=0; MODE_WORKTREE=0; shift ;;
        --local-only) MODE_REMOTE=0; MODE_WORKTREE=0; shift ;;
        --no-worktree) MODE_WORKTREE=0; shift ;;
        --no-report) WRITE_REPORT=0; WRITE_MD_REPORT=0; shift ;;
        --no-md-report) WRITE_MD_REPORT=0; shift ;;
        --issues) MODE_ISSUES=1; shift ;;
        --issues-days) ISSUES_DAYS="$2"; shift 2 ;;
        --issues-stale-label) ISSUES_STALE_LABEL="$2"; shift 2 ;;
        --archive-milestone) ARCHIVE_MILESTONE="$2"; shift 2 ;;
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
DATE_KEY="$(date -u +%Y-%m-%d)"
REPORT_FILE="${REPO_ROOT}/sweep-report-${TS}.txt"
MD_REPORT_DIR="${REPO_ROOT}/tools/branch-cleanup/reports"
MD_REPORT_FILE="${MD_REPORT_DIR}/${DATE_KEY}.md"

# ---- whitelist (절대 삭제 금지 branch) ----
PROTECTED_BRANCHES=(
    "main"
    "develop"
    "HEAD"
    "$CURRENT_BRANCH"
)
PROTECTED_PREFIXES=(
    "release/"
    "hotfix/"
)

is_protected() {
    local branch="$1"
    for p in "${PROTECTED_BRANCHES[@]}"; do
        [[ "$branch" == "$p" ]] && return 0
        [[ "$branch" == "origin/$p" ]] && return 0
    done
    for prefix in "${PROTECTED_PREFIXES[@]}"; do
        [[ "$branch" == ${prefix}* ]] && return 0
    done
    return 1
}

# ---- helpers ----
log() { echo "[$(date -u +%H:%M:%S)] $*"; }

now_epoch() { date +%s; }

iso_to_epoch() {
    date -d "$1" +%s 2>/dev/null || echo "0"
}

epoch_to_iso() {
    date -u -d "@$1" +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || echo "?"
}

# ---- 캐시: open PR head + recent-active branches ----
OPEN_PR_BRANCHES=""
load_open_pr_branches() {
    OPEN_PR_BRANCHES="$(gh pr list --state open --limit "$LIMIT_OPEN" \
        --json headRefName --jq '.[].headRefName' 2>/dev/null | sort -u)"
}

is_open_pr_branch() {
    local branch="$1"
    [[ -z "$OPEN_PR_BRANCHES" ]] && return 1
    echo "$OPEN_PR_BRANCHES" | grep -Fxq "$branch"
}

RECENT_ACTIVE_BRANCHES=""
# branches snapshot: `<branch>|<committer_epoch>` 한 줄씩.
# load_branches_snapshot() 가 한 번에 잡아 RECENT_ACTIVE + collect_stale 양쪽에 공급.
# (race 가드: 다른 워크트리가 push 하더라도 collector 들이 같은 snapshot 보도록)
BRANCHES_SNAPSHOT=""
load_branches_snapshot() {
    local attempt
    # 최대 3회 재시도 (다른 워크트리 git lockfile 경합 회피)
    for attempt in 1 2 3; do
        BRANCHES_SNAPSHOT="$(git for-each-ref refs/remotes/origin \
            --format='%(refname:short)|%(committerdate:unix)' 2>/dev/null \
            | sed 's|^origin/||')"
        if [[ -n "$BRANCHES_SNAPSHOT" ]]; then
            return 0
        fi
        sleep 1
    done
}

load_recent_active_branches() {
    local cutoff_epoch
    cutoff_epoch=$(( $(now_epoch) - ACTIVE_DAYS * 86400 ))
    [[ -z "$BRANCHES_SNAPSHOT" ]] && load_branches_snapshot
    RECENT_ACTIVE_BRANCHES="$(echo "$BRANCHES_SNAPSHOT" \
        | awk -F'|' -v cutoff="$cutoff_epoch" '$2 > cutoff { print $1 }' \
        | sort -u)"
}

is_recent_active() {
    local branch="$1"
    [[ -z "$RECENT_ACTIVE_BRANCHES" ]] && return 1
    echo "$RECENT_ACTIVE_BRANCHES" | grep -Fxq "$branch"
}

# ---- 분류 ----

# merged-branch: gh API mergedAt 기반
collect_merged_branches() {
    local cutoff_epoch
    cutoff_epoch=$(( $(now_epoch) - MERGED_DAYS * 86400 ))
    gh pr list --state merged --limit "$LIMIT_MERGED" \
        --json number,headRefName,mergedAt \
        --jq '.[] | "\(.mergedAt)\t\(.headRefName)\t\(.number)"' 2>/dev/null \
        | while IFS=$'\t' read -r merged_at branch num; do
            [[ -z "$branch" ]] && continue
            local merged_epoch
            merged_epoch=$(iso_to_epoch "$merged_at")
            if (( merged_epoch > 0 && merged_epoch < cutoff_epoch )); then
                is_protected "$branch" && continue
                is_open_pr_branch "$branch" && continue
                is_recent_active "$branch" && continue
                # 실제 remote 에 존재할 때만 후보 (이미 삭제된 branch skip)
                if git show-ref --quiet "refs/remotes/origin/${branch}"; then
                    echo "merged-${MERGED_DAYS}d+|${branch}|#${num}|${merged_at}"
                fi
            fi
        done
}

# closed-no-merge-branch: PR closed + not merged + closedAt cutoff+
collect_closed_no_merge_branches() {
    local cutoff_epoch
    cutoff_epoch=$(( $(now_epoch) - CLOSED_NO_MERGE_DAYS * 86400 ))
    gh pr list --state closed --limit "$LIMIT_CLOSED" \
        --json number,headRefName,closedAt,mergedAt \
        --jq '.[] | select(.mergedAt==null) | "\(.closedAt)\t\(.headRefName)\t\(.number)"' 2>/dev/null \
        | while IFS=$'\t' read -r closed_at branch num; do
            [[ -z "$branch" ]] && continue
            local closed_epoch
            closed_epoch=$(iso_to_epoch "$closed_at")
            if (( closed_epoch > 0 && closed_epoch < cutoff_epoch )); then
                is_protected "$branch" && continue
                is_open_pr_branch "$branch" && continue
                is_recent_active "$branch" && continue
                if git show-ref --quiet "refs/remotes/origin/${branch}"; then
                    echo "closed-nomerge-${CLOSED_NO_MERGE_DAYS}d+|${branch}|#${num}|${closed_at}"
                fi
            fi
        done
}

# stale-branch: open PR 없음 + last commit STALE_DAYS+
collect_stale_branches() {
    local cutoff_epoch
    cutoff_epoch=$(( $(now_epoch) - STALE_DAYS * 86400 ))
    [[ -z "$BRANCHES_SNAPSHOT" ]] && load_branches_snapshot
    echo "$BRANCHES_SNAPSHOT" \
        | while IFS='|' read -r branch epoch; do
            [[ -z "$branch" ]] && continue
            [[ "$branch" == "HEAD" ]] && continue
            is_protected "$branch" && continue
            is_open_pr_branch "$branch" && continue
            is_recent_active "$branch" && continue
            if (( epoch > 0 && epoch < cutoff_epoch )); then
                local iso
                iso="$(epoch_to_iso "$epoch")"
                echo "stale-${STALE_DAYS}d+|${branch}|-|${iso}"
            fi
        done
}

# agent-worktree: .claude/worktrees/agent-*
collect_worktrees() {
    git worktree list --porcelain 2>/dev/null | awk '
        /^worktree / { path=$2 }
        /^locked/ { is_locked=1 }
        /^$/ {
            if (path && path ~ /\.claude\/worktrees\/agent-/) {
                printf "agent-worktree|%s|-|locked=%d\n", path, (is_locked?1:0)
            }
            path=""; is_locked=0
        }
        END {
            if (path && path ~ /\.claude\/worktrees\/agent-/) {
                printf "agent-worktree|%s|-|locked=%d\n", path, (is_locked?1:0)
            }
        }
    '
}

# closed-issue: 30일+ closed + stale label 미부착
collect_closed_issues() {
    local cutoff_epoch
    cutoff_epoch=$(( $(now_epoch) - ISSUES_DAYS * 86400 ))
    gh issue list --state closed --limit "$LIMIT_ISSUES" \
        --json number,title,closedAt,labels,milestone \
        --jq '.[] | [.closedAt, (.number|tostring), ([.labels[].name] | join(",")), (.milestone.title // "-"), .title] | @tsv' 2>/dev/null \
        | while IFS=$'\t' read -r closed_at num labels milestone title; do
            local closed_epoch
            closed_epoch=$(iso_to_epoch "$closed_at")
            if (( closed_epoch > 0 && closed_epoch < cutoff_epoch )); then
                # 이미 stale 라벨 있거나 archive milestone 부착 시 skip
                if [[ ",${labels}," == *",${ISSUES_STALE_LABEL},"* ]]; then
                    continue
                fi
                if [[ -n "$ARCHIVE_MILESTONE" && "$milestone" == "$ARCHIVE_MILESTONE" ]]; then
                    continue
                fi
                # 안전: title 안 | 치환
                local safe_title="${title//|/_}"
                echo "closed-issue-${ISSUES_DAYS}d+|#${num}|${closed_at}|${safe_title}"
            fi
        done
}

# ---- collect ----
log "sweep started — REPO=${REPO_ROOT} APPLY=${APPLY} MERGED_DAYS=${MERGED_DAYS} CLOSED_NO_MERGE_DAYS=${CLOSED_NO_MERGE_DAYS} STALE_DAYS=${STALE_DAYS} ACTIVE_DAYS=${ACTIVE_DAYS}"

# whitelist 캐시 prepoulate (remote 모드일 때만)
# branches snapshot 을 먼저 잡아 race 가드 — load_recent_active + collect_stale 가 같은 view 사용
if (( MODE_REMOTE == 1 )); then
    log "loading branches snapshot + open PR + recent-active whitelist ..."
    load_branches_snapshot || true
    load_open_pr_branches || true
    load_recent_active_branches || true
fi

CANDIDATES_FILE="$(mktemp)"
ISSUES_FILE="$(mktemp)"
trap 'rm -f "$CANDIDATES_FILE" "$ISSUES_FILE"' EXIT

if (( MODE_MERGED == 1 && MODE_REMOTE == 1 )); then
    log "collecting merged branches (gh API) ..."
    collect_merged_branches >> "$CANDIDATES_FILE" || true
fi

if (( MODE_CLOSED_NO_MERGE == 1 && MODE_REMOTE == 1 )); then
    log "collecting closed-not-merged branches (gh API) ..."
    collect_closed_no_merge_branches >> "$CANDIDATES_FILE" || true
fi

if (( MODE_STALE == 1 && MODE_REMOTE == 1 )); then
    log "collecting stale remote branches ..."
    collect_stale_branches >> "$CANDIDATES_FILE" || true
fi

if (( MODE_WORKTREE == 1 )); then
    log "collecting agent worktrees ..."
    collect_worktrees >> "$CANDIDATES_FILE" || true
fi

if (( MODE_ISSUES == 1 )); then
    log "collecting closed issues (--issues, gh API) ..."
    collect_closed_issues >> "$ISSUES_FILE" || true
fi

TOTAL="$(wc -l < "$CANDIDATES_FILE" | tr -d ' ')"
ISSUES_TOTAL="$(wc -l < "$ISSUES_FILE" | tr -d ' ')"
log "candidates total: ${TOTAL} (issues: ${ISSUES_TOTAL})"

# 카운트
MERGED_CNT="$(grep -c '^merged-' "$CANDIDATES_FILE" 2>/dev/null || true)"
CLOSED_NM_CNT="$(grep -c '^closed-nomerge-' "$CANDIDATES_FILE" 2>/dev/null || true)"
STALE_CNT="$(grep -c '^stale-' "$CANDIDATES_FILE" 2>/dev/null || true)"
WORKTREE_CNT="$(grep -c '^agent-worktree|' "$CANDIDATES_FILE" 2>/dev/null || true)"
ISSUES_CNT="$(grep -c '^closed-issue-' "$ISSUES_FILE" 2>/dev/null || true)"
MERGED_CNT="${MERGED_CNT:-0}"
CLOSED_NM_CNT="${CLOSED_NM_CNT:-0}"
STALE_CNT="${STALE_CNT:-0}"
WORKTREE_CNT="${WORKTREE_CNT:-0}"
ISSUES_CNT="${ISSUES_CNT:-0}"

log "  - merged-${MERGED_DAYS}d+:           ${MERGED_CNT}"
log "  - closed-nomerge-${CLOSED_NO_MERGE_DAYS}d+:  ${CLOSED_NM_CNT}"
log "  - stale-${STALE_DAYS}d+:             ${STALE_CNT}"
log "  - agent-worktree:           ${WORKTREE_CNT}"
log "  - closed-issue-${ISSUES_DAYS}d+:     ${ISSUES_CNT}"

# ---- text report ----
if (( WRITE_REPORT == 1 )); then
    {
        echo "# sweep-report ${TS}"
        echo "# REPO=${REPO_ROOT}"
        echo "# APPLY=${APPLY} MERGED_DAYS=${MERGED_DAYS} CLOSED_NO_MERGE_DAYS=${CLOSED_NO_MERGE_DAYS} STALE_DAYS=${STALE_DAYS} ACTIVE_DAYS=${ACTIVE_DAYS}"
        echo "# 형식 (branches): <category>|<branch_or_path>|<pr_or_dash>|<extra>"
        echo "# 형식 (issues):   <category>|#<num>|<closed_at>|<title>"
        echo ""
        echo "## branches"
        cat "$CANDIDATES_FILE"
        if (( MODE_ISSUES == 1 )); then
            echo ""
            echo "## issues"
            cat "$ISSUES_FILE"
        fi
    } > "$REPORT_FILE"
    log "text report: ${REPORT_FILE}"
fi

# ---- markdown report ----
if (( WRITE_MD_REPORT == 1 )); then
    mkdir -p "$MD_REPORT_DIR"
    {
        echo "# branch cleanup sweep — ${DATE_KEY}"
        echo ""
        echo "- 실행 시각: \`${TS}\`"
        echo "- 모드: APPLY=${APPLY} (0=dry-run, 1=apply)"
        echo "- 기준: merged≥${MERGED_DAYS}d / closed-nomerge≥${CLOSED_NO_MERGE_DAYS}d / stale≥${STALE_DAYS}d / active-protect=${ACTIVE_DAYS}d"
        echo ""
        echo "## 요약"
        echo ""
        echo "| 카테고리 | 후보 수 |"
        echo "|---|---:|"
        echo "| merged-${MERGED_DAYS}d+            | ${MERGED_CNT} |"
        echo "| closed-nomerge-${CLOSED_NO_MERGE_DAYS}d+  | ${CLOSED_NM_CNT} |"
        echo "| stale-${STALE_DAYS}d+              | ${STALE_CNT} |"
        echo "| agent-worktree            | ${WORKTREE_CNT} |"
        if (( MODE_ISSUES == 1 )); then
            echo "| closed-issue-${ISSUES_DAYS}d+         | ${ISSUES_CNT} |"
        fi
        echo "| **TOTAL branches**        | **${TOTAL}** |"
        echo ""
        echo "## branch / worktree 후보"
        echo ""
        if [[ -s "$CANDIDATES_FILE" ]]; then
            echo "| category | branch / path | pr | extra |"
            echo "|---|---|---|---|"
            awk -F'|' '{ printf "| %s | `%s` | %s | %s |\n", $1, $2, $3, $4 }' "$CANDIDATES_FILE" | head -200
            CAND_LINES=$(wc -l < "$CANDIDATES_FILE" | tr -d ' ')
            if (( CAND_LINES > 200 )); then
                echo ""
                echo "_(상위 200건만 표시 — 전체는 \`${REPORT_FILE}\` 참조)_"
            fi
        else
            echo "_후보 없음._"
        fi
        if (( MODE_ISSUES == 1 )); then
            echo ""
            echo "## closed issue 후보"
            echo ""
            if [[ -s "$ISSUES_FILE" ]]; then
                echo "| category | issue | closed_at | title |"
                echo "|---|---|---|---|"
                awk -F'|' '{ printf "| %s | %s | %s | %s |\n", $1, $2, $3, $4 }' "$ISSUES_FILE" | head -200
                ISS_LINES=$(wc -l < "$ISSUES_FILE" | tr -d ' ')
                if (( ISS_LINES > 200 )); then
                    echo ""
                    echo "_(상위 200건만 표시)_"
                fi
            else
                echo "_후보 없음._"
            fi
        fi
        echo ""
        echo "## 권고 행동"
        echo ""
        echo "1. branch 5 카테고리 머지 30일+ + closed-nomerge 30일+ → \`--apply --yes\` 적용 가능."
        echo "2. stale 60일+ branch — 운영자 sample 검토 후 \`--apply\`."
        echo "3. closed issue → \`--issues --apply\` 로 \`${ISSUES_STALE_LABEL}\` 라벨 부착 (revert 가능)."
        if [[ -n "$ARCHIVE_MILESTONE" ]]; then
            echo "4. \`--archive-milestone "${ARCHIVE_MILESTONE}"\` 으로 milestone 묶기 (옵션)."
        fi
        echo ""
        echo "## 안티패턴"
        echo ""
        echo "- \`git branch -D\` / \`git push --force-with-lease\` 직접 호출 금지 — 본 스크립트 통해."
        echo "- \`.claude/worktrees/agent-*\` 도 \`rm -rf\` 직접 X — \`git worktree remove\` 사용."
    } > "$MD_REPORT_FILE"
    log "markdown report: ${MD_REPORT_FILE}"
fi

# ---- dry-run 모드 종료 ----
if (( APPLY == 0 )); then
    log "[DRY-RUN] no deletion. re-run with --apply to delete."
    echo ""
    echo "=== sample (first 10 branch candidates) ==="
    head -10 "$CANDIDATES_FILE" 2>/dev/null || true
    if (( MODE_ISSUES == 1 )); then
        echo ""
        echo "=== sample (first 10 issue candidates) ==="
        head -10 "$ISSUES_FILE" 2>/dev/null || true
    fi
    exit 0
fi

# ---- apply 모드 ----
log "[APPLY] mode — about to process ${TOTAL} branch + ${ISSUES_CNT} issue items."

if (( YES == 0 )); then
    echo ""
    echo "=== first 5 branch candidates ==="
    head -5 "$CANDIDATES_FILE" 2>/dev/null || true
    if (( MODE_ISSUES == 1 )); then
        echo ""
        echo "=== first 5 issue candidates ==="
        head -5 "$ISSUES_FILE" 2>/dev/null || true
    fi
    echo ""
    read -r -p "proceed? [y/N] " confirm
    [[ "$confirm" =~ ^[Yy]$ ]] || { log "aborted by user"; exit 0; }
fi

DELETED=0
FAILED=0
ISSUE_UPDATED=0
ISSUE_FAILED=0

# branch + worktree apply
while IFS='|' read -r category target extra1 extra2; do
    [[ -z "$category" ]] && continue
    case "$category" in
        merged-*|closed-nomerge-*|stale-*)
            if (( MODE_REMOTE == 1 )); then
                if git push origin --delete "$target" 2>/dev/null; then
                    ((DELETED += 1))
                    log "deleted remote: $target ($category)"
                else
                    ((FAILED += 1))
                    log "  fail (already gone?): origin/$target"
                fi
            fi
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

# issue apply (label + 옵션 milestone)
if (( MODE_ISSUES == 1 )); then
    # stale label 보유 확인 (없으면 생성)
    if ! gh label list --limit 200 --json name --jq '.[].name' 2>/dev/null | grep -Fxq "$ISSUES_STALE_LABEL"; then
        log "creating label '${ISSUES_STALE_LABEL}' (no-op if exists)"
        gh label create "$ISSUES_STALE_LABEL" \
            --description "closed ${ISSUES_DAYS}d+ — auto-tagged by branch-cleanup sweep" \
            --color "cccccc" 2>/dev/null || true
    fi

    while IFS='|' read -r category issue_id closed_at title; do
        [[ -z "$category" ]] && continue
        ISSUE_NUM="${issue_id#\#}"
        if gh issue edit "$ISSUE_NUM" --add-label "$ISSUES_STALE_LABEL" 2>/dev/null; then
            ((ISSUE_UPDATED += 1))
            log "tagged issue: $issue_id ($category)"
        else
            ((ISSUE_FAILED += 1))
            log "  fail: $issue_id (label apply)"
            continue
        fi
        if [[ -n "$ARCHIVE_MILESTONE" ]]; then
            if gh issue edit "$ISSUE_NUM" --milestone "$ARCHIVE_MILESTONE" 2>/dev/null; then
                log "  + milestone: $ARCHIVE_MILESTONE"
            else
                log "  warn: milestone apply failed — milestone exists?"
            fi
        fi
    done < "$ISSUES_FILE"
fi

# prune
if (( MODE_WORKTREE == 1 )); then
    git worktree prune -v 2>&1 | sed 's/^/  prune: /' || true
fi
if (( MODE_REMOTE == 1 )); then
    git remote prune origin 2>&1 | sed 's/^/  remote-prune: /' || true
fi

log "summary: branches deleted=${DELETED} failed=${FAILED} | issues updated=${ISSUE_UPDATED} failed=${ISSUE_FAILED}"
