#!/usr/bin/env bash
# PreToolUse(Bash) hook — 역할별 실행 차단.
#  - MOBRUJI_ROLE=helper 세션: default-DENY. relay/reply/status allowlist 외 전부 차단.
#    (시스템 프롬프트를 무시하고 작업하는 helper 를 harness 차원에서 하드 차단.
#     사용자 2026-05-26 "실행 작업 권한을 다 뽑아 — 시스템 프롬프트까지 무시한다".)
#  - sub-agent 세션 (be/fe/rev/plan + 임시 워크트리): 3 조건 OR 통과
#    1) cwd realpath 가 sub-agent 워크트리 화이트리스트 매칭
#    2) MOBRUJI_ROLE env 가 subagent role
#    3) MOBRUJI_ALLOW_DIRECT=1 sentinel (호환성)
#  - nmae 본진 (cwd=/home/mobruji/mobruji + role unset): 기존 blocklist 유지
#
# 본 spec: docs/features/helper-direct-work-guard-subagent-context.md (PR #1146)
# 시스템 path: /home/mobruji/.mobruji/helper-direct-work-guard.sh (이 파일의 symlink)
set -euo pipefail

INPUT="$(cat)"
CMD="$(printf '%s' "$INPUT" | python3 -c "import json,sys
try:
    print(json.load(sys.stdin).get('tool_input',{}).get('command',''))
except Exception:
    pass")"

# audit log helper — JSON Lines, 100줄 cap (FIFO truncate)
AUDIT_LOG="${HOME}/.mobruji/hook-bypass.log"
audit_log() {
  local reason="$1"
  local cwd_real="${2:-}"
  local role_detected="${MOBRUJI_ROLE:-unset}"
  local cmd_trunc
  cmd_trunc="$(printf '%s' "$CMD" | head -c 200)"
  local ts
  ts="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
  mkdir -p "$(dirname "$AUDIT_LOG")"
  # Python 으로 JSON 안전 직렬화 (인용/escape 자동 처리)
  TS="$ts" ROLE="$role_detected" CWD="$cwd_real" CMD_TRUNC="$cmd_trunc" REASON="$reason" \
    python3 -c "
import json, os
entry = {
    'ts': os.environ.get('TS', ''),
    'role': os.environ.get('ROLE', ''),
    'cwd': os.environ.get('CWD', ''),
    'cmd': os.environ.get('CMD_TRUNC', ''),
    'reason': os.environ.get('REASON', ''),
}
print(json.dumps(entry, ensure_ascii=False))
" >> "$AUDIT_LOG" 2>/dev/null || true
  # cap 100 줄 (FIFO)
  if [ -f "$AUDIT_LOG" ]; then
    local line_count
    line_count="$(wc -l < "$AUDIT_LOG" 2>/dev/null || echo 0)"
    if [ "$line_count" -gt 100 ]; then
      tail -n 100 "$AUDIT_LOG" > "${AUDIT_LOG}.tmp" 2>/dev/null && mv "${AUDIT_LOG}.tmp" "$AUDIT_LOG" 2>/dev/null || true
    fi
  fi
}

# 비상 우회 sentinel (공통, 항상 audit 기록)
if [[ "$CMD" == *"MOBRUJI_ALLOW_DIRECT=1"* ]]; then
  audit_log "sentinel" ""
  exit 0
fi

# ===== HELPER 세션: default-DENY allowlist (현행 유지) =====
if [ "${MOBRUJI_ROLE:-}" = "helper" ]; then
  ALLOW_PATTERNS=(
    'discord-reply\.sh'
    'helper-turn-start\.sh'
    'tmux[[:space:]]+send-keys[[:space:]]+-t[[:space:]]+mobruji'
    'helper-queue\.jsonl'
    'helper-current-target\.txt'
    'helper-current-thread\.txt'
    'last-user-msg-id\.txt'
    'cycle-status\.json'
    'cycle-counter\.json'
    'nmae-handoff-latest\.md'
    'directive-board'
  )
  for A in "${ALLOW_PATTERNS[@]}"; do
    if printf '%s' "$CMD" | grep -qE "$A"; then
      exit 0
    fi
  done
  cat >&2 <<EOF
🚫 helper 실행 차단 — helper 는 relay 전담이며 실행 작업 권한이 없다 (사용자 2026-05-26 "권한 다 뽑아").
차단된 명령: $CMD
허용된 것만: discord-reply.sh(사용자 답장) · tmux send-keys -t mobruji(nmae 위임) · queue/target/status 파일 갱신·읽기.
작업/조사가 필요하면 직접 하지 말고 nmae 에 위임하라:
  tmux send-keys -t mobruji "<지시문>" Enter
비상 우회(사용자 명시 권한 후): MOBRUJI_ALLOW_DIRECT=1 <명령>
EOF
  exit 2
fi

# ===== cwd 추출 + realpath 정규화 =====
CWD_RAW="$(printf '%s' "$INPUT" | python3 -c "import json,sys
try:
    print(json.load(sys.stdin).get('cwd',''))
except Exception:
    pass")"

# realpath 로 symlink/.. 우회 가드. 실패 시 raw 사용 (안전 fallback).
if [ -n "$CWD_RAW" ]; then
  CWD_REAL="$(realpath -m "$CWD_RAW" 2>/dev/null || printf '%s' "$CWD_RAW")"
else
  CWD_REAL=""
fi

# ===== [신설] sub-agent role context 통과 =====
# 조건 B: MOBRUJI_ROLE env subagent role 매핑
case "${MOBRUJI_ROLE:-}" in
  backend|frontend|review|plan|subagent|be|fe|rev)
    [ "${MOBRUJI_HOOK_DEBUG:-0}" = "1" ] && audit_log "subagent-role" "$CWD_REAL"
    exit 0
    ;;
esac

# 조건 A: cwd realpath 화이트리스트
# - /home/mobruji/mobruji-{be,fe,rev,plan} 정확 매칭
# - /tmp/mobruji-* 임시 워크트리
# - /home/mobruji/mobruji-tmp-* 임시 워크트리
SUBAGENT_CWD_PATTERNS=(
  '^/home/mobruji/mobruji-be(/|$)'
  '^/home/mobruji/mobruji-fe(/|$)'
  '^/home/mobruji/mobruji-rev(/|$)'
  '^/home/mobruji/mobruji-plan(/|$)'
  '^/tmp/mobruji-[^/]+(/|$)'
  '^/home/mobruji/mobruji-tmp-[^/]+(/|$)'
)
if [ -n "$CWD_REAL" ]; then
  for PATTERN in "${SUBAGENT_CWD_PATTERNS[@]}"; do
    if printf '%s' "$CWD_REAL" | grep -qE "$PATTERN"; then
      [ "${MOBRUJI_HOOK_DEBUG:-0}" = "1" ] && audit_log "subagent-cwd" "$CWD_REAL"
      exit 0
    fi
  done
fi

# ===== nmae 본진 (cwd=/home/mobruji/mobruji): 기존 blocklist =====
if [ "$CWD_REAL" != "/home/mobruji/mobruji" ]; then
  # 그 외 cwd 는 안전 default allow
  exit 0
fi
LEAD='(^|[;&|`(]|^[[:space:]]*[A-Z_]+=[^[:space:]]*[[:space:]])[[:space:]]*'
FORBIDDEN_PATTERNS=(
  "${LEAD}gh[[:space:]]+pr[[:space:]]+(create|merge|edit|close|review|reopen|ready)"
  "${LEAD}gh[[:space:]]+release[[:space:]]+(create|edit|delete)"
  "${LEAD}git[[:space:]]+tag[[:space:]]+"
  "${LEAD}git[[:space:]]+push[[:space:]]+[^\"']*--tags"
  "${LEAD}git[[:space:]]+push[[:space:]]+[^\"']*(--force|--force-with-lease|[[:space:]]-f[[:space:]])"
  "${LEAD}git[[:space:]]+push[[:space:]]+origin[[:space:]]+main"
)
for PATTERN in "${FORBIDDEN_PATTERNS[@]}"; do
  if printf '%s' "$CMD" | grep -qE "$PATTERN"; then
    echo "🚫 직접 작업 차단 (PR/release/tag/main push 는 위임). 명령: $CMD" >&2
    echo "비상 우회: MOBRUJI_ALLOW_DIRECT=1 <명령>" >&2
    echo "sub-agent 시: MOBRUJI_ROLE 환경변수 또는 sub-agent 워크트리 cwd 사용 (PR #1146 spec)" >&2
    exit 2
  fi
done
exit 0
