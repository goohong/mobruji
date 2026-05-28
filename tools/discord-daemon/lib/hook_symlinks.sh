#!/usr/bin/env bash
# hook_symlinks.sh — discord-daemon hook symlink ensure 공통 라이브러리.
#
# 배경 (PR #1124 / #1158 후속, PR #1154 와 짝):
#   PR #1154 머지 후 helper-direct-work-guard.sh 가 repo 안 파일로 추가됐다.
#   기존엔 nmae 가 수동으로 ~/.mobruji/<hook>.sh 를 backup 후 symlink 로
#   전환해야 했다. 본 라이브러리는 ensure_hook_symlink + ensure_hook_symlinks
#   함수를 export 한다.
#
#   - deploy.sh    — 매 deploy 마다 idempotent 동기화.
#   - setup-*.sh   — initial install 시점에 동일 hook 보장.
#
# 사용 예 (consumer script 측):
#     LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/lib" && pwd)"
#     # shellcheck disable=SC1091
#     source "$LIB_DIR/hook_symlinks.sh"
#     ensure_hook_symlinks
#
# 환경 변수:
#   MOBRUJI_HOOK_LINK_DIR   — symlink target dir (default /home/mobruji/.mobruji)
#   MOBRUJI_HOOK_SOURCE_DIR — repo 내 hook source dir
#                             (default /home/mobruji/mobruji/tools/discord-daemon)
#   MOBRUJI_HOOK_SYMLINK_ENSURE — 1 (default) / 0 (skip 전체)
#   MOBRUJI_HOOK_NAMES      — space-separated. 미설정 시 default 4종.
#
# 외부 의존:
#   - 호출 측이 `log()` `run()` 헬퍼를 정의해 두어야 함.
#     log() — stderr/stdout 로깅. run() — DRY_RUN 시 echo, 아니면 eval.
#   - DRY_RUN — 1 / 0. 미설정 시 0 으로 간주.
#
# 함수:
#   ensure_hook_symlink  — 단일 hook 처리 (인자 1개).
#   ensure_hook_symlinks — HOOK_NAMES 배열 전체 처리.

# default 값 (consumer 가 override 가능).
: "${HOOK_LINK_DIR:=${MOBRUJI_HOOK_LINK_DIR:-/home/mobruji/.mobruji}}"
: "${HOOK_SOURCE_DIR:=${MOBRUJI_HOOK_SOURCE_DIR:-/home/mobruji/mobruji/tools/discord-daemon}}"
: "${HOOK_SYMLINK_ENSURE:=${MOBRUJI_HOOK_SYMLINK_ENSURE:-1}}"

# HOOK_NAMES default — env 우선, 미설정 시 4종 default.
if [[ -z "${HOOK_NAMES:-}" ]]; then
  if [[ -n "${MOBRUJI_HOOK_NAMES:-}" ]]; then
    # shellcheck disable=SC2206
    HOOK_NAMES=(${MOBRUJI_HOOK_NAMES})
  else
    HOOK_NAMES=(
      helper-direct-work-guard.sh
      helper-turn-start.sh
      discord-reply.sh
      nmae-discord-push.sh
      # PR E-2 (2026-05-28): helper launch system prompt SoT sync 강제.
      # repo 의 helper-role.md = relay-only strict 룰 (코드 변경/분배 결정 금지).
      # 기존엔 NCP runtime 의 옛 system prompt (덜 strict) 가 ~/.mobruji/helper-role.md
      # 에 남아 있어 helper 가 분배 결정 (17:28 사고). symlink 강제로 repo 변경 즉시
      # 반영 — 학습 의존 ↓, 코드 강제. spec: actors/sub-agent.md §2-helper / nmae §11-7.
      helper-launch.sh
      helper-role.md
    )
  fi
fi

# log/run fallback — consumer 미정의 시 안전 default.
if ! declare -F log >/dev/null 2>&1; then
  log() {
    printf '[hook_symlinks] %s\n' "$*"
  }
fi
if ! declare -F run >/dev/null 2>&1; then
  run() {
    if [[ "${DRY_RUN:-0}" == "1" ]]; then
      printf '[dry-run] %s\n' "$*"
    else
      eval "$@"
    fi
  }
fi

# hook 단일 파일 symlink ensure.
#
# 인자: $1 = hook 파일명 (예: helper-direct-work-guard.sh)
#
# 동작:
#   - source 파일 (HOOK_SOURCE_DIR/<name>) 부재 시 skip + log (PR 머지 전 호환).
#   - link 위치 (HOOK_LINK_DIR/<name>) 가 이미 source 와 동일한 symlink → skip.
#   - link 위치가 regular file → 같은 dir 에 backup (`.bak.<TS>`) 후 symlink 생성.
#   - link 위치가 다른 target 의 symlink 또는 broken → unlink 후 symlink 재생성.
#   - link 위치 부재 → symlink 신규 생성.
#
# DRY_RUN=1 시 실제 명령 echo 만.
ensure_hook_symlink() {
  local hook_name=$1
  local source_path="$HOOK_SOURCE_DIR/$hook_name"
  local link_path="$HOOK_LINK_DIR/$hook_name"

  if [[ ! -f "$source_path" ]]; then
    log "  $hook_name: source 부재 ($source_path) — skip."
    return 0
  fi

  if [[ -L "$link_path" ]]; then
    local current_target
    current_target=$(readlink "$link_path")
    if [[ "$current_target" == "$source_path" ]]; then
      if [[ -e "$link_path" ]]; then
        log "  $hook_name: 이미 올바른 symlink — skip."
        return 0
      else
        log "  $hook_name: symlink 깨짐 ($current_target) — 재생성."
      fi
    else
      log "  $hook_name: 다른 target ($current_target) — 재생성."
    fi
    run "rm -f \"$link_path\""
    run "ln -s \"$source_path\" \"$link_path\""
    return 0
  fi

  if [[ -e "$link_path" ]]; then
    local backup_path
    backup_path="${link_path}.bak.$(date +%Y%m%d%H%M%S)"
    log "  $hook_name: regular file 감지 — $backup_path 로 backup 후 symlink 전환."
    run "mv \"$link_path\" \"$backup_path\""
    run "ln -s \"$source_path\" \"$link_path\""
    return 0
  fi

  log "  $hook_name: link 부재 — symlink 신규 생성."
  run "ln -s \"$source_path\" \"$link_path\""
}

# HOOK_NAMES 전체 처리. 환경 가드 (HOOK_SYMLINK_ENSURE / HOOK_LINK_DIR) 포함.
ensure_hook_symlinks() {
  if [[ "$HOOK_SYMLINK_ENSURE" != "1" ]]; then
    log "HOOK_SYMLINK_ENSURE=0 — hook symlink ensure skip."
    return 0
  fi

  if [[ ! -d "$HOOK_LINK_DIR" ]]; then
    log "HOOK_LINK_DIR=$HOOK_LINK_DIR 부재 — hook symlink ensure skip (사용자 환경 기본 dir 없음)."
    return 0
  fi

  log "hook symlink ensure (HOOK_SOURCE_DIR=$HOOK_SOURCE_DIR, HOOK_LINK_DIR=$HOOK_LINK_DIR)"
  for hook_name in "${HOOK_NAMES[@]}"; do
    ensure_hook_symlink "$hook_name"
  done
}
