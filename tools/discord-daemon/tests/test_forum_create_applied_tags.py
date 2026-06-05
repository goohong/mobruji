"""_forum_create_thread applied_tags 부착 + _resolve_forum_tags 단위 테스트 (#1858).

배경:
  bot.py `_forum_create_thread` 가 `create_thread(name, content)` 만 호출하고
  payload 의 `tags`(available_tags name list) 를 무시 → 생성된 thread 태그 None.
  FIX = payload tags 를 forum.available_tags 매칭(`_resolve_forum_tags`, retag 와
  공유)해 `applied_tags` 로 전달.

검증 범위:
1. _resolve_forum_tags — 매칭 / 미매칭 drop / 빈 입력.
2. _forum_create_thread — 매칭 tag → applied_tags 전달 / 미매칭 → kwarg 생략 /
   tags 없음 → kwarg 생략.
3. _forum_retag — 헬퍼 재사용 후에도 매칭 시 edit(applied_tags) 1회 / 미매칭 시 0회.
"""

from __future__ import annotations

import asyncio
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


def _fake_tag(name: str):
    tag = mock.MagicMock()
    tag.name = name
    return tag


def _fake_forum(forum_id: int, available_names: list[str]):
    """Forum 채널 흉내 — available_tags + async create_thread."""
    forum = mock.MagicMock()
    forum.id = forum_id
    forum.available_tags = [_fake_tag(n) for n in available_names]
    result = mock.MagicMock()
    result.thread.id = 555000111
    forum.create_thread = mock.AsyncMock(return_value=result)
    return forum


class ResolveForumTagsTests(unittest.TestCase):
    def test_matches_by_name(self):
        forum = _fake_forum(1, ["🟡 Pre-merge review", "✅ rev pass"])
        resolved = bot._resolve_forum_tags(forum, ["🟡 Pre-merge review"])
        self.assertEqual([t.name for t in resolved], ["🟡 Pre-merge review"])

    def test_drops_unknown(self):
        forum = _fake_forum(1, ["🟡 Pre-merge review"])
        resolved = bot._resolve_forum_tags(forum, ["없는태그"])
        self.assertEqual(resolved, [])

    def test_empty_input(self):
        forum = _fake_forum(1, ["🟡 Pre-merge review"])
        self.assertEqual(bot._resolve_forum_tags(forum, []), [])


class ForumCreateAppliedTagsTests(unittest.TestCase):
    def _run_create(self, forum, tags):
        client = mock.MagicMock()
        client.get_channel.return_value = forum
        payload = {
            "forum_id": str(forum.id),
            "title": "📌 PR #1858 rev review",
            "body": "no url here",
            "tags": tags,
        }
        with mock.patch.object(bot, "backfill_rev_forum_dedupe") as backfill:
            asyncio.run(bot._forum_create_thread(client, payload))
        return backfill

    def test_matched_tag_passed_as_applied_tags(self):
        forum = _fake_forum(123, ["🟡 Pre-merge review", "✅ rev pass"])
        self._run_create(forum, ["🟡 Pre-merge review"])
        forum.create_thread.assert_awaited_once()
        kwargs = forum.create_thread.await_args.kwargs
        self.assertIn("applied_tags", kwargs)
        self.assertEqual([t.name for t in kwargs["applied_tags"]], ["🟡 Pre-merge review"])

    def test_unknown_tag_omits_kwarg(self):
        forum = _fake_forum(123, ["🟡 Pre-merge review"])
        self._run_create(forum, ["없는태그"])
        kwargs = forum.create_thread.await_args.kwargs
        self.assertNotIn("applied_tags", kwargs)

    def test_no_tags_omits_kwarg(self):
        forum = _fake_forum(123, ["🟡 Pre-merge review"])
        self._run_create(forum, [])
        kwargs = forum.create_thread.await_args.kwargs
        self.assertNotIn("applied_tags", kwargs)
        self.assertEqual(kwargs["name"], "📌 PR #1858 rev review")


class ForumRetagReuseTests(unittest.TestCase):
    def _run_retag(self, available_names, tag_name):
        forum = mock.MagicMock()
        forum.available_tags = [_fake_tag(n) for n in available_names]
        thread = mock.MagicMock()
        thread.parent = forum
        thread.edit = mock.AsyncMock()
        client = mock.MagicMock()
        client.get_channel.return_value = thread
        payload = {"thread_id": "777", "tag_name": tag_name}
        asyncio.run(bot._forum_retag(client, payload))
        return thread

    def test_matched_retag_edits_once(self):
        thread = self._run_retag(["🔵 Post-merge audit"], "🔵 Post-merge audit")
        thread.edit.assert_awaited_once()
        applied = thread.edit.await_args.kwargs["applied_tags"]
        self.assertEqual([t.name for t in applied], ["🔵 Post-merge audit"])

    def test_unknown_retag_no_edit(self):
        thread = self._run_retag(["🔵 Post-merge audit"], "없는태그")
        thread.edit.assert_not_awaited()


if __name__ == "__main__":
    unittest.main()
