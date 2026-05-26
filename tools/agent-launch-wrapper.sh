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
#
# 사용법:
#   tools/agent-launch-wrapper.sh <worktree> --title "..." [--task "..."] \
#       [--description "..."] \
#       [--echo-prompt "Agent 도구 launch 시 사용할 prompt 본문"] \
#       [--no-cycle-push] \
#       [--refresh-backlog]
#
# --refresh-backlog (2026-05-26 사용자 정정 박제):
#   launch 직전 `tools/cycle-backlog/upsert.sh <worktree>` 호출 → cycle forum 의
#   `[BACKLOG] <worktree>` thread 를 GitHub PR/issue 기반 markdown 으로 upsert.
#   sub-agent 가 launch 시점에 자기 cycle 의 백로그 thread 를 보고 작업 안 까먹게
#   하는 핵심 hook. graceful — wrapper 의 실패가 wrapper 자체 fail 시키지 않음
#   (Agent launch 자체가 우선). CYCLE_BACKLOG_REFRESH_DEFAULT=1 env 면
#   --refresh-backlog 가 default ON.
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
           [--description "..."] [--echo-prompt "..."] [--no-cycle-push] \
           [--refresh-backlog | --no-refresh-backlog]

  <worktree>            be | fe | rev | plan
  --title TEXT          cycle-status.json in_progress.title 로 기록 (필수).
  --task TEXT           선택. 사용자 친화 task 한 줄 (cycle-status 에 함께 저장).
  --description TEXT    선택. 채널 launch 알림 본문 (부재 시 title 사용).
  --echo-prompt TEXT    선택. 본문을 stdout 으로 emit (nmae 가 Agent 도구 prompt 로 사용).
  --no-cycle-push       per-cycle 채널 push 단계 skip (테스트 / 채널 미설정 환경).
  --refresh-backlog     launch 직전 tools/cycle-backlog/upsert.sh 호출 (graceful).
  --no-refresh-backlog  CYCLE_BACKLOG_REFRESH_DEFAULT=1 일 때 본 호출만 skip.

환경:
  CYCLE_STATUS_PATH               (default: ~/.mobruji/cycle-status.json)
  DISCORD_REPLY_SH                (default: ~/.mobruji/discord-reply.sh)
  AGENT_LAUNCH_NO_DISCORD=1       push 단계 완전 skip (--no-cycle-push 와 동등)
  CYCLE_BACKLOG_REFRESH_DEFAULT=1 --refresh-backlog default ON
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
REFRESH_BACKLOG="${CYCLE_BACKLOG_REFRESH_DEFAULT:-0}"

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
    --refresh-backlog)
      REFRESH_BACKLOG=1
      ;;
    --no-refresh-backlog)
      REFRESH_BACKLOG=0
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

# ─── (선택) cycle 백로그 refresh — 2026-05-26 사용자 정정 박제 ───────────────
#
# 사용자: "각 agent 가 자기 계획이 있어야지. 백로그 보면서 작업 안 까먹고 다
# 진행하고." → launch 직전 backlog forum thread 를 GitHub 상태 기반으로 upsert.
# sub-agent 가 launch 즉시 자기 cycle 의 backlog thread 를 보고 시작.
#
# graceful: backlog upsert 실패는 wrapper fail 시키지 않음 — Agent launch 가 우선.
if [[ "$REFRESH_BACKLOG" -eq 1 ]]; then
  BACKLOG_SH="$SCRIPT_DIR/cycle-backlog/upsert.sh"
  if [[ -x "$BACKLOG_SH" ]]; then
    if BACKLOG_THREAD_ID=$("$BACKLOG_SH" "$WORKTREE" 2>/dev/null); then
      if [[ -n "$BACKLOG_THREAD_ID" ]]; then
        echo "agent-launch-wrapper.sh: backlog upsert OK (worktree=$WORKTREE, thread=$BACKLOG_THREAD_ID)" >&2
      fi
    else
      echo "agent-launch-wrapper.sh: backlog upsert 실패 (graceful — Agent launch 진행)" >&2
    fi
  else
    echo "agent-launch-wrapper.sh: $BACKLOG_SH 실행 불가 — backlog refresh skip" >&2
  fi
fi

# ─── stdout: prompt / confirm 한 줄 (기존 호환) ─────────────────────────────
if [[ -n "$ECHO_PROMPT" ]]; then
  # nmae 가 Agent 도구 prompt 로 그대로 사용. trailing newline 한 줄만 보장.
  printf '%s\n' "$ECHO_PROMPT"
else
  printf 'cycle-status set-active OK (worktree=%s, title=%s) — Agent 도구 launch 진행하세요.\n' \
    "$WORKTREE" "$TITLE"
fi

# ─── per-cycle 채널 launch 알림 + thread 생성 (B2, 2026-05-24) ─────────────
#
# 흐름:
#   1) discord-reply.sh 위치 resolve. 부재 / 실행 불가 → graceful skip + warning.
#   2) discord-reply.sh --cycle-channel <worktree> --auto-ack-thread "<본문>"
#      호출. 성공 시 stdout 에 thread_id 1줄 출력 (그 안에 ack 메시지 push +
#      해당 메시지에 thread 생성도 포함).
#   3) thread_id 를 wrapper stdout 마지막에 LAUNCH_THREAD_ID=<id> 형식으로 emit.
#      nmae 가 grep 으로 추출해 sub-agent prompt 의 LAUNCH_THREAD_ID env 에 전달.
#   4) 부수효과: 채널 운영자가 launch 사실을 채널 안에서 인지 (학습 의존 ↓).
#
# 의도적 graceful 정책:
#   - --no-cycle-push 또는 AGENT_LAUNCH_NO_DISCORD=1 → skip (warning 없음).
#   - discord-reply.sh 부재 → warning + skip (테스트 / 신규 인스턴스 친화).
#   - push 자체 실패 (4xx/5xx 등) → warning + skip — cycle-status set-active 는
#     이미 성공했으니 Agent launch 자체는 진행 (학습 의존 ↓ 보다 launch 자체가 우선).

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

# launch 알림 본문 — description 우선, 없으면 title.
ANNOUNCE_BODY="${DESCRIPTION:-$TITLE}"
ANNOUNCE_LINE="sub-agent launch ($WORKTREE) — ${ANNOUNCE_BODY}"

# --auto-ack-thread 호출. stdout = thread_id (snowflake), stderr = bot warning 등.
# discord-reply.sh 가 ack 메시지 push 후 thread 를 만들어 thread_id 를 1줄 출력.
# 실패 (script 자체 exit non-zero) 시 graceful — wrapper 는 launch 차단하지 않음.
THREAD_OUT=""
if THREAD_OUT=$("$DISCORD_REPLY_SH" \
      --cycle-channel "$WORKTREE" \
      --auto-ack-thread "$ANNOUNCE_LINE" 2>/dev/null); then
  # stdout 마지막 줄 = thread_id (다른 warning 이 stdout 으로 누출되지 않게
  # discord-reply.sh 가 설계됨 — tail -1 안전).
  LAUNCH_THREAD_ID=$(printf '%s' "$THREAD_OUT" | tr -d '\r' | awk 'NF{line=$0} END{print line}')
  if [[ "$LAUNCH_THREAD_ID" =~ ^[0-9]{17,20}$ ]]; then
    printf 'LAUNCH_THREAD_ID=%s\n' "$LAUNCH_THREAD_ID"
  else
    echo "agent-launch-wrapper.sh: per-cycle push 응답에 valid thread_id 가 없음 (raw=$THREAD_OUT) — skip" >&2
  fi
else
  echo "agent-launch-wrapper.sh: per-cycle 채널 push 실패 — cycle-status 는 갱신됨, Agent launch 진행 가능" >&2
fi

exit 0
