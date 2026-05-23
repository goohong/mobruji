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
MAX_TEXT_PREVIEW_LEN: Final[int] = 80
DEDUP_TTL_SECONDS: Final[int] = 24 * 60 * 60  # 24h
DEDUP_GC_INTERVAL_SECONDS: Final[int] = 60 * 60  # 1h

# digest cron 튜닝값 — cycle-status.json (사용자 룰 2026-05-23).
DEFAULT_DIGEST_INTERVAL_SECONDS: Final[int] = 900  # 15분
DIGEST_INITIAL_DELAY_SECONDS: Final[int] = 60  # boot 1분 warmup
DIGEST_HEARTBEAT_SECONDS: Final[int] = 60 * 60  # delta 없어도 1h 1회는 push
DEFAULT_CYCLE_STATUS_PATH: Final[str] = "/home/mobruji/.mobruji/cycle-status.json"
CYCLE_DIGEST_WORKSPACES: Final[tuple[str, ...]] = ("be", "fe", "rev", "plan")
CYCLE_DIGEST_MAX_LINE_LEN: Final[int] = 200
# digest 본문 timestamp — 사용자 요청 #811. Discord 가 보여주는 시각이 클라이언트
# timezone 에 의존하므로 본문에 KST 명시로 한눈에 emit 시각 확인.
CYCLE_DIGEST_TZ: Final[ZoneInfo] = ZoneInfo("Asia/Seoul")
CYCLE_DIGEST_TIME_FORMAT: Final[str] = "%Y-%m-%d %H:%M KST"

# 7 카테고리 emoji prefix — spec: docs/features/discord-message-style.md §3.
# bot.py 단순화본은 digest 만 사용하지만 헬퍼 import 호환을 위해 전체 보존.
MESSAGE_PREFIX: Final[dict[str, str]] = {
    "reply": "💬",
    "cycle-start": "🚀",
    "cycle-end": "✅",
    "digest": "📊",
    "alert": "🚨",
    "recovery": "🟢",
    "decision": "📌",
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
CONTEXT_AUTO_CLEAR_DEFAULT_ENABLED: Final[str] = "0"
CONTEXT_AUTO_CLEAR_DEFAULT_TRIGGER_PCT: Final[int] = 95
CONTEXT_AUTO_CLEAR_DEFAULT_HYSTERESIS_PCT: Final[int] = 80
CONTEXT_AUTO_CLEAR_POLL_INTERVAL_SECONDS: Final[int] = 5
CONTEXT_AUTO_CLEAR_DEFAULT_PANE: Final[str] = "mobruji:0.0"
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
    "description: maestro context auto-clear 사이클 결과 누적 (trigger / marker / clear)\n"
    "metadata:\n"
    "  type: project\n"
    "---\n"
    "\n"
    "# context auto-clear log (역시간순)\n"
    "\n"
    "| timestamp | event | context% | handoff file | duration |\n"
    "|---|---|---|---|---|\n"
)
CONTEXT_CLEANUP_PROMPT: Final[str] = (
    "🧠 컨텍스트 95% 도달. 다음 절차로 자율 정리하라: "
    "1) 진행 중 sub-agent 모두 완료 대기 (launch 추가 금지) "
    "2) 다음 핸드오프 메모리 project_session_handoff_<YYYY-MM-DD>_v<N+1>.md 작성 "
    "(사이클 카운트 / 진행 중 PR / 사용자 결정 대기 / 첫 액션) "
    "3) MEMORY.md 인덱스에 새 핸드오프 한 줄 추가 "
    "4) 정리 완료 후 stdout 에 marker 출력: ===CLEAR_READY=== "
    "5) 그 후 정지 (ScheduleWakeup 재호출 안 함 — /clear 후 다음 wake 가 처리)"
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
    env["TMUX_PANE_TARGET"] = os.environ.get(
        "TMUX_PANE_TARGET", CONTEXT_AUTO_CLEAR_DEFAULT_PANE
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


def truncate_for_log(text: str) -> str:
    """로그에 메시지를 남길 때 너무 길지 않도록 자릅니다."""
    if len(text) <= MAX_TEXT_PREVIEW_LEN:
        return text
    return text[:MAX_TEXT_PREVIEW_LEN] + "…"


def append_inbox(payload: dict[str, str]) -> None:
    """inbox.jsonl 에 한 줄 JSON 으로 append 합니다 (백업/디버깅용)."""
    try:
        with INBOX_PATH.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(payload, ensure_ascii=False) + "\n")
    except OSError as exc:
        logger.warning("inbox.jsonl write 실패: %s", exc)


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
            "be":  {"in_progress": str|null, "last_completed": {...}|null},
            "fe":  {...},
            "rev": {...},
            "plan": {...}
        }
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


def format_cycle_digest(
    status: dict | None,
    now: datetime | None = None,
) -> tuple[str, str]:
    """4 워크트리(be/fe/rev/plan) 의 진행/최근 한 줄씩 digest 본문을 만듭니다.

    Args:
        status: `read_cycle_status()` 반환 dict, 또는 None (파일 없음/깨짐).
        now: 헤더 timestamp 산출 기준 시각. 기본값 None → 호출 시점 KST.
            테스트 deterministic 용으로만 외부 주입.

    Returns:
        (rendered, signature) 튜플.
          - rendered: Discord 에 push 할 multiline 메시지.
          - signature: delta 비교용 (시간 무관). 동일 signature 면 heartbeat 만 push.

    한 줄 형식:
        [be] 진행: <in_progress 또는 "idle"> / 최근: <pr> (<title>)
        [be] 진행: idle / 최근: 없음   ← last_completed 가 null 인 경우

    스키마 누락/타입 이상 시 해당 필드만 "idle" / "없음" 으로 대체합니다.

    헤더 바로 아래에 `🕒 YYYY-MM-DD HH:MM KST` timestamp 라인을 항상 삽입합니다
    (사용자 요청 #811). timestamp 는 signature 에 포함하지 않으므로 delta push
    판정에는 영향을 주지 않습니다.
    """
    prefix = MESSAGE_PREFIX["digest"]
    if now is None:
        now = datetime.now(CYCLE_DIGEST_TZ)
    elif now.tzinfo is not None:
        now = now.astimezone(CYCLE_DIGEST_TZ)
    timestamp_line = f"🕒 {now.strftime(CYCLE_DIGEST_TIME_FORMAT)}"
    lines: list[str] = [f"{prefix} **cycle digest**", timestamp_line]
    sig_parts: list[str] = []

    if status is None or not isinstance(status, dict):
        lines.append("(cycle-status.json 읽기 실패 — 본진 갱신 대기)")
        return "\n".join(lines), "unavailable"

    for ws in CYCLE_DIGEST_WORKSPACES:
        entry = status.get(ws)
        if not isinstance(entry, dict):
            in_progress_text = "idle"
            recent_text = "없음"
            sig_parts.append(f"{ws}=missing")
        else:
            raw_in_progress = entry.get("in_progress")
            if isinstance(raw_in_progress, str) and raw_in_progress.strip():
                in_progress_text = raw_in_progress.strip()
            else:
                in_progress_text = "idle"

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

        in_progress_text = sanitize_mentions(in_progress_text)
        recent_text = sanitize_mentions(recent_text)

        line = f"[{ws}] 진행: {in_progress_text} / 최근: {recent_text}"
        if len(line) > CYCLE_DIGEST_MAX_LINE_LEN:
            line = line[: CYCLE_DIGEST_MAX_LINE_LEN - 1] + "…"
        lines.append(line)

    rendered = "\n".join(lines)
    signature = "||".join(sig_parts)
    return rendered, signature


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
    `~/.mobruji/cycle-status.json` 의 4 워크트리(be/fe/rev/plan) 진행/최근
    한 줄씩을 push 합니다.

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
                line, signature = format_cycle_digest(status)
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


def append_clear_log(pct: int, event: str) -> None:
    """`project_context_clear_log.md` 에 한 줄 append. 파일 없으면 header 생성.

    spec §5-3 포맷: `| timestamp | event | context% | handoff file | duration |`.
    handoff file 과 duration 은 자동 채울 정보가 없으므로 `-` 로 비워둡니다.
    """
    timestamp = datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")
    row = f"| {timestamp} | {event} | {pct} | - | - |\n"
    try:
        CONTEXT_CLEAR_LOG_PATH.parent.mkdir(parents=True, exist_ok=True)
        if not CONTEXT_CLEAR_LOG_PATH.exists():
            with CONTEXT_CLEAR_LOG_PATH.open("w", encoding="utf-8") as handle:
                handle.write(CONTEXT_CLEAR_LOG_HEADER)
        with CONTEXT_CLEAR_LOG_PATH.open("a", encoding="utf-8") as handle:
            handle.write(row)
    except OSError as exc:
        logger.warning("context clear log append 실패: %s", exc)


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
    pane_target: str = CONTEXT_AUTO_CLEAR_DEFAULT_PANE,
    trigger_pct: int = CONTEXT_AUTO_CLEAR_DEFAULT_TRIGGER_PCT,
    hysteresis_pct: int = CONTEXT_AUTO_CLEAR_DEFAULT_HYSTERESIS_PCT,
    poll_interval: int = CONTEXT_AUTO_CLEAR_POLL_INTERVAL_SECONDS,
) -> None:
    """5초 간격 polling. trigger_pct 도달 → 정리 prompt inject, marker → /clear.

    spec §5-2 본문 흐름:
      1. AUTOCLEAR_PAUSED_FLAG 존재하면 polling skip (사용자 수동 중단).
      2. tmux capture-pane → parse_context_pct 로 최신 marker 추출.
      3. awaiting_marker == True 이고 CLEAR_READY_MARKER 가 보이면:
         Discord push → send_clear_command → log append → awaiting_marker 해제.
      4. debounced == True 이고 pct ≤ hysteresis_pct 면 debounce 해제.
      5. debounced == False 이고 pct ≥ trigger_pct 면:
         Discord push → inject_cleanup_prompt → log append → debounced/awaiting_marker SET.
      6. marker 도착까지 timeout 없음 — sub-agent 보호 (spec §3 안전성).

    asyncio.CancelledError 는 외부로 전파해 bot 종료 시 깔끔히 정리되도록.
    """
    debounced = False
    awaiting_marker = False
    while True:
        try:
            await asyncio.sleep(poll_interval)
            if AUTOCLEAR_PAUSED_FLAG.exists():
                continue
            pane_text = capture_pane_text(pane_target)
            if pane_text is None:
                continue
            pct = parse_context_pct(pane_text)
            # marker 우선 — pct 가 None 이어도 정리 완료 신호는 살린다.
            if awaiting_marker and CLEAR_READY_MARKER in pane_text:
                last_pct_text = "?" if pct is None else f"{pct}%"
                channel = client.get_channel(channel_id)
                if channel is not None:
                    await channel.send(
                        f"🧹 정리 완료 → /clear 전송 (마지막 context {last_pct_text})"
                    )
                else:
                    logger.warning(
                        "context auto-clear: channel_id=%s 없음 — marker push skip",
                        channel_id,
                    )
                send_clear_command(pane_target)
                append_clear_log(pct if pct is not None else -1, "cleared")
                awaiting_marker = False
                # debounce 는 80% 이하 자연 falloff 까지 유지.
                continue
            if pct is None:
                continue
            if debounced and pct <= hysteresis_pct:
                debounced = False
            if not debounced and pct >= trigger_pct:
                channel = client.get_channel(channel_id)
                if channel is not None:
                    await channel.send(
                        f"🧠 context {pct}% → 자율 정리 시작"
                    )
                else:
                    logger.warning(
                        "context auto-clear: channel_id=%s 없음 — trigger push skip",
                        channel_id,
                    )
                inject_cleanup_prompt(pane_target)
                append_clear_log(pct, "triggered")
                debounced = True
                awaiting_marker = True
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001
            logger.warning("context_auto_clear_loop iter 실패: %s", exc)


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

    context_auto_clear_enabled = env.get("CONTEXT_AUTO_CLEAR_ENABLED", "0") == "1"
    context_trigger_pct = resolve_context_pct_env(
        env.get("CONTEXT_CLEAR_TRIGGER_PCT"),
        CONTEXT_AUTO_CLEAR_DEFAULT_TRIGGER_PCT,
    )
    context_hysteresis_pct = resolve_context_pct_env(
        env.get("CONTEXT_CLEAR_HYSTERESIS_PCT"),
        CONTEXT_AUTO_CLEAR_DEFAULT_HYSTERESIS_PCT,
    )
    context_pane_target = env.get("TMUX_PANE_TARGET", CONTEXT_AUTO_CLEAR_DEFAULT_PANE)

    @client.event
    async def on_ready() -> None:  # noqa: D401
        logger.info(
            "Discord Gateway 연결 OK: user=%s channel=%s notify=%s allowed=%d digest=%s",
            client.user,
            target_channel_id,
            notify_channel_id,
            len(allowed_user_ids),
            digest_enabled,
        )
        if digest_enabled and not hasattr(client, "_digest_task_started"):
            # on_ready 는 reconnect 시 재호출 — task 중복 시작 방지.
            client._digest_task_started = True  # type: ignore[attr-defined]
            client.loop.create_task(
                digest_loop(
                    client,
                    notify_channel_id,
                    interval=digest_interval,
                )
            )
            logger.info(
                "digest_loop launched: channel=%d interval=%ds heartbeat=%ds",
                notify_channel_id,
                digest_interval,
                DIGEST_HEARTBEAT_SECONDS,
            )

        # context auto-clear loop (spec §5-2, #809). opt-in 이고 pane 존재할 때만 launch.
        if context_auto_clear_enabled and not hasattr(
            client, "_context_auto_clear_task_started"
        ):
            pane_session = context_pane_target.split(":", 1)[0]
            if not tmux_has_session(pane_session):
                logger.warning(
                    "context auto-clear skip: tmux 세션 없음 (session=%s pane=%s)",
                    pane_session,
                    context_pane_target,
                )
            else:
                client._context_auto_clear_task_started = True  # type: ignore[attr-defined]
                client.loop.create_task(
                    context_auto_clear_loop(
                        client,
                        notify_channel_id,
                        pane_target=context_pane_target,
                        trigger_pct=context_trigger_pct,
                        hysteresis_pct=context_hysteresis_pct,
                    )
                )
                logger.info(
                    "context_auto_clear_loop launched: pane=%s trigger=%d%% hysteresis=%d%%",
                    context_pane_target,
                    context_trigger_pct,
                    context_hysteresis_pct,
                )
        elif not context_auto_clear_enabled:
            logger.info("context auto-clear disabled (CONTEXT_AUTO_CLEAR_ENABLED=0)")

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

        # helper tmux 세션 routing — 단순화본은 routing 만 수행. 응답은 helper 측
        # `~/.mobruji/discord-reply.sh "<msg>"` 가 직접 bot REST API 로 push.
        if not ensure_tmux_session(session_name, claude_bin):
            logger.error("tmux 세션 확보 실패 — 메시지 dropped: id=%s", message_id)
            return
        if not tmux_send_payload(target_pane, payload["text"]):
            logger.error("tmux send-keys 실패 — 메시지 dropped: id=%s", message_id)
            return

        if ledger is not None:
            ledger.mark_processed(message_id)

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
