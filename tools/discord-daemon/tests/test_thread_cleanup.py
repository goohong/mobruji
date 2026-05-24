"""Discord thread auto-cleanup loop 단위 테스트 (#1023).

검증 범위:
1. snowflake_to_datetime — 정상 변환 + 0 / 잘못된 값 (3건).
2. thread_last_activity — last_message_id 우선 / create_timestamp / fallback id (3건).
3. select_threads_to_archive — keep_recent 적용 / age_hours / age_minutes / 빈 입력 (5건).
4. fetch_guild_id / fetch_active_threads / archive_thread / delete_thread — HTTP stub (8건).
5. thread_cleanup_loop (asyncio mock) —
   - threads 0 → archive 0
   - 후보 N → archive 호출 + sleep
   - poll_interval=0 → 즉시 return (disabled)
   - guild_id 첫 호출 None → 다음 iter 재시도
   - delete=True 시 deleter 호출
   - age_minutes 분기 작동
   (6건)
"""

from __future__ import annotations

import asyncio
import sys
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest import mock

PARENT_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PARENT_DIR))

# discord stub — 실제 모듈 있으면 사용, 없으면 mock.
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


# ─────────────────────────────────────────────────────────────────────────────
# 1) snowflake_to_datetime — 3건
# ─────────────────────────────────────────────────────────────────────────────


class SnowflakeToDatetimeTest(unittest.TestCase):

    def test_known_snowflake_returns_expected_utc(self) -> None:
        # 1420070400000ms = 2015-01-01 00:00:00 UTC (Discord epoch). shift 22 = 0.
        result = bot.snowflake_to_datetime(0)
        self.assertEqual(
            result, datetime(2015, 1, 1, tzinfo=timezone.utc)
        )

    def test_realistic_snowflake_parses(self) -> None:
        # 임의 snowflake — 2024-12-01 부근. 정상적으로 datetime 으로 변환되는지만 확인.
        snowflake = "1313000000000000000"
        result = bot.snowflake_to_datetime(snowflake)
        self.assertIsInstance(result, datetime)
        self.assertEqual(result.tzinfo, timezone.utc)
        self.assertGreater(result.year, 2020)

    def test_invalid_raises_value_error(self) -> None:
        with self.assertRaises((TypeError, ValueError)):
            bot.snowflake_to_datetime("not-a-number")


# ─────────────────────────────────────────────────────────────────────────────
# 2) thread_last_activity — 3건
# ─────────────────────────────────────────────────────────────────────────────


class ThreadLastActivityTest(unittest.TestCase):

    def test_last_message_id_takes_precedence(self) -> None:
        thread = {
            "id": "1000000000000000000",
            "last_message_id": "1313000000000000000",
            "thread_metadata": {"create_timestamp": "2020-01-01T00:00:00Z"},
        }
        result = bot.thread_last_activity(thread)
        # last_message_id 가 우선 — 2020 보다 후.
        self.assertGreater(result.year, 2020)

    def test_create_timestamp_when_no_last_message(self) -> None:
        thread = {
            "id": "0",
            "thread_metadata": {"create_timestamp": "2024-03-15T10:00:00Z"},
        }
        result = bot.thread_last_activity(thread)
        self.assertEqual(result.year, 2024)
        self.assertEqual(result.month, 3)
        self.assertEqual(result.day, 15)

    def test_fallback_to_id_snowflake(self) -> None:
        thread = {"id": "1313000000000000000"}
        result = bot.thread_last_activity(thread)
        self.assertIsInstance(result, datetime)
        self.assertGreater(result.year, 2020)


# ─────────────────────────────────────────────────────────────────────────────
# 3) select_threads_to_archive — 5건
# ─────────────────────────────────────────────────────────────────────────────


def _thread(
    id_: str,
    last_activity: datetime,
) -> dict:
    """activity 가 last_message_id 로 표현되도록 인공 thread dict 생성."""
    ms = int(last_activity.timestamp() * 1000)
    snowflake = (ms - bot.DISCORD_EPOCH_MS) << 22
    return {
        "id": id_,
        "last_message_id": str(snowflake),
        "parent_id": "999",
    }


class SelectThreadsToArchiveTest(unittest.TestCase):

    def test_empty_input_returns_empty(self) -> None:
        result = bot.select_threads_to_archive(
            [],
            now_utc=datetime.now(timezone.utc),
            age_hours=1,
            keep_recent=3,
        )
        self.assertEqual(result, [])

    def test_keep_recent_excludes_top_n(self) -> None:
        now = datetime(2026, 5, 24, 12, 0, tzinfo=timezone.utc)
        threads = [
            _thread("a", now - timedelta(hours=10)),
            _thread("b", now - timedelta(hours=8)),
            _thread("c", now - timedelta(hours=6)),
            _thread("d", now - timedelta(hours=4)),
            _thread("e", now - timedelta(hours=2)),
        ]
        # keep_recent=3 → 가장 최근 e,d,c 제외. 나머지 b,a 모두 1h+ → archive.
        result = bot.select_threads_to_archive(
            threads, now_utc=now, age_hours=1, keep_recent=3
        )
        ids = {t["id"] for t in result}
        self.assertEqual(ids, {"a", "b"})

    def test_age_filter_excludes_recent_threads(self) -> None:
        now = datetime(2026, 5, 24, 12, 0, tzinfo=timezone.utc)
        threads = [
            _thread("recent1", now - timedelta(minutes=30)),
            _thread("recent2", now - timedelta(minutes=45)),
            _thread("old1", now - timedelta(hours=2)),
            _thread("old2", now - timedelta(hours=3)),
        ]
        # keep_recent=0 → 모든 thread 가 후보, age_hours=1 → old1,old2 만.
        result = bot.select_threads_to_archive(
            threads, now_utc=now, age_hours=1, keep_recent=0
        )
        ids = {t["id"] for t in result}
        self.assertEqual(ids, {"old1", "old2"})

    def test_all_within_keep_recent_returns_empty(self) -> None:
        now = datetime(2026, 5, 24, 12, 0, tzinfo=timezone.utc)
        threads = [
            _thread("a", now - timedelta(hours=10)),
            _thread("b", now - timedelta(hours=20)),
        ]
        # keep_recent >= 길이 → 모두 보존, 빈 결과.
        result = bot.select_threads_to_archive(
            threads, now_utc=now, age_hours=1, keep_recent=5
        )
        self.assertEqual(result, [])

    def test_age_minutes_short_threshold(self) -> None:
        """#1023 신규: age_minutes 옵션 — 분 단위 짧은 임계."""
        now = datetime(2026, 5, 24, 12, 0, tzinfo=timezone.utc)
        threads = [
            _thread("recent_5m", now - timedelta(minutes=5)),
            _thread("old_20m", now - timedelta(minutes=20)),
            _thread("old_60m", now - timedelta(minutes=60)),
        ]
        # age_minutes=15 → 5분 thread 는 제외, 20m/60m 만 후보.
        # keep_recent=0 → 모두 후보 풀.
        result = bot.select_threads_to_archive(
            threads, now_utc=now, age_minutes=15, keep_recent=0
        )
        ids = {t["id"] for t in result}
        self.assertEqual(ids, {"old_20m", "old_60m"})

    def test_age_minutes_overrides_age_hours(self) -> None:
        """age_minutes 가 None 아니면 age_hours 무시."""
        now = datetime(2026, 5, 24, 12, 0, tzinfo=timezone.utc)
        threads = [
            _thread("recent_5m", now - timedelta(minutes=5)),
            _thread("middle_30m", now - timedelta(minutes=30)),
        ]
        # age_minutes=15 우선 → 30m 만 archive 후보.
        result = bot.select_threads_to_archive(
            threads,
            now_utc=now,
            age_hours=24,  # 24시간 — 무시되어야 함
            age_minutes=15,
            keep_recent=0,
        )
        ids = {t["id"] for t in result}
        self.assertEqual(ids, {"middle_30m"})


# ─────────────────────────────────────────────────────────────────────────────
# 4) fetch_guild_id / fetch_active_threads / archive_thread / delete_thread — 8건
# ─────────────────────────────────────────────────────────────────────────────


class _FakeResp:
    def __init__(self, *, status_code=200, json_data=None, text="", ok=None):
        self.status_code = status_code
        self._json = json_data if json_data is not None else {}
        self.text = text
        self.ok = ok if ok is not None else (200 <= status_code < 300)

    def json(self):
        if isinstance(self._json, Exception):
            raise self._json
        return self._json


class HttpFetchersTest(unittest.TestCase):

    def test_fetch_guild_id_success(self) -> None:
        def fake_get(url, headers=None, timeout=None):
            self.assertIn("/channels/", url)
            return _FakeResp(json_data={"guild_id": "777"})

        result = bot.fetch_guild_id("123", "tok", http_get=fake_get)
        self.assertEqual(result, "777")

    def test_fetch_guild_id_returns_none_on_error(self) -> None:
        def fake_get(url, headers=None, timeout=None):
            return _FakeResp(status_code=500, text="err", ok=False)

        result = bot.fetch_guild_id("123", "tok", http_get=fake_get)
        self.assertIsNone(result)

    def test_fetch_active_threads_filters_by_parent(self) -> None:
        payload = {
            "threads": [
                {"id": "a", "parent_id": "123"},
                {"id": "b", "parent_id": "456"},  # 다른 채널
                {"id": "c", "parent_id": "123"},
            ]
        }

        def fake_get(url, headers=None, timeout=None):
            self.assertIn("/guilds/777/threads/active", url)
            return _FakeResp(json_data=payload)

        result = bot.fetch_active_threads("777", "123", "tok", http_get=fake_get)
        ids = [t["id"] for t in result]
        self.assertEqual(ids, ["a", "c"])

    def test_fetch_active_threads_empty_on_error(self) -> None:
        def fake_get(url, headers=None, timeout=None):
            raise RuntimeError("network down")

        result = bot.fetch_active_threads("777", "123", "tok", http_get=fake_get)
        self.assertEqual(result, [])

    def test_archive_thread_success(self) -> None:
        captured = {}

        def fake_patch(url, headers=None, json=None, timeout=None):
            captured["url"] = url
            captured["body"] = json
            return _FakeResp(status_code=204)

        ok = bot.archive_thread("thread-id", "tok", http_patch=fake_patch)
        self.assertTrue(ok)
        self.assertIn("/channels/thread-id", captured["url"])
        self.assertEqual(captured["body"], {"archived": True})

    def test_archive_thread_failure_returns_false(self) -> None:
        def fake_patch(url, headers=None, json=None, timeout=None):
            return _FakeResp(status_code=403, text="forbidden", ok=False)

        ok = bot.archive_thread("thread-id", "tok", http_patch=fake_patch)
        self.assertFalse(ok)

    def test_delete_thread_success(self) -> None:
        """#1023 신규: delete_thread DELETE /channels/{id} 성공."""
        captured = {}

        def fake_delete(url, headers=None, timeout=None):
            captured["url"] = url
            return _FakeResp(status_code=204)

        ok = bot.delete_thread("thread-id", "tok", http_delete=fake_delete)
        self.assertTrue(ok)
        self.assertIn("/channels/thread-id", captured["url"])

    def test_delete_thread_failure_returns_false(self) -> None:
        def fake_delete(url, headers=None, timeout=None):
            return _FakeResp(status_code=403, text="forbidden", ok=False)

        ok = bot.delete_thread("thread-id", "tok", http_delete=fake_delete)
        self.assertFalse(ok)


# ─────────────────────────────────────────────────────────────────────────────
# 5) thread_cleanup_loop — asyncio — 6건
# ─────────────────────────────────────────────────────────────────────────────


async def _run_iters(coro, iterations: int) -> None:
    task = asyncio.create_task(coro)
    for _ in range(iterations):
        await asyncio.sleep(0)
    task.cancel()
    try:
        await task
    except asyncio.CancelledError:
        pass


class ThreadCleanupLoopTest(unittest.IsolatedAsyncioTestCase):

    async def test_zero_threads_no_archive_calls(self) -> None:
        archives: list[str] = []

        async def noop_sleep(_):
            # asyncio.sleep(0) yields to scheduler — cancel 가 들어올 수 있게.
            await asyncio.sleep(0)

        coro = bot.thread_cleanup_loop(
            "123",
            "tok",
            poll_interval=1,
            initial_delay=0,
            guild_id_fetcher=lambda: "777",
            threads_fetcher=lambda gid: [],
            archiver=lambda tid: archives.append(tid) or True,
            sleeper=noop_sleep,
        )
        await _run_iters(coro, iterations=10)
        self.assertEqual(archives, [])

    async def test_old_threads_get_archived(self) -> None:
        now = datetime(2026, 5, 24, 12, 0, tzinfo=timezone.utc)
        threads = [
            _thread("recent_a", now - timedelta(minutes=10)),
            _thread("recent_b", now - timedelta(minutes=20)),
            _thread("recent_c", now - timedelta(minutes=30)),
            _thread("old_d", now - timedelta(hours=2)),
            _thread("old_e", now - timedelta(hours=3)),
        ]
        archives: list[str] = []

        async def noop_sleep(_):
            # asyncio.sleep(0) yields to scheduler — cancel 가 들어올 수 있게.
            await asyncio.sleep(0)

        coro = bot.thread_cleanup_loop(
            "123",
            "tok",
            poll_interval=1,
            initial_delay=0,
            age_hours=1,
            age_minutes=None,
            keep_recent=3,
            guild_id_fetcher=lambda: "777",
            threads_fetcher=lambda gid: threads,
            archiver=lambda tid: archives.append(tid) or True,
            sleeper=noop_sleep,
            now_source=lambda: now,
        )
        await _run_iters(coro, iterations=30)
        # old_d / old_e 가 적어도 1번씩은 archive 됐어야 함 (loop 반복으로 더 많을 수 있음).
        self.assertIn("old_d", archives)
        self.assertIn("old_e", archives)
        # recent 는 보존.
        self.assertNotIn("recent_a", archives)
        self.assertNotIn("recent_b", archives)
        self.assertNotIn("recent_c", archives)

    async def test_poll_interval_zero_disables_loop(self) -> None:
        called = {"count": 0}

        def fetch():
            called["count"] += 1
            return "777"

        async def noop_sleep(_):
            # asyncio.sleep(0) yields to scheduler — cancel 가 들어올 수 있게.
            await asyncio.sleep(0)

        await bot.thread_cleanup_loop(
            "123",
            "tok",
            poll_interval=0,
            initial_delay=0,
            guild_id_fetcher=fetch,
            threads_fetcher=lambda gid: [],
            archiver=lambda tid: True,
            sleeper=noop_sleep,
        )
        self.assertEqual(called["count"], 0)

    async def test_guild_id_none_retries(self) -> None:
        calls = {"count": 0}

        def fetch():
            calls["count"] += 1
            return None

        async def noop_sleep(_):
            # asyncio.sleep(0) yields to scheduler — cancel 가 들어올 수 있게.
            await asyncio.sleep(0)

        coro = bot.thread_cleanup_loop(
            "123",
            "tok",
            poll_interval=1,
            initial_delay=0,
            guild_id_fetcher=fetch,
            threads_fetcher=lambda gid: [],
            archiver=lambda tid: True,
            sleeper=noop_sleep,
        )
        await _run_iters(coro, iterations=10)
        # 매 iter 마다 재시도. >1.
        self.assertGreater(calls["count"], 1)

    async def test_delete_mode_calls_deleter_not_archiver(self) -> None:
        """#1023 신규: delete=True 면 archive 대신 delete 호출."""
        now = datetime(2026, 5, 24, 12, 0, tzinfo=timezone.utc)
        threads = [
            _thread("old_a", now - timedelta(hours=2)),
        ]
        archives: list[str] = []
        deletes: list[str] = []

        async def noop_sleep(_):
            # asyncio.sleep(0) yields to scheduler — cancel 가 들어올 수 있게.
            await asyncio.sleep(0)

        coro = bot.thread_cleanup_loop(
            "123",
            "tok",
            poll_interval=1,
            initial_delay=0,
            age_minutes=15,
            keep_recent=0,
            delete=True,
            guild_id_fetcher=lambda: "777",
            threads_fetcher=lambda gid: threads,
            archiver=lambda tid: archives.append(tid) or True,
            deleter=lambda tid: deletes.append(tid) or True,
            sleeper=noop_sleep,
            now_source=lambda: now,
        )
        await _run_iters(coro, iterations=20)
        # delete 모드 → archives 호출 0, deletes 호출 ≥ 1.
        self.assertEqual(archives, [])
        self.assertIn("old_a", deletes)

    async def test_age_minutes_threshold(self) -> None:
        """#1023 신규: age_minutes 임계 — 분 단위 짧은 cutoff."""
        now = datetime(2026, 5, 24, 12, 0, tzinfo=timezone.utc)
        threads = [
            _thread("fresh_5m", now - timedelta(minutes=5)),
            _thread("stale_20m", now - timedelta(minutes=20)),
        ]
        archives: list[str] = []

        async def noop_sleep(_):
            # asyncio.sleep(0) yields to scheduler — cancel 가 들어올 수 있게.
            await asyncio.sleep(0)

        coro = bot.thread_cleanup_loop(
            "123",
            "tok",
            poll_interval=1,
            initial_delay=0,
            age_minutes=15,
            keep_recent=0,
            guild_id_fetcher=lambda: "777",
            threads_fetcher=lambda gid: threads,
            archiver=lambda tid: archives.append(tid) or True,
            sleeper=noop_sleep,
            now_source=lambda: now,
        )
        await _run_iters(coro, iterations=20)
        # 20분짜리만 archive 후보, 5분은 fresh.
        self.assertIn("stale_20m", archives)
        self.assertNotIn("fresh_5m", archives)


if __name__ == "__main__":
    unittest.main()
