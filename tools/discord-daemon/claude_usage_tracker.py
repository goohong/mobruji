"""Claude API usage tracker (#1020).

Anthropic API 가 quota endpoint 를 제공하지 않으므로 ``~/.claude/projects/<project>/*.jsonl``
의 assistant 메시지 ``message.usage`` 를 직접 합산해 KST 일/주 단위 token 사용량을
추적한다.

기능:
1. ``~/.claude/projects/**/*.jsonl`` 안 assistant 메시지에서
   ``input_tokens / output_tokens / cache_creation_input_tokens / cache_read_input_tokens``
   합산.
2. KST(Asia/Seoul) 기준 일(YYYY-MM-DD) / 주(ISO week) 단위 누적.
3. ``~/.mobruji/claude-usage.json`` 으로 atomic write.
4. ``last_pushed.daily_pct`` / ``last_pushed.weekly_pct`` 비교로 같은 % 두 번
   push 금지 (dedup).
5. KST 자정 / 월요일 경계에서 daily / weekly 카운트 lazy reset.

bot.py 의 ``claude_usage_watch_loop`` 가 N 초마다 이 모듈을 호출.
spec: 이슈 #1020.
"""

from __future__ import annotations

import json
import logging
import os
import tempfile
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Final, Iterable
from zoneinfo import ZoneInfo

KST: Final[ZoneInfo] = ZoneInfo("Asia/Seoul")
DEFAULT_PROJECTS_ROOT: Final[Path] = Path("~/.claude/projects").expanduser()
DEFAULT_STATE_PATH: Final[Path] = Path("~/.mobruji/claude-usage.json").expanduser()
DEFAULT_DAILY_LIMIT: Final[int] = 1_000_000
DEFAULT_WEEKLY_LIMIT: Final[int] = 7_000_000
# 10% 단위 threshold — 0, 10, 20, ..., 100 중 마지막으로 통과한 bucket.
THRESHOLD_BUCKET_PCT: Final[int] = 10
STATE_FILE_MODE: Final[int] = 0o600

# `message.usage` 합산 키 — Anthropic streaming/regular schema 양쪽 공통.
USAGE_TOKEN_KEYS: Final[tuple[str, ...]] = (
    "input_tokens",
    "output_tokens",
    "cache_creation_input_tokens",
    "cache_read_input_tokens",
)

logger = logging.getLogger("mobruji-discord-daemon.claude-usage-tracker")


# ─────────────────────────────────────────────────────────────────────────────
# data models
# ─────────────────────────────────────────────────────────────────────────────


@dataclass(frozen=True)
class UsageSnapshot:
    """tracker scan 결과.

    Attributes:
        daily_date: KST YYYY-MM-DD.
        daily_tokens: 그 날 누적 token 합 (전 키 합산).
        weekly_iso_week: KST ISO week 문자열 (``YYYY-WNN``).
        weekly_tokens: 그 주 누적 token 합.
        daily_pct: daily_tokens / daily_limit * 100 (정수 floor).
        weekly_pct: weekly_tokens / weekly_limit * 100 (정수 floor).
    """

    daily_date: str
    daily_tokens: int
    weekly_iso_week: str
    weekly_tokens: int
    daily_pct: int
    weekly_pct: int


# ─────────────────────────────────────────────────────────────────────────────
# KST date / week helpers
# ─────────────────────────────────────────────────────────────────────────────


def _kst_now(now: datetime | None = None) -> datetime:
    """KST 기준 현재 시각 — None 이면 wall clock, 그 외 KST 로 변환."""
    if now is None:
        return datetime.now(KST)
    if now.tzinfo is None:
        # naive datetime 은 KST 로 간주 (테스트 deterministic 입력 가정).
        return now.replace(tzinfo=KST)
    return now.astimezone(KST)


def kst_date_string(now: datetime | None = None) -> str:
    """KST 기준 YYYY-MM-DD."""
    return _kst_now(now).strftime("%Y-%m-%d")


def kst_iso_week_string(now: datetime | None = None) -> str:
    """KST 기준 ISO week (``YYYY-WNN``, 1-indexed).

    ISO week 는 월요일 시작. weekly reset 경계 자동 처리.
    """
    iso = _kst_now(now).isocalendar()
    return f"{iso.year:04d}-W{iso.week:02d}"


# ─────────────────────────────────────────────────────────────────────────────
# jsonl scan
# ─────────────────────────────────────────────────────────────────────────────


def _iter_jsonl_files(projects_root: Path) -> Iterable[Path]:
    """``projects_root`` 아래 모든 .jsonl 파일을 yield."""
    if not projects_root.exists():
        return []
    return projects_root.rglob("*.jsonl")


def _extract_usage_tokens(usage: dict) -> int:
    """단일 ``message.usage`` dict 에서 token 4종 합산.

    누락된 키는 0. int 가 아니면 0. 음수도 0 으로 clip.
    """
    total = 0
    for key in USAGE_TOKEN_KEYS:
        raw = usage.get(key)
        if isinstance(raw, int) and raw > 0:
            total += raw
    return total


def _extract_record_timestamp(record: dict) -> datetime | None:
    """jsonl record 의 timestamp(ISO8601) 를 aware datetime 으로 파싱.

    ``timestamp`` 키 우선 (Claude CLI 표준). 빈 문자열/타입 이상은 None.
    """
    ts_raw = record.get("timestamp")
    if not isinstance(ts_raw, str) or not ts_raw.strip():
        return None
    candidate = ts_raw.strip()
    if candidate.endswith("Z"):
        candidate = candidate[:-1] + "+00:00"
    try:
        parsed = datetime.fromisoformat(candidate)
    except ValueError:
        return None
    if parsed.tzinfo is None:
        # naive 면 UTC 로 간주 (Claude CLI 가 UTC ISO 출력).
        parsed = parsed.replace(tzinfo=ZoneInfo("UTC"))
    return parsed


def scan_usage(
    *,
    projects_root: Path = DEFAULT_PROJECTS_ROOT,
    now: datetime | None = None,
    daily_limit: int = DEFAULT_DAILY_LIMIT,
    weekly_limit: int = DEFAULT_WEEKLY_LIMIT,
) -> UsageSnapshot:
    """``projects_root`` 아래 jsonl 들을 스캔해 KST 일/주 token 합산을 산출.

    Args:
        projects_root: ``~/.claude/projects`` (또는 테스트 fixture root).
        now: 기준 시각. None 이면 wall clock. naive datetime 은 KST 로 해석.
        daily_limit / weekly_limit: limit 분모 — pct 계산용.

    Returns:
        ``UsageSnapshot`` — daily/weekly 누적 token + pct (10% bucket 비교용).

    동작:
      - assistant 메시지의 ``message.usage`` 합산. user/tool/system 무시.
      - 각 record 의 ``timestamp`` 를 KST 로 변환해 daily/weekly bucket 분류.
      - timestamp 누락된 record 는 skip (정밀성 위해 추정 안 함).
      - jsonl 파싱 실패 line 은 skip + warning 1회.
    """
    today_date = kst_date_string(now)
    this_week = kst_iso_week_string(now)

    daily_tokens = 0
    weekly_tokens = 0

    for jsonl_path in _iter_jsonl_files(projects_root):
        try:
            # utf-8 graceful (#1068, 이슈 #1065): 손상된 binary log 가 섞일 수 있어
            # errors="replace" 로 line-level skip + 라인별 utf-8 decode error 도
            # 흡수. 이전엔 file 단위 raise → loop iter 실패.
            with jsonl_path.open(
                "r", encoding="utf-8", errors="replace"
            ) as handle:
                for line in handle:
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        record = json.loads(line)
                    except (json.JSONDecodeError, UnicodeDecodeError):
                        continue
                    if not isinstance(record, dict):
                        continue
                    if record.get("type") != "assistant":
                        continue
                    message = record.get("message")
                    if not isinstance(message, dict):
                        continue
                    usage = message.get("usage")
                    if not isinstance(usage, dict):
                        continue
                    record_ts = _extract_record_timestamp(record)
                    if record_ts is None:
                        continue
                    record_ts_kst = record_ts.astimezone(KST)
                    tokens = _extract_usage_tokens(usage)
                    if tokens == 0:
                        continue
                    record_date = record_ts_kst.strftime("%Y-%m-%d")
                    record_iso = record_ts_kst.isocalendar()
                    record_week = f"{record_iso.year:04d}-W{record_iso.week:02d}"
                    if record_date == today_date:
                        daily_tokens += tokens
                    if record_week == this_week:
                        weekly_tokens += tokens
        except (OSError, UnicodeDecodeError) as exc:
            # OSError: 권한 부재 등 / UnicodeDecodeError: errors="replace" 우회 후에도
            # open 단계 (메타데이터 손상) 에서 raise 되는 극단 케이스.
            logger.warning(
                "claude_usage scan: %s read 실패 (graceful skip): %s",
                jsonl_path,
                exc,
            )
            continue

    daily_pct = compute_pct(daily_tokens, daily_limit)
    weekly_pct = compute_pct(weekly_tokens, weekly_limit)
    return UsageSnapshot(
        daily_date=today_date,
        daily_tokens=daily_tokens,
        weekly_iso_week=this_week,
        weekly_tokens=weekly_tokens,
        daily_pct=daily_pct,
        weekly_pct=weekly_pct,
    )


def compute_pct(tokens: int, limit: int) -> int:
    """tokens / limit * 100 — int floor. limit<=0 이면 0."""
    if limit <= 0 or tokens <= 0:
        return 0
    return int((tokens * 100) // limit)


def bucket_pct(pct: int, bucket: int = THRESHOLD_BUCKET_PCT) -> int:
    """가장 가까운 작은 N% bucket (0, 10, 20, ...). pct<bucket 이면 0."""
    if pct < bucket:
        return 0
    return (pct // bucket) * bucket


# ─────────────────────────────────────────────────────────────────────────────
# state read / write
# ─────────────────────────────────────────────────────────────────────────────


def _empty_state(
    *,
    daily_limit: int = DEFAULT_DAILY_LIMIT,
    weekly_limit: int = DEFAULT_WEEKLY_LIMIT,
) -> dict:
    return {
        "daily": {"date": None, "tokens": 0},
        "weekly": {"iso_week": None, "tokens": 0},
        "limits": {"daily": daily_limit, "weekly": weekly_limit},
        "last_pushed": {"daily_pct": 0, "weekly_pct": 0},
    }


def read_state(
    path: Path = DEFAULT_STATE_PATH,
    *,
    daily_limit: int = DEFAULT_DAILY_LIMIT,
    weekly_limit: int = DEFAULT_WEEKLY_LIMIT,
) -> dict:
    """state JSON 을 읽어 dict 로 반환. 부재/parse fail → 빈 state."""
    if not path.exists():
        return _empty_state(daily_limit=daily_limit, weekly_limit=weekly_limit)
    try:
        with path.open("r", encoding="utf-8") as handle:
            data = json.load(handle)
    except (OSError, json.JSONDecodeError) as exc:
        logger.warning("claude_usage state read 실패: %s — empty state 사용", exc)
        return _empty_state(daily_limit=daily_limit, weekly_limit=weekly_limit)
    if not isinstance(data, dict):
        return _empty_state(daily_limit=daily_limit, weekly_limit=weekly_limit)
    # 정규화 — 누락 키 보전.
    normalized = _empty_state(daily_limit=daily_limit, weekly_limit=weekly_limit)
    for top_key, default_value in normalized.items():
        value = data.get(top_key)
        if isinstance(value, dict):
            merged = dict(default_value)
            merged.update({k: v for k, v in value.items() if k in default_value})
            normalized[top_key] = merged
    return normalized


def write_state(state: dict, path: Path = DEFAULT_STATE_PATH) -> None:
    """state JSON 을 atomic write (mktemp + os.replace).

    부모 디렉토리 없으면 생성. 권한 0o600.
    """
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp_fd, tmp_path = tempfile.mkstemp(
        prefix=".claude-usage-", dir=str(path.parent)
    )
    try:
        with os.fdopen(tmp_fd, "w", encoding="utf-8") as handle:
            json.dump(state, handle, ensure_ascii=False, indent=2, sort_keys=True)
            handle.write("\n")
        os.chmod(tmp_path, STATE_FILE_MODE)
        os.replace(tmp_path, path)
    except OSError:
        if os.path.exists(tmp_path):
            try:
                os.unlink(tmp_path)
            except OSError:
                pass
        raise


# ─────────────────────────────────────────────────────────────────────────────
# update + threshold detection
# ─────────────────────────────────────────────────────────────────────────────


@dataclass(frozen=True)
class ThresholdEvent:
    """threshold 도달 시 호출부에 전달되는 push payload.

    Attributes:
        kind: ``daily`` 또는 ``weekly`` — 어느 축에서 트리거된 bucket 인지.
        bucket_pct: 새로 진입한 10% bucket (10, 20, ..., 100).
        snapshot: 트리거 시점 ``UsageSnapshot`` (push 본문에 포함).
    """

    kind: str
    bucket_pct: int
    snapshot: UsageSnapshot


def update_and_detect_thresholds(
    state: dict,
    snapshot: UsageSnapshot,
) -> tuple[dict, list[ThresholdEvent]]:
    """state 와 snapshot 을 비교해 새 10% bucket 진입 event 를 산출.

    동작:
      1. KST 자정 reset — ``state.daily.date != snapshot.daily_date`` 이면
         daily counter 0 + last_pushed.daily_pct 0 reset.
      2. KST 월요일 reset — ``state.weekly.iso_week != snapshot.weekly_iso_week``
         이면 weekly counter 0 + last_pushed.weekly_pct 0 reset.
      3. snapshot 의 daily_tokens / weekly_tokens 로 state 갱신.
      4. snapshot.daily_pct 의 bucket 이 state.last_pushed.daily_pct 보다 크면
         events 에 ThresholdEvent 추가 + state 갱신.
      5. weekly 도 동일.

    Returns:
        (new_state, events) 튜플.
            - new_state: 갱신된 state (호출부가 write_state 로 영속).
            - events: 이번 호출에서 새로 진입한 bucket — 0건 가능.

    같은 bucket 두 번 호출은 events 0건 — 자연 dedup.
    """
    new_state = json.loads(json.dumps(state))  # deep copy.

    # daily reset.
    if new_state["daily"].get("date") != snapshot.daily_date:
        new_state["daily"]["date"] = snapshot.daily_date
        new_state["daily"]["tokens"] = 0
        new_state["last_pushed"]["daily_pct"] = 0
    new_state["daily"]["tokens"] = snapshot.daily_tokens

    # weekly reset.
    if new_state["weekly"].get("iso_week") != snapshot.weekly_iso_week:
        new_state["weekly"]["iso_week"] = snapshot.weekly_iso_week
        new_state["weekly"]["tokens"] = 0
        new_state["last_pushed"]["weekly_pct"] = 0
    new_state["weekly"]["tokens"] = snapshot.weekly_tokens

    events: list[ThresholdEvent] = []

    daily_new_bucket = bucket_pct(snapshot.daily_pct)
    daily_last_bucket = bucket_pct(
        int(new_state["last_pushed"].get("daily_pct", 0) or 0)
    )
    if daily_new_bucket > daily_last_bucket and daily_new_bucket > 0:
        events.append(
            ThresholdEvent(
                kind="daily",
                bucket_pct=daily_new_bucket,
                snapshot=snapshot,
            )
        )
        new_state["last_pushed"]["daily_pct"] = daily_new_bucket

    weekly_new_bucket = bucket_pct(snapshot.weekly_pct)
    weekly_last_bucket = bucket_pct(
        int(new_state["last_pushed"].get("weekly_pct", 0) or 0)
    )
    if weekly_new_bucket > weekly_last_bucket and weekly_new_bucket > 0:
        events.append(
            ThresholdEvent(
                kind="weekly",
                bucket_pct=weekly_new_bucket,
                snapshot=snapshot,
            )
        )
        new_state["last_pushed"]["weekly_pct"] = weekly_new_bucket

    return new_state, events


# ─────────────────────────────────────────────────────────────────────────────
# message format
# ─────────────────────────────────────────────────────────────────────────────


def _format_token_count(tokens: int) -> str:
    """token 수치를 사람이 읽기 쉬운 단위로 — 1.05M / 300K 등.

    1M 이상: ``X.YYM`` 소수점 2자리. 단 trailing 0 / '.' 제거.
    1K 이상: ``XK`` (천 단위 floor). 그 미만: 원본 정수 그대로.
    """
    if tokens >= 1_000_000:
        formatted = f"{tokens / 1_000_000:.2f}"
        # trailing 0 / 소수점 제거 (예: 1.00 → 1, 1.05 → 1.05, 1.10 → 1.1).
        if "." in formatted:
            formatted = formatted.rstrip("0").rstrip(".")
        return f"{formatted}M"
    if tokens >= 1_000:
        return f"{tokens // 1_000}K"
    return str(tokens)


def format_threshold_message(
    event: ThresholdEvent,
    *,
    daily_limit: int,
    weekly_limit: int,
) -> str:
    """ThresholdEvent → Discord push 본문 (#1020 spec 메시지).

    예: "📊 Claude usage daily 30% 도달 (300K / 1M tokens). 주간 15% (1.05M / 7M)."
    """
    snapshot = event.snapshot
    daily_str = _format_token_count(snapshot.daily_tokens)
    daily_limit_str = _format_token_count(daily_limit)
    weekly_str = _format_token_count(snapshot.weekly_tokens)
    weekly_limit_str = _format_token_count(weekly_limit)
    axis_label = "daily" if event.kind == "daily" else "weekly"
    return (
        f"📊 Claude usage {axis_label} {event.bucket_pct}% 도달 "
        f"({daily_str} / {daily_limit_str} tokens). "
        f"주간 {snapshot.weekly_pct}% ({weekly_str} / {weekly_limit_str})."
    )
