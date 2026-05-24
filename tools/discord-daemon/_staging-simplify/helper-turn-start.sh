#!/usr/bin/env bash
# helper-turn-start.sh — helper 본체 매 turn 첫 명령 (의무 액션 wrapper).
#
# 배경 (#1014, 2026-05-24): 사용자 "메모리 줄여도 부분 해결 — 자동화가 진짜."
# 학습 의존 절차를 wrapper 로 강제.
#
# 단순화본 (refactor/bot-py-simplify-remove-wrappers):
#   §1 target msg freeze (#987) 제거 — 5+분 turn 에선 stale 만 되어 useful
#   하지 않음. discord-reply.sh resolve chain 도 last-user-msg-id.txt 만
#   사용하도록 동기화 (REPLY_TO_OVERRIDE > last-user-msg-id.txt 2단).
#   5 액션 → 4 액션 (target freeze 제거, 나머지는 유지).
#
# 동작 (4 액션, side-effect + stdout 요약):
#   1. cycle-status.json 읽어 4 워크트리 in_progress 한 줄 요약 (사용자가
#      사이클 물어볼 가능성 항상 → helper 가 즉답 가능하게 미리 적재).
#   2. user-presence.json 읽어 status 표시 (단순 정보 — 행동 분기 X. helper 가
#      "지금 사용자 깨어 있나?" 판단 시 참고. wait 분기는 절대 금지).
#   3. helper-queue.jsonl 마지막 pending entry message_id 표시 — helper 가
#      queue append 의무를 까먹지 않게 현재 큐 상태 가시화.
#   4. 위 정보를 stdout 으로 한 번에 출력 → helper 가 단일 호출로 모든
#      문맥 획득. 다음 turn 처리에 필요한 모든 데이터 sourced.
#
# 동작 보장:
#   - 파일 부재 / 권한 없음 / JSON parse fail 모두 graceful — stderr warning +
#     해당 섹션 "?" 표시. helper turn 깨지지 않게 절대 exit 1 안 함.
#   - jq 부재 시 raw cat fallback.
#
# 배포:
#   - 워크트리: tools/discord-daemon/helper-turn-start.sh (소스 진실)
#   - 운영: ~/.mobruji/helper-turn-start.sh (helper PATH 진입점). symlink 권장.
#
# 종속: cat, jq (optional — 없어도 동작), tr.

set -uo pipefail  # 의도적으로 -e 빼기 — 한 섹션 fail 이 전체를 죽이면 안 됨.

MOBRUJI_DIR="${MOBRUJI_DIR:-${HOME:-/tmp}/.mobruji}"
CYCLE_STATUS_FILE="${MOBRUJI_DIR}/cycle-status.json"
USER_PRESENCE_FILE="${MOBRUJI_DIR}/user-presence.json"
QUEUE_FILE="${MOBRUJI_DIR}/helper-queue.jsonl"

HAS_JQ=0
if command -v jq >/dev/null 2>&1; then
  HAS_JQ=1
fi

echo "=== helper turn-start (#1014, simplified) ==="

# ─── 1) cycle-status.json 4 워크트리 요약 ───────────────────────────────────
if [[ -r "$CYCLE_STATUS_FILE" ]]; then
  if [[ "$HAS_JQ" -eq 1 ]]; then
    # cycle-status.json 스키마: top-level 키 = 워크트리 이름 (be/fe/rev/plan).
    # 각 값 = { in_progress: {title, ...} | null, last_completed: {...} }.
    # idle 정의: in_progress == null OR in_progress 부재.
    summary=$(jq -r '
      to_entries
      | map(
          .key as $ws
          | (.value.in_progress // null) as $ip
          | if $ip == null then
              "\($ws):idle"
            else
              "\($ws):" + (($ip.title // "?") | tostring | .[0:30])
            end
        )
      | join(" | ")
    ' "$CYCLE_STATUS_FILE" 2>/dev/null)
    if [[ -z "$summary" ]]; then
      summary="(parse 실패)"
    fi
    echo "[1/4] cycle-status: ${summary}"
  else
    echo "[1/4] cycle-status: (jq 미설치 — raw)"
    cat "$CYCLE_STATUS_FILE" 2>/dev/null | head -20
  fi
else
  echo "[1/4] cycle-status: ! ${CYCLE_STATUS_FILE} 부재" >&2
  echo "[1/4] cycle-status: ?"
fi

# ─── 2) user-presence (단순 정보 표시 — 행동 분기 X) ─────────────────────────
if [[ -r "$USER_PRESENCE_FILE" ]]; then
  if [[ "$HAS_JQ" -eq 1 ]]; then
    presence=$(jq -r '.status // .presence // "?"' "$USER_PRESENCE_FILE" 2>/dev/null)
    last_seen=$(jq -r '.last_seen // .updated_at // ""' "$USER_PRESENCE_FILE" 2>/dev/null)
    echo "[2/4] user-presence: ${presence:-?} (last_seen=${last_seen:-?}) — 정보만, wait 분기 금지"
  else
    raw=$(head -1 "$USER_PRESENCE_FILE" 2>/dev/null)
    echo "[2/4] user-presence: ${raw:0:80} — 정보만, wait 분기 금지"
  fi
else
  echo "[2/4] user-presence: ! ${USER_PRESENCE_FILE} 부재" >&2
  echo "[2/4] user-presence: ?"
fi

# ─── 3) helper-queue 마지막 pending 표시 ────────────────────────────────────
if [[ -r "$QUEUE_FILE" ]]; then
  if [[ "$HAS_JQ" -eq 1 ]]; then
    pending_count=$(jq -s '[.[] | select(.status == "pending")] | length' "$QUEUE_FILE" 2>/dev/null)
    last_pending=$(jq -r 'select(.status == "pending") | .message_id // "?"' "$QUEUE_FILE" 2>/dev/null | tail -1)
    echo "[3/4] queue: pending=${pending_count:-?} last_pending_msg_id=${last_pending:-?} — append 의무 잊지 말기"
  else
    pending_count=$(grep -c '"status": "pending"' "$QUEUE_FILE" 2>/dev/null || echo "?")
    echo "[3/4] queue: pending=${pending_count} (jq 미설치 raw count) — append 의무 잊지 말기"
  fi
else
  echo "[3/4] queue: ! ${QUEUE_FILE} 부재 (turn 종료 직전 신설 필요)" >&2
  echo "[3/4] queue: ?"
fi

# ─── 4) 다음 액션 reminder ──────────────────────────────────────────────────
echo "[4/4] 다음 액션: (a) queue append (b) 분류 (c) 처리 (d) 본답 push (e) queue done + grep 0건 검증"
echo "=== /helper turn-start ==="

exit 0
