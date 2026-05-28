#!/usr/bin/env bash
# backlog-scan.sh — directive-board.jsonl 의 🟡 대기 entry list + cycle 별 분배 후보 출력.
#
# spec: docs/features/directive-board-template-and-tags.md §5-6 백로그 운영 모델
#
# 사용:
#   bash tools/directive-board/backlog-scan.sh [--cycle <be|fe|rev|plan>]
#
# 동작:
#   1. ~/.mobruji/directive-board.jsonl 읽기 (env DIRECTIVE_BOARD_JSONL_PATH override)
#   2. status == "대기" entry filter
#   3. (선택) --cycle <name> 시 assigned_cycle 매칭 entry 만
#   4. 표 형식 stdout — message_id / ts / assigned_cycle (있으면) / summary
#
# 호출자:
#   - nmae 매 사이클 시작 시 (actors/nmae.md §11-8 의무)
#   - helper / 사용자 manual 점검
#
# env:
#   DIRECTIVE_BOARD_JSONL_PATH — default ~/.mobruji/directive-board.jsonl
#
# 출력 예:
#   === directive 백로그 (status=대기) ===
#   COUNT=3
#   [1] 1509427... · 2026-05-28 14:24 KST · assigned=plan · "잔존 작업들 어떻게 정리할래??"
#   [2] 1509427... · 2026-05-28 14:27 KST · assigned=- · "오케이 옵션 B로 가자"
#   [3] 1509442... · 2026-05-28 15:26 KST · assigned=rev · "rev만 재개해봐"
#
# 종료코드: 항상 0 (idle scan 도 정상). jsonl 부재 = warning + count 0.

set -uo pipefail

JSONL_PATH_DEFAULT="${HOME}/.mobruji/directive-board.jsonl"
JSONL_PATH="${DIRECTIVE_BOARD_JSONL_PATH:-${JSONL_PATH_DEFAULT}}"

CYCLE_FILTER=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --cycle)
      shift
      CYCLE_FILTER="${1:-}"
      shift
      ;;
    -h|--help)
      sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      echo "backlog-scan: 알 수 없는 인자 \"$1\"" >&2
      exit 64
      ;;
  esac
done

if [[ ! -f "${JSONL_PATH}" ]]; then
  echo "=== directive 백로그 (status=대기) ===" >&2
  echo "COUNT=0 (jsonl 부재: ${JSONL_PATH})" >&2
  exit 0
fi

if ! command -v jq >/dev/null 2>&1; then
  echo "backlog-scan: jq 미설치 — scan 불가" >&2
  exit 1
fi

# jq query — status="대기" entry filter + (선택) assigned_cycle 매칭.
JQ_FILTER='
  select(.status == "대기")
'
if [[ -n "${CYCLE_FILTER}" ]]; then
  JQ_FILTER="${JQ_FILTER} | select(.assigned_cycle == \"${CYCLE_FILTER}\")"
fi

# 한 줄 format: message_id · ts · assigned · summary (60자)
ENTRIES=$(jq -r --slurpfile _all <(jq -c "${JQ_FILTER}" "${JSONL_PATH}" 2>/dev/null) -n '
  $_all[]
  | [
      (.message_id // "?"),
      (.ts // "?"),
      (.assigned_cycle // "-"),
      ((.summary // "?") | tostring | .[0:60])
    ]
  | @tsv
' 2>/dev/null || true)

COUNT=0
if [[ -n "${ENTRIES}" ]]; then
  COUNT=$(echo "${ENTRIES}" | wc -l | tr -d ' ')
fi

echo "=== directive 백로그 (status=대기${CYCLE_FILTER:+, cycle=${CYCLE_FILTER}}) ==="
echo "COUNT=${COUNT}"

if [[ "${COUNT}" -eq 0 ]]; then
  exit 0
fi

IDX=0
while IFS=$'\t' read -r MSG_ID TS ASSIGNED SUMMARY; do
  IDX=$((IDX + 1))
  printf '[%d] %s · %s · assigned=%s · %q\n' \
    "${IDX}" "${MSG_ID}" "${TS}" "${ASSIGNED}" "${SUMMARY}"
done <<< "${ENTRIES}"

exit 0
