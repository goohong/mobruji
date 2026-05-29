#!/bin/bash
# mobruji-maestro.service 가 호출하는 런처 — 안정 경로 역할 프롬프트를 system prompt 로 주입.
# 브랜치 전환에 안 흔들리도록 repo 밖(~/.mobruji)에 둔다.

# 2026-05-29: Claude Code auto-updater 끄기 — TUI 가 "Auto-update failed" prompt 로
# input mode 차단되는 사고 영구 방지. spec: docs/features/disable-claude-autoupdater.md.
export DISABLE_AUTOUPDATER=1

exec /usr/bin/claude --dangerously-skip-permissions --append-system-prompt "$(cat /home/mobruji/.mobruji/nmae-role.md)"
