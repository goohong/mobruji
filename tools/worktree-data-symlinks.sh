#!/usr/bin/env bash
# worktree-data-symlinks.sh — 워크트리의 무거운 재생성 가능 디렉토리
# (web/node_modules, web/.next, backend/build) 를 /data 로 심볼릭 보장.
#
# 배경 (이슈 #1770):
#   root(/) 9.8G 는 좁고 /data 20G 는 여유. 워크트리마다 node_modules(700M+) +
#   .next/build 가 root 에 쌓이면 root 폭주(94%). 표준 워크트리는 수동으로 /data
#   심볼릭 전환해 왔으나 ephemeral/신규 워크트리 생성 시 누락돼 드리프트가 발생했다
#   (예: ephemeral 워크트리는 심볼릭 전무, mobruji-be/build 는 실 디렉토리). 본
#   스크립트는 워크트리 생성/사용 시점에 멱등 보장해 **매뉴얼 재이전을 제거**한다.
#   root cron 아님 — 워크트리 setup hook 으로 호출(subagent_runner ephemeral 경로).
#
# 멱등 + 디스크 안전:
#   - 이미 올바른 심볼릭 → skip.
#   - 실 디렉토리 존재 → /data 타깃으로 이동(타깃 없을 때) 후 심볼릭. 타깃이 이미
#     있으면 실 디렉토리는 재생성 가능 산출물이라 제거 후 심볼릭(재설치/재빌드로 복원).
#   - 깨진/다른 타깃 심볼릭 → 재생성.
#   - /data 사용률 >= DATA_FULL_PCT 면 이동 보류(skip) — 데이터 이동 중 손실 방지.
#
# 사용:
#   worktree-data-symlinks.sh <worktree_path> [--base <name>] [--dry-run]
#
# 환경 변수:
#   MOBRUJI_DATA_ROOT — 심볼릭 타깃 루트 (default /data)
#   DATA_FULL_PCT     — /data 이 이 % 이상이면 실 디렉토리 이동 보류 (default 92)
#
# 종료코드: 0 = 성공(모든 매핑 처리 또는 graceful skip). 1 = 인자/경로 오류.

set -uo pipefail

DATA_ROOT="${MOBRUJI_DATA_ROOT:-/data}"
DATA_FULL_PCT="${DATA_FULL_PCT:-92}"
DRY_RUN=0
BASE=""
WORKTREE=""

# rel_path:data_subdir — 워크트리 상대경로 → /data 하위 디렉토리.
MAPPINGS=(
  "web/node_modules:nm"
  "web/.next:next"
  "backend/build:gradlebuild"
)

log() { printf '[data-symlink] %s\n' "$*"; }

run() {
  if [[ "$DRY_RUN" == "1" ]]; then
    printf '[dry-run] %s\n' "$*"
  else
    eval "$@"
  fi
}

# /data 사용률(정수%). 조회 실패 시 0 (가드 미발동 — 안전 fallback).
_data_pct() {
  local pct
  pct=$(df --output=pcent "$DATA_ROOT" 2>/dev/null | tail -1 | tr -dc '0-9')
  [[ -n "$pct" ]] && printf '%s' "$pct" || printf '0'
}

# 단일 매핑 심볼릭 보장.
#   $1 = 워크트리 상대경로 (예: web/node_modules)
#   $2 = /data 하위 디렉토리 (예: nm)
ensure_one() {
  local rel="$1" sub="$2"
  local link_path="$WORKTREE/$rel"
  local target="$DATA_ROOT/$sub/$BASE"
  local link_parent
  link_parent="$(dirname "$link_path")"

  # 워크트리에 해당 부모(web/ 또는 backend/)가 없으면 skip — 무관한 워크트리.
  if [[ ! -d "$link_parent" ]]; then
    log "  $rel: 부모 디렉토리 부재 — skip."
    return 0
  fi

  # 이미 올바른 심볼릭이면 skip (멱등).
  if [[ -L "$link_path" ]]; then
    local cur
    cur="$(readlink "$link_path")"
    if [[ "$cur" == "$target" && -e "$link_path" ]]; then
      log "  $rel: 이미 올바른 심볼릭 → $target — skip."
      return 0
    fi
    log "  $rel: 잘못된/깨진 심볼릭 ($cur) — 재생성."
    run "mkdir -p \"$target\""
    run "rm -f \"$link_path\""
    run "ln -s \"$target\" \"$link_path\""
    return 0
  fi

  # 실 디렉토리 — /data 로 이동(타깃 없을 때) 또는 제거(타깃 있을 때) 후 심볼릭.
  if [[ -d "$link_path" ]]; then
    if [[ ! -e "$target" ]]; then
      local pct
      pct="$(_data_pct)"
      if [[ "$pct" -ge "$DATA_FULL_PCT" ]]; then
        log "  $rel: /data ${pct}% (>= ${DATA_FULL_PCT}%) — 이동 보류(skip), 실 디렉토리 유지."
        return 0
      fi
      log "  $rel: 실 디렉토리 → $target 이동 후 심볼릭."
      run "mkdir -p \"$(dirname "$target")\""
      run "mv \"$link_path\" \"$target\""
    else
      log "  $rel: 실 디렉토리 + 타깃 존재 → 재생성 가능 산출물이라 제거 후 심볼릭."
      run "rm -rf \"$link_path\""
    fi
    run "ln -s \"$target\" \"$link_path\""
    return 0
  fi

  # 예기치 않은 일반 파일 — 건드리지 않고 경고.
  if [[ -e "$link_path" ]]; then
    log "  $rel: 일반 파일 감지 — 심볼릭 전환 보류(skip)."
    return 0
  fi

  # 부재 → 타깃 생성 + 심볼릭 (npm ci / gradle 가 채움).
  log "  $rel: 부재 → 타깃 생성 + 심볼릭 신규."
  run "mkdir -p \"$target\""
  run "ln -s \"$target\" \"$link_path\""
}

# ─── arg parse ───────────────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
  case "$1" in
    --base) BASE="$2"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    -*) log "알 수 없는 옵션: $1"; exit 1 ;;
    *) WORKTREE="$1"; shift ;;
  esac
done

if [[ -z "$WORKTREE" ]]; then
  log "사용법: worktree-data-symlinks.sh <worktree_path> [--base <name>] [--dry-run]"
  exit 1
fi
if [[ ! -d "$WORKTREE" ]]; then
  log "워크트리 경로 부재: $WORKTREE"
  exit 1
fi

WORKTREE="$(cd "$WORKTREE" && pwd)"
[[ -z "$BASE" ]] && BASE="$(basename "$WORKTREE")"

log "워크트리=$WORKTREE base=$BASE DATA_ROOT=$DATA_ROOT (dry-run=$DRY_RUN)"
for mapping in "${MAPPINGS[@]}"; do
  ensure_one "${mapping%%:*}" "${mapping##*:}"
done
exit 0
