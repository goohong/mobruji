#!/usr/bin/env bash
# discord-reply.sh — helper 가 직접 bot REST API 로 응답 push.
#
# 사용 (이슈 #807 단순화본):
#   discord-reply.sh "<응답 메시지>"
#
# 배포 위치 권장:
#   - 워크트리: tools/discord-daemon/discord-reply.sh (소스 진실)
#   - 운영: ~/.mobruji/discord-reply.sh (helper PATH 진입점). 심볼릭 링크 또는 복사.
#
# 동작:
#   - .env 에서 DISCORD_BOT_TOKEN 과 채널 id (MOBRUJI_CHANNEL_ID 우선 — 사용자
#     응답은 #모부르지 채널, NOTIFY_CHANNEL_ID 는 digest cron 전용 fallback) 를
#     읽어 Discord REST `POST /channels/{id}/messages` 호출. bot.py 데몬과
#     동일한 .env 파일을 공유합니다.
#   - 메시지 본문은 jq 로 JSON-escape. 멀티라인 / 따옴표 안전.
#
# 종속:
#   - curl, jq, grep, cut. bot 호스트에 기본 설치되어 있는 도구만 사용.

set -euo pipefail

MSG="${1:?msg required — 사용법: discord-reply.sh \"<메시지>\"}"

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

PAYLOAD=$(jq -nc --arg c "$MSG" '{content: $c}')

curl -sS -X POST "https://discord.com/api/v10/channels/${CHANNEL}/messages" \
  -H "Authorization: Bot ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d "$PAYLOAD"
