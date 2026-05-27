#!/usr/bin/env bash
# test_hook_symlinks_lib.sh — lib/hook_symlinks.sh 공통 라이브러리 e2e 테스트 (PR #1124).
#
# 배경:
#   PR #1158 (deploy.sh hook symlink ensure 자동화) 의 ensure_hook_symlink(s) 함수를
#   `lib/hook_symlinks.sh` 공통 라이브러리로 추출했다. deploy.sh / setup-gcp-systemd.sh /
#   setup-launchagent.sh 가 모두 같은 라이브러리를 source 한다.
#
#   본 테스트는 라이브러리를 직접 source 하여 함수 동작을 검증한다 (deploy.sh sed 추출
#   회피). test_deploy_hook_symlink.sh 의 거울 케이스를 라이브러리 직접 호출로 변환.
#
# 검증 시나리오 (모두 isolated tmpdir 안 — 실제 ~/.mobruji 건드리지 않음):
#   1. link 부재 + source 존재 → symlink 신규 생성.
#   2. regular file 존재 + source 존재 → backup 후 symlink 전환.
#   3. 이미 올바른 symlink → skip (idempotent).
#   4. 잘못된 target symlink → 재생성.
#   5. broken symlink (target 부재) → 재생성.
#   6. source 부재 → skip.
#   7. HOOK_SYMLINK_ENSURE=0 → 함수 전체 skip.
#   8. HOOK_LINK_DIR 자체 부재 → 전체 skip (사용자 환경 default dir 없는 경우).
#   9. DRY_RUN=1 → 실제 파일 시스템 변경 없음.
#
# 종료코드: 모든 케이스 pass → 0. 임의 케이스 fail → 1.

set -uo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LIB_PATH="$REPO_DIR/lib/hook_symlinks.sh"
if [[ ! -f "$LIB_PATH" ]]; then
  printf '[FAIL] lib/hook_symlinks.sh 부재: %s\n' "$LIB_PATH"
  exit 1
fi

PASS=0
FAIL=0
FAIL_NAMES=()

_setup_tmpdir() {
  local tmpdir
  tmpdir=$(mktemp -d)
  mkdir -p "$tmpdir/source" "$tmpdir/link"
  echo "$tmpdir"
}

_invoke_single() {
  local tmpdir=$1
  local hook=$2
  local dry=${3:-0}
  LIB_PATH="$LIB_PATH" \
  HOOK_SOURCE_DIR="$tmpdir/source" \
  HOOK_LINK_DIR="$tmpdir/link" \
  HOOK_SYMLINK_ENSURE=1 \
  DRY_RUN="$dry" \
  HOOK_NAME_ARG="$hook" \
  bash -c '
    set -uo pipefail
    log() { printf "[lib-test] %s\n" "$*"; }
    run() {
      if [[ "$DRY_RUN" == "1" ]]; then
        printf "[dry-run] %s\n" "$*"
      else
        eval "$@"
      fi
    }
    # 라이브러리 source — log/run 정의 이후라 fallback 안 덮어쓴다.
    # shellcheck disable=SC1091,SC1090
    source "$LIB_PATH"
    ensure_hook_symlink "$HOOK_NAME_ARG"
  '
}

_invoke_all() {
  local tmpdir=$1
  local hook_ensure=${2:-1}
  local link_dir_override=${3:-}
  local link_dir="${link_dir_override:-$tmpdir/link}"
  LIB_PATH="$LIB_PATH" \
  HOOK_SOURCE_DIR="$tmpdir/source" \
  HOOK_LINK_DIR="$link_dir" \
  HOOK_SYMLINK_ENSURE="$hook_ensure" \
  DRY_RUN=0 \
  bash -c '
    set -uo pipefail
    HOOK_NAMES=(hook-a.sh hook-b.sh)
    log() { printf "[lib-test] %s\n" "$*"; }
    run() {
      if [[ "$DRY_RUN" == "1" ]]; then
        printf "[dry-run] %s\n" "$*"
      else
        eval "$@"
      fi
    }
    # shellcheck disable=SC1091,SC1090
    source "$LIB_PATH"
    ensure_hook_symlinks
  '
}

_record() {
  local name=$1
  local result=$2
  if [[ "$result" == "PASS" ]]; then
    PASS=$((PASS + 1))
    printf '[PASS] %s\n' "$name"
  else
    FAIL=$((FAIL + 1))
    FAIL_NAMES+=("$name")
    printf '[FAIL] %s\n' "$name"
  fi
}

# Case 1: link 부재 + source 존재 → 신규 symlink
test_case_1_link_absent() {
  local tmpdir name
  name="case_1_link_absent"
  tmpdir=$(_setup_tmpdir)
  echo "# source content" > "$tmpdir/source/hook-a.sh"

  _invoke_single "$tmpdir" "hook-a.sh" > /dev/null

  if [[ -L "$tmpdir/link/hook-a.sh" ]] \
     && [[ "$(readlink "$tmpdir/link/hook-a.sh")" == "$tmpdir/source/hook-a.sh" ]]; then
    _record "$name" PASS
  else
    _record "$name" FAIL
  fi
  rm -rf "$tmpdir"
}

# Case 2: regular file 존재 + source 존재 → backup 후 symlink 전환
test_case_2_regular_file_backup() {
  local tmpdir name backup_count
  name="case_2_regular_file_backup"
  tmpdir=$(_setup_tmpdir)
  echo "# source content" > "$tmpdir/source/hook-a.sh"
  echo "# regular file content (old)" > "$tmpdir/link/hook-a.sh"

  _invoke_single "$tmpdir" "hook-a.sh" > /dev/null

  backup_count=$(find "$tmpdir/link" -maxdepth 1 -name 'hook-a.sh.bak.*' -type f | wc -l)
  if [[ -L "$tmpdir/link/hook-a.sh" ]] \
     && [[ "$(readlink "$tmpdir/link/hook-a.sh")" == "$tmpdir/source/hook-a.sh" ]] \
     && [[ "$backup_count" == "1" ]]; then
    _record "$name" PASS
  else
    _record "$name" FAIL
  fi
  rm -rf "$tmpdir"
}

# Case 3: 이미 올바른 symlink → skip (idempotent)
test_case_3_correct_symlink_skip() {
  local tmpdir name mtime_before mtime_after
  name="case_3_correct_symlink_skip"
  tmpdir=$(_setup_tmpdir)
  echo "# source content" > "$tmpdir/source/hook-a.sh"
  ln -s "$tmpdir/source/hook-a.sh" "$tmpdir/link/hook-a.sh"
  mtime_before=$(stat -c '%Y' "$tmpdir/link/hook-a.sh" 2>/dev/null || stat -f '%m' "$tmpdir/link/hook-a.sh")
  sleep 1

  _invoke_single "$tmpdir" "hook-a.sh" > /dev/null

  mtime_after=$(stat -c '%Y' "$tmpdir/link/hook-a.sh" 2>/dev/null || stat -f '%m' "$tmpdir/link/hook-a.sh")
  if [[ -L "$tmpdir/link/hook-a.sh" ]] \
     && [[ "$mtime_before" == "$mtime_after" ]]; then
    _record "$name" PASS
  else
    _record "$name" FAIL
  fi
  rm -rf "$tmpdir"
}

# Case 4: 잘못된 target symlink → 재생성
test_case_4_wrong_target_symlink() {
  local tmpdir name
  name="case_4_wrong_target_symlink"
  tmpdir=$(_setup_tmpdir)
  echo "# source content" > "$tmpdir/source/hook-a.sh"
  echo "# other source" > "$tmpdir/source/other.sh"
  ln -s "$tmpdir/source/other.sh" "$tmpdir/link/hook-a.sh"

  _invoke_single "$tmpdir" "hook-a.sh" > /dev/null

  if [[ -L "$tmpdir/link/hook-a.sh" ]] \
     && [[ "$(readlink "$tmpdir/link/hook-a.sh")" == "$tmpdir/source/hook-a.sh" ]]; then
    _record "$name" PASS
  else
    _record "$name" FAIL
  fi
  rm -rf "$tmpdir"
}

# Case 5: broken symlink (target 부재) → 재생성
test_case_5_broken_symlink() {
  local tmpdir name
  name="case_5_broken_symlink"
  tmpdir=$(_setup_tmpdir)
  echo "# source content" > "$tmpdir/source/hook-a.sh"
  ln -s "$tmpdir/source/nonexistent.sh" "$tmpdir/link/hook-a.sh"

  _invoke_single "$tmpdir" "hook-a.sh" > /dev/null

  if [[ -L "$tmpdir/link/hook-a.sh" ]] \
     && [[ "$(readlink "$tmpdir/link/hook-a.sh")" == "$tmpdir/source/hook-a.sh" ]] \
     && [[ -e "$tmpdir/link/hook-a.sh" ]]; then
    _record "$name" PASS
  else
    _record "$name" FAIL
  fi
  rm -rf "$tmpdir"
}

# Case 6: source 부재 → skip
test_case_6_source_absent() {
  local tmpdir name
  name="case_6_source_absent"
  tmpdir=$(_setup_tmpdir)
  # source 없음

  _invoke_single "$tmpdir" "hook-a.sh" > /dev/null

  if [[ ! -e "$tmpdir/link/hook-a.sh" ]] && [[ ! -L "$tmpdir/link/hook-a.sh" ]]; then
    _record "$name" PASS
  else
    _record "$name" FAIL
  fi
  rm -rf "$tmpdir"
}

# Case 7: HOOK_SYMLINK_ENSURE=0 → 전체 skip
test_case_7_disabled() {
  local tmpdir name
  name="case_7_disabled"
  tmpdir=$(_setup_tmpdir)
  echo "# source content" > "$tmpdir/source/hook-a.sh"
  echo "# source content" > "$tmpdir/source/hook-b.sh"

  _invoke_all "$tmpdir" "0" > /dev/null

  if [[ ! -e "$tmpdir/link/hook-a.sh" ]] && [[ ! -e "$tmpdir/link/hook-b.sh" ]]; then
    _record "$name" PASS
  else
    _record "$name" FAIL
  fi
  rm -rf "$tmpdir"
}

# Case 8: HOOK_LINK_DIR 자체 부재 → 전체 skip
test_case_8_link_dir_absent() {
  local tmpdir name
  name="case_8_link_dir_absent"
  tmpdir=$(_setup_tmpdir)
  echo "# source content" > "$tmpdir/source/hook-a.sh"
  echo "# source content" > "$tmpdir/source/hook-b.sh"

  # HOOK_LINK_DIR 을 존재하지 않는 경로로 override.
  _invoke_all "$tmpdir" "1" "$tmpdir/missing-dir" > /dev/null

  if [[ ! -e "$tmpdir/missing-dir" ]]; then
    _record "$name" PASS
  else
    _record "$name" FAIL
  fi
  rm -rf "$tmpdir"
}

# Case 9: DRY_RUN=1 → 실제 파일 변경 없음
test_case_9_dry_run() {
  local tmpdir name
  name="case_9_dry_run"
  tmpdir=$(_setup_tmpdir)
  echo "# source content" > "$tmpdir/source/hook-a.sh"

  _invoke_single "$tmpdir" "hook-a.sh" "1" > /dev/null

  if [[ ! -e "$tmpdir/link/hook-a.sh" ]] && [[ ! -L "$tmpdir/link/hook-a.sh" ]]; then
    _record "$name" PASS
  else
    _record "$name" FAIL
  fi
  rm -rf "$tmpdir"
}

test_case_1_link_absent
test_case_2_regular_file_backup
test_case_3_correct_symlink_skip
test_case_4_wrong_target_symlink
test_case_5_broken_symlink
test_case_6_source_absent
test_case_7_disabled
test_case_8_link_dir_absent
test_case_9_dry_run

printf '\n[SUMMARY] PASS=%d FAIL=%d\n' "$PASS" "$FAIL"
if [[ "$FAIL" -gt 0 ]]; then
  printf '실패 케이스:\n'
  for name in "${FAIL_NAMES[@]}"; do
    printf '  - %s\n' "$name"
  done
  exit 1
fi
exit 0
