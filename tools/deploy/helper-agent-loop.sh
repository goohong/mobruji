#!/usr/bin/env bash
# helper-agent-loop.sh — helper 에이전트 무한 루프 가동.
# 이 스크립트는 tmux 세션 내부에서 실행되거나, systemd 에 의해 직접 가동될 수 있습니다.

# Claude CLI 경로 (npm-global)
CLAUDE_BIN="/home/mobruji/.npm-global/bin/claude"

echo "Starting Helper Agent Loop..."

while true; do
    echo "[$(date)] Launching Claude CLI..."
    # --non-interactive 는 쓰지 않음 (tmux attach 로 개입 가능해야 함)
    "$CLAUDE_BIN"
    EXIT_CODE=$?
    echo "[$(date)] Claude CLI exited with code $EXIT_CODE."
    
    if [ $EXIT_CODE -eq 0 ]; then
        echo "Normal exit. Restarting in 5s..."
    else
        echo "Crash or error detected. Restarting in 10s..."
        sleep 5
    fi
    sleep 5
done
