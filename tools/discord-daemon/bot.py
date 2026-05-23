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
import json
import logging
import os
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
SENTINEL_PREFIX: Final[str] = "/system:"
STATUS_COMMAND_PREFIX: Final[str] = "/status"
STATUS_GH_TIMEOUT_SECONDS: Final[int] = 8
STATUS_OPEN_PR_LIMIT: Final[int] = 8
STATUS_MERGED_PR_LIMIT: Final[int] = 5
STATUS_BUG_ISSUE_LIMIT: Final[int] = 5
STATUS_DISCORD_MAX_LEN: Final[int] = 1900  # Discord 메시지 한도 2000, 여유 100
AUTO_ACK_QUEUE_WINDOW_SECONDS: Final[int] = 300  # 5분 안 dedup mark 수 = queue 표시

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
AUTO_ACK_TEMPLATE: Final[str] = (
    f"{MESSAGE_PREFIX['reply']} reply: 받음, maestro 처리 중 (queue: {{queue}})"
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
            recent_lines.append(f"  · #{pr.get('number')} {title}")

    # 백로그 시그널: open PR 3개 title preview.
    backlog_lines: list[str] = []
    if open_prs:
        for pr in open_prs[:3]:
            title = pr.get("title", "")
            if len(title) > 60:
                title = title[:60] + "…"
            backlog_lines.append(f"  · #{pr.get('number')} {title}")

    bug_lines: list[str] = []
    if bug_issues:
        for issue in bug_issues[:3]:
            title = issue.get("title", "")
            if len(title) > 60:
                title = title[:60] + "…"
            bug_lines.append(f"  · #{issue.get('number')} {title}")

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
