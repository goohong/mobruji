"""forum_template_validator 단위 테스트 (PR F, #1362).

대상: `tools/discord-daemon/lib/forum_template_validator.py`
spec: `docs/features/forum-starter-template-guard.md §5-2·§5-7`

검증 범위:
1. 6 marker 매칭 — 6/6 ideal / 5/6 graceful / ≤4/6 fail.
2. marker 순서 무관 / variant 호환 (📌 vs 🛠️ / 진행 안 상태 변형).
3. empty body / 매우 긴 body / 일부 marker 변형.
4. format_alert 출력 양식 (사용자 가시 1줄).
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

PARENT_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PARENT_DIR))

from lib.forum_template_validator import (  # noqa: E402
    PASS_THRESHOLD,
    TEMPLATE_MARKERS,
    TOTAL_MARKERS,
    ValidationResult,
    format_alert,
    validate,
)


# ─── fixture body ────────────────────────────────────────────────────────────


CYCLE_TEMPLATE_BODY_6_OF_6 = """\
🛠️ **be 사이클 #1234 — register_directive_pending kind 확장**

💬 본문
> directive 등록 시 PR review / PR audit 분기 신설

🆔 cycle=be-2026-05-30-001 · directive=msg_999

📋 진행
- [x] launch
- [x] 분석 / 설계 — kind enum 3 추가
- [x] 구현 — branch=feat/...
- [x] 검증 — checkstyle+spotless+test green
- [x] PR 생성 — #1234
- [ ] PR 머지

🔖 관련
- PR: #1234
- directive: msg_999

---
_갱신: 2026-05-30T12:34:56+09:00_
"""


DIRECTIVE_TEMPLATE_BODY_6_OF_6 = """\
📌 **포럼 작업 갱신 양식 가드 필요**

💬 원본
> 포럼 작업 갱신한 거 보니까 양식 무시하고 PR 링크만 띡

🆔 `1509466456230989926` · 👤 <@123456> · 🕐 2026-05-30T12:00:00+09:00

📋 진행 (🟡 대기)
- [ ] 분석 / 위임 결정
- [ ] 실행
- [ ] 결과 반영

🔖 관련 *(없음 — 진행되며 helper 가 추가)*

---
_갱신: 2026-05-30T12:00:00+09:00_
"""


# 5/6 — 🆔 line 만 누락 (옛 directive entry 호환).
GRACEFUL_BODY_5_OF_6 = """\
📌 **타이틀 정제됨**

💬 원본
> 원본 본문

📋 진행 (🔵 진행 중)
- [x] 분석 / 위임 결정

🔖 관련
- PR: #1234

---
_갱신: 2026-05-30T13:00:00+09:00_
"""


VIOLATION_PR_LINK_ONLY = "PR #1234 작업 끝"
VIOLATION_PLAIN_COMPLETION = "## 완료\n\n링크: https://github.com/foo/bar/pull/1234"
VIOLATION_TITLE_ONLY = "🛠️ **작업 완료**\n\nPR #1234"


# ─── ValidationResult dataclass ──────────────────────────────────────────────


class ValidationResultDataclassTests(unittest.TestCase):
    """반환 객체 invariant 확인 (frozen + 필드 명)."""

    def test_returns_validation_result_instance(self) -> None:
        result = validate("anything")
        self.assertIsInstance(result, ValidationResult)
        self.assertIsInstance(result.passed, bool)
        self.assertIsInstance(result.matched, int)
        self.assertIsInstance(result.missing, list)


# ─── 정상 case (≥5/6) ───────────────────────────────────────────────────────


class PassingBodyTests(unittest.TestCase):
    """≥5 marker 매칭 body — passed=True."""

    def test_cycle_template_6_of_6_passes(self) -> None:
        result = validate(CYCLE_TEMPLATE_BODY_6_OF_6)
        self.assertTrue(result.passed, f"missing={result.missing}")
        self.assertEqual(result.matched, TOTAL_MARKERS)
        self.assertEqual(result.missing, [])

    def test_directive_template_6_of_6_passes(self) -> None:
        result = validate(DIRECTIVE_TEMPLATE_BODY_6_OF_6)
        self.assertTrue(result.passed, f"missing={result.missing}")
        self.assertEqual(result.matched, TOTAL_MARKERS)

    def test_graceful_5_of_6_id_missing_passes(self) -> None:
        result = validate(GRACEFUL_BODY_5_OF_6)
        self.assertTrue(result.passed, f"missing={result.missing}")
        self.assertEqual(result.matched, PASS_THRESHOLD)
        self.assertEqual(result.missing, ["id_line"])

    def test_marker_order_swap_still_passes(self) -> None:
        # marker 순서 무관 검증 — 본 body 는 footer 가 위, title 이 아래.
        body = (
            "---\n_갱신: 2026-05-30T15:00:00+09:00_\n\n"
            "🔖 관련\n- PR: #1234\n\n"
            "📋 진행\n- [x] launch\n\n"
            "🆔 cycle=be-001\n\n"
            "💬 본문\n> 본문\n\n"
            "🛠️ **타이틀**\n"
        )
        result = validate(body)
        self.assertTrue(result.passed, f"missing={result.missing}")
        self.assertEqual(result.matched, TOTAL_MARKERS)

    def test_progress_section_variant_passes(self) -> None:
        # `📋 진행 (🟡 대기)` / `📋 진행 (🔵 진행 중)` 등 상태 변형 호환.
        body_template = """\
📌 **타이틀**

💬 원본
> 본문

🆔 id

📋 진행 (🟣 결정 대기)
- [x] 1

🔖 관련
- 후속 directive

---
_갱신: now_
"""
        result = validate(body_template)
        self.assertTrue(result.passed, f"missing={result.missing}")
        self.assertEqual(result.matched, TOTAL_MARKERS)

    def test_long_body_above_10000_chars_still_passes(self) -> None:
        padding = "추가 내용\n" * 2000
        body = CYCLE_TEMPLATE_BODY_6_OF_6 + "\n\n" + padding
        self.assertGreater(len(body), 10000)
        result = validate(body)
        self.assertTrue(result.passed, f"missing={result.missing}")

    def test_footer_raw_not_italic_passes(self) -> None:
        # `_갱신:` italic 이 아닌 raw `갱신:` 도 footer marker 호환.
        body = """\
📌 **타이틀**

💬 원본
> 본문

🆔 id

📋 진행
- [x] 1

🔖 관련
- PR

---
갱신: 2026-05-30T15:00:00+09:00
"""
        result = validate(body)
        self.assertTrue(result.passed, f"missing={result.missing}")


# ─── 위배 case (≤4/6 → fail) ────────────────────────────────────────────────


class FailingBodyTests(unittest.TestCase):
    """≤4 marker 매칭 body — passed=False."""

    def test_empty_body_fails(self) -> None:
        result = validate("")
        self.assertFalse(result.passed)
        self.assertEqual(result.matched, 0)
        self.assertEqual(set(result.missing), set(TEMPLATE_MARKERS.keys()))

    def test_pr_link_only_fails(self) -> None:
        result = validate(VIOLATION_PR_LINK_ONLY)
        self.assertFalse(result.passed)
        self.assertEqual(result.matched, 0)

    def test_plain_completion_fails(self) -> None:
        result = validate(VIOLATION_PLAIN_COMPLETION)
        self.assertFalse(result.passed)
        self.assertEqual(result.matched, 0)

    def test_title_only_fails(self) -> None:
        result = validate(VIOLATION_TITLE_ONLY)
        self.assertFalse(result.passed)
        # title_prefix 1개만 매칭.
        self.assertEqual(result.matched, 1)
        self.assertIn("title_prefix", set(TEMPLATE_MARKERS.keys()) - set(result.missing))

    def test_three_markers_below_threshold_fails(self) -> None:
        # 경계 — 3 marker 만 매칭 (PASS_THRESHOLD = 5).
        body = "🛠️ **타이틀**\n\n📋 진행\n- [x] 1\n\n🔖 관련\n- PR"
        result = validate(body)
        self.assertFalse(result.passed)
        self.assertEqual(result.matched, 3)

    def test_four_markers_below_threshold_fails(self) -> None:
        # 경계 — 4 marker 만 매칭 (PASS_THRESHOLD = 5).
        body = (
            "🛠️ **타이틀**\n\n💬 본문\n> 본문\n\n"
            "📋 진행\n- [x] 1\n\n🔖 관련\n- PR"
        )
        result = validate(body)
        self.assertFalse(result.passed)
        self.assertEqual(result.matched, 4)


# ─── format_alert 출력 ──────────────────────────────────────────────────────


class FormatAlertTests(unittest.TestCase):
    """DIGEST 채널 push 본문 1줄 양식."""

    def test_alert_contains_thread_id_and_matched_ratio(self) -> None:
        alert = format_alert(thread_id=1509466456230989926, matched=2, missing=["id_line", "footer_update"])
        self.assertIn("1509466456230989926", alert)
        self.assertIn(f"2/{TOTAL_MARKERS}", alert)
        self.assertIn("id_line", alert)
        self.assertIn("footer_update", alert)

    def test_alert_handles_empty_missing_list(self) -> None:
        alert = format_alert(thread_id=1, matched=TOTAL_MARKERS, missing=[])
        self.assertIn("(없음)", alert)


# ─── module-level constants invariants ──────────────────────────────────────


class ModuleConstantTests(unittest.TestCase):
    """PASS_THRESHOLD / TOTAL_MARKERS / TEMPLATE_MARKERS 일치성."""

    def test_total_markers_matches_dict_length(self) -> None:
        self.assertEqual(TOTAL_MARKERS, len(TEMPLATE_MARKERS))

    def test_pass_threshold_below_total(self) -> None:
        # 5/6 graceful — strict 6/6 이 아님을 보장.
        self.assertLess(PASS_THRESHOLD, TOTAL_MARKERS)
        self.assertGreaterEqual(PASS_THRESHOLD, 1)

    def test_template_markers_includes_six_keys(self) -> None:
        expected = {
            "title_prefix",
            "body_section",
            "id_line",
            "progress_section",
            "related_section",
            "footer_update",
        }
        self.assertEqual(set(TEMPLATE_MARKERS.keys()), expected)


if __name__ == "__main__":  # pragma: no cover
    unittest.main()
