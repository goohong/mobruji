#!/usr/bin/env bash
# test_worktree_data_symlinks.sh — worktree-data-symlinks.sh e2e 테스트 (이슈 #1770).
#
# 검증 시나리오 (모두 isolated tmpdir — 실제 /data 안 건드림, MOBRUJI_DATA_ROOT override):
#   1. 부재 → /data 타깃 생성 + 심볼릭 신규.
#   2. 실 디렉토리 + 타깃 없음 → /data 로 이동(내용 보존) 후 심볼릭.
#   3. 실 디렉토리 + 타깃 존재 → 실 디렉토리 제거 후 심볼릭(재생성 가능).
#   4. 이미 올바른 심볼릭 → skip (멱등).
#   5. 깨진 심볼릭 → 재생성.
#   6. --dry-run → 실제 변경 없음.
#   7. /data 가득(DATA_FULL_PCT=0) → 실 디렉토리 이동 보류(유지).
#   8. 부모 디렉토리 부재(web/ 없음) → skip.
#
# 종료코드: 모든 케이스 pass → 0. 임의 fail → 1.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT="$SCRIPT_DIR/worktree-data-symlinks.sh"
if [[ ! -f "$SCRIPT" ]]; then
  printf '[FAIL] worktree-data-symlinks.sh 부재: %s\n' "$SCRIPT"
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

# 워크트리 골격 + isolated /data 루트 생성 → "wt data" 두 경로 echo.
_setup() {
  local root wt data
  root=$(mktemp -d)
  wt="$root/wt"; data="$root/data"
  mkdir -p "$wt/web" "$wt/backend" "$data"
  printf '%s %s' "$wt" "$data"
}

_run() {
  local wt=$1 data=$2; shift 2
  MOBRUJI_DATA_ROOT="$data" bash "$SCRIPT" "$wt" "$@" > /dev/null 2>&1
}

# Case 1: 부재 → 타깃 생성 + 심볼릭.
test_1_absent_creates() {
  local name="1_absent_creates" wt data; read -r wt data <<<"$(_setup)"
  _run "$wt" "$data"
  local base; base=$(basename "$wt")
  if [[ -L "$wt/web/node_modules" \
     && "$(readlink "$wt/web/node_modules")" == "$data/nm/$base" \
     && -d "$data/nm/$base" \
     && -L "$wt/web/.next" && -L "$wt/backend/build" ]]; then
    _record "$name" PASS; else _record "$name" FAIL; fi
  rm -rf "$(dirname "$wt")"
}

# Case 2: 실 디렉토리 + 타깃 없음 → 이동(내용 보존) + 심볼릭.
test_2_real_dir_moves() {
  local name="2_real_dir_moves" wt data; read -r wt data <<<"$(_setup)"
  mkdir -p "$wt/web/node_modules/pkg"; echo hi > "$wt/web/node_modules/pkg/f.txt"
  _run "$wt" "$data"
  local base; base=$(basename "$wt")
  if [[ -L "$wt/web/node_modules" \
     && -f "$data/nm/$base/pkg/f.txt" \
     && "$(cat "$wt/web/node_modules/pkg/f.txt")" == "hi" ]]; then
    _record "$name" PASS; else _record "$name" FAIL; fi
  rm -rf "$(dirname "$wt")"
}

# Case 3: 실 디렉토리 + 타깃 존재 → 실 디렉토리 제거 후 심볼릭.
test_3_real_dir_target_exists() {
  local name="3_real_dir_target_exists" wt data; read -r wt data <<<"$(_setup)"
  local base; base=$(basename "$wt")
  mkdir -p "$data/nm/$base/existing"          # 타깃 선존재
  mkdir -p "$wt/web/node_modules/local"        # 워크트리 실 디렉토리
  _run "$wt" "$data"
  if [[ -L "$wt/web/node_modules" \
     && -d "$data/nm/$base/existing" \
     && ! -e "$data/nm/$base/local" ]]; then    # 실 디렉토리는 버려짐(타깃 우선)
    _record "$name" PASS; else _record "$name" FAIL; fi
  rm -rf "$(dirname "$wt")"
}

# Case 4: 이미 올바른 심볼릭 → skip (멱등, 두 번 실행해도 동일).
test_4_idempotent() {
  local name="4_idempotent" wt data; read -r wt data <<<"$(_setup)"
  _run "$wt" "$data"; _run "$wt" "$data"
  local base; base=$(basename "$wt")
  if [[ -L "$wt/web/node_modules" \
     && "$(readlink "$wt/web/node_modules")" == "$data/nm/$base" ]]; then
    _record "$name" PASS; else _record "$name" FAIL; fi
  rm -rf "$(dirname "$wt")"
}

# Case 5: 깨진 심볼릭 → 재생성.
test_5_broken_symlink() {
  local name="5_broken_symlink" wt data; read -r wt data <<<"$(_setup)"
  ln -s "$data/nm/nonexistent-target" "$wt/web/node_modules"
  _run "$wt" "$data"
  local base; base=$(basename "$wt")
  if [[ -L "$wt/web/node_modules" \
     && "$(readlink "$wt/web/node_modules")" == "$data/nm/$base" \
     && -e "$wt/web/node_modules" ]]; then
    _record "$name" PASS; else _record "$name" FAIL; fi
  rm -rf "$(dirname "$wt")"
}

# Case 6: --dry-run → 실제 변경 없음.
test_6_dry_run() {
  local name="6_dry_run" wt data; read -r wt data <<<"$(_setup)"
  _run "$wt" "$data" --dry-run
  if [[ ! -e "$wt/web/node_modules" && ! -L "$wt/web/node_modules" ]]; then
    _record "$name" PASS; else _record "$name" FAIL; fi
  rm -rf "$(dirname "$wt")"
}

# Case 7: /data 가득(DATA_FULL_PCT=0) → 실 디렉토리 이동 보류(유지).
test_7_data_full_holds_move() {
  local name="7_data_full_holds_move" wt data; read -r wt data <<<"$(_setup)"
  mkdir -p "$wt/web/node_modules/pkg"
  MOBRUJI_DATA_ROOT="$data" DATA_FULL_PCT=0 bash "$SCRIPT" "$wt" > /dev/null 2>&1
  # 이동 보류 → 실 디렉토리 그대로(심볼릭 아님).
  if [[ -d "$wt/web/node_modules/pkg" && ! -L "$wt/web/node_modules" ]]; then
    _record "$name" PASS; else _record "$name" FAIL; fi
  rm -rf "$(dirname "$wt")"
}

# Case 8: 부모 디렉토리 부재(web/ 없음) → skip (에러 없이).
test_8_missing_parent_skips() {
  local name="8_missing_parent_skips" root wt data
  root=$(mktemp -d); wt="$root/wt"; data="$root/data"
  mkdir -p "$wt/backend" "$data"   # web/ 일부러 누락
  MOBRUJI_DATA_ROOT="$data" bash "$SCRIPT" "$wt" > /dev/null 2>&1
  local rc=$? base; base=$(basename "$wt")
  if [[ "$rc" == "0" \
     && ! -e "$wt/web" \
     && -L "$wt/backend/build" ]]; then  # backend 는 처리, web 은 skip
    _record "$name" PASS; else _record "$name" FAIL; fi
  rm -rf "$root"
}

test_1_absent_creates
test_2_real_dir_moves
test_3_real_dir_target_exists
test_4_idempotent
test_5_broken_symlink
test_6_dry_run
test_7_data_full_holds_move
test_8_missing_parent_skips

printf '\n[SUMMARY] PASS=%d FAIL=%d\n' "$PASS" "$FAIL"
if [[ "$FAIL" -gt 0 ]]; then
  printf '실패 케이스:\n'
  for name in "${FAIL_NAMES[@]}"; do printf '  - %s\n' "$name"; done
  exit 1
fi
exit 0
