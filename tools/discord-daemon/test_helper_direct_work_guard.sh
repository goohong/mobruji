#!/usr/bin/env bash
# helper-direct-work-guard.sh 검증 매트릭스 (PR #1146 spec §7 T1-T10).
# 사용: bash tools/discord-daemon/test_helper_direct_work_guard.sh
# 모든 케이스 통과 시 exit 0, 실패 시 exit 1 + 어느 케이스인지 출력.
set -u

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
HOOK="$SCRIPT_DIR/helper-direct-work-guard.sh"
if [ ! -x "$HOOK" ]; then
  echo "FAIL: hook script not executable: $HOOK" >&2
  exit 1
fi

# 결과 누적
PASS=0
FAIL=0
FAILED_TESTS=""

# run_test: 케이스명, cwd, MOBRUJI_ROLE, cmd, 기대 exit code
run_test() {
  local name="$1"
  local cwd="$2"
  local role="$3"
  local cmd="$4"
  local expected_exit="$5"

  local input_json
  input_json="$(CWD_IN="$cwd" CMD_IN="$cmd" python3 -c '
import json, os
print(json.dumps({"cwd": os.environ["CWD_IN"], "tool_input": {"command": os.environ["CMD_IN"]}}))
')"

  local actual_exit
  if [ -n "$role" ]; then
    actual_exit=$(MOBRUJI_ROLE="$role" bash "$HOOK" <<< "$input_json" >/dev/null 2>&1; echo $?)
  else
    actual_exit=$(env -u MOBRUJI_ROLE bash "$HOOK" <<< "$input_json" >/dev/null 2>&1; echo $?)
  fi

  if [ "$actual_exit" = "$expected_exit" ]; then
    PASS=$((PASS + 1))
    echo "PASS: $name (exit=$actual_exit)"
  else
    FAIL=$((FAIL + 1))
    FAILED_TESTS="$FAILED_TESTS\n  - $name (expected=$expected_exit, actual=$actual_exit)"
    echo "FAIL: $name (expected=$expected_exit, actual=$actual_exit)"
  fi
}

# T1: sub-agent 워크트리 PR 생성 (정상 — cwd 화이트리스트)
run_test "T1 sub-agent worktree PR create" \
  "/home/mobruji/mobruji-be" "" "gh pr create --base develop --title 'test'" 0

# T2: sub-agent role env set + 메인 cwd
run_test "T2 sub-agent role env + main cwd" \
  "/home/mobruji/mobruji" "backend" "gh pr create --base develop --title 'test'" 0

# T3: nmae 본진 직접 PR (차단 유지)
run_test "T3 nmae main direct PR (block)" \
  "/home/mobruji/mobruji" "" "gh pr create --base develop --title 'test'" 2

# T4: nmae 본진 sentinel 우회
run_test "T4 nmae sentinel bypass" \
  "/home/mobruji/mobruji" "" "MOBRUJI_ALLOW_DIRECT=1 gh pr create --base develop --title 'test'" 0

# T5: nmae 본진 일반 명령 (영향 없음)
run_test "T5 nmae main general cmd" \
  "/home/mobruji/mobruji" "" "git status" 0

# T6: helper default-DENY (영향 없음)
run_test "T6 helper default-DENY non-allowlist" \
  "/home/mobruji/mobruji" "helper" "vim file.txt" 2

# T7: helper allowlist 명령 (영향 없음)
run_test "T7 helper allowlist cmd" \
  "/home/mobruji/mobruji" "helper" "bash /home/mobruji/.mobruji/discord-reply.sh 'test'" 0

# T8: 임시 워크트리 PR 생성 (/tmp/mobruji-*)
run_test "T8 /tmp temp worktree PR create" \
  "/tmp/mobruji-hotfix-x" "" "gh pr create --base develop" 0

# T9: realpath 정규화 후 화이트리스트 (.. 우회 → 정상 매칭)
# /home/mobruji/mobruji/../mobruji-be → realpath → /home/mobruji/mobruji-be (워크트리)
run_test "T9 realpath normalize to worktree" \
  "/home/mobruji/mobruji/../mobruji-be" "" "gh pr create --base develop" 0

# T10: symlink 으로 가짜 sub-agent cwd 생성 (realpath 결과는 mobruji 본진)
# /home/mobruji/mobruji/../mobruji 같은 표현 → realpath → /home/mobruji/mobruji
# 차단되어야 함 (gh pr create + nmae 본진)
run_test "T10 path traversal to nmae main (block)" \
  "/home/mobruji/mobruji-be/../mobruji" "" "gh pr create --base develop" 2

# T11 (보강): sub-agent role 별칭 (be) 통과 확인
run_test "T11 sub-agent role alias 'be'" \
  "/home/mobruji/mobruji" "be" "gh pr create --base develop" 0

# T12 (보강): sub-agent 워크트리에서 main push (cwd 화이트리스트 통과 — sub-agent 책무 신뢰)
# spec §5-4 시퀀스: cwd 화이트리스트 매치 시 무조건 통과 (FORBIDDEN_PATTERNS 적용 안 함)
run_test "T12 sub-agent cwd: main push allowed (책무 신뢰)" \
  "/home/mobruji/mobruji-be" "" "git push origin main" 0

# T13 (보강): nmae 본진 main push 차단
run_test "T13 nmae main: git push origin main (block)" \
  "/home/mobruji/mobruji" "" "git push origin main" 2

echo ""
echo "==================== SUMMARY ===================="
echo "PASS: $PASS"
echo "FAIL: $FAIL"
if [ "$FAIL" -gt 0 ]; then
  echo -e "Failed cases:$FAILED_TESTS"
  exit 1
fi
exit 0
