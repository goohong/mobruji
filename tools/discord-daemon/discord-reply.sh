#!/usr/bin/env bash
# discord-reply.sh — helper / nmae 가 직접 Discord REST API 로 메시지 push.
#
# 단순화본 (refactor/bot-py-simplify-remove-wrappers): 5 모드 + 4 채널 옵션 +
# 5단 resolve chain 으로 폭증했던 분기를 5 모드 (호환 유지) + 1 + 3 alias +
# 2단 resolve 로 압축. 모든 기존 mode/flag 는 호환 (호출자 변경 없이 drop-in).
#
# 모드:
#   1) 본답 (default):
#        discord-reply.sh "<메시지>"
#        discord-reply.sh [--no-reply] [--reply-to <id>] [--channel <id> | --status-channel] "<메시지>"
#        → leading ZWSP+\n 자동 prepend (#921 ack/본답 분리)
#        → message_reference 자동 (last-user-msg-id.txt 기반, --no-reply 또는
#          --channel/--status-channel 시 disable)
#
#   2) ack + 새 thread (= --auto-ack-thread alias):
#        discord-reply.sh --ack "<문구>"             # deprecated alias (#963 stderr warn)
#        discord-reply.sh --auto-ack-thread "<문구>"
#        → ack push + thread 생성 + thread_id 를 ~/.mobruji/{helper-current,last-launch}-thread.txt
#          에 atomic write + stdout 으로 thread_id 출력
#
#   3) thread 안 push:
#        discord-reply.sh --thread <id> "<진행 줄>"
#        discord-reply.sh --auto-thread "<진행 줄>"
#        → --thread 는 명시, --auto-thread 는 env / file 에서 thread_id 자동 resolve
#          (LAUNCH_THREAD_ID env > last-launch-thread.txt > helper-current-thread.txt)
#
# 채널 override (모든 mode 와 조합 가능, 호출자 호환 alias 3종):
#   --channel <id>    : 임의 채널 id 로 override + auto NO_REPLY
#   --status-channel  : DIGEST_CHANNEL_ID (또는 backward-compat NOTIFY_CHANNEL_ID) 로
#                       override + auto NO_REPLY (#1036 채널 분리 leak fix)
#   --no-reply        : message_reference 비활성 (standalone push)
#   --reply-to <id>   : reply 대상 message_id 명시적 override
#
# Reply target resolve chain (단순화: 2단):
#   1. --reply-to <id> CLI
#   2. ~/.mobruji/last-user-msg-id.txt (bot.py atomic write)
#   (제거됨: HELPER_TURN_TARGET_MSG_ID env / helper-current-target.txt freeze /
#    helper-queue.jsonl pending. 5+분 turn 에서는 stale 만 되어 useful 하지 않음.)
#
# 종속: curl, jq, grep, cut, head, tr, awk, date, mktemp, mv

set -euo pipefail

command -v curl >/dev/null 2>&1 || { echo "discord-reply.sh: curl 미설치" >&2; exit 1; }
command -v jq   >/dev/null 2>&1 || { echo "discord-reply.sh: jq 미설치"   >&2; exit 1; }

ENV_PATH="${DISCORD_DAEMON_ENV_PATH:-/home/mobruji/mobruji/tools/discord-daemon/.env}"
if [[ ! -f "$ENV_PATH" ]]; then
  echo "discord-reply.sh: .env 파일 없음: $ENV_PATH" >&2
  exit 1
fi

# .env value 읽기 — CRLF / 따옴표 제거. 중복 키 첫 줄만.
read_env_value() {
  local key="$1"
  grep -E "^${key}=" "$ENV_PATH" | head -1 | cut -d= -f2- | tr -d '\r' | tr -d '"' | tr -d "'"
}

TOKEN=$(read_env_value DISCORD_BOT_TOKEN)
[[ -n "$TOKEN" ]] || { echo "discord-reply.sh: DISCORD_BOT_TOKEN 비어 있음" >&2; exit 1; }

MOBRUJI_CHANNEL_VALUE=$(read_env_value MOBRUJI_CHANNEL_ID || true)
DIGEST_CHANNEL_VALUE=$(read_env_value DIGEST_CHANNEL_ID || true)
NOTIFY_CHANNEL_VALUE=$(read_env_value NOTIFY_CHANNEL_ID || true)

# default 채널: MOBRUJI > DIGEST > NOTIFY (#1019 backward-compat).
CHANNEL="$MOBRUJI_CHANNEL_VALUE"
[[ -n "$CHANNEL" ]] || CHANNEL="$DIGEST_CHANNEL_VALUE"
if [[ -z "$CHANNEL" && -n "$NOTIFY_CHANNEL_VALUE" ]]; then
  CHANNEL="$NOTIFY_CHANNEL_VALUE"
  echo "discord-reply.sh: NOTIFY_CHANNEL_ID 는 deprecated (#1019) — DIGEST_CHANNEL_ID 로 rename 권장." >&2
fi
[[ -n "$CHANNEL" ]] || { echo "discord-reply.sh: MOBRUJI_CHANNEL_ID / DIGEST_CHANNEL_ID / NOTIFY_CHANNEL_ID 모두 비어 있음" >&2; exit 1; }

HELPER_THREAD_FILE="${HELPER_THREAD_FILE:-${HOME:-/tmp}/.mobruji/helper-current-thread.txt}"
LAUNCH_THREAD_FILE="${LAUNCH_THREAD_FILE:-${HOME:-/tmp}/.mobruji/last-launch-thread.txt}"
LAST_USER_MSG_ID_FILE="${LAST_USER_MSG_ID_FILE:-${HOME:-/tmp}/.mobruji/last-user-msg-id.txt}"
THREAD_NAME_MAX_LEN=30

DISCORD_RETRY_MAX="${DISCORD_RETRY_MAX:-3}"
DISCORD_RETRY_BASE_SEC="${DISCORD_RETRY_BASE_SEC:-1}"

# ─── prefix flag parse (mode 와 무관, 순서 자유) ────────────────────────────────
MODE="reply"
THREAD_ID=""
MSG=""
NO_REPLY=0
REPLY_TO_OVERRIDE=""
STATUS_CHANNEL=0
CHANNEL_OVERRIDE=""

usage() {
  cat >&2 <<'USAGE'
discord-reply.sh: 사용법:
  discord-reply.sh "<메시지>"
  discord-reply.sh [--no-reply] [--reply-to <id>] [--status-channel | --channel <id>] "<메시지>"
  discord-reply.sh --ack "<ack 문구>"               # deprecated alias of --auto-ack-thread
  discord-reply.sh --auto-ack-thread "<문구>"
  discord-reply.sh --thread <id> "<진행 줄>"
  discord-reply.sh --auto-thread "<진행 줄>"
USAGE
}

[[ $# -gt 0 ]] || { usage; exit 1; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-reply)         NO_REPLY=1; shift ;;
    --reply-to)
      [[ $# -ge 2 ]] || { echo "discord-reply.sh: --reply-to 뒤에 message_id 가 필요합니다" >&2; exit 1; }
      REPLY_TO_OVERRIDE="$2"; shift 2 ;;
    --status-channel)   STATUS_CHANNEL=1; NO_REPLY=1; shift ;;
    --channel)
      [[ $# -ge 2 ]] || { echo "discord-reply.sh: --channel 뒤에 channel_id 가 필요합니다" >&2; exit 1; }
      CHANNEL_OVERRIDE="$2"; NO_REPLY=1; shift 2 ;;
    *) break ;;
  esac
done

# 채널 override (--channel > --status-channel > default).
if [[ -n "$CHANNEL_OVERRIDE" ]]; then
  CHANNEL="$CHANNEL_OVERRIDE"
elif [[ "$STATUS_CHANNEL" -eq 1 ]]; then
  if [[ -n "$DIGEST_CHANNEL_VALUE" ]]; then
    CHANNEL="$DIGEST_CHANNEL_VALUE"
  elif [[ -n "$NOTIFY_CHANNEL_VALUE" ]]; then
    CHANNEL="$NOTIFY_CHANNEL_VALUE"
    echo "discord-reply.sh: --status-channel — NOTIFY_CHANNEL_ID 사용 (DIGEST_CHANNEL_ID 로 rename 권장, #1019)." >&2
  else
    echo "discord-reply.sh: --status-channel 지정됐으나 DIGEST_CHANNEL_ID / NOTIFY_CHANNEL_ID 미설정 — 채널 분리 불가" >&2
    echo "  .env 에 DIGEST_CHANNEL_ID=<id> 추가 후 재시도하세요." >&2
    exit 1
  fi
fi

[[ $# -gt 0 ]] || { echo "discord-reply.sh: prefix flag 뒤에 메시지가 필요합니다" >&2; exit 1; }

case "$1" in
  --ack|--auto-ack-thread)
    [[ "$1" == "--ack" ]] && echo "discord-reply.sh: --ack mode 는 deprecated (#963) — --auto-ack-thread 사용 권장." >&2
    MODE="ack"
    [[ $# -ge 2 ]] || { echo "discord-reply.sh: $1 뒤에 문구가 필요합니다" >&2; exit 1; }
    MSG="$2" ;;
  --thread)
    MODE="thread"
    [[ $# -ge 3 ]] || { echo "discord-reply.sh: --thread <id> <메시지> 형태로 입력해주세요" >&2; exit 1; }
    THREAD_ID="$2"; MSG="$3" ;;
  --auto-thread)
    MODE="auto-thread"
    [[ $# -ge 2 ]] || { echo "discord-reply.sh: --auto-thread 뒤에 진행 줄이 필요합니다" >&2; exit 1; }
    MSG="$2" ;;
  --*) echo "discord-reply.sh: 알 수 없는 옵션 $1" >&2; exit 1 ;;
  *)
    MODE="reply"; MSG="$1" ;;
esac

[[ -n "$MSG" ]] || { echo "discord-reply.sh: 빈 메시지 — 호출 의도 확인 필요" >&2; exit 1; }

# ─── helpers ──────────────────────────────────────────────────────────────────

# Discord REST 호출 + 429/5xx retry (#911 G-6).
discord_curl_with_retry() {
  local method="$1" url="$2" body="$3"
  local response status payload retry_after sleep_sec i
  for ((i = 1; i <= DISCORD_RETRY_MAX; i++)); do
    response=$(curl -sS -X "$method" "$url" \
      -H "Authorization: Bot ${TOKEN}" -H "Content-Type: application/json" \
      -w $'\n%{http_code}' -d "$body" 2>/dev/null || true)
    status="${response##*$'\n'}"
    payload="${response%$'\n'*}"

    if [[ "$status" =~ ^2[0-9][0-9]$ ]]; then
      printf '%s' "$payload"; return 0
    fi
    if [[ "$status" == "429" ]]; then
      retry_after=$(printf '%s' "$payload" | jq -r '.retry_after // empty' 2>/dev/null || true)
      sleep_sec="${retry_after:-$DISCORD_RETRY_BASE_SEC}"
      [[ "$sleep_sec" == "null" ]] && sleep_sec="$DISCORD_RETRY_BASE_SEC"
      echo "discord-reply.sh: 429 rate limit — retry ${i}/${DISCORD_RETRY_MAX} after ${sleep_sec}s" >&2
      sleep "$sleep_sec"; continue
    fi
    if [[ "$status" =~ ^5[0-9][0-9]$ ]]; then
      sleep_sec=$(awk -v base="$DISCORD_RETRY_BASE_SEC" -v exp="$((i - 1))" \
        'BEGIN { printf "%.2f", base * (2 ^ exp) }')
      echo "discord-reply.sh: ${status} server error — retry ${i}/${DISCORD_RETRY_MAX} after ${sleep_sec}s" >&2
      sleep "$sleep_sec"; continue
    fi
    # 4xx 등 — retry 무의미.
    printf '%s' "$payload"; return 1
  done
  echo "discord-reply.sh: ${DISCORD_RETRY_MAX} 회 retry 실패 (last status=${status})" >&2
  printf '%s' "$payload"; return 1
}

post_channel_message() {
  discord_curl_with_retry POST "https://discord.com/api/v10/channels/${CHANNEL}/messages" "$1"
}

start_thread_from_message() {
  local body
  body=$(jq -nc --arg n "$2" '{name: $n, auto_archive_duration: 1440}')
  discord_curl_with_retry POST \
    "https://discord.com/api/v10/channels/${CHANNEL}/messages/${1}/threads" "$body"
}

post_thread_message() {
  discord_curl_with_retry POST "https://discord.com/api/v10/channels/${1}/messages" "$2"
}

# snowflake (17~20 digit 정수) 검증.
validate_snowflake() {
  local raw="$1" source_label="$2"
  if [[ "$raw" =~ ^[0-9]{17,20}$ ]]; then
    printf '%s' "$raw"; return 0
  fi
  [[ -n "$raw" ]] && echo "discord-reply.sh: ${source_label} 비-snowflake (\"$raw\") — 다음 fallback" >&2
  printf ''; return 1
}

# Reply target resolve — 단순화 2단 (REPLY_TO_OVERRIDE > last-user-msg-id.txt).
resolve_reply_to_id() {
  [[ "$NO_REPLY" -eq 1 ]] && { printf ''; return 0; }
  local candidate
  if [[ -n "$REPLY_TO_OVERRIDE" ]]; then
    if candidate=$(validate_snowflake "$REPLY_TO_OVERRIDE" "--reply-to"); then
      printf '%s' "$candidate"; return 0
    fi
  fi
  if [[ -r "$LAST_USER_MSG_ID_FILE" ]]; then
    local raw
    raw=$(head -1 "$LAST_USER_MSG_ID_FILE" 2>/dev/null | tr -d '[:space:]' || true)
    if [[ -n "$raw" ]]; then
      if candidate=$(validate_snowflake "$raw" "last-user-msg-id.txt"); then
        printf '%s' "$candidate"; return 0
      fi
    fi
  fi
  printf ''
}

build_reply_payload() {
  local content="$1" reply_to_id="$2"
  if [[ -n "$reply_to_id" ]]; then
    jq -nc --arg c "$content" --arg mid "$reply_to_id" --arg cid "$CHANNEL" \
      '{content: $c, message_reference: {message_id: $mid, channel_id: $cid, fail_if_not_exists: false}}'
  else
    jq -nc --arg c "$content" '{content: $c}'
  fi
}

# helper-current-thread.txt atomic write (#911 G-5).
atomic_write_thread_file() {
  local thread_id="$1" thread_file="$2" dir tmp
  dir="$(dirname "$thread_file")"
  mkdir -p "$dir"
  tmp=$(mktemp "${thread_file}.XXXXXX")
  printf '%s\n' "$thread_id" > "$tmp"
  mv "$tmp" "$thread_file"
}

# ─── mode 실행 ────────────────────────────────────────────────────────────────

case "$MODE" in
  reply)
    # leading ZWSP + \n prepend (#921 ack/본답 시각 분리).
    MSG=$'​\n'"$MSG"
    REPLY_TO_ID=$(resolve_reply_to_id)
    PAYLOAD=$(build_reply_payload "$MSG" "$REPLY_TO_ID")
    post_channel_message "$PAYLOAD"
    ;;

  ack)
    REPLY_TO_ID=$(resolve_reply_to_id)
    PAYLOAD=$(build_reply_payload "$MSG" "$REPLY_TO_ID")
    ACK_RESPONSE=$(post_channel_message "$PAYLOAD")
    MSG_ID=$(echo "$ACK_RESPONSE" | jq -r '.id // empty')
    if [[ -z "$MSG_ID" ]]; then
      echo "discord-reply.sh: ack push 실패 (message_id 누락)" >&2
      echo "$ACK_RESPONSE" >&2; exit 1
    fi

    SHORT=$(printf '%s' "$MSG" | tr '\n' ' ' | cut -c1-${THREAD_NAME_MAX_LEN})
    TS_SHORT=$(date -u +%H%M%S)
    THREAD_NAME="${SHORT} ${TS_SHORT}"

    THREAD_RESPONSE=$(start_thread_from_message "$MSG_ID" "$THREAD_NAME")
    NEW_THREAD_ID=$(echo "$THREAD_RESPONSE" | jq -r '.id // empty')
    if [[ -z "$NEW_THREAD_ID" ]]; then
      echo "discord-reply.sh: thread 생성 실패 (ack 자체는 push 됨, msg_id=${MSG_ID})" >&2
      echo "$THREAD_RESPONSE" >&2; exit 0
    fi

    atomic_write_thread_file "$NEW_THREAD_ID" "$HELPER_THREAD_FILE"
    atomic_write_thread_file "$NEW_THREAD_ID" "$LAUNCH_THREAD_FILE"
    printf '%s\n' "$NEW_THREAD_ID"
    ;;

  thread)
    PAYLOAD=$(jq -nc --arg c "$MSG" '{content: $c}')
    post_thread_message "$THREAD_ID" "$PAYLOAD"
    ;;

  auto-thread)
    # resolve chain: LAUNCH_THREAD_ID env > LAUNCH_THREAD_FILE > HELPER_THREAD_FILE.
    # (제거: helper-current-target.txt freeze — 5+분 turn 에서 stale 만 됨.)
    AUTO_THREAD_ID=""; AUTO_THREAD_SOURCE=""

    if [[ -n "${LAUNCH_THREAD_ID:-}" ]]; then
      if AUTO_THREAD_ID=$(validate_snowflake "$LAUNCH_THREAD_ID" "LAUNCH_THREAD_ID env"); then
        AUTO_THREAD_SOURCE="LAUNCH_THREAD_ID env"
      else
        AUTO_THREAD_ID=""
      fi
    fi

    if [[ -z "$AUTO_THREAD_ID" && -r "$LAUNCH_THREAD_FILE" ]]; then
      LAUNCH_RAW=$(head -1 "$LAUNCH_THREAD_FILE" 2>/dev/null | tr -d '\r\n' | tr -d ' ')
      if [[ -n "$LAUNCH_RAW" ]]; then
        if AUTO_THREAD_ID=$(validate_snowflake "$LAUNCH_RAW" "$LAUNCH_THREAD_FILE"); then
          AUTO_THREAD_SOURCE="$LAUNCH_THREAD_FILE"
        else
          AUTO_THREAD_ID=""
        fi
      fi
    fi

    # HELPER_THREAD_FILE fallback — 기존 호환을 위해 snowflake 검증 안 함
    # (test_helper_ux.test_auto_thread_mode_does_not_include_message_reference 회귀 가드).
    if [[ -z "$AUTO_THREAD_ID" ]]; then
      [[ -f "$HELPER_THREAD_FILE" ]] || { echo "discord-reply.sh: $HELPER_THREAD_FILE 없음 — auto-thread skip" >&2; exit 0; }
      HELPER_RAW=$(head -1 "$HELPER_THREAD_FILE" | tr -d '\r\n' | tr -d ' ')
      [[ -n "$HELPER_RAW" ]] || { echo "discord-reply.sh: $HELPER_THREAD_FILE 비어 있음 — auto-thread skip" >&2; exit 0; }
      AUTO_THREAD_ID="$HELPER_RAW"
      AUTO_THREAD_SOURCE="$HELPER_THREAD_FILE"
    fi

    PAYLOAD=$(jq -nc --arg c "$MSG" '{content: $c}')
    if ! post_thread_message "$AUTO_THREAD_ID" "$PAYLOAD" >/dev/null; then
      echo "discord-reply.sh: auto-thread push 실패 (thread_id=$AUTO_THREAD_ID, source=$AUTO_THREAD_SOURCE, 만료/삭제 추정) — skip" >&2
      exit 0
    fi
    ;;
esac
