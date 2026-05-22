"""Mobruji Discord daemon (Phase 3 tmux bridge 포함).

기능:
- Discord Gateway WebSocket 으로 24/7 연결을 유지한다.
- 지정된 채널(MOBRUJI_CHANNEL_ID)에서 화이트리스트(ALLOWED_USER_IDS)
  사용자가 보낸 메시지만 처리한다.
- `TMUX_BRIDGE_ENABLED=1` 이면 메시지를 maestro tmux 세션 stdin 에 주입한다.
  세션이 없으면 `tmux new-session -d -s <name> '<CLAUDE_BIN>'` 으로 생성한다.
- `TMUX_BRIDGE_ENABLED=0` (또는 미설정) 이면 종전대로 GitHub
  `repository_dispatch` 호출만 수행한다.
- dedup ledger 는 SQLite (`DEDUP_LEDGER_PATH`, 기본 `~/.mobruji/discord-bridge.sqlite`).
  `message_id` PK + `processed_at` TIMESTAMP, 24h TTL 후 GC.
- 옵션으로 inbox 파일에도 메시지를 append 한다 (디버깅/백업용).
- `TMUX_PIPE_PANE_ENABLED=1` 이면 부팅 시 `tmux pipe-pane` 으로 pane stdout 을
  파일로 캡처하고 size 기반 자체 rotation 을 수행한다.

운영 가이드와 셋업 절차는 같은 디렉토리의 README.md 참고.
spec: docs/features/discord-driven-mobruji.md
"""

from __future__ import annotations

import json
import logging
import os
import shlex
import sqlite3
import subprocess
import sys
import threading
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Final

import discord
import requests
from dotenv import load_dotenv

LOG_FORMAT: Final[str] = (
    "%(asctime)s %(levelname)s %(name)s :: %(message)s"
)
INBOX_PATH: Final[Path] = Path(__file__).resolve().parent / "inbox.jsonl"
REQUEST_TIMEOUT_SECONDS: Final[int] = 10
MAX_TEXT_PREVIEW_LEN: Final[int] = 80
DEDUP_TTL_SECONDS: Final[int] = 24 * 60 * 60  # 24h
DEDUP_GC_INTERVAL_SECONDS: Final[int] = 60 * 60  # 1h
PIPE_PANE_ROTATE_INTERVAL_SECONDS: Final[int] = 5 * 60  # 5min
DEFAULT_PIPE_PANE_MAX_BYTES: Final[int] = 100 * 1024 * 1024  # 100MB
SENTINEL_PREFIX: Final[str] = "/system:"

logging.basicConfig(level=logging.INFO, format=LOG_FORMAT, stream=sys.stdout)
logger = logging.getLogger("mobruji-discord-daemon")


def load_env() -> dict[str, str]:
    """필수 환경변수를 로드한다. 누락 시 즉시 종료한다."""
    load_dotenv(Path(__file__).resolve().parent / ".env")

    required = (
        "DISCORD_BOT_TOKEN",
        "ALLOWED_USER_IDS",
        "MOBRUJI_CHANNEL_ID",
        "GITHUB_PAT",
        "GITHUB_REPO",
    )
    missing = [key for key in required if not os.environ.get(key)]
    if missing:
        logger.error("필수 환경변수 누락: %s", ", ".join(missing))
        sys.exit(1)

    env: dict[str, str] = {key: os.environ[key] for key in required}
    env["TMUX_BRIDGE_ENABLED"] = os.environ.get("TMUX_BRIDGE_ENABLED", "0")
    env["TMUX_SESSION_NAME"] = os.environ.get("TMUX_SESSION_NAME", "mobruji")
    env["TMUX_TARGET_PANE"] = os.environ.get("TMUX_TARGET_PANE", "mobruji:0.0")
    env["CLAUDE_BIN"] = os.environ.get("CLAUDE_BIN", "claude")
    env["DEDUP_LEDGER_PATH"] = os.path.expanduser(
        os.environ.get("DEDUP_LEDGER_PATH", "~/.mobruji/discord-bridge.sqlite")
    )
    env["TMUX_PIPE_PANE_ENABLED"] = os.environ.get("TMUX_PIPE_PANE_ENABLED", "0")
    env["TMUX_PIPE_PANE_PATH"] = os.path.expanduser(
        os.environ.get("TMUX_PIPE_PANE_PATH", "~/.mobruji/tmux-pane.log")
    )
    env["TMUX_PIPE_PANE_MAX_BYTES"] = os.environ.get(
        "TMUX_PIPE_PANE_MAX_BYTES", str(DEFAULT_PIPE_PANE_MAX_BYTES)
    )
    return env


def parse_allowed_user_ids(raw: str) -> set[int]:
    """CSV 형태 user id 목록을 정수 집합으로 변환한다."""
    ids: set[int] = set()
    for token in raw.split(","):
        token = token.strip()
        if not token:
            continue
        try:
            ids.add(int(token))
        except ValueError:
            logger.warning("ALLOWED_USER_IDS 토큰 무시 (정수 아님): %r", token)
    return ids


def truncate_for_log(text: str) -> str:
    """로그에 메시지를 남길 때 너무 길지 않도록 자른다."""
    if len(text) <= MAX_TEXT_PREVIEW_LEN:
        return text
    return text[:MAX_TEXT_PREVIEW_LEN] + "…"


def append_inbox(payload: dict[str, str]) -> None:
    """inbox.jsonl 에 한 줄 JSON 으로 append (백업/디버깅용)."""
    try:
        with INBOX_PATH.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(payload, ensure_ascii=False) + "\n")
    except OSError as exc:  # 디스크 문제는 데몬을 죽이지 않는다
        logger.warning("inbox.jsonl write 실패: %s", exc)


def dispatch_to_github(
    github_pat: str,
    github_repo: str,
    payload: dict[str, str],
) -> None:
    """GitHub repository_dispatch 호출 (tmux bridge off 시 fallback 경로)."""
    url = f"https://api.github.com/repos/{github_repo}/dispatches"
    headers = {
        "Accept": "application/vnd.github+json",
        "Authorization": f"Bearer {github_pat}",
        "X-GitHub-Api-Version": "2022-11-28",
    }
    body = {
        "event_type": "discord_message",
        "client_payload": payload,
    }
    try:
        response = requests.post(
            url,
            headers=headers,
            json=body,
            timeout=REQUEST_TIMEOUT_SECONDS,
        )
    except requests.RequestException as exc:
        logger.error("repository_dispatch 요청 실패: %s", exc)
        return

    if response.status_code >= 300:
        logger.error(
            "repository_dispatch 비정상 응답: %s %s",
            response.status_code,
            truncate_for_log(response.text),
        )
        return

    logger.info(
        "repository_dispatch 성공: repo=%s event=discord_message",
        github_repo,
    )


class DedupLedger:
    """SQLite 기반 dedup ledger. message_id PK + processed_at TIMESTAMP.

    spec Q6 답: SQLite (JSONL 대비 TTL GC / 동시성 안전).
    """

    SCHEMA = (
        "CREATE TABLE IF NOT EXISTS processed_messages ("
        "  message_id TEXT PRIMARY KEY,"
        "  processed_at INTEGER NOT NULL"
        ")"
    )

    def __init__(self, db_path: str) -> None:
        self.db_path = db_path
        Path(db_path).parent.mkdir(parents=True, exist_ok=True)
        self._conn = sqlite3.connect(db_path, check_same_thread=False, timeout=5.0)
        self._lock = threading.Lock()
        with self._lock:
            self._conn.execute(self.SCHEMA)
            self._conn.commit()
        try:
            os.chmod(db_path, 0o600)
        except OSError:
            pass

    def is_processed(self, message_id: str) -> bool:
        with self._lock:
            row = self._conn.execute(
                "SELECT 1 FROM processed_messages WHERE message_id = ?",
                (message_id,),
            ).fetchone()
            return row is not None

    def mark_processed(self, message_id: str, now_epoch: int | None = None) -> None:
        ts = int(time.time()) if now_epoch is None else now_epoch
        with self._lock:
            self._conn.execute(
                "INSERT OR IGNORE INTO processed_messages (message_id, processed_at) VALUES (?, ?)",
                (message_id, ts),
            )
            self._conn.commit()

    def gc(self, ttl_seconds: int = DEDUP_TTL_SECONDS) -> int:
        cutoff = int(time.time()) - ttl_seconds
        with self._lock:
            cursor = self._conn.execute(
                "DELETE FROM processed_messages WHERE processed_at < ?",
                (cutoff,),
            )
            self._conn.commit()
            return cursor.rowcount


def start_dedup_gc_thread(ledger: DedupLedger) -> None:
    """백그라운드에서 주기적으로 dedup ledger GC 를 수행한다."""

    def loop() -> None:
        while True:
            time.sleep(DEDUP_GC_INTERVAL_SECONDS)
            try:
                deleted = ledger.gc()
                if deleted:
                    logger.info("dedup ledger GC: deleted=%d", deleted)
            except sqlite3.Error as exc:
                logger.warning("dedup ledger GC 실패: %s", exc)

    threading.Thread(target=loop, name="dedup-gc", daemon=True).start()


def tmux_has_session(session_name: str) -> bool:
    result = subprocess.run(
        ["tmux", "has-session", "-t", session_name],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        check=False,
    )
    return result.returncode == 0


def tmux_create_session(session_name: str, claude_bin: str) -> bool:
    """tmux new-session -d 로 maestro 세션을 만든다."""
    cmd = ["tmux", "new-session", "-d", "-s", session_name, claude_bin]
    result = subprocess.run(cmd, check=False, capture_output=True, text=True)
    if result.returncode != 0:
        logger.error(
            "tmux new-session 실패: rc=%d stderr=%s",
            result.returncode,
            truncate_for_log(result.stderr or ""),
        )
        return False
    logger.info("tmux 세션 생성: session=%s claude_bin=%s", session_name, claude_bin)
    return True


def ensure_tmux_session(session_name: str, claude_bin: str) -> bool:
    if tmux_has_session(session_name):
        return True
    return tmux_create_session(session_name, claude_bin)


SENTINEL_KEYS: Final[dict[str, list[str]]] = {
    "ctrl-c": ["C-c"],
    "ctrl-d": ["C-d"],
    "enter": ["Enter"],
    "esc": ["Escape"],
}


def resolve_sentinel(text: str) -> list[str] | None:
    """`/system:<key>` 형태 sentinel 을 tmux send-keys 인자 리스트로 변환.

    Q5 답(c): 명시 sentinel 만 종료/제어 입력으로 받는다. `/exit` 같은
    일반 슬래시 명령은 maestro Claude TUI 에 그대로 전달한다.
    """
    if not text.startswith(SENTINEL_PREFIX):
        return None
    key = text[len(SENTINEL_PREFIX):].strip().lower()
    return SENTINEL_KEYS.get(key)


def tmux_send_payload(target_pane: str, text: str) -> bool:
    """일반 텍스트는 `-l` (literal) 로 보낸 뒤 Enter 키를 누른다.

    sentinel(/system:ctrl-c 등) 은 컨트롤 키 인자로 직접 전송한다.
    multiline 은 줄마다 `-l` + Enter.
    """
    sentinel = resolve_sentinel(text)
    if sentinel is not None:
        cmd = ["tmux", "send-keys", "-t", target_pane, *sentinel]
        result = subprocess.run(cmd, check=False, capture_output=True, text=True)
        if result.returncode != 0:
            logger.error(
                "tmux send-keys (sentinel) 실패: rc=%d stderr=%s",
                result.returncode,
                truncate_for_log(result.stderr or ""),
            )
            return False
        logger.info("tmux send-keys sentinel: %s", sentinel)
        return True

    lines = text.split("\n")
    for line in lines:
        if line:
            literal_cmd = ["tmux", "send-keys", "-t", target_pane, "-l", line]
            result = subprocess.run(literal_cmd, check=False, capture_output=True, text=True)
            if result.returncode != 0:
                logger.error(
                    "tmux send-keys (-l) 실패: rc=%d stderr=%s",
                    result.returncode,
                    truncate_for_log(result.stderr or ""),
                )
                return False
        enter_cmd = ["tmux", "send-keys", "-t", target_pane, "Enter"]
        result = subprocess.run(enter_cmd, check=False, capture_output=True, text=True)
        if result.returncode != 0:
            logger.error(
                "tmux send-keys (Enter) 실패: rc=%d stderr=%s",
                result.returncode,
                truncate_for_log(result.stderr or ""),
            )
            return False
    return True


def start_tmux_pipe_pane(
    target_pane: str,
    log_path: str,
    max_bytes: int,
) -> None:
    """pane stdout 을 파일로 캡처한다. 동시에 size 기반 self-rotation 스레드 가동.

    spec §5-2 컴포넌트 3: Phase 1 디버깅 용도. 메시지 원문이 포함될 수 있어
    chmod 600. 로그 파일이 max_bytes 초과 시 `.1` 로 회전(단일 백업).
    """
    Path(log_path).parent.mkdir(parents=True, exist_ok=True)
    Path(log_path).touch(exist_ok=True)
    try:
        os.chmod(log_path, 0o600)
    except OSError:
        pass

    pipe_shell = f"cat >> {shlex.quote(log_path)}"
    cmd = ["tmux", "pipe-pane", "-t", target_pane, "-o", pipe_shell]
    result = subprocess.run(cmd, check=False, capture_output=True, text=True)
    if result.returncode != 0:
        logger.warning(
            "tmux pipe-pane 시작 실패: rc=%d stderr=%s",
            result.returncode,
            truncate_for_log(result.stderr or ""),
        )
        return
    logger.info("tmux pipe-pane 시작: target=%s log=%s", target_pane, log_path)

    def rotate_loop() -> None:
        while True:
            time.sleep(PIPE_PANE_ROTATE_INTERVAL_SECONDS)
            try:
                size = Path(log_path).stat().st_size
            except OSError:
                continue
            if size < max_bytes:
                continue
            rotated = log_path + ".1"
            try:
                if Path(rotated).exists():
                    Path(rotated).unlink()
                Path(log_path).rename(rotated)
                Path(log_path).touch(exist_ok=True)
                os.chmod(log_path, 0o600)
                logger.info(
                    "tmux pipe-pane 로그 회전: %s → %s (size=%d)",
                    log_path,
                    rotated,
                    size,
                )
                subprocess.run(
                    ["tmux", "pipe-pane", "-t", target_pane],
                    check=False,
                    capture_output=True,
                )
                subprocess.run(
                    ["tmux", "pipe-pane", "-t", target_pane, "-o", pipe_shell],
                    check=False,
                    capture_output=True,
                )
            except OSError as exc:
                logger.warning("pipe-pane 로그 회전 실패: %s", exc)

    threading.Thread(target=rotate_loop, name="pipe-pane-rotate", daemon=True).start()


def build_client(env: dict[str, str], ledger: DedupLedger | None) -> discord.Client:
    """discord.py Client 를 셋업하고 핸들러를 바인딩한다."""
    intents = discord.Intents.default()
    intents.message_content = True
    client = discord.Client(intents=intents)

    allowed_user_ids = parse_allowed_user_ids(env["ALLOWED_USER_IDS"])
    try:
        target_channel_id = int(env["MOBRUJI_CHANNEL_ID"])
    except ValueError:
        logger.error(
            "MOBRUJI_CHANNEL_ID 가 정수 아님: %r", env["MOBRUJI_CHANNEL_ID"]
        )
        sys.exit(1)

    tmux_enabled = env["TMUX_BRIDGE_ENABLED"] == "1"
    session_name = env["TMUX_SESSION_NAME"]
    target_pane = env["TMUX_TARGET_PANE"]
    claude_bin = env["CLAUDE_BIN"]

    @client.event
    async def on_ready() -> None:  # noqa: D401
        logger.info(
            "Discord Gateway 연결 OK: user=%s channel=%s allowed=%d tmux_bridge=%s",
            client.user,
            target_channel_id,
            len(allowed_user_ids),
            tmux_enabled,
        )

    @client.event
    async def on_message(message: discord.Message) -> None:
        if message.author.bot:
            return
        if message.channel.id != target_channel_id:
            return
        if message.author.id not in allowed_user_ids:
            logger.info(
                "허용되지 않은 사용자 무시: user_id=%s", message.author.id
            )
            return

        message_id = str(message.id)
        if ledger is not None and ledger.is_processed(message_id):
            logger.info("dedup hit: message_id=%s", message_id)
            return

        ts_iso = message.created_at.astimezone(timezone.utc).isoformat()
        payload = {
            "text": message.content or "",
            "author": str(message.author.id),
            "author_name": message.author.name,
            "ts": ts_iso,
            "message_id": message_id,
            "channel_id": str(message.channel.id),
        }

        logger.info(
            "메시지 수신: author=%s ts=%s preview=%r",
            payload["author"],
            payload["ts"],
            truncate_for_log(payload["text"]),
        )
        append_inbox(payload)

        if tmux_enabled:
            if not ensure_tmux_session(session_name, claude_bin):
                logger.error("tmux 세션 확보 실패 — 메시지 dropped: id=%s", message_id)
                return
            if not tmux_send_payload(target_pane, payload["text"]):
                logger.error("tmux send-keys 실패 — 메시지 dropped: id=%s", message_id)
                return
        else:
            dispatch_to_github(env["GITHUB_PAT"], env["GITHUB_REPO"], payload)

        if ledger is not None:
            ledger.mark_processed(message_id)

    return client


def main() -> None:
    env = load_env()
    ledger: DedupLedger | None = None
    if env["TMUX_BRIDGE_ENABLED"] == "1":
        ledger = DedupLedger(env["DEDUP_LEDGER_PATH"])
        start_dedup_gc_thread(ledger)
        ensure_tmux_session(env["TMUX_SESSION_NAME"], env["CLAUDE_BIN"])
        if env["TMUX_PIPE_PANE_ENABLED"] == "1":
            try:
                max_bytes = int(env["TMUX_PIPE_PANE_MAX_BYTES"])
            except ValueError:
                max_bytes = DEFAULT_PIPE_PANE_MAX_BYTES
                logger.warning(
                    "TMUX_PIPE_PANE_MAX_BYTES 가 정수 아님 — 기본값 사용: %d",
                    max_bytes,
                )
            start_tmux_pipe_pane(env["TMUX_TARGET_PANE"], env["TMUX_PIPE_PANE_PATH"], max_bytes)

    client = build_client(env, ledger)
    logger.info(
        "Discord daemon 시작 (received_at=%s tmux_bridge=%s)",
        datetime.now(timezone.utc).isoformat(),
        env["TMUX_BRIDGE_ENABLED"],
    )
    client.run(env["DISCORD_BOT_TOKEN"], log_handler=None)


if __name__ == "__main__":
    main()
