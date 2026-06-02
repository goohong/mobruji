#!/usr/bin/env bash
# GitHub 라벨 일괄 생성 스크립트
# 사용법: gh auth login 후 ./scripts/setup-labels.sh
# 이미 존재하는 라벨은 --force로 색상/설명만 갱신한다.

set -euo pipefail

if ! command -v gh >/dev/null 2>&1; then
  echo "gh CLI가 설치돼 있지 않습니다. https://cli.github.com/ 에서 설치하세요." >&2
  exit 1
fi

create() {
  local name="$1" color="$2" desc="$3"
  gh label create "$name" --color "$color" --description "$desc" --force
}

# 운영
create "task"               "0E8A16" "이슈 템플릿 기본 라벨"
create "Post-Review"        "D93F0B" "예외 머지 후 24시간 내 사후 리뷰 대상"

# Type
create "type:feat"          "1D76DB" "신규 기능"
create "type:fix"           "D73A4A" "버그 수정"
create "type:refactor"      "5319E7" "리팩터링"
create "type:chore"         "C5DEF5" "잡일/설정"
create "type:docs"          "0075CA" "문서"
create "type:test"          "BFD4F2" "테스트"
create "type:style"         "FBCA04" "코드 스타일/포맷"
create "type:release"       "006B75" "릴리즈 PR (develop → main)"

# Scope (도메인) — mobruji 화이트리스트: user / song / recommendation / voice / infra / web / feedback
create "scope:user"           "EDEDED" "도메인: user (회원/인증)"
create "scope:song"           "EDEDED" "도메인: song (곡 메타데이터/카탈로그)"
create "scope:recommendation" "EDEDED" "도메인: recommendation (추천 알고리즘)"
create "scope:voice"          "EDEDED" "도메인: voice (음역대 진단)"
create "scope:infra"          "EDEDED" "도메인: infra (CI/CD/배포/DB/모노레포)"
create "scope:web"            "EDEDED" "도메인: web (Next.js 프론트엔드)"
create "scope:feedback"       "EDEDED" "도메인: feedback (좋아요/북마크/리뷰 등 사용자 피드백)"

# AI 운영
create "ai-generated"       "8A2BE2" "AI 보조/생성으로 작성된 PR (우산 라벨)"
create "ai:claude"          "5A2BC0" "Claude가 작성한 PR"
create "ai:codex"           "10A37E" "Codex가 작성한 PR"
create "needs-human-review" "B60205" "보호 영역 변경 PR (사람 사후 리뷰 권장)"

# Session (다중 세션 운영)
create "session:backend"  "1F77B4" "백엔드 구현 세션이 진행 중인 PR"
create "session:frontend" "2CA02C" "프론트엔드 구현 세션이 진행 중인 PR"
create "session:review"   "9467BD" "리뷰 세션의 산출물 (코멘트로 진행, 브랜치 없음)"
create "session:plan"     "FF7F0E" "기획 세션(docs/ADR/spec)이 진행 중인 PR"
create "reviewed:claude"  "BCBD22" "Claude 리뷰 세션이 검토 완료한 PR"

# 자동 재작업 고리 (pr-rework-auto-loop.md)
create "rev:changes-requested" "D93F0B" "rev 가 결함 발견 — 자동 재작업 대상 (rev:hold 와 구분)"
create "ci:failed"             "B60205" "required check FAIL 멱등 가시화 마커"
create "rework:in-progress"    "FBCA04" "fix 모드 재작업 directive 적재됨 (중복 적재 가드)"
create "rework:exhausted"      "5319E7" "자동 재작업 상한 소진 — 사람 개입 필요"

echo "완료."
