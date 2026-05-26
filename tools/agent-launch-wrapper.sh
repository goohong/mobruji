#!/usr/bin/env bash
# tools/agent-launch-wrapper.sh — sub-agent launch 직전 cycle-status set-active +
# per-cycle 채널 launch 알림 자동 push (#1008 + B2 2026-05-24).
#
# 배경:
#   - #1008 (set-active 강제): nmae 가 매 sub-agent launch 전
#     `tools/cycle-status/update.sh <ws> set-active` 호출 룰
#     ([[feedback-cycle-status-json]]) 이 학습 의존 → 종종 누락.
#   - B2 (2026-05-24, per-cycle 채널 push 강제): nmae 가 sub-agent launch 시
#     해당 cycle 채널 (BE/FE/REV/PLAN_CHANNEL_ID) 로 launch 알림 push 룰
#     ([[feedback-per-cycle-channel-routing]]) 도 학습 의존 → 종종 누락 또는
#     기본 DIGEST 채널로 leak. 본 wrapper 가 set-active 와 동일하게 강제 push.
#   - #1106 (2026-05-26, 사용자 P0 cycle channel silence 영구 fix): 운영 cycle
#     채널이 Discord forum channel (type 15) 로 전환됐는데도 wrapper 가
#     --cycle-channel (text-channel API) 호출하다 400 reject 만 받음.
#     wrapper 가 --forum-post-auto-tag <ws> 로 분기 — forum 의 available_tags
#     fallback chain 적용 + forum thread 생성. forum_id 미설정 / push 실패 시
#     DIGEST status channel 로 graceful fallback (사이런스 재발 방지).
#
# 사용법:
#   tools/agent-launch-wrapper.sh <worktree> --title "..." [--task "..."] \
#       [--description "..."] \
#       [--echo-prompt "Agent 도구 launch 시 사용할 prompt 본문"] \
#       [--no-cycle-push]
#
# 흐름:
#   1) `tools/cycle-status/update.sh <worktree> set-active --title "..." [--task "..."]`
#      호출. exit code 비제로면 wrapper 즉시 fail (Agent launch 차단).
#   2) per-cycle 채널에 launch 알림 + thread 생성 (B2, 2026-05-24).
#      `discord-reply.sh --cycle-channel <worktree> --auto-ack-thread "..."`
#      를 호출해 BE/FE/REV/PLAN_CHANNEL_ID 채널에 launch 메시지를 push 하고
#      그 메시지에 thread 를 생성한다. thread_id 는 stdout 마지막에
#      `LAUNCH_THREAD_ID=<id>` 형식으로 emit — nmae 가
#      `LAUNCH_THREAD_ID=$(bash wrapper.sh ... | grep -oE '...')` 또는
#      `eval` 로 capture 해 sub-agent prompt 에 inherit 시킨다.
#   3) `--echo-prompt` 가 주어졌으면 그 내용을 stdout 으로 출력 (nmae 가 Agent 도구
#      input 에 그대로 붙여넣을 수 있게).
#   4) 부재 시 짧은 confirm 한 줄만 출력.
#
# stdout 계약 (호출자 parsing 용):
#   - 첫 블록: --echo-prompt 본문 또는 confirm 한 줄 (기존 호환).
#   - 마지막 블록 (push 성공 시): 2줄
#       LAUNCH_THREAD_ID=<digits>
#       CYCLE_CHANNEL_MSG_ID=<digits>
#     push 실패 / skip 시 미출력 — 호출자는 grep 으로 안전 추출.
#
# 환경:
#   CYCLE_STATUS_PATH   (default: ~/.mobruji/cycle-status.json) — update.sh 가 사용.
#   DISCORD_REPLY_SH    (default: ~/.mobruji/discord-reply.sh, fallback:
#                        $(dirname $0)/discord-daemon/discord-reply.sh) — push 진입점.
#   AGENT_LAUNCH_NO_DISCORD=1 — push 단계 완전 skip (테스트 / discord 미가용 환경).
#                              --no-cycle-push flag 와 동등.
#
# 의도:
#   - nmae 학습 부담 ↓ (한 명령으로 set-active + 채널 알림 + thread 생성 묶음).
#   - update.sh / 채널 push 누락 자동 차단 (wrapper 실패 시 launch 안 함).
#   - thread_id stdout 노출 → sub-agent 가 LAUNCH_THREAD_ID env 로 inherit 받아
#     자기 진행을 cycle thread 에 stream ([[feedback-helper-subagent-launch-thread]]).
#
# 검증:
#   tools/tests/test_agent_launch_wrapper.sh                      (기존 흐름)
#   tools/discord-daemon/tests/test_launch_wrapper_cycle_push.sh  (B2 push 흐름)
#
# 관련:
#   - spec: docs/features/autonomous-cycle-orchestration.md
#   - 메모리: [[feedback-cycle-status-json]] [[feedback-keep-4-cycles-active]]
#            [[feedback-per-cycle-channel-routing]]
#            [[feedback-helper-subagent-launch-thread]]
#   - 의존: PR #1049 (discord-reply.sh --cycle-channel + nmae-discord-push.sh --cycle)

set -euo pipefail

usage() {
  cat >&2 <<'USAGE'
usage: agent-launch-wrapper.sh <worktree> --title "..." [--task "..."] \
           [--description "..."] [--echo-prompt "..."] [--no-cycle-push]

  <worktree>            be | fe | rev | plan
  --title TEXT          cycle-status.json in_progress.title 로 기록 (필수).
  --task TEXT           선택. 사용자 친화 task 한 줄 (cycle-status 에 함께 저장).
  --description TEXT    선택. 채널 launch 알림 본문 (부재 시 title 사용).
  --echo-prompt TEXT    선택. 본문을 stdout 으로 emit (nmae 가 Agent 도구 prompt 로 사용).
  --no-cycle-push       per-cycle 채널 push 단계 skip (테스트 / 채널 미설정 환경).

환경:
  CYCLE_STATUS_PATH         (default: ~/.mobruji/cycle-status.json)
  DISCORD_REPLY_SH          (default: ~/.mobruji/discord-reply.sh)
  AGENT_LAUNCH_NO_DISCORD=1 push 단계 완전 skip (--no-cycle-push 와 동등)
USAGE
  exit 2
}

if [[ $# -lt 1 ]]; then
  usage
fi

WORKTREE="$1"
shift

case "$WORKTREE" in
  be|fe|rev|plan) ;;
  *)
    echo "ERROR: worktree must be one of be/fe/rev/plan (got: $WORKTREE)" >&2
    exit 2
    ;;
esac

TITLE=""
TASK=""
DESCRIPTION=""
ECHO_PROMPT=""
NO_CYCLE_PUSH=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --title)
      shift; [[ $# -gt 0 ]] || { echo "ERROR: --title requires value" >&2; exit 2; }
      TITLE="$1"
      ;;
    --task)
      shift; [[ $# -gt 0 ]] || { echo "ERROR: --task requires value" >&2; exit 2; }
      TASK="$1"
      ;;
    --description)
      shift; [[ $# -gt 0 ]] || { echo "ERROR: --description requires value" >&2; exit 2; }
      DESCRIPTION="$1"
      ;;
    --echo-prompt)
      shift; [[ $# -gt 0 ]] || { echo "ERROR: --echo-prompt requires value" >&2; exit 2; }
      ECHO_PROMPT="$1"
      ;;
    --no-cycle-push)
      NO_CYCLE_PUSH=1
      ;;
    -h|--help)
      usage
      ;;
    *)
      echo "ERROR: unknown arg: $1" >&2
      usage
      ;;
  esac
  shift
done

if [[ -z "$TITLE" ]]; then
  echo "ERROR: --title 필수" >&2
  exit 2
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
UPDATE_SH="$SCRIPT_DIR/cycle-status/update.sh"

if [[ ! -x "$UPDATE_SH" ]]; then
  # update.sh 가 실행 권한 없거나 부재 — 환경 문제. wrapper fail (silent skip 금지).
  echo "ERROR: $UPDATE_SH 실행 불가 (없거나 권한 없음)" >&2
  exit 3
fi

UPDATE_ARGS=("$WORKTREE" "set-active" "--title" "$TITLE")
if [[ -n "$TASK" ]]; then
  UPDATE_ARGS+=("--task" "$TASK")
fi

# update.sh 실패 시 wrapper 도 즉시 fail — Agent launch 차단 (cycle-status drift 방어).
if ! "$UPDATE_SH" "${UPDATE_ARGS[@]}" >&2; then
  echo "ERROR: cycle-status update.sh 호출 실패 — Agent launch 차단" >&2
  exit 4
fi

# ─── stdout: prompt / confirm 한 줄 (기존 호환) ─────────────────────────────
if [[ -n "$ECHO_PROMPT" ]]; then
  # nmae 가 Agent 도구 prompt 로 그대로 사용. trailing newline 한 줄만 보장.
  printf '%s\n' "$ECHO_PROMPT"
else
  printf 'cycle-status set-active OK (worktree=%s, title=%s) — Agent 도구 launch 진행하세요.\n' \
    "$WORKTREE" "$TITLE"
fi

# ─── per-cycle forum launch post 생성 (#1106, 2026-05-26 영구 fix) ──────────
#
# 사고 (사용자 P0, 2026-05-26 12:19 KST):
#   be/fe/rev/plan cycle forum 4 채널 모두 0 메시지. root cause = 이전 wrapper
#   가 --cycle-channel (text-channel API) 사용했으나 운영 cycle 채널은 forum
#   channel (type 15) — Discord 가 400 reject (`Cannot send messages in a
#   non-text channel`). 학습 의존 X, 코드 API mismatch.
#
# 영구 fix: discord-reply.sh --forum-post-auto-tag <worktree> "<title>" "<body>"
#   호출. forum_create_thread (POST /channels/{forum_id}/threads) + 자동 tag
#   fallback chain 으로 cycle launch 별 thread 1개 생성. thread_id 를
#   LAUNCH_THREAD_ID=<id> 로 emit — sub-agent 가 inherit 해 본 thread 안에
#   milestone 을 stream (--forum-comment).
#
# graceful fallback (forum_id 미설정 / push 실패):
#   1) BE/FE/REV/PLAN_FORUM_ID env 가 비어 있어 forum-post-auto-tag 가 1 exit
#      → wrapper 가 --status-channel (DIGEST text channel) 로 retry.
#      (사용자 가시성 최소 보장 — 사일런스 사고 재발 방지)
#   2) 두 push 모두 실패 → cycle-status 는 이미 갱신됐으니 Agent launch 는 진행.
#
# 의도적 graceful 정책:
#   - --no-cycle-push 또는 AGENT_LAUNCH_NO_DISCORD=1 → skip (warning 없음).
#   - discord-reply.sh 부재 → warning + skip (테스트 / 신규 인스턴스 친화).

if [[ "$NO_CYCLE_PUSH" -eq 1 ]] || [[ "${AGENT_LAUNCH_NO_DISCORD:-0}" == "1" ]]; then
  exit 0
fi

# discord-reply.sh 위치 resolve. 운영 환경에서는 ~/.mobruji/discord-reply.sh
# (symlink), 워크트리 내 단독 실행 / 테스트에서는 SCRIPT_DIR 옆 discord-daemon/.
DISCORD_REPLY_SH="${DISCORD_REPLY_SH:-${HOME:-/tmp}/.mobruji/discord-reply.sh}"
if [[ ! -x "$DISCORD_REPLY_SH" ]]; then
  ALT="$SCRIPT_DIR/discord-daemon/discord-reply.sh"
  if [[ -x "$ALT" ]]; then
    DISCORD_REPLY_SH="$ALT"
  else
    echo "agent-launch-wrapper.sh: discord-reply.sh 부재 ($DISCORD_REPLY_SH / $ALT) — per-cycle 채널 push skip" >&2
    exit 0
  fi
fi

# launch 알림 본문 — description 우선, 없으면 title. forum thread 본문은
# 운영자가 클릭해 들어왔을 때 worktree / task / launch 시각 즉시 보이도록 멀티라인.
ANNOUNCE_BODY="${DESCRIPTION:-$TITLE}"
LAUNCH_TS="$(date -Iseconds 2>/dev/null || date '+%Y-%m-%dT%H:%M:%S%z')"
FORUM_TITLE="sub-agent launch ($WORKTREE) — ${ANNOUNCE_BODY}"
# Discord forum thread name 은 100 char 제한 — 잘라 안전.
FORUM_TITLE="${FORUM_TITLE:0:99}"
FORUM_BODY="$(printf 'worktree: %s\ntitle: %s\nstarted: %s' \
  "$WORKTREE" "$ANNOUNCE_BODY" "$LAUNCH_TS")"
if [[ -n "$TASK" ]]; then
  FORUM_BODY="$(printf '%s\ntask: %s' "$FORUM_BODY" "$TASK")"
fi

# 1차: --forum-post-auto-tag (Discord forum channel POST). stderr 는 tee 로
# 보존해 fallback 결정에 사용. stdout 마지막 줄 = thread_id.
FORUM_OUT=""
FORUM_RC=0
FORUM_OUT=$("$DISCORD_REPLY_SH" \
  --forum-post-auto-tag "$WORKTREE" "$FORUM_TITLE" "$FORUM_BODY" 2>/dev/null) \
  || FORUM_RC=$?

if [[ "$FORUM_RC" -eq 0 ]]; then
  LAUNCH_THREAD_ID=$(printf '%s' "$FORUM_OUT" | tr -d '\r' | awk 'NF{line=$0} END{print line}')
  if [[ "$LAUNCH_THREAD_ID" =~ ^[0-9]{17,20}$ ]]; then
    printf 'LAUNCH_THREAD_ID=%s\n' "$LAUNCH_THREAD_ID"
    exit 0
  fi
  echo "agent-launch-wrapper.sh: forum-post 응답에 valid thread_id 가 없음 (raw=$FORUM_OUT) — DIGEST fallback 시도" >&2
else
  echo "agent-launch-wrapper.sh: forum-post 실패 (rc=$FORUM_RC) — DIGEST status channel fallback 시도" >&2
fi

# 2차 graceful fallback: --status-channel (DIGEST text channel) — 사용자 가시성
# 최소 보장. forum_id 미설정 / forum push 실패 시에도 사이런스 사고 재발 방지.
# DIGEST 채널은 text channel 이라 --auto-ack-thread (text-channel API) 정상 동작.
STATUS_OUT=""
STATUS_RC=0
STATUS_OUT=$("$DISCORD_REPLY_SH" \
  --status-channel "$FORUM_TITLE — DIGEST fallback (forum push 실패)" 2>/dev/null) \
  || STATUS_RC=$?

if [[ "$STATUS_RC" -ne 0 ]]; then
  echo "agent-launch-wrapper.sh: DIGEST fallback push 도 실패 — cycle-status 는 갱신됨, Agent launch 진행 가능" >&2
fi

exit 0
