#!/usr/bin/env python3
"""Mobruji Task Worker — 작업 큐를 감시하고 실제 에이전트(claude)를 실행합니다.

Phase 2: Forum-Driven Architecture 의 실행 엔진.
- SQLite `tasks` 테이블을 폴링하며 PENDING 작업을 가져옴.
- 작업을 인지하면 해당 Discord 쓰레드에 "작업 시작" 알림.
- `claude` CLI 를 실행하여 작업을 수행 (현재는 tmux send-keys 로 안전하게 브리지).
"""

import os
import sys
import time
import json
import logging
import subprocess
from pathlib import Path

# bot.py 의 OpLedger 클래스 로드를 위해 경로 추가
sys.path.insert(0, str(Path(__file__).resolve().parent))
from bot import OpLedger, tmux_send_payload, ensure_tmux_session

# 설정 (환경변수)
DEDUP_LEDGER_PATH = os.environ.get("DEDUP_LEDGER_PATH", os.path.expanduser("~/.mobruji/discord-bridge.sqlite"))
TMUX_SESSION_NAME = os.environ.get("TMUX_SESSION_NAME", "mobruji")
# nmae 워커 pane — 기본 {session}:0.0. 명시 override 가능.
TMUX_TARGET_PANE = os.environ.get("TMUX_TARGET_PANE", f"{TMUX_SESSION_NAME}:0.0")
CLAUDE_BIN = os.environ.get("CLAUDE_BIN", "/usr/local/bin/claude")
POLL_INTERVAL = 5

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s :: %(message)s")
logger = logging.getLogger("mobruji-worker")


def format_worker_inject(task: dict) -> str:
    """nmae pane 에 주입할 thread-aware 프롬프트를 만듭니다.

    spec docs/features/discord-forum-ops-v2.md §2: 워커가 작업을 인지하면 nmae 가
    "어느 Discord 쓰레드 작업인지" 알고 그 쓰레드로 결과를 회신해야 한다. 큐에
    payload.text 만 넘기면 nmae 가 thread context 를 잃으므로, task_id 와
    discord_thread_id 를 prefix 로 동봉한다. (실제 쓰레드 회신 = Feedback Bridge,
    Phase 3.)
    """
    task_id = task["task_id"]
    thread_id = task["thread_id"]
    text = task.get("payload", {}).get("text", "")
    return (
        f"[task #{task_id} | discord-thread {thread_id}] {text}\n"
        f"(완료 시 위 thread 로 결과 요약 회신)"
    )


def process_task(ledger: OpLedger, task: dict):
    task_id = task["task_id"]
    thread_id = task["thread_id"]

    logger.info("작업 시작: task_id=%s thread_id=%s", task_id, thread_id)

    # 1. tmux 세션 보장
    ensure_tmux_session(TMUX_SESSION_NAME, CLAUDE_BIN)

    # 2. 작업 수행 (현재는 tmux 로 nmae 에 전달). thread context 동봉.
    # TODO: 향후 작업별 격리 worktree 에서 claude 실행으로 확장 가능.
    success = tmux_send_payload(TMUX_TARGET_PANE, format_worker_inject(task))

    if success:
        logger.info("작업 수행 완료 (전달 성공): task_id=%s", task_id)
        # 작업이 '완료'된 것은 에이전트의 응답까지 확인해야 하므로,
        # 여기서는 '진행중(WORKING)' 상태를 유지.
        # 실제 완료 처리는 Feedback Bridge 가 수행하도록 설계 (Phase 3).
        ledger.update_task_status(task_id, "WORKING", result="Dispatched to tmux")
    else:
        logger.error("작업 수행 실패: task_id=%s", task_id)
        ledger.update_task_status(task_id, "FAILED", result="Tmux send-keys failed")

def main():
    logger.info("Mobruji Worker 시작 (polling=%ds)", POLL_INTERVAL)
    ledger = OpLedger(DEDUP_LEDGER_PATH)
    
    while True:
        try:
            task = ledger.get_next_task()
            if task:
                process_task(ledger, task)
            else:
                # 작업 없음
                pass
        except Exception:
            logger.exception("Worker 루프 에러:")
        
        time.sleep(POLL_INTERVAL)

if __name__ == "__main__":
    main()
