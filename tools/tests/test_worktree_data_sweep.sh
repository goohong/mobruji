#!/usr/bin/env bash
# test_worktree_data_sweep.sh — worktree-data-sweep.sh e2e 테스트 (이슈 #1785).
#
# 검증 시나리오 (모두 isolated tmpdir — 실제 /data·실 repo 안 건드림):
#   1. 다중 워크트리 sweep → 각 워크트리 실 node_modules 가 /data 심볼릭으로 전환.
#   2. idle 가드: 방금 수정된(< IDLE_MIN) 실 디렉토리 보유 워크트리는 skip(실 유지).
#   3. --dry-run → 실제 변경 없음.
#   4. 이미 심볼릭 → 멱등(에러 없이 유지).
#
# 종료코드: 모든 케이스 pass → 0. 임의 fail → 1.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SWEEP="$SCRIPT_DIR/worktree-data-sweep.sh"
SYMLINK="$SCRIPT_DIR/worktree-data-symlinks.sh"
if [[ ! -f "$SWEEP" || ! -f "$SYMLINK" ]]; then
  printf '[FAIL] 스크립트 부재: %s / %s\n' "$SWEEP" "$SYMLINK"
  exit 1
fi

PASS=0
FAIL=0
FAIL_NAMES=()

_record() {
  local name=$1 result=$2
  if [[ "$result" == "PASS" ]]; then
    PASS=$((PASS + 1)); printf '[PASS] %s\n' "$name"
  else
    FAIL=$((FAIL + 1)); FAIL_NAMES+=("$name"); printf '[FAIL] %s\n' "$name"
  fi
}

# 메인 repo + nested 워크트리 1개 골격 + isolated /data 루트 생성.
# echo: "main nested data" 세 경로.
_setup() {
  local root main nested data
  root=$(mktemp -d)
  main="$root/main"; data="$root/data"
  mkdir -p "$main" "$data"
  git -C "$main" init -q
  git -C "$main" config user.email t@t.t
  git -C "$main" config user.name t
  git -C "$main" commit -q --allow-empty -m init
  nested="$main/.claude/worktrees/wtA"
  git -C "$main" worktree add -q --detach "$nested" >/dev/null 2>&1
  mkdir -p "$nested/web" "$nested/backend"
  printf '%s %s %s' "$main" "$nested" "$data"
}

_sweep() {
  local main=$1 data=$2; shift 2
  MOBRUJI_DATA_ROOT="$data" MOBRUJI_SYMLINK_SCRIPT="$SYMLINK" \
    MOBRUJI_SWEEP_ANCHOR="$main" bash "$SWEEP" "$@" > /dev/null 2>&1
}

# Case 1: 실 node_modules 보유 nested 워크트리 → 심볼릭 전환.
test_1_relocates_nested() {
  local name="1_relocates_nested" main nested data
  read -r main nested data <<<"$(_setup)"
  mkdir -p "$nested/web/node_modules/pkg"; echo hi > "$nested/web/node_modules/pkg/f.txt"
  # mtime 을 과거로 — idle 로 간주되게.
  touch -d "30 min ago" "$nested/web/node_modules"
  MOBRUJI_SWEEP_IDLE_MIN=10 _sweep "$main" "$data"
  local base; base=$(basename "$nested")
  if [[ -L "$nested/web/node_modules" \
     && "$(readlink "$nested/web/node_modules")" == "$data/nm/$base" \
     && -f "$data/nm/$base/pkg/f.txt" ]]; then
    _record "$name" PASS; else _record "$name" FAIL; fi
  rm -rf "$main" "$data" "$(dirname "$main")"
}

# Case 2: 방금 수정된 실 디렉토리 → idle 가드로 skip (실 유지).
test_2_busy_skipped() {
  local name="2_busy_skipped" main nested data
  read -r main nested data <<<"$(_setup)"
  mkdir -p "$nested/web/node_modules/pkg"; echo hi > "$nested/web/node_modules/pkg/f.txt"
  touch "$nested/web/node_modules"  # now → busy
  MOBRUJI_SWEEP_IDLE_MIN=10 _sweep "$main" "$data"
  if [[ -d "$nested/web/node_modules" && ! -L "$nested/web/node_modules" ]]; then
    _record "$name" PASS; else _record "$name" FAIL; fi
  rm -rf "$main" "$data" "$(dirname "$main")"
}

# Case 3: --dry-run → 실제 변경 없음.
test_3_dry_run() {
  local name="3_dry_run" main nested data
  read -r main nested data <<<"$(_setup)"
  mkdir -p "$nested/web/node_modules/pkg"
  touch -d "30 min ago" "$nested/web/node_modules"
  MOBRUJI_SWEEP_IDLE_MIN=10 _sweep "$main" "$data" --dry-run
  if [[ -d "$nested/web/node_modules" && ! -L "$nested/web/node_modules" \
     && ! -e "$data/nm" ]]; then
    _record "$name" PASS; else _record "$name" FAIL; fi
  rm -rf "$main" "$data" "$(dirname "$main")"
}

# Case 4: 이미 심볼릭 → 멱등 (재실행해도 유지).
test_4_idempotent() {
  local name="4_idempotent" main nested data
  read -r main nested data <<<"$(_setup)"
  mkdir -p "$nested/web/node_modules"
  touch -d "30 min ago" "$nested/web/node_modules"
  MOBRUJI_SWEEP_IDLE_MIN=10 _sweep "$main" "$data"
  MOBRUJI_SWEEP_IDLE_MIN=10 _sweep "$main" "$data"  # 2회차
  local base; base=$(basename "$nested")
  if [[ -L "$nested/web/node_modules" \
     && "$(readlink "$nested/web/node_modules")" == "$data/nm/$base" ]]; then
    _record "$name" PASS; else _record "$name" FAIL; fi
  rm -rf "$main" "$data" "$(dirname "$main")"
}

test_1_relocates_nested
test_2_busy_skipped
test_3_dry_run
test_4_idempotent

printf '\n[SUMMARY] PASS=%d FAIL=%d\n' "$PASS" "$FAIL"
if [[ "$FAIL" -gt 0 ]]; then
  printf '실패 케이스:\n'
  for name in "${FAIL_NAMES[@]}"; do printf '  - %s\n' "$name"; done
  exit 1
fi
exit 0
