#!/usr/bin/env bash
# mark-polished.sh — helper sub-agent 가 directive 본문 정제 완료 후 호출.
#
# spec: docs/features/directive-board-template-and-tags.md §5-6 polished flag
#
# 사용:
#   bash tools/directive-board/mark-polished.sh <directive_id>
#
# 동작:
#   1. ~/.mobruji/directive-board.jsonl 에서 message_id / source_queue_msg_id /
#      thread_id 중 매칭 entry 검색.
#   2. polished=true + last_updated_kst 갱신 (atomic write — flock + mv).
#   3. nmae backlog-scan 이 default 로 polished=true 만 list → 정제 완료된
#      entry 만 분배 후보로 들어감. race 가드.
#
# 호출자:
#   - helper sub-agent (sub-agent.md §2-helper directive_polish task 처리 후 의무)
#
# env:
#   DIRECTIVE_BOARD_JSONL_PATH — default ~/.mobruji/directive-board.jsonl
#
# 종료코드: 0 = OK / 1 = entry 미발견 또는 jsonl 부재 / 64 = usage error.

set -uo pipefail

JSONL_PATH_DEFAULT="${HOME}/.mobruji/directive-board.jsonl"
JSONL_PATH="${DIRECTIVE_BOARD_JSONL_PATH:-${JSONL_PATH_DEFAULT}}"

usage() {
  echo "usage: mark-polished.sh <directive_id>" >&2
  exit 64
}

if [[ $# -lt 1 ]]; then
  usage
fi

ID_ARG="$1"

if [[ -z "${ID_ARG}" ]]; then
  usage
fi

if [[ ! -f "${JSONL_PATH}" ]]; then
  echo "mark-polished: ${JSONL_PATH} 파일 없음" >&2
  exit 1
fi

if ! command -v jq >/dev/null 2>&1; then
  echo "mark-polished: jq 미설치" >&2
  exit 1
fi

# 매칭 entry 존재 검증.
MATCHED=$(jq -c --arg id "${ID_ARG}" '
  select(
    (.message_id // "") == $id
    or (.source_queue_msg_id // "") == $id
    or (.thread_id // "") == $id
  )
' "${JSONL_PATH}" 2>/dev/null | head -1)

if [[ -z "${MATCHED}" ]]; then
  echo "mark-polished: id=${ID_ARG} 매칭 entry 없음 (${JSONL_PATH})" >&2
  exit 1
fi

# 멱등성 — 이미 polished=true 면 no-op.
ALREADY=$(echo "${MATCHED}" | jq -r '.polished // false')
if [[ "${ALREADY}" == "true" ]]; then
  echo "mark-polished: id=${ID_ARG} 이미 polished=true — no-op" >&2
  exit 0
fi

TS_KST="$(TZ='Asia/Seoul' date '+%Y-%m-%d %H:%M KST')"

JSONL_TMP="$(mktemp -t "mark-polished.XXXXXX")"
trap 'rm -f "${JSONL_TMP}"' EXIT

# flock — directive_append.sh / directive_status.sh 와 동일 .lock 파일 공유.
{
  flock -x 9
  jq -c --arg id "${ID_ARG}" --arg ts "${TS_KST}" '
    if (
      (.message_id // "") == $id
      or (.source_queue_msg_id // "") == $id
      or (.thread_id // "") == $id
    ) then
      .polished = true
      | .last_updated_kst = $ts
    else
      .
    end
  ' "${JSONL_PATH}" > "${JSONL_TMP}"
  mv "${JSONL_TMP}" "${JSONL_PATH}"
} 9>>"${JSONL_PATH}.lock"

echo "mark-polished OK: id=${ID_ARG} polished=true (race 가드 해제 — nmae backlog-scan picker 가능)" >&2

# spec: docs/features/directive-board-template-and-tags.md §5-6 + actors/nmae.md §11-8
# nmae tmux pane 에 즉시 inject — 학습 의존 ↓ 코드 강제 (PR E-1).
# polished=true 된 시점에 nmae 가 backlog-scan + 4 분기 분배 결정하도록 명시 신호.
# helper 가 직접 nmae 위임하는 relay-only 룰 우회 path 와 별개로 정확한 picker
# trigger 보장. helper 차단은 PR E-2 (system prompt 강제).
NMAE_PANE="${MOBRUJI_NMAE_PANE:-mobruji:0.0}"
NMAE_INJECT_ENABLED="${MOBRUJI_NMAE_INJECT_ENABLED:-1}"

if [[ "${NMAE_INJECT_ENABLED}" != "1" ]]; then
  echo "mark-polished: nmae inject disabled (MOBRUJI_NMAE_INJECT_ENABLED=0)" >&2
  exit 0
fi

# Issue #1248 — status 필터 (false positive 차단).
# 매칭 entry status 가 '대기' 또는 '진행 중' 외면 inject 자체 skip. 사고: 2026-05-29
# 10:39~10:44 KST 사이 status=완료 entry 6건이 mark-polished 호출되어 nmae 본진에
# 6회 false positive inject. backlog-scan 은 status=대기만 picker 후보로 보내므로
# 완료/실패 entry inject 는 의미 없음.
#
# 검색: jsonl 갱신 후 (mv 직후) 다시 read 해서 최신 entry status 확인. 위 update
# 단계가 ID_ARG 매칭 entry 의 polished=true 만 박고 status 는 건드리지 않으므로
# jsonl 의 현재 status 가 곧 inject 결정 기준.
CURRENT_STATUS=$(jq -r --arg id "${ID_ARG}" '
  select(
    (.message_id // "") == $id
    or (.source_queue_msg_id // "") == $id
    or (.thread_id // "") == $id
  )
  | .status // ""
' "${JSONL_PATH}" 2>/dev/null | head -1)

# 한국어 정규화 — 일부 entry 가 "✅ 완료" / "🔄 진행 중" 같은 이모지 prefix
# 사용 (jsonl-forum-diff.sh 와 동일 정규화 패턴).
NORMALIZED_STATUS="${CURRENT_STATUS#*[[:space:]]}"
case "${CURRENT_STATUS}" in
  *완료*) NORMALIZED_STATUS="완료" ;;
  *실패*) NORMALIZED_STATUS="실패" ;;
  *"진행 중"*) NORMALIZED_STATUS="진행 중" ;;
  *대기*) NORMALIZED_STATUS="대기" ;;
esac

case "${NORMALIZED_STATUS}" in
  대기|"진행 중")
    : # inject 진행
    ;;
  *)
    echo "mark-polished: nmae inject skip — id=${ID_ARG} status=${CURRENT_STATUS:-unknown} (#1248 false positive 차단, 대기/진행 중 외 entry inject 무의미)" >&2
    exit 0
    ;;
esac

if ! command -v tmux >/dev/null 2>&1; then
  echo "mark-polished: tmux 부재 — nmae inject skip (graceful)" >&2
  exit 0
fi

# session 이름 = pane 이름의 ':' 앞 부분.
NMAE_SESSION="${NMAE_PANE%:*}"
if ! tmux has-session -t "${NMAE_SESSION}" 2>/dev/null; then
  echo "mark-polished: tmux session ${NMAE_SESSION} 부재 — nmae inject skip (graceful)" >&2
  exit 0
fi

INJECT_MSG="[directive] 새 polished directive ${ID_ARG} — backlog-scan + 분배 결정 의무. bash tools/directive-board/backlog-scan.sh 호출 후 4 분기 휴리스틱 적용 (actors/nmae.md §11-8). plan 위임 시 사유 명시 의무."

# 본문 + Enter 별도 send (tmux send-keys 의 한 줄 묶음이 종종 submit 안 됨 — 관찰됨).
if tmux send-keys -t "${NMAE_PANE}" "${INJECT_MSG}" 2>/dev/null && \
   tmux send-keys -t "${NMAE_PANE}" Enter 2>/dev/null; then
  echo "mark-polished: nmae inject OK (pane=${NMAE_PANE}, id=${ID_ARG})" >&2
else
  echo "mark-polished: nmae inject 실패 (pane=${NMAE_PANE} 권한 또는 race)" >&2
fi

exit 0
