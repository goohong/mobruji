"""directive_starter_status.transform 단위 테스트 (#1419).

검증: directive forum starter 상태 전이가 '내용 보존형'으로 동작하는가.
과거 --update-status 가 본문을 통째 덮어써 📌 제목·💬 요약·🔖 관련이
소멸한 사고(2026-05-31 사용자 정정)의 회귀 가드.
"""
from __future__ import annotations

import importlib.util
from pathlib import Path

_MOD_PATH = Path(__file__).resolve().parent.parent / "directive_starter_status.py"
_spec = importlib.util.spec_from_file_location("directive_starter_status", _MOD_PATH)
assert _spec and _spec.loader
dss = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(dss)

TEMPLATE = (
    "📌 **추천 API 400 fix**\n\n"
    "💬 요약\n노래방 추천 API 가 400 을 반환하는 버그.\n\n"
    "🆔 `123` · 🕐 2026-05-31 10:00 KST\n\n"
    "📋 진행 (🟡 대기)\n- [ ] 분석 / 위임 결정\n- [ ] 실행\n- [ ] 결과 반영\n\n"
    "🔖 관련 *(없음 — 진행되며 helper 가 추가)*\n\n"
    "---\n_갱신: 2026-05-31 10:00 KST_"
)


def test_완료_전이_내용_보존_체크_PR보강():
    out = dss.transform(
        TEMPLATE, "완료", "2026-05-31 16:00 KST",
        "https://github.com/goohong/mobruji/pull/1413",
    )
    # given/when/then — 제목·요약 보존
    assert "📌 **추천 API 400 fix**" in out
    assert "노래방 추천 API 가 400 을 반환하는 버그." in out
    assert "🆔 `123`" in out
    # 상태 헤더 전이
    assert "📋 진행 (🟢 완료)" in out
    assert "🟡 대기" not in out
    # 완료 시 체크박스 전부 체크
    assert "- [ ]" not in out
    assert out.count("- [x]") == 3
    # PR 보강 — 닫는 italic '*' 없이 깔끔하게
    assert "- PR: https://github.com/goohong/mobruji/pull/1413" in out
    assert "pull/1413*" not in out
    # 갱신 줄
    assert "_갱신: 2026-05-31 16:00 KST · 상태 갱신_" in out


def test_진행중_전이_체크박스_유지():
    out = dss.transform(TEMPLATE, "진행 중", "2026-05-31 16:00 KST", "")
    assert "📋 진행 (🔵 진행 중)" in out
    # 진행 중은 체크박스 자동 체크 안 함
    assert "- [ ] 분석 / 위임 결정" in out


def test_PR_중복_미보강():
    once = dss.transform(TEMPLATE, "진행 중", "t", "https://x/pull/1")
    twice = dss.transform(once, "완료", "t2", "https://x/pull/1")
    assert twice.count("- PR: https://x/pull/1") == 1


def test_legacy_빈템플릿_원문_보존():
    out = dss.transform("사용자가 쓴 평문", "진행 중", "2026-05-31 16:00 KST", "")
    assert "사용자가 쓴 평문" in out
    assert "📋 진행 (🔵 진행 중)" in out


def test_미지_상태_기본_emoji():
    out = dss.transform(TEMPLATE, "알수없음", "t", "")
    assert "📋 진행 (🔵 알수없음)" in out
