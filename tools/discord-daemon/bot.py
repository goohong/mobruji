"""Mobruji Discord daemon — 단순화본 (이슈 #807).

기능 (사용자 결정 2026-05-23 큰 단순화 위임):
1. Discord Gateway WebSocket 24/7 유지.
2. 지정 채널(MOBRUJI_CHANNEL_ID) + 화이트리스트(ALLOWED_USER_IDS) 사용자 메시지를
   `tmux send-keys -t helper:0.0 "<msg>" Enter` 로 helper pane 에 단순 routing.
3. cycle-status.json digest cron — `~/.mobruji/cycle-status.json` 을 일정 주기로
   읽어 4 워크트리(be/fe/rev/plan) 진행/최근 한 줄씩 Discord 알림 채널에 push.

helper 응답은 helper 측 신규 script `~/.mobruji/discord-reply.sh "<msg>"` 가
직접 Discord REST API 로 push 합니다 (bot.py 안에서 응답 watcher 가동 안 함).

운영 가이드와 셋업 절차는 같은 디렉토리의 README.md 참고.
spec: docs/features/discord-driven-mobruji.md
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import re
import sqlite3
import subprocess
import sys
import threading
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Final
from zoneinfo import ZoneInfo

import discord
from dotenv import load_dotenv

LOG_FORMAT: Final[str] = "%(asctime)s %(levelname)s %(name)s :: %(message)s"
INBOX_PATH: Final[Path] = Path(__file__).resolve().parent / "inbox.jsonl"
INBOX_FILE_MODE: Final[int] = 0o600
MAX_TEXT_PREVIEW_LEN: Final[int] = 80
# inbox.jsonl 에 저장되는 사용자 메시지 본문 최대 길이 (#909 F-1).
# PII/민감 본문이 평문으로 디스크에 남는 위험을 완화 — 백업/디버깅 용도 한정.
INBOX_TEXT_MAX_LEN: Final[int] = 500
DEDUP_TTL_SECONDS: Final[int] = 24 * 60 * 60  # 24h
DEDUP_GC_INTERVAL_SECONDS: Final[int] = 60 * 60  # 1h

# bot.py 1초 generic auto-ack (#880) — helper bash chain latency 시 사용자 깜깜이 해소.
# #807 에서 제거됐던 것 부활. helper 측 구체 ack 와 직렬로 보이게 됨.
BOT_AUTO_ACK_DEFAULT_ENABLED: Final[str] = "1"
BOT_AUTO_ACK_TEXT: Final[str] = "🤖 helper bot 수신 — helper 가 nmae 상태 확인 중. 곧 답변드립니다."

# reply.referenced_message forwarding (#880) — 사용자 Discord "답장" 으로 보낸 메시지가
# 어떤 메시지에 대한 답장인지 helper 가 알 수 있도록 prefix.
REPLY_CONTEXT_PREVIEW_LEN: Final[int] = 30
REPLY_CONTEXT_PREFIX_TEMPLATE: Final[str] = "[답장→ {preview}] {body}"

# helper 본답 → 사용자 메시지 reply (#946, 2026-05-24).
# bot.py 가 사용자 메시지를 helper 로 forward 할 때 그 message_id 를 file 에
# atomic 으로 기록. discord-reply.sh bare body 모드가 그 파일을 읽어
# Discord REST API payload 에 `message_reference` 를 포함시켜 자동 reply.
#
# Why: 사용자 입장에서 채널 누적 메시지 중 어떤 본문에 대한 helper 답인지
# 시각적으로 추적하기 위함. standalone 메시지는 짝짓기가 어려움.
LAST_USER_MSG_ID_PATH: Final[Path] = Path(
    "~/.mobruji/last-user-msg-id.txt"
).expanduser()
LAST_USER_MSG_ID_FILE_MODE: Final[int] = 0o600

# digest cron 튜닝값 — cycle-status.json (사용자 룰 2026-05-23).
DEFAULT_DIGEST_INTERVAL_SECONDS: Final[int] = 900  # 15분
DIGEST_INITIAL_DELAY_SECONDS: Final[int] = 60  # boot 1분 warmup
DIGEST_HEARTBEAT_SECONDS: Final[int] = 60 * 60  # delta 없어도 1h 1회는 push

# Discord API resilience (#911 G-6) — channel.send 시 429 / 5xx 명시적 retry.
# discord.py 가 라이브러리 차원 ratelimiter 를 가지지만 갑작스러운 5xx /
# transient HTTP error 는 그대로 raise. defense-in-depth 로 명시적 retry 추가.
DISCORD_SEND_RETRY_MAX: Final[int] = 3
DISCORD_SEND_RETRY_BASE_SEC: Final[float] = 1.0
DEFAULT_CYCLE_STATUS_PATH: Final[str] = os.path.expanduser("~/.mobruji/cycle-status.json")
CYCLE_DIGEST_WORKSPACES: Final[tuple[str, ...]] = ("be", "fe", "rev", "plan")
CYCLE_DIGEST_MAX_LINE_LEN: Final[int] = 200
# digest 본문 timestamp — 사용자 요청 #811. Discord 가 보여주는 시각이 클라이언트
# timezone 에 의존하므로 본문에 KST 명시로 한눈에 emit 시각 확인.
CYCLE_DIGEST_TZ: Final[ZoneInfo] = ZoneInfo("Asia/Seoul")
CYCLE_DIGEST_TIME_FORMAT: Final[str] = "%Y-%m-%d %H:%M KST"
# Embed 시각화 — UX 개선 #840.
# 워크트리별 역할 인지용 emoji prefix. field name 에 적용.
CYCLE_DIGEST_WORKSPACE_EMOJI: Final[dict[str, str]] = {
    "be": "🛠",
    "fe": "🎨",
    "rev": "🔍",
    "plan": "📋",
}
# Embed 색상: 모든 워크트리 idle 이면 gray (조용한 상태), 그 외 blue (활동 중).
CYCLE_DIGEST_COLOR_ACTIVE: Final[int] = 0x3498DB  # blue
CYCLE_DIGEST_COLOR_IDLE: Final[int] = 0x95A5A6  # gray
# Field value 한 줄 최대 길이 — 너무 길면 truncate (Discord embed field 1024 자
# 제한이 있지만 가독성 위해 더 엄격하게 적용).
CYCLE_DIGEST_FIELD_LINE_LEN: Final[int] = 180

# digest emoji prefix — spec: docs/features/discord-message-style.md §3.
# bot.py 단순화본은 digest 만 사용. 나머지 6종 (reply/cycle-start/cycle-end/
# alert/recovery/decision) 은 외부 import 없음 확인 후 #819 에서 제거.
MESSAGE_PREFIX: Final[dict[str, str]] = {
    "digest": "📊",
}

# Discord mention 토큰 차단 패턴 — digest 안 PR title 에 `@everyone` 등이
# 들어가도 실제 mention 알림이 발생하지 않도록 zero-width space 삽입.
MENTION_SANITIZE_PATTERNS: Final[tuple[tuple[re.Pattern[str], str], ...]] = (
    (re.compile(r"@everyone"), "@​everyone"),
    (re.compile(r"@here"), "@​here"),
    (re.compile(r"<@(?=[!&]?\d)"), "<​@"),
)

SENTINEL_PREFIX: Final[str] = "/system:"
SENTINEL_KEYS: Final[dict[str, list[str]]] = {
    "ctrl-c": ["C-c"],
    "ctrl-d": ["C-d"],
    "enter": ["Enter"],
    "esc": ["Escape"],
}

# context auto-clear (spec: docs/features/context-auto-clear.md §5).
# PR #807 단순화에서 누락된 loop 를 #809 에서 복구. opt-in (default off — dry-run).
# #855 multi-pane 확장: nmae(mobruji:0.0) + helper(helper:0.0) 둘 다 polling.
CONTEXT_AUTO_CLEAR_DEFAULT_ENABLED: Final[str] = "0"
CONTEXT_AUTO_CLEAR_DEFAULT_TRIGGER_PCT: Final[int] = 95
CONTEXT_AUTO_CLEAR_DEFAULT_HYSTERESIS_PCT: Final[int] = 80
CONTEXT_AUTO_CLEAR_POLL_INTERVAL_SECONDS: Final[int] = 5
# 복수 pane CSV. plural (`TMUX_PANE_TARGETS`) 우선, singular (`TMUX_PANE_TARGET`)
# 후방호환. 둘 다 부재 시 아래 default.
CONTEXT_AUTO_CLEAR_DEFAULT_PANES: Final[str] = "mobruji:0.0,helper:0.0"
CONTEXT_AUTO_CLEAR_DEFAULT_PANE: Final[str] = "mobruji:0.0"  # legacy 단일 default (env 후방호환용)
CONTEXT_AUTO_CLEAR_CAPTURE_LINES: Final[int] = 2000
CLEAR_READY_MARKER: Final[str] = "===CLEAR_READY==="
CONTEXT_PCT_PATTERN: Final[re.Pattern[str]] = re.compile(r"===CTX:(\d{1,3})%===")
AUTOCLEAR_PAUSED_FLAG: Final[Path] = Path("~/.mobruji/autoclear-paused").expanduser()
CONTEXT_CLEAR_LOG_PATH: Final[Path] = Path(
    "~/.claude/projects/-home-mobruji-mobruji/memory/project_context_clear_log.md"
).expanduser()
CONTEXT_CLEAR_LOG_HEADER: Final[str] = (
    "---\n"
    "name: project-context-clear-log\n"
    "description: maestro/helper context auto-clear 사이클 결과 누적 (trigger / marker / clear)\n"
    "metadata:\n"
    "  type: project\n"
    "---\n"
    "\n"
    "# context auto-clear log (역시간순)\n"
    "\n"
    "| timestamp | pane | event | context% | handoff file | duration |\n"
    "|---|---|---|---|---|---|\n"
)
CONTEXT_CLEANUP_PROMPT: Final[str] = (
    "🧠 컨텍스트 95% 도달. 다음 절차로 자율 정리하라: "
    "1) 진행 중 작업 모두 완료 대기 (sub-agent launch / 새 요청 추가 금지) "
    "2) 다음 핸드오프 메모리 project_session_handoff_<YYYY-MM-DD>_v<N+1>.md 작성 "
    "(사이클 카운트 / 진행 중 PR / 사용자 결정 대기 / 첫 액션) "
    "3) MEMORY.md 인덱스에 새 핸드오프 한 줄 추가 "
    "4) 정리 완료 후 stdout 에 marker 출력: ===CLEAR_READY=== "
    "5) 그 후 정지 (ScheduleWakeup 재호출 안 함 — /clear 후 다음 wake 가 처리)"
)

# nmae 사이클 watchdog (spec: docs/features/nmae-cycle-watchdog.md).
# 메모리 [[feedback-keep-4-cycles-active]] nmae 자기 점검 반복 누락 →
# 외부 데몬 안전망. 5분 polling cycle-status.json, idle 워크트리 발견 시
# 자동 nmae tmux inject + Discord push.
CYCLE_IDLE_WATCH_DEFAULT_ENABLED: Final[str] = "1"
CYCLE_IDLE_THRESHOLD_DEFAULT_MINUTES: Final[int] = 10
CYCLE_IDLE_WATCH_DEFAULT_INTERVAL_SECONDS: Final[int] = 300  # 5분
CYCLE_IDLE_WATCH_DEFAULT_INJECT_TARGET: Final[str] = "mobruji:0.0"
CYCLE_IDLE_WATCH_DEFAULT_WORKSPACES: Final[str] = "be,fe,rev,plan"
# 동일 워크트리 재알림 debounce — spam 방지.
CYCLE_IDLE_WATCH_DEBOUNCE_SECONDS: Final[int] = 15 * 60  # 15분
# tmux inject prompt 템플릿. {date}/{workspaces}/{summary} 치환.
CYCLE_IDLE_WATCH_INJECT_TEMPLATE: Final[str] = (
    "[watchdog {date}] cycle-status.json idle 발견 — {workspaces}. "
    "{summary}. keep-4-cycles 룰 위반. "
    "즉시 다음 백로그 launch 또는 root cause 보고."
)

logging.basicConfig(level=logging.INFO, format=LOG_FORMAT, stream=sys.stdout)
logger = logging.getLogger("mobruji-discord-daemon")


def load_env() -> dict[str, str]:
    """필수 환경변수를 로드합니다. 누락 시 즉시 종료합니다.

    단순화본 (이슈 #807): repository_dispatch 경로를 폐기했으므로
    `GITHUB_PAT` / `GITHUB_REPO` 는 필수가 아닙니다.
    """
    load_dotenv(Path(__file__).resolve().parent / ".env")

    required = (
        "DISCORD_BOT_TOKEN",
        "ALLOWED_USER_IDS",
        "MOBRUJI_CHANNEL_ID",
    )
    missing = [key for key in required if not os.environ.get(key)]
    if missing:
        logger.error("필수 환경변수 누락: %s", ", ".join(missing))
        sys.exit(1)

    env: dict[str, str] = {key: os.environ[key] for key in required}
    env["TMUX_SESSION_NAME"] = os.environ.get("TMUX_SESSION_NAME", "helper")
    env["TMUX_TARGET_PANE"] = os.environ.get("TMUX_TARGET_PANE", "helper:0.0")
    env["CLAUDE_BIN"] = os.environ.get("CLAUDE_BIN", "claude")
    env["DEDUP_LEDGER_PATH"] = os.path.expanduser(
        os.environ.get("DEDUP_LEDGER_PATH", "~/.mobruji/discord-bridge.sqlite")
    )
    env["DIGEST_ENABLED"] = os.environ.get("DIGEST_ENABLED", "1")
    env["NOTIFY_CHANNEL_ID"] = os.environ.get(
        "NOTIFY_CHANNEL_ID", env["MOBRUJI_CHANNEL_ID"]
    )
    env["CONTEXT_AUTO_CLEAR_ENABLED"] = os.environ.get(
        "CONTEXT_AUTO_CLEAR_ENABLED", CONTEXT_AUTO_CLEAR_DEFAULT_ENABLED
    )
    env["CONTEXT_CLEAR_TRIGGER_PCT"] = os.environ.get(
        "CONTEXT_CLEAR_TRIGGER_PCT", str(CONTEXT_AUTO_CLEAR_DEFAULT_TRIGGER_PCT)
    )
    env["CONTEXT_CLEAR_HYSTERESIS_PCT"] = os.environ.get(
        "CONTEXT_CLEAR_HYSTERESIS_PCT", str(CONTEXT_AUTO_CLEAR_DEFAULT_HYSTERESIS_PCT)
    )
    # multi-pane: TMUX_PANE_TARGETS (plural, CSV) 우선. 부재 시 TMUX_PANE_TARGET
    # (singular) 후방호환. 둘 다 없으면 CONTEXT_AUTO_CLEAR_DEFAULT_PANES.
    plural_value = os.environ.get("TMUX_PANE_TARGETS")
    singular_value = os.environ.get("TMUX_PANE_TARGET")
    if plural_value is not None:
        env["TMUX_PANE_TARGETS"] = plural_value
    elif singular_value is not None:
        env["TMUX_PANE_TARGETS"] = singular_value
    else:
        env["TMUX_PANE_TARGETS"] = CONTEXT_AUTO_CLEAR_DEFAULT_PANES
    # singular 도 별도 키로 보존 — 디버깅/legacy import 용.
    env["TMUX_PANE_TARGET"] = singular_value or CONTEXT_AUTO_CLEAR_DEFAULT_PANE
    env["CYCLE_STATUS_PATH"] = os.path.expanduser(
        os.environ.get("CYCLE_STATUS_PATH", DEFAULT_CYCLE_STATUS_PATH)
    )
    env["BOT_AUTO_ACK"] = os.environ.get("BOT_AUTO_ACK", BOT_AUTO_ACK_DEFAULT_ENABLED)
    # nmae cycle watchdog (#941, spec: docs/features/nmae-cycle-watchdog.md)
    env["CYCLE_IDLE_WATCH"] = os.environ.get(
        "CYCLE_IDLE_WATCH", CYCLE_IDLE_WATCH_DEFAULT_ENABLED
    )
    env["CYCLE_IDLE_THRESHOLD_MINUTES"] = os.environ.get(
        "CYCLE_IDLE_THRESHOLD_MINUTES", str(CYCLE_IDLE_THRESHOLD_DEFAULT_MINUTES)
    )
    env["CYCLE_IDLE_WATCH_INTERVAL_SECONDS"] = os.environ.get(
        "CYCLE_IDLE_WATCH_INTERVAL_SECONDS",
        str(CYCLE_IDLE_WATCH_DEFAULT_INTERVAL_SECONDS),
    )
    env["CYCLE_INJECT_TARGET"] = os.environ.get(
        "CYCLE_INJECT_TARGET", CYCLE_IDLE_WATCH_DEFAULT_INJECT_TARGET
    )
    env["CYCLE_WATCH_WORKSPACES"] = os.environ.get(
        "CYCLE_WATCH_WORKSPACES", CYCLE_IDLE_WATCH_DEFAULT_WORKSPACES
    )
    # CYCLE_NOTIFY_CHANNEL_ID 부재 시 NOTIFY_CHANNEL_ID fallback.
    env["CYCLE_NOTIFY_CHANNEL_ID"] = os.environ.get(
        "CYCLE_NOTIFY_CHANNEL_ID", env["NOTIFY_CHANNEL_ID"]
    )
    return env


def parse_allowed_user_ids(raw: str) -> set[int]:
    """CSV user id 목록을 정수 집합으로 변환합니다."""
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


def truncate_for_log(text: str, max_len: int = MAX_TEXT_PREVIEW_LEN) -> str:
    """로그/inbox 에 메시지를 남길 때 너무 길지 않도록 자릅니다.

    Args:
        text: 원문.
        max_len: 자르기 기준 길이. 기본값은 로그 미리보기용 :data:`MAX_TEXT_PREVIEW_LEN`.
            inbox.jsonl append 시 (#909 F-1) :data:`INBOX_TEXT_MAX_LEN` 를 명시적으로
            전달하여 더 긴 본문도 capped 형태로 저장합니다.
    """
    if len(text) <= max_len:
        return text
    return text[:max_len] + "…"


def _ensure_inbox_secure() -> None:
    """inbox.jsonl 디렉토리 보장 + 0o600 권한 강제 (#909 F-1).

    - 부모 디렉토리가 없으면 생성.
    - 파일이 없으면 0o600 mode 로 touch.
    - 파일이 이미 있더라도 매 호출마다 chmod 600 으로 보정 (운영 중 권한 수정 방어).

    chmod / touch 실패는 warning 만 남기고 raise 하지 않습니다 — 백업/디버깅용
    파일이라 본 메시지 처리(send-keys) 흐름을 막아서는 안 됩니다.
    """
    try:
        INBOX_PATH.parent.mkdir(parents=True, exist_ok=True)
        if not INBOX_PATH.exists():
            INBOX_PATH.touch(mode=INBOX_FILE_MODE, exist_ok=True)
        os.chmod(INBOX_PATH, INBOX_FILE_MODE)
    except OSError as exc:
        logger.warning("inbox.jsonl 보안 준비 실패: %s", exc)


def append_inbox(payload: dict[str, str]) -> None:
    """inbox.jsonl 에 한 줄 JSON 으로 append 합니다 (백업/디버깅용).

    #909 F-1: PII 평문 노출 완화.
      - 매 호출마다 부모 디렉토리 + 0o600 권한 보장.
      - ``text`` 필드를 :data:`INBOX_TEXT_MAX_LEN` (500자) 로 truncate 저장.
        나머지 필드(author/ts/message_id 등) 는 ID 류라 그대로 보존.
    """
    _ensure_inbox_secure()
    truncated_payload = dict(payload)
    if isinstance(payload.get("text"), str):
        truncated_payload["text"] = truncate_for_log(
            payload["text"], max_len=INBOX_TEXT_MAX_LEN
        )
    try:
        with INBOX_PATH.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(truncated_payload, ensure_ascii=False) + "\n")
    except OSError as exc:
        logger.warning("inbox.jsonl write 실패: %s", exc)


def write_last_user_msg_id(message_id: str) -> None:
    """`~/.mobruji/last-user-msg-id.txt` 에 최신 사용자 message_id 를 atomic write (#946).

    discord-reply.sh bare body (본답) 모드가 이 파일을 읽어 Discord REST API
    payload 에 `message_reference: {message_id, channel_id, fail_if_not_exists: false}`
    를 포함시켜 자동으로 사용자 메시지에 reply 형태로 push.

    동작:
      - 부모 디렉토리 부재 시 생성.
      - mktemp + os.replace 로 same-fs atomic rename — 동시 on_message 가
        부분 파일을 읽지 않도록 보장 (POSIX rename(2) atomicity).
      - 파일 권한 0o600 강제 — message_id 자체는 민감 정보 아니지만 PII
        디렉토리 일관성 유지 (helper-queue.jsonl 등과 동일).
      - 실패는 warning 만 — reply 표시 누락은 quality-of-life 저하일 뿐
        본 forwarding 흐름을 막아서는 안 됨.

    Args:
        message_id: Discord message snowflake (정수 또는 문자열). 항상 str
            로 받아 그대로 저장.
    """
    import tempfile as _tempfile  # local — module top scope 변경 회피.
    try:
        LAST_USER_MSG_ID_PATH.parent.mkdir(parents=True, exist_ok=True)
        tmp_fd, tmp_path = _tempfile.mkstemp(
            prefix=".last-user-msg-id-",
            dir=str(LAST_USER_MSG_ID_PATH.parent),
        )
        try:
            with os.fdopen(tmp_fd, "w", encoding="utf-8") as handle:
                handle.write(message_id)
            os.chmod(tmp_path, LAST_USER_MSG_ID_FILE_MODE)
            os.replace(tmp_path, LAST_USER_MSG_ID_PATH)
        except OSError:
            if tmp_path and os.path.exists(tmp_path):
                try:
                    os.unlink(tmp_path)
                except OSError:
                    pass
            raise
    except OSError as exc:
        logger.warning("last-user-msg-id 기록 실패: %s", exc)


def build_reply_context_prefix(
    referenced_content: str | None,
    user_body: str,
    *,
    preview_len: int = REPLY_CONTEXT_PREVIEW_LEN,
) -> str:
    """사용자 Discord "답장" 메시지에 reference 본문 요약 prefix 를 붙입니다 (#880).

    Discord 의 reply 기능은 `Message.reference.message_id` + 서버에서 hydrate 한
    `Message.referenced_message` 객체로 제공됩니다. 이 함수는 referenced_message
    가 있을 때만 `[답장→ <preview>] <user 본문>` 형태로 helper 에게 전달할
    문자열을 만듭니다.

    Args:
        referenced_content: ``Message.referenced_message.content`` 원문, 또는
            None / 빈 문자열 (답장이 아닐 때).
        user_body: 사용자가 새로 작성한 메시지 본문.
        preview_len: 원문 미리보기 글자 수. 줄바꿈은 공백으로 치환.

    Returns:
        - referenced_content 가 None / 빈 문자열 → ``user_body`` 그대로
          (기존 호환).
        - 그 외 → ``[답장→ <preview>] <user 본문>``.
    """
    if not referenced_content:
        return user_body
    # 멀티라인 reference 는 한 줄로 만들고 N자 컷.
    flattened = " ".join(referenced_content.split())
    if not flattened:
        return user_body
    if len(flattened) > preview_len:
        preview = flattened[: preview_len - 1] + "…"
    else:
        preview = flattened
    return REPLY_CONTEXT_PREFIX_TEMPLATE.format(preview=preview, body=user_body)


def sanitize_mentions(text: str) -> str:
    """Discord mention 토큰을 zero-width space 로 무력화합니다.

    digest 안 PR title / 진행 메시지에 `@everyone` / `@here` / `<@USER_ID>` 가
    들어가도 실제 알림이 발생하지 않도록 prefix 직후에 U+200B 삽입.
    """
    sanitized = text
    for pattern, replacement in MENTION_SANITIZE_PATTERNS:
        sanitized = pattern.sub(replacement, sanitized)
    return sanitized


class DedupLedger:
    """SQLite 기반 dedup ledger — 사용자 메시지 중복 처리 방지.

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

    def claim(self, message_id: str, now_epoch: int | None = None) -> bool:
        """is_processed + mark_processed 를 단일 SQLite 트랜잭션으로 원자화 (#909 F-3).

        Discord Gateway reconnect / on_message 콜백 동시성 race 방지:
        ``INSERT OR IGNORE`` 후 ``rowcount`` 검사로 신규 claim 여부 판단.

        Returns:
            True  — 신규 claim 성공 (호출자가 처리 진행).
            False — 이미 처리된 message_id (호출자가 즉시 return).
        """
        ts = int(time.time()) if now_epoch is None else now_epoch
        with self._lock:
            cursor = self._conn.execute(
                "INSERT OR IGNORE INTO processed_messages (message_id, processed_at) VALUES (?, ?)",
                (message_id, ts),
            )
            self._conn.commit()
            return cursor.rowcount > 0

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
    """백그라운드에서 주기적으로 dedup ledger GC 를 수행합니다."""

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
    """tmux new-session -d 로 helper 세션을 만듭니다."""
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


def resolve_sentinel(text: str) -> list[str] | None:
    """`/system:<key>` 형태 sentinel 을 tmux send-keys 인자 리스트로 변환합니다.

    Q5 답(c): 명시 sentinel 만 종료/제어 입력으로 받습니다. `/exit` 같은
    일반 슬래시 명령은 helper Claude TUI 에 그대로 전달합니다.
    """
    if not text.startswith(SENTINEL_PREFIX):
        return None
    key = text[len(SENTINEL_PREFIX):].strip().lower()
    return SENTINEL_KEYS.get(key)


def tmux_send_payload(target_pane: str, text: str) -> bool:
    """일반 텍스트는 `-l` (literal) 로 보낸 뒤 Enter 키를 누릅니다.

    sentinel(/system:ctrl-c 등) 은 컨트롤 키 인자로 직접 전송합니다.
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


# ─────────────────────────────────────────────────────────────────────────────
# cycle-status digest (사용자 룰 2026-05-23)
# ─────────────────────────────────────────────────────────────────────────────


def read_cycle_status(path: str = DEFAULT_CYCLE_STATUS_PATH) -> dict | None:
    """`~/.mobruji/cycle-status.json` 을 읽어 dict 로 반환합니다.

    nmae(maestro 본진) 가 매 sub-agent launch/완료/머지 시 실시간 갱신하는
    상태 파일입니다. 파일이 없거나 JSON 파싱이 실패하면 None 을 반환하고,
    호출부 (`format_cycle_digest`) 가 graceful fallback 합니다.

    스키마:
        {
            "be":  {"in_progress": str|dict|null, "last_completed": {...}|null},
            "fe":  {...},
            "rev": {...},
            "plan": {...}
        }
        in_progress (dict 형식, nmae 현행):
            be/fe/plan:
                {"issue": "#NNN", "pr": "#MMM"|null,
                 "title": str, "started_at": ISO8601}
            rev:
                {"target": str, "title": str, "started_at": ISO8601}
            str 형식(legacy)도 계속 지원합니다 — trim 후 그대로 노출.
        last_completed = {"pr": "#NNN"|null, "title": str, "merged_at": ISO8601}
    """
    try:
        with open(path, "r", encoding="utf-8") as handle:
            return json.load(handle)
    except FileNotFoundError:
        logger.warning("cycle-status.json 없음: path=%s", path)
        return None
    except json.JSONDecodeError as exc:
        logger.warning("cycle-status.json JSON 파싱 실패: path=%s err=%s", path, exc)
        return None
    except OSError as exc:
        logger.warning("cycle-status.json 읽기 실패: path=%s err=%s", path, exc)
        return None


def _format_in_progress(raw: object) -> str:
    """`in_progress` 필드를 한 줄 label 로 변환합니다.

    수용 형식:
        - None / 누락 / 빈 문자열 / 미지원 타입 → ``"idle"``
        - ``str`` (legacy) → trim 후 그대로 반환
        - ``dict`` → 우선순위:
            1. ``issue`` 또는 ``pr`` 있으면 ``"<id> <title>"``
               (둘 다 있으면 ``issue`` 우선 — nmae 가 issue 를 1차 식별자로 씀)
            2. ``target`` 있으면 ``"<target>: <title>"`` (rev 워크트리)
            3. ``title`` 만 있으면 ``"<title>"``
            4. 셋 다 없으면 ``"진행 중(스키마 미상)"`` — 이론상 도달 안 함.

    title 누락이거나 str 이 아니면 ``"제목 없음"`` 으로 대체.
    """
    if isinstance(raw, str):
        stripped = raw.strip()
        return stripped if stripped else "idle"
    if isinstance(raw, dict):
        title_raw = raw.get("title")
        title_text = (
            title_raw.strip()
            if isinstance(title_raw, str) and title_raw.strip()
            else "제목 없음"
        )

        def _str_or_none(value: object) -> str | None:
            if isinstance(value, str) and value.strip():
                return value.strip()
            return None

        issue_id = _str_or_none(raw.get("issue")) or _str_or_none(raw.get("pr"))
        if issue_id is not None:
            return f"{issue_id} {title_text}"
        target = _str_or_none(raw.get("target"))
        if target is not None:
            return f"{target}: {title_text}"
        if isinstance(title_raw, str) and title_raw.strip():
            return title_text
        return "진행 중(스키마 미상)"
    return "idle"


def _truncate_field_line(text: str, limit: int = CYCLE_DIGEST_FIELD_LINE_LEN) -> str:
    """Field value 한 줄 길이 가드. limit 초과 시 ``…`` suffix 로 표시."""
    if len(text) > limit:
        return text[: limit - 1] + "…"
    return text


def format_cycle_digest(
    status: dict | None,
    now: datetime | None = None,
    *,
    interval_seconds: int | None = None,
) -> tuple["discord.Embed", str]:
    """4 워크트리(be/fe/rev/plan) digest 를 Discord Embed 로 빌드합니다.

    UX 개선 #840: 기존 plain markdown multiline 텍스트 → Discord native embed.
    스캔 친화적 시각 hierarchy + 워크트리별 emoji prefix + 활동 색상.

    Args:
        status: `read_cycle_status()` 반환 dict, 또는 None (파일 없음/깨짐).
        now: 헤더 timestamp 산출 기준 시각. 기본값 None → 호출 시점 KST.
            테스트 deterministic 용으로만 외부 주입.
        interval_seconds: footer 에 ``interval=Ns`` 명시. None 이면 footer 생략.

    Returns:
        (embed, signature) 튜플.
          - embed: ``discord.Embed`` 인스턴스 (channel.send(embed=...) 로 push).
          - signature: delta 비교용 (시간 무관). 동일 signature 면 heartbeat 만 push.

    Embed 구조:
        title       : ``🔁 Cycle Digest``
        description : ``🕒 YYYY-MM-DD HH:MM KST``
        color       : 모두 idle → gray, 그 외 → blue
        fields      : be/fe/rev/plan 4 개 (inline=False), 각:
                        name  = "🛠 be" (워크트리별 emoji)
                        value = "진행: ...\\n최근: ..." (2 줄)
        footer.text : f"interval={N}s" (interval_seconds 주어진 경우)
        timestamp   : ``now`` (KST). Discord 클라이언트 locale 로 footer 옆에 렌더.

    스키마 누락/타입 이상 시 해당 필드만 "idle" / "없음" 으로 대체합니다.
    cycle-status.json 자체 읽기 실패 시 (status=None) description 에 fallback
    한 줄 추가, signature="unavailable" 반환.
    """
    if now is None:
        now = datetime.now(CYCLE_DIGEST_TZ)
    elif now.tzinfo is not None:
        now = now.astimezone(CYCLE_DIGEST_TZ)
    timestamp_text = f"🕒 {now.strftime(CYCLE_DIGEST_TIME_FORMAT)}"

    embed = discord.Embed(
        title="🔁 Cycle Digest",
        description=timestamp_text,
        color=CYCLE_DIGEST_COLOR_ACTIVE,
    )
    embed.timestamp = now

    if status is None or not isinstance(status, dict):
        embed.description = (
            f"{timestamp_text}\n(cycle-status.json 읽기 실패 — 본진 갱신 대기)"
        )
        embed.color = CYCLE_DIGEST_COLOR_IDLE
        if interval_seconds is not None:
            embed.set_footer(text=f"interval={interval_seconds}s")
        return embed, "unavailable"

    sig_parts: list[str] = []
    any_active = False

    for ws in CYCLE_DIGEST_WORKSPACES:
        entry = status.get(ws)
        if not isinstance(entry, dict):
            in_progress_text = "idle"
            recent_text = "없음"
            sig_parts.append(f"{ws}=missing")
        else:
            in_progress_text = _format_in_progress(entry.get("in_progress"))

            last_completed = entry.get("last_completed")
            if isinstance(last_completed, dict):
                pr_raw = last_completed.get("pr")
                title_raw = last_completed.get("title")
                title_text = (
                    title_raw.strip()
                    if isinstance(title_raw, str) and title_raw.strip()
                    else "제목 없음"
                )
                if isinstance(pr_raw, str) and pr_raw.strip():
                    recent_text = f"{pr_raw.strip()} ({title_text})"
                else:
                    recent_text = title_text
            else:
                recent_text = "없음"
            sig_parts.append(f"{ws}={in_progress_text}|{recent_text}")

        if in_progress_text != "idle":
            any_active = True

        in_progress_text = sanitize_mentions(in_progress_text)
        recent_text = sanitize_mentions(recent_text)

        in_progress_text = _truncate_field_line(in_progress_text)
        recent_text = _truncate_field_line(recent_text)

        emoji = CYCLE_DIGEST_WORKSPACE_EMOJI.get(ws, "")
        field_name = f"{emoji} {ws}".strip() if emoji else ws
        field_value = f"진행: {in_progress_text}\n최근: {recent_text}"
        embed.add_field(name=field_name, value=field_value, inline=False)

    embed.color = CYCLE_DIGEST_COLOR_ACTIVE if any_active else CYCLE_DIGEST_COLOR_IDLE
    if interval_seconds is not None:
        embed.set_footer(text=f"interval={interval_seconds}s")

    signature = "||".join(sig_parts)
    return embed, signature


def resolve_digest_interval(env_value: str | None) -> int:
    """DIGEST_INTERVAL_SECONDS env 값을 정수로 해석합니다. 부재/이상값이면 default."""
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


async def send_with_retry(
    channel: "discord.abc.Messageable",
    *,
    content: str | None = None,
    embed: "discord.Embed | None" = None,
    max_attempts: int = DISCORD_SEND_RETRY_MAX,
    base_sleep: float = DISCORD_SEND_RETRY_BASE_SEC,
    sleeper=asyncio.sleep,
) -> bool:
    """Discord ``channel.send`` 호출 + 429/5xx 재시도 (#911 G-6).

    - 429: ``HTTPException.retry_after`` (discord.py 가 응답에서 추출) 가 있으면
      그 초만큼 sleep, 없으면 ``base_sleep`` 사용.
    - 5xx: exponential backoff (``base_sleep * 2 ** (i-1)``).
    - 4xx 등 retry 불가 상태: 즉시 False 반환 + warning log.
    - ``max_attempts`` 회 시도 후에도 실패하면 False.

    호출부는 성공 여부만 알면 충분하므로 bool 반환. (raise 하지 않음 — 호출부는
    이미 broad ``except`` 안에서 호출되며, retry 후에도 실패하면 다음 iter 에서
    자연 회복하길 기대.)

    sleeper 인자는 테스트 용 — 실제 sleep 없이 path 만 검증할 때 stub.
    """
    last_exc: Exception | None = None
    for attempt in range(1, max_attempts + 1):
        try:
            if embed is not None:
                await channel.send(content=content, embed=embed)
            else:
                # content is required when no embed; assume caller guarantees this.
                await channel.send(content)
            return True
        except discord.HTTPException as exc:
            last_exc = exc
            status = getattr(exc, "status", None)
            if status == 429:
                retry_after = getattr(exc, "retry_after", None)
                sleep_sec = (
                    float(retry_after)
                    if retry_after is not None
                    else base_sleep
                )
                logger.warning(
                    "send_with_retry: 429 rate limit — retry %d/%d after %.2fs",
                    attempt,
                    max_attempts,
                    sleep_sec,
                )
            elif status is not None and 500 <= status < 600:
                sleep_sec = base_sleep * (2 ** (attempt - 1))
                logger.warning(
                    "send_with_retry: %d server error — retry %d/%d after %.2fs",
                    status,
                    attempt,
                    max_attempts,
                    sleep_sec,
                )
            else:
                # 4xx etc — retry 무의미.
                logger.warning(
                    "send_with_retry: non-retryable HTTPException status=%s err=%s",
                    status,
                    exc,
                )
                return False
        except (asyncio.CancelledError, KeyboardInterrupt):
            raise
        except Exception as exc:  # noqa: BLE001
            # 네트워크 layer 예외 (ConnectionError, TimeoutError 등) — backoff retry.
            last_exc = exc
            sleep_sec = base_sleep * (2 ** (attempt - 1))
            logger.warning(
                "send_with_retry: transient error — retry %d/%d after %.2fs err=%s",
                attempt,
                max_attempts,
                sleep_sec,
                exc,
            )
        # 마지막 attempt 직후엔 sleep 안 함 — 의미 없음 (어차피 더 시도 안 함).
        if attempt < max_attempts:
            await sleeper(sleep_sec)
    logger.warning(
        "send_with_retry: %d 회 시도 후 실패 last_err=%s",
        max_attempts,
        last_exc,
    )
    return False


async def digest_loop(
    client: "discord.Client",
    channel_id: int,
    *,
    interval: int = DEFAULT_DIGEST_INTERVAL_SECONDS,
    initial_delay: int = DIGEST_INITIAL_DELAY_SECONDS,
    heartbeat_seconds: int = DIGEST_HEARTBEAT_SECONDS,
    time_source=time.monotonic,
    cycle_status_path: str = DEFAULT_CYCLE_STATUS_PATH,
) -> None:
    """on_ready 직후 launch. interval 초 마다 cycle-status digest 를 push 합니다.

    사용자 룰 (2026-05-23 #모부르지): nmae 가 실시간 갱신하는
    `~/.mobruji/cycle-status.json` 의 4 워크트리(be/fe/rev/plan) 진행/최근을
    Discord embed (UX 개선 #840) 로 push 합니다.

    - 직전 push 와 signature(시간 제외) 가 동일하면 noise 라고 보고 skip.
    - signature 가 바뀌면 즉시 push (= delta push).
    - 동일해도 마지막 push 로부터 heartbeat_seconds 경과 시 한 번 push (생존 신호).
    - 첫 iter 는 last signature 가 없으므로 무조건 push (초기 baseline).

    bot 종료 시 cancel 됩니다. asyncio.CancelledError 는 외부로 전파.
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
                status = read_cycle_status(cycle_status_path)
                embed, signature = format_cycle_digest(
                    status, interval_seconds=interval
                )
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
                    # #911 G-6: 429/5xx retry. 실패 시 last_signature 갱신 안 함
                    # → 다음 iter 에서 동일 signature 로 재시도 (delta 유지).
                    sent = await send_with_retry(channel, embed=embed)
                    if sent:
                        last_signature = signature
                        last_pushed_at = now_ts
                        logger.info("digest push: reason=%s signature=%s", reason, signature)
                    else:
                        logger.warning(
                            "digest push 실패 (retry 소진) reason=%s signature=%s — 다음 iter 에 재시도",
                            reason,
                            signature,
                        )
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


# ─────────────────────────────────────────────────────────────────────────────
# context auto-clear (spec: docs/features/context-auto-clear.md §5)
# ─────────────────────────────────────────────────────────────────────────────


def parse_context_pct(pane_text: str) -> int | None:
    """`===CTX:NN%===` marker 의 마지막 occurrence 를 정수로 파싱합니다.

    spec §5-6 옵션 A 확정: maestro 가 매 turn 끝에 self-emit. footer scrape
    아닌 단일 regex `r'===CTX:(\\d{1,3})%==='`. capture-pane 출력에 여러 turn
    이력이 누적되므로 **마지막** 매치를 사용합니다 (= 최신 turn). 매치 없거나
    숫자가 0~100 범위를 벗어나면 None.
    """
    if not pane_text:
        return None
    matches = CONTEXT_PCT_PATTERN.findall(pane_text)
    if not matches:
        return None
    try:
        pct = int(matches[-1])
    except (TypeError, ValueError):
        return None
    if pct < 0 or pct > 100:
        return None
    return pct


def capture_pane_text(pane_target: str) -> str | None:
    """`tmux capture-pane -t <pane> -p -S -<N>` 결과를 문자열로 반환합니다.

    실패 시 (세션 없음/명령 오류) None. CONTEXT_AUTO_CLEAR_CAPTURE_LINES 만큼
    스크롤백을 함께 가져와 marker 가 짧은 polling 사이에 화면 위로 밀려도
    감지되도록 합니다.
    """
    cmd = [
        "tmux",
        "capture-pane",
        "-t",
        pane_target,
        "-p",
        "-S",
        f"-{CONTEXT_AUTO_CLEAR_CAPTURE_LINES}",
    ]
    try:
        result = subprocess.run(cmd, check=False, capture_output=True, text=True)
    except OSError as exc:
        logger.warning("tmux capture-pane OSError: %s", exc)
        return None
    if result.returncode != 0:
        logger.warning(
            "tmux capture-pane 실패: rc=%d stderr=%s",
            result.returncode,
            truncate_for_log(result.stderr or ""),
        )
        return None
    return result.stdout or ""


def inject_cleanup_prompt(pane_target: str) -> bool:
    """tmux send-keys 로 정리 prompt 한 줄 inject + Enter."""
    return tmux_send_payload(pane_target, CONTEXT_CLEANUP_PROMPT)


def send_clear_command(pane_target: str) -> bool:
    """tmux send-keys '/clear' Enter — Claude TUI 컨텍스트 비우기 슬래시."""
    return tmux_send_payload(pane_target, "/clear")


def clear_pane_history(pane_target: str) -> bool:
    """`tmux clear-history -t <pane>` — 스크롤백 cleanup (G-1, #910 묶음 B).

    `/clear` 직후 호출해 직전 turn 의 `===CLEAR_READY===` marker 가 다음
    polling iter 의 `capture-pane -S -N` 결과에 잔존해서 false detect → 즉시
    `/clear` 재전송 → sub-agent 작업 손실 시나리오를 차단한다.

    실패해도 caller 흐름을 막지 않는다 (warning log 만 남기고 False 반환).
    """
    cmd = ["tmux", "clear-history", "-t", pane_target]
    try:
        result = subprocess.run(cmd, check=False, capture_output=True, text=True)
    except OSError as exc:
        logger.warning("tmux clear-history OSError: pane=%s err=%s", pane_target, exc)
        return False
    if result.returncode != 0:
        logger.warning(
            "tmux clear-history 실패: pane=%s rc=%d stderr=%s",
            pane_target,
            result.returncode,
            truncate_for_log(result.stderr or ""),
        )
        return False
    return True


def append_clear_log(pct: int, event: str, pane: str = "-") -> None:
    """`project_context_clear_log.md` 에 한 줄 append. 파일 없으면 header 생성.

    spec §5-3 포맷 (#855 갱신): `| timestamp | pane | event | context% | handoff file | duration |`.
    handoff file 과 duration 은 자동 채울 정보가 없으므로 `-` 로 비워둡니다.
    pane 인자 부재 시 (legacy 호출) `-` 로 표기.
    """
    timestamp = datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")
    row = f"| {timestamp} | {pane} | {event} | {pct} | - | - |\n"
    try:
        CONTEXT_CLEAR_LOG_PATH.parent.mkdir(parents=True, exist_ok=True)
        if not CONTEXT_CLEAR_LOG_PATH.exists():
            with CONTEXT_CLEAR_LOG_PATH.open("w", encoding="utf-8") as handle:
                handle.write(CONTEXT_CLEAR_LOG_HEADER)
        with CONTEXT_CLEAR_LOG_PATH.open("a", encoding="utf-8") as handle:
            handle.write(row)
    except OSError as exc:
        logger.warning("context clear log append 실패: %s", exc)


def resolve_pane_targets(raw: str | None) -> list[str]:
    """`TMUX_PANE_TARGETS` (또는 singular `TMUX_PANE_TARGET`) CSV 를 list 로 변환.

    빈 문자열 토큰 / 공백 trim. raw 가 None 이거나 비어 있으면
    `CONTEXT_AUTO_CLEAR_DEFAULT_PANES` 를 fallback. 중복 제거 (순서 보존).
    """
    if raw is None or not raw.strip():
        raw = CONTEXT_AUTO_CLEAR_DEFAULT_PANES
    seen: set[str] = set()
    result: list[str] = []
    for token in raw.split(","):
        stripped = token.strip()
        if not stripped or stripped in seen:
            continue
        seen.add(stripped)
        result.append(stripped)
    return result


def resolve_context_pct_env(env_value: str | None, default: int) -> int:
    """CONTEXT_CLEAR_TRIGGER_PCT / CONTEXT_CLEAR_HYSTERESIS_PCT 정수 파싱."""
    if env_value is None:
        return default
    try:
        parsed = int(env_value)
    except ValueError:
        logger.warning(
            "context auto-clear pct env 정수 아님(%r) — 기본값 사용: %d",
            env_value,
            default,
        )
        return default
    if parsed < 0 or parsed > 100:
        logger.warning(
            "context auto-clear pct env 범위 이상(%d) — 기본값 사용: %d",
            parsed,
            default,
        )
        return default
    return parsed


async def context_auto_clear_loop(
    client: "discord.Client",
    channel_id: int,
    *,
    pane_targets: list[str] | tuple[str, ...] | None = None,
    pane_target: str | None = None,  # legacy 단일 인자 (#810 호환).
    trigger_pct: int = CONTEXT_AUTO_CLEAR_DEFAULT_TRIGGER_PCT,
    hysteresis_pct: int = CONTEXT_AUTO_CLEAR_DEFAULT_HYSTERESIS_PCT,
    poll_interval: int = CONTEXT_AUTO_CLEAR_POLL_INTERVAL_SECONDS,
) -> None:
    """pane 별 독립 state polling. trigger_pct 도달 → 정리 prompt inject, marker → /clear.

    #855: 다중 pane (nmae mobruji:0.0 + helper helper:0.0) polling. 각 pane 마다
    debounced / awaiting_marker 를 독립 추적해 한 pane 의 트리거가 다른 pane 에
    영향을 주지 않는다. pane 별 `tmux has-session` 확인 → missing pane 은 매 iter
    silently skip (helper 세션 없는 환경에서 crash 금지).

    spec §5-2 본문 흐름 (pane 별 반복):
      1. AUTOCLEAR_PAUSED_FLAG 존재하면 전체 polling skip (사용자 수동 중단).
      2. 각 pane 에 대해:
         a. tmux has-session 실패 → 해당 pane 만 skip.
         b. tmux capture-pane → parse_context_pct 로 최신 marker 추출.
         c. awaiting_marker(pane) == True 이고 CLEAR_READY_MARKER 가 보이면:
            Discord push → send_clear_command → log append → awaiting_marker 해제.
         d. debounced(pane) == True 이고 pct ≤ hysteresis_pct 면 debounce 해제.
         e. debounced(pane) == False 이고 pct ≥ trigger_pct 면:
            Discord push → inject_cleanup_prompt → log append → debounced/awaiting_marker SET.
      3. marker 도착까지 timeout 없음 — 진행 중 작업 보호 (spec §3 안전성).

    pane_target (singular) 가 지정되면 [pane_target] 단일 리스트로 처리 — legacy
    호출(#809/#810 테스트 호환) 보존. 둘 다 None 이면 default panes 사용.

    asyncio.CancelledError 는 외부로 전파해 bot 종료 시 깔끔히 정리되도록.
    """
    if pane_targets is None:
        if pane_target is not None:
            pane_targets = [pane_target]
        else:
            pane_targets = resolve_pane_targets(None)
    panes = list(pane_targets)
    state: dict[str, dict[str, bool]] = {
        p: {"debounced": False, "awaiting_marker": False} for p in panes
    }
    missing_warned: set[str] = set()
    while True:
        try:
            await asyncio.sleep(poll_interval)
            if AUTOCLEAR_PAUSED_FLAG.exists():
                continue
            for pane in panes:
                session = pane.split(":", 1)[0]
                if not tmux_has_session(session):
                    if pane not in missing_warned:
                        logger.warning(
                            "context auto-clear: pane=%s 세션 없음 — skip",
                            pane,
                        )
                        missing_warned.add(pane)
                    continue
                # 세션이 재생성되면 다시 warn 가능하도록 살아있을 땐 set 에서 제거.
                missing_warned.discard(pane)

                pane_text = capture_pane_text(pane)
                if pane_text is None:
                    continue
                pct = parse_context_pct(pane_text)
                st = state[pane]
                # marker 우선 — pct 가 None 이어도 정리 완료 신호는 살린다.
                if st["awaiting_marker"] and CLEAR_READY_MARKER in pane_text:
                    last_pct_text = "?" if pct is None else f"{pct}%"
                    channel = client.get_channel(channel_id)
                    if channel is not None:
                        # #911 G-6: 429/5xx retry.
                        await send_with_retry(
                            channel,
                            content=(
                                f"🧹 {pane} 정리 완료 → /clear 전송 "
                                f"(마지막 context {last_pct_text})"
                            ),
                        )
                    else:
                        logger.warning(
                            "context auto-clear: channel_id=%s 없음 — pane=%s marker push skip",
                            channel_id,
                            pane,
                        )
                    send_clear_command(pane)
                    # G-1 (#910): /clear 직후 스크롤백 cleanup. 다음 iter capture-pane
                    # 결과에서 직전 turn 의 CLEAR_READY marker 잔존을 차단.
                    clear_pane_history(pane)
                    append_clear_log(
                        pct if pct is not None else -1, "cleared", pane=pane
                    )
                    logger.info(
                        "context auto-clear: pane=%s cleared=%s",
                        pane,
                        last_pct_text,
                    )
                    st["awaiting_marker"] = False
                    # debounce 는 80% 이하 자연 falloff 까지 유지.
                    continue
                if pct is None:
                    continue
                if st["debounced"] and pct <= hysteresis_pct:
                    st["debounced"] = False
                if not st["debounced"] and pct >= trigger_pct:
                    channel = client.get_channel(channel_id)
                    if channel is not None:
                        # #911 G-6: 429/5xx retry.
                        await send_with_retry(
                            channel,
                            content=f"🧠 {pane} context {pct}% → 자율 정리 시작",
                        )
                    else:
                        logger.warning(
                            "context auto-clear: channel_id=%s 없음 — pane=%s trigger push skip",
                            channel_id,
                            pane,
                        )
                    inject_cleanup_prompt(pane)
                    append_clear_log(pct, "triggered", pane=pane)
                    logger.info(
                        "context auto-clear: pane=%s trigger=%d%%",
                        pane,
                        pct,
                    )
                    st["debounced"] = True
                    st["awaiting_marker"] = True
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001
            logger.warning("context_auto_clear_loop iter 실패: %s", exc)


# ─────────────────────────────────────────────────────────────────────────────
# nmae cycle watchdog (spec: docs/features/nmae-cycle-watchdog.md)
# ─────────────────────────────────────────────────────────────────────────────


def resolve_cycle_targets(raw: str | None) -> list[str]:
    """`CYCLE_WATCH_WORKSPACES` CSV (또는 default `be,fe,rev,plan`) 을 list 로 반환.

    공백 trim. 빈 토큰 무시. 중복 제거 (순서 보존). None / 빈 문자열 → default.
    """
    if raw is None or not raw.strip():
        raw = CYCLE_IDLE_WATCH_DEFAULT_WORKSPACES
    seen: set[str] = set()
    result: list[str] = []
    for token in raw.split(","):
        stripped = token.strip()
        if not stripped or stripped in seen:
            continue
        seen.add(stripped)
        result.append(stripped)
    return result


def parse_cycle_status(path: str) -> dict | None:
    """cycle-status.json 을 dict 로 읽거나, 부재/parse fail/OSError 시 None.

    `read_cycle_status` 와 의미상 동일하지만 watchdog 전용 thin wrapper —
    호출부에서 graceful skip 흐름을 일관되게 가져가기 위해 별도 함수로 둠.
    테스트는 이 함수 직접 검증 (정상 / null / missing / malformed 4건).
    """
    try:
        with open(path, "r", encoding="utf-8") as handle:
            return json.load(handle)
    except FileNotFoundError:
        return None
    except json.JSONDecodeError:
        return None
    except OSError:
        return None


def _parse_iso_datetime(text: object) -> datetime | None:
    """`last_completed.completed_at` 등 ISO8601 문자열을 aware datetime 으로 파싱.

    str 가 아니거나 파싱 실패 → None. ``Z`` suffix 는 ``+00:00`` 로 치환.
    naive datetime 은 UTC 로 간주 (보수적 — naive 면 timezone 모름).
    """
    if not isinstance(text, str) or not text.strip():
        return None
    candidate = text.strip()
    if candidate.endswith("Z"):
        candidate = candidate[:-1] + "+00:00"
    try:
        parsed = datetime.fromisoformat(candidate)
    except ValueError:
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed


def detect_idle_worktrees(
    status: dict | None,
    *,
    threshold_minutes: int,
    now: datetime,
    workspaces: list[str] | tuple[str, ...] = ("be", "fe", "rev", "plan"),
) -> list[dict]:
    """4 워크트리(be/fe/rev/plan) idle 판정 결과 list 반환.

    idle 정의:
        ``in_progress`` 가 None / 누락 / 빈 문자열 AND
        ``last_completed.completed_at`` 이 ``now - threshold_minutes`` 보다
        오래됨 (또는 last_completed 부재 / completed_at 부재 / 파싱 실패).

    Returns:
        idle 워크트리 dict 의 list. 각 dict 키:
            ``workspace`` (str) — be/fe/rev/plan 등
            ``last_completed_title`` (str) — last_completed.title 또는 "없음"
            ``last_completed_at`` (datetime|None) — 파싱된 시각 또는 None
        idle 0건이면 빈 list.

    Args:
        status: cycle-status.json dict 또는 None. None 이면 빈 list (alert 안 함
            — 파일 부재는 별 alert path).
        threshold_minutes: idle 임계 분. 0 이면 모든 비-in-progress 가 idle.
        now: 현재 시각 (aware datetime). 테스트 deterministic 용.
        workspaces: 검사할 워크트리 list.
    """
    if not isinstance(status, dict):
        return []

    cutoff_seconds = threshold_minutes * 60
    if now.tzinfo is None:
        now = now.replace(tzinfo=timezone.utc)

    idle: list[dict] = []
    for ws in workspaces:
        entry = status.get(ws)
        if not isinstance(entry, dict):
            # 워크트리 자체 누락 — idle 로 간주 (cycle-status.json 미초기화).
            idle.append({
                "workspace": ws,
                "last_completed_title": "없음",
                "last_completed_at": None,
            })
            continue
        in_progress_raw = entry.get("in_progress")
        is_active = False
        if isinstance(in_progress_raw, dict) and in_progress_raw:
            is_active = True
        elif isinstance(in_progress_raw, str) and in_progress_raw.strip():
            is_active = True
        if is_active:
            continue

        last_completed = entry.get("last_completed")
        completed_at: datetime | None = None
        title_text = "없음"
        if isinstance(last_completed, dict):
            completed_at = _parse_iso_datetime(last_completed.get("completed_at"))
            title_raw = last_completed.get("title")
            if isinstance(title_raw, str) and title_raw.strip():
                title_text = title_raw.strip()

        if completed_at is None:
            # last_completed 부재 / completed_at 없음 → idle 로 간주.
            idle.append({
                "workspace": ws,
                "last_completed_title": title_text,
                "last_completed_at": None,
            })
            continue

        elapsed = (now - completed_at).total_seconds()
        if elapsed > cutoff_seconds:
            idle.append({
                "workspace": ws,
                "last_completed_title": title_text,
                "last_completed_at": completed_at,
            })
    return idle


def _format_idle_summary(idle: list[dict]) -> str:
    """tmux inject prompt 의 ``{summary}`` 부분 빌드.

    각 워크트리 별 "<ws>: <title 앞 60자>" 콤마 join.
    """
    parts: list[str] = []
    for entry in idle:
        ws = entry.get("workspace", "?")
        title = entry.get("last_completed_title") or "없음"
        if len(title) > 60:
            title = title[:59] + "…"
        parts.append(f"last_completed[{ws}]={title}")
    return " / ".join(parts) if parts else "last_completed: 없음"


def tmux_inject_text(pane_target: str, text: str) -> bool:
    """tmux send-keys 로 한 줄 inject (watchdog 알림).

    `tmux_send_payload` 와 동일한 패턴 (-l literal + Enter). 단, watchdog 알림은
    sentinel 처리 불필요하므로 단순 wrapper.
    """
    return tmux_send_payload(pane_target, text)


async def cycle_idle_watch_loop(
    client: "discord.Client",
    notify_channel_id: int,
    *,
    cycle_status_path: str,
    inject_target: str = CYCLE_IDLE_WATCH_DEFAULT_INJECT_TARGET,
    threshold_minutes: int = CYCLE_IDLE_THRESHOLD_DEFAULT_MINUTES,
    poll_interval: int = CYCLE_IDLE_WATCH_DEFAULT_INTERVAL_SECONDS,
    workspaces: list[str] | tuple[str, ...] = ("be", "fe", "rev", "plan"),
    debounce_seconds: int = CYCLE_IDLE_WATCH_DEBOUNCE_SECONDS,
    time_source=time.monotonic,
    now_provider=lambda: datetime.now(timezone.utc),
) -> None:
    """5분 polling cycle-status.json — idle 워크트리 발견 시 nmae tmux inject + Discord push.

    spec: docs/features/nmae-cycle-watchdog.md.

    동작:
      1. ``poll_interval`` 초 마다 cycle-status.json read.
      2. ``detect_idle_worktrees`` 로 idle list 산출.
      3. debounce 적용 — 워크트리 별 마지막 alert 시각 cache. 같은 워크트리에
         ``debounce_seconds`` 내 재알림 안 함.
      4. debounce 통과한 idle ≥ 1 이면:
         - nmae tmux pane (``inject_target``) 에 `tmux_inject_text` 알림 inject.
         - Discord ``notify_channel_id`` 에 경고 push.
      5. graceful skip — cycle-status.json 부재/parse fail / tmux session 부재 /
         Discord channel 미발견 시 warn 1회 후 skip.
      6. ``threshold_minutes <= 0`` 이면 disabled — 즉시 return (테스트 용).

    asyncio.CancelledError 는 외부로 전파해 bot 종료 시 깔끔히 정리.

    Args:
        time_source: debounce 비교용 monotonic 시각 source. 테스트 stub.
        now_provider: idle 판정용 wall-clock provider (aware datetime).
    """
    if threshold_minutes <= 0:
        logger.info("cycle_idle_watch_loop disabled (threshold_minutes<=0)")
        return

    inject_session = inject_target.split(":", 1)[0]
    last_alert_at: dict[str, float] = {}
    missing_session_warned = False
    missing_channel_warned = False
    missing_status_warned = False

    while True:
        try:
            await asyncio.sleep(poll_interval)
            status = parse_cycle_status(cycle_status_path)
            if status is None:
                if not missing_status_warned:
                    logger.warning(
                        "cycle_idle_watch_loop: cycle-status.json 읽기 실패 — skip (path=%s)",
                        cycle_status_path,
                    )
                    missing_status_warned = True
                continue
            missing_status_warned = False

            idle_all = detect_idle_worktrees(
                status,
                threshold_minutes=threshold_minutes,
                now=now_provider(),
                workspaces=workspaces,
            )
            if not idle_all:
                continue

            mono_now = time_source()
            fresh_idle = [
                entry
                for entry in idle_all
                if (mono_now - last_alert_at.get(entry["workspace"], 0.0))
                >= debounce_seconds
            ]
            if not fresh_idle:
                continue

            if not tmux_has_session(inject_session):
                if not missing_session_warned:
                    logger.warning(
                        "cycle_idle_watch_loop: tmux session 부재 — skip (target=%s)",
                        inject_target,
                    )
                    missing_session_warned = True
                continue
            missing_session_warned = False

            workspaces_label = ", ".join(e["workspace"] for e in fresh_idle)
            summary = _format_idle_summary(fresh_idle)
            today = now_provider().strftime("%Y-%m-%d")
            inject_text = CYCLE_IDLE_WATCH_INJECT_TEMPLATE.format(
                date=today, workspaces=workspaces_label, summary=summary
            )
            tmux_inject_text(inject_target, inject_text)

            channel = client.get_channel(notify_channel_id)
            if channel is None:
                if not missing_channel_warned:
                    logger.warning(
                        "cycle_idle_watch_loop: Discord channel 부재 — push skip (channel_id=%s)",
                        notify_channel_id,
                    )
                    missing_channel_warned = True
            else:
                missing_channel_warned = False
                discord_text = (
                    f"⚠️ nmae watchdog — {len(fresh_idle)} 워크트리 idle "
                    f"({workspaces_label}) — nmae 에 알림 inject 완료"
                )
                await send_with_retry(channel, content=discord_text)

            now_mono = time_source()
            for entry in fresh_idle:
                last_alert_at[entry["workspace"]] = now_mono
            logger.info(
                "cycle_idle_watch_loop: idle alert workspaces=%s",
                workspaces_label,
            )
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001
            logger.warning("cycle_idle_watch_loop iter 실패: %s", exc)


# ─────────────────────────────────────────────────────────────────────────────
# Discord client
# ─────────────────────────────────────────────────────────────────────────────


def build_client(env: dict[str, str], ledger: DedupLedger | None) -> discord.Client:
    """discord.py Client 를 셋업하고 핸들러를 바인딩합니다."""
    intents = discord.Intents.default()
    intents.message_content = True
    client = discord.Client(intents=intents)

    allowed_user_ids = parse_allowed_user_ids(env["ALLOWED_USER_IDS"])
    try:
        target_channel_id = int(env["MOBRUJI_CHANNEL_ID"])
    except ValueError:
        logger.error("MOBRUJI_CHANNEL_ID 가 정수 아님: %r", env["MOBRUJI_CHANNEL_ID"])
        sys.exit(1)

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

    session_name = env["TMUX_SESSION_NAME"]
    target_pane = env["TMUX_TARGET_PANE"]
    claude_bin = env["CLAUDE_BIN"]

    digest_enabled = env.get("DIGEST_ENABLED", "1") == "1"
    digest_interval = resolve_digest_interval(env.get("DIGEST_INTERVAL_SECONDS"))
    cycle_status_path = env.get("CYCLE_STATUS_PATH", DEFAULT_CYCLE_STATUS_PATH)

    bot_auto_ack_enabled = (
        env.get("BOT_AUTO_ACK", BOT_AUTO_ACK_DEFAULT_ENABLED) == "1"
    )

    # cycle watchdog (#941) — env 해석.
    cycle_idle_watch_enabled = (
        env.get("CYCLE_IDLE_WATCH", CYCLE_IDLE_WATCH_DEFAULT_ENABLED) == "1"
    )

    def _resolve_int_env(key: str, default: int, *, allow_zero: bool = True) -> int:
        raw = env.get(key)
        if raw is None:
            return default
        try:
            parsed = int(raw)
        except ValueError:
            logger.warning("%s 정수 아님(%r) — 기본값 %d 사용", key, raw, default)
            return default
        if not allow_zero and parsed <= 0:
            logger.warning("%s 양수 아님(%d) — 기본값 %d 사용", key, parsed, default)
            return default
        if allow_zero and parsed < 0:
            logger.warning("%s 음수(%d) — 기본값 %d 사용", key, parsed, default)
            return default
        return parsed

    cycle_idle_threshold_minutes = _resolve_int_env(
        "CYCLE_IDLE_THRESHOLD_MINUTES", CYCLE_IDLE_THRESHOLD_DEFAULT_MINUTES
    )
    cycle_idle_poll_interval = _resolve_int_env(
        "CYCLE_IDLE_WATCH_INTERVAL_SECONDS",
        CYCLE_IDLE_WATCH_DEFAULT_INTERVAL_SECONDS,
        allow_zero=False,
    )
    cycle_inject_target = env.get(
        "CYCLE_INJECT_TARGET", CYCLE_IDLE_WATCH_DEFAULT_INJECT_TARGET
    )
    cycle_workspaces = resolve_cycle_targets(env.get("CYCLE_WATCH_WORKSPACES"))
    cycle_notify_raw = env.get("CYCLE_NOTIFY_CHANNEL_ID", str(notify_channel_id))
    try:
        cycle_notify_channel_id = int(cycle_notify_raw)
    except ValueError:
        logger.warning(
            "CYCLE_NOTIFY_CHANNEL_ID 정수 아님(%r) — notify_channel_id(%d) fallback",
            cycle_notify_raw,
            notify_channel_id,
        )
        cycle_notify_channel_id = notify_channel_id

    context_auto_clear_enabled = env.get("CONTEXT_AUTO_CLEAR_ENABLED", "0") == "1"
    context_trigger_pct = resolve_context_pct_env(
        env.get("CONTEXT_CLEAR_TRIGGER_PCT"),
        CONTEXT_AUTO_CLEAR_DEFAULT_TRIGGER_PCT,
    )
    context_hysteresis_pct = resolve_context_pct_env(
        env.get("CONTEXT_CLEAR_HYSTERESIS_PCT"),
        CONTEXT_AUTO_CLEAR_DEFAULT_HYSTERESIS_PCT,
    )
    # multi-pane (#855). plural CSV 우선, singular 후방호환.
    context_pane_targets = resolve_pane_targets(env.get("TMUX_PANE_TARGETS"))

    @client.event
    async def on_ready() -> None:  # noqa: D401
        logger.info(
            "Discord Gateway 연결 OK: user=%s channel=%s notify=%s allowed=%d digest=%s auto_ack=%s",
            client.user,
            target_channel_id,
            notify_channel_id,
            len(allowed_user_ids),
            digest_enabled,
            bot_auto_ack_enabled,
        )
        if digest_enabled and not hasattr(client, "_digest_task_started"):
            # on_ready 는 reconnect 시 재호출 — task 중복 시작 방지.
            client._digest_task_started = True  # type: ignore[attr-defined]
            client.loop.create_task(
                digest_loop(
                    client,
                    notify_channel_id,
                    interval=digest_interval,
                    cycle_status_path=cycle_status_path,
                )
            )
            logger.info(
                "digest_loop launched: channel=%d interval=%ds heartbeat=%ds path=%s",
                notify_channel_id,
                digest_interval,
                DIGEST_HEARTBEAT_SECONDS,
                cycle_status_path,
            )

        # context auto-clear loop (spec §5-2, #809 → #855 multi-pane → #910 G-4).
        # opt-in 시 **항상 launch**. pane 존재 체크는 loop 안의 매 iter 에서
        # graceful skip — NCP 재부팅 순서 (bot.py boot < maestro tmux 세션 생성)
        # 의존성으로 loop 가 영구 dead 되는 버그(#910 G-4) 차단.
        if context_auto_clear_enabled and not hasattr(
            client, "_context_auto_clear_task_started"
        ):
            available_panes = [
                p
                for p in context_pane_targets
                if tmux_has_session(p.split(":", 1)[0])
            ]
            missing_panes = [
                p for p in context_pane_targets if p not in available_panes
            ]
            if missing_panes:
                logger.warning(
                    "context auto-clear: 부재 pane(들) graceful skip (loop 안에서 매 iter 재확인): %s",
                    ", ".join(missing_panes),
                )
            client._context_auto_clear_task_started = True  # type: ignore[attr-defined]
            client.loop.create_task(
                context_auto_clear_loop(
                    client,
                    notify_channel_id,
                    pane_targets=context_pane_targets,
                    trigger_pct=context_trigger_pct,
                    hysteresis_pct=context_hysteresis_pct,
                )
            )
            logger.info(
                "context_auto_clear_loop launched: panes=%s trigger=%d%% hysteresis=%d%% available=%d/%d",
                ", ".join(context_pane_targets),
                context_trigger_pct,
                context_hysteresis_pct,
                len(available_panes),
                len(context_pane_targets),
            )
        elif not context_auto_clear_enabled:
            logger.info("context auto-clear disabled (CONTEXT_AUTO_CLEAR_ENABLED=0)")

        # nmae cycle watchdog (#941, spec: docs/features/nmae-cycle-watchdog.md).
        # 5분 polling cycle-status.json — idle 워크트리 자동 nmae 알림 + Discord push.
        if cycle_idle_watch_enabled and not hasattr(
            client, "_cycle_idle_watch_task_started"
        ):
            client._cycle_idle_watch_task_started = True  # type: ignore[attr-defined]
            client.loop.create_task(
                cycle_idle_watch_loop(
                    client,
                    cycle_notify_channel_id,
                    cycle_status_path=cycle_status_path,
                    inject_target=cycle_inject_target,
                    threshold_minutes=cycle_idle_threshold_minutes,
                    poll_interval=cycle_idle_poll_interval,
                    workspaces=cycle_workspaces,
                )
            )
            logger.info(
                "cycle_idle_watch_loop launched: channel=%d interval=%ds threshold=%dmin target=%s workspaces=%s",
                cycle_notify_channel_id,
                cycle_idle_poll_interval,
                cycle_idle_threshold_minutes,
                cycle_inject_target,
                ",".join(cycle_workspaces),
            )
        elif not cycle_idle_watch_enabled:
            logger.info("cycle_idle_watch disabled (CYCLE_IDLE_WATCH=0)")

    @client.event
    async def on_message(message: discord.Message) -> None:
        if message.author.bot:
            return
        if message.channel.id != target_channel_id:
            return
        if message.author.id not in allowed_user_ids:
            logger.info("허용되지 않은 사용자 무시: user_id=%s", message.author.id)
            return

        message_id = str(message.id)
        # #909 F-3: dedup race fix.
        # 기존 흐름은 `is_processed` → tmux send → `mark_processed` 순서라
        # Discord Gateway reconnect (`on_message` 재호출) 시 mark 이전에 두 번째
        # 진입이 가능했다. `claim` 으로 가드+마크를 단일 SQLite 트랜잭션으로
        # 원자화하고, 이후 단계 (tmux send 등) 실패는 warning 만 남긴다.
        # mark 는 유지 — 재처리 위험이 tmux 재전송 누락보다 비용이 큼.
        if ledger is not None and not ledger.claim(message_id):
            logger.info("dedup hit: message_id=%s", message_id)
            return

        # reply.referenced_message — 사용자가 Discord "답장" 으로 보낸 경우,
        # 어떤 메시지에 대한 답장인지 prefix 로 helper 에게 전달 (#880).
        # discord.py 가 message.reference 와 message.referenced_message (hydrated)
        # 를 제공. referenced_message 가 None / 부분 정보 (delete 등) 면 ignore.
        referenced_content: str | None = None
        referenced_message = getattr(message, "referenced_message", None)
        if referenced_message is not None:
            ref_raw = getattr(referenced_message, "content", None)
            if isinstance(ref_raw, str) and ref_raw.strip():
                referenced_content = ref_raw

        original_body = message.content or ""
        forwarded_text = build_reply_context_prefix(
            referenced_content, original_body
        )

        ts_iso = message.created_at.astimezone(timezone.utc).isoformat()
        payload = {
            "text": forwarded_text,
            "author": str(message.author.id),
            "author_name": message.author.name,
            "ts": ts_iso,
            "message_id": message_id,
            "channel_id": str(message.channel.id),
        }

        logger.info(
            "메시지 수신: author=%s ts=%s preview=%r reply=%s",
            payload["author"],
            payload["ts"],
            truncate_for_log(payload["text"]),
            referenced_content is not None,
        )
        append_inbox(payload)
        # #946: helper 본답 자동 reply 용 message_id 캐시.
        # discord-reply.sh bare body 모드가 이 파일을 읽어
        # `message_reference` 를 payload 에 포함시켜 자동 reply 형태로 push.
        write_last_user_msg_id(message_id)

        # bot.py 1초 generic auto-ack (#880) — helper 자체 ack 까지 bash chain
        # latency 5+초 깜깜이 해소. 사용자 입장에서 [bot 1초 ack] → [helper 구체
        # ack] → [thread stream...] → [helper 본답] 순.
        # #807 에서 제거됐던 것 부활. BOT_AUTO_ACK=false 면 legacy 동작.
        if bot_auto_ack_enabled:
            try:
                await message.channel.send(BOT_AUTO_ACK_TEXT)
            except Exception as exc:  # noqa: BLE001
                logger.warning("bot auto-ack push 실패: %s", exc)

        # helper tmux 세션 routing — 단순화본은 routing 만 수행. 응답은 helper 측
        # `~/.mobruji/discord-reply.sh "<msg>"` 가 직접 bot REST API 로 push.
        # #909 F-3: claim 으로 이미 마킹됐기 때문에 여기서 실패해도 unclaim 하지
        # 않는다 (재처리 위험 회피). 실패는 warning + 운영자가 로그로 인지.
        if not ensure_tmux_session(session_name, claude_bin):
            logger.warning(
                "tmux 세션 확보 실패 — 메시지 dropped (claim 유지): id=%s",
                message_id,
            )
            return
        if not tmux_send_payload(target_pane, payload["text"]):
            logger.warning(
                "tmux send-keys 실패 — 메시지 dropped (claim 유지): id=%s",
                message_id,
            )
            return

    return client


def main() -> None:
    env = load_env()
    ledger = DedupLedger(env["DEDUP_LEDGER_PATH"])
    start_dedup_gc_thread(ledger)
    ensure_tmux_session(env["TMUX_SESSION_NAME"], env["CLAUDE_BIN"])

    client = build_client(env, ledger)
    logger.info(
        "Discord daemon 시작 (received_at=%s)",
        datetime.now(timezone.utc).isoformat(),
    )
    client.run(env["DISCORD_BOT_TOKEN"], log_handler=None)


if __name__ == "__main__":
    main()
