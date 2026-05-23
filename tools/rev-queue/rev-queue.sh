#!/usr/bin/env bash
# rev-queue.sh — rev sub-agent 가 처리할 PR 목록을 stage 별로 산출
#
# 사용:
#   rev-queue.sh stage1     # 머지 전 (reviewed:claude 라벨 없음)
#   rev-queue.sh stage2     # develop 머지 1h+ 후 (rev-post-merge-pass 라벨 없음)
#   rev-queue.sh stage3     # 최근 release 의 PR (rev-prod-pass 라벨 없음)
#   rev-queue.sh all        # 3 stage 모두
#
# 환경변수:
#   REPO=<owner/name>       # default goohong/mobruji
#   GH_BIN=<path>           # default gh (mock 주입용)
#
# 의존: gh CLI (mobruji repo 인증 완료) + bash 4+ + date(GNU)
#
# 설계 의도 (사용자 2026-05-24):
#   메모리/룰 학습 의존 X — GitHub 라벨 + 본 스크립트가 단일 진실 (single source of truth).
#   rev sub-agent 는 매 사이클 첫 액션으로 본 스크립트 호출 → 출력 큐 따라 처리.
#   처리 완료 시 PR 에 라벨 부착 → 다음 호출에서 자동 제외.

set -euo pipefail

STAGE="${1:?stage required: stage1|stage2|stage3|all}"
REPO="${REPO:-goohong/mobruji}"
GH_BIN="${GH_BIN:-gh}"

stage1() {
  echo "## Stage 1 — PR 머지 전 (reviewed:claude 라벨 없는 open PR)"
  local result
  result=$("$GH_BIN" pr list -R "$REPO" --state open --json number,title,labels,createdAt \
    --jq '[.[] | select(.labels | map(.name) | contains(["reviewed:claude"]) | not)]
          | sort_by(.createdAt)
          | .[] | "  - #\(.number) (\(.createdAt[:10])) \(.title)"' \
    2>/dev/null || true)
  if [[ -z "$result" ]]; then
    echo "  (없음)"
  else
    echo "$result"
  fi
}

stage2() {
  echo "## Stage 2 — develop 머지 후 1시간+ rev-post-merge-pass 라벨 없음"
  local cutoff
  cutoff=$(date -u -d '1 hour ago' +%Y-%m-%dT%H:%M:%SZ)
  local result
  result=$("$GH_BIN" pr list -R "$REPO" --state merged --base develop --limit 30 \
    --json number,title,labels,mergedAt \
    --jq "[.[] | select(.mergedAt != null and .mergedAt < \"$cutoff\")
                 | select(.labels | map(.name) | contains([\"rev-post-merge-pass\"]) | not)]
          | sort_by(.mergedAt)
          | .[] | \"  - #\(.number) (\(.mergedAt[:16])) \(.title)\"" \
    2>/dev/null || true)
  if [[ -z "$result" ]]; then
    echo "  (없음)"
  else
    echo "$result"
  fi
}

stage3() {
  echo "## Stage 3 — 최근 release tag 의 PR 중 rev-prod-pass 라벨 없음"
  local latest_release
  latest_release=$("$GH_BIN" release list -R "$REPO" --limit 1 --json tagName \
    --jq '.[0].tagName // ""' 2>/dev/null || echo "")
  if [[ -z "$latest_release" ]]; then
    echo "  (release 없음)"
    return
  fi
  echo "  최근 release: $latest_release"

  local body
  body=$("$GH_BIN" release view "$latest_release" -R "$REPO" --json body --jq '.body // ""' 2>/dev/null || echo "")
  if [[ -z "$body" ]]; then
    echo "  (release body 없음)"
    return
  fi

  local pr_numbers
  pr_numbers=$(echo "$body" | grep -oE '#[0-9]+' | sort -u | sed 's/#//')
  if [[ -z "$pr_numbers" ]]; then
    echo "  (release 노트에 PR 번호 없음)"
    return
  fi

  local found=0
  while read -r pr_num; do
    [[ -z "$pr_num" ]] && continue
    local labels title
    labels=$("$GH_BIN" pr view "$pr_num" -R "$REPO" --json labels \
      --jq '[.labels[].name] | join(",")' 2>/dev/null || echo "")
    if [[ "$labels" != *"rev-prod-pass"* ]]; then
      title=$("$GH_BIN" pr view "$pr_num" -R "$REPO" --json title --jq '.title' 2>/dev/null || echo "(unknown)")
      echo "  - #$pr_num $title"
      found=1
    fi
  done <<< "$pr_numbers"

  if [[ "$found" -eq 0 ]]; then
    echo "  (없음)"
  fi
}

case "$STAGE" in
  stage1) stage1 ;;
  stage2) stage2 ;;
  stage3) stage3 ;;
  all)
    stage1
    echo
    stage2
    echo
    stage3
    ;;
  *)
    echo "Unknown stage: $STAGE" >&2
    echo "Usage: rev-queue.sh stage1|stage2|stage3|all" >&2
    exit 1
    ;;
esac
