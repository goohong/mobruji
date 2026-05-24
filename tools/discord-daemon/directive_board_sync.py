"""directive-board auto-PATCH + mismatch detector (#P11).

배경 (사용자 P0 사고, 2026-05-24):
helper backfill 후 8 directive 본문이 stale (PR 머지/상태 변경 후에도 Discord
포스트가 manual PATCH 필요해 갱신 누락). 사용자 정정: "모부르지-지시들은 그냥
계속 진행중인가? 진행상황에 변동이 없네".

근본 원인: 자동 PATCH 파이프라인 부재. helper/nmae/sub-agent 가 jsonl 을 갱신해도
Discord 측 PATCH 가 manual.

동작:
1. ``~/.mobruji/directive-board.jsonl`` 을 N 초마다 polling.
2. 각 entry 별 ``message_id`` 와 ``last_updated_kst`` (변경 감지 키) 를
   state 파일 (``~/.mobruji/directive-board-sync.json``) 에 기록.
3. ``last_updated_kst`` 가 state 와 다르면 Discord REST API ``PATCH
   /channels/{ch}/messages/{msg_id}`` 호출로 본문 갱신.
4. PATCH 실패 (404 Unknown Message / 403 등) 시 mismatch 카운트 누적 — digest
   signature 에 포함되어 사용자 가시화.
5. atomic write + 같은 entry 동일 last_updated_kst 시 skip (no-op).

bot.py 의 ``directive_board_sync_loop`` 가 N 초마다 ``sync_once`` 호출.

spec: 이슈 #P11 (directive-board auto-PATCH).
"""

from __future__ import annotations

import json
import logging
import os
import tempfile
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable, Final

import requests

logger = logging.getLogger("mobruji-discord-daemon.directive-board-sync")

DEFAULT_JSONL_PATH: Final[Path] = Path(
    "~/.mobruji/directive-board.jsonl"
).expanduser()
DEFAULT_STATE_PATH: Final[Path] = Path(
    "~/.mobruji/directive-board-sync.json"
).expanduser()
STATE_FILE_MODE: Final[int] = 0o600
DISCORD_API_BASE: Final[str] = "https://discord.com/api/v10"
PATCH_TIMEOUT_SECONDS: Final[float] = 10.0
# Discord embed/content 본문 길이 안전 cap — 메시지 1건 content 2000자 제한.
DIRECTIVE_BODY_MAX_LEN: Final[int] = 1800
# 429 rate limit retry (#1068).
PATCH_429_MAX_RETRIES: Final[int] = 3
PATCH_429_FALLBACK_SLEEP_SECONDS: Final[float] = 1.0
# PATCH 호출 간 minimum delay — Discord per-route rate limit (5 req/sec) 회피 (#1068).
PATCH_MIN_INTERVAL_SECONDS: Final[float] = 0.25
# 404 mismatch 가 N 회 연속이면 영구 skip (status="stale"). 다음 PATCH 시도 안 함 (#1068).
MISMATCH_STALE_THRESHOLD: Final[int] = 3


# ─────────────────────────────────────────────────────────────────────────────
# data models
# ─────────────────────────────────────────────────────────────────────────────


@dataclass(frozen=True)
class DirectiveEntry:
    """directive-board.jsonl 1 라인.

    필드 누락 / 타입 이상 시 ``parse_directive_line`` 가 None 반환.
    """

    message_id: str
    ts: str
    summary: str
    status: str
    owner: str
    related: str
    last_updated_kst: str


@dataclass
class SyncResult:
    """``sync_once`` 한 번의 결과 — bot.py loop 가 로그/digest signature 에 사용."""

    scanned: int = 0
    patched: int = 0
    skipped: int = 0
    mismatched: int = 0
    errors: int = 0
    # 404 영구 skip — mismatch_count 가 임계 이상 누적된 entry (#1068).
    stale: int = 0
    # mismatch (404 등) 가 발생한 message_id 목록 — digest summary 가시화용.
    mismatched_ids: list[str] = field(default_factory=list)


# ─────────────────────────────────────────────────────────────────────────────
# jsonl parsing
# ─────────────────────────────────────────────────────────────────────────────


def parse_directive_line(line: str) -> DirectiveEntry | None:
    """jsonl 한 줄을 DirectiveEntry 로 파싱합니다. 실패 시 None.

    필수 필드: ``message_id`` (snowflake) + ``last_updated_kst``.
    그 외 (ts/summary/status/owner/related) 누락 시 빈 문자열로 fallback.
    """
    line = line.strip()
    if not line:
        return None
    try:
        data = json.loads(line)
    except (json.JSONDecodeError, TypeError):
        return None
    if not isinstance(data, dict):
        return None
    message_id = data.get("message_id")
    last_updated = data.get("last_updated_kst")
    if not isinstance(message_id, str) or not message_id.strip():
        return None
    if not isinstance(last_updated, str) or not last_updated.strip():
        return None

    def _str_field(key: str) -> str:
        value = data.get(key)
        return value.strip() if isinstance(value, str) else ""

    return DirectiveEntry(
        message_id=message_id.strip(),
        ts=_str_field("ts"),
        summary=_str_field("summary"),
        status=_str_field("status"),
        owner=_str_field("owner"),
        related=_str_field("related"),
        last_updated_kst=last_updated.strip(),
    )


def read_directive_board(path: Path) -> list[DirectiveEntry]:
    """jsonl 전체를 읽어 DirectiveEntry 리스트 반환. 파일 부재 시 빈 리스트."""
    if not path.exists():
        return []
    try:
        raw = path.read_text(encoding="utf-8")
    except OSError as exc:
        logger.warning("directive-board jsonl 읽기 실패 path=%s exc=%s", path, exc)
        return []
    entries: list[DirectiveEntry] = []
    for line in raw.splitlines():
        entry = parse_directive_line(line)
        if entry is not None:
            entries.append(entry)
    return entries


# ─────────────────────────────────────────────────────────────────────────────
# state (~/.mobruji/directive-board-sync.json)
# ─────────────────────────────────────────────────────────────────────────────


def read_state(path: Path) -> dict[str, dict[str, Any]]:
    """state 파일에서 ``{message_id: {"last_updated_kst": ..., "status": "ok|mismatch|stale", "mismatch_count": int}}`` 반환.

    파일 부재/깨짐 시 빈 dict 반환.

    ``mismatch_count`` (#1068): 404 누적 카운트. ``MISMATCH_STALE_THRESHOLD`` 이상이면
    ``status="stale"`` 영구 skip — 매 loop 마다 404 무한 반복 차단.
    """
    if not path.exists():
        return {}
    try:
        raw = path.read_text(encoding="utf-8")
        data = json.loads(raw)
    except (OSError, json.JSONDecodeError) as exc:
        logger.warning("directive-board-sync state 읽기 실패 path=%s exc=%s", path, exc)
        return {}
    if not isinstance(data, dict):
        return {}
    entries = data.get("entries")
    if not isinstance(entries, dict):
        return {}
    out: dict[str, dict[str, Any]] = {}
    for mid, info in entries.items():
        if not isinstance(mid, str) or not isinstance(info, dict):
            continue
        last = info.get("last_updated_kst")
        st = info.get("status")
        if not (isinstance(last, str) and isinstance(st, str)):
            continue
        record: dict[str, Any] = {"last_updated_kst": last, "status": st}
        mc = info.get("mismatch_count")
        if isinstance(mc, int) and mc >= 0:
            record["mismatch_count"] = mc
        else:
            record["mismatch_count"] = 0
        out[mid] = record
    return out


def write_state(state: dict[str, dict[str, Any]], path: Path) -> None:
    """atomic write. 권한 0600."""
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = {"entries": state}
    # tempfile + replace = atomic on POSIX.
    fd, tmp_path = tempfile.mkstemp(
        prefix=path.name + ".",
        suffix=".tmp",
        dir=str(path.parent),
    )
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as fp:
            json.dump(payload, fp, ensure_ascii=False, indent=2, sort_keys=True)
        os.chmod(tmp_path, STATE_FILE_MODE)
        os.replace(tmp_path, path)
    except Exception:
        # cleanup on failure.
        try:
            os.unlink(tmp_path)
        except OSError:
            pass
        raise


# ─────────────────────────────────────────────────────────────────────────────
# formatting
# ─────────────────────────────────────────────────────────────────────────────


def format_directive_body(entry: DirectiveEntry) -> str:
    """directive 본문 Discord 포맷.

    포맷 (사용자 P11 spec):
        📌 <summary>
        지시  <ts>
        상태  <status>
        담당  <owner>
        관련  <related>
        업데이트 <last_updated_kst>

    빈 필드는 ``-`` 로 대체. 길이 cap 적용.
    """

    def _safe(value: str) -> str:
        return value.strip() if value and value.strip() else "-"

    body = (
        f"📌 {_safe(entry.summary)}\n"
        f"지시  {_safe(entry.ts)}\n"
        f"상태  {_safe(entry.status)}\n"
        f"담당  {_safe(entry.owner)}\n"
        f"관련  {_safe(entry.related)}\n"
        f"업데이트 {_safe(entry.last_updated_kst)}"
    )
    if len(body) > DIRECTIVE_BODY_MAX_LEN:
        body = body[: DIRECTIVE_BODY_MAX_LEN - 1] + "…"
    return body


# ─────────────────────────────────────────────────────────────────────────────
# Discord PATCH
# ─────────────────────────────────────────────────────────────────────────────


PatchFunc = Callable[[str, str, str], "PatchResult"]


@dataclass(frozen=True)
class PatchResult:
    """``patch_message`` 반환.

    Attributes:
        status_code: HTTP status (200 OK / 404 Unknown Message / 0 = network error 등).
        ok: 2xx 면 True.
        mismatch: 404 (메시지 삭제) 시 True — sync_once 가 mismatch 카운트 누적.
        rate_limited: 429 retry 모두 소진 시 True (#1068).
    """

    status_code: int
    ok: bool
    mismatch: bool
    rate_limited: bool = False


def _sleep_for_retry_after(resp: Any) -> float:
    """429 응답에서 ``retry_after`` 추출 + sleep. fallback ``PATCH_429_FALLBACK_SLEEP_SECONDS``.

    Discord 응답 본문 예: ``{"retry_after": 0.3, "message": "You are being rate limited."}``.
    """
    delay = PATCH_429_FALLBACK_SLEEP_SECONDS
    try:
        body = resp.json() if hasattr(resp, "json") else {}
        if isinstance(body, dict):
            raw = body.get("retry_after")
            if isinstance(raw, (int, float)) and raw > 0:
                # Discord 가 가끔 0.3-0.6 같은 짧은 값을 반환하므로 그대로 따른다.
                # 다만 30s 초과는 비정상으로 보고 cap.
                delay = min(float(raw), 30.0)
    except (ValueError, AttributeError, json.JSONDecodeError):
        pass
    # Retry-After header fallback (RFC 7231).
    if hasattr(resp, "headers"):
        header_raw = resp.headers.get("Retry-After") if resp.headers else None
        if header_raw:
            try:
                header_delay = float(header_raw)
                if header_delay > 0:
                    delay = max(delay, min(header_delay, 30.0))
            except ValueError:
                pass
    time.sleep(delay)
    return delay


def patch_message(
    channel_id: str,
    message_id: str,
    body: str,
    *,
    token: str,
    timeout: float = PATCH_TIMEOUT_SECONDS,
    session: requests.Session | None = None,
    max_retries: int = PATCH_429_MAX_RETRIES,
) -> PatchResult:
    """Discord REST API PATCH ``/channels/{ch}/messages/{msg_id}``.

    응답:
      - 2xx: ok=True.
      - 404: mismatch=True (메시지 deleted 추정 — jsonl 만 남고 Discord 측 없음).
      - 429: ``retry_after`` sleep 후 최대 ``max_retries`` 회 재시도. 모두 소진 시
        rate_limited=True 반환 (#1068).
      - 그 외: ok=False, mismatch=False (network/auth 등).

    network exception 도 graceful — status_code=0 반환.
    """
    url = f"{DISCORD_API_BASE}/channels/{channel_id}/messages/{message_id}"
    headers = {
        "Authorization": f"Bot {token}",
        "Content-Type": "application/json",
    }
    payload = {"content": body}
    http = session or requests
    last_429_status = 0
    for attempt in range(max_retries + 1):
        try:
            resp = http.patch(url, headers=headers, json=payload, timeout=timeout)
        except requests.RequestException as exc:
            logger.warning(
                "directive PATCH network exception msg_id=%s exc=%s",
                message_id,
                exc,
            )
            return PatchResult(
                status_code=0, ok=False, mismatch=False, rate_limited=False
            )
        status = resp.status_code
        if 200 <= status < 300:
            return PatchResult(
                status_code=status, ok=True, mismatch=False, rate_limited=False
            )
        if status == 404:
            logger.warning(
                "directive PATCH 404 mismatch msg_id=%s — Discord 메시지 deleted 추정",
                message_id,
            )
            return PatchResult(
                status_code=status, ok=False, mismatch=True, rate_limited=False
            )
        if status == 429:
            last_429_status = status
            if attempt >= max_retries:
                logger.warning(
                    "directive PATCH 429 retry 소진 msg_id=%s attempts=%d",
                    message_id,
                    attempt + 1,
                )
                return PatchResult(
                    status_code=status,
                    ok=False,
                    mismatch=False,
                    rate_limited=True,
                )
            delay = _sleep_for_retry_after(resp)
            logger.info(
                "directive PATCH 429 retry msg_id=%s attempt=%d delay=%.2fs",
                message_id,
                attempt + 1,
                delay,
            )
            continue
        # 그 외 (4xx/5xx, 단 429/404 제외) — retry 안 함.
        logger.warning(
            "directive PATCH 실패 msg_id=%s status=%d body=%r",
            message_id,
            status,
            resp.text[:200] if hasattr(resp, "text") else "?",
        )
        return PatchResult(
            status_code=status, ok=False, mismatch=False, rate_limited=False
        )
    # 루프 정상 종료는 위에서 모두 return — 도달 불가.
    return PatchResult(
        status_code=last_429_status,
        ok=False,
        mismatch=False,
        rate_limited=True,
    )


# ─────────────────────────────────────────────────────────────────────────────
# sync_once
# ─────────────────────────────────────────────────────────────────────────────


def sync_once(
    *,
    channel_id: str,
    token: str,
    jsonl_path: Path = DEFAULT_JSONL_PATH,
    state_path: Path = DEFAULT_STATE_PATH,
    patch_func: PatchFunc | None = None,
    min_interval_seconds: float = PATCH_MIN_INTERVAL_SECONDS,
    sleep_func: Callable[[float], None] | None = None,
) -> SyncResult:
    """jsonl 1회 scan + 변경된 entry 만 PATCH + state 갱신.

    Args:
        channel_id: directive-board Discord 채널 id (DIRECTIVE_BOARD_CHANNEL_ID).
        token: Discord bot token (PATCH Authorization header).
        jsonl_path: directive-board.jsonl 경로.
        state_path: directive-board-sync state 경로.
        patch_func: 테스트 주입용. None 이면 실제 ``patch_message`` 호출.
        min_interval_seconds: PATCH 호출 사이 minimum delay (#1068). 0 이면 disable.
        sleep_func: 테스트 주입용 sleep. None 이면 ``time.sleep``.

    Returns:
        SyncResult — scanned/patched/skipped/mismatched/errors/stale 카운트.

    동작:
      - jsonl 의 각 entry 별로 state 의 last_updated_kst 와 비교.
      - 다르면 PATCH 호출 → 성공 시 state 갱신 (status="ok", mismatch_count=0).
      - 404 mismatch → state status="mismatch" 기록 + mismatch_count 증가
        + mismatched 카운트.
      - mismatch_count ≥ ``MISMATCH_STALE_THRESHOLD`` 이면 status="stale" 영구
        skip — 매 loop 마다 404 무한 반복 차단 (#1068).
      - 동일 last_updated_kst + 이전 status="ok" 면 skip (no-op).
      - 동일 last_updated_kst + 이전 status="stale" 면 영구 skip (404 가
        반복되는 메시지는 사용자가 ``DIRECTIVE_AUTO_RECREATE`` 또는 jsonl 수동
        조치 필요 — 별도 작업).
      - 동일 last_updated_kst + 이전 status="mismatch" + 임계 미만 면 retry —
        메시지 복구 가능성.
      - PATCH 호출 사이 ``min_interval_seconds`` sleep — Discord per-route rate
        limit (5 req/sec) 회피 (#1068).
    """
    result = SyncResult()
    entries = read_directive_board(jsonl_path)
    if not entries:
        return result
    state = read_state(state_path)
    new_state: dict[str, dict[str, Any]] = {k: dict(v) for k, v in state.items()}
    actor = patch_func or (
        lambda ch, mid, body: patch_message(
            ch, mid, body, token=token
        )
    )
    sleeper = sleep_func or time.sleep
    patch_call_count = 0

    for entry in entries:
        result.scanned += 1
        prev = state.get(entry.message_id)
        prev_last = prev.get("last_updated_kst") if prev else None
        prev_status = prev.get("status") if prev else None
        prev_mismatch_count_raw = prev.get("mismatch_count") if prev else 0
        prev_mismatch_count = (
            prev_mismatch_count_raw
            if isinstance(prev_mismatch_count_raw, int)
            else 0
        )

        # skip — unchanged + previously synced ok.
        if prev_last == entry.last_updated_kst and prev_status == "ok":
            result.skipped += 1
            continue

        # skip — unchanged + previously stale (#1068 영구 skip).
        if prev_last == entry.last_updated_kst and prev_status == "stale":
            result.stale += 1
            continue

        body = format_directive_body(entry)

        # PATCH 호출 간 minimum delay — Discord per-route rate limit 회피.
        if patch_call_count > 0 and min_interval_seconds > 0:
            sleeper(min_interval_seconds)

        try:
            patch_res = actor(channel_id, entry.message_id, body)
        except Exception as exc:  # noqa: BLE001
            logger.warning(
                "directive PATCH actor 예외 msg_id=%s exc=%s",
                entry.message_id,
                exc,
            )
            result.errors += 1
            patch_call_count += 1
            continue
        patch_call_count += 1

        if patch_res.ok:
            new_state[entry.message_id] = {
                "last_updated_kst": entry.last_updated_kst,
                "status": "ok",
                "mismatch_count": 0,
            }
            result.patched += 1
        elif patch_res.mismatch:
            # last_updated_kst 가 바뀌었으면 counter reset (사용자가 jsonl 수정 후
            # 회복 의도 추정). 같은 last_updated_kst 면 counter 증가.
            counter = (
                prev_mismatch_count + 1
                if prev_last == entry.last_updated_kst
                else 1
            )
            if counter >= MISMATCH_STALE_THRESHOLD:
                new_state[entry.message_id] = {
                    "last_updated_kst": entry.last_updated_kst,
                    "status": "stale",
                    "mismatch_count": counter,
                }
                result.stale += 1
                logger.warning(
                    "directive PATCH stale 영구 skip msg_id=%s mismatch_count=%d "
                    "— DIRECTIVE_AUTO_RECREATE 또는 jsonl 수동 조치 필요",
                    entry.message_id,
                    counter,
                )
            else:
                new_state[entry.message_id] = {
                    "last_updated_kst": entry.last_updated_kst,
                    "status": "mismatch",
                    "mismatch_count": counter,
                }
                result.mismatched += 1
                result.mismatched_ids.append(entry.message_id)
        else:
            # network/auth/429 retry 소진 — state 갱신 안 함, 다음 iter retry.
            result.errors += 1

    if new_state != state:
        try:
            write_state(new_state, state_path)
        except OSError as exc:
            logger.warning(
                "directive-board-sync state write 실패 path=%s exc=%s",
                state_path,
                exc,
            )
            result.errors += 1
    return result


# ─────────────────────────────────────────────────────────────────────────────
# summary (bot.py digest_loop / loop log)
# ─────────────────────────────────────────────────────────────────────────────


def directive_board_summary(state_path: Path = DEFAULT_STATE_PATH) -> dict[str, Any]:
    """state 파일을 읽어 사용자 가시화 summary 를 반환합니다.

    Returns:
        ``{"total": N, "ok": M, "mismatch": K, "stale": S, "mismatch_ids": [...], "stale_ids": [...]}``

    ``stale`` 은 404 영구 skip 된 entry 수 (#1068). digest signature 와 사용자 가시화에
    포함되어 manual 조치 (jsonl message_id 갱신 또는 DIRECTIVE_AUTO_RECREATE) 가 필요한
    entry 를 표면화한다.
    """
    state = read_state(state_path)
    total = len(state)
    ok_count = sum(1 for v in state.values() if v.get("status") == "ok")
    mismatch_ids = [
        mid for mid, v in state.items() if v.get("status") == "mismatch"
    ]
    stale_ids = [
        mid for mid, v in state.items() if v.get("status") == "stale"
    ]
    return {
        "total": total,
        "ok": ok_count,
        "mismatch": len(mismatch_ids),
        "stale": len(stale_ids),
        "mismatch_ids": mismatch_ids,
        "stale_ids": stale_ids,
    }
