#!/usr/bin/env bash
#
# post-merge-cleanup.sh
#
# 머지된 PR 정리 후 모든 워크트리를 origin/develop 최신 시점에 detach 시키고
# 본진(`mobruji`)에서 머지된 로컬 branch들을 일괄 삭제한다.
#
# 사용법:
#   ./scripts/post-merge-cleanup.sh
#
# 동작:
#   1. 본진 워크트리(`mobruji`)에서 `git fetch origin develop`
#   2. be/fe/rev/plan 워크트리 순회:
#      - 작업 브랜치에 있으면 detach (origin/develop 기준)
#      - 변경사항이 있으면 경고 후 skip
#   3. 본진에서 `origin/develop`에 머지된 로컬 branch 삭제
#
# 가정:
#   - 본진 워크트리 경로: $HOME/workspace/github/mobruji
#   - 서브 워크트리 경로: $HOME/workspace/github/mobruji-{be,fe,rev,plan}
#   - 이 스크립트는 본진에서 실행 (다른 워크트리에서 실행해도 git worktree 정보는 동일)

set -euo pipefail

ROOT_DIR="${HOME}/workspace/github/mobruji"
SUB_WORKTREES=(
  "${HOME}/workspace/github/mobruji-be"
  "${HOME}/workspace/github/mobruji-fe"
  "${HOME}/workspace/github/mobruji-rev"
  "${HOME}/workspace/github/mobruji-plan"
)

log() {
  printf '[post-merge-cleanup] %s\n' "$*"
}

warn() {
  printf '[post-merge-cleanup] WARN: %s\n' "$*" >&2
}

# 0) 본진 존재 확인
if [[ ! -d "${ROOT_DIR}/.git" && ! -f "${ROOT_DIR}/.git" ]]; then
  warn "본진 워크트리(${ROOT_DIR})가 git 워크트리가 아니다. 중단."
  exit 1
fi

# 1) 본진에서 origin/develop fetch
log "본진(${ROOT_DIR})에서 origin/develop fetch"
git -C "${ROOT_DIR}" fetch origin develop --prune

# 2) 서브 워크트리 순회
for wt in "${SUB_WORKTREES[@]}"; do
  if [[ ! -d "${wt}" ]]; then
    log "skip: ${wt} 없음"
    continue
  fi

  # working tree dirty 체크
  if ! git -C "${wt}" diff --quiet || ! git -C "${wt}" diff --cached --quiet; then
    warn "${wt}: working tree dirty. detach skip. 사람이 정리 필요."
    continue
  fi

  # untracked 파일은 경고만
  if [[ -n "$(git -C "${wt}" ls-files --others --exclude-standard)" ]]; then
    warn "${wt}: untracked 파일 있음. detach는 진행하지만 사용자 확인 권장."
  fi

  log "${wt}: origin/develop으로 detach"
  git -C "${wt}" fetch origin develop --prune
  git -C "${wt}" checkout --detach origin/develop
done

# 3) 본진에서 머지된 로컬 branch 삭제 (develop 제외)
log "본진에서 origin/develop에 머지된 로컬 branch 정리"
merged_branches=$(
  git -C "${ROOT_DIR}" branch --merged origin/develop \
    | sed 's/^[* ] //' \
    | grep -vE '^(develop|main)$' \
    || true
)

if [[ -z "${merged_branches}" ]]; then
  log "삭제할 머지 branch 없음"
else
  printf '%s\n' "${merged_branches}" | while read -r branch; do
    [[ -z "${branch}" ]] && continue
    # 다른 워크트리에서 사용 중인 branch는 -d가 거부함 (안전)
    if git -C "${ROOT_DIR}" branch -d "${branch}" 2>/dev/null; then
      log "  deleted: ${branch}"
    else
      warn "  skip (워크트리 사용 중 또는 unmerged): ${branch}"
    fi
  done
fi

log "완료."
