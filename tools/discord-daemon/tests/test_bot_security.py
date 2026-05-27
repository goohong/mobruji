"""bot.py 보안/race 묶음 A 단위 테스트 (#909).

대상:
- **F-1 (inbox PII)**: ``append_inbox`` 호출 후 inbox.jsonl 권한 ``0o600`` +
  ``text`` 필드 ``INBOX_TEXT_MAX_LEN`` (500자) 캡.
- **F-3 (dedup race)**: ``OpLedger.claim`` 원자성 + ``on_message`` 가
  ``is_processed`` 직후 즉시 mark (tmux send 이전) 하는지.

외부 네트워크 호출 없음. discord/dotenv/requests 는 stub.
"""

from __future__ import annotations

import asyncio
import json
import os
import stat
import sys
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path
from unittest import mock

PARENT_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PARENT_DIR))

import discord
for missing in ("requests", "dotenv"):
    if missing not in sys.modules:
        stub = mock.MagicMock()
        if missing == "dotenv":
            stub.load_dotenv = lambda *a, **kw: None
        sys.modules[missing] = stub

import bot  # noqa: E402


# ─────────────────────────────────────────────────────────────────────────────
# F-1: inbox.jsonl PII / 권한
# ─────────────────────────────────────────────────────────────────────────────


class AppendInboxSecurityTests(unittest.TestCase):
    """append_inbox 호출 후 파일 권한 0o600 + text 캡 확인 (#909 F-1)."""

    def setUp(self) -> None:
        self._tmpdir = tempfile.TemporaryDirectory()
        self._tmp_inbox = Path(self._tmpdir.name) / "inbox.jsonl"
        # bot.INBOX_PATH 는 Final 이지만 Final 은 런타임 enforce 아님.
        self._patcher = mock.patch.object(bot, "INBOX_PATH", self._tmp_inbox)
        self._patcher.start()

    def tearDown(self) -> None:
        self._patcher.stop()
        self._tmpdir.cleanup()

    def test_creates_file_with_0600_mode(self) -> None:
        self.assertFalse(self._tmp_inbox.exists())
        bot.append_inbox({"text": "hi", "author": "1", "ts": "t"})
        self.assertTrue(self._tmp_inbox.exists())
        mode = stat.S_IMODE(self._tmp_inbox.stat().st_mode)
        self.assertEqual(
            mode,
            0o600,
            f"inbox.jsonl 권한이 0o600 이어야 하는데 {oct(mode)} 임",
        )

    def test_chmod_enforced_even_if_file_exists_with_loose_mode(self) -> None:
        # 이미 644 로 존재하는 파일도 매 append 시 600 으로 재조정.
        self._tmp_inbox.parent.mkdir(parents=True, exist_ok=True)
        self._tmp_inbox.touch(mode=0o644)
        os.chmod(self._tmp_inbox, 0o644)
        bot.append_inbox({"text": "hi", "author": "1", "ts": "t"})
        mode = stat.S_IMODE(self._tmp_inbox.stat().st_mode)
        self.assertEqual(mode, 0o600)

    def test_truncates_long_text_field(self) -> None:
        long_text = "x" * (bot.INBOX_TEXT_MAX_LEN + 200)
        bot.append_inbox({"text": long_text, "author": "1", "ts": "t"})
        line = self._tmp_inbox.read_text(encoding="utf-8").strip()
        record = json.loads(line)
        # `…` suffix (truncate_for_log 동작) 포함이라 len 은 max_len + 1.
        self.assertEqual(len(record["text"]), bot.INBOX_TEXT_MAX_LEN + 1)
        self.assertTrue(record["text"].endswith("…"))
        # 다른 필드는 무관해야 함.
        self.assertEqual(record["author"], "1")

    def test_short_text_preserved_verbatim(self) -> None:
        bot.append_inbox({"text": "안녕", "author": "1", "ts": "t"})
        line = self._tmp_inbox.read_text(encoding="utf-8").strip()
        record = json.loads(line)
        self.assertEqual(record["text"], "안녕")

    def test_parent_directory_auto_created(self) -> None:
        nested = Path(self._tmpdir.name) / "nested" / "deeper" / "inbox.jsonl"
        with mock.patch.object(bot, "INBOX_PATH", nested):
            bot.append_inbox({"text": "x", "author": "1", "ts": "t"})
        self.assertTrue(nested.exists())
        self.assertEqual(stat.S_IMODE(nested.stat().st_mode), 0o600)


# ─────────────────────────────────────────────────────────────────────────────
# F-3: dedup race — claim 원자성 + on_message 즉시 mark
# ─────────────────────────────────────────────────────────────────────────────


class OpLedgerClaimTests(unittest.TestCase):
    """OpLedger.claim 원자성 — INSERT OR IGNORE rowcount 기반."""

    def setUp(self) -> None:
        self.ledger = bot.OpLedger(":memory:")

    def test_first_claim_returns_true(self) -> None:
        self.assertTrue(self.ledger.claim("m1"))

    def test_second_claim_returns_false(self) -> None:
        self.assertTrue(self.ledger.claim("m1"))
        self.assertFalse(self.ledger.claim("m1"))

    def test_claim_marks_processed(self) -> None:
        self.assertFalse(self.ledger.is_processed("m1"))
        self.ledger.claim("m1")
        self.assertTrue(self.ledger.is_processed("m1"))

    def test_distinct_ids_independent(self) -> None:
        self.assertTrue(self.ledger.claim("m1"))
        self.assertTrue(self.ledger.claim("m2"))
        self.assertFalse(self.ledger.claim("m1"))


# ─────────────────────────────────────────────────────────────────────────────
# on_message → claim 호출 순서 (tmux send 이전)
# ─────────────────────────────────────────────────────────────────────────────


def _make_fake_message(
    *,
    content: str = "hello",
    channel_id: int = 999,
    author_id: int = 111,
    message_id: int = 42,
    is_bot: bool = False,
) -> mock.MagicMock:
    msg = mock.MagicMock()
    msg.content = content
    msg.channel = mock.MagicMock()
    msg.channel.id = channel_id
    msg.channel.send = mock.AsyncMock()
    msg.author = mock.MagicMock()
    msg.author.id = author_id
    msg.author.bot = is_bot
    msg.author.name = "tester"
    msg.id = message_id
    msg.created_at = datetime(2026, 5, 23, 12, 0, tzinfo=timezone.utc)
    msg.referenced_message = None
    return msg


class OnMessageDedupOrderTests(unittest.TestCase):
    """on_message 가 is_processed 직후 즉시 mark (claim) 하는지 검증.

    재현 시나리오: Gateway reconnect 로 같은 message_id 가 두 번 들어오는 경우,
    두 번째 호출 시점에 첫 호출이 아직 tmux send 단계를 못 끝냈더라도 claim 이
    먼저 발생하므로 두 번째는 dedup hit 으로 빠져야 한다.
    """

    BASE_ENV = {
        "DISCORD_BOT_TOKEN": "t",
        "ALLOWED_USER_IDS": "111",
        "MOBRUJI_CHANNEL_ID": "999",
        "DIGEST_CHANNEL_ID": "999",
        "TMUX_SESSION_NAME": "helper",
        "TMUX_TARGET_PANE": "helper:0.0",
        "CLAUDE_BIN": "claude",
        "DEDUP_LEDGER_PATH": ":memory:",
        "DIGEST_ENABLED": "0",
        "CONTEXT_AUTO_CLEAR_ENABLED": "0",
        "BOT_AUTO_ACK": "0",
        "CYCLE_STATUS_PATH": "/tmp/cycle-status.json",
        "TMUX_PANE_TARGETS": "helper:0.0",
        "TMUX_PANE_TARGET": "helper:0.0",
        "CONTEXT_CLEAR_TRIGGER_PCT": "95",
        "CONTEXT_CLEAR_HYSTERESIS_PCT": "80",
    }

    def _build_handler(self, ledger: bot.OpLedger):
        registered: dict[str, object] = {}

        class FakeClient:
            user = "fake-bot"
            loop = mock.MagicMock()

            def event(self, func):
                registered[func.__name__] = func
                setattr(self, func.__name__, func)
                return func

            def get_channel(self, channel_id):
                return None

        fake_client = FakeClient()
        with mock.patch.object(bot.discord, "Client", return_value=fake_client), \
             mock.patch.object(bot.discord, "Intents") as intents_cls:
            intents_cls.default.return_value = mock.MagicMock()
            bot.build_client(self.BASE_ENV, ledger)
        return registered["on_message"]

    def test_claim_called_before_enqueue(self) -> None:
        """claim 이 enqueue_task 보다 먼저 호출되는지 순서 검증."""
        ledger = bot.OpLedger(":memory:")
        call_order: list[str] = []

        original_claim = ledger.claim
        original_enqueue = ledger.enqueue_task

        def tracking_claim(message_id: str, now_epoch: int | None = None) -> bool:
            call_order.append("claim")
            return original_claim(message_id, now_epoch)

        def tracking_enqueue(*a, **kw) -> int:
            call_order.append("enqueue")
            return original_enqueue(*a, **kw)

        with mock.patch.object(ledger, "claim", side_effect=tracking_claim), \
             mock.patch.object(ledger, "enqueue_task", side_effect=tracking_enqueue), \
             mock.patch.object(bot, "append_inbox"):
            handler = self._build_handler(ledger)
            msg = _make_fake_message(message_id=1)
            asyncio.run(handler(msg))

        # claim 이 반드시 enqueue 보다 먼저.
        self.assertIn("claim", call_order)
        self.assertIn("enqueue", call_order)
        self.assertLess(
            call_order.index("claim"),
            call_order.index("enqueue"),
            f"claim 이 enqueue 보다 먼저여야 함. 실제 순서: {call_order}",
        )

    def test_duplicate_message_id_skipped(self) -> None:
        """같은 message_id 두 번 들어오면 두 번째는 enqueue 가 호출되지 않음."""
        ledger = bot.OpLedger(":memory:")
        with mock.patch.object(ledger, "enqueue_task") as enqueue_mock, \
             mock.patch.object(bot, "append_inbox"):
            handler = self._build_handler(ledger)
            msg1 = _make_fake_message(message_id=7)
            msg2 = _make_fake_message(message_id=7)
            asyncio.run(handler(msg1))
            asyncio.run(handler(msg2))

        self.assertEqual(
            enqueue_mock.call_count,
            1,
            "중복 message_id 는 두 번째 진입에서 dedup hit 으로 빠져야 함",
        )

    def test_processing_failure_keeps_claim(self) -> None:
        """이후 단계(큐 등록 등) 실패해도 claim 은 유지 (재처리 위험 회피)."""
        ledger = bot.OpLedger(":memory:")
        with mock.patch.object(ledger, "enqueue_task", side_effect=Exception("DB Error")), \
             mock.patch.object(bot, "append_inbox"):
            handler = self._build_handler(ledger)
            msg = _make_fake_message(message_id=99)
            # broad except 에 잡히므로 에러는 전파 안 됨
            asyncio.run(handler(msg))

        self.assertTrue(
            ledger.is_processed("99"),
            "작업 처리 중 예외가 발생해도 claim 은 유지되어야 함 (재처리 방지)",
        )


if __name__ == "__main__":
    unittest.main()
