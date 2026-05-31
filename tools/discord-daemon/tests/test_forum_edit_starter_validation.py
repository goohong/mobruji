"""_forum_edit_starter validation 통합 테스트 (PR F, #1362).

대상: `tools/discord-daemon/bot.py:_forum_edit_starter` + 헬퍼:
- `_append_forum_template_violation`
- `_alert_forum_template_violation`
- `_should_debounce_forum_template_violation`
- `_record_forum_template_violation_debounce`

spec: `docs/features/forum-starter-template-guard.md §5-3·§5-7`

검증 범위:
1. 정상 body (6/6) — starter.edit 호출 + alert 호출 X.
2. 위배 body (0/6) — starter.edit 호출 X + violation jsonl append + DIGEST + thread 댓글.
3. graceful 5/6 — starter.edit 호출 (PASS_THRESHOLD threshold).
4. 같은 thread 1h debounce — 두 번째 alert skip (jsonl append 만).
5. thread 미발견 시 graceful drop.
"""

from __future__ import annotations

import asyncio
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

PARENT_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PARENT_DIR))

try:
    import discord as _real_discord  # noqa: F401
except ImportError:
    sys.modules["discord"] = mock.MagicMock()

for missing in ("requests", "dotenv"):
    if missing not in sys.modules:
        stub = mock.MagicMock()
        if missing == "dotenv":
            stub.load_dotenv = lambda *a, **kw: None
        sys.modules[missing] = stub

import bot  # noqa: E402


# ─── fixture body ───────────────────────────────────────────────────────────


VALID_BODY_6_OF_6 = """\
🛠️ **be 사이클 — 양식 가드**

💬 본문
> validation 가드 구현

🆔 cycle=be-001

📋 진행
- [x] launch
- [x] PR 생성

🔖 관련
- PR: #1362

---
_갱신: 2026-05-30T15:00:00+09:00_
"""


INVALID_BODY_PR_LINK = "PR #1234 작업 끝"


# ─── 공통 fixture ───────────────────────────────────────────────────────────


def _make_fake_thread(thread_id: int) -> mock.MagicMock:
    """fake forum thread — fetch_message + send 양쪽 지원."""
    thread = mock.MagicMock()
    thread.id = thread_id
    fake_starter = mock.MagicMock()
    fake_starter.edit = mock.AsyncMock()
    thread.fetch_message = mock.AsyncMock(return_value=fake_starter)
    thread.send = mock.AsyncMock()
    thread._starter = fake_starter  # 테스트가 assert 용도로 read.
    return thread


def _make_fake_digest_channel() -> mock.MagicMock:
    """fake DIGEST 채널 — send 호출 spy."""
    channel = mock.MagicMock()
    channel.send = mock.AsyncMock()
    return channel


class _ForumEditStarterFixture(unittest.TestCase):
    """tmp HOME + DIGEST env + violation jsonl path patch 공통."""

    def setUp(self) -> None:
        self._tmpdir = tempfile.TemporaryDirectory()
        self.tmp_home = Path(self._tmpdir.name)
        self.violation_path = self.tmp_home / "forum-template-violations.jsonl"
        self.debounce_path = self.tmp_home / "forum-template-violation-debounce.jsonl"
        self._patchers = [
            mock.patch.object(
                bot, "FORUM_TEMPLATE_VIOLATION_LOG_PATH", self.violation_path,
            ),
            mock.patch.object(
                bot,
                "FORUM_TEMPLATE_VIOLATION_DEBOUNCE_PATH",
                self.debounce_path,
            ),
        ]
        for patcher in self._patchers:
            patcher.start()

    def tearDown(self) -> None:
        for patcher in self._patchers:
            patcher.stop()
        self._tmpdir.cleanup()


# ─── 정상 body — starter.edit 호출 + alert 미발사 ──────────────────────────


class PassingEditStarterTests(_ForumEditStarterFixture):
    """validation pass 시 기존 동작 (starter.edit) 유지 확인."""

    def test_valid_body_calls_starter_edit_once(self) -> None:
        thread_id = 1509466456230989926
        fake_thread = _make_fake_thread(thread_id)
        fake_digest = _make_fake_digest_channel()

        client = mock.MagicMock()

        def get_channel(channel_id: int):
            if channel_id == thread_id:
                return fake_thread
            return fake_digest

        client.get_channel.side_effect = get_channel

        payload = {"thread_id": str(thread_id), "body": VALID_BODY_6_OF_6}

        with mock.patch.dict(
            "os.environ", {"DIGEST_CHANNEL_ID": "999"}, clear=False,
        ):
            asyncio.run(bot._forum_edit_starter(client, payload))

        fake_thread.fetch_message.assert_awaited_once_with(thread_id)
        fake_thread._starter.edit.assert_awaited_once_with(content=VALID_BODY_6_OF_6)
        # alert 미발사 — DIGEST send 호출 0.
        fake_digest.send.assert_not_called()
        # violation jsonl 미생성.
        self.assertFalse(self.violation_path.exists())

    def test_graceful_5_of_6_body_passes(self) -> None:
        # 🆔 line 만 누락 — PASS_THRESHOLD = 5 → pass.
        body_5_of_6 = VALID_BODY_6_OF_6.replace("🆔 cycle=be-001\n\n", "")
        thread_id = 7777
        fake_thread = _make_fake_thread(thread_id)
        fake_digest = _make_fake_digest_channel()
        client = mock.MagicMock()
        client.get_channel.side_effect = lambda cid: (
            fake_thread if cid == thread_id else fake_digest
        )
        payload = {"thread_id": str(thread_id), "body": body_5_of_6}
        with mock.patch.dict(
            "os.environ", {"DIGEST_CHANNEL_ID": "999"}, clear=False,
        ):
            asyncio.run(bot._forum_edit_starter(client, payload))
        fake_thread._starter.edit.assert_awaited_once()
        fake_digest.send.assert_not_called()


# ─── 위배 body — starter.edit 미호출 + alert 발사 ──────────────────────────


class RejectingEditStarterTests(_ForumEditStarterFixture):
    """validation fail 시 graceful reject + alert + jsonl append 확인."""

    def test_invalid_body_does_not_call_starter_edit(self) -> None:
        thread_id = 1509466456230989927
        fake_thread = _make_fake_thread(thread_id)
        fake_digest = _make_fake_digest_channel()
        client = mock.MagicMock()
        client.get_channel.side_effect = lambda cid: (
            fake_thread if cid == thread_id else fake_digest
        )
        payload = {
            "thread_id": str(thread_id),
            "body": INVALID_BODY_PR_LINK,
            "actor": "be",
            "cycle": "be",
        }
        with mock.patch.dict(
            "os.environ", {"DIGEST_CHANNEL_ID": "999"}, clear=False,
        ):
            asyncio.run(bot._forum_edit_starter(client, payload))

        # starter.edit 미호출.
        fake_thread._starter.edit.assert_not_called()
        # fetch_message 도 미호출 (reject 가 fetch 전에 발사).
        fake_thread.fetch_message.assert_not_called()

    def test_invalid_body_appends_violation_jsonl(self) -> None:
        thread_id = 1509466456230989928
        fake_thread = _make_fake_thread(thread_id)
        fake_digest = _make_fake_digest_channel()
        client = mock.MagicMock()
        client.get_channel.side_effect = lambda cid: (
            fake_thread if cid == thread_id else fake_digest
        )
        payload = {
            "thread_id": str(thread_id),
            "body": INVALID_BODY_PR_LINK,
            "actor": "be",
            "cycle": "be",
        }
        with mock.patch.dict(
            "os.environ", {"DIGEST_CHANNEL_ID": "999"}, clear=False,
        ):
            asyncio.run(bot._forum_edit_starter(client, payload))

        self.assertTrue(self.violation_path.exists())
        lines = self.violation_path.read_text(encoding="utf-8").splitlines()
        self.assertEqual(len(lines), 1)
        entry = json.loads(lines[0])
        self.assertEqual(entry["thread_id"], str(thread_id))
        self.assertEqual(entry["actor"], "be")
        self.assertEqual(entry["cycle"], "be")
        self.assertEqual(entry["matched"], 0)
        self.assertIn("title_prefix", entry["missing"])
        self.assertIn("body_section", entry["missing"])
        # body 첫 80자 박제.
        self.assertEqual(entry["attempted_body_head"], INVALID_BODY_PR_LINK[:80])

    def test_invalid_body_pushes_digest_and_thread_alert(self) -> None:
        thread_id = 1509466456230989929
        fake_thread = _make_fake_thread(thread_id)
        fake_digest = _make_fake_digest_channel()
        client = mock.MagicMock()
        client.get_channel.side_effect = lambda cid: (
            fake_thread if cid == thread_id else fake_digest
        )
        payload = {"thread_id": str(thread_id), "body": INVALID_BODY_PR_LINK}
        with mock.patch.dict(
            "os.environ", {"DIGEST_CHANNEL_ID": "999"}, clear=False,
        ):
            asyncio.run(bot._forum_edit_starter(client, payload))

        # DIGEST 채널 + cycle thread 양쪽 alert.
        fake_digest.send.assert_awaited_once()
        fake_thread.send.assert_awaited_once()
        # alert 본문에 thread_id + matched ratio 포함.
        digest_alert_args = fake_digest.send.await_args
        digest_body = digest_alert_args.kwargs.get("content") or (
            digest_alert_args.args[0] if digest_alert_args.args else ""
        )
        self.assertIn(str(thread_id), digest_body)
        self.assertIn("0/6", digest_body)


# ─── debounce — 같은 thread 1h 두 번째 alert skip ───────────────────────────


class DebounceForumTemplateViolationTests(_ForumEditStarterFixture):
    """같은 thread 1h 안 두 번 위배 시 alert 1번만 push (jsonl append 는 매번)."""

    def test_second_violation_within_1h_skips_alert(self) -> None:
        thread_id = 1509466456230989930
        fake_thread = _make_fake_thread(thread_id)
        fake_digest = _make_fake_digest_channel()
        client = mock.MagicMock()
        client.get_channel.side_effect = lambda cid: (
            fake_thread if cid == thread_id else fake_digest
        )
        payload = {"thread_id": str(thread_id), "body": INVALID_BODY_PR_LINK}
        with mock.patch.dict(
            "os.environ", {"DIGEST_CHANNEL_ID": "999"}, clear=False,
        ):
            asyncio.run(bot._forum_edit_starter(client, payload))
            asyncio.run(bot._forum_edit_starter(client, payload))

        # alert 1번만 (debounce 적용).
        self.assertEqual(fake_digest.send.await_count, 1)
        self.assertEqual(fake_thread.send.await_count, 1)
        # violation jsonl 은 2건 append (회고 / 통계 완전).
        lines = self.violation_path.read_text(encoding="utf-8").splitlines()
        self.assertEqual(len(lines), 2)


# ─── thread 미발견 graceful ────────────────────────────────────────────────


class ThreadMissingTests(_ForumEditStarterFixture):
    """thread 미발견 시 기존 graceful drop (validation 호출 전 return)."""

    def test_missing_thread_does_not_call_validation(self) -> None:
        client = mock.MagicMock()
        client.get_channel.return_value = None
        payload = {"thread_id": "0", "body": INVALID_BODY_PR_LINK}
        asyncio.run(bot._forum_edit_starter(client, payload))
        # violation jsonl 미생성 (validation 호출 전 return).
        self.assertFalse(self.violation_path.exists())


if __name__ == "__main__":  # pragma: no cover
    unittest.main()
