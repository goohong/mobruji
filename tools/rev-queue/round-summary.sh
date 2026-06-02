#!/usr/bin/env bash
# round-summary.sh — rev sub-agent round 종료 보고 wrapper.
#
# spec: docs/features/rev-qa-protocol.md §5-9-5
#
# 사용:
#   bash tools/rev-queue/round-summary.sh <round_id> \
#     [--s1 P/H/F] [--s2 P/H/F] [--s3 P/H/F] \
#     [--follow "#N,#M,#K"] [--fail-detail "<요약>"]
#
# args:
#   <round_id>      — round 식별자 (예: "round-12" / "12" / 임의 문자열)
#   --s1 P/H/F      — 단계 1 (PR 머지 전 코드 리뷰) PASS/HOLD/FAIL count. default 0/0/0
#   --s2 P/H/F      — 단계 2 (develop 머지 후) PASS/HOLD/FAIL count. default 0/0/0
#   --s3 P/H/F      — 단계 3 (release 후 production) PASS/HOLD/FAIL count. default 0/0/0
#   --follow        — 이번 round 등록한 후속 issue/PR 번호 CSV (예: "#1234,#1235")
#   --fail-detail   — ❌ 발견 시 1줄 사유 요약. 단계 무관.
#
# 동작 (학습 의존 ↓ 강제 메커니즘 — feedback-evidence-based-root-cause):
#   1. args parse + format 통일 본문 build.
#   2. DIGEST 채널 (DIGEST_CHANNEL_ID) 에 round 요약 push — discord-reply.sh --status-channel.
#   3. fail-detail 있으면 사용자 마지막 메시지 reply 추가 push (bare body mode) —
#      ❌ 발견 시 사용자 attention 즉시 (§5-9-4).
#   4. graceful — Discord push 실패 시 stderr warning + exit 0 (rev 사이클 차단 금지).
#
# 호출자: rev sub-agent (sub-agent.md §2-rev "round 종료 wrapper 호출 의무").
#
# env:
#   DISCORD_REPLY_BIN  — discord-reply.sh 절대 경로 (default ${HOME}/.mobruji/discord-reply.sh).
#   ROUND_SUMMARY_DRY_RUN  — 1 이면 push 안 함, 본문만 stdout 출력 (테스트용).
#
# 출력 예 (DIGEST 채널):
#   rev round 12 종료
#   ─────────────────
#   단계 1 (PR 머지 전): 3 PASS / 1 HOLD / 0 FAIL
#   단계 2 (develop 후): 5 PASS / 0 HOLD / 1 FAIL — #1196 develop 후 회귀
#   단계 3 (release 후): 2 PASS / 0 HOLD / 0 FAIL
#
#   후속: #1234, #1235

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DISCORD_REPLY_BIN="${DISCORD_REPLY_BIN:-${HOME}/.mobruji/discord-reply.sh}"
DRY_RUN="${ROUND_SUMMARY_DRY_RUN:-0}"

usage() {
  echo "usage: round-summary.sh <round_id> [--s1 P/H/F] [--s2 P/H/F] [--s3 P/H/F] [--follow CSV] [--fail-detail TEXT]" >&2
  exit 64
}

if [[ $# -lt 1 ]]; then
  usage
fi

ROUND_ID="$1"
shift

S1="0/0/0"
S2="0/0/0"
S3="0/0/0"
FOLLOW=""
FAIL_DETAIL=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --s1) shift; S1="${1:-0/0/0}"; shift ;;
    --s2) shift; S2="${1:-0/0/0}"; shift ;;
    --s3) shift; S3="${1:-0/0/0}"; shift ;;
    --follow) shift; FOLLOW="${1:-}"; shift ;;
    --fail-detail) shift; FAIL_DETAIL="${1:-}"; shift ;;
    -h|--help) usage ;;
    *) echo "round-summary: 알 수 없는 인자 \"$1\"" >&2; usage ;;
  esac
done

# P/H/F format 검증 + 분해.
_parse_phf() {
  local raw="$1"
  IFS='/' read -r P H F <<< "${raw}"
  P="${P:-0}"; H="${H:-0}"; F="${F:-0}"
  # 숫자 검증 (graceful — 비숫자면 0).
  [[ "${P}" =~ ^[0-9]+$ ]] || P=0
  [[ "${H}" =~ ^[0-9]+$ ]] || H=0
  [[ "${F}" =~ ^[0-9]+$ ]] || F=0
  echo "${P} ${H} ${F}"
}

read S1_P S1_H S1_F <<< "$(_parse_phf "${S1}")"
read S2_P S2_H S2_F <<< "$(_parse_phf "${S2}")"
read S3_P S3_H S3_F <<< "$(_parse_phf "${S3}")"

TOTAL_FAIL=$((S1_F + S2_F + S3_F))

# 본문 build — 통일 format.
BODY=""
BODY+="rev round ${ROUND_ID} 종료"$'\n'
BODY+="─────────────────"$'\n'

_stage_line() {
  local label="$1" p="$2" h="$3" f="$4"
  local line="${label}: ${p} PASS / ${h} HOLD / ${f} FAIL"
  if [[ "${f}" -gt 0 && -n "${FAIL_DETAIL}" ]]; then
    # FAIL 이 있는 단계에만 detail 부착 (한 사유는 한 단계에만).
    line+=" — ${FAIL_DETAIL}"
    FAIL_DETAIL=""  # 중복 부착 방지.
  fi
  echo "${line}"
}

BODY+="$(_stage_line "단계 1 (PR 머지 전)" "${S1_P}" "${S1_H}" "${S1_F}")"$'\n'
BODY+="$(_stage_line "단계 2 (develop 후)" "${S2_P}" "${S2_H}" "${S2_F}")"$'\n'
BODY+="$(_stage_line "단계 3 (release 후)" "${S3_P}" "${S3_H}" "${S3_F}")"$'\n'

if [[ -n "${FOLLOW}" ]]; then
  BODY+=$'\n'"후속: ${FOLLOW}"$'\n'
fi

# dry-run = 본문만 stdout 출력 + exit 0.
if [[ "${DRY_RUN}" == "1" ]]; then
  echo "${BODY}"
  exit 0
fi

# DIGEST 채널 push (discord-reply.sh --status-channel).
DIGEST_OK="false"
if [[ -x "${DISCORD_REPLY_BIN}" ]]; then
  if "${DISCORD_REPLY_BIN}" --status-channel "${BODY}" >/dev/null 2>&1; then
    DIGEST_OK="true"
  else
    echo "round-summary: DIGEST push 실패 (round=${ROUND_ID})" >&2
  fi
else
  echo "round-summary: ${DISCORD_REPLY_BIN} 부재 또는 실행 불가 — DIGEST push skip" >&2
fi

# ❌ 있으면 사용자 메시지 reply 추가 push (§5-9-4).
# bare body mode = LAST_USER_MSG_ID 기반 자동 reply.
USER_REPLY_OK="false"
if [[ "${TOTAL_FAIL}" -gt 0 && -x "${DISCORD_REPLY_BIN}" ]]; then
  REPLY_BODY="🔴 rev round ${ROUND_ID} — ${TOTAL_FAIL} FAIL 발견. nmae fix 사이클 launch 필요."
  if "${DISCORD_REPLY_BIN}" "${REPLY_BODY}" >/dev/null 2>&1; then
    USER_REPLY_OK="true"
  else
    echo "round-summary: 사용자 reply push 실패 (round=${ROUND_ID})" >&2
  fi
fi

echo "round-summary OK: round=${ROUND_ID} digest=${DIGEST_OK} user_reply=${USER_REPLY_OK} fail_total=${TOTAL_FAIL}" >&2
exit 0
