#!/usr/bin/env bash
# test_discord_reply_cycle_channel.sh — `--cycle-channel <ws>` forum adapter e2e
# 테스트 (PR #1155 spec impl, 2026-05-27).
#
# 배경 (사용자 P0 박제):
#   nmae 가 4 사이클 (be/fe/rev/plan) 채널을 Discord forum (type=15) 으로 전환한
#   이후 sub-agent / nmae 의 `discord-reply.sh --cycle-channel <ws> "<본문>"`
#   호출이 50008 (Cannot send messages in a non-text channel) 으로 silent fail.
#   PR #1155 의 adapter 는 channel type 자동 감지 → forum 이면 forum_create_thread
#   경로로 fallback.
#
# 검증 항목:
#   1. cycle channel = forum (type=15) 일 때 forum_create_thread (POST
#      /channels/{forum_id}/threads) 호출. POST messages 호출 없음.
#   2. cycle channel = text (type=0) 일 때 기존 POST messages 호출.
#   3. channel type 조회 실패 (4xx/네트워크) 시 best-effort 로 기존 text channel
#      경로 시도 (graceful fallback). stderr 에 warning 출력.
#   4. title 자동 추론 — 본문 첫 줄 80자 truncate.
#   5. tag 자동 fallback — forum 의 available_tags 첫 매칭.
#   6. text channel 회귀 안전 — `--cycle-channel be` (text=0) 호출이 기존과 동일하게
#      bare body POST messages 로 동작.
#   7. forum (15) + `--thread <id>` 조합은 thread 모드라 forum adapter 우회 (안 건드림).
#
# 거울 룰: test_forum_modes.sh (fake curl 패턴) + test_cycle_channel_routing.py
#         (라우팅 캡처 패턴) 의 합성.
#
# 종료코드: 모든 케이스 pass → 0. 임의 케이스 fail → 1.

set -uo pipefail

SCRIPT_PATH="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/discord-reply.sh"
if [[ ! -x "$SCRIPT_PATH" ]]; then
  chmod +x "$SCRIPT_PATH" 2>/dev/null || true
fi

PASS=0
FAIL=0
FAIL_NAMES=()

# fake curl — GET (channel type / tag lookup) 와 POST/PATCH 모두 캡처.
#
# 응답 정책:
#   - GET /channels/{id} → response body 에 channel_type_json 인자 그대로 echo.
#       예) '{"id":"chan","type":15,"available_tags":[{"id":"tag-1","name":"진행"}]}'
#     단, fetch_should_fail=1 이면 4xx 응답 (silent fallback 검증용).
#   - 그 외 (POST/PATCH) → {"id":"thread-stub"} 200.
_write_fake_curl() {
  local tmpdir="$1"
  local capture="$2"
  local channel_type_json="$3"
  local fetch_should_fail="${4:-0}"
  cat > "$tmpdir/curl" <<EOF
#!/usr/bin/env bash
# Captures: each invocation appends "<METHOD> <URL> <PAYLOAD>" line to $capture.
METHOD="GET"
URL=""
PAYLOAD=""
while [[ \$# -gt 0 ]]; do
  case "\$1" in
    -X) shift; METHOD="\$1";;
    -d) shift; PAYLOAD="\$1";;
    -w) shift;;
    -H) shift;;
    -sS) ;;
    http*) URL="\$1";;
  esac
  shift
done
printf '%s %s %s\n' "\$METHOD" "\$URL" "\$PAYLOAD" >> $capture
if [[ "\$METHOD" == "GET" ]]; then
  if [[ "${fetch_should_fail}" == "1" ]]; then
    printf '%s\n403' '{"message":"Missing Access","code":50001}'
  else
    printf '%s\n200' '${channel_type_json}'
  fi
else
  printf '{"id":"thread-stub"}\n200'
fi
EOF
  chmod +x "$tmpdir/curl"
}

_write_env() {
  local tmpdir="$1"
  local env_path="$tmpdir/test.env"
  {
    echo "DISCORD_BOT_TOKEN=stub"
    echo "DISCORD_RETRY_MAX=1"
    echo "DISCORD_RETRY_BASE_SEC=0"
    echo "MOBRUJI_CHANNEL_ID=42"
    echo "DIGEST_CHANNEL_ID=digest-99"
    echo "BE_CHANNEL_ID=cycle-be"
    echo "FE_CHANNEL_ID=cycle-fe"
    echo "REV_CHANNEL_ID=cycle-rev"
    echo "PLAN_CHANNEL_ID=cycle-plan"
  } > "$env_path"
  printf '%s' "$env_path"
}

_run() {
  local tmpdir="$1"; shift
  local env_path="$1"; shift
  PATH="$tmpdir:$PATH" \
    DISCORD_DAEMON_ENV_PATH="$env_path" \
    LAST_USER_MSG_ID_FILE="$tmpdir/nonexistent-last.txt" \
    HELPER_TARGET_FILE="$tmpdir/nonexistent-target.txt" \
    HELPER_QUEUE_FILE="$tmpdir/nonexistent-queue.jsonl" \
    HELPER_THREAD_FILE="$tmpdir/nonexistent-thread.txt" \
    LAUNCH_THREAD_FILE="$tmpdir/nonexistent-launch.txt" \
    bash "$SCRIPT_PATH" "$@"
}

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

# ── case 1: cycle channel = forum (type=15) → forum_create_thread 경로 ────────
case1_forum_channel_routes_to_forum_thread() {
  echo "[case1] --cycle-channel be (forum type=15) → forum thread 생성 경로"
  local tmpdir
  tmpdir=$(mktemp -d)
  trap "rm -rf $tmpdir" RETURN
  local capture="$tmpdir/capture.txt"
  : > "$capture"
  _write_fake_curl "$tmpdir" "$capture" \
    '{"id":"cycle-be","type":15,"available_tags":[{"id":"tag-1","name":"진행"}]}'
  local env_path
  env_path=$(_write_env "$tmpdir")

  local stdout
  stdout=$(_run "$tmpdir" "$env_path" \
    --cycle-channel be "PR #1155 spec impl 완료" 2>/dev/null)
  local rc=$?

  _assert "case1 returncode 0" "[[ $rc -eq 0 ]]"
  _assert "case1 stdout = thread-stub (forum thread id)" "[[ '$stdout' == 'thread-stub' ]]"
  _assert "case1 GET cycle-be (channel type 조회)" \
    "grep -q '^GET https://discord.com/api/v10/channels/cycle-be ' $capture"
  _assert "case1 POST cycle-be/threads (forum_create_thread)" \
    "grep -q '^POST https://discord.com/api/v10/channels/cycle-be/threads ' $capture"
  _assert "case1 POST cycle-be/messages 미호출 (text channel 경로 차단)" \
    "! grep -q '^POST https://discord.com/api/v10/channels/cycle-be/messages ' $capture"
  _assert "case1 payload 안 title (본문 첫 줄)" \
    "grep -q 'PR #1155 spec impl 완료' $capture"
}

# ── case 2: cycle channel = text (type=0) → 기존 POST messages 경로 ──────────
case2_text_channel_keeps_messages_path() {
  echo "[case2] --cycle-channel be (text type=0) → 기존 POST messages 경로 유지"
  local tmpdir
  tmpdir=$(mktemp -d)
  trap "rm -rf $tmpdir" RETURN
  local capture="$tmpdir/capture.txt"
  : > "$capture"
  _write_fake_curl "$tmpdir" "$capture" \
    '{"id":"cycle-be","type":0}'
  local env_path
  env_path=$(_write_env "$tmpdir")

  _run "$tmpdir" "$env_path" --cycle-channel be "text channel msg" >/dev/null 2>&1
  local rc=$?

  _assert "case2 returncode 0" "[[ $rc -eq 0 ]]"
  _assert "case2 GET cycle-be (channel type 조회)" \
    "grep -q '^GET https://discord.com/api/v10/channels/cycle-be ' $capture"
  _assert "case2 POST cycle-be/messages (bare body 경로)" \
    "grep -q '^POST https://discord.com/api/v10/channels/cycle-be/messages ' $capture"
  _assert "case2 POST cycle-be/threads 미호출 (forum 우회 안 함)" \
    "! grep -q '^POST https://discord.com/api/v10/channels/cycle-be/threads ' $capture"
}

# ── case 3: channel type 조회 실패 → best-effort 기존 경로 ───────────────────
case3_type_fetch_failure_graceful_fallback() {
  echo "[case3] channel type 조회 실패 (403) → best-effort 기존 text 경로 + stderr warning"
  local tmpdir
  tmpdir=$(mktemp -d)
  trap "rm -rf $tmpdir" RETURN
  local capture="$tmpdir/capture.txt"
  : > "$capture"
  # fetch_should_fail=1 → GET 응답이 403.
  _write_fake_curl "$tmpdir" "$capture" '{}' 1
  local env_path
  env_path=$(_write_env "$tmpdir")

  local stderr
  stderr=$(_run "$tmpdir" "$env_path" \
    --cycle-channel rev "graceful body" 2>&1 >/dev/null)
  local rc=$?

  # fetch 실패해도 기존 path 시도 — POST messages 자체는 200 으로 응답 (fake curl POST).
  _assert "case3 returncode 0 (graceful)" "[[ $rc -eq 0 ]]"
  _assert "case3 GET 호출됨" "grep -q '^GET ' $capture"
  _assert "case3 POST cycle-rev/messages (fallback path)" \
    "grep -q '^POST https://discord.com/api/v10/channels/cycle-rev/messages ' $capture"
  _assert "case3 stderr 안 fetch 실패 warning" \
    "echo '$stderr' | grep -q 'channel type 조회 실패'"
}

# ── case 4: forum (type=15) 자동 tag fallback ─────────────────────────────────
case4_forum_auto_tag_priority() {
  echo "[case4] forum (type=15) — available_tags fallback chain 자동 선택"
  local tmpdir
  tmpdir=$(mktemp -d)
  trap "rm -rf $tmpdir" RETURN
  local capture="$tmpdir/capture.txt"
  : > "$capture"
  # "PR 진행 중" 1순위. 다른 tag 도 있지만 우선순위 1순위가 선택됨.
  _write_fake_curl "$tmpdir" "$capture" \
    '{"id":"cycle-fe","type":15,"available_tags":[{"id":"tag-pr","name":"PR 진행 중"},{"id":"tag-prog","name":"진행"},{"id":"tag-wait","name":"대기"}]}'
  local env_path
  env_path=$(_write_env "$tmpdir")

  _run "$tmpdir" "$env_path" \
    --cycle-channel fe "forum auto-tag test body" >/dev/null 2>&1
  local rc=$?

  _assert "case4 returncode 0" "[[ $rc -eq 0 ]]"
  local post_line
  post_line=$(grep '^POST https://discord.com/api/v10/channels/cycle-fe/threads' "$capture" | head -1)
  _assert "case4 applied_tags 에 tag-pr (1순위) 포함" \
    "echo '$post_line' | grep -q 'tag-pr'"
  _assert "case4 starter message body 포함" \
    "echo '$post_line' | grep -q 'forum auto-tag test body'"
}

# ── case 5: forum + 본문 첫 줄 title 추론 ─────────────────────────────────────
case5_title_inferred_from_first_line() {
  echo "[case5] forum — title 자동 추론 (본문 첫 줄 80자 truncate)"
  local tmpdir
  tmpdir=$(mktemp -d)
  trap "rm -rf $tmpdir" RETURN
  local capture="$tmpdir/capture.txt"
  : > "$capture"
  _write_fake_curl "$tmpdir" "$capture" \
    '{"id":"cycle-plan","type":15,"available_tags":[{"id":"tag-1","name":"대기"}]}'
  local env_path
  env_path=$(_write_env "$tmpdir")

  local multiline_body=$'첫 번째 줄이 title 이 됨\n두 번째 줄은 본문 안에 포함\n세 번째 줄'
  _run "$tmpdir" "$env_path" \
    --cycle-channel plan "$multiline_body" >/dev/null 2>&1
  local rc=$?

  _assert "case5 returncode 0" "[[ $rc -eq 0 ]]"
  local post_line
  post_line=$(grep '^POST https://discord.com/api/v10/channels/cycle-plan/threads' "$capture" | head -1)
  _assert "case5 title 에 첫 줄 포함" \
    "echo '$post_line' | grep -q '첫 번째 줄이 title 이 됨'"
}

# ── case 6: forum (type=15) + 빈 available_tags → tag 미부착 ─────────────────
case6_forum_empty_tags() {
  echo "[case6] forum (type=15) + available_tags 비어 있음 → tag 없이 thread 생성"
  local tmpdir
  tmpdir=$(mktemp -d)
  trap "rm -rf $tmpdir" RETURN
  local capture="$tmpdir/capture.txt"
  : > "$capture"
  _write_fake_curl "$tmpdir" "$capture" \
    '{"id":"cycle-rev","type":15,"available_tags":[]}'
  local env_path
  env_path=$(_write_env "$tmpdir")

  _run "$tmpdir" "$env_path" \
    --cycle-channel rev "empty tags body" >/dev/null 2>&1
  local rc=$?

  _assert "case6 returncode 0" "[[ $rc -eq 0 ]]"
  local post_line
  post_line=$(grep '^POST https://discord.com/api/v10/channels/cycle-rev/threads' "$capture" | head -1)
  _assert "case6 applied_tags 미포함 (tag 없이 thread 생성)" \
    "! echo '$post_line' | grep -q applied_tags"
  _assert "case6 starter message body 포함" \
    "echo '$post_line' | grep -q 'empty tags body'"
}

# ── case 7: forum + --thread <id> 조합 → forum adapter 우회 (thread mode 그대로) ─
case7_thread_mode_unaffected() {
  echo "[case7] --cycle-channel + --thread <id> 조합 → thread mode 그대로 (adapter 우회)"
  local tmpdir
  tmpdir=$(mktemp -d)
  trap "rm -rf $tmpdir" RETURN
  local capture="$tmpdir/capture.txt"
  : > "$capture"
  _write_fake_curl "$tmpdir" "$capture" \
    '{"id":"cycle-be","type":15,"available_tags":[]}'
  local env_path
  env_path=$(_write_env "$tmpdir")

  # --thread mode 의 thread id 는 자체로 채널과 동일하게 POST messages 받음.
  _run "$tmpdir" "$env_path" \
    --cycle-channel be --thread 1234567890123456789 "thread mode body" >/dev/null 2>&1
  local rc=$?

  _assert "case7 returncode 0" "[[ $rc -eq 0 ]]"
  _assert "case7 POST thread id/messages (thread 모드 유지)" \
    "grep -q '^POST https://discord.com/api/v10/channels/1234567890123456789/messages ' $capture"
  _assert "case7 forum_create_thread 미호출 (cycle channel forum adapter 우회)" \
    "! grep -q '^POST https://discord.com/api/v10/channels/cycle-be/threads ' $capture"
}

# ── case 8: --channel <id> 직접 mode 는 forum adapter 미적용 (사용자 책임) ───
case8_explicit_channel_no_adapter() {
  echo "[case8] --channel <id> 직접 mode — forum adapter 미적용 (사용자 책임)"
  local tmpdir
  tmpdir=$(mktemp -d)
  trap "rm -rf $tmpdir" RETURN
  local capture="$tmpdir/capture.txt"
  : > "$capture"
  _write_fake_curl "$tmpdir" "$capture" \
    '{"id":"custom-channel","type":15,"available_tags":[]}'
  local env_path
  env_path=$(_write_env "$tmpdir")

  _run "$tmpdir" "$env_path" \
    --channel custom-channel "custom channel body" >/dev/null 2>&1
  local rc=$?

  # GET 호출 자체가 없어야 함 — --channel 은 channel type detect 호출자 책임.
  _assert "case8 returncode 0" "[[ $rc -eq 0 ]]"
  _assert "case8 GET 호출 없음 (channel type detect 미적용)" \
    "! grep -q '^GET ' $capture"
  _assert "case8 POST custom-channel/messages (기존 path)" \
    "grep -q '^POST https://discord.com/api/v10/channels/custom-channel/messages ' $capture"
}

# ── 실행 ────────────────────────────────────────────────────────────────────────
echo "== discord-reply.sh --cycle-channel forum adapter 테스트 (PR #1155) =="
case1_forum_channel_routes_to_forum_thread
case2_text_channel_keeps_messages_path
case3_type_fetch_failure_graceful_fallback
case4_forum_auto_tag_priority
case5_title_inferred_from_first_line
case6_forum_empty_tags
case7_thread_mode_unaffected
case8_explicit_channel_no_adapter

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
