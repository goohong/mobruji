#!/usr/bin/env bash
# worktree-data-sweep.sh — 모든 git 워크트리의 무거운 재생성 가능 디렉토리를
# /data 로 심볼릭 보장하는 일괄 sweep. (이슈 #1785)
#
# 배경 (#1785, #1770 후속):
#   #1770 의 worktree-data-symlinks.sh 는 subagent_runner 의 ephemeral 워크트리
#   setup hook 에서만 호출된다. 그러나 하네스가 직접 만드는 nested 워크트리
#   (`<base>/.claude/worktrees/<name>`) 와 standing 체크아웃은 그 hook 을 못 거쳐
#   node_modules/.next/build 가 root(/) 에 실 디렉토리로 누적된다. 실측: nested
#   워크트리 1개의 node_modules 가 723M 를 root 에 점유 → root 90%+ → 디스크 가드가
#   신규 launch 를 영구 보류 → dispatch stall.
#
#   본 sweep 은 단일 `git worktree list` (모든 워크트리가 공통 git dir 공유) 로
#   전체 워크트리를 열거해 각각 worktree-data-symlinks.sh 를 멱등 적용한다. 누가
#   워크트리를 만들었든(하네스/subagent_runner/수동) 상관없이 드리프트를 닫는다.
#
# 라이브 프로세스 안전 (idle 시점에만 이동):
#   워크트리의 매핑 대상 중 하나라도 실 디렉토리이고 그 디렉토리 mtime 이 IDLE_MIN
#   분 이내(= 진행 중 install/build 추정)면 그 워크트리는 이번 pass skip. 이미
#   심볼릭인 대상은 mtime 무관(이동 불필요). 활성 워크트리의 산출물을 이동 중
#   깨뜨리는 것을 막는다 — 다음 sweep 에서 idle 일 때 처리된다.
#
# 사용:
#   worktree-data-sweep.sh [--dry-run] [--anchor <worktree_path>]
#
# 환경 변수:
#   MOBRUJI_SYMLINK_SCRIPT — 위임할 심볼릭 스크립트 (default: 동일 디렉토리 스크립트)
#   MOBRUJI_SWEEP_IDLE_MIN — 이 분 이내 수정된 실 디렉토리 보유 워크트리 skip (default 10)
#   MOBRUJI_SWEEP_ANCHOR   — worktree list 기준 워크트리 (default: 이 스크립트의 repo)
#
# 종료코드: 0 = sweep 완료(개별 워크트리 graceful skip 포함). 1 = 인자/환경 오류.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SYMLINK_SCRIPT="${MOBRUJI_SYMLINK_SCRIPT:-$SCRIPT_DIR/worktree-data-symlinks.sh}"
IDLE_MIN="${MOBRUJI_SWEEP_IDLE_MIN:-10}"
ANCHOR="${MOBRUJI_SWEEP_ANCHOR:-$SCRIPT_DIR}"
DRY_RUN=0

# rel_path — 워크트리 상대경로(심볼릭 스크립트 MAPPINGS 와 동일 집합). idle 판정용.
HEAVY_RELS=("web/node_modules" "web/.next" "backend/build")

log() { printf '[data-sweep] %s\n' "$*"; }

# ─── arg parse ───────────────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) DRY_RUN=1; shift ;;
    --anchor) ANCHOR="$2"; shift 2 ;;
    -*) log "알 수 없는 옵션: $1"; exit 1 ;;
    *) log "예기치 않은 인자: $1"; exit 1 ;;
  esac
done

if [[ ! -x "$SYMLINK_SCRIPT" && ! -f "$SYMLINK_SCRIPT" ]]; then
  log "심볼릭 스크립트 부재: $SYMLINK_SCRIPT"
  exit 1
fi
if ! git -C "$ANCHOR" rev-parse --git-dir >/dev/null 2>&1; then
  log "anchor 가 git 워크트리가 아님: $ANCHOR"
  exit 1
fi

# 활성(진행 중 install/build) 추정 시 true — 매핑 실 디렉토리 mtime 이 IDLE_MIN 분 이내.
_is_busy() {
  local wt="$1" rel dir
  for rel in "${HEAVY_RELS[@]}"; do
    dir="$wt/$rel"
    # 심볼릭/부재는 이동 대상 아님 — 무관.
    [[ -L "$dir" || ! -d "$dir" ]] && continue
    if [[ -n "$(find "$dir" -maxdepth 0 -mmin "-${IDLE_MIN}" 2>/dev/null)" ]]; then
      return 0
    fi
  done
  return 1
}

# ─── sweep ───────────────────────────────────────────────────────────────────
mapfile -t WORKTREES < <(
  git -C "$ANCHOR" worktree list --porcelain 2>/dev/null \
    | awk '/^worktree /{print $2}'
)

if [[ ${#WORKTREES[@]} -eq 0 ]]; then
  log "워크트리 없음 (anchor=$ANCHOR) — no-op."
  exit 0
fi

log "워크트리 ${#WORKTREES[@]}개 sweep (idle_min=${IDLE_MIN}, dry-run=${DRY_RUN})"
swept=0; skipped=0
for wt in "${WORKTREES[@]}"; do
  [[ -d "$wt" ]] || { log "  $wt: 경로 부재 — skip."; continue; }
  if _is_busy "$wt"; then
    log "  $wt: 활성(< ${IDLE_MIN}분 수정) — 이번 pass skip."
    skipped=$((skipped + 1))
    continue
  fi
  args=("$wt")
  [[ "$DRY_RUN" == "1" ]] && args+=("--dry-run")
  if bash "$SYMLINK_SCRIPT" "${args[@]}"; then
    swept=$((swept + 1))
  else
    log "  $wt: 심볼릭 스크립트 비정상 종료 — 계속."
  fi
done

log "완료: sweep ${swept} / skip ${skipped} / 전체 ${#WORKTREES[@]}."
exit 0
