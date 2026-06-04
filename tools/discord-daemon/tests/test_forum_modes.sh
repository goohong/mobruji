#!/usr/bin/env bash
# test_forum_modes.sh — discord-reply.sh forum mode bash e2e 테스트 (#17, 2026-05-24).
#
# 배경 (사용자 #17 forum 전환 wave):
#   directive-board / per-cycle 채널을 Discord GUILD_FORUM type 으로 신설.
#   discord-reply.sh 에 --forum-post / --forum-comment / --forum-edit /
#   --forum-retag mode 도입. 본 테스트는 외부 Discord REST 호출을 fake curl 로
#   대체해 URL + method + payload 캡처 후 routing / tag lookup / payload shape
#   검증.
#
# 거울 룰: test_cycle_channel_routing.py (Python unittest) 와 동일 캡처 패턴,
#         bash 단독 작성으로 Python 의존 회피 (운영 호스트 어디서나 실행).
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

# fake curl 작성 — GET (tag lookup) 와 POST/PATCH (action) 둘 다 대응.
# GET /channels/{forum_id} → available_tags 응답.
# 그 외 (POST/PATCH) → URL/METHOD/PAYLOAD 캡처 + {"id":"thread-123"} stub 응답.
_write_fake_curl() {
  local tmpdir="$1"
  local capture="$2"
  local available_tags_json="$3"   # JSON array literal (e.g. '[{"id":"tag-A","name":"대기"}]')
  cat > "$tmpdir/curl" <<EOF
#!/usr/bin/env bash
# Captures: each invocation appends "<METHOD> <URL> <PAYLOAD>" line to $capture.
# GET responses: forum metadata with available_tags.
# Other responses: {"id": "thread-123"} + status 200.
METHOD="GET"
URL=""
PAYLOAD=""
while [[ \$# -gt 0 ]]; do
  case "\$1" in
    -X) shift; METHOD="\$1";;
    -d) shift; PAYLOAD="\$1";;
    -w) shift;;  # discard format spec
    -H) shift;;  # discard header
    -sS) ;;
    http*) URL="\$1";;
  esac
  shift
done
printf '%s %s %s\n' "\$METHOD" "\$URL" "\$PAYLOAD" >> $capture
if [[ "\$METHOD" == "GET" ]]; then
  printf '{"id":"forum-stub","available_tags":${available_tags_json}}\n200'
else
  printf '{"id":"thread-123"}\n200'
fi
EOF
  chmod +x "$tmpdir/curl"
}

_write_env() {
  local tmpdir="$1"; shift
  local env_path="$tmpdir/test.env"
  {
    echo "DISCORD_BOT_TOKEN=stub"
    echo "DISCORD_RETRY_MAX=1"
    echo "DISCORD_RETRY_BASE_SEC=0"
    echo "MOBRUJI_CHANNEL_ID=42"
    echo "DIRECTIVE_BOARD_FORUM_ID=forum-directive"
    echo "BE_FORUM_ID=forum-be"
    echo "FE_FORUM_ID=forum-fe"
    echo "REV_FORUM_ID=forum-rev"
    echo "PLAN_FORUM_ID=forum-plan"
    echo "INFRA_FORUM_ID=forum-infra"
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

# ── case 1: --forum-post directive "테스트" "진행" "본문" → thread 생성 ────────
case1_forum_post_directive() {
  echo "[case1] --forum-post directive 정상 라우팅"
  local tmpdir
  tmpdir=$(mktemp -d)
  trap "rm -rf $tmpdir" RETURN
  local capture="$tmpdir/capture.txt"
  : > "$capture"
  _write_fake_curl "$tmpdir" "$capture" \
    '[{"id":"tag-1","name":"진행"},{"id":"tag-2","name":"완료"}]'
  local env_path
  env_path=$(_write_env "$tmpdir")

  local stdout
  stdout=$(_run "$tmpdir" "$env_path" \
    --forum-post directive "테스트 제목" "진행" "본문 내용" 2>/dev/null)
  local rc=$?

  _assert "case1 returncode 0" "[[ $rc -eq 0 ]]"
  _assert "case1 stdout = thread-123" "[[ '$stdout' == 'thread-123' ]]"
  _assert "case1 GET forum-directive 호출" \
    "grep -q '^GET https://discord.com/api/v10/channels/forum-directive ' $capture"
  _assert "case1 POST forum-directive/threads 호출" \
    "grep -q '^POST https://discord.com/api/v10/channels/forum-directive/threads ' $capture"
  _assert "case1 payload 안 applied_tags = tag-1" \
    "grep -q 'applied_tags' $capture && grep -q 'tag-1' $capture"
  _assert "case1 payload 안 name = 테스트 제목" "grep -q '테스트 제목' $capture"
}

# ── case 2: --forum-comment <thread_id> "댓글" → POST messages ────────────────
case2_forum_comment() {
  echo "[case2] --forum-comment thread 안 댓글"
  local tmpdir
  tmpdir=$(mktemp -d)
  trap "rm -rf $tmpdir" RETURN
  local capture="$tmpdir/capture.txt"
  : > "$capture"
  _write_fake_curl "$tmpdir" "$capture" '[]'
  local env_path
  env_path=$(_write_env "$tmpdir")

  _run "$tmpdir" "$env_path" --forum-comment thread-456 "댓글 본문" >/dev/null 2>&1
  local rc=$?

  _assert "case2 returncode 0" "[[ $rc -eq 0 ]]"
  _assert "case2 POST thread-456/messages" \
    "grep -q '^POST https://discord.com/api/v10/channels/thread-456/messages ' $capture"
  _assert "case2 GET 호출 없음 (tag lookup 없음)" "! grep -q '^GET ' $capture"
}

# ── case 3: --forum-edit <thread_id> "새 본문" → PATCH messages/{id} ──────────
case3_forum_edit() {
  echo "[case3] --forum-edit thread starter 본문 PATCH"
  local tmpdir
  tmpdir=$(mktemp -d)
  trap "rm -rf $tmpdir" RETURN
  local capture="$tmpdir/capture.txt"
  : > "$capture"
  _write_fake_curl "$tmpdir" "$capture" '[]'
  local env_path
  env_path=$(_write_env "$tmpdir")

  _run "$tmpdir" "$env_path" --forum-edit thread-789 "수정된 본문" >/dev/null 2>&1
  local rc=$?

  _assert "case3 returncode 0" "[[ $rc -eq 0 ]]"
  _assert "case3 PATCH thread-789/messages/thread-789 (starter == thread)" \
    "grep -q '^PATCH https://discord.com/api/v10/channels/thread-789/messages/thread-789 ' $capture"
  _assert "case3 payload 안 수정된 본문" "grep -q '수정된 본문' $capture"
}

# ── case 4: --forum-retag <thread_id> directive "완료" → PATCH thread ─────────
case4_forum_retag() {
  echo "[case4] --forum-retag applied_tags 갱신"
  local tmpdir
  tmpdir=$(mktemp -d)
  trap "rm -rf $tmpdir" RETURN
  local capture="$tmpdir/capture.txt"
  : > "$capture"
  _write_fake_curl "$tmpdir" "$capture" \
    '[{"id":"tag-done","name":"완료"},{"id":"tag-todo","name":"대기"}]'
  local env_path
  env_path=$(_write_env "$tmpdir")

  _run "$tmpdir" "$env_path" --forum-retag thread-555 directive "완료" >/dev/null 2>&1
  local rc=$?

  _assert "case4 returncode 0" "[[ $rc -eq 0 ]]"
  _assert "case4 GET forum-directive (tag lookup)" \
    "grep -q '^GET https://discord.com/api/v10/channels/forum-directive ' $capture"
  _assert "case4 PATCH thread-555 (속성 갱신)" \
    "grep -q '^PATCH https://discord.com/api/v10/channels/thread-555 ' $capture"
  _assert "case4 payload 안 applied_tags=tag-done" \
    "grep -q 'tag-done' $capture"
}

# ── case 5: 미지원 forum_env → 명시 에러 ─────────────────────────────────────
case5_unknown_forum_env() {
  echo "[case5] 미지원 forum_env → 명시 에러"
  local tmpdir
  tmpdir=$(mktemp -d)
  trap "rm -rf $tmpdir" RETURN
  local capture="$tmpdir/capture.txt"
  : > "$capture"
  _write_fake_curl "$tmpdir" "$capture" '[]'
  local env_path
  env_path=$(_write_env "$tmpdir")

  local stderr
  stderr=$(_run "$tmpdir" "$env_path" \
    --forum-post bogus "제목" "진행" "본문" 2>&1 >/dev/null)
  local rc=$?

  _assert "case5 returncode != 0" "[[ $rc -ne 0 ]]"
  _assert "case5 stderr 안 forum_env 메시지" \
    "echo '$stderr' | grep -q 'forum_env'"
  _assert "case5 push 호출 0건" "! grep -q '^POST ' $capture"
}

# ── case 6: 미지원 tag_name → 명시 에러 ──────────────────────────────────────
case6_unknown_tag() {
  echo "[case6] 미지원 tag_name → 명시 에러"
  local tmpdir
  tmpdir=$(mktemp -d)
  trap "rm -rf $tmpdir" RETURN
  local capture="$tmpdir/capture.txt"
  : > "$capture"
  _write_fake_curl "$tmpdir" "$capture" \
    '[{"id":"tag-1","name":"진행"}]'
  local env_path
  env_path=$(_write_env "$tmpdir")

  local stderr
  stderr=$(_run "$tmpdir" "$env_path" \
    --forum-post be "제목" "존재하지않는태그" "본문" 2>&1 >/dev/null)
  local rc=$?

  _assert "case6 returncode != 0" "[[ $rc -ne 0 ]]"
  _assert "case6 stderr 안 tag 메시지" \
    "echo '$stderr' | grep -q '미지원'"
  _assert "case6 POST threads 호출 없음" "! grep -q '/threads' $capture"
}

# ── case 7: *_FORUM_ID 미설정 → 명시 에러 ───────────────────────────────────
case7_forum_id_unset() {
  echo "[case7] BE_FORUM_ID 미설정 → 명시 에러 (silent fallback 금지)"
  local tmpdir
  tmpdir=$(mktemp -d)
  trap "rm -rf $tmpdir" RETURN
  local capture="$tmpdir/capture.txt"
  : > "$capture"
  _write_fake_curl "$tmpdir" "$capture" '[]'
  # BE_FORUM_ID 만 빈 값.
  local env_path="$tmpdir/test.env"
  {
    echo "DISCORD_BOT_TOKEN=stub"
    echo "MOBRUJI_CHANNEL_ID=42"
    echo "BE_FORUM_ID="
  } > "$env_path"

  local stderr
  stderr=$(_run "$tmpdir" "$env_path" \
    --forum-post be "제목" "진행" "본문" 2>&1 >/dev/null)
  local rc=$?

  _assert "case7 returncode != 0" "[[ $rc -ne 0 ]]"
  _assert "case7 stderr 안 BE_FORUM_ID 안내" \
    "echo '$stderr' | grep -q 'BE_FORUM_ID'"
}

# ── case 8: 인자 부족 ─────────────────────────────────────────────────────────
case8_missing_args() {
  echo "[case8] --forum-post 인자 부족 → 에러"
  local tmpdir
  tmpdir=$(mktemp -d)
  trap "rm -rf $tmpdir" RETURN
  local env_path
  env_path=$(_write_env "$tmpdir")
  _write_fake_curl "$tmpdir" "$tmpdir/cap.txt" '[]'

  local stderr
  stderr=$(_run "$tmpdir" "$env_path" --forum-post directive "title" 2>&1 >/dev/null)
  local rc=$?
  _assert "case8 returncode != 0" "[[ $rc -ne 0 ]]"
  _assert "case8 stderr 사용법 안내" \
    "echo '$stderr' | grep -q -- '--forum-post'"
}

# ── case 9: --forum-post-auto-tag (#1106) — fallback chain 우선순위 ─────────
case9_forum_post_auto_tag_priority() {
  echo "[case9] --forum-post-auto-tag — fallback chain 우선순위"
  local tmpdir
  tmpdir=$(mktemp -d)
  trap "rm -rf $tmpdir" RETURN
  local env_path
  env_path=$(_write_env "$tmpdir")
  # 6 tag 모두 존재 — "PR 진행 중" 가 1순위 → 선택됨.
  _write_fake_curl "$tmpdir" "$tmpdir/cap.txt" \
    '[{"id":"tag-pr","name":"PR 진행 중"},{"id":"tag-prog","name":"진행"},{"id":"tag-spec","name":"spec"},{"id":"tag-stage","name":"stage 1"},{"id":"tag-wait","name":"대기"},{"id":"tag-other","name":"기타"}]'

  local stdout
  stdout=$(_run "$tmpdir" "$env_path" \
    --forum-post-auto-tag be "title-auto" "body-auto" 2>/dev/null)
  local rc=$?

  _assert "case9 returncode 0" "[[ $rc -eq 0 ]]"
  _assert "case9 thread_id 출력" "[[ -n '$stdout' ]]"
  _assert "case9 GET forum 채널 호출" \
    "grep -q 'GET https://discord.com/api/v10/channels/forum-be' $tmpdir/cap.txt"
  _assert "case9 POST forum/threads 호출" \
    "grep -q 'POST https://discord.com/api/v10/channels/forum-be/threads' $tmpdir/cap.txt"
  local post_line
  post_line=$(grep 'POST .*forum-be/threads' "$tmpdir/cap.txt" | head -1)
  _assert "case9 applied_tags 에 tag-pr (1순위) 포함" \
    "echo '$post_line' | grep -q 'tag-pr'"
  _assert "case9 thread name 포함" \
    "echo '$post_line' | grep -q 'title-auto'"
  _assert "case9 starter message content 포함" \
    "echo '$post_line' | grep -q 'body-auto'"
}

# ── case 10: --forum-post-auto-tag — 1순위 없으면 다음 우선순위 ─────────────
case10_forum_post_auto_tag_secondary() {
  echo "[case10] --forum-post-auto-tag — 1순위 없으면 \"진행\" 선택"
  local tmpdir
  tmpdir=$(mktemp -d)
  trap "rm -rf $tmpdir" RETURN
  local env_path
  env_path=$(_write_env "$tmpdir")
  _write_fake_curl "$tmpdir" "$tmpdir/cap.txt" \
    '[{"id":"tag-prog","name":"진행"},{"id":"tag-spec","name":"spec"}]'

  local stdout
  stdout=$(_run "$tmpdir" "$env_path" \
    --forum-post-auto-tag fe "title-fe" "body-fe" 2>/dev/null)
  local rc=$?

  _assert "case10 returncode 0" "[[ $rc -eq 0 ]]"
  local post_line
  post_line=$(grep 'POST .*forum-fe/threads' "$tmpdir/cap.txt" | head -1)
  _assert "case10 applied_tags 에 tag-prog (\"진행\") 포함" \
    "echo '$post_line' | grep -q 'tag-prog'"
  _assert "case10 1순위 \"PR 진행 중\" tag-pr id 미포함 (정확 매칭)" \
    "! echo '$post_line' | grep -qE '\"tag-pr\"'"
}

# ── case 11: --forum-post-auto-tag — available_tags 비어 있으면 tag 없이 생성
case11_forum_post_auto_tag_empty() {
  echo "[case11] --forum-post-auto-tag — available_tags 비어 있음 → tag 없이"
  local tmpdir
  tmpdir=$(mktemp -d)
  trap "rm -rf $tmpdir" RETURN
  local env_path
  env_path=$(_write_env "$tmpdir")
  _write_fake_curl "$tmpdir" "$tmpdir/cap.txt" '[]'

  local stdout
  stdout=$(_run "$tmpdir" "$env_path" \
    --forum-post-auto-tag rev "title-rev" "body-rev" 2>/dev/null)
  local rc=$?

  _assert "case11 returncode 0" "[[ $rc -eq 0 ]]"
  local post_line
  post_line=$(grep 'POST .*forum-rev/threads' "$tmpdir/cap.txt" | head -1)
  _assert "case11 applied_tags 미포함 (tag 없이 thread 생성)" \
    "! echo '$post_line' | grep -q applied_tags"
  _assert "case11 name 정상 포함" \
    "echo '$post_line' | grep -q 'title-rev'"
}

# ── case 12: --forum-post-auto-tag — forum_id 미설정 → 명시 에러 ────────────
case12_forum_post_auto_tag_id_unset() {
  echo "[case12] --forum-post-auto-tag — forum_id 미설정 → 1 exit"
  local tmpdir
  tmpdir=$(mktemp -d)
  trap "rm -rf $tmpdir" RETURN
  local env_path="$tmpdir/test.env"
  {
    echo "DISCORD_BOT_TOKEN=stub"
    echo "MOBRUJI_CHANNEL_ID=42"
    echo "BE_FORUM_ID=forum-be"
    # PLAN_FORUM_ID 의도적 미작성.
  } > "$env_path"
  _write_fake_curl "$tmpdir" "$tmpdir/cap.txt" '[]'

  local stderr
  stderr=$(_run "$tmpdir" "$env_path" \
    --forum-post-auto-tag plan "title-plan" "body-plan" 2>&1 >/dev/null)
  local rc=$?

  _assert "case12 returncode != 0" "[[ $rc -ne 0 ]]"
  _assert "case12 stderr 안 PLAN_FORUM_ID 안내" \
    "echo '$stderr' | grep -q 'PLAN_FORUM_ID'"
}

# ── case 13: --forum-post infra "테스트" "진행" "본문" → infra forum 라우팅 ───
case13_forum_post_infra() {
  echo "[case13] --forum-post infra 정상 라우팅 (#1679)"
  local tmpdir
  tmpdir=$(mktemp -d)
  trap "rm -rf $tmpdir" RETURN
  local capture="$tmpdir/capture.txt"
  : > "$capture"
  _write_fake_curl "$tmpdir" "$capture" \
    '[{"id":"tag-1","name":"진행"},{"id":"tag-2","name":"완료"}]'
  local env_path
  env_path=$(_write_env "$tmpdir")

  local stdout
  stdout=$(_run "$tmpdir" "$env_path" \
    --forum-post infra "infra 사이클 진행" "진행" "본문 내용" 2>/dev/null)
  local rc=$?

  _assert "case13 returncode 0" "[[ $rc -eq 0 ]]"
  _assert "case13 stdout = thread-123" "[[ '$stdout' == 'thread-123' ]]"
  _assert "case13 GET forum-infra 호출" \
    "grep -q '^GET https://discord.com/api/v10/channels/forum-infra ' $capture"
  _assert "case13 POST forum-infra/threads 호출" \
    "grep -q '^POST https://discord.com/api/v10/channels/forum-infra/threads ' $capture"
  _assert "case13 payload 안 applied_tags = tag-1" \
    "grep -q 'applied_tags' $capture && grep -q 'tag-1' $capture"
}

# ── case 14: INFRA_FORUM_ID 미설정 → 명시 에러 ──────────────────────────────
case14_infra_forum_id_unset() {
  echo "[case14] INFRA_FORUM_ID 미설정 → 명시 에러 (silent fallback 금지)"
  local tmpdir
  tmpdir=$(mktemp -d)
  trap "rm -rf $tmpdir" RETURN
  local capture="$tmpdir/capture.txt"
  : > "$capture"
  _write_fake_curl "$tmpdir" "$capture" '[]'
  local env_path="$tmpdir/test.env"
  {
    echo "DISCORD_BOT_TOKEN=stub"
    echo "MOBRUJI_CHANNEL_ID=42"
    echo "INFRA_FORUM_ID="
  } > "$env_path"

  local stderr
  stderr=$(_run "$tmpdir" "$env_path" \
    --forum-post infra "제목" "진행" "본문" 2>&1 >/dev/null)
  local rc=$?

  _assert "case14 returncode != 0" "[[ $rc -ne 0 ]]"
  _assert "case14 stderr 안 INFRA_FORUM_ID 안내" \
    "echo '$stderr' | grep -q 'INFRA_FORUM_ID'"
}

# ── 실행 ────────────────────────────────────────────────────────────────────────
echo "== discord-reply.sh forum mode 테스트 =="
case1_forum_post_directive
case2_forum_comment
case3_forum_edit
case4_forum_retag
case5_unknown_forum_env
case6_unknown_tag
case7_forum_id_unset
case8_missing_args
case9_forum_post_auto_tag_priority
case10_forum_post_auto_tag_secondary
case11_forum_post_auto_tag_empty
case12_forum_post_auto_tag_id_unset
case13_forum_post_infra
case14_infra_forum_id_unset

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
