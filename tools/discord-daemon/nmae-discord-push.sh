#!/usr/bin/env bash
# nmae-discord-push.sh — nmae 사이클 알림 전용 discord-reply.sh wrapper (#1036).
#
# 배경 (2026-05-24 채널 분리 사고):
#   nmae sub-agent launch / 완료 / cycle alert 메시지 (예: "🚀 plan(temp)
#   sub-agent launch...") 가 사용자 응답 채널 (#모부르지 = MOBRUJI_CHANNEL_ID)
#   으로 leak. 사용자가 "이런게 모부르지 채널로 오니" 정정. helper-relay-scope
#   룰 (PR #1034) 의 nmae 측 거울 룰.
#
# 단일 책임:
#   nmae 의 모든 status push 가 DIGEST_CHANNEL_ID 로 가도록 강제하는 thin
#   wrapper. 호출자는 discord-reply.sh 의 모든 mode/flag 를 자유롭게 forward.
#
# 사용:
#   nmae-discord-push.sh "🚀 be #1234 launch"
#   nmae-discord-push.sh --auto-ack-thread "🚀 be sub-agent: PR #1234"
#   nmae-discord-push.sh --thread <id> "✅ be #1234 머지 완료"
#   nmae-discord-push.sh --auto-thread "[done] rev e2e 단계 2 통과"
#
# 동작:
#   discord-reply.sh --status-channel "$@" 와 동일.
#   --status-channel flag 는 채널을 DIGEST_CHANNEL_ID (또는 backward-compat
#   NOTIFY_CHANNEL_ID) 로 override + 자동 NO_REPLY=1 강제 (status 는 답장
#   대상 메시지가 없음).
#
# 룰: CLAUDE.md §11-8 [[feedback-nmae-status-channel]]
#     (대응 거울 룰: [[feedback-helper-relay-scope]] §12-1)

set -euo pipefail

DISCORD_REPLY_SH="${DISCORD_REPLY_SH:-/home/mobruji/.mobruji/discord-reply.sh}"

if [[ ! -x "$DISCORD_REPLY_SH" ]]; then
  echo "nmae-discord-push.sh: discord-reply.sh 실행 불가: $DISCORD_REPLY_SH" >&2
  echo "  (DISCORD_REPLY_SH env 로 경로 override 가능)" >&2
  exit 1
fi

if [[ $# -eq 0 ]]; then
  echo "nmae-discord-push.sh: 인자 부족 — 사용법:" >&2
  echo "  nmae-discord-push.sh \"<status 본문>\"" >&2
  echo "  nmae-discord-push.sh --auto-ack-thread \"<thread 시작 문구>\"" >&2
  echo "  nmae-discord-push.sh --thread <id> \"<진행 줄>\"" >&2
  echo "  nmae-discord-push.sh --auto-thread \"<진행 줄>\"" >&2
  exit 1
fi

# --status-channel 을 가장 앞에 prepend — discord-reply.sh prefix flag 파서가
# mode flag 보다 먼저 consume 한다. 호출자가 다시 --status-channel 을 명시해도
# 무해 (멱등).
exec "$DISCORD_REPLY_SH" --status-channel "$@"
