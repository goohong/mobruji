"""directive-detect — 사용자 메시지 directive 분류 + 등록 mismatch 감지 (issue #1071).

설계 의도 (사용자 frustration "내가 지시한 거 왜 지시 forum에 추가 안해"):

1. bot.py `on_message` 에서 메시지 텍스트를 분류 (regex + heuristic) 후
   `~/.mobruji/directive-detect.jsonl` 에 한 줄 append. helper 가 turn 시작 시
   본 jsonl 로 누락 가능성을 사전에 인지.
2. watchdog (`directive_register_watch_loop`) 10분 주기:
   - 최근 1h directive-detect.jsonl 에서 `class in {"directive", "directive-ambiguous"}`
     카운트.
   - 같은 1h 동안 `directive-board.jsonl` 에서 신규 thread 카운트.
   - mismatch (detect > board + grace) 시 MOBRUJI_CHANNEL_ID (사용자 응답 채널) 로
     자율 알림 push.

`helper-queue.jsonl` 은 helper 본체가 단일 writer 라서 (race 회피) bot.py 가
patch 하지 않고 별도 jsonl 로 분리. helper 가 turn 안에서 두 jsonl 을 cross-ref.

본 모듈은 pure (network/disk side-effect 는 caller 의무 — 단위 테스트 용이).
"""
from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Iterable

# directive 패턴: 구체 행동 요청. paraphrase: "X 해줘 / 추가해 / 수정해 / 진행해 / 등록해 / 분리해 / 정정 / 박제 / 폐기".
_DIRECTIVE_PATTERNS: tuple[str, ...] = (
    # 종결 어미
    r"해줘$|해주세요$|해라$|해\?$|해야해$|해야합니다$",
    r"진행해|진행해줘|진행하|진행 승인|진행 시작|시작해|개시",
    # 동사 키워드
    r"추가|제거|삭제|수정|변경|정정|박제|폐기|분리|강화|간소화|단순화|reduce|cleanup",
    r"만들어|신설|등록|작성|개설|구축|설치|구성|구현",
    r"위임|launch|실행해|돌려|실행해줘",
    r"방법.{0,5}(제안|찾|고민|결정)|해결|해결책|결정해|결정해줘",
    r"필요해$|필요합|필요하다$|의무$|강제$",
    r"권유|권고|요구|부탁",
    r"하자$|돌리자$|가자$",
    r"바꿔|개선해|개편해",
)

# query 패턴: 의문문 / 확인 요청.
_QUERY_PATTERNS: tuple[str, ...] = (
    # 명확한 의문 종결
    r"\?$",
    r"왜\s|왜\?$|어떻게|무엇|뭐\s|뭐야$|뭐니$|뭐\?$|뭘\s|어디|언제|얼마",
    # 의문어 (의문부호 없어도 query) — "지금 뭐하고 있니" 같은 자연어 의문문
    r"있니$|있나$|있어\?$|되니$|되나$|되었니$|되었나$|됐니$|됐나$|됐어\?$",
    r"~니\?$|~까\?$|~지\?$|~나\?$|~냐\?$|~을까\?$|~는데\?$",
    r"여부$|확인.{0,10}(부탁|해|할)$|상태.{0,10}(보고|확인)$|진행 상황$|진행상황$|어디까지$",
    r"맞아\?$|되니\?$|있어\?$|있니\?$|됐어\?$|됐니\?$",
)

_DIRECTIVE_RE = re.compile("|".join(_DIRECTIVE_PATTERNS))
_QUERY_RE = re.compile("|".join(_QUERY_PATTERNS))

# 분류 결과 enum (string — JSON friendly).
CLASS_DIRECTIVE = "directive"
CLASS_DIRECTIVE_MIXED = "directive-mixed"
CLASS_DIRECTIVE_AMBIGUOUS = "directive-ambiguous"
CLASS_QUERY = "query"
CLASS_CONVERSATION = "conversation"

# helper 가 forum 등록 필요로 간주할 class.
DIRECTIVE_CLASSES: frozenset[str] = frozenset(
    {CLASS_DIRECTIVE, CLASS_DIRECTIVE_MIXED, CLASS_DIRECTIVE_AMBIGUOUS}
)


def classify(text: str) -> str:
    """단일 메시지를 directive / query / conversation 으로 분류.

    Boundary 케이스는 directive 로 분류 (false-positive safe — 사용자 frustration
    "왜 지시 forum 에 추가 안해" 가드).
    """
    if text is None:
        return CLASS_CONVERSATION
    stripped = text.strip()
    if not stripped:
        return CLASS_CONVERSATION

    has_directive = bool(_DIRECTIVE_RE.search(stripped))
    has_query = bool(_QUERY_RE.search(stripped))

    if has_directive and has_query:
        return CLASS_DIRECTIVE_MIXED
    if has_directive:
        return CLASS_DIRECTIVE
    if has_query:
        return CLASS_QUERY
    # 인사 / 짧은 정정 / 응답 — 너무 짧으면 conversation.
    if len(stripped) <= 5:
        return CLASS_CONVERSATION
    # 모호 — directive 분류 (사용자 frustration 가드)
    return CLASS_DIRECTIVE_AMBIGUOUS


def summarize(text: str, max_len: int = 60) -> str:
    """첫 max_len 자 paraphrase summary — internal_id 라벨 안 박음 (사용자 가시)."""
    cleaned = re.sub(r"\s+", " ", (text or "").strip())
    if len(cleaned) > max_len:
        return cleaned[: max_len - 1] + "…"
    return cleaned


def make_detect_entry(
    *,
    message_id: str,
    ts_iso: str,
    text: str,
    channel_id: str | None = None,
) -> dict:
    """jsonl 1줄 entry dict 생성."""
    cls = classify(text)
    return {
        "ts": ts_iso,
        "message_id": message_id,
        "channel_id": channel_id or "",
        "text": text or "",
        "class": cls,
        "summary": summarize(text or ""),
    }


def append_detect_entry(path: Path, entry: dict) -> None:
    """`~/.mobruji/directive-detect.jsonl` append (race 없음 — bot.py 단일 writer).

    실패는 caller 책임. 본 모듈은 OSError 를 raise 한다.
    """
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as fh:
        fh.write(json.dumps(entry, ensure_ascii=False) + "\n")


# ---- watchdog: directive register mismatch ----

@dataclass
class MismatchSnapshot:
    """watchdog 한 iter 의 mismatch 검출 결과."""
    window_minutes: int = 60
    detect_count: int = 0
    board_count: int = 0
    mismatch: bool = False
    sample_summaries: list[str] = field(default_factory=list)


def _parse_iso(ts: str) -> datetime | None:
    """ISO 8601 (Z 또는 +00:00) parsing — fail-soft None 반환."""
    if not ts:
        return None
    try:
        # KST 표기 ("2026-05-24 12:31 KST") 호환
        if " KST" in ts:
            ts2 = ts.replace(" KST", "+09:00")
            return datetime.fromisoformat(ts2)
        return datetime.fromisoformat(ts.replace("Z", "+00:00"))
    except ValueError:
        return None


def _read_jsonl(path: Path) -> Iterable[dict]:
    if not path.exists():
        return
    with path.open(encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            try:
                yield json.loads(line)
            except json.JSONDecodeError:
                continue


def detect_mismatch(
    *,
    detect_path: Path,
    board_path: Path,
    now: datetime,
    window_minutes: int = 60,
    grace_count: int = 5,
) -> MismatchSnapshot:
    """1h 윈도우 안 directive vs board 신규 thread 카운트 비교.

    Args:
        detect_path: directive-detect.jsonl
        board_path: directive-board.jsonl
        now: 현재 시각 (timezone-aware)
        window_minutes: 윈도우 길이 (default 60)
        grace_count: detect 가 board 보다 X 건 이상 많을 때만 mismatch
            (5분 grace 의 의미 — 사용자가 메시지 보낸 직후 helper turn 안에서
            5분 미만 grace 안에서는 forum 등록이 아직 진행 중일 수 있음).

    Returns:
        MismatchSnapshot — caller (watchdog) 가 push 여부 결정.
    """
    window_start = now - timedelta(minutes=window_minutes)
    detect_count = 0
    sample: list[str] = []
    for entry in _read_jsonl(detect_path):
        if entry.get("class") not in DIRECTIVE_CLASSES:
            continue
        ts = _parse_iso(entry.get("ts", ""))
        if ts is None or ts < window_start:
            continue
        detect_count += 1
        if len(sample) < 3:
            sample.append(entry.get("summary", "")[:50])

    board_count = 0
    for entry in _read_jsonl(board_path):
        ts = _parse_iso(entry.get("ts", "")) or _parse_iso(
            entry.get("last_updated_kst", "")
        )
        if ts is None or ts < window_start:
            continue
        board_count += 1

    mismatch = detect_count > (board_count + grace_count)
    return MismatchSnapshot(
        window_minutes=window_minutes,
        detect_count=detect_count,
        board_count=board_count,
        mismatch=mismatch,
        sample_summaries=sample,
    )


def format_mismatch_push(snapshot: MismatchSnapshot) -> str:
    """mismatch 알림 본문 — #모부르지 (사용자 응답) 채널 push 용 정중체.

    Discord 정중체 룰 ([[feedback-discord-tone-formal]]) 준수: "~합니다 / ~할까요".
    줄임 표현 / 비문 금지.
    """
    sample_lines = "\n".join(f"  - {s}" for s in snapshot.sample_summaries) or "  (없음)"
    return (
        f":warning: directive forum 등록 누락 의심 — 최근 {snapshot.window_minutes}분"
        f" detect {snapshot.detect_count}건 vs board 신규 {snapshot.board_count}건.\n"
        f"최근 directive 후보:\n{sample_lines}\n"
        f"helper 본체가 backfill 또는 분류 정정을 진행하겠습니다."
    )
