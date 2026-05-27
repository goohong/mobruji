#!/usr/bin/env bash
# directive_status.sh — directive board event-driven (PR #1129) 의 (b) 위임 +
# (c) 완료 트리거. directive-board.jsonl entry status 갱신 + Discord forum thread
# 태그 retag + starter message 본문 update atomic 호출.
#
# spec: docs/features/directive-board-event-driven-redesign.md §3 (b) (c)
#
# 사용:
#   directive_status.sh <id> <new_status> [pr_url]
#
# args:
#   <id>          — directive entry 식별자. JSONL 의 message_id /
#                   source_queue_msg_id 또는 thread_id 와 매칭.
#   <new_status>  — in_progress | completed (그 외 거부)
#                   in_progress → 한국어 "진행 중" / completed → "완료" 매핑.
#   [pr_url]      — 선택. 관련 PR URL.
#
# 동작:
#   1. status 검증 — in_progress / completed 만 허용. 그 외 exit 1.
#   2. JSONL entry 검색 — message_id / source_queue_msg_id / thread_id 중 매칭.
#      미존재 시 exit 1.
#   3. 멱등성 — 이미 같은 status (한국어 매핑) 면 no-op (재호출 안전).
#   4. JSONL atomic update (status / last_updated_kst / pr_url / completed_kst).
#   5. discord-reply.sh --forum-retag directive "<status>" + --update-status
#      <thread_id> "<status>" [pr_url] atomic 호출.
#   6. graceful — Discord push 실패 시 stderr warning + exit 0
#      (forwarding 흐름 차단 금지). JSONL update 자체는 항상 수행 (SoT 보존).
#
# 호출자:
#   - nmae / helper / sub-agent (PR 1140 spec §3 (b) (c))
#   - agent-launch-wrapper.sh / sub-agent 완료 보고 wrapper
#
# env:
#   DIRECTIVE_BOARD_JSONL_PATH — default ~/.mobruji/directive-board.jsonl
#   DISCORD_REPLY_BIN          — discord-reply.sh 절대 경로 (default 동일 dir)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DISCORD_REPLY_BIN="${DISCORD_REPLY_BIN:-${SCRIPT_DIR}/discord-reply.sh}"
JSONL_PATH_DEFAULT="${HOME}/.mobruji/directive-board.jsonl"
JSONL_PATH="${DIRECTIVE_BOARD_JSONL_PATH:-${JSONL_PATH_DEFAULT}}"

usage() {
  echo "usage: directive_status.sh <id> <in_progress|completed> [pr_url]" >&2
  exit 64
}

if [[ $# -lt 2 ]]; then
  usage
fi

ID_ARG="$1"
NEW_STATUS="$2"
PR_URL="${3:-}"

if [[ -z "${ID_ARG}" || -z "${NEW_STATUS}" ]]; then
  usage
fi

# status 검증 — 명시 화이트리스트.
# 한국어 status 매핑 (jsonl entry 의 기존 status 형식 유지).
case "${NEW_STATUS}" in
  in_progress)
    STATUS_KO="진행 중"
    ;;
  completed)
    STATUS_KO="완료"
    ;;
  *)
    echo "directive_status: 알 수 없는 status \"${NEW_STATUS}\" — in_progress|completed 만 허용" >&2
    exit 1
    ;;
esac

# JSONL 파일 존재 + 비어 있지 않음 검증.
if [[ ! -f "${JSONL_PATH}" ]]; then
  echo "directive_status: ${JSONL_PATH} 파일 없음" >&2
  exit 1
fi

# id 매칭 entry 검색 — message_id / source_queue_msg_id / thread_id 중 하나.
# jq 가 jsonl (1줄 1 entry) 을 받아 첫 매칭 entry 를 stdout 출력.
MATCHED_ENTRY=$(jq -c --arg id "${ID_ARG}" '
  select(
    (.message_id // "") == $id
    or (.source_queue_msg_id // "") == $id
    or (.thread_id // "") == $id
  )
' "${JSONL_PATH}" 2>/dev/null | head -1)

if [[ -z "${MATCHED_ENTRY}" ]]; then
  echo "directive_status: id=${ID_ARG} 매칭 entry 없음 (${JSONL_PATH})" >&2
  exit 1
fi

CURRENT_STATUS=$(echo "${MATCHED_ENTRY}" | jq -r '.status // ""')
THREAD_ID=$(echo "${MATCHED_ENTRY}" | jq -r '.thread_id // ""')

# 멱등성 — 이미 같은 status 면 no-op.
if [[ "${CURRENT_STATUS}" == "${STATUS_KO}" ]]; then
  echo "directive_status: id=${ID_ARG} 이미 ${STATUS_KO} — no-op" >&2
  exit 0
fi

# KST timestamp.
TS_KST="$(TZ='Asia/Seoul' date '+%Y-%m-%d %H:%M KST')"

# JSONL atomic update — 임시 파일 작성 후 rename.
# id 매칭 entry 의 status / last_updated_kst / (completed 시) completed_kst /
# (pr_url 시) related_pr 갱신. 다른 entry 는 그대로 유지.
JSONL_TMP="$(mktemp -t "directive_status.XXXXXX")"
trap 'rm -f "${JSONL_TMP}"' EXIT

# flock 으로 동시 호출 race 방지 (directive_append.sh 와 동일 .lock 파일 공유).
{
  flock -x 9
  jq -c --arg id "${ID_ARG}" \
        --arg status "${STATUS_KO}" \
        --arg ts "${TS_KST}" \
        --arg pr "${PR_URL}" \
        --arg new_status "${NEW_STATUS}" '
    if (
      (.message_id // "") == $id
      or (.source_queue_msg_id // "") == $id
      or (.thread_id // "") == $id
    ) then
      .status = $status
      | .last_updated_kst = $ts
      | (if $new_status == "completed" then .completed_kst = $ts else . end)
      | (if $pr != "" then .related_pr = $pr else . end)
    else
      .
    end
  ' "${JSONL_PATH}" > "${JSONL_TMP}"
  # mv 는 atomic (같은 filesystem 가정).
  mv "${JSONL_TMP}" "${JSONL_PATH}"
} 9>>"${JSONL_PATH}.lock"

# Discord forum atomic update — graceful (실패해도 JSONL 은 이미 갱신).
DISCORD_OK="false"
if [[ -n "${THREAD_ID}" && -x "${DISCORD_REPLY_BIN}" ]]; then
  # 1) forum thread tag retag (대기 → 진행 중 → 완료).
  RETAG_OK="false"
  if "${DISCORD_REPLY_BIN}" --forum-retag "${THREAD_ID}" directive "${STATUS_KO}" \
      >/dev/null 2>&1; then
    RETAG_OK="true"
  else
    echo "directive_status: forum-retag 실패 thread=${THREAD_ID} status=${STATUS_KO}" >&2
  fi

  # 2) starter message 본문 update (status + timestamp + pr_url).
  UPDATE_OK="false"
  if [[ -n "${PR_URL}" ]]; then
    if "${DISCORD_REPLY_BIN}" --update-status "${THREAD_ID}" "${STATUS_KO}" "${PR_URL}" \
        >/dev/null 2>&1; then
      UPDATE_OK="true"
    fi
  else
    if "${DISCORD_REPLY_BIN}" --update-status "${THREAD_ID}" "${STATUS_KO}" \
        >/dev/null 2>&1; then
      UPDATE_OK="true"
    fi
  fi
  if [[ "${UPDATE_OK}" != "true" ]]; then
    echo "directive_status: update-status 실패 thread=${THREAD_ID} status=${STATUS_KO}" >&2
  fi

  if [[ "${RETAG_OK}" == "true" && "${UPDATE_OK}" == "true" ]]; then
    DISCORD_OK="true"
  fi
elif [[ -z "${THREAD_ID}" ]]; then
  echo "directive_status: entry id=${ID_ARG} 에 thread_id 부재 — Discord update skip (JSONL 갱신만)" >&2
else
  echo "directive_status: ${DISCORD_REPLY_BIN} 부재 또는 실행 불가 — Discord update skip" >&2
fi

echo "directive_status OK: id=${ID_ARG} status=${STATUS_KO} thread_id=${THREAD_ID:-none} discord=${DISCORD_OK}" >&2
exit 0
