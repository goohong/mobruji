#!/usr/bin/env bash
# directive_append.sh — directive board event-driven (PR #1140) 의 (a) 지시 발생
# 트리거. directive-board.jsonl 에 새 entry append + Discord forum 채널
# (#모부르지-지시) 에 thread 생성. polling sync_loop 폐기 대체.
#
# spec: docs/features/directive-board-event-driven-redesign.md §3 (a)
#
# 사용:
#   directive_append.sh <msg_id> "<title>" [pr_url] [body] [user_id]
#
# args:
#   <msg_id>   — 원본 사용자 메시지 message_id (멱등 key, 필수)
#   <title>    — directive 본문 요약 (forum thread name, 필수, 90자 cap)
#   [pr_url]   — 선택. 관련 PR URL (https://github.com/...). 빈 문자열 가능
#   [body]     — 선택. forum thread 본문 (미명시 시 build_template_body 자동 호출 —
#                spec: directive-board-template-and-tags.md). 명시 시 그대로 사용
#                (helper 정제 / nmae custom 등 override path).
#   [user_id]  — 선택. 발화자 Discord user_id (template 의 👤 line 표시용).
#                미명시 시 env `DIRECTIVE_USER_ID` fallback. 둘 다 부재 시 line 생략.
#
# 동작:
#   1. 멱등성 — JSONL 이미 같은 msg_id (또는 source_queue_msg_id) 존재 시 no-op
#      (race / 중복 호출 가드).
#   2. directive-board.jsonl 에 새 entry append (status="대기", ts KST).
#   3. discord-reply.sh --forum-post-auto-tag directive "<title>" "<body>"
#      호출 → forum thread 생성 + thread_id stdout.
#   4. JSONL entry 의 thread_id / message_id 필드를 atomic update.
#   5. graceful — Discord push 실패 시 stderr warning + exit 0
#      (forwarding 흐름 차단 금지). JSONL append 자체는 항상 수행 (forum 미생성
#      이라도 SoT 보존 + 다음 wrapper 누락 detect 가 후속 정정 유도).
#
# 호출자:
#   - bot.py on_message — directive 분류 시 자동 호출
#   - helper / nmae — manual 호출 가능 (멱등하므로 중복 안전)
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
  echo "usage: directive_append.sh <msg_id> \"<title>\" [pr_url] [body] [user_id]" >&2
  exit 64
}

if [[ $# -lt 2 ]]; then
  usage
fi

MSG_ID="$1"
TITLE="$2"
PR_URL="${3:-}"
BODY="${4:-}"
USER_ID="${5:-${DIRECTIVE_USER_ID:-}}"

if [[ -z "${MSG_ID}" || -z "${TITLE}" ]]; then
  usage
fi

# Title cap — Discord forum thread name limit 100 자. 안전 90.
if [[ ${#TITLE} -gt 90 ]]; then
  TITLE="${TITLE:0:87}..."
fi

# spec: docs/features/directive-board-template-and-tags.md §5-4
# template body 빌드 — title + msg_id + ts_kst + (선택) user_id 받아 minimal
# template 본문 생성. 사용자가 forum 한 번 보면 "어떤 상황 / 어디까지 진행" 즉시 파악.
# helper 가 다음 turn 에 PATCH 로 점진 보강 (컨텍스트 / category tag / 관련 PR).
build_template_body() {
  local _title="$1"
  local _msg_id="$2"
  local _ts_kst="$3"
  local _user_id="${4:-}"

  local _user_line=""
  if [[ -n "${_user_id}" ]]; then
    _user_line="👤 <@${_user_id}> · "
  fi

  cat <<EOF
📌 **${_title}**

💬 원본
> ${_title}

🆔 \`${_msg_id}\` · ${_user_line}🕐 ${_ts_kst}

📋 진행 (🟡 대기)
- [ ] 분석 / 위임 결정
- [ ] 실행
- [ ] 결과 반영

🔖 관련 *(없음 — 진행되며 helper 가 추가)*

🤖 helper 정제 대기 (nmae 분배 보류 — race 가드)

---
_갱신: ${_ts_kst}_
EOF
}

# JSONL 파일 + 부모 디렉토리 보장. mode 0600 유지 (PII 가능성).
mkdir -p "$(dirname "${JSONL_PATH}")"
if [[ ! -f "${JSONL_PATH}" ]]; then
  touch "${JSONL_PATH}"
  chmod 600 "${JSONL_PATH}"
fi

# 멱등성 검사 — 동일 msg_id 가 jsonl 안 message_id 또는 source_queue_msg_id 로
# 이미 존재하면 no-op (중복 호출 안전).
if grep -q -E "\"(message_id|source_queue_msg_id)\":\\s*\"${MSG_ID}\"" \
    "${JSONL_PATH}" 2>/dev/null; then
  echo "directive_append: msg_id=${MSG_ID} 이미 등록 — no-op" >&2
  exit 0
fi

# KST timestamp (jsonl 기존 entry 형식 "YYYY-MM-DD HH:MM KST" 와 호환).
TS_KST="$(TZ='Asia/Seoul' date '+%Y-%m-%d %H:%M KST')"

# BODY 미명시 시 template 본문 자동 빌드 (spec §5-4).
# 명시 시 그대로 사용 (helper 정제 / nmae custom 등 override path 보존).
if [[ -z "${BODY}" ]]; then
  BODY=$(build_template_body "${TITLE}" "${MSG_ID}" "${TS_KST}" "${USER_ID}")
fi

# atomic append — tmp 파일 mktemp + cat 으로 추가 후 rename.
ENTRY_TMP="$(mktemp -t "directive_append.XXXXXX")"
trap 'rm -f "${ENTRY_TMP}"' EXIT

# JSONL entry 생성 — jq 로 안전 escape.
PR_URL_JSON_FIELD=""
if [[ -n "${PR_URL}" ]]; then
  PR_URL_JSON_FIELD=$(jq -nc --arg url "${PR_URL}" '{related_pr: $url}')
fi

ENTRY_JSON=$(jq -nc \
  --arg ts "${TS_KST}" \
  --arg summary "${TITLE}" \
  --arg msg_id "${MSG_ID}" \
  --arg last_updated "${TS_KST}" \
  --arg src_msg "${MSG_ID}" \
  --argjson pr "${PR_URL_JSON_FIELD:-null}" \
  '{
    ts: $ts,
    summary: $summary,
    status: "대기",
    polished: false,
    message_id: $msg_id,
    last_updated_kst: $last_updated,
    source_queue_msg_id: $src_msg
  } + (if $pr == null then {} else $pr end)')

# Discord forum thread 생성 — graceful (실패해도 JSONL append 는 수행).
THREAD_ID=""
DISCORD_OK="false"
if [[ -x "${DISCORD_REPLY_BIN}" ]]; then
  if THREAD_ID=$("${DISCORD_REPLY_BIN}" \
      --forum-post-auto-tag directive "${TITLE}" "${BODY}" 2>&1); then
    # stdout 마지막 줄 = thread_id (숫자만 추출).
    THREAD_ID=$(echo "${THREAD_ID}" | tr -d '[:space:]' | grep -oE '[0-9]+' \
      | tail -n1 || true)
    if [[ -n "${THREAD_ID}" ]]; then
      DISCORD_OK="true"
      # entry 에 thread_id 추가.
      ENTRY_JSON=$(echo "${ENTRY_JSON}" \
        | jq -c --arg tid "${THREAD_ID}" '. + {thread_id: $tid}')
    fi
  else
    echo "directive_append: discord-reply.sh forum-post 실패 — JSONL append 만 수행" >&2
  fi
else
  echo "directive_append: ${DISCORD_REPLY_BIN} 부재 또는 실행 불가 — JSONL append 만" >&2
fi

# JSONL atomic append (append-only flock 으로 동시 호출 가드).
{
  flock -x 9
  echo "${ENTRY_JSON}" >> "${JSONL_PATH}"
} 9>>"${JSONL_PATH}.lock"

echo "directive_append OK: msg_id=${MSG_ID} thread_id=${THREAD_ID:-none} discord=${DISCORD_OK}" >&2
exit 0
