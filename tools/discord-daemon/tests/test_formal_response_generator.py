"""정형 응답 generator 단위 테스트 (#797).

`formal_ack` / `formal_status` / `formal_error` / `formal_digest` 네 함수가
deterministic 하게 정중체 메시지를 조립하는지 검증.

종결 어미는 `~합니다 / ~했습니다 / ~할까요?` 만 사용해야 한다.
"""

from __future__ import annotations

import re
import sys
import unittest
from pathlib import Path
from unittest import mock

PARENT_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PARENT_DIR))

for missing in ("discord", "requests", "dotenv"):
    if missing not in sys.modules:
        stub = mock.MagicMock()
        if missing == "dotenv":
            stub.load_dotenv = lambda *a, **kw: None
        sys.modules[missing] = stub

import bot  # noqa: E402

# 금지 종결 어미 패턴 — 비속체 차단 (#797).
# `받음`, `옴`, `끝`, `켤까`, `볼까` 등이 메시지에 그대로 들어가면 안 된다.
FORBIDDEN_TERMINATIONS = (
    "받음",
    "처리 중$",  # 마침표 없는 단순 종결
    "켤까",
    "끝\\.",
    "옴\\.",
)


def _assert_polite(test: unittest.TestCase, text: str) -> None:
    """text 에 금지 비속체 종결 어미가 포함되지 않았는지 단언."""
    for forbidden in FORBIDDEN_TERMINATIONS:
        test.assertIsNone(
            re.search(forbidden, text),
            f"비속체 종결 어미 발견 ({forbidden!r}): {text!r}",
        )


class FormalAckTests(unittest.TestCase):
    """formal_ack — 수신 확인 정중체."""

    def test_basic_ack_contains_queue_and_eta(self) -> None:
        out = bot.formal_ack(queue_n=3, eta_min=5)
        self.assertIn("queue: 3", out)
        self.assertIn("ETA 5분", out)
        self.assertIn("받았습니다", out)
        self.assertIn("처리 중입니다", out)
        _assert_polite(self, out)

    def test_sub_agent_active_eta_range(self) -> None:
        out = bot.formal_ack(queue_n=1, eta_min=5, sub_agent_active=True)
        self.assertIn("5-15분", out)
        self.assertIn("sub-agent 가동 중", out)
        _assert_polite(self, out)

    def test_starts_with_reply_emoji(self) -> None:
        out = bot.formal_ack(queue_n=1)
        self.assertTrue(out.startswith(bot.MESSAGE_PREFIX["reply"]))


class FormalStatusTests(unittest.TestCase):
    """formal_status — 작업 상황 구조화 정중체."""

    def test_all_sections_present(self) -> None:
        out = bot.formal_status(
            work_in_progress=["PR #797 review 대기"],
            work_completed=["PR #788 머지 완료"],
            pending_decisions=["NCP secret 갱신 여부"],
        )
        self.assertIn("진행 중", out)
        self.assertIn("완료", out)
        self.assertIn("결정 대기", out)
        self.assertIn("PR #797 review 대기", out)
        self.assertIn("PR #788 머지 완료", out)
        self.assertIn("NCP secret 갱신 여부", out)
        _assert_polite(self, out)

    def test_empty_sections_show_polite_none(self) -> None:
        out = bot.formal_status(
            work_in_progress=[],
            work_completed=[],
            pending_decisions=[],
        )
        # 빈 section 은 "없습니다." 로 표기 (정중체).
        self.assertEqual(out.count("없습니다."), 3)
        _assert_polite(self, out)

    def test_status_starts_with_reply_emoji(self) -> None:
        out = bot.formal_status([], [], [])
        self.assertTrue(out.startswith(bot.MESSAGE_PREFIX["reply"]))


class FormalErrorTests(unittest.TestCase):
    """formal_error — 오류 정중체."""

    def test_error_no_retry(self) -> None:
        out = bot.formal_error("tmux send 실패")
        self.assertIn("오류가 발생했습니다", out)
        self.assertIn("tmux send 실패", out)
        self.assertNotIn("재시도", out)
        _assert_polite(self, out)

    def test_error_with_retry(self) -> None:
        out = bot.formal_error("Discord send timeout", retry_count=3)
        self.assertIn("재시도 3회 진행했습니다", out)
        _assert_polite(self, out)

    def test_error_starts_with_alert_emoji(self) -> None:
        out = bot.formal_error("X")
        self.assertTrue(out.startswith(bot.MESSAGE_PREFIX["alert"]))


class FormalDigestTests(unittest.TestCase):
    """formal_digest — cron digest 정중체."""

    def test_digest_format(self) -> None:
        out = bot.formal_digest(merged_prs=5, open_prs=3, bugs=1)
        self.assertIn("머지 5건", out)
        self.assertIn("open PR 3건", out)
        self.assertIn("bug 1건", out)
        self.assertIn("입니다", out)
        _assert_polite(self, out)

    def test_digest_starts_with_digest_emoji(self) -> None:
        out = bot.formal_digest(0, 0, 0)
        self.assertTrue(out.startswith(bot.MESSAGE_PREFIX["digest"]))


class AutoAckTemplatePolitenessTests(unittest.TestCase):
    """기존 AUTO_ACK_TEMPLATE 도 정중체로 전환 (#797)."""

    def test_template_uses_polite_form(self) -> None:
        rendered = bot.AUTO_ACK_TEMPLATE.format(queue=2)
        self.assertIn("받았습니다", rendered)
        self.assertIn("처리 중입니다", rendered)
        _assert_polite(self, rendered)


if __name__ == "__main__":
    unittest.main()
