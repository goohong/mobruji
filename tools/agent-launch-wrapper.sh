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

# spec: docs/features/cycle-forum-operation.md §5-3 — --register-pending mode 신설.
# nmae 가 backlog 등록 시 호출. 첫 args = --register-pending 이면 mode 분기.
MODE="launch"
if [[ "${1:-}" == "--register-pending" ]]; then
  MODE="register-pending"
  shift
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
# spec: cycle-forum-operation.md §5-4 — 기존 🟡 대기 thread 재사용 시 명시.
# wrapper 가 retag 🟡 → ⏳ + 본문 [x] launch update.
PENDING_THREAD_ID=""
DIRECTIVE_ID=""

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
    --pending-thread-id)
      shift; [[ $# -gt 0 ]] || { echo "ERROR: --pending-thread-id requires value" >&2; exit 2; }
      PENDING_THREAD_ID="$1"
      ;;
    --directive-id)
      shift; [[ $# -gt 0 ]] || { echo "ERROR: --directive-id requires value" >&2; exit 2; }
      DIRECTIVE_ID="$1"
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

# spec: docs/features/cycle-forum-operation.md §5-3
# register-pending mode = nmae 가 backlog 등록 시 호출. cycle forum 에 🟡 대기
# thread 신설 + template body. set-active / cycle-status update 는 skip (launch 안 함).
# stdout: PENDING_THREAD_ID=<id> — nmae 가 cache (다음 launch 시 --pending-thread-id 전달).
if [[ "$MODE" == "register-pending" ]]; then
  if [[ -z "$TITLE" ]]; then
    echo "ERROR: --register-pending mode 는 --title 필수" >&2
    exit 2
  fi

  # discord-reply.sh resolve.
  DISCORD_REPLY_SH="${DISCORD_REPLY_SH:-${HOME:-/tmp}/.mobruji/discord-reply.sh}"
  if [[ ! -x "$DISCORD_REPLY_SH" ]]; then
    ALT="$SCRIPT_DIR/discord-daemon/discord-reply.sh"
    if [[ -x "$ALT" ]]; then
      DISCORD_REPLY_SH="$ALT"
    else
      echo "ERROR: discord-reply.sh 부재 — pending thread 신설 불가" >&2
      exit 5
    fi
  fi

  PENDING_TS_KST="$(TZ='Asia/Seoul' date '+%Y-%m-%d %H:%M KST')"
  PENDING_DESC="${DESCRIPTION:-$TITLE}"

  _build_pending_body() {
    local _cycle="$1" _title="$2" _desc="$3" _ts="$4" _did="$5"
    local _did_line=""
    if [[ -n "$_did" ]]; then
      _did_line="
- directive: \`${_did}\`"
    fi
    cat <<EOF
🛠️ **${_title}**

💬 작업 / 의도
${_desc}

🆔 사이클: \`${_cycle}\` · 📋 PR: #—
🕐 launch: 대기 중 · ⏱️ 진행 —

📋 진행 (🟡 대기)
- [ ] launch (nmae 위임)
- [ ] 분석 / 설계
- [ ] 구현
- [ ] 검증 (lint / test / typecheck)
- [ ] PR 생성
- [ ] PR 머지

✅ 결과 *(종결 시점에만 채워짐)*
—

⏭️ 다음 단계
nmae launch 대기

🔖 관련${_did_line}

---
_갱신: ${_ts} (등록)_
EOF
  }

  PENDING_BODY="$(_build_pending_body "$WORKTREE" "$TITLE" "$PENDING_DESC" "$PENDING_TS_KST" "$DIRECTIVE_ID")"
  # forum thread name = 🟡 prefix + title (사용자 sidebar 가시화).
  PENDING_TITLE="🟡 ${TITLE}"
  PENDING_TITLE="${PENDING_TITLE:0:99}"

  PENDING_OUT=""
  PENDING_RC=0
  PENDING_OUT=$("$DISCORD_REPLY_SH" \
    --forum-post-auto-tag "$WORKTREE" "$PENDING_TITLE" "$PENDING_BODY" 2>/dev/null) \
    || PENDING_RC=$?

  if [[ "$PENDING_RC" -ne 0 ]]; then
    echo "ERROR: pending thread 신설 실패 (rc=$PENDING_RC, raw=$PENDING_OUT)" >&2
    exit 6
  fi

  PENDING_THREAD_ID_RESULT=$(printf '%s' "$PENDING_OUT" | tr -d '\r' | awk 'NF{line=$0} END{print line}')
  if [[ ! "$PENDING_THREAD_ID_RESULT" =~ ^[0-9]{17,20}$ ]]; then
    echo "ERROR: pending thread 신설 응답 thread_id 누락 (raw=$PENDING_OUT)" >&2
    exit 7
  fi

  printf 'PENDING_THREAD_ID=%s\n' "$PENDING_THREAD_ID_RESULT"
  echo "agent-launch-wrapper.sh: --register-pending OK (cycle=$WORKTREE, thread=$PENDING_THREAD_ID_RESULT)" >&2
  exit 0
fi

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

# ─── directive-board jsonl ↔ Discord forum mismatch detect (#1129 impl PR 3) ─
#
# spec: docs/features/directive-board-event-driven-redesign.md §4
# CLAUDE.md §11-11.
#
# polling sync_loop 폐기 (PR #1129 impl PR 1) 후 actor 호출 누락 사고는
# 발생 가능 (sub-agent reasoning interrupt, helper crash 등). sub-agent launch
# 시작 시점에 jsonl 최근 N=20 entry ↔ Discord forum 태그 diff 를 출력해
# nmae / sub-agent 가 즉시 정정 호출 (`directive_status.sh`) 할 수 있게 한다.
#
# graceful: diff 헬퍼 자체가 exit 0 보장. wrapper stdout 계약 (첫 블록 =
# --echo-prompt 본문 또는 confirm 라인, 마지막 블록 = LAUNCH_THREAD_ID) 을
# 깨지 않게 diff 헬퍼 stdout 은 wrapper stderr 로 redirect — Discord launch
# thread / tmux pane / journal 에서는 visible, 호출자가 stdout grep 으로
# prompt body 추출할 때는 섞이지 않는다.
DIFF_SH="$SCRIPT_DIR/directive-board/jsonl-forum-diff.sh"
if [[ -x "$DIFF_SH" ]]; then
  "$DIFF_SH" --limit 20 >&2 2>&1 || true
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
# 운영자가 클릭해 들어왔을 때 sub-agent 가 어떤 작업 / 어디까지 진행 / 다음 단계
# 즉시 파악되도록 template 본문 (spec: docs/features/directive-board-template-and-tags.md §5-6).
ANNOUNCE_BODY="${DESCRIPTION:-$TITLE}"
LAUNCH_TS_ISO="$(date -Iseconds 2>/dev/null || date '+%Y-%m-%dT%H:%M:%S%z')"
LAUNCH_TS_KST="$(TZ='Asia/Seoul' date '+%Y-%m-%d %H:%M KST')"
FORUM_TITLE="sub-agent launch ($WORKTREE) — ${ANNOUNCE_BODY}"
# Discord forum thread name 은 100 char 제한 — 잘라 안전.
FORUM_TITLE="${FORUM_TITLE:0:99}"

# spec: directive-board-template-and-tags.md §5-6 cycle forum thread template.
# 사용자 정정 (2026-05-28): "각 subagent forum 들도 무슨 작업하는지 솔직히 나는
# 잘 모르겠어. 같은 이치로 내용 정제가 필요". launch 시점에 template 박아
# sub-agent 가 진행되며 [x] update / 댓글 append. 정제 hook 별도 launch X.
_build_cycle_template_body() {
  local _worktree="$1" _title="$2" _desc="$3" _task="$4" _ts_kst="$5"
  local _task_line=""
  if [[ -n "$_task" ]]; then
    _task_line="
**Task**: ${_task}"
  fi
  cat <<EOF
🛠️ **${_title}**

💬 작업
${_desc}${_task_line}

🆔 사이클: \`${_worktree}\` · 🕐 launch: ${_ts_kst}

📋 진행
- [x] launch (nmae 위임)
- [ ] 분석 / 설계
- [ ] 구현
- [ ] 검증 (lint / test / typecheck)
- [ ] PR 생성
- [ ] PR 머지

🔖 관련
- (sub-agent 가 milestone 시 PR / 이슈 / directive 링크 추가)

---
_갱신: ${_ts_kst} (launch 시점)_
EOF
}

FORUM_BODY="$(_build_cycle_template_body "$WORKTREE" "$ANNOUNCE_BODY" "$ANNOUNCE_BODY" "$TASK" "$LAUNCH_TS_KST")"

# 1차: --forum-post-auto-tag (Discord forum channel POST). stderr 는 tee 로
# 보존해 fallback 결정에 사용. stdout 마지막 줄 = thread_id.
FORUM_OUT=""
FORUM_RC=0

# spec: cycle-forum-operation.md §5-4 (PR cf-4) — --pending-thread-id 명시 시
# 기존 🟡 thread 재사용 + retag 🟡 → ⏳ + 본문 update (forum-edit). 신규 thread X.
if [[ -n "$PENDING_THREAD_ID" && "$PENDING_THREAD_ID" =~ ^[0-9]{17,20}$ ]]; then
  # retag — forum thread 의 tag 변경. graceful (실패 시 stderr warning + 진행).
  if ! "$DISCORD_REPLY_SH" --forum-retag "$PENDING_THREAD_ID" "$WORKTREE" "진행" \
      >/dev/null 2>&1; then
    echo "agent-launch-wrapper.sh: --pending-thread-id retag 실패 thread=$PENDING_THREAD_ID — graceful" >&2
  fi
  # 본문 update — launch 시점 정보 박힘.
  if ! "$DISCORD_REPLY_SH" --forum-edit "$PENDING_THREAD_ID" "$FORUM_BODY" \
      >/dev/null 2>&1; then
    echo "agent-launch-wrapper.sh: --pending-thread-id 본문 update 실패 thread=$PENDING_THREAD_ID — graceful" >&2
  fi
  # LAUNCH_THREAD_ID = pending thread (sub-agent inherit).
  printf 'LAUNCH_THREAD_ID=%s\n' "$PENDING_THREAD_ID"
  printf 'CYCLE_CHANNEL_MSG_ID=%s\n' "$PENDING_THREAD_ID"
  exit 0
fi

# 2026-05-29 (PR fix/cycle-forum-noise-prune): pending-thread-id 부재 시 신규
# forum thread 생성 skip. cycle forum 의 "sub-agent launch (...)" noise thread
# 누적 차단. spec: cycle-forum-operation.md §5-6 (1 task = 1 thread 원칙).
# 자율 사이클 (사용자 directive 박지 않은 launch) 은 DIGEST 채널 fallback 만.
#
# 단 명시적 opt-in (AGENT_LAUNCH_CREATE_FORUM_THREAD=1) 시 기존 path 유지 —
# 운영 전환기 / 디버그 친화.
if [[ "${AGENT_LAUNCH_CREATE_FORUM_THREAD:-0}" == "1" ]]; then
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
else
  echo "agent-launch-wrapper.sh: pending-thread-id 부재 + AGENT_LAUNCH_CREATE_FORUM_THREAD!=1 → forum 신규 thread skip. DIGEST fallback 사용." >&2
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
