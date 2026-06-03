"""rev forum thread 완료 자동 retag (구멍 A) + dedupe backfill (구멍 C) 단위 테스트.

배경 (cycle forum '완료' 태그 자동화 구멍):
  - 구멍 A: rev sub-agent 는 PR 을 안 만들고 리뷰만 한다 → rev forum thread 는
    PR body 의 `cycle-forum:` 참조를 못 받아 cycle_thread_complete_on_merge_loop 가
    영원히 못 잡음 → 무한 정체. FIX = PR #N 머지 시 제목 `#N` rev thread 완료 retag.
  - 구멍 C: forum_create_thread snowflake 즉시 미반환 → rev-forum-dedupe.jsonl 에
    thread_id=None → _register_pr_audit lookup miss. FIX = _forum_create_thread 가
    생성 직후 backfill_rev_forum_dedupe 로 실제 thread_id 채움.

검증 범위:
1. extract_pr_url_from_forum_body — 정상 / 부재 / None.
2. title_references_pr — `#N` 매칭 / `#12`≠`#123` 오매칭 거부 / zero-pad / 무관 제목.
3. thread_is_done — 완료 태그 / 비-완료 태그 / 태그 없음.
4. backfill_rev_forum_dedupe — append schema + cap.
5. find_rev_threads_for_pr — active + archived 매칭 / 이미 완료 제외 / forum 부재.
6. rev_forum_complete_on_merge_loop — 매칭 시 retag 1회 / 매칭 없으면 retag 0회 /
   disabled (poll<=0, forum_id=0).
"""

from __future__ import annotations

import asyncio
import json
import sys
import unittest
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


PR_URL = "https://github.com/goohong/mobruji/pull/1500"


def _fake_thread(thread_id: int, name: str, tag_names: list[str] | None = None):
    """discord.Thread 흉내 — id / name / applied_tags(name 속성)."""
    thread = mock.MagicMock()
    thread.id = thread_id
    thread.name = name
    tags = []
    for tn in tag_names or []:
        tag = mock.MagicMock()
        tag.name = tn
        tags.append(tag)
    thread.applied_tags = tags
    return thread


class ExtractPrUrlTests(unittest.TestCase):
    def test_extracts_url_from_template_body(self):
        body = f"📌 PR rev review\n> URL: {PR_URL}\n🆔 `rev-1500-open`"
        self.assertEqual(bot.extract_pr_url_from_forum_body(body), PR_URL)

    def test_no_url_returns_none(self):
        self.assertIsNone(bot.extract_pr_url_from_forum_body("no url here"))

    def test_empty_returns_none(self):
        self.assertIsNone(bot.extract_pr_url_from_forum_body(""))


class TitleReferencesPrTests(unittest.TestCase):
    def test_exact_match(self):
        self.assertTrue(bot.title_references_pr("📌 PR #1500 rev review — x", 1500))

    def test_no_partial_overmatch(self):
        # #150 must NOT match a title that only mentions #1500.
        self.assertFalse(bot.title_references_pr("PR #1500 rev review", 150))

    def test_zero_pad_match(self):
        self.assertTrue(bot.title_references_pr("PR #0042 rev", 42))

    def test_unrelated_title(self):
        self.assertFalse(bot.title_references_pr("그냥 잡담 thread", 1500))

    def test_invalid_pr_number(self):
        self.assertFalse(bot.title_references_pr("PR #1500", 0))


class ThreadIsDoneTests(unittest.TestCase):
    def test_done_tag(self):
        self.assertTrue(bot.thread_is_done(_fake_thread(1, "x", ["완료"])))

    def test_done_tag_emoji(self):
        self.assertTrue(bot.thread_is_done(_fake_thread(1, "x", ["✅ 완료"])))

    def test_not_done(self):
        self.assertFalse(bot.thread_is_done(_fake_thread(1, "x", ["🟡 1차 review"])))

    def test_no_tags(self):
        self.assertFalse(bot.thread_is_done(_fake_thread(1, "x", [])))


class BackfillDedupeTests(unittest.TestCase):
    def setUp(self):
        import shutil
        import tempfile
        self._tmp = Path(tempfile.mkdtemp())
        self.addCleanup(lambda: shutil.rmtree(self._tmp, ignore_errors=True))

    def test_append_schema(self):
        with mock.patch.object(bot.Path, "home", return_value=self._tmp):
            bot.backfill_rev_forum_dedupe(PR_URL, "999888777")
        path = self._tmp / ".mobruji" / "rev-forum-dedupe.jsonl"
        self.assertTrue(path.exists())
        entry = json.loads(path.read_text(encoding="utf-8").strip())
        self.assertEqual(entry["pr_url"], PR_URL)
        self.assertEqual(entry["kind"], "pr_review")
        self.assertEqual(entry["thread_id"], "999888777")
        self.assertIn("ts", entry)


class FindRevThreadsTests(unittest.TestCase):
    def _run(self, forum, pr_number):
        client = mock.MagicMock()
        client.get_channel.return_value = forum
        return asyncio.run(bot.find_rev_threads_for_pr(client, 123, pr_number))

    def test_active_match(self):
        forum = mock.MagicMock()
        forum.threads = [
            _fake_thread(11, "📌 PR #1500 rev review — fix", ["🟡 1차 review"]),
            _fake_thread(12, "📌 PR #1499 rev review", ["🟡 1차 review"]),
        ]
        forum.archived_threads = None
        self.assertEqual(self._run(forum, 1500), ["11"])

    def test_already_done_excluded(self):
        forum = mock.MagicMock()
        forum.threads = [_fake_thread(11, "📌 PR #1500 rev review", ["완료"])]
        forum.archived_threads = None
        self.assertEqual(self._run(forum, 1500), [])

    def test_forum_absent(self):
        client = mock.MagicMock()
        client.get_channel.return_value = None
        self.assertEqual(
            asyncio.run(bot.find_rev_threads_for_pr(client, 123, 1500)), []
        )


class LoopTests(unittest.TestCase):
    def _client_with_forum(self, threads):
        forum = mock.MagicMock()
        forum.threads = threads
        forum.archived_threads = None
        client = mock.MagicMock()
        client.get_channel.return_value = forum
        return client

    def test_match_triggers_retag_once(self):
        threads = [_fake_thread(11, "📌 PR #1500 rev review", ["🟡 1차 review"])]
        client = self._client_with_forum(threads)
        fetcher = lambda: [{"number": 1500}]  # noqa: E731
        calls: list[list[str]] = []

        def fake_run(cmd, **kw):
            calls.append(cmd)
            return mock.MagicMock(returncode=0)

        async def _drive():
            task = asyncio.create_task(
                bot.rev_forum_complete_on_merge_loop(
                    client, 123, poll_interval=1, initial_delay=0, fetcher=fetcher,
                )
            )
            await asyncio.sleep(0.05)
            task.cancel()
            try:
                await task
            except asyncio.CancelledError:
                pass

        with mock.patch.object(bot.subprocess, "run", side_effect=fake_run), \
                mock.patch.object(bot.Path, "exists", return_value=True):
            asyncio.run(_drive())

        retag_calls = [c for c in calls if "--forum-retag" in c]
        self.assertEqual(len(retag_calls), 1)
        self.assertIn("11", retag_calls[0])
        self.assertIn("rev", retag_calls[0])
        self.assertIn("완료", retag_calls[0])

    def test_no_match_no_retag(self):
        threads = [_fake_thread(11, "📌 PR #1499 rev review", ["🟡 1차 review"])]
        client = self._client_with_forum(threads)
        fetcher = lambda: [{"number": 1500}]  # noqa: E731
        calls: list[list[str]] = []

        def fake_run(cmd, **kw):
            calls.append(cmd)
            return mock.MagicMock(returncode=0)

        async def _drive():
            task = asyncio.create_task(
                bot.rev_forum_complete_on_merge_loop(
                    client, 123, poll_interval=1, initial_delay=0, fetcher=fetcher,
                )
            )
            await asyncio.sleep(0.05)
            task.cancel()
            try:
                await task
            except asyncio.CancelledError:
                pass

        with mock.patch.object(bot.subprocess, "run", side_effect=fake_run), \
                mock.patch.object(bot.Path, "exists", return_value=True):
            asyncio.run(_drive())

        self.assertEqual([c for c in calls if "--forum-retag" in c], [])

    def test_disabled_poll_zero(self):
        client = mock.MagicMock()
        # poll_interval=0 → 즉시 return (await 도 안 함).
        asyncio.run(
            bot.rev_forum_complete_on_merge_loop(client, 123, poll_interval=0)
        )

    def test_disabled_forum_zero(self):
        client = mock.MagicMock()
        asyncio.run(
            bot.rev_forum_complete_on_merge_loop(client, 0, poll_interval=1)
        )


if __name__ == "__main__":
    unittest.main()
