#!/usr/bin/env bash
# helper-turn-start.sh — helper 본체 매 turn 첫 명령 (의무 액션 wrapper).
#
# 배경 (#1014, 2026-05-24): 사용자 "메모리 줄여도 부분 해결 — 자동화가 진짜.
# helper 자율 더 잘 하려면." → 학습 의존 절차를 wrapper 로 강제.
#
# CLAUDE.md §12-3 매 사용자 메시지마다 순서대로 (한 단계라도 건너뛰면 룰
# 위반) 첫 단계: `bash tools/discord-daemon/helper-turn-start.sh` 호출.
#
# 동작 (6 액션, side-effect + stdout 요약):
#   1. target msg freeze (#987) — `last-user-msg-id.txt` → `helper-current-target.txt`
#      cp 로 atomic freeze. helper turn 진행 중 새 user msg 도착해도 target 안 바뀜.
#   2. ✍️ writing marker ON (#1128, PR #1139 spec) — target freeze 직후 즉시
#      `discord-reply.sh --writing-marker <target_id>` 호출. helper reasoning /
#      작업 단계 (수 분 ~ 수십 분) 내내 사용자 메시지에 ✍️ reaction 부착되어
#      "답 작성 중" 가시화. 본답 push 직후 OFF 는 docs/ai-harness/actors/helper.md §12-2 step 6
#      (helper 본체) 또는 BOT_WRITING_AUTO_HOOK_ENABLED=1 자동 hook 가 처리.
#      target_id 부재/snowflake 미달 시 graceful skip (turn 안 깨짐).
#   3. cycle-status.json 읽어 4 워크트리 in_progress 한 줄 요약 (사용자가
#      사이클 물어볼 가능성 항상 → helper 가 즉답 가능하게 미리 적재).
#   4. user-presence.json 읽어 status 표시 (단순 정보 — 행동 분기 X. helper 가
#      "지금 사용자 깨어 있나?" 판단 시 참고. wait 분기는 절대 금지).
#   5. helper-queue.jsonl 마지막 pending entry message_id 표시 — helper 가
#      queue append 의무를 까먹지 않게 현재 큐 상태 가시화.
#   6. 위 정보를 stdout 으로 한 번에 출력 → helper 가 단일 호출로 모든
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
# 종속: cp, cat, jq (optional — 없어도 동작), tr.

set -uo pipefail  # 의도적으로 -e 빼기 — 한 섹션 fail 이 전체를 죽이면 안 됨.

MOBRUJI_DIR="${MOBRUJI_DIR:-${HOME:-/tmp}/.mobruji}"
LAST_MSG_FILE="${MOBRUJI_DIR}/last-user-msg-id.txt"
TARGET_FILE="${MOBRUJI_DIR}/helper-current-target.txt"
CYCLE_STATUS_FILE="${MOBRUJI_DIR}/cycle-status.json"
USER_PRESENCE_FILE="${MOBRUJI_DIR}/user-presence.json"
QUEUE_FILE="${MOBRUJI_DIR}/helper-queue.jsonl"
USER_MODE_FILE="${MOBRUJI_DIR}/user-mode.txt"

HAS_JQ=0
if command -v jq >/dev/null 2>&1; then
  HAS_JQ=1
fi

# ─── USER_MODE marker — spec: docs/features/discord-reaction-choice-input.md ───
# AUTO (default, 자율) / ASK (질문 받는 mode). file 부재/잘못된 값 = AUTO.
# helper LLM 이 이 marker 보고 `--choices` 사용 여부 결정.
user_mode="AUTO"
if [[ -r "$USER_MODE_FILE" ]]; then
  raw_mode=$(head -1 "$USER_MODE_FILE" 2>/dev/null | tr -d '[:space:]' | tr 'a-z' 'A-Z')
  if [[ "$raw_mode" == "ASK" || "$raw_mode" == "AUTO" ]]; then
    user_mode="$raw_mode"
  fi
fi
echo "===USER_MODE:${user_mode}==="

echo "=== helper turn-start (#1014) ==="

# ─── 0) 룰 reminder (#1085, 2026-05-26 사용자 정정) ────────────────────────────
# 메모리 학습 의존 ↓ — wrapper stdout 으로 매 turn 핵심 룰 reminder.
# 단일 SoT: docs/ai-harness/actors/helper.md. CLAUDE.md §12 는 본 파일 포인터.
echo "[0/7] rules: (a) 답 first → 그 다음 작업 (b) helper 본체 = pure dispatch (코드 변경 = sub-agent 위임) (c) docs/ai-harness/actors/helper.md = SoT"

# ─── 1) target msg freeze (#987) ──────────────────────────────────────────────
target_id=""
if [[ -r "$LAST_MSG_FILE" ]]; then
  if cp "$LAST_MSG_FILE" "$TARGET_FILE" 2>/dev/null; then
    target_id=$(head -1 "$TARGET_FILE" 2>/dev/null | tr -d '[:space:]')
    echo "[1/7] target freeze: ${target_id:-?}"
  else
    echo "[1/7] target freeze: ! cp 실패 (권한?)" >&2
    echo "[1/7] target freeze: ?"
  fi
else
  echo "[1/7] target freeze: ! ${LAST_MSG_FILE} 부재" >&2
  echo "[1/7] target freeze: ?"
fi

# ─── 2) ✍️ writing marker ON (#1128, PR #1139 spec) ─────────────────────────
# target freeze 직후 즉시 ✍️ reaction + typing indicator ON. helper reasoning
# 단계 (수 분 ~ 수십 분) 내내 사용자 시점에서 "답 작성 중" 가시화.
# graceful: target_id 부재 / snowflake 형식 미달 시 skip — turn 안 깨짐.
# 본답 push 직후 OFF 는 docs/ai-harness/actors/helper.md §12-2 step 6 (helper 본체 명시 호출)
# 또는 BOT_WRITING_AUTO_HOOK_ENABLED=1 자동 hook 가 처리 (본 wrapper 책임 X).
# discord-reply.sh path 해결: 본 스크립트와 같은 디렉토리. symlink 운영도 대응
# (readlink -f BASH_SOURCE → 실제 위치 → dirname).
if [[ -n "$target_id" && "$target_id" =~ ^[0-9]{17,20}$ ]]; then
  SCRIPT_SELF="${BASH_SOURCE[0]}"
  if command -v readlink >/dev/null 2>&1; then
    SCRIPT_RESOLVED=$(readlink -f "$SCRIPT_SELF" 2>/dev/null || echo "$SCRIPT_SELF")
  else
    SCRIPT_RESOLVED="$SCRIPT_SELF"
  fi
  SCRIPT_DIR=$(dirname "$SCRIPT_RESOLVED")
  REPLY_SH="${SCRIPT_DIR}/discord-reply.sh"
  if [[ -x "$REPLY_SH" ]]; then
    # 호출 자체 fail 해도 turn 안 깨지게 || true 가드. stdout/stderr 은
    # 본 wrapper 의 진행 라인과 섞이지 않도록 /dev/null 로 silence — 실패 시
    # 다음 line 의 marker status 만 출력.
    if "$REPLY_SH" --writing-marker "$target_id" >/dev/null 2>&1; then
      echo "[2/7] writing marker ON: ✍️ reaction + typing → target=${target_id}"
    else
      echo "[2/7] writing marker ON: ! discord-reply.sh --writing-marker 호출 실패 (network/perm?) — graceful skip" >&2
      echo "[2/7] writing marker ON: skip"
    fi
  else
    echo "[2/7] writing marker ON: ! ${REPLY_SH} 부재/비실행 — skip" >&2
    echo "[2/7] writing marker ON: skip"
  fi
else
  echo "[2/7] writing marker ON: skip (target_id 부재/snowflake 미달)"
fi

# ─── 3) cycle-status.json 4 워크트리 요약 ───────────────────────────────────
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
    echo "[3/7] cycle-status: ${summary}"
  else
    echo "[3/7] cycle-status: (jq 미설치 — raw)"
    cat "$CYCLE_STATUS_FILE" 2>/dev/null | head -20
  fi
else
  echo "[3/7] cycle-status: ! ${CYCLE_STATUS_FILE} 부재" >&2
  echo "[3/7] cycle-status: ?"
fi

# ─── 4) user-presence (단순 정보 표시 — 행동 분기 X) ─────────────────────────
if [[ -r "$USER_PRESENCE_FILE" ]]; then
  if [[ "$HAS_JQ" -eq 1 ]]; then
    presence=$(jq -r '.status // .presence // "?"' "$USER_PRESENCE_FILE" 2>/dev/null)
    last_seen=$(jq -r '.last_seen // .updated_at // ""' "$USER_PRESENCE_FILE" 2>/dev/null)
    echo "[4/7] user-presence: ${presence:-?} (last_seen=${last_seen:-?}) — 정보만, wait 분기 금지"
  else
    raw=$(head -1 "$USER_PRESENCE_FILE" 2>/dev/null)
    echo "[4/7] user-presence: ${raw:0:80} — 정보만, wait 분기 금지"
  fi
else
  echo "[4/7] user-presence: ! ${USER_PRESENCE_FILE} 부재" >&2
  echo "[4/7] user-presence: ?"
fi

# ─── 5) helper-queue 마지막 pending 표시 ────────────────────────────────────
if [[ -r "$QUEUE_FILE" ]]; then
  if [[ "$HAS_JQ" -eq 1 ]]; then
    pending_count=$(jq -s '[.[] | select(.status == "pending")] | length' "$QUEUE_FILE" 2>/dev/null)
    last_pending=$(jq -r 'select(.status == "pending") | .message_id // "?"' "$QUEUE_FILE" 2>/dev/null | tail -1)
    echo "[5/7] queue: pending=${pending_count:-?} last_pending_msg_id=${last_pending:-?} — append 의무 잊지 말기"
  else
    pending_count=$(grep -c '"status": "pending"' "$QUEUE_FILE" 2>/dev/null || echo "?")
    echo "[5/7] queue: pending=${pending_count} (jq 미설치 raw count) — append 의무 잊지 말기"
  fi
else
  echo "[5/7] queue: ! ${QUEUE_FILE} 부재 (turn 종료 직전 신설 필요)" >&2
  echo "[5/7] queue: ?"
fi

# ─── 6) 다음 액션 reminder ──────────────────────────────────────────────────
echo "[6/7] 다음 액션 (docs/ai-harness/actors/helper.md §12-2): (a) queue append (b) 분류 (c) 답 first → 처리 (d) sub-agent dispatch (e) queue done + grep 0건 검증 (f) directive forum 등록 (지시 채택 시)"
echo "[7/7] 본답 push 직후 (docs/ai-harness/actors/helper.md §12-2 step 6): discord-reply.sh --writing-done <target_id> 호출로 ✍️ OFF (또는 BOT_WRITING_AUTO_HOOK_ENABLED=1 자동 hook)"
echo "=== /helper turn-start ==="

exit 0
