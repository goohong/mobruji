#!/usr/bin/env bash
# setup-helper-tmux.sh — helper tmux 세션 초기화 및 claude CLI 로그인 안내.

SESSION_NAME="helper"

if tmux has-session -t "$SESSION_NAME" 2>/dev/null; then
    echo "tmux session '$SESSION_NAME' already exists."
else
    echo "Creating tmux session '$SESSION_NAME'..."
    tmux new-session -d -s "$SESSION_NAME"
    echo "Session created."
fi

echo "----------------------------------------------------------------------"
echo "Next steps:"
echo "1. Attach to the session: tmux attach -t $SESSION_NAME"
echo "2. Login to Claude CLI: claude --login"
echo "3. (Optional) Set up pipe-pane for debugging:"
echo "   tmux pipe-pane -t $SESSION_NAME:0.0 'cat >> /tmp/helper-pane.log'"
echo "----------------------------------------------------------------------"
