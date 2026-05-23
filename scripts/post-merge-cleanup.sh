#!/usr/bin/env bash
#
# post-merge-cleanup.sh
#
# 머지된 PR 정리 후 모든 워크트리를 origin/develop 최신 시점에 detach 시키고
# maestro(`mobruji`)에서 머지된 로컬 branch들을 일괄 삭제한다.
#
# 사용법:
#   ./scripts/post-merge-cleanup.sh [--force]
#
# 옵션:
#   --force  dirty 워크트리도 강제 reset (작업 분실 위험)
#            기본은 dirty면 skip + 경고. force는 git reset --hard + clean -fd로
#            unstaged/untracked 파일을 모두 제거한다. maestro이 명시적 결정 후에만 사용.
#
# 동작:
#   1. maestro 워크트리(`mobruji`)에서 `git fetch origin develop`
#   2. be/fe/rev/plan 워크트리 순회:
#      - dirty 검사 (unstaged/staged/untracked)
#      - 기본: dirty면 skip + 경고
#      - --force: reset --hard origin/develop + clean -fd
#      - clean이면 그대로 detach
#   3. maestro에서 `origin/develop`에 머지된 로컬 branch 삭제
#
# 가정:
#   - maestro 워크트리 경로: 자동 감지 (스크립트 위치 기준 `git rev-parse --show-toplevel`)
#     실패 시 fallback: $HOME/mobruji
#   - 서브 워크트리 경로: <maestro 부모>/mobruji-{be,fe,rev,plan}
#   - 환경변수 `MOBRUJI_ROOT` 로 maestro 경로 override 가능
#   - 이 스크립트는 maestro에서 실행 (다른 워크트리에서 실행해도 git worktree 정보는 동일)
#
# 호환성 매트릭스:
#   - mac 본진 ($HOME/workspace/github/mobruji)        → 자동 감지 OK
#   - NCP nmae (/home/mobruji/mobruji)                 → 자동 감지 OK
#   - MOBRUJI_ROOT=/custom/path 으로 override          → env 우선

set -euo pipefail

# maestro(`mobruji`) 워크트리 경로 자동 감지.
# 스크립트가 워크트리 안에 있는 경우 `git rev-parse --show-toplevel`로 root 추출.
# 실패 시 $HOME/mobruji 로 fallback. MOBRUJI_ROOT env로 override 가능.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEFAULT_ROOT="$(git -C "${SCRIPT_DIR}/.." rev-parse --show-toplevel 2>/dev/null || echo "${HOME}/mobruji")"
ROOT_DIR="${MOBRUJI_ROOT:-${DEFAULT_ROOT}}"
PARENT_DIR="$(dirname "${ROOT_DIR}")"
SUB_WORKTREES=(
  "${PARENT_DIR}/mobruji-be"
  "${PARENT_DIR}/mobruji-fe"
  "${PARENT_DIR}/mobruji-rev"
  "${PARENT_DIR}/mobruji-plan"
)

FORCE=0
for arg in "$@"; do
  case "${arg}" in
    --force)
      FORCE=1
      ;;
    -h|--help)
      sed -n '2,25p' "$0"
      exit 0
      ;;
    *)
      printf 'Unknown option: %s\n' "${arg}" >&2
      printf 'Usage: post-merge-cleanup.sh [--force]\n' >&2
      exit 2
      ;;
  esac
done

log() {
  printf '[post-merge-cleanup] %s\n' "$*"
}

warn() {
  printf '[post-merge-cleanup] WARN: %s\n' "$*" >&2
}

# 0) maestro 존재 확인
if [[ ! -d "${ROOT_DIR}/.git" && ! -f "${ROOT_DIR}/.git" ]]; then
  warn "maestro 워크트리(${ROOT_DIR})가 git 워크트리가 아니다. 중단."
  exit 1
fi

# 1) maestro에서 origin/develop fetch
log "maestro(${ROOT_DIR})에서 origin/develop fetch"
git -C "${ROOT_DIR}" fetch origin develop --prune

if (( FORCE )); then
  warn "--force 활성: dirty 워크트리도 강제 reset 한다. 작업 분실 위험."
fi

# 2) 서브 워크트리 순회
for wt in "${SUB_WORKTREES[@]}"; do
  if [[ ! -d "${wt}" ]]; then
    log "skip: ${wt} 없음"
    continue
  fi

  # dirty 상태 종합 판정
  has_unstaged=0
  has_staged=0
  has_untracked=0
  if ! git -C "${wt}" diff --quiet; then
    has_unstaged=1
  fi
  if ! git -C "${wt}" diff --cached --quiet; then
    has_staged=1
  fi
  if [[ -n "$(git -C "${wt}" ls-files --others --exclude-standard)" ]]; then
    has_untracked=1
  fi

  is_dirty=$(( has_unstaged || has_staged || has_untracked ))

  if (( is_dirty )); then
    log "${wt}: dirty 상태 (unstaged=${has_unstaged} staged=${has_staged} untracked=${has_untracked})"
    git -C "${wt}" status --short || true

    if (( FORCE )); then
      warn "${wt}: --force로 강제 reset 진행 (변경 영구 손실)"
      git -C "${wt}" fetch origin develop --prune
      git -C "${wt}" reset --hard origin/develop
      git -C "${wt}" clean -fd
      # reset 후에도 detached 상태 확정
      git -C "${wt}" checkout --detach origin/develop
      log "${wt}: forced reset + detach 완료"
    else
      warn "${wt}: dirty라 detach skip. --force로 재실행하거나 사람이 정리 필요."
    fi
    continue
  fi

  log "${wt}: origin/develop으로 detach"
  git -C "${wt}" fetch origin develop --prune
  git -C "${wt}" checkout --detach origin/develop
done

# 3) maestro에서 머지된 로컬 branch 삭제 (develop 제외)
log "maestro에서 origin/develop에 머지된 로컬 branch 정리"
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
