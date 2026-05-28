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
exit 0
