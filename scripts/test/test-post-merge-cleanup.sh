#!/usr/bin/env bash
#
# test-post-merge-cleanup.sh
#
# post-merge-cleanup.sh 의 ROOT_DIR / SUB_WORKTREES 자동 감지 + MOBRUJI_ROOT
# override 동작을 dry-run 으로 검증한다.
#
# 검증 시나리오:
#   1. 자동 감지 (스크립트가 현 워크트리 안에 있을 때)
#   2. MOBRUJI_ROOT env override
#   3. git 워크트리 밖일 때 HOME/mobruji fallback (skip — 환경 의존)
#
# 본 테스트는 post-merge-cleanup.sh 의 path 결정 로직만 source로 가져와 평가하며,
# 실제 git fetch/reset 은 수행하지 않는다.
#
# 실행:
#   bash scripts/test/test-post-merge-cleanup.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET_SCRIPT="${SCRIPT_DIR}/../post-merge-cleanup.sh"

if [[ ! -f "${TARGET_SCRIPT}" ]]; then
  printf 'FAIL: target script not found: %s\n' "${TARGET_SCRIPT}" >&2
  exit 1
fi

RESULT_FILE="$(mktemp)"
trap 'rm -f "${RESULT_FILE}"' EXIT
printf '0 0\n' > "${RESULT_FILE}"

assert_eq() {
  local label="$1"
  local expected="$2"
  local actual="$3"
  local pass fail
  read -r pass fail < "${RESULT_FILE}"
  if [[ "${expected}" == "${actual}" ]]; then
    printf 'PASS: %s\n' "${label}"
    pass=$((pass + 1))
  else
    printf 'FAIL: %s\n  expected=%s\n  actual=%s\n' "${label}" "${expected}" "${actual}" >&2
    fail=$((fail + 1))
  fi
  printf '%d %d\n' "${pass}" "${fail}" > "${RESULT_FILE}"
}

# path 결정 블록만 추출해 evaluator 로 source 한다.
# (set -euo pipefail 이전~SUB_WORKTREES=(...) 닫는 ) 까지)
extract_path_block() {
  awk '
    /^SCRIPT_DIR=/ { capture = 1 }
    capture { print }
    /^\)$/ && capture { exit }
  ' "${TARGET_SCRIPT}"
}

PATH_BLOCK="$(extract_path_block)"

# ---------- Scenario 1: 자동 감지 ----------
# 현 worktree 안에서 source 했을 때 ROOT_DIR 이 worktree root 와 일치해야 한다.
(
  unset MOBRUJI_ROOT
  # shellcheck disable=SC1090
  eval "${PATH_BLOCK}"
  expected_root="$(git -C "${SCRIPT_DIR}/.." rev-parse --show-toplevel)"
  expected_parent="$(dirname "${expected_root}")"
  assert_eq "Scenario 1: ROOT_DIR 자동 감지" "${expected_root}" "${ROOT_DIR}"
  assert_eq "Scenario 1: SUB_WORKTREES[be]" "${expected_parent}/mobruji-be" "${SUB_WORKTREES[0]}"
  assert_eq "Scenario 1: SUB_WORKTREES[fe]" "${expected_parent}/mobruji-fe" "${SUB_WORKTREES[1]}"
  assert_eq "Scenario 1: SUB_WORKTREES[rev]" "${expected_parent}/mobruji-rev" "${SUB_WORKTREES[2]}"
  assert_eq "Scenario 1: SUB_WORKTREES[plan]" "${expected_parent}/mobruji-plan" "${SUB_WORKTREES[3]}"
)

# ---------- Scenario 2: MOBRUJI_ROOT env override ----------
# 부모 디렉터리 기준으로 SUB_WORKTREES 가 결정되므로 expected 도 부모 기준으로 검증.
(
  export MOBRUJI_ROOT="/tmp/customroot/mobruji"
  # shellcheck disable=SC1090
  eval "${PATH_BLOCK}"
  assert_eq "Scenario 2: MOBRUJI_ROOT override" "/tmp/customroot/mobruji" "${ROOT_DIR}"
  assert_eq "Scenario 2: SUB_WORKTREES[be] override" "/tmp/customroot/mobruji-be" "${SUB_WORKTREES[0]}"
  assert_eq "Scenario 2: SUB_WORKTREES[plan] override" "/tmp/customroot/mobruji-plan" "${SUB_WORKTREES[3]}"
)

# ---------- Scenario 3: NCP 경로 모사 ----------
(
  export MOBRUJI_ROOT="/home/mobruji/mobruji"
  # shellcheck disable=SC1090
  eval "${PATH_BLOCK}"
  assert_eq "Scenario 3: NCP ROOT_DIR" "/home/mobruji/mobruji" "${ROOT_DIR}"
  assert_eq "Scenario 3: NCP SUB_WORKTREES[be]" "/home/mobruji/mobruji-be" "${SUB_WORKTREES[0]}"
  assert_eq "Scenario 3: NCP SUB_WORKTREES[fe]" "/home/mobruji/mobruji-fe" "${SUB_WORKTREES[1]}"
)

# ---------- Scenario 4: mac 경로 모사 ----------
(
  export MOBRUJI_ROOT="${HOME}/workspace/github/mobruji"
  # shellcheck disable=SC1090
  eval "${PATH_BLOCK}"
  assert_eq "Scenario 4: mac ROOT_DIR" "${HOME}/workspace/github/mobruji" "${ROOT_DIR}"
  assert_eq "Scenario 4: mac SUB_WORKTREES[be]" "${HOME}/workspace/github/mobruji-be" "${SUB_WORKTREES[0]}"
  assert_eq "Scenario 4: mac SUB_WORKTREES[rev]" "${HOME}/workspace/github/mobruji-rev" "${SUB_WORKTREES[2]}"
)

read -r pass_count fail_count < "${RESULT_FILE}"
printf '\n--- summary: %d passed, %d failed ---\n' "${pass_count}" "${fail_count}"

if (( fail_count > 0 )); then
  exit 1
fi
