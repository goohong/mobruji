"""_merged_since_qualifier 단위 테스트 (#1436).

GitHub 검색이 'merged:>1h ago' 상대 문법을 지원 안 해 머지 자동화가 0건 매칭으로
한 번도 안 돌던 사고의 회귀 가드. 절대 날짜 qualifier 를 생성하는지 검증.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import bot  # noqa: E402

ISO_RE = re.compile(r"^merged:>=\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\+00:00$")


def test_valid_window_produces_absolute_date():
    q = bot._merged_since_qualifier("1h")
    assert ISO_RE.match(q), q
    # 옛 무효 문법이 아니어야 한다
    assert "ago" not in q
    assert "merged:>1h" not in q


def test_larger_window_has_earlier_cutoff():
    h1 = bot._merged_since_qualifier("1h")
    d7 = bot._merged_since_qualifier("7d")
    # 7일 전이 1시간 전보다 더 과거(문자열 비교로도 ISO 는 시간순)
    assert d7 < h1


def test_units_parsed():
    for w in ("30m", "24h", "7d"):
        assert ISO_RE.match(bot._merged_since_qualifier(w)), w


def test_invalid_window_falls_back_1h():
    q = bot._merged_since_qualifier("garbage")
    assert ISO_RE.match(q), q  # 예외 없이 fallback
