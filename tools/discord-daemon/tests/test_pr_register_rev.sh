#!/usr/bin/env bash
# test_pr_register_rev.sh — pr-register-rev.sh 단위 테스트 (#1360, 2026-05-30).
#
# spec: docs/features/pr-webhook-rev-forum.md (옵션 D, PR 2-a).
# 거울 룰: test_forum_modes.sh / test_directive_append.sh — fake python3 +
#         fake discord-reply.sh 로 외부 호출 캡처. 모든 case pass → 0.
#
# 검증 케이스 (이슈 본문 + spec §8):
#   1. gh pr create 매칭 → register 호출 (kind=pr_review).
#   2. gh pr merge 매칭 → register 호출 (kind=pr_audit, gh CLI mock).
#   3. gh issue create → no-op (skip).
#   4. dedupe — 같은 (url, kind) 2번 → 1번만 trigger.
#   5. graceful — tool_response 없거나 PR URL parse 실패 → silent exit 0.
#   6. env MOBRUJI_PR_REGISTER_REV=0 → skip.
#   7. actor 가 등록 대상 아니면 skip.
#   8. gh pr create-comment false positive 가드.
#   9. HEREDOC / quoted command 변형 매칭.
#   10. Python direct 호출 실패 시 alert counter 증가 (N=5 도달 시 DIGEST push).
#   11. Python direct 호출 성공 시 alert counter 리셋.
#   12. 다중 PR URL → 첫 번째만 사용.
#   13. malformed JSON stdin → silent exit 0.
#   14. gh pr merge --auto → 가드 (실제 머지 아님).
#   18. MOBRUJI_AGENT_DIR 미설정 → script sibling ../agent 자동 탐색 (#1769).

set -uo pipefail

SCRIPT_PATH="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/pr-register-rev.sh"
if [[ ! -x "$SCRIPT_PATH" ]]; then
  chmod +x "$SCRIPT_PATH" 2>/dev/null || true
fi

PASS=0
FAIL=0
FAIL_NAMES=()

_assert() {
  local name="$1"
  local condition="$2"
  if eval "$condition"; then
    PASS=$((PASS + 1))
    echo "  PASS: $name"
  else
    FAIL=$((FAIL + 1))
    FAIL_NAMES+=("$name")
    echo "  FAIL: $name (조건: $condition)"
  fi
}

_assert_eq() {
  local name="$1"
  local expected="$2"
  local actual="$3"
  if [[ "$expected" == "$actual" ]]; then
    PASS=$((PASS + 1))
    echo "  PASS: $name"
  else
    FAIL=$((FAIL + 1))
    FAIL_NAMES+=("$name")
    echo "  FAIL: $name expected=<$expected> actual=<$actual>"
  fi
}

# fake python3 작성 — register_directive_pending 호출 캡처.
# 인자 2 = exit code (0=성공, 1=실패).
_write_fake_python() {
  local tmpdir="$1"
  local exit_code="${2:-0}"
  local capture="$tmpdir/py_capture.txt"
  cat > "$tmpdir/python3" <<EOF
#!/usr/bin/env bash
# fake python3 — args + code 캡처 + exit code 제어.
echo "ARGS: \$*" >> "$capture"
if [[ "\${1:-}" == "-c" ]]; then
  echo "CODE: \${2:-}" >> "$capture"
fi
echo "EXIT: $exit_code" >> "$capture"
exit $exit_code
EOF
  chmod +x "$tmpdir/python3"
}

# fake discord-reply.sh 작성 — DIGEST push 캡처.
_write_fake_discord_reply() {
  local tmpdir="$1"
  local capture="$tmpdir/discord_capture.txt"
  cat > "$tmpdir/discord-reply.sh" <<EOF
#!/usr/bin/env bash
# fake discord-reply.sh — args 캡처.
echo "DISCORD: \$*" >> "$capture"
exit 0
EOF
  chmod +x "$tmpdir/discord-reply.sh"
}

# fake gh 작성 — gh pr view 결과 stub.
_write_fake_gh() {
  local tmpdir="$1"
  local pr_url="$2"
  cat > "$tmpdir/gh" <<EOF
#!/usr/bin/env bash
# fake gh — pr view 시 URL stub 출력.
if [[ "\${1:-}" == "pr" && "\${2:-}" == "view" ]]; then
  echo "$pr_url"
fi
exit 0
EOF
  chmod +x "$tmpdir/gh"
}

# common runner — stdin 으로 hook JSON 전달, env + MOBRUJI_DIR 격리.
# extra_env_key + extra_env_val = 1 쌍 환경 변수 (필요 시 확장).
_run_hook() {
  local tmpdir="$1"
  local stdin_json="$2"
  local actor="${3:-be}"
  local extra_env_key="${4:-}"
  local extra_env_val="${5:-}"

  if [[ -n "$extra_env_key" ]]; then
    env \
      PATH="$tmpdir:/usr/bin:/bin:/usr/local/bin:/opt/homebrew/bin" \
      HOME="$tmpdir" \
      MOBRUJI_DIR="$tmpdir" \
      MOBRUJI_HOOK_ACTOR="$actor" \
      MOBRUJI_AGENT_PYTHON="$tmpdir/python3" \
      MOBRUJI_AGENT_DIR="$tmpdir/agent_stub" \
      "$extra_env_key=$extra_env_val" \
      bash "$SCRIPT_PATH" <<< "$stdin_json"
  else
    env \
      PATH="$tmpdir:/usr/bin:/bin:/usr/local/bin:/opt/homebrew/bin" \
      HOME="$tmpdir" \
      MOBRUJI_DIR="$tmpdir" \
      MOBRUJI_HOOK_ACTOR="$actor" \
      MOBRUJI_AGENT_PYTHON="$tmpdir/python3" \
      MOBRUJI_AGENT_DIR="$tmpdir/agent_stub" \
      bash "$SCRIPT_PATH" <<< "$stdin_json"
  fi
}

# ─────────────────────────────────────────────────────────────────────────────
echo "== pr-register-rev.sh 단위 테스트 =="

# ── case 1: gh pr create 매칭 → register 호출 (kind=pr_review) ──────────────
case1_gh_pr_create_match() {
  echo "[case1] gh pr create 매칭 → register 호출 (kind=pr_review)"
  local tmpdir
  tmpdir=$(mktemp -d)
  _write_fake_python "$tmpdir" 0
  _write_fake_discord_reply "$tmpdir"

  local stdin_json
  stdin_json=$(cat <<'JSON'
{"hook_event_name":"PostToolUse","tool_name":"Bash","tool_input":{"command":"gh pr create --base develop --title \"feat(song): foo\""},"tool_response":{"output":"https://github.com/goohong/mobruji/pull/1234\n"}}
JSON
)
  _run_hook "$tmpdir" "$stdin_json" "be" >/dev/null 2>&1
  local rc=$?

  _assert "case1 exit 0" "[[ $rc -eq 0 ]]"
  _assert "case1 python3 호출됨" "[[ -f $tmpdir/py_capture.txt ]]"
  _assert "case1 kind=pr_review 박힘" "grep -q 'pr_review' $tmpdir/py_capture.txt"
  _assert "case1 directive_id=rev-1234-open 박힘" \
    "grep -q 'rev-1234-open' $tmpdir/py_capture.txt"
  _assert "case1 pr_url 박힘" \
    "grep -q 'https://github.com/goohong/mobruji/pull/1234' $tmpdir/py_capture.txt"
  _assert "case1 dedupe jsonl 1줄" \
    "[[ -f $tmpdir/pr-register-rev-dedupe.jsonl ]] && [[ \$(wc -l < $tmpdir/pr-register-rev-dedupe.jsonl) -eq 1 ]]"
  rm -rf "$tmpdir"
}

# ── case 2: gh pr merge 매칭 → register 호출 (kind=pr_audit) ────────────────
case2_gh_pr_merge_match() {
  echo "[case2] gh pr merge 매칭 → register 호출 (kind=pr_audit)"
  local tmpdir
  tmpdir=$(mktemp -d)
  _write_fake_python "$tmpdir" 0
  _write_fake_discord_reply "$tmpdir"
  _write_fake_gh "$tmpdir" "https://github.com/goohong/mobruji/pull/5678"

  # tool_response 부재 — command 안 PR 번호 → gh CLI lookup.
  local stdin_json
  stdin_json=$(cat <<'JSON'
{"hook_event_name":"PostToolUse","tool_name":"Bash","tool_input":{"command":"gh pr merge 5678 --squash --delete-branch"},"tool_response":{"output":"merged...\n"}}
JSON
)
  _run_hook "$tmpdir" "$stdin_json" "be" >/dev/null 2>&1
  local rc=$?

  _assert "case2 exit 0" "[[ $rc -eq 0 ]]"
  _assert "case2 python3 호출됨" "[[ -f $tmpdir/py_capture.txt ]]"
  _assert "case2 kind=pr_audit 박힘" "grep -q 'pr_audit' $tmpdir/py_capture.txt"
  _assert "case2 directive_id=rev-5678-merge 박힘" \
    "grep -q 'rev-5678-merge' $tmpdir/py_capture.txt"
  rm -rf "$tmpdir"
}

# ── case 3: gh issue create → no-op ─────────────────────────────────────────
case3_gh_issue_create_skip() {
  echo "[case3] gh issue create → no-op (skip)"
  local tmpdir
  tmpdir=$(mktemp -d)
  _write_fake_python "$tmpdir" 0

  local stdin_json
  stdin_json=$(cat <<'JSON'
{"hook_event_name":"PostToolUse","tool_name":"Bash","tool_input":{"command":"gh issue create --title foo"},"tool_response":{"output":"https://github.com/goohong/mobruji/issues/9999"}}
JSON
)
  _run_hook "$tmpdir" "$stdin_json" "be" >/dev/null 2>&1
  local rc=$?

  _assert "case3 exit 0" "[[ $rc -eq 0 ]]"
  _assert "case3 python3 미호출 (no register)" "[[ ! -f $tmpdir/py_capture.txt ]]"
  rm -rf "$tmpdir"
}

# ── case 4: dedupe — 같은 (url, kind) 2번 → 1번만 trigger ───────────────────
case4_dedupe() {
  echo "[case4] dedupe — 같은 (url, kind) 2번"
  local tmpdir
  tmpdir=$(mktemp -d)
  _write_fake_python "$tmpdir" 0
  _write_fake_discord_reply "$tmpdir"

  local stdin_json
  stdin_json=$(cat <<'JSON'
{"hook_event_name":"PostToolUse","tool_name":"Bash","tool_input":{"command":"gh pr create --base develop"},"tool_response":{"output":"https://github.com/goohong/mobruji/pull/1234"}}
JSON
)
  _run_hook "$tmpdir" "$stdin_json" "be" >/dev/null 2>&1
  _run_hook "$tmpdir" "$stdin_json" "be" >/dev/null 2>&1
  local rc=$?

  _assert "case4 exit 0" "[[ $rc -eq 0 ]]"
  # python3 1회만 호출 (capture 안 ARGS line 1개).
  local py_calls
  py_calls=$(grep -c '^ARGS:' "$tmpdir/py_capture.txt" 2>/dev/null || echo 0)
  _assert_eq "case4 python3 1회만 호출 (dedupe)" "1" "$py_calls"
  rm -rf "$tmpdir"
}

# ── case 5: PR URL parse 실패 → silent exit 0 ──────────────────────────────
case5_graceful_no_url() {
  echo "[case5] PR URL parse 실패 → silent exit 0"
  local tmpdir
  tmpdir=$(mktemp -d)
  _write_fake_python "$tmpdir" 0

  # tool_response 부재 + command 도 URL/번호 없음. pr_review 분기 (gh CLI lookup X).
  local stdin_json
  stdin_json=$(cat <<'JSON'
{"hook_event_name":"PostToolUse","tool_name":"Bash","tool_input":{"command":"gh pr create --base develop"},"tool_response":{"output":"some unrelated output"}}
JSON
)
  _run_hook "$tmpdir" "$stdin_json" "be" >/dev/null 2>&1
  local rc=$?

  _assert "case5 exit 0 (graceful)" "[[ $rc -eq 0 ]]"
  _assert "case5 python3 미호출" "[[ ! -f $tmpdir/py_capture.txt ]]"
  _assert "case5 log 안 WARN" \
    "[[ -f $tmpdir/pr-register-rev.log ]] && grep -q 'WARN' $tmpdir/pr-register-rev.log"
  rm -rf "$tmpdir"
}

# ── case 6: env MOBRUJI_PR_REGISTER_REV=0 → skip ───────────────────────────
case6_env_off() {
  echo "[case6] MOBRUJI_PR_REGISTER_REV=0 → skip"
  local tmpdir
  tmpdir=$(mktemp -d)
  _write_fake_python "$tmpdir" 0

  local stdin_json
  stdin_json=$(cat <<'JSON'
{"hook_event_name":"PostToolUse","tool_name":"Bash","tool_input":{"command":"gh pr create"},"tool_response":{"output":"https://github.com/foo/bar/pull/1"}}
JSON
)
  _run_hook "$tmpdir" "$stdin_json" "be" "MOBRUJI_PR_REGISTER_REV" "0" >/dev/null 2>&1
  local rc=$?

  _assert "case6 exit 0" "[[ $rc -eq 0 ]]"
  _assert "case6 python3 미호출 (off)" "[[ ! -f $tmpdir/py_capture.txt ]]"
  rm -rf "$tmpdir"
}

# ── case 7: actor 가드 — unknown actor → skip ──────────────────────────────
case7_actor_guard() {
  echo "[case7] actor=unknown → skip"
  local tmpdir
  tmpdir=$(mktemp -d)
  _write_fake_python "$tmpdir" 0

  local stdin_json
  stdin_json=$(cat <<'JSON'
{"hook_event_name":"PostToolUse","tool_name":"Bash","tool_input":{"command":"gh pr create"},"tool_response":{"output":"https://github.com/foo/bar/pull/1"}}
JSON
)
  _run_hook "$tmpdir" "$stdin_json" "unknown" >/dev/null 2>&1
  local rc=$?

  _assert "case7 exit 0" "[[ $rc -eq 0 ]]"
  _assert "case7 python3 미호출" "[[ ! -f $tmpdir/py_capture.txt ]]"

  # 6 actor 모두 발사 확인 (#1372 정정 — helper 제외, mmae 추가).
  # 사고 박제: helper 는 Discord 중계 전담 (PR 안 만듦, [[feedback-helper-relay-only]])
  # → 본 hook 의 actor list 에서 제외. PR 만드는 6 actor (mmae/nmae/be/fe/rev/plan) 만.
  for valid_actor in mmae nmae be fe rev plan; do
    local tmp2
    tmp2=$(mktemp -d)
    _write_fake_python "$tmp2" 0
    _write_fake_discord_reply "$tmp2"
    _run_hook "$tmp2" "$stdin_json" "$valid_actor" >/dev/null 2>&1
    _assert "case7 actor=$valid_actor 발사" "[[ -f $tmp2/py_capture.txt ]]"
    rm -rf "$tmp2"
  done
  rm -rf "$tmpdir"
}

# ── case 8: gh pr create-comment false positive 가드 ───────────────────────
case8_false_positive_guard() {
  echo "[case8] gh pr view / comment / list / checkout 매칭 X"
  local tmpdir
  tmpdir=$(mktemp -d)

  for cmd in "gh pr view 1234" "gh pr comment 1234 -b foo" "gh pr list" "gh pr checkout 1234"; do
    local tmp2
    tmp2=$(mktemp -d)
    _write_fake_python "$tmp2" 0
    local sj
    sj=$(printf '{"hook_event_name":"PostToolUse","tool_name":"Bash","tool_input":{"command":"%s"},"tool_response":{"output":"https://github.com/foo/bar/pull/1"}}' "$cmd")
    _run_hook "$tmp2" "$sj" "be" >/dev/null 2>&1
    _assert "case8 cmd='$cmd' 매칭 X" "[[ ! -f $tmp2/py_capture.txt ]]"
    rm -rf "$tmp2"
  done
  rm -rf "$tmpdir"
}

# ── case 9: HEREDOC / quoted command 변형 매칭 ──────────────────────────────
case9_command_variants() {
  echo "[case9] command 변형 매칭"
  local tmpdir
  tmpdir=$(mktemp -d)
  _write_fake_python "$tmpdir" 0
  _write_fake_discord_reply "$tmpdir"

  # 앞 공백 + 긴 args.
  local stdin_json
  stdin_json=$(cat <<'JSON'
{"hook_event_name":"PostToolUse","tool_name":"Bash","tool_input":{"command":"   gh pr create --base develop --title \"feat(infra): foo bar\" --body \"long\""},"tool_response":{"output":"https://github.com/owner/repo/pull/777"}}
JSON
)
  _run_hook "$tmpdir" "$stdin_json" "be" >/dev/null 2>&1
  local rc=$?

  _assert "case9 exit 0" "[[ $rc -eq 0 ]]"
  _assert "case9 매칭 + register 호출" "[[ -f $tmpdir/py_capture.txt ]]"
  _assert "case9 PR 번호 777" "grep -q 'rev-777-open' $tmpdir/py_capture.txt"
  rm -rf "$tmpdir"
}

# ── case 10: Python direct 호출 실패 → alert counter ───────────────────────
case10_alert_counter_increment() {
  echo "[case10] Python 호출 실패 → alert counter 증가"
  local tmpdir
  tmpdir=$(mktemp -d)
  _write_fake_python "$tmpdir" 1   # exit 1 → 실패.
  _write_fake_discord_reply "$tmpdir"

  # MOBRUJI_PR_REGISTER_FAIL_THRESH=3 으로 낮게 — 3회 시 alert.
  for i in 1 2 3; do
    local sj
    sj=$(printf '{"hook_event_name":"PostToolUse","tool_name":"Bash","tool_input":{"command":"gh pr create"},"tool_response":{"output":"https://github.com/x/y/pull/%d"}}' "$i")
    _run_hook "$tmpdir" "$sj" "be" "MOBRUJI_PR_REGISTER_FAIL_THRESH" "3" >/dev/null 2>&1
  done

  _assert "case10 fail counter file 존재" "[[ -f $tmpdir/pr-register-rev-failures.txt ]]"
  local fail_count
  fail_count=$(cat "$tmpdir/pr-register-rev-failures.txt" 2>/dev/null | tr -d ' \n')
  _assert_eq "case10 fail counter = 3" "3" "$fail_count"
  _assert "case10 DIGEST push 호출됨" \
    "[[ -f $tmpdir/discord_capture.txt ]] && grep -q 'DIGEST' $tmpdir/discord_capture.txt"
  rm -rf "$tmpdir"
}

# ── case 11: Python direct 호출 성공 → counter reset ───────────────────────
case11_counter_reset_on_success() {
  echo "[case11] Python 호출 성공 → counter reset"
  local tmpdir
  tmpdir=$(mktemp -d)
  _write_fake_python "$tmpdir" 0   # 성공.
  _write_fake_discord_reply "$tmpdir"

  # 사전 counter = 4 박제.
  echo "4" > "$tmpdir/pr-register-rev-failures.txt"

  local stdin_json
  stdin_json=$(cat <<'JSON'
{"hook_event_name":"PostToolUse","tool_name":"Bash","tool_input":{"command":"gh pr create"},"tool_response":{"output":"https://github.com/foo/bar/pull/999"}}
JSON
)
  _run_hook "$tmpdir" "$stdin_json" "be" >/dev/null 2>&1

  local fail_count
  fail_count=$(cat "$tmpdir/pr-register-rev-failures.txt" 2>/dev/null | tr -d ' \n')
  _assert_eq "case11 counter reset = 0" "0" "$fail_count"
  rm -rf "$tmpdir"
}

# ── case 12: 다중 PR URL → 첫 번째만 ────────────────────────────────────────
case12_multiple_urls() {
  echo "[case12] 다중 PR URL → 첫 번째만 사용"
  local tmpdir
  tmpdir=$(mktemp -d)
  _write_fake_python "$tmpdir" 0
  _write_fake_discord_reply "$tmpdir"

  local stdin_json
  stdin_json=$(cat <<'JSON'
{"hook_event_name":"PostToolUse","tool_name":"Bash","tool_input":{"command":"gh pr create"},"tool_response":{"output":"https://github.com/foo/bar/pull/111 and https://github.com/foo/bar/pull/222"}}
JSON
)
  _run_hook "$tmpdir" "$stdin_json" "be" >/dev/null 2>&1

  _assert "case12 첫 PR 번호 111 사용" "grep -q 'rev-111-open' $tmpdir/py_capture.txt"
  _assert "case12 두 번째 222 미사용" "! grep -q 'rev-222' $tmpdir/py_capture.txt"
  rm -rf "$tmpdir"
}

# ── case 13: malformed JSON → silent exit 0 ────────────────────────────────
case13_malformed_json() {
  echo "[case13] malformed JSON stdin → silent exit 0"
  local tmpdir
  tmpdir=$(mktemp -d)
  _write_fake_python "$tmpdir" 0

  _run_hook "$tmpdir" "not a json {{{ malformed" "be" >/dev/null 2>&1
  local rc=$?

  _assert "case13 exit 0" "[[ $rc -eq 0 ]]"
  _assert "case13 python3 미호출" "[[ ! -f $tmpdir/py_capture.txt ]]"
  rm -rf "$tmpdir"
}

# ── case 14: gh pr merge --auto 가드 ────────────────────────────────────────
case14_auto_merge_guard() {
  echo "[case14] gh pr merge --auto → 가드 (실제 머지 아님)"
  local tmpdir
  tmpdir=$(mktemp -d)
  _write_fake_python "$tmpdir" 0

  local stdin_json
  stdin_json=$(cat <<'JSON'
{"hook_event_name":"PostToolUse","tool_name":"Bash","tool_input":{"command":"gh pr merge 1234 --auto --squash"},"tool_response":{"output":"merge enabled"}}
JSON
)
  _run_hook "$tmpdir" "$stdin_json" "be" >/dev/null 2>&1
  local rc=$?

  _assert "case14 exit 0" "[[ $rc -eq 0 ]]"
  _assert "case14 python3 미호출 (--auto 가드)" "[[ ! -f $tmpdir/py_capture.txt ]]"
  rm -rf "$tmpdir"
}

# ── case 15: cwd fallback — 변수 미설정 시 작업 폴더 기반 자동 판단 (#1372) ──
case15_cwd_fallback() {
  echo "[case15] cwd fallback — env 미설정 시 cwd 기반 actor 자동 판단"
  local stdin_json
  stdin_json=$(cat <<'JSON'
{"hook_event_name":"PostToolUse","tool_name":"Bash","tool_input":{"command":"gh pr create --base develop"},"tool_response":{"output":"https://github.com/goohong/mobruji/pull/1234"}}
JSON
)
  for sub in be fe rev plan; do
    local tmpdir
    tmpdir=$(mktemp -d)
    local fake_cwd="$tmpdir/mobruji-$sub"
    mkdir -p "$fake_cwd"
    _write_fake_python "$fake_cwd" 0
    _write_fake_discord_reply "$fake_cwd"

    (cd "$fake_cwd" && env -u MOBRUJI_HOOK_ACTOR \
      PATH="$fake_cwd:/usr/bin:/bin:/usr/local/bin:/opt/homebrew/bin" \
      HOME="$fake_cwd" \
      MOBRUJI_DIR="$fake_cwd" \
      MOBRUJI_AGENT_PYTHON="$fake_cwd/python3" \
      MOBRUJI_AGENT_DIR="$fake_cwd/agent_stub" \
      bash "$SCRIPT_PATH" <<< "$stdin_json") >/dev/null 2>&1

    _assert "case15 cwd=mobruji-$sub → actor=$sub 자동 판단 발화" \
      "[[ -f $fake_cwd/py_capture.txt ]]"
    rm -rf "$tmpdir"
  done
}

# ── case 16: cwd 미매칭 + env 미설정 → 가드 exit 0 (#1372) ──────────────────
case16_cwd_no_match_skip() {
  echo "[case16] cwd 미매칭 (mac mmae / NCP nmae 등) + env 미설정 → skip"
  local tmpdir
  tmpdir=$(mktemp -d)
  local fake_cwd="$tmpdir/mobruji"  # sub-agent 워크트리 아님
  mkdir -p "$fake_cwd"
  _write_fake_python "$fake_cwd" 0

  local stdin_json
  stdin_json=$(cat <<'JSON'
{"hook_event_name":"PostToolUse","tool_name":"Bash","tool_input":{"command":"gh pr create"},"tool_response":{"output":"https://github.com/foo/bar/pull/1"}}
JSON
)
  (cd "$fake_cwd" && env -u MOBRUJI_HOOK_ACTOR \
    PATH="$fake_cwd:/usr/bin:/bin:/usr/local/bin:/opt/homebrew/bin" \
    HOME="$fake_cwd" \
    MOBRUJI_DIR="$fake_cwd" \
    MOBRUJI_AGENT_PYTHON="$fake_cwd/python3" \
    MOBRUJI_AGENT_DIR="$fake_cwd/agent_stub" \
    bash "$SCRIPT_PATH" <<< "$stdin_json") >/dev/null 2>&1

  _assert "case16 cwd 미매칭 → python3 미호출" "[[ ! -f $fake_cwd/py_capture.txt ]]"
  rm -rf "$tmpdir"
}

# ── case 17: helper actor 명시 → skip (Discord 중계 전담, PR 안 만듦) (#1372) ──
case17_helper_excluded() {
  echo "[case17] helper actor — 본 hook 발화 X (PR 안 만드는 역할)"
  local tmpdir
  tmpdir=$(mktemp -d)
  _write_fake_python "$tmpdir" 0

  local stdin_json
  stdin_json=$(cat <<'JSON'
{"hook_event_name":"PostToolUse","tool_name":"Bash","tool_input":{"command":"gh pr create"},"tool_response":{"output":"https://github.com/foo/bar/pull/1"}}
JSON
)
  _run_hook "$tmpdir" "$stdin_json" "helper" >/dev/null 2>&1
  local rc=$?

  _assert "case17 exit 0 (graceful)" "[[ $rc -eq 0 ]]"
  _assert "case17 helper python3 미호출 (PR 안 만드는 역할)" \
    "[[ ! -f $tmpdir/py_capture.txt ]]"
  rm -rf "$tmpdir"
}

# ── case 18: MOBRUJI_AGENT_DIR 미설정 → script sibling 자동 탐색 (#1769) ──────
# 사고 박제: env 미설정 시 기존 fallback ${MOBRUJI_DIR}/agent (= ~/.mobruji/agent) 가
# 실재하지 않아 tools_cycle import 가 매 hook 발사마다 silent 실패했다. 이제 script
# 실제 위치 sibling ../agent (= repo tools/agent) 를 자동 탐색해야 한다.
case18_agent_dir_autodiscover() {
  echo "[case18] MOBRUJI_AGENT_DIR 미설정 → repo tools/agent 자동 탐색 (#1769)"
  local tmpdir
  tmpdir=$(mktemp -d)
  # fake python3 — 호출 시 받은 MOBRUJI_AGENT_DIR env 캡처.
  cat > "$tmpdir/python3" <<EOF
#!/usr/bin/env bash
echo "AGENTDIR: \${MOBRUJI_AGENT_DIR:-}" >> "$tmpdir/agentdir_capture.txt"
exit 0
EOF
  chmod +x "$tmpdir/python3"

  local stdin_json
  stdin_json=$(cat <<'JSON'
{"hook_event_name":"PostToolUse","tool_name":"Bash","tool_input":{"command":"gh pr create --base develop"},"tool_response":{"output":"https://github.com/goohong/mobruji/pull/1769"}}
JSON
)
  # MOBRUJI_AGENT_DIR 만 unset — 자동 탐색 경로를 강제.
  (env -u MOBRUJI_AGENT_DIR \
    PATH="$tmpdir:/usr/bin:/bin:/usr/local/bin:/opt/homebrew/bin" \
    HOME="$tmpdir" \
    MOBRUJI_DIR="$tmpdir" \
    MOBRUJI_HOOK_ACTOR="be" \
    MOBRUJI_AGENT_PYTHON="$tmpdir/python3" \
    bash "$SCRIPT_PATH" <<< "$stdin_json") >/dev/null 2>&1

  local resolved
  resolved=$(grep '^AGENTDIR:' "$tmpdir/agentdir_capture.txt" 2>/dev/null | head -1 | sed 's/^AGENTDIR: //')
  _assert "case18 자동 탐색된 AGENT_DIR 에 tools_cycle.py 존재" \
    "[[ -n '$resolved' ]] && [[ -f '$resolved/tools_cycle.py' ]]"
  _assert "case18 깨진 legacy fallback(~/.mobruji/agent) 아님" \
    "[[ '$resolved' != '$tmpdir/agent' ]]"
  rm -rf "$tmpdir"
}

# ─────────────────────────────────────────────────────────────────────────────
case1_gh_pr_create_match
case2_gh_pr_merge_match
case3_gh_issue_create_skip
case4_dedupe
case5_graceful_no_url
case6_env_off
case7_actor_guard
case8_false_positive_guard
case9_command_variants
case10_alert_counter_increment
case11_counter_reset_on_success
case12_multiple_urls
case13_malformed_json
case14_auto_merge_guard
case15_cwd_fallback
case16_cwd_no_match_skip
case17_helper_excluded
case18_agent_dir_autodiscover

echo
echo "결과: PASS=$PASS FAIL=$FAIL"
if [[ $FAIL -gt 0 ]]; then
  echo "실패 케이스:"
  for n in "${FAIL_NAMES[@]}"; do
    echo "  - $n"
  done
  exit 1
fi
exit 0
