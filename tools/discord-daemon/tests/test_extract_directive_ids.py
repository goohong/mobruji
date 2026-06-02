"""extract_directive_ids_from_body 단위 테스트 (#1473).

사고 (#1473): `DIRECTIVE_PR_BODY_RE` 가 `directive[:\\s]+`(콜론 또는 공백)로
PR 본문 **산문 중간**의 'directive <id>' 언급까지 매칭 → #1451 이 다른 directive
(1511193376639422656)를 자기 live-test 예시로 산문에 적은 것을 오매칭해, 머지 시
그 directive 를 #1451 로 잘못 완료·링크(실제 fix #1454 가려짐).

fix: 줄 시작 앵커(`^[ \\t]*` + re.MULTILINE)로 정식 trailer 줄만 매칭.
"""
from __future__ import annotations

import sys
from pathlib import Path
from unittest import mock

PARENT_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PARENT_DIR))

try:
    import discord as _real_discord  # noqa: F401
except ImportError:
    sys.modules["discord"] = mock.MagicMock()
for _missing in ("requests", "dotenv"):
    if _missing not in sys.modules:
        _stub = mock.MagicMock()
        if _missing == "dotenv":
            _stub.load_dotenv = lambda *a, **kw: None
        sys.modules[_missing] = _stub

import bot  # noqa: E402


DIRECTIVE_ID = "1511193376639422656"


def test_canonical_trailer_matches():
    # #1454 형태 — 정식 trailer.
    body = "## AS-IS\n...\n\ndirective: 1511193376639422656\n\n🤖 Generated"
    assert bot.extract_directive_ids_from_body(body) == [DIRECTIVE_ID]


def test_closes_directive_trailer_matches():
    body = "본문\n\nCloses directive 1511193376639422656\n"
    assert bot.extract_directive_ids_from_body(body) == [DIRECTIVE_ID]


def test_indented_trailer_matches():
    body = "리스트\n    directive: 1511193376639422656\n"
    assert bot.extract_directive_ids_from_body(body) == [DIRECTIVE_ID]


def test_prose_midline_mention_does_not_match():
    # #1451 회귀 가드 — 산문 중간 'directive <id>' 언급은 매칭 X.
    body = (
        "- 근거: live 테스트 directive 1511193376639422656 의 "
        "state thread_id(dialogue 1511193247568101407) ≠ board thread_id"
        "(forum 1511193616947744788) 확인"
    )
    assert bot.extract_directive_ids_from_body(body) == []


def test_prose_with_other_ids_not_picked():
    # 산문 줄의 다른 thread id 들도 directive 로 오인하지 않는다.
    body = "see directive 1511193376639422656 in another PR — not a trailer here"
    assert bot.extract_directive_ids_from_body(body) == []


def test_empty_body():
    assert bot.extract_directive_ids_from_body("") == []
