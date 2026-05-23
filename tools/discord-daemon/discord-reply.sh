#!/usr/bin/env bash
# discord-reply.sh — helper 가 직접 bot REST API 로 응답 push.
#
# 사용 (이슈 #807 단순화본 + #880 thread stream 확장):
#
#   1) 본답 (메인 채널 push, 기존 호환):
#       discord-reply.sh "<응답 메시지>"
#
#   2) ack + 새 thread 생성 (#880 thread stream):
#       THREAD_ID=$(discord-reply.sh --ack "<ack 문구>")
#         → 메인 채널에 ack 메시지 push + 그 메시지에 thread 생성.
#         → stdout 으로 thread_id 만 출력 (캐치하기 쉽게).
#         → thread 이름 = ack 문구 첫 30자 + ISO timestamp 짧은 형식.
#         → 본 호출 결과는 ~/.mobruji/helper-current-thread.txt 에도 1줄로 저장
#           (helper 가 다음 turn 에 환경변수 잃어도 복구 가능).
#
#   3) thread 안 진행 stream (#880 thread stream):
#       discord-reply.sh --thread <id> "<진행 줄>"
#         → 해당 thread 에만 push (메인 채널 잡음 없음).
#
# 배포 위치 권장:
#   - 워크트리: tools/discord-daemon/discord-reply.sh (소스 진실)
#   - 운영: ~/.mobruji/discord-reply.sh (helper PATH 진입점). 심볼릭 링크 또는 복사.
#
# 동작:
#   - .env 에서 DISCORD_BOT_TOKEN 과 채널 id (MOBRUJI_CHANNEL_ID 우선 — 사용자
#     응답은 #모부르지 채널, NOTIFY_CHANNEL_ID 는 digest cron 전용 fallback) 를
#     읽어 Discord REST API 호출. bot.py 데몬과 동일한 .env 파일을 공유합니다.
#   - 메시지 본문은 jq 로 JSON-escape. 멀티라인 / 따옴표 안전.
#
# 종속:
#   - curl, jq, grep, cut, date. bot 호스트에 기본 설치되어 있는 도구만 사용.

set -euo pipefail

ENV_PATH="${DISCORD_DAEMON_ENV_PATH:-/home/mobruji/mobruji/tools/discord-daemon/.env}"
if [[ ! -f "$ENV_PATH" ]]; then
  echo "discord-reply.sh: .env 파일 없음: $ENV_PATH" >&2
  exit 1
fi

TOKEN=$(grep -E '^DISCORD_BOT_TOKEN=' "$ENV_PATH" | cut -d= -f2- | tr -d '"' | tr -d "'")
if [[ -z "$TOKEN" ]]; then
  echo "discord-reply.sh: DISCORD_BOT_TOKEN 비어 있음" >&2
  exit 1
fi

# MOBRUJI_CHANNEL_ID 우선 (helper raw 응답 = 메인 #모부르지), NOTIFY_CHANNEL_ID 는 digest 전용 fallback.
CHANNEL=$(grep -E '^MOBRUJI_CHANNEL_ID=' "$ENV_PATH" | cut -d= -f2- | tr -d '"' | tr -d "'" | head -1)
if [[ -z "$CHANNEL" ]]; then
  CHANNEL=$(grep -E '^NOTIFY_CHANNEL_ID=' "$ENV_PATH" | cut -d= -f2- | tr -d '"' | tr -d "'" | head -1)
fi
if [[ -z "$CHANNEL" ]]; then
  echo "discord-reply.sh: NOTIFY_CHANNEL_ID / MOBRUJI_CHANNEL_ID 둘 다 비어 있음" >&2
  exit 1
fi

HELPER_THREAD_FILE="${HELPER_THREAD_FILE:-$HOME/.mobruji/helper-current-thread.txt}"
THREAD_NAME_MAX_LEN=30

# ─── mode dispatch ────────────────────────────────────────────────────────────

MODE="reply"
THREAD_ID=""
MSG=""

if [[ $# -eq 0 ]]; then
  echo "discord-reply.sh: 인자 부족 — 사용법:" >&2
  echo "  discord-reply.sh \"<메시지>\"" >&2
  echo "  discord-reply.sh --ack \"<ack 문구>\"" >&2
  echo "  discord-reply.sh --thread <id> \"<진행 줄>\"" >&2
  exit 1
fi

case "$1" in
  --ack)
    MODE="ack"
    if [[ $# -lt 2 ]]; then
      echo "discord-reply.sh: --ack 뒤에 ack 문구가 필요합니다" >&2
      exit 1
    fi
    MSG="$2"
    ;;
  --thread)
    MODE="thread"
    if [[ $# -lt 3 ]]; then
      echo "discord-reply.sh: --thread <id> <메시지> 형태로 입력해주세요" >&2
      exit 1
    fi
    THREAD_ID="$2"
    MSG="$3"
    ;;
  --*)
    echo "discord-reply.sh: 알 수 없는 옵션 $1" >&2
    exit 1
    ;;
  *)
    MODE="reply"
    MSG="$1"
    ;;
esac

if [[ -z "$MSG" ]]; then
  echo "discord-reply.sh: 빈 메시지 — 호출 의도 확인 필요" >&2
  exit 1
fi

# ─── helpers ──────────────────────────────────────────────────────────────────

# JSON escape via jq -Rn (raw input + null入). 단일 인자에 멀티라인/따옴표 안전.
json_escape() {
  jq -Rn --arg s "$1" '$s'
}

# 메인 채널에 메시지 push. stdout = REST 응답 raw (JSON).
post_channel_message() {
  local body="$1"
  curl -sS -X POST "https://discord.com/api/v10/channels/${CHANNEL}/messages" \
    -H "Authorization: Bot ${TOKEN}" \
    -H "Content-Type: application/json" \
    -d "$body"
}

# 메시지에서 thread 시작 (해당 메시지 아래에 붙는 thread).
# Discord REST: POST /channels/{channel_id}/messages/{message_id}/threads
# response.id = thread id (TEXTUAL_THREAD type 11).
start_thread_from_message() {
  local message_id="$1"
  local thread_name="$2"
  local body
  body=$(jq -nc --arg n "$thread_name" '{name: $n, auto_archive_duration: 1440}')
  curl -sS -X POST \
    "https://discord.com/api/v10/channels/${CHANNEL}/messages/${message_id}/threads" \
    -H "Authorization: Bot ${TOKEN}" \
    -H "Content-Type: application/json" \
    -d "$body"
}

# thread 안 push. thread 자체가 channel snowflake 처럼 동작 (Discord API spec).
post_thread_message() {
  local thread_id="$1"
  local body="$2"
  curl -sS -X POST "https://discord.com/api/v10/channels/${thread_id}/messages" \
    -H "Authorization: Bot ${TOKEN}" \
    -H "Content-Type: application/json" \
    -d "$body"
}

# ─── mode 실행 ────────────────────────────────────────────────────────────────

case "$MODE" in
  reply)
    PAYLOAD=$(jq -nc --arg c "$MSG" '{content: $c}')
    post_channel_message "$PAYLOAD"
    ;;

  ack)
    # 1) ack 메시지 push.
    PAYLOAD=$(jq -nc --arg c "$MSG" '{content: $c}')
    ACK_RESPONSE=$(post_channel_message "$PAYLOAD")
    MSG_ID=$(echo "$ACK_RESPONSE" | jq -r '.id // empty')
    if [[ -z "$MSG_ID" ]]; then
      echo "discord-reply.sh: ack push 실패 (message_id 누락)" >&2
      echo "$ACK_RESPONSE" >&2
      exit 1
    fi

    # 2) thread 이름 = ack 첫 30자 + ISO timestamp 짧은 형식 (HHMMSS).
    #    멀티라인 ack 는 첫 줄만, 너무 길면 30자에서 잘라 ellipsis.
    SHORT=$(printf '%s' "$MSG" | tr '\n' ' ' | cut -c1-${THREAD_NAME_MAX_LEN})
    TS_SHORT=$(date -u +%H%M%S)
    THREAD_NAME="${SHORT} ${TS_SHORT}"

    # 3) thread 생성.
    THREAD_RESPONSE=$(start_thread_from_message "$MSG_ID" "$THREAD_NAME")
    NEW_THREAD_ID=$(echo "$THREAD_RESPONSE" | jq -r '.id // empty')
    if [[ -z "$NEW_THREAD_ID" ]]; then
      # thread 생성 실패해도 ack push 자체는 성공. stdout 빈 줄 + stderr 에 사유.
      echo "discord-reply.sh: thread 생성 실패 (ack 자체는 push 됨, msg_id=${MSG_ID})" >&2
      echo "$THREAD_RESPONSE" >&2
      exit 0
    fi

    # 4) 현재 thread 파일에 1줄 저장 (helper 가 다음 turn 에 환경변수 잃어도 복구).
    mkdir -p "$(dirname "$HELPER_THREAD_FILE")"
    printf '%s\n' "$NEW_THREAD_ID" > "$HELPER_THREAD_FILE"

    # 5) stdout 으로 thread id 만 출력.
    printf '%s\n' "$NEW_THREAD_ID"
    ;;

  thread)
    PAYLOAD=$(jq -nc --arg c "$MSG" '{content: $c}')
    post_thread_message "$THREAD_ID" "$PAYLOAD"
    ;;
esac
