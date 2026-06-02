#!/usr/bin/env bash
# autoseed.sh — 유휴 시 백로그 자동 시드 (idle 감지 → GitHub 백로그 →
# directive 큐 보충). 자율 사이클(be/fe)이 idle 이고 큐가 비면 scope 라벨로
# 매칭된 open 이슈 1건을 directive-board.jsonl 🟡 대기 entry 로 보충한다.
# nmae 는 큐 소비 역할 불변 (STRICT 정합) — 본 스크립트는 큐만 채운다.
#
# spec: docs/features/roadmap-queue-autoseed.md (§5-3 5중 가드 / §5-4 loop)
#
# 사용:
#   bash tools/directive-board/autoseed.sh [--once] [--dry-run]
#
#   --once      1 패스만 실행 (default). 데몬 loop 는 cron / bot.py 가 주기 호출.
#   --dry-run   시드 후보만 stdout 출력, 실제 append/inject 안 함.
#
# 안전 가드 (spec §5-3):
#   G1 pending cap   — source=autoseed AND status=대기 entry ≤ AUTOSEED_MAX_PENDING
#                      (사람 등록 directive 는 카운트 제외).
#   G2 rate limit    — 패스당 max AUTOSEED_BATCH 건 + cycle 당 seeded 🟡 대기 ≤ 1.
#   G3 high-stakes   — type:release / security 라벨 + 보호영역 키워드 이슈 제외.
#   G4 중복 방지     — seed_issue 기등록 / 매핑 PR 존재 / assignee 보유 / closed 제외.
#   G5 실패 격리     — seed 1건 실패가 loop 전체 중단 안 함. 연속 실패 alert.
#
# scope→cycle 매핑 (spec §5-2 — 결정적 매핑만 자동 시드, 모호 scope 는 nmae 결정):
#   fe ← scope:web
#   be ← scope:recommendation/song/user/voice/feedback
#   rev/plan ← (매핑 없음 — 자동 시드 대상 외, nmae 가 분배)
#
# env:
#   CYCLE_STATUS_PATH            — default ~/.mobruji/cycle-status.json
#   DIRECTIVE_BOARD_JSONL_PATH   — default ~/.mobruji/directive-board.jsonl
#   AUTOSEED_MAX_PENDING         — G1 cap (default 4 = 워크트리 4 × 1)
#   AUTOSEED_BATCH               — G2 패스당 seed 상한 (default 1)
#   AUTOSEED_IDLE_MINUTES        — idle 판정 임계 분 (default 10)
#   AUTOSEED_EXCLUDE_LABELS      — G3 제외 라벨 csv (default "type:release,security")
#   AUTOSEED_EXCLUDE_KEYWORDS    — G3 제외 키워드 csv (보호영역 — 아래 default)
#   AUTOSEED_FAIL_ALERT_THRESHOLD— G5 연속 실패 alert 임계 (default 3)
#   AUTOSEED_GH_ISSUE_LIMIT      — gh issue list --limit (default 50)
#   AUTOSEED_CYCLES              — 검사 대상 cycle (default "be fe rev plan")
#   AUTOSEED_GH_BIN              — gh CLI (default gh) — 테스트 fake 주입용
#   AUTOSEED_APPEND_BIN          — directive_append.sh 경로 (default sibling)
#   AUTOSEED_MARK_POLISHED_BIN   — mark-polished.sh 경로 (default sibling)
#   AUTOSEED_CYCLE_UPDATE_BIN    — cycle-status/update.sh 경로 (default sibling)
#   AUTOSEED_DIGEST_BIN          — 설정 시 "<line>" 인자로 1회 호출 (DIGEST push)
#
# 종료코드: 항상 0 (idle pass 도 정상). 사용 오류만 64.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

CYCLE_STATUS_PATH="${CYCLE_STATUS_PATH:-${HOME}/.mobruji/cycle-status.json}"
JSONL_PATH="${DIRECTIVE_BOARD_JSONL_PATH:-${HOME}/.mobruji/directive-board.jsonl}"

AUTOSEED_MAX_PENDING="${AUTOSEED_MAX_PENDING:-4}"
AUTOSEED_BATCH="${AUTOSEED_BATCH:-1}"
AUTOSEED_IDLE_MINUTES="${AUTOSEED_IDLE_MINUTES:-10}"
AUTOSEED_EXCLUDE_LABELS="${AUTOSEED_EXCLUDE_LABELS:-type:release,security}"
AUTOSEED_EXCLUDE_KEYWORDS="${AUTOSEED_EXCLUDE_KEYWORDS:-migration,.env,secret,credential,build.gradle,dockerfile,docker-compose,workflow,license,breaking,마이그레이션,운영 db}"
AUTOSEED_FAIL_ALERT_THRESHOLD="${AUTOSEED_FAIL_ALERT_THRESHOLD:-3}"
AUTOSEED_GH_ISSUE_LIMIT="${AUTOSEED_GH_ISSUE_LIMIT:-50}"
AUTOSEED_CYCLES="${AUTOSEED_CYCLES:-be fe rev plan}"

GH_BIN="${AUTOSEED_GH_BIN:-gh}"
APPEND_BIN="${AUTOSEED_APPEND_BIN:-${SCRIPT_DIR}/../discord-daemon/directive_append.sh}"
MARK_POLISHED_BIN="${AUTOSEED_MARK_POLISHED_BIN:-${SCRIPT_DIR}/mark-polished.sh}"
CYCLE_UPDATE_BIN="${AUTOSEED_CYCLE_UPDATE_BIN:-${SCRIPT_DIR}/../cycle-status/update.sh}"
DIGEST_BIN="${AUTOSEED_DIGEST_BIN:-}"

DRY_RUN=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --once) shift ;;            # default 동작 — 명시 허용 (cron 가독성).
    --dry-run) DRY_RUN=1; shift ;;
    -h|--help)
      sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      echo "autoseed: 알 수 없는 인자 \"$1\"" >&2
      exit 64
      ;;
  esac
done

if ! command -v jq >/dev/null 2>&1; then
  echo "autoseed: jq 미설치 — 시드 불가" >&2
  exit 0
fi

log() { echo "autoseed: $*" >&2; }

# scope→cycle 역매핑 — cycle 이 소비할 scope 라벨 csv. 결정적 매핑만 (spec §5-2).
cycle_to_scope_labels() {
  case "$1" in
    fe) echo "scope:web" ;;
    be) echo "scope:recommendation,scope:song,scope:user,scope:voice,scope:feedback" ;;
    *) echo "" ;;   # rev/plan — 매핑 없음. nmae 가 분배.
  esac
}

# cycle 이 idle 인가? in_progress==null AND idle_since 가 임계 분 초과.
# idle_since 부재 시(set-idle 미경유) 보수적으로 not-idle 취급 — 조기 시드 방지.
is_cycle_idle() {
  local _cycle="$1"
  [[ -f "${CYCLE_STATUS_PATH}" ]] || return 1
  local _in_progress _idle_since
  _in_progress=$(jq -r --arg c "${_cycle}" '.[$c].in_progress // "null"' \
    "${CYCLE_STATUS_PATH}" 2>/dev/null)
  [[ "${_in_progress}" == "null" ]] || return 1
  _idle_since=$(jq -r --arg c "${_cycle}" '.[$c].idle_since // empty' \
    "${CYCLE_STATUS_PATH}" 2>/dev/null)
  [[ -n "${_idle_since}" ]] || return 1
  local _idle_epoch _now_epoch _elapsed_min
  _idle_epoch=$(date -d "${_idle_since}" +%s 2>/dev/null) || return 1
  _now_epoch=$(date +%s)
  _elapsed_min=$(( (_now_epoch - _idle_epoch) / 60 ))
  [[ "${_elapsed_min}" -ge "${AUTOSEED_IDLE_MINUTES}" ]]
}

# cycle 에 이미 status=대기 entry 가 있나? (있으면 보충 불요)
cycle_has_pending() {
  local _cycle="$1"
  [[ -f "${JSONL_PATH}" ]] || return 1
  local _n
  _n=$(jq -s --arg c "${_cycle}" '
    [ .[] | select(.status == "대기" and .assigned_cycle == $c) ] | length
  ' "${JSONL_PATH}" 2>/dev/null || echo 0)
  [[ "${_n:-0}" -gt 0 ]]
}

# G1 — 현재 source=autoseed AND status=대기 entry 수 (사람 directive 미포함).
count_seeded_pending() {
  [[ -f "${JSONL_PATH}" ]] || { echo 0; return; }
  jq -s '[ .[] | select(.status == "대기" and .source == "autoseed") ] | length' \
    "${JSONL_PATH}" 2>/dev/null || echo 0
}

# G4 — 이슈 #N 이 이미 시드됐나? (seed_issue 기등록)
issue_already_seeded() {
  local _n="$1"
  [[ -f "${JSONL_PATH}" ]] || return 1
  local _m
  _m=$(jq -s --argjson n "${_n}" \
    '[ .[] | select(.seed_issue == $n) ] | length' "${JSONL_PATH}" 2>/dev/null || echo 0)
  [[ "${_m:-0}" -gt 0 ]]
}

# G4 — 이슈 #N 본문 참조 PR 이 (open/closed 무관) 존재하나?
issue_has_mapped_pr() {
  local _n="$1"
  local _out
  _out=$("${GH_BIN}" pr list --search "${_n} in:body" --state all \
    --json number --limit 5 2>/dev/null || echo "[]")
  local _cnt
  _cnt=$(echo "${_out}" | jq 'length' 2>/dev/null || echo 0)
  [[ "${_cnt:-0}" -gt 0 ]]
}

# 한 cycle 에 대해 시드 후보 이슈 번호를 우선순위 정렬해 한 줄씩 출력.
# G3(라벨/키워드 제외) + assignee 제외를 jq 에서 적용. closed 는 --state open 으로 제외.
pick_candidates() {
  local _scope_labels="$1"
  local _gh_args=()
  local _label
  IFS=',' read -ra _labels <<< "${_scope_labels}"
  for _label in "${_labels[@]}"; do
    [[ -n "${_label}" ]] && _gh_args+=(--label "${_label}")
  done
  local _issues
  # --label 다중 = OR 아닌 AND 이므로 scope 별 개별 조회 후 합친다.
  _issues="[]"
  for _label in "${_labels[@]}"; do
    [[ -n "${_label}" ]] || continue
    local _one
    _one=$("${GH_BIN}" issue list --label "${_label}" --state open \
      --json number,title,labels,createdAt,assignees,body \
      --limit "${AUTOSEED_GH_ISSUE_LIMIT}" 2>/dev/null || echo "[]")
    _issues=$(jq -s 'add | unique_by(.number)' \
      <(echo "${_issues}") <(echo "${_one}") 2>/dev/null || echo "${_issues}")
  done

  echo "${_issues}" | jq -r \
    --arg labels "${AUTOSEED_EXCLUDE_LABELS}" \
    --arg kw "${AUTOSEED_EXCLUDE_KEYWORDS}" '
    ($labels | split(",") | map(select(length > 0))) as $xl
    | ($kw | split(",") | map(select(length > 0) | ascii_downcase)) as $xk
    | map(
        select(((.assignees // []) | length) == 0)
        | select([.labels[].name] as $ln | ($xl | any(. as $x | ($ln | index($x)) != null)) | not)
        | select((((.title // "") + " " + (.body // "")) | ascii_downcase) as $t | ($xk | any(. as $k | $t | contains($k)) | not))
        | . + {prank: ([.labels[].name] as $ln
            | if ($ln | index("priority:high")) != null then 0
              elif ($ln | index("priority:low")) != null then 2
              else 1 end)}
      )
    | sort_by(.prank, .createdAt, .number)
    | .[].number
  ' 2>/dev/null
}

# 이슈 #N 제목 조회 (seeded entry summary 용).
issue_title() {
  local _n="$1"
  "${GH_BIN}" issue view "${_n}" --json title -q '.title' 2>/dev/null || echo ""
}

# seeded entry forum 본문 (spec §5-5 provenance template).
build_seed_body() {
  local _n="$1" _title="$2" _ts="$3"
  cat <<EOF
🌱 **${_title}** (백로그 자동 시드)

💬 원본
> GitHub 이슈 #${_n}: ${_title}

🆔 \`autoseed-${_n}\` · 🤖 autoseed · 🕐 ${_ts}

📋 진행 (🟡 대기)
- [ ] 분석 / 위임 결정 (nmae)
- [ ] 실행
- [ ] 결과 반영

🔖 관련
- 이슈: #${_n}
- 출처: 유휴 백로그 자동 시드 (roadmap-queue-autoseed)

---
_갱신: ${_ts} · 🌱 autoseed (사람 등록 directive 와 구분)_
EOF
}

# ── 메인 패스 ────────────────────────────────────────────────────────────────
SEEDED_THIS_PASS=0
CONSEC_FAIL=0
declare -a SEEDED_LINES=()

for CYCLE in ${AUTOSEED_CYCLES}; do
  # G2 — 패스 batch 상한 도달 시 중단.
  if [[ "${SEEDED_THIS_PASS}" -ge "${AUTOSEED_BATCH}" ]]; then
    log "G2 batch(${AUTOSEED_BATCH}) 도달 — 패스 종료"
    break
  fi

  SCOPE_LABELS="$(cycle_to_scope_labels "${CYCLE}")"
  if [[ -z "${SCOPE_LABELS}" ]]; then
    continue   # rev/plan — 결정적 scope 매핑 없음 (nmae 가 분배).
  fi

  if ! is_cycle_idle "${CYCLE}"; then
    continue
  fi

  if cycle_has_pending "${CYCLE}"; then
    log "${CYCLE} 이미 대기 entry 보유 — skip"
    continue
  fi

  # G1 — seeded pending cap.
  SEEDED_PENDING="$(count_seeded_pending)"
  if [[ "${SEEDED_PENDING}" -ge "${AUTOSEED_MAX_PENDING}" ]]; then
    log "G1 cap(${AUTOSEED_MAX_PENDING}) 도달 (현재 seeded 대기 ${SEEDED_PENDING}) — skip"
    break
  fi

  # 후보 picking + G4 (seed_issue dedup / 매핑 PR) 통과 첫 이슈 선정.
  PICKED=""
  while read -r CAND; do
    [[ -n "${CAND}" ]] || continue
    if issue_already_seeded "${CAND}"; then
      continue
    fi
    if issue_has_mapped_pr "${CAND}"; then
      continue
    fi
    PICKED="${CAND}"
    break
  done < <(pick_candidates "${SCOPE_LABELS}")

  if [[ -z "${PICKED}" ]]; then
    log "${CYCLE} idle 이나 시드 후보 없음 — skip"
    continue
  fi

  TITLE="$(issue_title "${PICKED}")"
  [[ -n "${TITLE}" ]] || TITLE="이슈 #${PICKED}"
  TS_KST="$(TZ='Asia/Seoul' date '+%Y-%m-%d %H:%M KST')"
  SYNTH_ID="autoseed-${PICKED}-$(date +%s)"

  if [[ "${DRY_RUN}" -eq 1 ]]; then
    echo "[dry-run] seed #${PICKED} → ${CYCLE} · \"${TITLE}\""
    SEEDED_THIS_PASS=$((SEEDED_THIS_PASS + 1))
    SEEDED_LINES+=("🌱 autoseed: #${PICKED} → ${CYCLE}")
    continue
  fi

  # G5 — seed 1건 실패 격리. directive_append 멱등성으로 부분 성공 재호출 안전.
  SEED_BODY="$(build_seed_body "${PICKED}" "${TITLE}" "${TS_KST}")"
  if "${APPEND_BIN}" "${SYNTH_ID}" "${TITLE}" "#${PICKED}" "${SEED_BODY}" "" \
      --source autoseed --seed-issue "${PICKED}" \
      --assigned-cycle "${CYCLE}" --polished 2>>/dev/null; then
    CONSEC_FAIL=0
    SEEDED_THIS_PASS=$((SEEDED_THIS_PASS + 1))
    SEEDED_LINES+=("🌱 autoseed: #${PICKED} → ${CYCLE}")
    log "seed OK: #${PICKED} → ${CYCLE} (\"${TITLE}\")"

    # mark-polished — nmae inject (graceful). seeded entry 는 polished=true 라
    # 즉시 분배 후보. directive_append 가 thread 생성 후 message_id 로 매칭.
    if [[ -x "${MARK_POLISHED_BIN}" ]]; then
      "${MARK_POLISHED_BIN}" "${SYNTH_ID}" >/dev/null 2>&1 \
        || log "mark-polished 실패 (graceful) — #${PICKED}"
    fi

    # cycle-status note 갱신 (spec §5-6) — watchdog STRICT 정합. graceful.
    if [[ -x "${CYCLE_UPDATE_BIN}" ]]; then
      "${CYCLE_UPDATE_BIN}" "${CYCLE}" set-idle \
        --note "autoseed: #${PICKED} 큐 보충 — nmae 분배 대기" >/dev/null 2>&1 \
        || log "cycle-status note 갱신 실패 (graceful) — ${CYCLE}"
    fi
  else
    CONSEC_FAIL=$((CONSEC_FAIL + 1))
    log "G5 seed 실패 격리: #${PICKED} → ${CYCLE} (연속 실패 ${CONSEC_FAIL})"
    if [[ "${CONSEC_FAIL}" -ge "${AUTOSEED_FAIL_ALERT_THRESHOLD}" ]]; then
      ALERT="⚠️ autoseed 연속 실패 ${CONSEC_FAIL}회 — 점검 필요"
      log "${ALERT}"
      [[ -n "${DIGEST_BIN}" && -x "${DIGEST_BIN}" ]] \
        && "${DIGEST_BIN}" "${ALERT}" >/dev/null 2>&1 || true
    fi
  fi
done

# 관측성 — seed 발생 시 1줄 요약 + DIGEST push (spec 비기능 관측성).
if [[ "${#SEEDED_LINES[@]}" -gt 0 ]]; then
  for LINE in "${SEEDED_LINES[@]}"; do
    echo "${LINE}"
    [[ -n "${DIGEST_BIN}" && -x "${DIGEST_BIN}" && "${DRY_RUN}" -eq 0 ]] \
      && "${DIGEST_BIN}" "${LINE}" >/dev/null 2>&1 || true
  done
else
  log "이번 패스 시드 0건 (idle+큐고갈 cycle 없음 또는 가드 차단)"
fi

exit 0
