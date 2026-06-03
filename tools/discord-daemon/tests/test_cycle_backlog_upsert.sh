#!/usr/bin/env bash
# test_cycle_backlog_upsert.sh — discord-reply.sh --cycle-backlog-upsert 모드 검증.
#
# DEPRECATED (2026-06-03): `[BACKLOG] <cycle>` 단일 스레드 방식 폐기
# (cycle-forum-operation.md §5-7). mode 는 thread 를 생성/갱신하지 않고 no-op
# exit 0 (빈 stdout + stderr deprecation warning). 인자 검증(cycle/body)은 유지.
# 본 테스트는 "어떤 호출자가 호출해도 새 [BACKLOG] 스레드가 생기지 않음" 을 보장한다.
#
# fake curl 패턴 (test_forum_modes.sh 와 동일 — URL/METHOD/PAYLOAD 캡처).
# GET /channels/{forum_id} 응답에 guild_id 포함 (forum_find_active_thread_by_name 가
# guild_id 추출용).
# GET /guilds/{guild_id}/threads/active 응답에 threads 배열 (parent_id, name).
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

# fake curl — URL 패턴별 분기 응답.
#   - GET /channels/{id}                            → forum 메타 (available_tags + guild_id)
#   - GET /guilds/{gid}/threads/active              → 활성 thread 목록 (parent_id + name)
#   - POST /channels/{forum_id}/threads             → 신규 thread 생성 (id stub)
#   - PATCH /channels/{tid}/messages/{tid}          → starter 본문 갱신
_write_fake_curl() {
  local tmpdir="$1"
  local capture="$2"
  local active_threads_json="$3"   # GET /guilds/{gid}/threads/active 응답의 threads 배열
  local forum_id_for_match="$4"    # 어느 forum 의 [BACKLOG] thread 가 존재한다고 모방할 forum_id
  cat > "$tmpdir/curl" <<EOF
#!/usr/bin/env bash
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

# GET /guilds/{gid}/threads/active
if [[ "\$URL" == *"/guilds/"*"/threads/active" ]]; then
  printf '{"threads":${active_threads_json}}\n200'
  exit 0
fi
# GET /channels/{id}  → forum 메타 + guild_id
if [[ "\$METHOD" == "GET" && "\$URL" == *"/channels/"* ]]; then
  printf '{"id":"\${URL##*/}","guild_id":"guild-xyz","available_tags":[{"id":"tag-pending","name":"대기"},{"id":"tag-done","name":"완료"}]}\n200'
  exit 0
fi
# 그 외 (POST/PATCH) → 200 stub. POST /channels/{forum_id}/threads 면 thread-new 반환.
if [[ "\$URL" == *"/threads" && "\$METHOD" == "POST" ]]; then
  printf '{"id":"thread-new"}\n200'
  exit 0
fi
printf '{"id":"stub"}\n200'
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
    echo "BE_FORUM_ID=forum-be"
    echo "FE_FORUM_ID=forum-fe"
    echo "REV_FORUM_ID=forum-rev"
    echo "PLAN_FORUM_ID=forum-plan"
    echo "DIRECTIVE_BOARD_FORUM_ID=forum-directive"
    echo "DISCORD_GUILD_ID=guild-xyz"
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

# ── case 1: 기존 thread 없음 → 신규 생성 안 함 (deprecated no-op) ─────────────
case1_create_new() {
  echo "[case1 DEPRECATED] --cycle-backlog-upsert be — 신규 [BACKLOG] 스레드 생성 안 함"
  local tmpdir
  tmpdir=$(mktemp -d)
  trap "rm -rf $tmpdir" RETURN
  local capture="$tmpdir/capture.txt"
  : > "$capture"
  _write_fake_curl "$tmpdir" "$capture" '[]' "forum-be"
  local env_path
  env_path=$(_write_env "$tmpdir")

  local stdout stderr
  stdout=$(_run "$tmpdir" "$env_path" \
    --cycle-backlog-upsert be "- [ ] 작업 1 (#100)" 2>"$tmpdir/err.txt")
  local rc=$?
  stderr=$(cat "$tmpdir/err.txt")

  _assert "case1 returncode 0 (no-op)" "[[ $rc -eq 0 ]]"
  _assert "case1 stdout 빈 값 (thread_id 미반환)" "[[ -z '$stdout' ]]"
  _assert "case1 POST 호출 없음 (신규 생성 안 함)" "! grep -q '^POST ' $capture"
  _assert "case1 PATCH 호출 없음" "! grep -q '^PATCH ' $capture"
  _assert "case1 stderr DEPRECATED 경고" \
    "echo '$stderr' | grep -q 'DEPRECATED'"
}

# ── case 2: 기존 thread 있어도 PATCH/갱신 안 함 (deprecated no-op) ────────────
case2_update_existing() {
  echo "[case2 DEPRECATED] --cycle-backlog-upsert fe — 기존 [BACKLOG] 스레드 갱신 안 함"
  local tmpdir
  tmpdir=$(mktemp -d)
  trap "rm -rf $tmpdir" RETURN
  local capture="$tmpdir/capture.txt"
  : > "$capture"
  _write_fake_curl "$tmpdir" "$capture" \
    '[{"id":"thread-existing-fe","parent_id":"forum-fe","name":"[BACKLOG] fe"}]' \
    "forum-fe"
  local env_path
  env_path=$(_write_env "$tmpdir")

  local stdout
  stdout=$(_run "$tmpdir" "$env_path" \
    --cycle-backlog-upsert fe "- [x] 작업 1\n- [ ] 작업 2" 2>/dev/null)
  local rc=$?

  _assert "case2 returncode 0 (no-op)" "[[ $rc -eq 0 ]]"
  _assert "case2 stdout 빈 값" "[[ -z '$stdout' ]]"
  _assert "case2 PATCH 호출 없음 (갱신 안 함)" "! grep -q '^PATCH ' $capture"
  _assert "case2 POST 호출 없음" "! grep -q '^POST ' $capture"
}

# ── case 3: 어떤 cycle 도 신규 생성/갱신 안 함 (deprecated no-op) ─────────────
case3_isolate_by_parent() {
  echo "[case3 DEPRECATED] --cycle-backlog-upsert rev — 신규 생성 안 함"
  local tmpdir
  tmpdir=$(mktemp -d)
  trap "rm -rf $tmpdir" RETURN
  local capture="$tmpdir/capture.txt"
  : > "$capture"
  _write_fake_curl "$tmpdir" "$capture" \
    '[{"id":"thread-be","parent_id":"forum-be","name":"[BACKLOG] be"}]' \
    "forum-be"
  local env_path
  env_path=$(_write_env "$tmpdir")

  local stdout
  stdout=$(_run "$tmpdir" "$env_path" \
    --cycle-backlog-upsert rev "본문" 2>/dev/null)
  local rc=$?

  _assert "case3 returncode 0 (no-op)" "[[ $rc -eq 0 ]]"
  _assert "case3 stdout 빈 값" "[[ -z '$stdout' ]]"
  _assert "case3 POST 호출 없음" "! grep -q '^POST ' $capture"
  _assert "case3 PATCH 호출 없음" "! grep -q '^PATCH ' $capture"
}

# ── case 4: 잘못된 cycle 이름 → 명시 에러 ───────────────────────────────────
case4_invalid_cycle() {
  echo "[case4] --cycle-backlog-upsert infra → 에러"
  local tmpdir
  tmpdir=$(mktemp -d)
  trap "rm -rf $tmpdir" RETURN
  local capture="$tmpdir/capture.txt"
  : > "$capture"
  _write_fake_curl "$tmpdir" "$capture" '[]' ""
  local env_path
  env_path=$(_write_env "$tmpdir")

  local stderr
  stderr=$(_run "$tmpdir" "$env_path" \
    --cycle-backlog-upsert infra "본문" 2>&1 >/dev/null)
  local rc=$?

  _assert "case4 returncode != 0" "[[ $rc -ne 0 ]]"
  _assert "case4 stderr 안 be|fe|rev|plan 안내" \
    "echo '$stderr' | grep -q 'be|fe|rev|plan'"
  _assert "case4 POST 호출 없음" "! grep -q '^POST ' $capture"
}

# ── case 5: 인자 부족 → 사용법 ───────────────────────────────────────────────
case5_missing_body() {
  echo "[case5] --cycle-backlog-upsert be (body 누락) → 에러"
  local tmpdir
  tmpdir=$(mktemp -d)
  trap "rm -rf $tmpdir" RETURN
  local capture="$tmpdir/capture.txt"
  _write_fake_curl "$tmpdir" "$capture" '[]' ""
  local env_path
  env_path=$(_write_env "$tmpdir")

  local stderr
  stderr=$(_run "$tmpdir" "$env_path" --cycle-backlog-upsert be 2>&1 >/dev/null)
  local rc=$?
  _assert "case5 returncode != 0" "[[ $rc -ne 0 ]]"
  _assert "case5 stderr 사용법 안내" \
    "echo '$stderr' | grep -q -- '--cycle-backlog-upsert'"
}

# ── 실행 ────────────────────────────────────────────────────────────────────────
echo "== discord-reply.sh --cycle-backlog-upsert 테스트 =="
case1_create_new
case2_update_existing
case3_isolate_by_parent
case4_invalid_cycle
case5_missing_body

echo
echo "── 결과: PASS=$PASS, FAIL=$FAIL"
if [[ $FAIL -gt 0 ]]; then
  printf '실패한 케이스: %s\n' "${FAIL_NAMES[*]}"
  exit 1
fi
exit 0
