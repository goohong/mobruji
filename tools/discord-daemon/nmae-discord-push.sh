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
#   nmae-discord-push.sh --cycle be "🚀 be #1234 launch"          (P12, 2026-05-24)
#   nmae-discord-push.sh --cycle rev --auto-ack-thread "..."        (P12, 2026-05-24)
#
# 동작:
#   기본: `discord-reply.sh --status-channel "$@"` — DIGEST_CHANNEL_ID 로 라우팅.
#   --cycle <name> 명시 시: `discord-reply.sh --cycle-channel <name> "$@"` — 해당
#     cycle 채널 (BE/FE/REV/PLAN_CHANNEL_ID) 로 라우팅. 미설정 시 DIGEST fallback.
#   두 mode 모두 자동 NO_REPLY=1 (status push 는 답장 대상 없음).
#
# 룰: CLAUDE.md §11-8 [[feedback-nmae-status-channel]]
#     (대응 거울 룰: [[feedback-helper-relay-scope]] §12-1)
#     P12 per-cycle 라우팅: CLAUDE.md §11-9 [[feedback-per-cycle-channel-routing]]

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
  echo "  nmae-discord-push.sh --cycle <be|fe|rev|plan> \"<본문>\"" >&2
  exit 1
fi

# --cycle <name> 가 가장 앞에 오면 per-cycle 라우팅 — discord-reply.sh 의
# --cycle-channel 로 forward. 그 외는 기존 --status-channel (DIGEST 강제).
# 두 mode 모두 prefix flag 라 mode flag 보다 먼저 consume.
if [[ "$1" == "--cycle" ]]; then
  if [[ $# -lt 2 ]]; then
    echo "nmae-discord-push.sh: --cycle 뒤에 cycle 이름 (be|fe|rev|plan) 이 필요합니다" >&2
    exit 1
  fi
  CYCLE_NAME="$2"
  shift 2
  exec "$DISCORD_REPLY_SH" --cycle-channel "$CYCLE_NAME" "$@"
fi

# --status-channel 을 가장 앞에 prepend — discord-reply.sh prefix flag 파서가
# mode flag 보다 먼저 consume 한다. 호출자가 다시 --status-channel 을 명시해도
# 무해 (멱등).
exec "$DISCORD_REPLY_SH" --status-channel "$@"
