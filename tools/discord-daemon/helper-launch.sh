#!/bin/bash
# mobruji-helper.service 가 호출하는 런처 — 안정 경로 역할 프롬프트를 system prompt 로 주입.
# 브랜치 전환에 안 흔들리도록 repo 밖(~/.mobruji)에 둔다.

# spec: docs/features/helper-tool-visibility.md (2026-05-29).
# PreToolUse hook (.claude/settings.json) 의 helper-tool-progress.sh 가 본 marker
# 를 검사해 helper 본체만 도구 호출 가시화 push. sub-agent / nmae 호출 시엔
# marker 없음 → hook skip (noise 차단). 자식 process (claude) 에 env 상속.
export MOBRUJI_HOOK_ACTOR=helper

exec /usr/bin/claude --dangerously-skip-permissions --append-system-prompt "$(cat /home/mobruji/.mobruji/helper-role.md)"
