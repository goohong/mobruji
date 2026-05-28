#!/usr/bin/env bash
# tools/directive-board/jsonl-forum-diff.sh — directive board jsonl ↔ Discord
# forum 태그 mismatch detect 헬퍼 (PR #1129 impl PR 3, spec §4).
#
# 배경:
#   PR #1129 (directive board event-driven 재설계) 가 polling sync_loop 를
#   폐기한 뒤에도 actor (helper / nmae / sub-agent) 의 directive_status.sh
#   호출 누락 사고가 발생 가능. 본 헬퍼가 jsonl ↔ Discord forum 의 상태
#   diff 를 turn / launch 시작 시점에 emit — actor 가 stdout visible warning
#   을 보고 즉시 정정 호출.
#
# spec: docs/features/directive-board-event-driven-redesign.md §4
# CLAUDE.md §11-11 누락 검출.
#
# 사용:
#   tools/directive-board/jsonl-forum-diff.sh [--limit N]
#
# 옵션:
#   --limit N   directive-board.jsonl 의 최근 N entry 만 비교 (default 20).
#               jsonl 끝쪽 (가장 최근) 부터 N 개. 0 또는 음수 면 모든 entry.
#
# 동작:
#   1. directive-board.jsonl 최근 N entry 읽어 (message_id 또는 thread_id) +
#      status 추출. status 는 한국어 정규화 ("✅ 완료" → "완료", "🔄 진행 중
#      (... 부가)" → "진행 중", "⏳ 대기 (...)" → "대기").
#   2. discord-reply.sh --forum-state-dump directive 호출 — forum 내 active
#      thread + tag name 의 jsonl 형식 (각 줄 = {thread_id, name, tags}).
#   3. thread_id 기준 join → jsonl_status vs forum_tag 비교. 정규화 상태
#      불일치 시 stdout 에 한 줄 warning:
#        [!] directive mismatch: thread_id=<id> jsonl_status=<s1> forum_tag=<s2> summary=<s>
#   4. 비교 불가 (forum dump 실패 / jsonl 부재) 도 stdout warning 한 줄.
#   5. exit 0 — 호출자 wrapper 가 본 헬퍼 결과로 fail 되면 안 됨 (graceful).
#
# graceful 정책:
#   - jsonl 부재 → stdout 에 한 줄 안내 + exit 0.
#   - discord-reply.sh 부재 / forum-state-dump 실패 → stdout warning + exit 0.
#   - jq 미설치 → stderr warning + exit 0 (skip — 호출자가 turn 진행).
#
# env:
#   DIRECTIVE_BOARD_JSONL_PATH  — default ~/.mobruji/directive-board.jsonl
#   DISCORD_REPLY_BIN           — discord-reply.sh 절대 경로 (default
#                                 ~/.mobruji/discord-reply.sh + 폴백
#                                 ${SCRIPT_DIR}/../discord-daemon/discord-reply.sh)
#   DIRECTIVE_DIFF_NO_DISCORD=1 — forum dump 단계 skip (테스트 환경).
#                                 jsonl 만 stdout 으로 dump (debug).
#
# 검증:
#   tools/tests/test_directive_jsonl_forum_diff.sh

set -uo pipefail

LIMIT=20

while [[ $# -gt 0 ]]; do
  case "$1" in
    --limit)
      shift
      [[ $# -gt 0 ]] || {
        echo "ERROR: --limit requires value" >&2
        exit 64
      }
      LIMIT="$1"
      ;;
    -h|--help)
      sed -n '/^# tools\//,/^set -uo/p' "$0" | sed 's/^# \?//'
      exit 0
      ;;
    *)
      echo "ERROR: unknown arg: $1" >&2
      exit 64
      ;;
  esac
  shift
done

# limit 정규화 — 0 또는 음수면 전체 entry.
if ! [[ "$LIMIT" =~ ^-?[0-9]+$ ]]; then
  echo "ERROR: --limit 은 정수여야 함 (got: $LIMIT)" >&2
  exit 64
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JSONL_PATH="${DIRECTIVE_BOARD_JSONL_PATH:-${HOME:-/tmp}/.mobruji/directive-board.jsonl}"
DISCORD_REPLY_BIN="${DISCORD_REPLY_BIN:-${HOME:-/tmp}/.mobruji/discord-reply.sh}"

# discord-reply.sh fallback 위치 — 워크트리 안에서 단독 실행 / 테스트.
if [[ ! -x "$DISCORD_REPLY_BIN" ]]; then
  ALT="$SCRIPT_DIR/../discord-daemon/discord-reply.sh"
  if [[ -x "$ALT" ]]; then
    DISCORD_REPLY_BIN="$ALT"
  fi
fi

# jq 부재 — skip (graceful).
if ! command -v jq >/dev/null 2>&1; then
  echo "[!] directive-board mismatch check skipped (jq 미설치)" >&2
  exit 0
fi

# jsonl 부재 — actor 에 stdout 한 줄 안내 (warning 톤).
if [[ ! -r "$JSONL_PATH" ]]; then
  echo "[!] directive-board mismatch check: jsonl 부재 (${JSONL_PATH})"
  exit 0
fi

# ── 1) jsonl 최근 N entry 추출 ────────────────────────────────────────────────
# jq -s 로 array 변환 → 끝쪽 N 개 slice. limit ≤ 0 이면 전체.
if [[ "$LIMIT" -le 0 ]]; then
  JSONL_RECENT=$(jq -s '.' "$JSONL_PATH" 2>/dev/null || echo '[]')
else
  JSONL_RECENT=$(jq -s --argjson n "$LIMIT" '.[-$n:]' "$JSONL_PATH" 2>/dev/null || echo '[]')
fi

# entry 0 건 → silent exit (정상).
JSONL_COUNT=$(printf '%s' "$JSONL_RECENT" | jq 'length' 2>/dev/null || echo 0)
if [[ "$JSONL_COUNT" -eq 0 ]]; then
  exit 0
fi

# ── 2) discord-reply.sh --forum-state-dump directive 호출 ─────────────────────
# DIRECTIVE_DIFF_NO_DISCORD=1 → skip (테스트 / 오프라인).
FORUM_DUMP=""
if [[ "${DIRECTIVE_DIFF_NO_DISCORD:-0}" == "1" ]]; then
  FORUM_DUMP=""
elif [[ -x "$DISCORD_REPLY_BIN" ]]; then
  if ! FORUM_DUMP=$("$DISCORD_REPLY_BIN" --forum-state-dump directive 2>/dev/null); then
    echo "[!] directive-board mismatch check: --forum-state-dump 호출 실패 (graceful skip)"
    exit 0
  fi
else
  echo "[!] directive-board mismatch check: discord-reply.sh 부재 (${DISCORD_REPLY_BIN}) — graceful skip"
  exit 0
fi

# forum dump 가 비어 있으면 mismatch 비교 불가 — silent skip (정상 0건 케이스).
if [[ -z "$FORUM_DUMP" && "${DIRECTIVE_DIFF_NO_DISCORD:-0}" != "1" ]]; then
  exit 0
fi

# ── 3) 정규화 함수 (jq filter 안 inline) ────────────────────────────────────
# 한국어 status 정규화 — 이모지/괄호 부가 텍스트 제거.
#   "✅ 완료" / "완료 (2회 실행)" → "완료"
#   "🔄 진행 중 (단계 1 ✅ …)" → "진행 중"
#   "⏳ 대기 (P3c 큐)" → "대기"
#   "🟡 HOLD" → "HOLD" (해당 forum tag 도 동일하면 match)
#   기타 → 입력 그대로 trim.
NORMALIZE_JQ='
  def normalize_status:
    if . == null then ""
    else
      tostring
      # 1) 선행 이모지 + 공백 제거 (✅ 🔄 ⏳ 🟡 등).
      | gsub("^[\\p{So}\\p{Sk}\\p{Cf}]+\\s*"; "")
      # 2) 괄호 부가 텍스트 제거 (영문 / 한글 괄호).
      | gsub("\\s*\\(.*$"; "")
      | gsub("\\s*（.*$"; "")
      # 3) 양옆 공백 제거.
      | gsub("^\\s+|\\s+$"; "")
    end;
'

# ── 4) jsonl entry → {thread_id, status_norm, summary} 매핑 ───────────────────
JSONL_NORMALIZED=$(printf '%s' "$JSONL_RECENT" | jq -c "
  ${NORMALIZE_JQ}
  map(
    select((.thread_id // \"\") != \"\")
    | {
        thread_id: .thread_id,
        status_norm: (.status | normalize_status),
        status_raw: (.status // \"\"),
        summary: (.summary // \"\"),
      }
  )
" 2>/dev/null || echo '[]')

# ── 5) forum dump → {thread_id, tag_norm} 매핑 ────────────────────────────────
# forum-state-dump 는 jsonl (1줄 1 entry) 출력. 첫 tag 만 정규화 비교.
# tags 배열 비어 있으면 tag_norm = "" (jsonl_status 와 정렬 비교).
FORUM_NORMALIZED='[]'
if [[ -n "$FORUM_DUMP" ]]; then
  FORUM_NORMALIZED=$(printf '%s\n' "$FORUM_DUMP" | jq -cs "
    ${NORMALIZE_JQ}
    map({
      thread_id: (.thread_id // \"\"),
      tag_norm: ((.tags // []) | (first // \"\") | normalize_status),
      tag_raw: ((.tags // []) | (first // \"\")),
    })
  " 2>/dev/null || echo '[]')
fi

# ── 6) thread_id 기준 diff ────────────────────────────────────────────────────
# jsonl entry 마다 forum 의 같은 thread_id 찾아 status_norm vs tag_norm 비교.
# forum 에 thread_id 부재 → tag_norm = null (mismatch 후보, 단 forum dump 가
# 비어 있는 경우 skip).
MISMATCHES=$(jq -cn \
  --argjson j "$JSONL_NORMALIZED" \
  --argjson f "$FORUM_NORMALIZED" \
  '
    ($f | map({(.thread_id): {tag_norm, tag_raw}}) | add // {}) as $forum_map
    | $j
    | map(
        . as $entry
        | ($forum_map[$entry.thread_id] // null) as $forum_match
        | if $forum_match == null then
            null
          elif ($entry.status_norm == $forum_match.tag_norm) then
            null
          else
            {
              thread_id: $entry.thread_id,
              jsonl_status: $entry.status_norm,
              jsonl_status_raw: $entry.status_raw,
              forum_tag: $forum_match.tag_norm,
              forum_tag_raw: $forum_match.tag_raw,
              summary: $entry.summary,
            }
          end
      )
    | map(select(. != null))
  ' 2>/dev/null || echo '[]')

MISMATCH_COUNT=$(printf '%s' "$MISMATCHES" | jq 'length' 2>/dev/null || echo 0)

# ── 7) emit warning (stdout) ─────────────────────────────────────────────────
# graceful: visible warning 만 stdout, exit 0.
if [[ "$MISMATCH_COUNT" -gt 0 ]]; then
  echo "[!] directive-board mismatch detected (${MISMATCH_COUNT}건, jsonl ↔ Discord forum)"
  printf '%s' "$MISMATCHES" | jq -r '
    .[]
    | "[!] directive mismatch: thread_id=" + .thread_id
      + " jsonl_status=" + (.jsonl_status // "?")
      + " forum_tag=" + (.forum_tag // "?")
      + " summary=" + ((.summary | tostring)[0:60])
  '
  echo "[!] 정정 호출: bash ~/.mobruji/directive_status.sh <thread_id> <in_progress|completed>"
fi

exit 0
