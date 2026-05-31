#!/usr/bin/env bash
# mmae-launch.sh — mac mobruji 본 세션 시작 wrapper (#1376, 2026-05-30).
#
# spec: docs/features/pr-webhook-rev-forum.md (옵션 D, PR 2-a).
# 이슈: #1376 (mmae actor 환경 변수 박는 path, PR #1373 #1372 follow-up).
#
# 배경:
#   PR #1373 가 4 sub-agent (be/fe/rev/plan) 작업 폴더 자동 판단 cover.
#   PR #1375 가 NCP nmae Environment= 박는 path.
#   mac mmae (사용자 직접 claude 명령으로 시작) 는 wrapper 없으면
#   MOBRUJI_HOOK_ACTOR 환경 변수 박는 path 없음 → pr-register-rev.sh hook 가
#   actor 가드 silent exit 0 → 자동 등록 흐름 작동 X.
#
# 사용자 manual 셋업 (mac 1 회):
#   ln -sf ~/workspace/github/mobruji/tools/discord-daemon/mmae-launch.sh \
#         ~/.mobruji/mmae-launch.sh
#   echo "alias mmae='~/.mobruji/mmae-launch.sh'" >> ~/.zshrc
#   source ~/.zshrc
#
# 이후 mmae 명령으로 Claude Code 띄우면 MOBRUJI_HOOK_ACTOR=mmae 박혀
# pr-register-rev.sh hook 가 PR 만들 때 자동 등록 흐름 발화.
#
# 검증:
#   mmae 세션에서 `env | grep MOBRUJI_HOOK_ACTOR` → `MOBRUJI_HOOK_ACTOR=mmae`.

set -uo pipefail

# mmae actor marker — pr-register-rev.sh hook 가 본 변수 보고 자동 등록 흐름 발화.
export MOBRUJI_HOOK_ACTOR=mmae

# 추가 환경 변수 — pr-register-rev.sh 의 Python direct 호출 path 가 필요로 함.
# mac 의 nmae venv / agent dir 가 없으므로 미설정 시 hook 가 graceful skip
# (alert counter 증가). 사용자 환경에 맞춰 export 권고.
# export MOBRUJI_AGENT_PYTHON="$HOME/.mobruji/venv/bin/python3"  # 예시
# export MOBRUJI_AGENT_DIR="$HOME/workspace/github/mobruji/tools/agent"

# Claude Code CLI 본체 호출 — 인자 그대로 전달.
exec claude "$@"
