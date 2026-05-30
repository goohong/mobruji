#!/usr/bin/env bash
# pr-register-rev.sh — Claude Code PostToolUse hook → rev directive 자동 등록.
#
# spec: docs/features/pr-webhook-rev-forum.md (2026-05-30 round 19, 옵션 D 채택).
# 이슈: #1360 (PR 2-a — hook script + settings.json 등록).
#
# 배경:
#   PR 생성·머지를 trigger 한 actor (helper / be / fe / nmae / rev / plan) 가
#   `gh pr create` / `gh pr merge` 호출 시 Claude Code 의 PostToolUse hook 으로
#   본 script 가 발사 → `register_directive_pending(kind=pr_review|pr_audit, ...)`
#   호출 → rev 작업 큐 entry + rev forum thread 라이프사이클.
#
# Range:
#   helper / nmae / be / fe / rev / plan 6 actor 모두 — MOBRUJI_HOOK_ACTOR env
#   marker 가드 (helper-tool-progress.sh 는 helper 만이었으나 본 hook 은 6 actor 허용).
#
# 입력 (stdin JSON, Claude Code PostToolUse hook spec):
#   {"hook_event_name": "PostToolUse", "tool_name": "Bash",
#    "tool_input": {"command": "gh pr create ..."},
#    "tool_response": {"output": "https://github.com/.../pull/1234"}}
#
# 출력:
#   - stdout: 빈 줄 (hook 가 stdout 을 컨텍스트에 추가하지 않도록).
#   - exit 0 항상 (hook 실패가 actor 도구 호출 자체 차단 금지 — graceful).
#
# 환경 변수:
#   MOBRUJI_HOOK_ACTOR              — helper / nmae / be / fe / rev / plan 중 1.
#                                     unset / unknown 시 silent skip (defense).
#   MOBRUJI_PR_REGISTER_REV=0       — 임시 OFF (개발/디버깅).
#   MOBRUJI_DIR=~/.mobruji          — dedupe / log / failures 파일 위치.
#   MOBRUJI_AGENT_PYTHON            — Python interpreter (venv) path. 미설정 시
#                                     ${MOBRUJI_DIR}/venv/bin/python3 → python3
#                                     순으로 fallback.
#   MOBRUJI_AGENT_DIR               — tools/agent dir path (PYTHONPATH 박제).
#                                     미설정 시 ${MOBRUJI_DIR}/agent fallback.
#   MOBRUJI_PR_REGISTER_FAIL_THRESH — DIGEST 채널 alert 발사 threshold (default 5).
#
# 자율 default (사용자 redirect 가능):
#   - Q11: 연속 N=5 실패 시 DIGEST 채널 push.
#   - Q12: Python direct 호출 (venv + PYTHONPATH wrapper).
#   - Q13: per-worktree `.claude/settings.json` 등록.

set -uo pipefail

# graceful exit — 어떤 단계 실패해도 actor 의 도구 호출 자체 차단하지 않는다.
exit_graceful() {
  exit 0
}
trap exit_graceful ERR

# ─── 가드 1: actor marker ────────────────────────────────────────────────────
# helper-tool-progress.sh 는 helper 만 허용. 본 hook 은 6 actor 허용.
case "${MOBRUJI_HOOK_ACTOR:-}" in
  helper|nmae|be|fe|rev|plan) ;;
  *) exit 0 ;;
esac

# ─── 가드 2: 임시 OFF ────────────────────────────────────────────────────────
if [[ "${MOBRUJI_PR_REGISTER_REV:-1}" == "0" ]]; then
  exit 0
fi

# ─── 가드 3: 의존성 ──────────────────────────────────────────────────────────
if ! command -v jq >/dev/null 2>&1; then
  exit 0
fi

MOBRUJI_DIR="${MOBRUJI_DIR:-${HOME:-/tmp}/.mobruji}"
DEDUPE_FILE="${MOBRUJI_DIR}/pr-register-rev-dedupe.jsonl"
LOG_FILE="${MOBRUJI_DIR}/pr-register-rev.log"
FAIL_COUNT_FILE="${MOBRUJI_DIR}/pr-register-rev-failures.txt"
FAIL_THRESHOLD="${MOBRUJI_PR_REGISTER_FAIL_THRESH:-5}"

mkdir -p "$MOBRUJI_DIR" 2>/dev/null || true

# ─── stdin JSON read (max 64KB) ──────────────────────────────────────────────
INPUT=$(head -c 65536)
[[ -z "$INPUT" ]] && exit 0

# malformed JSON → silent exit 0.
if ! printf '%s' "$INPUT" | jq -e . >/dev/null 2>&1; then
  exit 0
fi

# ─── hook event + tool 매칭 ──────────────────────────────────────────────────
HOOK_EVENT=$(printf '%s' "$INPUT" | jq -r '.hook_event_name // empty' 2>/dev/null || echo "")
TOOL=$(printf '%s' "$INPUT" | jq -r '.tool_name // empty' 2>/dev/null || echo "")
[[ "$HOOK_EVENT" != "PostToolUse" ]] && exit 0
[[ "$TOOL" != "Bash" ]] && exit 0

CMD=$(printf '%s' "$INPUT" | jq -r '.tool_input.command // empty' 2>/dev/null || echo "")
[[ -z "$CMD" ]] && exit 0

# ─── 정규식 매칭: gh pr create / gh pr merge ────────────────────────────────
# false positive 가드:
#   - `gh pr create-comment` 는 매칭 X (gh CLI 의 별 subcommand 부재 — 단, 사용자
#     wrapper 가능성 차단). regex 끝에 \b (word boundary) 또는 공백/EOL.
#   - `gh pr merge --auto` 는 auto-merge 활성화만, 실제 머지 X → 가드.
#   - HEREDOC / quoted command 변형 — CMD 자체에 newline 가능 → head -1 후 매칭.
CMD_FIRST=$(printf '%s' "$CMD" | head -1)

KIND=""
if [[ "$CMD_FIRST" =~ (^|[^a-zA-Z0-9_-])gh[[:space:]]+pr[[:space:]]+create([[:space:]]|$) ]]; then
  KIND="pr_review"
elif [[ "$CMD_FIRST" =~ (^|[^a-zA-Z0-9_-])gh[[:space:]]+pr[[:space:]]+merge([[:space:]]|$) ]]; then
  # gh pr merge --auto 가드 — auto-merge 활성화만, 실제 머지 X.
  # (Q14 case 14 — `gh pr merge --auto` 는 실제 머지 시 별 hook trigger 의도)
  if [[ "$CMD_FIRST" =~ [[:space:]]--auto([[:space:]]|$) ]]; then
    exit 0
  fi
  KIND="pr_audit"
else
  exit 0
fi

# ─── PR URL parse ────────────────────────────────────────────────────────────
OUTPUT=$(printf '%s' "$INPUT" | jq -r '.tool_response.output // .tool_response // empty' 2>/dev/null || echo "")

PR_URL=""
# 우선순위 1: tool_response.output 에서 PR URL 직접 추출.
# grep 매칭 0건 시 exit 1 → trap ERR 발사 차단을 위해 || true.
PR_URL=$(printf '%s' "$OUTPUT" | grep -oE 'https://github\.com/[^/[:space:]]+/[^/[:space:]]+/pull/[0-9]+' 2>/dev/null | head -1 || true)

# 우선순위 2: tool_input.command 의 인자에서 URL 또는 번호 추출 (gh pr merge 1234).
if [[ -z "$PR_URL" ]]; then
  PR_URL=$(printf '%s' "$CMD" | grep -oE 'https://github\.com/[^/[:space:]]+/[^/[:space:]]+/pull/[0-9]+' 2>/dev/null | head -1 || true)
fi

# 우선순위 3: gh pr merge <num> — 번호만 있는 경우 `gh pr view --json url` 로 보완.
if [[ -z "$PR_URL" && "$KIND" == "pr_audit" ]]; then
  PR_NUM=$(printf '%s' "$CMD" | grep -oE 'gh[[:space:]]+pr[[:space:]]+merge[[:space:]]+[0-9]+' 2>/dev/null | grep -oE '[0-9]+$' 2>/dev/null | head -1 || true)
  if [[ -n "${PR_NUM:-}" ]] && command -v gh >/dev/null 2>&1; then
    PR_URL=$(gh pr view "$PR_NUM" --json url --jq .url 2>/dev/null || true)
    PR_URL="${PR_URL:-}"
  fi
fi

if [[ -z "$PR_URL" ]]; then
  # warning log + graceful exit 0 — actor 흐름 차단 X.
  printf '[%s] %s: PR URL parse 실패 — kind=%s cmd=%s\n' \
    "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "WARN" "$KIND" "$(printf '%s' "$CMD_FIRST" | head -c 100)" \
    >> "$LOG_FILE" 2>/dev/null || true
  exit 0
fi

# ─── 멱등성 가드: dedupe lookup ──────────────────────────────────────────────
# 같은 (pr_url, kind) 24h 안 hit 시 skip.
NOW_TS=$(date +%s)
DEDUPE_WINDOW=86400  # 24h.

if [[ -f "$DEDUPE_FILE" ]]; then
  # malformed line 무시 + JSON parse 실패 시 skip.
  while IFS= read -r line; do
    [[ -z "$line" ]] && continue
    entry_url=$(printf '%s' "$line" | jq -r '.pr_url // empty' 2>/dev/null || echo "")
    entry_kind=$(printf '%s' "$line" | jq -r '.kind // empty' 2>/dev/null || echo "")
    entry_ts=$(printf '%s' "$line" | jq -r '.ts // empty' 2>/dev/null || echo "")
    [[ -z "$entry_url" || -z "$entry_kind" || -z "$entry_ts" ]] && continue
    if [[ "$entry_url" == "$PR_URL" && "$entry_kind" == "$KIND" ]]; then
      # 24h 안 hit → dedupe skip.
      diff=$((NOW_TS - entry_ts))
      if [[ "$diff" -lt "$DEDUPE_WINDOW" ]]; then
        printf '[%s] %s: dedupe skip — pr_url=%s kind=%s (last_ts=%s)\n' \
          "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "INFO" "$PR_URL" "$KIND" "$entry_ts" \
          >> "$LOG_FILE" 2>/dev/null || true
        exit 0
      fi
    fi
  done < "$DEDUPE_FILE"
fi

# dedupe append (선처리 — register 호출 실패해도 next retry 시 dedupe miss 되지 않도록).
printf '{"pr_url":"%s","kind":"%s","ts":%d}\n' "$PR_URL" "$KIND" "$NOW_TS" \
  >> "$DEDUPE_FILE" 2>/dev/null || true

# cap 1000 FIFO truncate (best-effort).
DEDUPE_LINES=$(wc -l < "$DEDUPE_FILE" 2>/dev/null | tr -d ' ' || echo "0")
if [[ "$DEDUPE_LINES" -gt 1000 ]]; then
  tail -n 1000 "$DEDUPE_FILE" > "$DEDUPE_FILE.tmp" 2>/dev/null && \
    mv "$DEDUPE_FILE.tmp" "$DEDUPE_FILE" 2>/dev/null || true
fi

# ─── PR 번호 / directive_id 추출 ─────────────────────────────────────────────
PR_NUMBER=$(printf '%s' "$PR_URL" | grep -oE '/pull/[0-9]+$' 2>/dev/null | grep -oE '[0-9]+$' 2>/dev/null || true)
[[ -z "${PR_NUMBER:-}" ]] && exit 0

if [[ "$KIND" == "pr_review" ]]; then
  DIRECTIVE_ID="rev-${PR_NUMBER}-open"
  SUMMARY="PR #${PR_NUMBER} — rev 1차 review"
else
  DIRECTIVE_ID="rev-${PR_NUMBER}-merge"
  SUMMARY="PR #${PR_NUMBER} 머지 — 사후 E2E QA"
fi

# ─── Python direct 호출 (Q12=(b)) ────────────────────────────────────────────
# venv path 결정 — env override > MOBRUJI_DIR/venv > system python3.
PY_BIN="${MOBRUJI_AGENT_PYTHON:-}"
if [[ -z "$PY_BIN" ]]; then
  if [[ -x "${MOBRUJI_DIR}/venv/bin/python3" ]]; then
    PY_BIN="${MOBRUJI_DIR}/venv/bin/python3"
  else
    PY_BIN="python3"
  fi
fi
if ! command -v "$PY_BIN" >/dev/null 2>&1 && [[ ! -x "$PY_BIN" ]]; then
  # python3 fallback.
  PY_BIN="python3"
fi

# agent dir (PYTHONPATH) 결정.
AGENT_DIR="${MOBRUJI_AGENT_DIR:-${MOBRUJI_DIR}/agent}"

# register_directive_pending 호출 (실패 시 alert counter 증가).
# 시그니처는 PR 2-b 에서 확장 — 본 PR 단계에서는 호출 자체가 import 실패 가능.
# 그 경우도 graceful (counter 증가 + log + exit 0).
#
# trap ERR 우회 — Python 호출 자체가 non-zero 반환 시 trap 발사 차단.
# trap 임시 해제 + set +e 둘 다 사용 (bash trap ERR 은 set -e 와 별개).
trap - ERR
set +e
REG_LOG=$(MOBRUJI_AGENT_DIR="$AGENT_DIR" \
  PYTHONPATH="$AGENT_DIR:${PYTHONPATH:-}" \
  "$PY_BIN" -c "
import sys
import os
sys.path.insert(0, os.environ.get('MOBRUJI_AGENT_DIR', ''))
try:
    from tools_cycle import register_directive_pending
    result = register_directive_pending(
        directive_id='${DIRECTIVE_ID}',
        summary='${SUMMARY}',
        cycle_hint='rev',
        kind='${KIND}',
        pr_url='${PR_URL}',
        thread_id=None,
        source='pr_register_rev_hook',
    )
    print('OK', result)
except TypeError as exc:
    # PR 2-b 미머지 — 새 키워드 미지원. legacy 시그니처 fallback.
    try:
        result = register_directive_pending(
            directive_id='${DIRECTIVE_ID}',
            summary='${SUMMARY}',
            cycle_hint='rev',
        )
        print('OK_LEGACY', result)
    except Exception as inner:
        print('ERR_LEGACY', type(inner).__name__, inner)
        sys.exit(1)
except Exception as exc:
    print('ERR', type(exc).__name__, exc)
    sys.exit(1)
" 2>&1)
REG_RC=$?
set -u  # set -e 는 복구하지 않음 (이후 흐름 trap ERR 영향 회피).

if [[ "$REG_RC" -eq 0 ]]; then
  # 성공 → alert counter reset.
  echo "0" > "$FAIL_COUNT_FILE" 2>/dev/null || true
  printf '[%s] %s: register_directive_pending OK — pr_url=%s kind=%s directive_id=%s result=%s\n' \
    "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "INFO" "$PR_URL" "$KIND" "$DIRECTIVE_ID" "$REG_LOG" \
    >> "$LOG_FILE" 2>/dev/null || true
else
  # 실패 → alert counter 증가.
  CURRENT_FAIL=0
  if [[ -f "$FAIL_COUNT_FILE" ]]; then
    CURRENT_FAIL=$(cat "$FAIL_COUNT_FILE" 2>/dev/null | tr -d ' \n' || echo "0")
    [[ -z "$CURRENT_FAIL" ]] && CURRENT_FAIL=0
  fi
  NEW_FAIL=$((CURRENT_FAIL + 1))
  echo "$NEW_FAIL" > "$FAIL_COUNT_FILE" 2>/dev/null || true

  printf '[%s] %s: register_directive_pending FAIL (count=%d/%d) — pr_url=%s kind=%s err=%s\n' \
    "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "ERROR" "$NEW_FAIL" "$FAIL_THRESHOLD" "$PR_URL" "$KIND" "$REG_LOG" \
    >> "$LOG_FILE" 2>/dev/null || true

  # threshold 도달 시 DIGEST 채널 push (Q11=(b)).
  if [[ "$NEW_FAIL" -ge "$FAIL_THRESHOLD" ]]; then
    DISCORD_REPLY="${MOBRUJI_DIR}/discord-reply.sh"
    if [[ -x "$DISCORD_REPLY" ]]; then
      ALERT_MSG="⚠️ pr-register-rev hook 연속 ${NEW_FAIL}회 실패 — register_directive_pending 호출 갱신 의무 (kind=${KIND}, pr=${PR_URL})"
      "$DISCORD_REPLY" --channel=DIGEST "$ALERT_MSG" >/dev/null 2>&1 || true
      # alert 후 counter reset 하지 않음 — 사용자 재시작 또는 성공 시 reset.
    fi
  fi
fi

exit 0
