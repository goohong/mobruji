#!/usr/bin/env bash
# discord-reply.sh — helper 가 직접 bot REST API 로 응답 push.
#
# 사용 (이슈 #807 단순화본 + #880 thread stream 확장 + #946 reply mode + #960 ack reply 확장 + #963 ack 단순화):
#
#   1) 본답 (메인 채널 push, 기존 호환):
#       discord-reply.sh "<응답 메시지>"
#         → 본답 모드는 자동으로 leading ZWSP(U+200B) + \n 를 메시지 앞에
#           prepend 한다 (#921, 2026-05-24). 이유: jq escape 가 leading
#           newline 을 strip 해서 ack 와 본답이 Discord 채널에서 시각적으로
#           붙어 보이는 문제 영구 해결.
#         → 본답 모드는 또한 `~/.mobruji/last-user-msg-id.txt` (bot.py 가
#           on_message 시 atomic write — #946) 를 읽어 Discord REST API
#           `message_reference` 를 payload 에 포함시켜 자동으로 사용자
#           메시지에 reply (답장) 형태로 push 한다. 파일 부재 / 빈 값
#           (cron digest 등) 은 graceful standalone fallback.
#           --no-reply 플래그 또는 LAST_USER_MSG_ID_FILE=/dev/null 로 disable.
#
#   2) [DEPRECATED #963] ack + 새 thread 생성 (#880 thread stream + #960 ack reply):
#       THREAD_ID=$(discord-reply.sh --ack "<ack 문구>")
#         → helper 본체 ack push 는 폐지됐다 (bot.py auto-ack 가 1초 ack 역할).
#           본 mode 호출 시 stderr 에 deprecation warning 을 출력하되 동작 자체는
#           유지 (운영 호환성). 신규 helper 흐름은 ack 없이 본답으로 직행 — 채널에
#           messages 가 2건 (auto-ack 1 + 본답 1) 만 남아 가독성 ↑.
#           장시간 작업 thread 가 필요하면 `--auto-ack-thread` 사용 (의미가 ack 가
#           아니라 "작업 시작 메시지 + thread" 로 redefine — #963).
#         → 메인 채널에 ack 메시지 push + 그 메시지에 thread 생성.
#         → stdout 으로 thread_id 만 출력 (캐치하기 쉽게).
#         → thread 이름 = ack 문구 첫 30자 + ISO timestamp 짧은 형식.
#         → 본 호출 결과는 ~/.mobruji/helper-current-thread.txt 에도 1줄로 저장
#           (helper 가 다음 turn 에 환경변수 잃어도 복구 가능).
#         → ack push 자체도 본답 모드와 동일하게 `message_reference` 자동
#           적용 — 사용자 메시지에 답장 형태로 ack 가 붙어 어떤 메시지에 대한
#           ack 인지 시각적으로 식별 가능 (#960, 2026-05-24).
#           --no-reply flag 로 disable 가능.
#
#   3) thread 안 진행 stream (#880 thread stream):
#       discord-reply.sh --thread <id> "<진행 줄>"
#         → 해당 thread 에만 push (메인 채널 잡음 없음).
#         → thread 자체는 사용자 메시지에 붙은 컨텍스트 안에서 흐르므로
#           message_reference 미적용 (회귀 가드 테스트 존재).
#
#   4) auto-ack + thread (#947 helper 자동 활용 + #960 ack reply + #963 thread 시작 용도):
#       discord-reply.sh --auto-ack-thread "<작업 시작 문구>"
#         → 동작은 --ack 와 동일. helper 본체 룰 (CLAUDE.md §11) 직설 명명.
#         → #963 이후 본 mode 는 ack 가 아니라 **작업 thread 시작** 의미.
#           장시간 작업 (위임/조사/PR) 일 때만 호출해 진행 thread 를 만든다.
#           단순 즉답 turn 에선 호출 금지 — bot auto-ack 만으로 충분.
#         → ack push + thread 생성 + thread_id 를 ~/.mobruji/helper-current-thread.txt
#           에 atomic 저장 + stdout 으로 thread_id 출력.
#         → helper 가 stdout 캡쳐를 잊어도 다음 --auto-thread 호출이 파일에서 복구.
#         → ack push 도 `message_reference` 자동 적용 (#960).
#
#   5) auto-thread (#947 helper 자동 활용):
#       discord-reply.sh --auto-thread "<진행 줄>"
#         → ~/.mobruji/helper-current-thread.txt 자동 읽어 --thread <id> 처럼 동작.
#         → 파일 없거나 비어 있으면 graceful skip (exit 0, stderr warning).
#         → thread 만료 (24h archive) / 삭제 시 Discord 404 → stderr warning + exit 0
#           (helper turn 깨지지 않게).
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

# 종속 도구 가드 — bot 호스트 외 환경(예: 신규 NCP 인스턴스)에서 조용히 실패하지
# 않도록 명시적으로 점검. set -e 와 별개로 사람 친화 메시지 제공.
command -v curl >/dev/null 2>&1 || { echo "discord-reply.sh: curl 미설치" >&2; exit 1; }
command -v jq   >/dev/null 2>&1 || { echo "discord-reply.sh: jq 미설치"   >&2; exit 1; }

ENV_PATH="${DISCORD_DAEMON_ENV_PATH:-/home/mobruji/mobruji/tools/discord-daemon/.env}"
if [[ ! -f "$ENV_PATH" ]]; then
  echo "discord-reply.sh: .env 파일 없음: $ENV_PATH" >&2
  exit 1
fi

# env 값 추출 헬퍼 — KEY 받아 값 1줄 echo. 따옴표 / CRLF 모두 strip 해서
# Windows 에서 편집된 .env (CRLF) 도 안전하게 받는다. head -1 로 같은 키
# 중복 정의 시 첫 줄만.
read_env_value() {
  local key="$1"
  grep -E "^${key}=" "$ENV_PATH" \
    | head -1 \
    | cut -d= -f2- \
    | tr -d '\r' \
    | tr -d '"' \
    | tr -d "'"
}

TOKEN=$(read_env_value DISCORD_BOT_TOKEN)
if [[ -z "$TOKEN" ]]; then
  echo "discord-reply.sh: DISCORD_BOT_TOKEN 비어 있음" >&2
  exit 1
fi

# MOBRUJI_CHANNEL_ID 우선 (helper raw 응답 = 메인 #모부르지), NOTIFY_CHANNEL_ID 는 digest 전용 fallback.
CHANNEL=$(read_env_value MOBRUJI_CHANNEL_ID)
if [[ -z "$CHANNEL" ]]; then
  CHANNEL=$(read_env_value NOTIFY_CHANNEL_ID)
fi
if [[ -z "$CHANNEL" ]]; then
  echo "discord-reply.sh: NOTIFY_CHANNEL_ID / MOBRUJI_CHANNEL_ID 둘 다 비어 있음" >&2
  exit 1
fi

# $HOME 이 unbound 환경 (예: 테스트 / systemd unit 일부) 에서 set -u 로 죽지
# 않도록 명시적 default. 운영에서 $HOME 은 항상 존재 — 이 default 는 안전망.
HELPER_THREAD_FILE="${HELPER_THREAD_FILE:-${HOME:-/tmp}/.mobruji/helper-current-thread.txt}"
THREAD_NAME_MAX_LEN=30

# helper 본답/ack → 사용자 메시지 reply (#946 본답, #960 ack 확장, 2026-05-24).
# bot.py on_message 가 사용자 메시지 forward 시 이 파일에 message_id 를
# atomic write. 본답/ack 모드가 읽어 Discord `message_reference` payload 에 포함.
# 파일 부재 / 빈 값 / 비숫자 → graceful standalone (REST API 가 그래도 본답/ack
# 메시지는 push 되도록).
LAST_USER_MSG_ID_FILE="${LAST_USER_MSG_ID_FILE:-${HOME:-/tmp}/.mobruji/last-user-msg-id.txt}"

# Discord API retry 설정 (#911 G-6).
# 429 (Rate Limited) / 5xx (Server Error) 응답을 곧이곧대로 무시하지 않고
# Discord 가 권장하는 retry_after 또는 exponential backoff 로 재시도한다.
# 환경변수로 외부화 — 테스트에서 max=1, base=0 으로 강제해 fast fail 가능.
DISCORD_RETRY_MAX="${DISCORD_RETRY_MAX:-3}"
DISCORD_RETRY_BASE_SEC="${DISCORD_RETRY_BASE_SEC:-1}"

# ─── mode dispatch ────────────────────────────────────────────────────────────

MODE="reply"
THREAD_ID=""
MSG=""
# --no-reply: 본답 모드에서 message_reference 비활성화. 운영 환경에서
# last-user-msg-id 가 있어도 standalone push 하고 싶을 때 (예: cron 직접 호출).
NO_REPLY=0

if [[ $# -eq 0 ]]; then
  echo "discord-reply.sh: 인자 부족 — 사용법:" >&2
  echo "  discord-reply.sh \"<메시지>\"" >&2
  echo "  discord-reply.sh [--no-reply] \"<메시지>\"" >&2
  echo "  discord-reply.sh --ack \"<ack 문구>\"" >&2
  echo "  discord-reply.sh --thread <id> \"<진행 줄>\"" >&2
  echo "  discord-reply.sh --auto-ack-thread \"<ack 문구>\"" >&2
  echo "  discord-reply.sh --auto-thread \"<진행 줄>\"" >&2
  exit 1
fi

# --no-reply 는 선택적 prefix — 다른 flag 보다 먼저 consume.
if [[ "$1" == "--no-reply" ]]; then
  NO_REPLY=1
  shift
  if [[ $# -eq 0 ]]; then
    echo "discord-reply.sh: --no-reply 뒤에 메시지가 필요합니다" >&2
    exit 1
  fi
fi

case "$1" in
  --ack|--auto-ack-thread)
    # --auto-ack-thread 는 --ack 와 동일 동작 — helper 본체 룰 가독성용 alias (#947).
    # #963: helper 본체 ack push 폐지. `--ack` (bare) 호출은 deprecated — stderr warning.
    #       `--auto-ack-thread` 는 thread 시작 용도로 redefine 됐기에 warning 없음.
    if [[ "$1" == "--ack" ]]; then
      echo "discord-reply.sh: --ack mode 는 deprecated (#963) — helper 본체 ack push 폐지. bot.py auto-ack 가 1초 ack 역할. 작업 thread 가 필요하면 --auto-ack-thread 사용." >&2
    fi
    MODE="ack"
    if [[ $# -lt 2 ]]; then
      echo "discord-reply.sh: $1 뒤에 문구가 필요합니다" >&2
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
  --auto-thread)
    # helper-current-thread.txt 자동 읽어 thread push (#947).
    MODE="auto-thread"
    if [[ $# -lt 2 ]]; then
      echo "discord-reply.sh: --auto-thread 뒤에 진행 줄이 필요합니다" >&2
      exit 1
    fi
    MSG="$2"
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

# JSON escape via jq -Rn (raw input + null입). 단일 인자에 멀티라인/따옴표 안전.
json_escape() {
  jq -Rn --arg s "$1" '$s'
}

# Discord REST 호출 + 429/5xx retry (#911 G-6).
#
# 인자: METHOD URL BODY
# stdout: 마지막 시도의 응답 본문 (JSON 가정). 호출부는 기존처럼 jq 로 파싱.
# 종료코드: 마지막 시도가 2xx 면 0, 그 외면 1.
#
# 정책:
#   - 2xx → 즉시 반환 (0).
#   - 429 → JSON `retry_after` (Discord 권고) 가 있으면 그 초만큼 sleep, 없으면
#           DISCORD_RETRY_BASE_SEC 사용. retry_after 는 정수/소수 모두 허용.
#   - 5xx → exponential backoff (base * 2^(i-1)).
#   - 그 외 (4xx 등) → retry 무의미 → 즉시 종료 (1) + 응답 그대로 반환.
#   - DISCORD_RETRY_MAX 회 시도 후 실패 → stderr warning + 1 반환.
discord_curl_with_retry() {
  local method="$1"
  local url="$2"
  local body="$3"
  local response status payload retry_after sleep_sec i

  for ((i = 1; i <= DISCORD_RETRY_MAX; i++)); do
    # -w '\n%{http_code}' 로 마지막 줄에 status code append.
    response=$(curl -sS -X "$method" "$url" \
      -H "Authorization: Bot ${TOKEN}" \
      -H "Content-Type: application/json" \
      -w $'\n%{http_code}' \
      -d "$body" 2>/dev/null || true)

    status="${response##*$'\n'}"
    payload="${response%$'\n'*}"

    if [[ "$status" =~ ^2[0-9][0-9]$ ]]; then
      printf '%s' "$payload"
      return 0
    fi

    if [[ "$status" == "429" ]]; then
      # Discord 권고 retry_after — JSON 본문에 초 단위로 옴.
      retry_after=$(printf '%s' "$payload" \
        | jq -r '.retry_after // empty' 2>/dev/null || true)
      if [[ -z "$retry_after" || "$retry_after" == "null" ]]; then
        sleep_sec="$DISCORD_RETRY_BASE_SEC"
      else
        sleep_sec="$retry_after"
      fi
      echo "discord-reply.sh: 429 rate limit — retry ${i}/${DISCORD_RETRY_MAX} after ${sleep_sec}s" >&2
      sleep "$sleep_sec"
      continue
    fi

    if [[ "$status" =~ ^5[0-9][0-9]$ ]]; then
      sleep_sec=$(awk -v base="$DISCORD_RETRY_BASE_SEC" -v exp="$((i - 1))" \
        'BEGIN { printf "%.2f", base * (2 ^ exp) }')
      echo "discord-reply.sh: ${status} server error — retry ${i}/${DISCORD_RETRY_MAX} after ${sleep_sec}s" >&2
      sleep "$sleep_sec"
      continue
    fi

    # 4xx 등 — retry 무의미.
    printf '%s' "$payload"
    return 1
  done

  echo "discord-reply.sh: ${DISCORD_RETRY_MAX} 회 retry 실패 (last status=${status})" >&2
  printf '%s' "$payload"
  return 1
}

# 메인 채널에 메시지 push. stdout = REST 응답 raw (JSON).
post_channel_message() {
  local body="$1"
  discord_curl_with_retry POST \
    "https://discord.com/api/v10/channels/${CHANNEL}/messages" \
    "$body"
}

# 메시지에서 thread 시작 (해당 메시지 아래에 붙는 thread).
# Discord REST: POST /channels/{channel_id}/messages/{message_id}/threads
# response.id = thread id (TEXTUAL_THREAD type 11).
start_thread_from_message() {
  local message_id="$1"
  local thread_name="$2"
  local body
  body=$(jq -nc --arg n "$thread_name" '{name: $n, auto_archive_duration: 1440}')
  discord_curl_with_retry POST \
    "https://discord.com/api/v10/channels/${CHANNEL}/messages/${message_id}/threads" \
    "$body"
}

# thread 안 push. thread 자체가 channel snowflake 처럼 동작 (Discord API spec).
post_thread_message() {
  local thread_id="$1"
  local body="$2"
  discord_curl_with_retry POST \
    "https://discord.com/api/v10/channels/${thread_id}/messages" \
    "$body"
}

# reply 대상 message_id 해석 — last-user-msg-id.txt 읽고 snowflake 검증 (#946, #960).
#
# 출력: stdout 으로 message_id (정수 문자열) 또는 빈 문자열.
# 정책:
#   - $NO_REPLY=1 → 빈 문자열 (명시적 disable).
#   - 파일 부재 / 빈 값 / 비숫자 → 빈 문자열 (graceful standalone fallback).
#   - 유효 snowflake (정수) → 그 값 그대로.
#
# bare body + ack 모드 모두 동일 로직을 공유하기 위해 함수로 분리.
resolve_reply_to_id() {
  local raw_id=""
  if [[ "$NO_REPLY" -eq 0 && -r "$LAST_USER_MSG_ID_FILE" ]]; then
    raw_id=$(head -1 "$LAST_USER_MSG_ID_FILE" 2>/dev/null | tr -d '[:space:]' || true)
    # Discord snowflake = 정수 (보통 17~20자리). 비숫자/빈 값은 무시.
    if [[ "$raw_id" =~ ^[0-9]+$ ]]; then
      printf '%s' "$raw_id"
      return 0
    fi
  fi
  printf ''
}

# reply 적용 payload 빌더 — message_id 있으면 message_reference 포함, 없으면 단순 content (#946, #960).
#
# 인자: CONTENT REPLY_TO_ID
# 출력: jq -nc 로 빌드한 JSON payload (stdout 1줄).
#
# fail_if_not_exists: false — referenced message 가 삭제됐어도 본 메시지
# 자체는 정상 push (standalone 으로 표시). Discord 권장 패턴.
build_reply_payload() {
  local content="$1"
  local reply_to_id="$2"
  if [[ -n "$reply_to_id" ]]; then
    jq -nc \
      --arg c "$content" \
      --arg mid "$reply_to_id" \
      --arg cid "$CHANNEL" \
      '{
        content: $c,
        message_reference: {
          message_id: $mid,
          channel_id: $cid,
          fail_if_not_exists: false
        }
      }'
  else
    jq -nc --arg c "$content" '{content: $c}'
  fi
}

# helper-current-thread.txt atomic write (#911 G-5).
#
# 동시에 두 helper turn 이 --ack 를 호출하면 read/write 순서가 어긋나 마지막
# write 가 부분 파일을 남길 수 있다 (또는 두 writer 가 동일 path 에 동시에
# write 하면 reader 가 일부만 본 채로 thread id 를 잘못 파싱).
# `mktemp + mv` 패턴으로 같은 파일시스템 안에서 atomic rename 을 보장한다.
# (POSIX rename(2) atomicity — 같은 디렉터리/파일시스템 내부 필수).
atomic_write_thread_file() {
  local thread_id="$1"
  local thread_file="$2"
  local dir tmp
  dir="$(dirname "$thread_file")"
  mkdir -p "$dir"
  # mktemp 를 동일 디렉터리에 만들어 cross-fs rename 회피.
  tmp=$(mktemp "${thread_file}.XXXXXX")
  printf '%s\n' "$thread_id" > "$tmp"
  mv "$tmp" "$thread_file"
}

# ─── mode 실행 ────────────────────────────────────────────────────────────────

case "$MODE" in
  reply)
    # 본답 모드: 자동 leading ZWSP(U+200B) + \n prepend (#921, 2026-05-24).
    # 이유: jq escape 가 leading/trailing \n strip 해서 ack 메시지와 본답
    # 메시지가 Discord 채널에서 시각적으로 붙어 보이는 문제 영구 해결.
    # ZWSP 는 invisible character — visual padding 없이 빈 줄 효과를 보장.
    # ack / thread 모드는 짧은 단발성 push 라 미적용.
    MSG=$'​\n'"$MSG"

    # #946: 사용자 메시지에 reply (답장) 형태로 push.
    # bot.py 가 on_message 시 atomic write 한 LAST_USER_MSG_ID_FILE 에서
    # message_id 를 읽어 Discord REST `message_reference` 에 포함.
    # 파일 부재 / 빈 값 / 비숫자 → graceful standalone push (legacy 호환).
    # --no-reply flag 시에도 standalone.
    REPLY_TO_ID=$(resolve_reply_to_id)
    PAYLOAD=$(build_reply_payload "$MSG" "$REPLY_TO_ID")
    post_channel_message "$PAYLOAD"
    ;;

  ack)
    # #960: ack 도 사용자 메시지에 reply (답장) 형태로 push — 어떤 메시지에
    # 대한 ack 인지 시각적 식별. bare body 모드와 동일한 헬퍼 공유.
    REPLY_TO_ID=$(resolve_reply_to_id)
    # 1) ack 메시지 push.
    PAYLOAD=$(build_reply_payload "$MSG" "$REPLY_TO_ID")
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
    #    동시 --ack 호출 race 회피를 위해 mktemp+mv atomic write (#911 G-5).
    atomic_write_thread_file "$NEW_THREAD_ID" "$HELPER_THREAD_FILE"

    # 5) stdout 으로 thread id 만 출력.
    printf '%s\n' "$NEW_THREAD_ID"
    ;;

  thread)
    PAYLOAD=$(jq -nc --arg c "$MSG" '{content: $c}')
    post_thread_message "$THREAD_ID" "$PAYLOAD"
    ;;

  auto-thread)
    # helper-current-thread.txt 자동 읽기 (#947 helper 자동 활용).
    # 파일 없거나 비어 있으면 graceful skip — helper turn 안 깨지게.
    if [[ ! -f "$HELPER_THREAD_FILE" ]]; then
      echo "discord-reply.sh: $HELPER_THREAD_FILE 없음 — auto-thread skip" >&2
      exit 0
    fi
    AUTO_THREAD_ID=$(head -1 "$HELPER_THREAD_FILE" | tr -d '\r\n' | tr -d ' ')
    if [[ -z "$AUTO_THREAD_ID" ]]; then
      echo "discord-reply.sh: $HELPER_THREAD_FILE 비어 있음 — auto-thread skip" >&2
      exit 0
    fi
    PAYLOAD=$(jq -nc --arg c "$MSG" '{content: $c}')
    # post_thread_message 의 retry wrapper 가 4xx 면 1 반환. thread 만료 / 삭제
    # 시 Discord 가 404 — helper turn 깨지지 않게 stderr warning + exit 0 으로
    # graceful 처리.
    if ! post_thread_message "$AUTO_THREAD_ID" "$PAYLOAD" >/dev/null; then
      echo "discord-reply.sh: auto-thread push 실패 (thread_id=$AUTO_THREAD_ID, 만료/삭제 추정) — skip" >&2
      exit 0
    fi
    ;;
esac
