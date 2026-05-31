"""Forum starter template validator (PR F, spec §5-2 SoT).

`forum_edit_starter` 호출 body 가 directive / cycle 양식 marker 를 유지하는지
검증. bot.py `_forum_edit_starter` 가 starter.edit 호출 직전 본 모듈의
`validate(body)` 를 호출 → graceful reject 분기.

template marker SoT — `docs/features/directive-board-template-and-tags.md §5-2·§5-3`
와 `tools/agent-launch-wrapper.sh:_build_cycle_template_body` 에서 박힌 6 marker:
- 📌 (또는 🛠️ — cycle forum 양식 호환) title prefix
- 💬 원본 또는 본문 섹션
- 🆔 directive_id / cycle id line
- 📋 진행 (체크박스 섹션)
- 🔖 관련 (관련 항목 섹션)
- footer (`---` + `_갱신:` 또는 `갱신:` KST 시각)

PASS_THRESHOLD = 5 / 6 — graceful (marker 1개 누락 허용). 사유:
- 6/6 strict 시 기존 옛 template (🆔 또는 💬 line 누락) 도 reject — 회귀 위험.
- ≤4/6 = 양식 위배 의심 (reject + alert).

rev sub-agent 보완 사항 박제 (mmae 결정):
- `💬` marker 도 포함 (5 marker → 6 marker) — template SoT 양식 정렬.
- footer marker 단독 검사 — `--- \n갱신:` 누락 starter graceful reject.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Final


# ─── 6 marker regex SoT ─────────────────────────────────────────────────────


# title prefix — 📌 (directive) 또는 🛠️ (cycle forum) 둘 다 매칭.
# `^` multi-line — body 첫 줄 또는 안쪽 어디서나 매칭 (cycle template 의
# `🛠️ **{cycle_title}**` 양식 호환).
_TITLE_PREFIX_RE: Final[re.Pattern[str]] = re.compile(
    r"(📌|🛠️)\s*\*\*.+\*\*",
)

# 본문 / 원본 섹션 — 💬 prefix.
_BODY_SECTION_RE: Final[re.Pattern[str]] = re.compile(r"💬")

# id line — 🆔 prefix.
_ID_LINE_RE: Final[re.Pattern[str]] = re.compile(r"🆔")

# 진행 섹션 — 📋 + "진행".
_PROGRESS_SECTION_RE: Final[re.Pattern[str]] = re.compile(r"📋\s*진행")

# 관련 섹션 — 🔖 + "관련".
_RELATED_SECTION_RE: Final[re.Pattern[str]] = re.compile(r"🔖\s*관련")

# footer — `---` separator + 갱신 시각 line. `_갱신:` (italic) 또는 `갱신:`
# (raw) 둘 다 허용. graceful — newline 양은 1+ (cat HEREDOC 출력 호환).
_FOOTER_RE: Final[re.Pattern[str]] = re.compile(
    r"---[\s\S]*?_?갱신:\s+",
)


# marker name → regex 매핑. 호출자 (테스트 / 로그) 가 이름 기반 식별 가능.
TEMPLATE_MARKERS: Final[dict[str, re.Pattern[str]]] = {
    "title_prefix": _TITLE_PREFIX_RE,
    "body_section": _BODY_SECTION_RE,
    "id_line": _ID_LINE_RE,
    "progress_section": _PROGRESS_SECTION_RE,
    "related_section": _RELATED_SECTION_RE,
    "footer_update": _FOOTER_RE,
}

# pass 기준 — N=6 중 ≥5 매칭 (graceful, marker 1개 누락 허용).
PASS_THRESHOLD: Final[int] = 5
TOTAL_MARKERS: Final[int] = len(TEMPLATE_MARKERS)


@dataclass(frozen=True)
class ValidationResult:
    """validate 반환 객체.

    Attributes
    ----------
    passed : bool
        매칭 marker count >= PASS_THRESHOLD.
    matched : int
        매칭 marker count (0 ~ TOTAL_MARKERS).
    missing : list[str]
        매칭 실패 marker name list. PASS_THRESHOLD 도달 시도 graceful 1개
        누락 marker 가 박힐 수 있음.
    """

    passed: bool
    matched: int
    missing: list[str] = field(default_factory=list)


def validate(body: str) -> ValidationResult:
    """body 가 6 marker 중 PASS_THRESHOLD 이상 매칭 여부 판단.

    Parameters
    ----------
    body : str
        forum thread starter message body. empty / None 동작:
        empty 는 0/6 fail. None 은 호출자가 변환 의무 (signature str only).

    Returns
    -------
    ValidationResult
        passed=True 시 starter.edit 진행 가능. passed=False 시 호출자가
        graceful reject 분기 (starter 보존 + warning + alert).
    """
    if not body:
        return ValidationResult(
            passed=False, matched=0, missing=list(TEMPLATE_MARKERS.keys()),
        )

    missing_markers: list[str] = []
    matched_count = 0
    for marker_name, pattern in TEMPLATE_MARKERS.items():
        if pattern.search(body):
            matched_count += 1
        else:
            missing_markers.append(marker_name)
    return ValidationResult(
        passed=matched_count >= PASS_THRESHOLD,
        matched=matched_count,
        missing=missing_markers,
    )


def format_alert(thread_id: int, matched: int, missing: list[str]) -> str:
    """validation reject 시 DIGEST 채널 alert 1줄 (한국어 사용자 가시).

    Parameters
    ----------
    thread_id : int
        위배된 forum thread id (Discord snowflake).
    matched : int
        매칭 marker count.
    missing : list[str]
        매칭 실패 marker name list.

    Returns
    -------
    str
        DIGEST 채널 push 본문 1줄.
    """
    missing_label = ", ".join(missing) if missing else "(없음)"
    return (
        f"🚨 forum template 위배 — thread {thread_id} "
        f"matched {matched}/{TOTAL_MARKERS} missing {missing_label}"
    )
