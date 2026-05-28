#!/usr/bin/env bash
# tools/tests/test_directive_jsonl_forum_diff.sh — directive-board jsonl ↔
# Discord forum mismatch detect 헬퍼 단위 테스트 (PR #1129 impl PR 3).
#
# 검증 시나리오:
#   1. mismatch 없음 (jsonl status == forum tag) → silent + exit 0
#   2. mismatch 있음 → stdout [!] warning + exit 0 (graceful)
#   3. jsonl 부재 → stdout 한 줄 안내 + exit 0
#   4. forum-state-dump 실패 (discord-reply.sh 비실행) → graceful skip + exit 0
#   5. DIRECTIVE_DIFF_NO_DISCORD=1 → forum 단계 skip, silent + exit 0
#   6. 이모지/괄호 부가 텍스트 정규화 매칭 (✅ 완료 == 완료, 🔄 진행 중 (X) == 진행 중)
#   7. --limit 옵션이 최근 N entry 만 비교 (옛 entry 의 mismatch 는 무시)
#   8. 잘못된 --limit 값 → exit 64
#
# 외부 discord-reply.sh 호출은 fake bin 으로 대체 → stdout 으로 forum jsonl 응답.
#
# 사용:
#   bash tools/tests/test_directive_jsonl_forum_diff.sh

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DIFF_SH="$SCRIPT_DIR/../directive-board/jsonl-forum-diff.sh"

PASS=0
FAIL=0
FAILURES=()

if [[ ! -x "$DIFF_SH" ]]; then
  echo "FATAL: $DIFF_SH 가 실행 불가합니다 (chmod +x 필요)." >&2
  exit 2
fi

if ! command -v jq >/dev/null 2>&1; then
  echo "SKIP: jq 미설치 — 본 테스트는 jq 의존입니다." >&2
  exit 0
fi

make_tmp() {
  mktemp -d -t directive-diff-XXXXXX
}

# fake discord-reply.sh — stdout 에 forum dump (jsonl, 1줄 1 thread) 출력.
# 호출 인자 첫 두 개가 "--forum-state-dump directive" 면 옆 dump 파일 cat.
# 그 외 호출은 exit 1.
# (dump body 를 heredoc 안에 embed 하면 따옴표 escape 깨짐 — 별도 파일 사용.)
make_fake_discord_reply() {
  local target="$1"
  local dump_body="$2"
  local dump_file="${target}.dump"
  printf '%s' "$dump_body" > "$dump_file"
  cat > "$target" <<EOF
#!/usr/bin/env bash
if [[ "\${1:-}" == "--forum-state-dump" && "\${2:-}" == "directive" ]]; then
  cat "$dump_file"
  exit 0
fi
exit 1
EOF
  chmod +x "$target"
}

# fake discord-reply.sh 가 항상 exit 1 (forum-state-dump 실패 케이스).
make_fake_discord_reply_failing() {
  local target="$1"
  cat > "$target" <<'EOF'
#!/usr/bin/env bash
exit 1
EOF
  chmod +x "$target"
}

assert_exit() {
  local name="$1" expected="$2" actual="$3"
  if [[ "$actual" == "$expected" ]]; then
    PASS=$((PASS + 1))
    echo "PASS: $name"
  else
    FAIL=$((FAIL + 1))
    FAILURES+=("$name (exit expected=$expected actual=$actual)")
    echo "FAIL: $name (exit expected=$expected actual=$actual)"
  fi
}

assert_contains() {
  local name="$1" haystack="$2" needle="$3"
  if [[ "$haystack" == *"$needle"* ]]; then
    PASS=$((PASS + 1))
    echo "PASS: $name"
  else
    FAIL=$((FAIL + 1))
    FAILURES+=("$name (needle missing: '$needle')")
    echo "FAIL: $name (haystack='$haystack')"
  fi
}

assert_not_contains() {
  local name="$1" haystack="$2" needle="$3"
  if [[ "$haystack" != *"$needle"* ]]; then
    PASS=$((PASS + 1))
    echo "PASS: $name"
  else
    FAIL=$((FAIL + 1))
    FAILURES+=("$name (needle should not exist: '$needle')")
    echo "FAIL: $name (haystack='$haystack')"
  fi
}

# ─────────────────────────────────────────────────────────────────────────────
# 1) mismatch 없음 — silent + exit 0
# ─────────────────────────────────────────────────────────────────────────────
TMP=$(make_tmp)
JSONL="$TMP/dir.jsonl"
{
  printf '%s\n' '{"ts":"2026-05-27 10:00 KST","summary":"매치 1","status":"✅ 완료","message_id":"m1","thread_id":"t1"}'
  printf '%s\n' '{"ts":"2026-05-27 10:30 KST","summary":"매치 2","status":"🔄 진행 중 (단계 1 ✅)","message_id":"m2","thread_id":"t2"}'
} > "$JSONL"
FORUM_DUMP=$'{"thread_id":"t1","name":"매치 1","tags":["완료"]}\n{"thread_id":"t2","name":"매치 2","tags":["진행 중"]}'
FAKE_REPLY="$TMP/discord-reply.sh"
make_fake_discord_reply "$FAKE_REPLY" "$FORUM_DUMP"

OUTPUT=$(DIRECTIVE_BOARD_JSONL_PATH="$JSONL" DISCORD_REPLY_BIN="$FAKE_REPLY" \
  "$DIFF_SH" --limit 20 2>/dev/null)
RC=$?
assert_exit "mismatch 없음 exit 0" 0 "$RC"
assert_not_contains "mismatch 없음 silent (warning 미출력)" "$OUTPUT" "[!]"
rm -rf "$TMP"

# ─────────────────────────────────────────────────────────────────────────────
# 2) mismatch 있음 — stdout [!] warning + exit 0
# ─────────────────────────────────────────────────────────────────────────────
TMP=$(make_tmp)
JSONL="$TMP/dir.jsonl"
{
  printf '%s\n' '{"ts":"2026-05-27 10:00 KST","summary":"미스매치 1 jsonl 완료 vs forum 진행","status":"✅ 완료","message_id":"m1","thread_id":"t1"}'
  printf '%s\n' '{"ts":"2026-05-27 10:30 KST","summary":"매치 OK","status":"🔄 진행 중","message_id":"m2","thread_id":"t2"}'
} > "$JSONL"
FORUM_DUMP=$'{"thread_id":"t1","name":"미스매치","tags":["진행 중"]}\n{"thread_id":"t2","name":"매치","tags":["진행 중"]}'
FAKE_REPLY="$TMP/discord-reply.sh"
make_fake_discord_reply "$FAKE_REPLY" "$FORUM_DUMP"

OUTPUT=$(DIRECTIVE_BOARD_JSONL_PATH="$JSONL" DISCORD_REPLY_BIN="$FAKE_REPLY" \
  "$DIFF_SH" 2>/dev/null)
RC=$?
assert_exit "mismatch 있음 exit 0 (graceful)" 0 "$RC"
assert_contains "mismatch 헤더 emit" "$OUTPUT" "directive-board mismatch detected"
assert_contains "mismatch 라인 thread_id=t1" "$OUTPUT" "thread_id=t1"
assert_contains "mismatch 라인 jsonl_status=완료" "$OUTPUT" "jsonl_status=완료"
assert_contains "mismatch 라인 forum_tag=진행 중" "$OUTPUT" "forum_tag=진행 중"
assert_contains "정정 호출 reminder" "$OUTPUT" "directive_status.sh"
# t2 는 매치 → 미출력.
assert_not_contains "매치 t2 는 mismatch 라인 미출력" "$OUTPUT" "thread_id=t2"
rm -rf "$TMP"

# ─────────────────────────────────────────────────────────────────────────────
# 3) jsonl 부재 — stdout 한 줄 + exit 0
# ─────────────────────────────────────────────────────────────────────────────
TMP=$(make_tmp)
FAKE_REPLY="$TMP/discord-reply.sh"
make_fake_discord_reply "$FAKE_REPLY" ""

OUTPUT=$(DIRECTIVE_BOARD_JSONL_PATH="$TMP/nonexistent.jsonl" DISCORD_REPLY_BIN="$FAKE_REPLY" \
  "$DIFF_SH" 2>/dev/null)
RC=$?
assert_exit "jsonl 부재 exit 0" 0 "$RC"
assert_contains "jsonl 부재 안내" "$OUTPUT" "jsonl 부재"
rm -rf "$TMP"

# ─────────────────────────────────────────────────────────────────────────────
# 4) forum-state-dump 실패 (discord-reply.sh 가 exit 1) — graceful skip
# ─────────────────────────────────────────────────────────────────────────────
TMP=$(make_tmp)
JSONL="$TMP/dir.jsonl"
printf '%s\n' '{"ts":"2026-05-27 10:00 KST","summary":"e","status":"완료","message_id":"m1","thread_id":"t1"}' > "$JSONL"
FAKE_REPLY="$TMP/discord-reply.sh"
make_fake_discord_reply_failing "$FAKE_REPLY"

OUTPUT=$(DIRECTIVE_BOARD_JSONL_PATH="$JSONL" DISCORD_REPLY_BIN="$FAKE_REPLY" \
  "$DIFF_SH" 2>/dev/null)
RC=$?
assert_exit "forum-state-dump 실패 graceful exit 0" 0 "$RC"
assert_contains "forum-state-dump 실패 안내" "$OUTPUT" "forum-state-dump 호출 실패"
rm -rf "$TMP"

# ─────────────────────────────────────────────────────────────────────────────
# 5) DIRECTIVE_DIFF_NO_DISCORD=1 — forum 단계 skip, silent
# ─────────────────────────────────────────────────────────────────────────────
TMP=$(make_tmp)
JSONL="$TMP/dir.jsonl"
printf '%s\n' '{"ts":"2026-05-27 10:00 KST","summary":"e","status":"완료","message_id":"m1","thread_id":"t1"}' > "$JSONL"
FAKE_REPLY="$TMP/discord-reply.sh"
# 본 케이스에서 fake reply 가 호출되면 안 됨 (skip flag).
make_fake_discord_reply_failing "$FAKE_REPLY"

OUTPUT=$(DIRECTIVE_BOARD_JSONL_PATH="$JSONL" DISCORD_REPLY_BIN="$FAKE_REPLY" \
  DIRECTIVE_DIFF_NO_DISCORD=1 "$DIFF_SH" 2>/dev/null)
RC=$?
assert_exit "DIRECTIVE_DIFF_NO_DISCORD=1 exit 0" 0 "$RC"
assert_not_contains "DIRECTIVE_DIFF_NO_DISCORD=1 silent" "$OUTPUT" "[!]"
rm -rf "$TMP"

# ─────────────────────────────────────────────────────────────────────────────
# 6) 이모지/괄호 정규화 매칭 — 다양한 raw status 가 정규화 후 동일 매칭
# ─────────────────────────────────────────────────────────────────────────────
TMP=$(make_tmp)
JSONL="$TMP/dir.jsonl"
{
  printf '%s\n' '{"ts":"2026-05-27 10:00 KST","summary":"이모지 prefix","status":"✅ 완료","message_id":"m1","thread_id":"t1"}'
  printf '%s\n' '{"ts":"2026-05-27 10:30 KST","summary":"괄호 부가","status":"🔄 진행 중 (단계 1 ✅ — issue #1044)","message_id":"m2","thread_id":"t2"}'
  printf '%s\n' '{"ts":"2026-05-27 11:00 KST","summary":"대기 부가","status":"⏳ 대기 (P3c 큐)","message_id":"m3","thread_id":"t3"}'
} > "$JSONL"
# forum tag 는 raw "완료" / "진행 중" / "대기" — 정규화 매칭이 성공해야 함.
FORUM_DUMP=$'{"thread_id":"t1","name":"a","tags":["완료"]}\n{"thread_id":"t2","name":"b","tags":["진행 중"]}\n{"thread_id":"t3","name":"c","tags":["대기"]}'
FAKE_REPLY="$TMP/discord-reply.sh"
make_fake_discord_reply "$FAKE_REPLY" "$FORUM_DUMP"

OUTPUT=$(DIRECTIVE_BOARD_JSONL_PATH="$JSONL" DISCORD_REPLY_BIN="$FAKE_REPLY" \
  "$DIFF_SH" 2>/dev/null)
RC=$?
assert_exit "정규화 매칭 exit 0" 0 "$RC"
assert_not_contains "정규화 매칭 silent (mismatch 0)" "$OUTPUT" "[!]"
rm -rf "$TMP"

# ─────────────────────────────────────────────────────────────────────────────
# 7) --limit 옵션 — 최근 N entry 만 비교 (옛 entry mismatch 무시)
# ─────────────────────────────────────────────────────────────────────────────
TMP=$(make_tmp)
JSONL="$TMP/dir.jsonl"
{
  # 옛 entry (mismatch) — limit=2 면 skip 돼야 함.
  printf '%s\n' '{"ts":"2026-05-20 10:00 KST","summary":"옛 mismatch","status":"완료","message_id":"m_old","thread_id":"t_old"}'
  # 최근 2 entry (매치).
  printf '%s\n' '{"ts":"2026-05-27 10:00 KST","summary":"신규 1","status":"완료","message_id":"m1","thread_id":"t1"}'
  printf '%s\n' '{"ts":"2026-05-27 10:30 KST","summary":"신규 2","status":"진행 중","message_id":"m2","thread_id":"t2"}'
} > "$JSONL"
FORUM_DUMP=$'{"thread_id":"t_old","name":"a","tags":["대기"]}\n{"thread_id":"t1","name":"b","tags":["완료"]}\n{"thread_id":"t2","name":"c","tags":["진행 중"]}'
FAKE_REPLY="$TMP/discord-reply.sh"
make_fake_discord_reply "$FAKE_REPLY" "$FORUM_DUMP"

OUTPUT=$(DIRECTIVE_BOARD_JSONL_PATH="$JSONL" DISCORD_REPLY_BIN="$FAKE_REPLY" \
  "$DIFF_SH" --limit 2 2>/dev/null)
RC=$?
assert_exit "--limit 2 exit 0" 0 "$RC"
assert_not_contains "--limit 2 옛 entry mismatch 무시" "$OUTPUT" "t_old"

# 같은 데이터로 --limit 0 (= 전체) → 옛 entry mismatch 가 detect 돼야 함.
OUTPUT2=$(DIRECTIVE_BOARD_JSONL_PATH="$JSONL" DISCORD_REPLY_BIN="$FAKE_REPLY" \
  "$DIFF_SH" --limit 0 2>/dev/null)
RC2=$?
assert_exit "--limit 0 exit 0" 0 "$RC2"
assert_contains "--limit 0 옛 entry mismatch detect" "$OUTPUT2" "t_old"
rm -rf "$TMP"

# ─────────────────────────────────────────────────────────────────────────────
# 8) 잘못된 --limit 값 → exit 64
# ─────────────────────────────────────────────────────────────────────────────
TMP=$(make_tmp)
JSONL="$TMP/dir.jsonl"
printf '%s\n' '{"ts":"2026-05-27 10:00 KST","summary":"e","status":"완료","message_id":"m1","thread_id":"t1"}' > "$JSONL"
DIRECTIVE_BOARD_JSONL_PATH="$JSONL" \
  "$DIFF_SH" --limit notanumber >/dev/null 2>&1
RC=$?
assert_exit "--limit 비정수 exit 64" 64 "$RC"
rm -rf "$TMP"

# ─────────────────────────────────────────────────────────────────────────────
# 9) helper-turn-start.sh 통합 — mismatch 발견 시 stdout 에 visible warning
# ─────────────────────────────────────────────────────────────────────────────
# helper-turn-start.sh 는 helper LLM 가 직접 보는 stdout 에 reminder/summary
# 를 emit 한다. mismatch 도 같은 채널 (stdout) 로 노출돼야 helper 가 보고
# 정정 호출 결정 가능. agent-launch-wrapper.sh 와는 다른 stdout 정책.
TMP=$(make_tmp)
MOBRUJI_DIR_HTS="$TMP/.mobruji"
mkdir -p "$MOBRUJI_DIR_HTS"
echo "1234567890123456789" > "$MOBRUJI_DIR_HTS/last-user-msg-id.txt"
echo '{"be":{"in_progress":null},"fe":{"in_progress":null},"rev":{"in_progress":null},"plan":{"in_progress":null}}' \
  > "$MOBRUJI_DIR_HTS/cycle-status.json"
echo '{"status":"online"}' > "$MOBRUJI_DIR_HTS/user-presence.json"
echo '{"message_id":"x","status":"pending"}' > "$MOBRUJI_DIR_HTS/helper-queue.jsonl"
DIR_JSONL_HTS="$MOBRUJI_DIR_HTS/directive-board.jsonl"
printf '%s\n' '{"ts":"2026-05-27 10:00 KST","summary":"HTS mismatch","status":"완료","message_id":"m-hts","thread_id":"t-hts"}' > "$DIR_JSONL_HTS"
DUMP_FILE_HTS="$TMP/dump.jsonl"
printf '%s\n' '{"thread_id":"t-hts","name":"a","tags":["진행 중"]}' > "$DUMP_FILE_HTS"
# helper-turn-start.sh 안 ✍️ writing marker / diff helper 호출 모두 본 fake
# discord-reply.sh 를 가리키게 동일 binary 로 mount — writing-marker / dump
# 두 모드를 stub.
FAKE_REPLY_HTS="$MOBRUJI_DIR_HTS/discord-reply.sh"
cat > "$FAKE_REPLY_HTS" <<EOF
#!/usr/bin/env bash
case "\${1:-}" in
  --writing-marker)
    exit 0
    ;;
  --forum-state-dump)
    cat "$DUMP_FILE_HTS"
    exit 0
    ;;
esac
exit 0
EOF
chmod +x "$FAKE_REPLY_HTS"
# helper-turn-start.sh 의 diff 호출 부분에서 DIRECTIVE_BOARD_JSONL_PATH +
# DISCORD_REPLY_BIN 을 env 로 받게 wrapper script 가 export.
HTS_SH="$SCRIPT_DIR/../discord-daemon/helper-turn-start.sh"
HTS_STDOUT=$(MOBRUJI_DIR="$MOBRUJI_DIR_HTS" \
  DIRECTIVE_BOARD_JSONL_PATH="$DIR_JSONL_HTS" \
  DISCORD_REPLY_BIN="$FAKE_REPLY_HTS" \
  "$HTS_SH" 2>/dev/null)
HTS_RC=$?
assert_exit "helper-turn-start.sh 정상 exit 0 (mismatch 있어도 graceful)" 0 "$HTS_RC"
assert_contains "helper-turn-start.sh stdout 에 mismatch warning" "$HTS_STDOUT" "directive-board mismatch detected"
assert_contains "helper-turn-start.sh stdout 에 thread_id=t-hts" "$HTS_STDOUT" "thread_id=t-hts"
assert_contains "helper-turn-start.sh 기존 출력 [6/7] reminder 유지" "$HTS_STDOUT" "[6/7]"
rm -rf "$TMP"

# ─────────────────────────────────────────────────────────────────────────────
# 결과
# ─────────────────────────────────────────────────────────────────────────────
echo
echo "─── 결과 ───"
echo "PASS: $PASS"
echo "FAIL: $FAIL"
if [[ $FAIL -gt 0 ]]; then
  echo "실패 케이스:"
  for entry in "${FAILURES[@]}"; do
    echo "  - $entry"
  done
  exit 1
fi
exit 0
