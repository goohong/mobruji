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

import asyncio
import hashlib
import json
import logging
import os
import re
import shlex
import sqlite3
import subprocess
import sys
import threading
import time
from datetime import datetime, timedelta, timezone
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
# maestro_response_watcher_loop 튜닝값. tmux pane 캡처 파일을 tail 하여 idle 임계 도달 시
# 누적 buffer 를 한 응답 chunk 로 push. 노이즈 줄이려고 짧은 chunk skip + sha256 dedup.
MAESTRO_WATCHER_POLL_INTERVAL_SECONDS: Final[float] = 1.0
MAESTRO_WATCHER_IDLE_SECONDS: Final[float] = 30.0
MAESTRO_WATCHER_MIN_CHUNK_LEN: Final[int] = 100  # 이보다 짧은 chunk 는 노이즈로 skip
MAESTRO_WATCHER_MAX_CHUNK_LEN: Final[int] = 1800  # Discord 한도 2000 여유 200
MAESTRO_WATCHER_DEDUP_PREFIX: Final[str] = "maestro:"
MAESTRO_WATCHER_SEND_MAX_RETRIES: Final[int] = 3  # transient send 실패 시 최대 재시도 횟수
# context_auto_clear_loop 튜닝값. maestro 가 매 turn 끝에 emit 하는 `===CTX:NN%===` 마커를
# pipe-pane capture 파일에서 tail 하여 95% 도달 시 정리 prompt inject, ===CLEAR_READY===
# 마커 감지 시 /clear 전송. spec: docs/features/context-auto-clear.md §5-6 옵션 A.
CONTEXT_AUTO_CLEAR_POLL_INTERVAL_SECONDS: Final[float] = 5.0
CONTEXT_DEFAULT_TRIGGER_PCT: Final[int] = 95
CONTEXT_DEFAULT_HYSTERESIS_PCT: Final[int] = 80
CONTEXT_MARKER_RE: Final[re.Pattern[str]] = re.compile(r"===CTX:(\d{1,3})%===")
CONTEXT_CLEAR_READY_MARKER: Final[str] = "===CLEAR_READY==="
CONTEXT_CLEANUP_PROMPT: Final[str] = (
    ":memory: 95% 도달. 진행 중 sub-agent 완료 대기 + 핸드오프 메모리 갱신 + "
    "===CLEAR_READY=== 출력 후 정지"
)
CONTEXT_AUTO_CLEAR_TAIL_BYTES: Final[int] = 64 * 1024  # 최근 64KB 만 스캔 (큰 로그 회피)
# ANSI escape sequence: CSI (`ESC [ ... letter`) + OSC (`ESC ] ... BEL/ST`) + 단일 ESC.
# claude TUI 가 컬러/커서/타이틀 코드 다수 출력 — Discord 에 raw 노출 방지.
ANSI_ESCAPE_RE: Final[re.Pattern[str]] = re.compile(
    r"\x1B(?:\[[0-?]*[ -/]*[@-~]|\][^\x07\x1B]*(?:\x07|\x1B\\)|[@-_])"
)
# Secret masking — maestro tmux pane 에 PAT/토큰/패스워드가 echo 될 수 있으므로
# Discord push 직전에 패턴 매칭으로 마스킹 (#742). 새 시크릿 형태 발견 시 패턴 추가.
# 순서 중요: 더 구체적인 패턴(github_pat, discord token)이 일반 env-style 보다 먼저 매칭되도록 배치.
SECRET_MASK_PATTERNS: Final[tuple[tuple[re.Pattern[str], str], ...]] = (
    # GitHub fine-grained PAT — `github_pat_` + 82자 (실측 prefix 11 + body)
    (re.compile(r"github_pat_[A-Za-z0-9_]{50,}"), "github_pat_***"),
    # GitHub classic PAT — `ghp_` + 36자 영숫자
    (re.compile(r"ghp_[A-Za-z0-9]{36,}"), "ghp_***"),
    # Discord bot token — `[MN][A-Za-z0-9-_]{23}.[A-Za-z0-9-_]{6}.[A-Za-z0-9-_]{27+}`
    (
        re.compile(
            r"[MN][A-Za-z0-9_-]{23}\.[A-Za-z0-9_-]{6}\.[A-Za-z0-9_-]{27,}"
        ),
        "discord_token_***",
    ),
    # MySQL/DB password 패턴 — `password=value` (case-insensitive).
    # 일반 env-style (KEY/TOKEN/SECRET/PASSWORD) 은 _ENV_STYLE_SECRET_RE 로 별도 처리.
    (re.compile(r"(?i)\bpassword=\S+"), "password=***"),
)

# 일반 env-style `*_TOKEN=` / `*_SECRET=` / `*_KEY=` / `*_PASSWORD=` 매칭 (#759 narrow).
# 단순 광역 패턴은 `NEXT_PUBLIC_API_KEY=`, `PUBLIC_KEY=` (RSA pub), `API_VERSION_KEY=` 등
# 공개 의도 변수까지 마스킹해 가독성 회귀를 일으켰다. 콜백에서 화이트리스트/블랙리스트 + 길이
# 임계로 false positive 를 줄인다.
_ENV_STYLE_SECRET_RE: Final[re.Pattern[str]] = re.compile(
    r"([A-Z][A-Z0-9_]*(?:TOKEN|SECRET|KEY|PASSWORD))=(\S+)"
)
# 키 이름에 이 단어들이 포함되어 있으면 secret 이 아니므로 마스킹 제외.
# - PUBLIC: `NEXT_PUBLIC_*` (Next.js 클라이언트 노출), `PUBLIC_KEY` (RSA pub) 등 공개 의도.
# - VERSION/COUNT/LIMIT/INDEX/SIZE/LENGTH: 비밀이 아닌 메타 식별자.
_ENV_STYLE_SECRET_KEYNAME_BLOCKLIST: Final[tuple[str, ...]] = (
    "PUBLIC",
    "VERSION",
    "COUNT",
    "LIMIT",
    "INDEX",
    "SIZE",
    "LENGTH",
)
# 값 최소 길이 임계. 너무 짧은 값은 secret 일 가능성이 낮고 가독성 손해가 더 큼.
# 일반 PAT/토큰/api key 는 32+ 가 일반적이지만, 짧은 dev secret 도 보호 가능하도록 12 로 설정.
_ENV_STYLE_SECRET_MIN_VALUE_LEN: Final[int] = 12


def _mask_env_style_secret(match: re.Match[str]) -> str:
    """env-style `KEY=value` 매칭 1건에 대해 화이트리스트/블랙리스트/길이 임계 적용."""
    key_name = match.group(1)
    value = match.group(2)
    if any(blocked in key_name for blocked in _ENV_STYLE_SECRET_KEYNAME_BLOCKLIST):
        return match.group(0)  # 공개 의도 / 비-secret 메타 — 원문 유지
    if len(value) < _ENV_STYLE_SECRET_MIN_VALUE_LEN:
        return match.group(0)  # 너무 짧음 — secret 아닐 가능성 높음, 원문 유지
    return f"{key_name}=***"
SENTINEL_PREFIX: Final[str] = "/system:"
STATUS_COMMAND_PREFIX: Final[str] = "/status"
STATUS_GH_TIMEOUT_SECONDS: Final[int] = 8
STATUS_OPEN_PR_LIMIT: Final[int] = 8
STATUS_MERGED_PR_LIMIT: Final[int] = 5
STATUS_BUG_ISSUE_LIMIT: Final[int] = 5
STATUS_DISCORD_MAX_LEN: Final[int] = 1900  # Discord 메시지 한도 2000, 여유 100
AUTO_ACK_QUEUE_WINDOW_SECONDS: Final[int] = 300  # 5분 안 dedup mark 수 = queue 표시
# `/system:status <msg>` — 사용자가 maestro 진척을 강제로 채널에 push 하고 싶을 때.
# 일반 메시지 dispatch (tmux/repository_dispatch) 는 건너뛰고 reply 카테고리로만 발화.
# maestro 자신이 자기 진척을 알릴 때도 (tmux pane 에 이 prefix 로 입력) 같은 경로로 push 됨.
STATUS_PROGRESS_PREFIX: Final[str] = "/system:status"

# 7 카테고리 emoji prefix — spec: docs/features/discord-message-style.md §3.
# 한 메시지 = 한 카테고리. 첫 줄 emoji 만 보고 사용자가 종류 즉시 식별 (시나리오 S1).
MESSAGE_PREFIX: Final[dict[str, str]] = {
    "reply": "💬",        # 사용자 메시지에 maestro 답 (메인 채널)
    "cycle-start": "🚀",  # 사이클 시작 (알림 채널)
    "cycle-end": "✅",    # 사이클 정상 종료 (알림 채널)
    "digest": "📊",       # cron 상태 1줄 (알림 채널)
    "alert": "🚨",        # 위험 발생 (P1+ 메인 동시)
    "recovery": "🟢",     # 위험 회복 1회 (알림 채널)
    "decision": "📌",     # 사용자 결정 묶음 질문 (메인 채널)
}

# auto-ack 은 reply 카테고리 — 사용자 메시지에 즉시 응답하는 maestro 답이므로 spec §3 reply 분류.
# template §4-1 에 따라 첫 줄 `💬 reply: {요약}` 형식.
# ETA 표기: 사용자가 "처리 중" 만 보고 무한정 기다리는 무의미한 ack 가 안 되도록
# 일반 응답(1-5분) / sub-agent 가동 시(5-15분) 범위를 같이 노출.
# 추가 진척이 필요하면 `/system:status <msg>` 로 ad-hoc push 가능 (STATUS_PROGRESS_PREFIX).
AUTO_ACK_TEMPLATE: Final[str] = (
    f"{MESSAGE_PREFIX['reply']} reply: 받음, maestro 처리 중 "
    f"(queue: {{queue}}, ETA 1-5분 · sub-agent 가동 시 5-15분)"
)
DEFAULT_DIGEST_INTERVAL_SECONDS: Final[int] = 900  # 15분 cron digest 주기 (env override 가능)
DIGEST_INITIAL_DELAY_SECONDS: Final[int] = 60  # boot 1분 warmup 후 첫 발화
DIGEST_MERGED_WINDOW_HOURS: Final[int] = 24  # "최근 머지" 24h 윈도우
DIGEST_HEARTBEAT_SECONDS: Final[int] = 60 * 60  # delta 없어도 1h 1회는 push (생존 신호)

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
    env["DIGEST_ENABLED"] = os.environ.get("DIGEST_ENABLED", "0")
    # maestro 응답 자동 캡처 + 메인 채널 push 워처. opt-in (긴급 위임).
    # TMUX_PIPE_PANE_ENABLED=1 + 동일 capture 파일 사용 전제.
    env["MAESTRO_RESPONSE_WATCHER_ENABLED"] = os.environ.get(
        "MAESTRO_RESPONSE_WATCHER_ENABLED", "0"
    )
    # context auto-clear loop (spec PR C). opt-in — default off.
    # `===CTX:NN%===` 마커 polling → 95% 도달 시 inject + /clear.
    # TMUX_BRIDGE_ENABLED=1 + TMUX_PIPE_PANE_ENABLED=1 전제.
    env["CONTEXT_AUTO_CLEAR_ENABLED"] = os.environ.get(
        "CONTEXT_AUTO_CLEAR_ENABLED", "0"
    )
    env["CONTEXT_CLEAR_TRIGGER_PCT"] = os.environ.get(
        "CONTEXT_CLEAR_TRIGGER_PCT", str(CONTEXT_DEFAULT_TRIGGER_PCT)
    )
    env["CONTEXT_CLEAR_HYSTERESIS_PCT"] = os.environ.get(
        "CONTEXT_CLEAR_HYSTERESIS_PCT", str(CONTEXT_DEFAULT_HYSTERESIS_PCT)
    )
    # NOTIFY_CHANNEL_ID — 알림 카테고리(cycle/digest/alert/recovery) 발사 채널.
    # 미설정 시 MOBRUJI_CHANNEL_ID 로 fallback (현재 동작 유지, 채널 분리 전 단계).
    # spec: docs/features/discord-message-style.md §5-2.
    env["NOTIFY_CHANNEL_ID"] = os.environ.get(
        "NOTIFY_CHANNEL_ID", env["MOBRUJI_CHANNEL_ID"]
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

    def count_since(self, window_seconds: int) -> int:
        """마지막 window_seconds 안에 처리된 메시지 수.

        auto-ack 의 'queue: N' 값. maestro 처리 부하 가시화 목적.
        """
        cutoff = int(time.time()) - window_seconds
        with self._lock:
            row = self._conn.execute(
                "SELECT COUNT(*) FROM processed_messages WHERE processed_at >= ?",
                (cutoff,),
            ).fetchone()
            return int(row[0]) if row else 0


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


def parse_status_progress(text: str) -> str | None:
    """`/system:status <msg>` 면 메시지 본문을 반환. 아니면 None.

    maestro 진척 ad-hoc push 용. on_message 에서 가로채서 dispatch (tmux/repository_dispatch)
    를 건너뛰고 reply 카테고리(📡 status) 메시지만 채널에 send 한다.
    본문 없는 (`/system:status` 단독) 경우는 None 반환 — 의미 없는 빈 push 방지.
    """
    if not text.startswith(STATUS_PROGRESS_PREFIX):
        return None
    remainder = text[len(STATUS_PROGRESS_PREFIX):]
    # prefix 뒤가 공백 또는 EOL 이어야 정확한 매칭 (예: `/system:statusxyz` 는 거부).
    if remainder and not remainder[0].isspace():
        return None
    body = remainder.strip()
    if not body:
        return None
    return body


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


def _run_gh_json(args: list[str], github_pat: str) -> list[dict] | None:
    """gh CLI 호출 후 JSON 배열을 파싱한다. 실패 시 None — 호출부에서 graceful degradation."""
    env = os.environ.copy()
    if github_pat:
        env["GH_TOKEN"] = github_pat
    try:
        result = subprocess.run(
            ["gh", *args],
            capture_output=True,
            text=True,
            timeout=STATUS_GH_TIMEOUT_SECONDS,
            env=env,
            check=False,
        )
    except (subprocess.TimeoutExpired, FileNotFoundError) as exc:
        logger.warning("gh 호출 실패: args=%s err=%s", args, exc)
        return None
    if result.returncode != 0:
        logger.warning(
            "gh 비정상 종료: args=%s rc=%s stderr=%s",
            args,
            result.returncode,
            truncate_for_log(result.stderr),
        )
        return None
    try:
        return json.loads(result.stdout)
    except json.JSONDecodeError as exc:
        logger.warning("gh JSON 파싱 실패: %s", exc)
        return None


def _systemd_is_active(unit: str) -> str:
    """systemctl is-active 결과 한 줄. 실패 시 'unknown'."""
    try:
        result = subprocess.run(
            ["systemctl", "is-active", unit],
            capture_output=True,
            text=True,
            timeout=3,
            check=False,
        )
    except (subprocess.TimeoutExpired, FileNotFoundError):
        return "unknown"
    return (result.stdout or "").strip() or "unknown"


def _truncate_title(title: str, limit: int = 60) -> str:
    if len(title) <= limit:
        return title
    return title[: limit - 1] + "…"


def build_status_report(
    github_repo: str,
    github_pat: str,
    *,
    maestro_unit: str = "mobruji-maestro.service",
    bridge_unit: str = "mobruji-discord-bridge.service",
    now: datetime | None = None,
) -> str:
    """결정성 /status 응답을 1 메시지로 조립한다.

    구성: maestro/bridge systemd 상태 + 진행 중 PR + 최근 머지 + 오픈 type:bug 이슈.
    LLM 호출 없음. 외부는 gh CLI + systemctl 만. 실패 항목은 '(조회 실패)'로 표시.
    """
    now = now or datetime.now(timezone.utc)
    repo_args = ["--repo", github_repo]

    open_prs = _run_gh_json(
        ["pr", "list", *repo_args, "--state", "open", "--limit", str(STATUS_OPEN_PR_LIMIT),
         "--json", "number,title,isDraft,labels"],
        github_pat,
    )
    merged_prs = _run_gh_json(
        ["pr", "list", *repo_args, "--state", "merged", "--limit", str(STATUS_MERGED_PR_LIMIT),
         "--json", "number,title,mergedAt"],
        github_pat,
    )
    bug_issues = _run_gh_json(
        ["issue", "list", *repo_args, "--state", "open", "--label", "type:bug",
         "--limit", str(STATUS_BUG_ISSUE_LIMIT), "--json", "number,title"],
        github_pat,
    )

    maestro_state = _systemd_is_active(maestro_unit)
    bridge_state = _systemd_is_active(bridge_unit)

    lines: list[str] = ["🎼 **mobruji status**"]
    lines.append(
        f"- maestro: `{maestro_state}` · bridge: `{bridge_state}`"
    )

    if open_prs is None:
        lines.append("- 진행 중 PR: (조회 실패)")
    elif not open_prs:
        lines.append("- 진행 중 PR: (없음)")
    else:
        lines.append(f"- 진행 중 PR ({len(open_prs)}):")
        for pr in open_prs:
            draft = " 📝" if pr.get("isDraft") else ""
            lines.append(f"  • #{pr['number']}{draft} {_truncate_title(pr['title'])}")

    if merged_prs is None:
        lines.append("- 최근 머지: (조회 실패)")
    elif not merged_prs:
        lines.append("- 최근 머지: (없음)")
    else:
        lines.append(f"- 최근 머지 ({len(merged_prs)}):")
        for pr in merged_prs:
            lines.append(f"  • #{pr['number']} {_truncate_title(pr['title'])}")

    if bug_issues is None:
        lines.append("- 알려진 오류: (조회 실패)")
    elif not bug_issues:
        lines.append("- 알려진 오류: (없음)")
    else:
        lines.append(f"- 알려진 오류 ({len(bug_issues)}):")
        for issue in bug_issues:
            lines.append(f"  • #{issue['number']} {_truncate_title(issue['title'])}")

    kst = now.astimezone(timezone(timedelta(hours=9), name="KST"))
    lines.append(f"- as of: {kst.strftime('%Y-%m-%d %H:%M %Z')}")

    report = "\n".join(lines)
    if len(report) > STATUS_DISCORD_MAX_LEN:
        report = report[: STATUS_DISCORD_MAX_LEN - 1] + "…"
    return report


def build_digest_line(
    github_repo: str,
    github_pat: str,
    *,
    now: datetime | None = None,
) -> str:
    """cron digest 1줄. /status 보다 압축.

    형식: 📊 PR open:N / 머지 24h:N / bug:N — HH:MM KST
    (`type:bug:` 표기는 Discord 가 :bug: 를 🐛 emoji 로 변환하므로 prefix 제거.)
    조회 실패는 '?' 로 표시. LLM 호출 없음.
    """
    line, _signature = build_digest_payload(github_repo, github_pat, now=now)
    return line


def build_digest_payload(
    github_repo: str,
    github_pat: str,
    *,
    now: datetime | None = None,
) -> tuple[str, str]:
    """digest 1줄(rendered) 과 delta 비교용 signature(시간 제외) 를 함께 반환한다.

    signature 는 'open=N|merged24=N|bug=N' 형태. 호출 실패한 항목은 '?' 그대로 들어가므로
    조회 실패가 연속되어도 '동일 signature' 로 잡혀 delta skip 된다.
    """
    now = now or datetime.now(timezone.utc)
    repo_args = ["--repo", github_repo]

    open_prs = _run_gh_json(
        ["pr", "list", *repo_args, "--state", "open", "--limit", "30",
         "--json", "number,title"],
        github_pat,
    )
    since = (now - timedelta(hours=DIGEST_MERGED_WINDOW_HOURS)).strftime("%Y-%m-%dT%H:%M:%SZ")
    merged_recent = _run_gh_json(
        ["pr", "list", *repo_args, "--state", "merged", "--search", f"merged:>{since}",
         "--limit", "30", "--json", "number,title,mergedAt"],
        github_pat,
    )
    bug_issues = _run_gh_json(
        ["issue", "list", *repo_args, "--state", "open", "--label", "type:bug",
         "--limit", "30", "--json", "number,title"],
        github_pat,
    )

    def fmt(value: list | None) -> str:
        return "?" if value is None else str(len(value))

    open_count = fmt(open_prs)
    merged_count = fmt(merged_recent)
    bug_count = fmt(bug_issues)

    kst = now.astimezone(timezone(timedelta(hours=9), name="KST"))

    # 최근 머지 PR 3개 title preview (가장 최신 순). 데이터 없으면 라인 생략.
    # title 에 `@everyone`/`@here`/`<@USER>` 가 포함되면 Discord 가 mention 으로
    # 해석해 알림 폭주가 발생하므로 sanitize_mentions 적용 (#754).
    recent_lines: list[str] = []
    if merged_recent:
        sorted_recent = sorted(
            merged_recent,
            key=lambda pr: pr.get("mergedAt", ""),
            reverse=True,
        )[:3]
        for pr in sorted_recent:
            title = pr.get("title", "")
            if len(title) > 60:
                title = title[:60] + "…"
            recent_lines.append(f"  · #{pr.get('number')} {sanitize_mentions(title)}")

    # 백로그 시그널: open PR 3개 title preview.
    backlog_lines: list[str] = []
    if open_prs:
        for pr in open_prs[:3]:
            title = pr.get("title", "")
            if len(title) > 60:
                title = title[:60] + "…"
            backlog_lines.append(f"  · #{pr.get('number')} {sanitize_mentions(title)}")

    bug_lines: list[str] = []
    if bug_issues:
        for issue in bug_issues[:3]:
            title = issue.get("title", "")
            if len(title) > 60:
                title = title[:60] + "…"
            bug_lines.append(f"  · #{issue.get('number')} {sanitize_mentions(title)}")

    parts = [
        f"📊 **{kst.strftime('%H:%M')} KST digest**",
        f"✅ 머지 {DIGEST_MERGED_WINDOW_HOURS}h: {merged_count}",
    ]
    parts.extend(recent_lines)
    parts.append(f"🔄 open PR: {open_count}")
    parts.extend(backlog_lines)
    bug_label = "🐛 OPEN" if bug_count not in {"0", "?"} else "🐛 없음"
    parts.append(f"{bug_label}: {bug_count}")
    parts.extend(bug_lines)

    line = "\n".join(parts)
    signature = f"open={open_count}|merged{DIGEST_MERGED_WINDOW_HOURS}={merged_count}|bug={bug_count}"
    return line, signature


def resolve_digest_interval(env_value: str | None) -> int:
    """DIGEST_INTERVAL_SECONDS env 값을 정수로 해석. 부재/이상값이면 default."""
    if env_value is None:
        return DEFAULT_DIGEST_INTERVAL_SECONDS
    try:
        parsed = int(env_value)
    except ValueError:
        logger.warning(
            "DIGEST_INTERVAL_SECONDS 가 정수 아님(%r) — 기본값 사용: %d",
            env_value,
            DEFAULT_DIGEST_INTERVAL_SECONDS,
        )
        return DEFAULT_DIGEST_INTERVAL_SECONDS
    if parsed <= 0:
        logger.warning(
            "DIGEST_INTERVAL_SECONDS 가 양수 아님(%d) — 기본값 사용: %d",
            parsed,
            DEFAULT_DIGEST_INTERVAL_SECONDS,
        )
        return DEFAULT_DIGEST_INTERVAL_SECONDS
    return parsed


async def digest_loop(
    client: "discord.Client",
    channel_id: int,
    github_repo: str,
    github_pat: str,
    *,
    interval: int = DEFAULT_DIGEST_INTERVAL_SECONDS,
    initial_delay: int = DIGEST_INITIAL_DELAY_SECONDS,
    heartbeat_seconds: int = DIGEST_HEARTBEAT_SECONDS,
    time_source=time.monotonic,
) -> None:
    """on_ready 직후 launch. interval 초 마다 digest 1줄 push (delta + heartbeat).

    - 직전 push 와 signature(시간 제외 counts) 가 동일하면 noise 라고 보고 skip.
    - signature 가 바뀌면 즉시 push (= delta push).
    - 동일해도 마지막 push 로부터 heartbeat_seconds 경과 시 한 번 push (생존 신호).
    - 첫 iter 는 last signature 가 없으므로 무조건 push (초기 baseline).

    bot 종료 시 cancel 됨. asyncio.CancelledError 는 외부로 전파.
    """
    await asyncio.sleep(initial_delay)
    last_signature: str | None = None
    last_pushed_at: float | None = None
    while True:
        try:
            channel = client.get_channel(channel_id)
            if channel is None:
                logger.warning("digest: channel_id=%s 찾을 수 없음 — skip 후 재시도", channel_id)
            else:
                line, signature = build_digest_payload(github_repo, github_pat)
                now_ts = time_source()
                should_push = False
                reason = ""
                if last_signature is None:
                    should_push = True
                    reason = "initial"
                elif signature != last_signature:
                    should_push = True
                    reason = "delta"
                elif (
                    last_pushed_at is not None
                    and (now_ts - last_pushed_at) >= heartbeat_seconds
                ):
                    should_push = True
                    reason = "heartbeat"

                if should_push:
                    await channel.send(line)
                    last_signature = signature
                    last_pushed_at = now_ts
                    logger.info("digest push: reason=%s signature=%s", reason, signature)
                else:
                    logger.debug(
                        "digest skip: signature unchanged (%s), since_last=%.0fs",
                        signature,
                        0.0 if last_pushed_at is None else now_ts - last_pushed_at,
                    )
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001
            logger.warning("digest send 실패: %s", exc)
        await asyncio.sleep(interval)


def strip_ansi(text: str) -> str:
    """ANSI escape sequence 제거. claude TUI 출력의 컬러/커서 코드 정리."""
    return ANSI_ESCAPE_RE.sub("", text)


def mask_secrets(text: str) -> str:
    """tmux pane buffer 의 PAT/토큰/패스워드 마스킹 (#742).

    maestro Claude TUI 가 gh/curl/env 명령 echo 시 PAT 가 raw 노출될 수 있으므로
    Discord push 전 SECRET_MASK_PATTERNS 순서대로 치환한다. 패턴 미매칭이면 원문 반환.

    env-style `KEY=value` 는 _ENV_STYLE_SECRET_RE + _mask_env_style_secret 콜백으로
    별도 처리 — PUBLIC/VERSION 등 화이트리스트 / 짧은 값 false positive 제외 (#759).
    """
    masked = text
    for pattern, replacement in SECRET_MASK_PATTERNS:
        masked = pattern.sub(replacement, masked)
    masked = _ENV_STYLE_SECRET_RE.sub(_mask_env_style_secret, masked)
    return masked


# Discord mention 토큰 차단 패턴 (#754).
# PR title / tmux pane chunk 에 `@everyone` / `@here` / `<@USER_ID>` 등이 그대로
# 들어가면 Discord 가 mention 으로 해석해 알림 폭주를 일으킨다. zero-width space (​)
# 를 끼워 넣어 시각적으로는 거의 동일하되 mention parsing 은 무력화한다.
# Discord mention 문법:
#   - `@everyone` / `@here` (literal)
#   - `<@USER_ID>` / `<@!USER_ID>` (user)
#   - `<@&ROLE_ID>` (role)
# 모두 prefix 직후에 ​ 를 삽입하면 안전하게 깨진다.
MENTION_SANITIZE_PATTERNS: Final[tuple[tuple[re.Pattern[str], str], ...]] = (
    (re.compile(r"@everyone"), "@​everyone"),
    (re.compile(r"@here"), "@​here"),
    # `<@123>`, `<@!123>`, `<@&123>` — `<@` 다음에 ​ 삽입.
    (re.compile(r"<@(?=[!&]?\d)"), "<​@"),
)


def sanitize_mentions(text: str) -> str:
    """Discord mention 토큰을 zero-width space 로 무력화 (#754).

    PR title / maestro tmux pane chunk 가 Discord 로 송신되기 전 호출.
    `@everyone`, `@here`, `<@USER_ID>`, `<@!USER_ID>`, `<@&ROLE_ID>` 등
    Discord 가 mention 으로 해석하는 모든 토큰의 prefix 직후에 U+200B
    (zero-width space) 를 삽입해 알림 폭주를 차단한다.
    """
    sanitized = text
    for pattern, replacement in MENTION_SANITIZE_PATTERNS:
        sanitized = pattern.sub(replacement, sanitized)
    return sanitized


def sanitize_chunk(text: str) -> str | None:
    """raw tmux pane buffer 를 Discord push 후보로 정리.

    - ANSI escape 제거
    - 라인 단위로 trim 후 빈 라인 압축 (연속 공백 라인 1개로)
    - 최소 길이 미만이면 None (노이즈 — 사용자 입력 echo / 짧은 prompt 등)
    - 최대 길이 초과 시 잘라낸 뒤 `…(truncated)` 표시

    Discord 한도 2000자. 너무 길면 잘라야 send 가 성공.
    시크릿 마스킹은 ANSI 제거 후 / 라인 압축 전에 수행 — chunk 가 잘리거나 dedup
    되더라도 원문 시크릿이 절대 send 되지 않도록 보장 (#742).
    mention sanitize 도 같은 위치에서 수행 — maestro pane 에 echo 된 `@everyone`
    등이 Discord mention 으로 발화되지 않도록 차단 (#754).
    """
    cleaned = sanitize_mentions(mask_secrets(strip_ansi(text)))
    # 라인별 rstrip + 빈 라인 합치기
    lines: list[str] = []
    blank_run = 0
    for raw_line in cleaned.split("\n"):
        line = raw_line.rstrip()
        if not line:
            blank_run += 1
            if blank_run <= 1:
                lines.append("")
            continue
        blank_run = 0
        lines.append(line)
    compact = "\n".join(lines).strip()
    if len(compact) < MAESTRO_WATCHER_MIN_CHUNK_LEN:
        return None
    if len(compact) > MAESTRO_WATCHER_MAX_CHUNK_LEN:
        compact = compact[: MAESTRO_WATCHER_MAX_CHUNK_LEN - 16] + "\n…(truncated)"
    return compact


def parse_context_pct(pane_text: str) -> int | None:
    """`===CTX:NN%===` 마커의 마지막 occurrence 를 정수로 반환. 없으면 None.

    spec §5-6 옵션 A — maestro 가 매 turn 끝에 self-emit. footer scrape 안 함.
    NN 은 0~100 이외 (예: 105) 일 수 있으나 호출부에서 임계값과 비교 시 자연 통과.
    """
    matches = CONTEXT_MARKER_RE.findall(pane_text)
    if not matches:
        return None
    try:
        return int(matches[-1])
    except ValueError:
        return None


def read_tail_text(path: Path, max_bytes: int) -> str:
    """파일 끝 max_bytes 만 utf-8 로 디코딩하여 반환. 파일 없으면 빈 문자열.

    pipe-pane log 가 100MB 까지 성장 가능 — 매 polling 마다 전부 읽지 않도록 tail.
    UTF-8 경계가 잘릴 수 있어 errors='replace'.
    """
    try:
        stat = path.stat()
    except OSError:
        return ""
    size = stat.st_size
    start = max(0, size - max_bytes)
    try:
        with path.open("rb") as handle:
            handle.seek(start)
            data = handle.read(size - start)
    except OSError as exc:
        logger.debug("context auto-clear: tail read 실패 %s", exc)
        return ""
    return strip_ansi(data.decode("utf-8", errors="replace"))


def inject_cleanup_prompt(target_pane: str) -> bool:
    """tmux send-keys 로 정리 prompt 한 줄 inject + Enter. 성공 시 True.

    tmux_send_payload 와 동일 동작 — 코드 재사용. sentinel 우회 (`/system:` prefix 없음)
    이므로 일반 literal path.
    """
    return tmux_send_payload(target_pane, CONTEXT_CLEANUP_PROMPT)


def send_clear_command(target_pane: str) -> bool:
    """tmux send-keys '/clear' Enter. Claude TUI 의 /clear slash 명령 트리거."""
    return tmux_send_payload(target_pane, "/clear")


async def context_auto_clear_loop(
    client: "discord.Client",
    channel_id: int,
    pipe_pane_path: str,
    target_pane: str,
    *,
    trigger_pct: int = CONTEXT_DEFAULT_TRIGGER_PCT,
    hysteresis_pct: int = CONTEXT_DEFAULT_HYSTERESIS_PCT,
    poll_interval: float = CONTEXT_AUTO_CLEAR_POLL_INTERVAL_SECONDS,
    tail_bytes: int = CONTEXT_AUTO_CLEAR_TAIL_BYTES,
    sleep=asyncio.sleep,
    inject_fn=None,
    clear_fn=None,
) -> None:
    """`===CTX:NN%===` 마커 polling — trigger_pct 도달 시 정리 prompt inject,
    `===CLEAR_READY===` 마커 감지 시 /clear 전송.

    State machine (3 상태):
      ARMED: 트리거 대기 상태. pct >= trigger_pct 시 inject → AWAITING_MARKER.
      AWAITING_MARKER: 정리 prompt inject 후 maestro 의 정리 완료 marker 대기.
        marker 감지 시 /clear 송신 → DEBOUNCED.
      DEBOUNCED: /clear 완료. pct <= hysteresis_pct 떨어져야 ARMED 복귀.
        그동안 추가 trigger 안 됨.

    dedup: 같은 NN% 가 연속 polling 에 보여도 (state 가 ARMED 가 아니면) 추가 발화 없음.
    inject_fn / clear_fn 주입 가능 — 테스트에서 tmux 호출 mock.

    bot 종료 시 cancel. asyncio.CancelledError 는 외부로 전파.
    """
    path = Path(pipe_pane_path)
    inject = inject_fn if inject_fn is not None else (lambda: inject_cleanup_prompt(target_pane))
    clear = clear_fn if clear_fn is not None else (lambda: send_clear_command(target_pane))
    state = "ARMED"
    logger.info(
        "context_auto_clear_loop 시작: path=%s pane=%s trigger=%d%% hysteresis=%d%% poll=%.1fs",
        pipe_pane_path,
        target_pane,
        trigger_pct,
        hysteresis_pct,
        poll_interval,
    )
    while True:
        try:
            tail = read_tail_text(path, tail_bytes)
            pct = parse_context_pct(tail)
            marker_seen = CONTEXT_CLEAR_READY_MARKER in tail

            if state == "AWAITING_MARKER" and marker_seen:
                # 정리 완료 — /clear 전송. pct 는 marker 와 함께 emit 안 됐을 수도
                # 있으므로 None 허용.
                pct_label = f"{pct}%" if pct is not None else "?"
                logger.info(
                    "context auto-clear: CLEAR_READY 감지 (pct=%s) → /clear 송신",
                    pct_label,
                )
                channel = client.get_channel(channel_id)
                if channel is not None:
                    try:
                        await channel.send(
                            f"🧹 정리 완료 → /clear 전송 (마지막 context {pct_label})"
                        )
                    except Exception as exc:  # noqa: BLE001
                        logger.warning("context auto-clear push 실패: %s", exc)
                if clear():
                    state = "DEBOUNCED"
                else:
                    # /clear 송신 실패 — AWAITING_MARKER 유지하고 다음 iter 에 재시도.
                    logger.warning("context auto-clear: /clear 송신 실패 — 다음 iter 재시도")
            elif state == "DEBOUNCED":
                # hysteresis 해제 — pct 가 임계 이하로 떨어졌을 때만 ARMED 복귀.
                if pct is not None and pct <= hysteresis_pct:
                    logger.info(
                        "context auto-clear: hysteresis 해제 (pct=%d%% <= %d%%) → ARMED",
                        pct,
                        hysteresis_pct,
                    )
                    state = "ARMED"
            elif state == "ARMED":
                if pct is not None and pct >= trigger_pct:
                    logger.info(
                        "context auto-clear: 트리거 (pct=%d%% >= %d%%) → cleanup prompt inject",
                        pct,
                        trigger_pct,
                    )
                    channel = client.get_channel(channel_id)
                    if channel is not None:
                        try:
                            await channel.send(
                                f"🧠 context {pct}% → 자율 정리 시작"
                            )
                        except Exception as exc:  # noqa: BLE001
                            logger.warning("context auto-clear push 실패: %s", exc)
                    if inject():
                        state = "AWAITING_MARKER"
                    else:
                        logger.warning(
                            "context auto-clear: inject 실패 — ARMED 유지하고 다음 iter 재시도"
                        )
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001
            logger.warning("context auto-clear loop 예외: %s", exc)

        await sleep(poll_interval)


def chunk_signature(text: str) -> str:
    """chunk content 의 안정적 해시. dedup ledger message_id 로 사용.

    `maestro:` prefix 로 사용자 메시지 id 와 namespace 분리.
    """
    digest = hashlib.sha256(text.encode("utf-8", errors="replace")).hexdigest()
    return f"{MAESTRO_WATCHER_DEDUP_PREFIX}{digest[:32]}"


async def maestro_response_watcher_loop(
    client: "discord.Client",
    channel_id: int,
    pipe_pane_path: str,
    ledger: DedupLedger | None,
    *,
    poll_interval: float = MAESTRO_WATCHER_POLL_INTERVAL_SECONDS,
    idle_seconds: float = MAESTRO_WATCHER_IDLE_SECONDS,
    time_source=time.monotonic,
    sleep=asyncio.sleep,
    initial_offset: int | None = None,
) -> None:
    """tmux pipe-pane 캡처 파일을 tail 하여 maestro 응답을 자동 push.

    동작:
    - poll_interval 마다 파일 size 확인. 새 바이트가 있으면 읽어 buffer 누적.
    - 마지막 신규 바이트 도착 후 idle_seconds 동안 신규 없음 = "응답 완료" 로 간주.
    - buffer 를 sanitize → 최소 길이 통과 + dedup miss 면 channel.send + ledger mark.
    - 사용자 입력 echo 등 짧은 chunk 는 sanitize_chunk 에서 None 으로 skip.

    초기 offset 은 파일 현재 끝 (이미 쌓인 과거 출력을 한꺼번에 push 하지 않음).
    initial_offset 지정 시 그 값으로 시작 (테스트용).

    bot 종료 시 cancel 됨. asyncio.CancelledError 는 외부로 전파.
    """
    path = Path(pipe_pane_path)
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.touch(exist_ok=True)
    except OSError as exc:
        logger.warning("maestro watcher: 캡처 파일 준비 실패 %s — loop 종료", exc)
        return

    if initial_offset is None:
        try:
            offset = path.stat().st_size
        except OSError:
            offset = 0
    else:
        offset = initial_offset

    buffer = ""
    last_new_at: float | None = None
    # send 실패 재시도 상태. pending_candidate 가 존재하면 다음 idle flush 에서 같은 chunk 를 재시도한다.
    pending_candidate: str | None = None
    pending_sig: str | None = None
    pending_retry_count = 0
    logger.info(
        "maestro_response_watcher_loop 시작: path=%s offset=%d idle=%.0fs poll=%.1fs",
        pipe_pane_path,
        offset,
        idle_seconds,
        poll_interval,
    )

    while True:
        try:
            try:
                stat = path.stat()
            except OSError as exc:
                logger.debug("maestro watcher: stat 실패 %s", exc)
                stat = None

            if stat is not None:
                size = stat.st_size
                # 파일이 회전(truncate/rotate) 되었거나 새 파일로 교체된 경우 offset 리셋.
                if size < offset:
                    logger.info(
                        "maestro watcher: 파일 회전 감지 (size=%d < offset=%d) — reset",
                        size,
                        offset,
                    )
                    offset = 0
                    buffer = ""
                    last_new_at = None

                if size > offset:
                    try:
                        with path.open("rb") as handle:
                            handle.seek(offset)
                            new_bytes = handle.read(size - offset)
                        chunk_text = new_bytes.decode("utf-8", errors="replace")
                        buffer += chunk_text
                        offset = size
                        last_new_at = time_source()
                    except OSError as exc:
                        logger.warning("maestro watcher: 파일 read 실패 %s", exc)

            # pending (직전 send 실패) 가 없을 때만 새 chunk 를 idle 임계로 확정한다.
            # pending 이 있으면 그 chunk 를 우선 재시도한다 (새 buffer 는 계속 누적).
            if pending_candidate is None and buffer and last_new_at is not None:
                idle_for = time_source() - last_new_at
                if idle_for >= idle_seconds:
                    candidate = sanitize_chunk(buffer)
                    buffer = ""
                    last_new_at = None
                    if candidate is None:
                        logger.debug("maestro watcher: chunk skip (length < min)")
                    else:
                        pending_candidate = candidate
                        pending_sig = chunk_signature(candidate)
                        pending_retry_count = 0

            # pending chunk 가 있으면 send 시도. 실패하면 다음 iteration 에서 재시도.
            if pending_candidate is not None and pending_sig is not None:
                if ledger is not None and ledger.is_processed(pending_sig):
                    logger.info("maestro watcher: dedup hit sig=%s", pending_sig)
                    pending_candidate = None
                    pending_sig = None
                    pending_retry_count = 0
                else:
                    channel = client.get_channel(channel_id)
                    if channel is None:
                        logger.warning(
                            "maestro watcher: channel_id=%s 못 찾음 — skip",
                            channel_id,
                        )
                        # 채널 미발견은 transient 가능 (bot 아직 ready 전 등) → 재시도 카운트.
                        pending_retry_count += 1
                        if pending_retry_count >= MAESTRO_WATCHER_SEND_MAX_RETRIES:
                            logger.error(
                                "maestro watcher: send drop (channel 미발견 %d회 연속) sig=%s len=%d",
                                pending_retry_count,
                                pending_sig,
                                len(pending_candidate),
                            )
                            pending_candidate = None
                            pending_sig = None
                            pending_retry_count = 0
                    else:
                        try:
                            await channel.send(pending_candidate)
                            if ledger is not None:
                                ledger.mark_processed(pending_sig)
                            logger.info(
                                "maestro watcher push: sig=%s len=%d retries=%d",
                                pending_sig,
                                len(pending_candidate),
                                pending_retry_count,
                            )
                            pending_candidate = None
                            pending_sig = None
                            pending_retry_count = 0
                        except Exception as exc:  # noqa: BLE001
                            pending_retry_count += 1
                            if pending_retry_count >= MAESTRO_WATCHER_SEND_MAX_RETRIES:
                                logger.error(
                                    "maestro watcher: send drop (%d회 연속 실패) sig=%s len=%d 마지막 예외=%s",
                                    pending_retry_count,
                                    pending_sig,
                                    len(pending_candidate),
                                    exc,
                                )
                                pending_candidate = None
                                pending_sig = None
                                pending_retry_count = 0
                            else:
                                logger.warning(
                                    "maestro watcher send 실패 (재시도 %d/%d): %s",
                                    pending_retry_count,
                                    MAESTRO_WATCHER_SEND_MAX_RETRIES,
                                    exc,
                                )
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001
            logger.warning("maestro watcher loop 예외: %s", exc)

        await sleep(poll_interval)


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

    # NOTIFY_CHANNEL_ID 파싱 — 알림 카테고리 발사 채널. 값 이상 시 메인 채널로 fallback
    # (운영 끊김 회피). spec: docs/features/discord-message-style.md §5-2.
    notify_raw = env.get("NOTIFY_CHANNEL_ID", env["MOBRUJI_CHANNEL_ID"])
    try:
        notify_channel_id = int(notify_raw)
    except ValueError:
        logger.warning(
            "NOTIFY_CHANNEL_ID 가 정수 아님(%r) — 메인 채널(%d)로 fallback",
            notify_raw,
            target_channel_id,
        )
        notify_channel_id = target_channel_id

    tmux_enabled = env["TMUX_BRIDGE_ENABLED"] == "1"
    session_name = env["TMUX_SESSION_NAME"]
    target_pane = env["TMUX_TARGET_PANE"]
    claude_bin = env["CLAUDE_BIN"]

    digest_enabled = env.get("DIGEST_ENABLED", "0") == "1"
    digest_interval = resolve_digest_interval(env.get("DIGEST_INTERVAL_SECONDS"))

    maestro_watcher_enabled = env.get("MAESTRO_RESPONSE_WATCHER_ENABLED", "0") == "1"
    maestro_watcher_path = env.get("TMUX_PIPE_PANE_PATH", "")

    context_clear_enabled = env.get("CONTEXT_AUTO_CLEAR_ENABLED", "0") == "1"
    try:
        context_trigger_pct = int(env.get("CONTEXT_CLEAR_TRIGGER_PCT",
                                          str(CONTEXT_DEFAULT_TRIGGER_PCT)))
    except ValueError:
        logger.warning(
            "CONTEXT_CLEAR_TRIGGER_PCT 정수 아님 — 기본값 %d 사용",
            CONTEXT_DEFAULT_TRIGGER_PCT,
        )
        context_trigger_pct = CONTEXT_DEFAULT_TRIGGER_PCT
    try:
        context_hysteresis_pct = int(env.get("CONTEXT_CLEAR_HYSTERESIS_PCT",
                                             str(CONTEXT_DEFAULT_HYSTERESIS_PCT)))
    except ValueError:
        logger.warning(
            "CONTEXT_CLEAR_HYSTERESIS_PCT 정수 아님 — 기본값 %d 사용",
            CONTEXT_DEFAULT_HYSTERESIS_PCT,
        )
        context_hysteresis_pct = CONTEXT_DEFAULT_HYSTERESIS_PCT

    @client.event
    async def on_ready() -> None:  # noqa: D401
        logger.info(
            "Discord Gateway 연결 OK: user=%s channel=%s notify=%s allowed=%d tmux_bridge=%s digest=%s",
            client.user,
            target_channel_id,
            notify_channel_id,
            len(allowed_user_ids),
            tmux_enabled,
            digest_enabled,
        )
        if digest_enabled and not hasattr(client, "_digest_task_started"):
            # on_ready 는 reconnect 시 재호출 — task 중복 시작 방지.
            client._digest_task_started = True  # type: ignore[attr-defined]
            client.loop.create_task(
                digest_loop(
                    client,
                    notify_channel_id,
                    env["GITHUB_REPO"],
                    env.get("GITHUB_PAT", ""),
                    interval=digest_interval,
                )
            )
            logger.info(
                "digest_loop launched: channel=%d interval=%ds heartbeat=%ds",
                notify_channel_id,
                digest_interval,
                DIGEST_HEARTBEAT_SECONDS,
            )

        if (
            maestro_watcher_enabled
            and maestro_watcher_path
            and not hasattr(client, "_maestro_watcher_started")
        ):
            client._maestro_watcher_started = True  # type: ignore[attr-defined]
            client.loop.create_task(
                maestro_response_watcher_loop(
                    client,
                    target_channel_id,
                    maestro_watcher_path,
                    ledger,
                )
            )
            logger.info(
                "maestro_response_watcher_loop launched: channel=%d path=%s",
                target_channel_id,
                maestro_watcher_path,
            )

        if (
            context_clear_enabled
            and maestro_watcher_path
            and not hasattr(client, "_context_clear_task_started")
        ):
            client._context_clear_task_started = True  # type: ignore[attr-defined]
            client.loop.create_task(
                context_auto_clear_loop(
                    client,
                    notify_channel_id,
                    maestro_watcher_path,
                    target_pane,
                    trigger_pct=context_trigger_pct,
                    hysteresis_pct=context_hysteresis_pct,
                )
            )
            logger.info(
                "context_auto_clear_loop launched: channel=%d path=%s pane=%s trigger=%d hysteresis=%d",
                notify_channel_id,
                maestro_watcher_path,
                target_pane,
                context_trigger_pct,
                context_hysteresis_pct,
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

        # /status — 결정성 빠른 응답. tmux/dispatch 분기 건너뜀.
        content = (message.content or "").strip()
        if content.split(maxsplit=1)[:1] == [STATUS_COMMAND_PREFIX]:
            logger.info("/status 요청: user=%s", message.author.id)
            report = build_status_report(env["GITHUB_REPO"], env.get("GITHUB_PAT", ""))
            try:
                await message.channel.send(report)
            except Exception as exc:  # noqa: BLE001
                logger.error("/status 응답 send 실패: %s", exc)
            if ledger is not None:
                ledger.mark_processed(message_id)
            return

        # /system:status <msg> — maestro 진척 ad-hoc push. dispatch 건너뜀.
        # reply 카테고리(📡 status: ...) 한 줄로 채널에 push. ledger mark 로 dedup.
        status_body = parse_status_progress(content)
        if status_body is not None:
            logger.info(
                "/system:status push: user=%s preview=%r",
                message.author.id,
                truncate_for_log(status_body),
            )
            try:
                await message.channel.send(
                    f"{MESSAGE_PREFIX['reply']} status: {status_body}"
                )
            except Exception as exc:  # noqa: BLE001
                logger.error("/system:status send 실패: %s", exc)
            if ledger is not None:
                ledger.mark_processed(message_id)
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

        # auto-ack — 1초 안 채널 응답. maestro 거치지 않음. /status 분기는 이미 위에서 처리됨.
        # queue: 마지막 AUTO_ACK_QUEUE_WINDOW_SECONDS 안에 처리된 메시지 수 + 현재(=1).
        queue_count = (ledger.count_since(AUTO_ACK_QUEUE_WINDOW_SECONDS) + 1) if ledger else 1
        ack_text = AUTO_ACK_TEMPLATE.format(queue=queue_count)
        try:
            await message.channel.send(ack_text)
        except Exception as exc:  # noqa: BLE001
            logger.warning("auto-ack 발송 실패: %s", exc)

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
    # ledger 는 TMUX_BRIDGE_ENABLED 또는 MAESTRO_RESPONSE_WATCHER_ENABLED 중 하나라도 켜져 있으면 필요.
    # (전자는 사용자 메시지 dedup, 후자는 maestro 응답 chunk dedup.)
    needs_ledger = (
        env["TMUX_BRIDGE_ENABLED"] == "1"
        or env.get("MAESTRO_RESPONSE_WATCHER_ENABLED", "0") == "1"
    )
    if needs_ledger:
        ledger = DedupLedger(env["DEDUP_LEDGER_PATH"])
        start_dedup_gc_thread(ledger)
    if env["TMUX_BRIDGE_ENABLED"] == "1":
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
