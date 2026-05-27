#!/usr/bin/env bash
# test_deploy_hook_symlink.sh — deploy.sh hook symlink ensure bash e2e 테스트 (PR #1124).
#
# 배경 (사용자 PR #1154 후속 자동화):
#   PR #1154 머지 후 helper-direct-work-guard.sh 가 repo 안 파일 로 추가됨.
#   기존엔 nmae 가 수동으로 ~/.mobruji/helper-direct-work-guard.sh 를 backup 후
#   symlink 로 전환해야 했음. deploy.sh 에 hook symlink ensure 함수 추가하여
#   deploy 마다 idempotent 하게 자동 동기화.
#
# 검증 시나리오 (모두 isolated tmpdir 안 — 실제 ~/.mobruji 건드리지 않음):
#   1. link 부재 + source 존재 → symlink 신규 생성.
#   2. regular file 존재 + source 존재 → backup 후 symlink 전환.
#   3. 이미 올바른 symlink → skip (idempotent).
#   4. 잘못된 target symlink → 재생성.
#   5. broken symlink (target 부재) → 재생성.
#   6. source 부재 → skip (PR 머지 전 hook 호환).
#   7. HOOK_SYMLINK_ENSURE=0 → 함수 전체 skip.
#
# 거울 룰: test_forum_modes.sh 와 동일 bash unittest 패턴 (Python 의존 회피).
#
# 종료코드: 모든 케이스 pass → 0. 임의 케이스 fail → 1.

set -uo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEPLOY_SH="$REPO_DIR/deploy.sh"
if [[ ! -f "$DEPLOY_SH" ]]; then
  printf '[FAIL] deploy.sh 부재: %s\n' "$DEPLOY_SH"
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

# deploy.sh 에서 ensure_hook_symlink + ensure_hook_symlinks 함수 정의만 추출.
# git/systemctl main flow 는 source 하지 않음 (사이드 이펙트 회피).
ENSURE_FN_BODY=$(sed -n '/^ensure_hook_symlink()/,/^}$/p' "$DEPLOY_SH")
ENSURE_ALL_FN_BODY=$(sed -n '/^ensure_hook_symlinks()/,/^}$/p' "$DEPLOY_SH")
if [[ -z "$ENSURE_FN_BODY" ]] || [[ -z "$ENSURE_ALL_FN_BODY" ]]; then
  printf '[FAIL] deploy.sh 에서 ensure_hook_symlink(s) 함수 추출 실패\n'
  exit 1
fi

_invoke_ensure() {
  local tmpdir=$1
  local hook=$2
  HOOK_SOURCE_DIR="$tmpdir/source" \
  HOOK_LINK_DIR="$tmpdir/link" \
  DRY_RUN=0 \
  FN_BODY="$ENSURE_FN_BODY" \
  HOOK_NAME_ARG="$hook" \
  bash -c '
    set -euo pipefail
    log() { printf "[deploy] %s\n" "$*"; }
    run() { eval "$@"; }
    eval "$FN_BODY"
    ensure_hook_symlink "$HOOK_NAME_ARG"
  '
}

_invoke_ensure_all() {
  local tmpdir=$1
  local hook_ensure=${2:-1}
  HOOK_SOURCE_DIR="$tmpdir/source" \
  HOOK_LINK_DIR="$tmpdir/link" \
  HOOK_SYMLINK_ENSURE="$hook_ensure" \
  DRY_RUN=0 \
  FN_BODY="$ENSURE_FN_BODY" \
  FN_ALL_BODY="$ENSURE_ALL_FN_BODY" \
  bash -c '
    set -uo pipefail
    HOOK_NAMES=(hook-a.sh hook-b.sh)
    log() { printf "[deploy] %s\n" "$*"; }
    run() { eval "$@"; }
    eval "$FN_BODY"
    eval "$FN_ALL_BODY"
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

  _invoke_ensure "$tmpdir" "hook-a.sh" > /dev/null

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

  _invoke_ensure "$tmpdir" "hook-a.sh" > /dev/null

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

  _invoke_ensure "$tmpdir" "hook-a.sh" > /dev/null

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

  _invoke_ensure "$tmpdir" "hook-a.sh" > /dev/null

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
  ln -s "$tmpdir/source/hook-a.sh" "$tmpdir/link/hook-a.sh"
  # source 옆 다른 target 으로 broken 만들기
  rm "$tmpdir/link/hook-a.sh"
  ln -s "$tmpdir/source/nonexistent.sh" "$tmpdir/link/hook-a.sh"

  _invoke_ensure "$tmpdir" "hook-a.sh" > /dev/null

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

  _invoke_ensure "$tmpdir" "hook-a.sh" > /dev/null

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

  _invoke_ensure_all "$tmpdir" "0" > /dev/null

  if [[ ! -e "$tmpdir/link/hook-a.sh" ]] && [[ ! -e "$tmpdir/link/hook-b.sh" ]]; then
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

printf '\n[SUMMARY] PASS=%d FAIL=%d\n' "$PASS" "$FAIL"
if [[ "$FAIL" -gt 0 ]]; then
  printf '실패 케이스:\n'
  for name in "${FAIL_NAMES[@]}"; do
    printf '  - %s\n' "$name"
  done
  exit 1
fi
exit 0
