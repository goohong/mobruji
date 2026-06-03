#!/usr/bin/env bash
# tools/cycle-backlog/upsert.sh — cycle (be/fe/rev/plan) 백로그 forum thread upsert.
#
# 배경 (2026-05-26 사용자 정정):
#   "각 agent 가 자기 계획이 있어야지. 백로그 보면서 작업 안 까먹고 다 진행하고."
#   학습 의존 / 메모리 룰 = 굴복 패턴. 봇/스크립트 강제 (= 헌법) 가 정답.
#
# 흐름:
#   1) GitHub API 로 cycle 별 open + reviewed:claude 없는 PR + open issue 산출.
#      (--source=github 기본 — 외부 jsonl/file 도 옵션으로 받음)
#   2) markdown checkbox 본문 빌드:
#        ## <cycle> 백로그 (갱신 <ISO>)
#        - [ ] [#N] title (라벨)
#        - [ ] (issue) [#M] issue title
#   3) `discord-reply.sh --cycle-backlog-upsert <cycle> "<markdown>"` 호출 →
#      `[BACKLOG] <cycle>` forum thread upsert (없으면 신규, 있으면 starter PATCH).
#
# 사용:
#   tools/cycle-backlog/upsert.sh <cycle> [--dry-run] [--source github|file <path>]
#   <cycle>: be | fe | rev | plan
#
# 환경:
#   REPO=goohong/mobruji         GitHub repo
#   GH_BIN=gh                    gh CLI override
#   DISCORD_REPLY_SH=~/.mobruji/discord-reply.sh   discord-reply.sh 위치
#   CYCLE_BACKLOG_NO_DISCORD=1   discord push skip (dry-run 동등)
#
# 출력:
#   - stdout: thread_id (성공 시) 또는 빈 줄.
#   - stderr: 진행 로그.
#
# 관련:
#   - 사용자 정정 (2026-05-26 12:24 KST)
#   - tools/discord-daemon/discord-reply.sh --cycle-backlog-upsert
#   - tools/agent-launch-wrapper.sh (launch 직전 본 wrapper 호출)
#   - docs/features/work-cycle-simplification.md (plan 사이클 spec)
#
# DEPRECATED (2026-06-03) — `[BACKLOG] <cycle>` 단일 스레드 방식 폐기
# (cycle-forum-operation.md §3·§5-7). 현 표준은 "작업마다 개별 thread + 4태그".
# 본 스크립트는 더 이상 [BACKLOG] 스레드를 신설/갱신하지 않는다 (회귀 차단). 호출은
# deprecation warning stderr emit 후 no-op exit 0 (호출자 호환). 실제 thread-creating
# tool (discord-reply.sh --cycle-backlog-upsert) 도 동일하게 no-op 으로 차단된다.

set -euo pipefail

if [[ "${CYCLE_BACKLOG_FORCE:-0}" != "1" ]]; then
  echo "tools/cycle-backlog/upsert.sh: [DEPRECATED] [BACKLOG] 단일 스레드 방식 폐기됨 — no-op (cycle-forum-operation.md §5-7). 개별 스레드 + 4태그 사용." >&2
  exit 0
fi

usage() {
  cat >&2 <<'USAGE'
usage: tools/cycle-backlog/upsert.sh <cycle> [--dry-run] [--source github|file <path>]

  <cycle>             be | fe | rev | plan
  --dry-run           markdown 본문만 stdout 으로 print, discord push 안 함.
  --source github     GitHub PR/issue 로부터 자동 빌드 (default).
  --source file PATH  외부 jsonl/markdown 파일 본문 그대로 사용.

환경:
  REPO=goohong/mobruji        GitHub repo
  GH_BIN=gh                   gh CLI override
  DISCORD_REPLY_SH=...        discord-reply.sh 위치 (default: ~/.mobruji/discord-reply.sh)
  CYCLE_BACKLOG_NO_DISCORD=1  push skip (dry-run 과 동등)
USAGE
  exit 2
}

if [[ $# -lt 1 ]]; then
  usage
fi

CYCLE="$1"
shift
case "$CYCLE" in
  be|fe|rev|plan) ;;
  *) echo "ERROR: cycle must be one of be|fe|rev|plan (got: $CYCLE)" >&2; usage ;;
esac

DRY_RUN=0
SOURCE="github"
SOURCE_PATH=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) DRY_RUN=1; shift ;;
    --source)
      shift
      [[ $# -gt 0 ]] || { echo "ERROR: --source requires value" >&2; exit 2; }
      case "$1" in
        github) SOURCE="github"; shift ;;
        file)
          SOURCE="file"; shift
          [[ $# -gt 0 ]] || { echo "ERROR: --source file requires PATH" >&2; exit 2; }
          SOURCE_PATH="$1"; shift
          ;;
        *) echo "ERROR: unknown --source $1" >&2; exit 2 ;;
      esac
      ;;
    -h|--help) usage ;;
    *) echo "ERROR: unknown arg $1" >&2; usage ;;
  esac
done

REPO="${REPO:-goohong/mobruji}"
GH_BIN="${GH_BIN:-gh}"
DISCORD_REPLY_SH="${DISCORD_REPLY_SH:-${HOME:-/tmp}/.mobruji/discord-reply.sh}"

# cycle ↔ session label / scope label 매핑.
# session:<be|fe|rev|plan> 라벨이 정식 (이슈 #1002, .github/workflows/auto-label.yml).
# 또한 scope: 라벨로 fallback.
session_label_for_cycle() {
  case "$1" in
    be)   echo "session:backend" ;;
    fe)   echo "session:frontend" ;;
    rev)  echo "session:rev" ;;
    plan) echo "session:plan" ;;
  esac
}

scope_label_for_cycle() {
  case "$1" in
    be)   echo "scope:backend" ;;
    fe)   echo "scope:web" ;;
    rev)  echo "" ;;   # rev 는 scope 가 다양 — 라벨로 안 좁힘.
    plan) echo "" ;;   # plan 도 scope 가 다양 (docs / infra 등).
  esac
}

build_markdown_from_github() {
  local cycle="$1"
  local session_label scope_label timestamp
  session_label=$(session_label_for_cycle "$cycle")
  scope_label=$(scope_label_for_cycle "$cycle")
  timestamp=$(date -u +%Y-%m-%dT%H:%M:%SZ)

  local pr_jq
  if [[ -n "$scope_label" ]]; then
    # session 라벨 OR scope 라벨 매치 (둘 다 cycle 작업).
    pr_jq=$(printf '[.[] | select((.labels | map(.name) | contains(["%s"])) or (.labels | map(.name) | contains(["%s"])))]' \
      "$session_label" "$scope_label")
  else
    pr_jq=$(printf '[.[] | select(.labels | map(.name) | contains(["%s"]))]' "$session_label")
  fi

  echo "## ${cycle} 백로그 (갱신 ${timestamp})"
  echo
  echo "_본 thread 는 \`tools/cycle-backlog/upsert.sh ${cycle}\` 가 자동 갱신합니다._"
  echo

  echo "### Open PR (rev pass 대기 / 머지 대기)"
  local pr_section
  pr_section=$("$GH_BIN" pr list -R "$REPO" --state open --limit 50 \
    --json number,title,labels,createdAt,isDraft,mergeable \
    --jq "${pr_jq} | sort_by(.createdAt) | .[] | \"- [ ] [#\(.number)](\(\"https://github.com/${REPO}/pull/\\(.number)\")) \(.title) \(if .isDraft then \"(draft)\" else \"\" end) \(if (.labels | map(.name) | contains([\"reviewed:claude\"])) then \"✅rev\" else \"\" end)\"" \
    2>/dev/null || true)
  if [[ -z "$pr_section" ]]; then
    echo "- (없음)"
  else
    echo "$pr_section"
  fi
  echo

  echo "### Open Issue (next 후보)"
  local issue_section
  issue_section=$("$GH_BIN" issue list -R "$REPO" --state open --limit 30 \
    --label "$session_label" \
    --json number,title,labels,createdAt \
    --jq '[.[] | select(.labels | map(.name) | contains(["task"]))] | sort_by(.createdAt) | .[] | "- [ ] [#\(.number)](https://github.com/'$REPO'/issues/\(.number)) \(.title)"' \
    2>/dev/null || true)
  if [[ -z "$issue_section" ]]; then
    echo "- (없음)"
  else
    echo "$issue_section"
  fi
}

# ── markdown 본문 빌드 ──
case "$SOURCE" in
  github)
    BODY=$(build_markdown_from_github "$CYCLE")
    ;;
  file)
    if [[ ! -r "$SOURCE_PATH" ]]; then
      echo "ERROR: --source file PATH 못 읽음 ($SOURCE_PATH)" >&2
      exit 3
    fi
    BODY=$(cat "$SOURCE_PATH")
    ;;
esac

# discord 메시지 길이 제한 (2000 자) — 초과 시 잘라내고 truncated 표시.
# UTF-8 multibyte 고려: wc -m 으로 character 수 측정.
MAX_LEN=1900
BODY_LEN=$(printf '%s' "$BODY" | wc -m)
if [[ "$BODY_LEN" -gt "$MAX_LEN" ]]; then
  echo "WARN: backlog body $BODY_LEN chars > $MAX_LEN — truncating" >&2
  BODY=$(printf '%s' "$BODY" | head -c $((MAX_LEN - 40)))
  BODY="${BODY}

_…(잘림: 전체는 GitHub 에서)_"
fi

if [[ "$DRY_RUN" -eq 1 ]] || [[ "${CYCLE_BACKLOG_NO_DISCORD:-0}" == "1" ]]; then
  echo "─── dry-run: cycle=$CYCLE body ───" >&2
  printf '%s\n' "$BODY"
  exit 0
fi

if [[ ! -x "$DISCORD_REPLY_SH" ]]; then
  echo "ERROR: discord-reply.sh 실행 불가 ($DISCORD_REPLY_SH)" >&2
  exit 4
fi

# upsert 호출 — stdout = thread_id.
THREAD_ID=$("$DISCORD_REPLY_SH" --cycle-backlog-upsert "$CYCLE" "$BODY")
if [[ -z "$THREAD_ID" ]]; then
  echo "ERROR: cycle-backlog upsert 실패 (cycle=$CYCLE)" >&2
  exit 5
fi

printf '%s\n' "$THREAD_ID"
