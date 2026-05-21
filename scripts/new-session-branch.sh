#!/usr/bin/env bash
# 세션이 새 작업을 시작할 때 한 줄로 모든 셋업 처리.
# 사용법: ./scripts/new-session-branch.sh <session> <type> <scope> <slug> [issue_title]
#   session: be | fe | rev
#   type:    feat | fix | refactor | chore | docs | test | style
#   scope:   user | song | recommendation | voice | infra | web
#   slug:    <kebab-case-summary>
#   issue_title (optional): 이슈 제목. 미입력 시 slug 사용.
#
# 예시:
#   ./scripts/new-session-branch.sh be feat voice voice-range-validation "음역대 입력 검증 강화"
#   ./scripts/new-session-branch.sh fe feat web voice-range-input-page

set -euo pipefail

if [[ $# -lt 4 ]]; then
    echo "Usage: $0 <session> <type> <scope> <slug> [issue_title]" >&2
    echo "  session: be | fe | rev" >&2
    echo "  type:    feat | fix | refactor | chore | docs | test | style" >&2
    echo "  scope:   user | song | recommendation | voice | infra | web" >&2
    exit 1
fi

session="$1"
type="$2"
scope="$3"
slug="$4"
issue_title="${5:-$slug}"

case "$session" in
    be) session_label="session:backend" ;;
    fe) session_label="session:frontend" ;;
    rev)
        echo "[ERROR] rev 세션은 브랜치를 만들지 않는다. PR 코멘트만 한다." >&2
        exit 1
        ;;
    *)
        echo "[ERROR] unknown session: $session (be|fe)" >&2
        exit 1
        ;;
esac

# 0) develop 동기화 (detached worktree 친화: origin/develop로 직접 작업)
echo "[1/5] sync develop"
git fetch origin develop

# 1) 이슈 생성
echo "[2/5] create issue"
issue_url=$(gh issue create \
    --title "$issue_title" \
    --label "task,type:$type,scope:$scope,ai-generated,ai:claude,$session_label" \
    --body "## Description
세션 \`$session\`이 시작한 작업. 자세한 사항은 PR에서.

생성: \`./scripts/new-session-branch.sh $session $type $scope $slug\`")
issue_num=$(echo "$issue_url" | grep -oE '[0-9]+$')
echo "  → $issue_url"

# 2) 브랜치 분기
branch_type="$type"
case "$type" in
    feat) branch_type="feature" ;;
esac
branch="${branch_type}/${slug}-#${issue_num}"
echo "[3/5] branch $branch (from origin/develop)"
# -B로 강제 reset, origin/develop를 기준점으로. detached/branch 상태 무관하게 동작.
git checkout -B "$branch" origin/develop

# 3) 빈 commit으로 PR 생성을 위한 first push (draft PR은 commit 1개 필요)
echo "[4/5] empty placeholder commit + push"
git commit --allow-empty -m "chore: scaffold for #$issue_num"
git push -u origin "$branch"

# 4) Draft PR 생성
echo "[5/5] create draft PR"
pr_url=$(gh pr create \
    --base develop \
    --draft \
    --title "$type($scope): $issue_title" \
    --body "## AS-IS
(작성 중)

## TO-BE
(작성 중)

## 관련 이슈
- closes #$issue_num

세션 \`$session\` 진행 중. 작업 끝나면 \`gh pr ready\`로 review-ready 전환.")
pr_num=$(echo "$pr_url" | grep -oE '[0-9]+$')

# 라벨은 auto-label.yml이 자동 부여하지만, session 라벨은 제목에서 못 잡으므로 명시
gh pr edit "$pr_num" --add-label "$session_label,ai-generated,ai:claude" >/dev/null

echo ""
echo "✓ 셋업 완료"
echo "  Issue: $issue_url"
echo "  PR:    $pr_url"
echo ""
echo "다음 단계:"
echo "  1. 코드 작성/커밋"
echo "  2. ./gradlew checkstyleMain spotlessCheck test (backend)"
echo "  3. git push"
echo "  4. gh pr ready $pr_num    # draft → ready for review"
