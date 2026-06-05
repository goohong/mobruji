"""SQLite event log + state table — IPC 옵션 2 (event-sourcing 단순화).

events  : append-only audit log (모든 변경의 시계열)
state   : current snapshot (read O(1), 변경 시 즉시 update)

bot.py 가 사용자 메시지 받으면 events INSERT (kind='user_message'). agent 가
polling 으로 SELECT + 처리 + 답 event INSERT (kind='agent_reply'). bot.py 가
agent_reply 를 polling 으로 SELECT + Discord push.

DB 위치: ~/.mobruji/agent.sqlite (별 file, legacy discord-bridge.sqlite 와 분리).
"""

from __future__ import annotations

import json
import sqlite3
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterator, Literal


EventKind = Literal[
    # user → agent
    "user_message",       # bot.py: 사용자 Discord 메시지
    "user_reaction",      # bot.py: ⏹/❓/📌 reaction
    "user_pause",         # bot.py: 사용자 사이클 정지 명령
    "user_resume",        # bot.py: 사이클 재개
    # agent → bot.py
    "agent_reply",        # agent: Discord push 요청 (channel + body + reply_to + thread)
    "agent_forum_action", # agent: forum thread 생성 / 댓글 / retag / starter edit
    # agent ↔ subagent
    "subagent_launched",   # agent: be/fe/rev/plan 사이클 시작
    "subagent_completed",  # agent: sub-agent 종료
    # work-queue (#1388) — directive 즉시 launch 폐지 → 사이클별 큐 적재 + dispatch
    "work_enqueued",       # agent: directive 를 cycle 큐에 적재
    "work_dispatched",     # agent: dispatcher 가 큐 항목 launch
    # cycle / directive transitions (state 변경 audit)
    "cycle_state_changed",
    "directive_registered",
    "directive_polished",
    "directive_status_changed",
    # Phase F (2026-05-29) — 사용자 O click → directive 적재 완료 + agent launch trigger.
    # 사용자가 PinDialogueView 의 ⭕ button click → bot.py 가 events INSERT.
    # agent.py 의 handle_directive_approved 가 consume → cycle 위임 결정 → launch_subagent.
    "directive_approved",
    # webhook (PR 머지)
    "pr_merged",
]


DEFAULT_DB_PATH = Path("~/.mobruji/agent.sqlite").expanduser()


def db_path() -> Path:
    """env override 가능 — 테스트 시 별 path."""
    import os
    override = os.environ.get("MOBRUJI_AGENT_DB")
    return Path(override).expanduser() if override else DEFAULT_DB_PATH


SCHEMA = """
CREATE TABLE IF NOT EXISTS events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    kind TEXT NOT NULL,
    payload TEXT NOT NULL,            -- JSON
    ts_iso TEXT NOT NULL,
    consumed_by TEXT,                  -- 'bot' / 'agent' / NULL (미처리)
    consumed_at TEXT
);

CREATE INDEX IF NOT EXISTS idx_events_unconsumed
    ON events (consumed_by, id)
    WHERE consumed_by IS NULL;

CREATE TABLE IF NOT EXISTS agent_state (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL,               -- JSON
    updated_at TEXT NOT NULL
);
"""


@contextmanager
def connect(path: Path | None = None) -> Iterator[sqlite3.Connection]:
    """SQLite 연결 — WAL mode (동시 read 안전) + foreign_keys ON."""
    target = path or db_path()
    target.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(str(target), isolation_level=None, timeout=10.0)
    try:
        conn.execute("PRAGMA journal_mode=WAL")
        conn.execute("PRAGMA foreign_keys=ON")
        conn.row_factory = sqlite3.Row
        yield conn
    finally:
        conn.close()


def init_schema(path: Path | None = None) -> None:
    """첫 실행 시 schema 생성. 이미 있으면 no-op."""
    with connect(path) as conn:
        conn.executescript(SCHEMA)


# ─── events table ────────────────────────────────────────────────────────────


def append_event(
    kind: EventKind,
    payload: dict[str, Any],
    *,
    path: Path | None = None,
) -> int:
    """append-only — 새 event row. 모든 변경은 본 함수 통해서."""
    ts_iso = datetime.now(timezone.utc).isoformat()
    with connect(path) as conn:
        cursor = conn.execute(
            "INSERT INTO events (kind, payload, ts_iso) VALUES (?, ?, ?)",
            (kind, json.dumps(payload, ensure_ascii=False), ts_iso),
        )
        return cursor.lastrowid or 0


def read_unconsumed_events(
    consumer: Literal["bot", "agent"],
    *,
    limit: int = 100,
    path: Path | None = None,
    kinds: tuple[str, ...] | None = None,
) -> list[dict[str, Any]]:
    """소비자별 미처리 events 읽기 (FIFO 순서).

    소비자 의도:
    - bot.py polling: kind='agent_reply' / 'agent_forum_action' 처리 → Discord push.
    - agent polling: kind='user_message' / 'user_reaction' / 'user_pause' / 'user_resume'
      / 'pr_merged' 처리.

    `kinds` 인자 (2026-05-29 fix): consumer 가 자기 책임 kind 만 SELECT.
    인자 없으면 모든 kind. 호출자가 'directive_registered' 같이 자기 책임 아닌
    kind 를 skip 하면 mark_consumed 안 되어 batch limit 안에 영구 누적 → 자기
    책임 events 도달 못 함. kinds 명시로 cursor 가 무관 kind 건너뜀.
    """
    with connect(path) as conn:
        if kinds:
            placeholders = ",".join(["?"] * len(kinds))
            rows = conn.execute(
                "SELECT id, kind, payload, ts_iso FROM events "
                "WHERE consumed_by IS NULL "
                f"AND kind IN ({placeholders}) "
                "ORDER BY id ASC LIMIT ?",
                (*kinds, limit),
            ).fetchall()
        else:
            rows = conn.execute(
                "SELECT id, kind, payload, ts_iso FROM events "
                "WHERE consumed_by IS NULL "
                "ORDER BY id ASC LIMIT ?",
                (limit,),
            ).fetchall()
    return [
        {"id": r["id"], "kind": r["kind"],
         "payload": json.loads(r["payload"]), "ts_iso": r["ts_iso"]}
        for r in rows
    ]


def mark_consumed(
    event_id: int,
    consumer: Literal["bot", "agent"],
    *,
    path: Path | None = None,
) -> None:
    """consumer 가 처리 완료 시 호출 — 다음 polling 에서 skip."""
    ts_iso = datetime.now(timezone.utc).isoformat()
    with connect(path) as conn:
        conn.execute(
            "UPDATE events SET consumed_by = ?, consumed_at = ? "
            "WHERE id = ? AND consumed_by IS NULL",
            (consumer, ts_iso, event_id),
        )


# ─── state table ─────────────────────────────────────────────────────────────


def get_state(key: str, *, path: Path | None = None) -> Any | None:
    """current state value 조회. 미존재 시 None."""
    with connect(path) as conn:
        row = conn.execute(
            "SELECT value FROM agent_state WHERE key = ?", (key,)
        ).fetchone()
        return json.loads(row["value"]) if row else None


def set_state(key: str, value: Any, *, path: Path | None = None) -> None:
    """current state upsert. 동시 audit event 자동 emit (state_changed)."""
    ts_iso = datetime.now(timezone.utc).isoformat()
    with connect(path) as conn:
        conn.execute(
            "INSERT INTO agent_state (key, value, updated_at) VALUES (?, ?, ?) "
            "ON CONFLICT(key) DO UPDATE SET value = excluded.value, "
            "updated_at = excluded.updated_at",
            (key, json.dumps(value, ensure_ascii=False), ts_iso),
        )


def delete_state(key: str, *, path: Path | None = None) -> None:
    """state key 제거 (cycle 완료 시 in_flight 해제 등)."""
    with connect(path) as conn:
        conn.execute("DELETE FROM agent_state WHERE key = ?", (key,))
