"""rev e2e 단계 2 (post-merge) 자동 trigger 단위 테스트 (#1008).

spec: docs/features/rev-e2e-3-stages.md §3-2.

검증 범위:
1. `fetch_rev_post_merge_candidates` — gh CLI 정상 / rc!=0 / JSON 부서짐 / timeout (4건).
2. `filter_debounced_prs` — fresh 모두 통과 / debounce hit / cache cap (3건).
3. `format_rev_post_merge_inject` / `format_rev_post_merge_discord` (2건).
4. `rev_post_merge_audit_loop` (asyncio mock) —
   - 후보 0 → inject/Discord 없음
   - 후보 N → inject + Discord push 각 1회
   - debounce hit → 같은 PR 두 번째 iter 에선 inject skip
   - tmux session 부재 → graceful skip
   - poll_interval=0 → 즉시 return (disabled)
   (5건)
"""

from __future__ import annotations

import asyncio
import json
import subprocess
import sys
import unittest
from pathlib import Path
from unittest import mock

PARENT_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PARENT_DIR))

# discord — 실제 모듈 우선 (embed 호환), 없으면 stub.
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
# 1) fetch_rev_post_merge_candidates — 4건
# ─────────────────────────────────────────────────────────────────────────────


class _FakeProc:
    def __init__(self, returncode=0, stdout="", stderr=""):
        self.returncode = returncode
        self.stdout = stdout
        self.stderr = stderr


class FetchRevPostMergeCandidatesTest(unittest.TestCase):

    def test_valid_json_returns_pr_numbers(self) -> None:
        payload = json.dumps(
            [
                {"number": 851, "title": "fix voice", "mergedAt": "2026-05-24T01:00:00Z"},
                {"number": 852, "title": "infra", "mergedAt": "2026-05-24T01:10:00Z"},
            ]
        )

        def fake_run(cmd, **kwargs):
            self.assertIn("gh", cmd)
            self.assertIn("--state", cmd)
            self.assertIn("merged", cmd)
            self.assertIn("--base", cmd)
            self.assertIn("develop", cmd)
            return _FakeProc(returncode=0, stdout=payload)

        result = bot.fetch_rev_post_merge_candidates(runner=fake_run)
        self.assertEqual(result, [851, 852])

    def test_nonzero_returncode_returns_empty(self) -> None:
        def fake_run(cmd, **kwargs):
            return _FakeProc(returncode=1, stderr="gh: auth required")

        self.assertEqual(bot.fetch_rev_post_merge_candidates(runner=fake_run), [])

    def test_malformed_json_returns_empty(self) -> None:
        def fake_run(cmd, **kwargs):
            return _FakeProc(returncode=0, stdout="{not valid")

        self.assertEqual(bot.fetch_rev_post_merge_candidates(runner=fake_run), [])

    def test_timeout_returns_empty(self) -> None:
        def fake_run(cmd, **kwargs):
            raise subprocess.TimeoutExpired(cmd=cmd, timeout=5)

        self.assertEqual(bot.fetch_rev_post_merge_candidates(runner=fake_run), [])


# ─────────────────────────────────────────────────────────────────────────────
# 2) filter_debounced_prs — 3건
# ─────────────────────────────────────────────────────────────────────────────


class FilterDebouncedPrsTest(unittest.TestCase):

    def test_all_fresh_when_cache_empty(self) -> None:
        cache: dict[int, float] = {}
        result = bot.filter_debounced_prs(
            [1, 2, 3],
            cache,
            now_monotonic=100.0,
            debounce_seconds=900,
        )
        self.assertEqual(result, [1, 2, 3])

    def test_debounce_hit_removed(self) -> None:
        cache: dict[int, float] = {1: 99.0, 3: 50.0}
        # debounce_seconds=900 — 1 은 now-99=1 초 < 900, 3 은 now-50=50 초 < 900.
        # 2 는 cache 없음 → fresh.
        result = bot.filter_debounced_prs(
            [1, 2, 3],
            cache,
            now_monotonic=100.0,
            debounce_seconds=900,
        )
        self.assertEqual(result, [2])

    def test_cache_cap_truncates_oldest(self) -> None:
        # max_entries=3, cache 5 entries → 2 oldest 제거 (가장 작은 ts 부터).
        cache: dict[int, float] = {10: 1.0, 11: 2.0, 12: 3.0, 13: 4.0, 14: 5.0}
        bot.filter_debounced_prs(
            [],
            cache,
            now_monotonic=100.0,
            debounce_seconds=900,
            max_entries=3,
        )
        # 1.0, 2.0 (oldest 2) 제거 → 12,13,14 만 남음.
        self.assertEqual(set(cache.keys()), {12, 13, 14})


# ─────────────────────────────────────────────────────────────────────────────
# 3) format_* — 2건
# ─────────────────────────────────────────────────────────────────────────────


class FormatRevPostMergeTest(unittest.TestCase):

    def test_format_inject_joins_pr_numbers(self) -> None:
        text = bot.format_rev_post_merge_inject([100, 101])
        self.assertIn("#100,#101", text)
        self.assertIn("rev e2e post-merge", text)
        self.assertIn("rev-post-merge-pass", text)

    def test_format_discord_joins_pr_numbers(self) -> None:
        text = bot.format_rev_post_merge_discord([200])
        self.assertIn("#200", text)
        # (#1447) narrative + '감사' 금지 — 옛 로그형 "rev post-merge audit trigger" 폐기
        self.assertIn("머지됐으므로", text)
        self.assertIn("코드 리뷰", text)


# ─────────────────────────────────────────────────────────────────────────────
# 4) rev_post_merge_audit_loop — asyncio mock — 5건
# ─────────────────────────────────────────────────────────────────────────────


class FakeChannel:
    def __init__(self) -> None:
        self.sent: list[str] = []

    async def send(self, content=None, embed=None) -> None:
        if content is not None:
            self.sent.append(content)


class FakeClient:
    def __init__(self, channel: FakeChannel | None) -> None:
        self._channel = channel

    def get_channel(self, channel_id: int):
        return self._channel


async def _run_loop_iters(loop_coro, iterations: int) -> None:
    """loop coroutine 을 task 로 띄우고 iterations 만큼 양보 후 cancel."""
    task = asyncio.create_task(loop_coro)
    for _ in range(iterations):
        await asyncio.sleep(0)
    task.cancel()
    try:
        await task
    except asyncio.CancelledError:
        pass


class RevPostMergeAuditLoopTest(unittest.IsolatedAsyncioTestCase):

    async def test_zero_candidates_no_inject(self) -> None:
        channel = FakeChannel()
        client = FakeClient(channel)
        with mock.patch.object(bot, "tmux_has_session", return_value=True), \
             mock.patch.object(bot, "tmux_inject_text", return_value=True) as inject:
            coro = bot.rev_post_merge_audit_loop(
                client,
                digest_channel_id=222,
                inject_target="mobruji:0.0",
                poll_interval=1,
                initial_delay=0,
                candidate_fetcher=lambda: [],
            )
            await _run_loop_iters(coro, iterations=5)
        inject.assert_not_called()
        self.assertEqual(channel.sent, [])

    async def test_candidates_emit_event_and_push(self) -> None:
        # (#1447) tmux inject → post_merge_review_requested event 발행으로 전환.
        channel = FakeChannel()
        client = FakeClient(channel)
        with mock.patch.object(bot, "append_agent_event", return_value=1) as emit:
            coro = bot.rev_post_merge_audit_loop(
                client,
                digest_channel_id=222,
                inject_target="mobruji:0.0",
                poll_interval=1,
                initial_delay=0,
                candidate_fetcher=lambda: [851],
            )
            await _run_loop_iters(coro, iterations=5)
        self.assertGreaterEqual(emit.call_count, 1)
        kind, payload = emit.call_args_list[0].args
        self.assertEqual(kind, "post_merge_review_requested")
        self.assertEqual(payload["pr_number"], 851)
        # Discord narrative push 에 PR 번호 포함.
        self.assertTrue(any("#851" in m for m in channel.sent))

    async def test_debounce_prevents_repeat_event_same_pr(self) -> None:
        channel = FakeChannel()
        client = FakeClient(channel)
        fake_mono = [1000.0]
        with mock.patch.object(bot, "append_agent_event", return_value=1) as emit:
            coro = bot.rev_post_merge_audit_loop(
                client,
                digest_channel_id=222,
                inject_target="mobruji:0.0",
                poll_interval=1,
                initial_delay=0,
                debounce_seconds=900,
                candidate_fetcher=lambda: [777],
                time_source=lambda: fake_mono[0],
            )
            await _run_loop_iters(coro, iterations=20)
        # 같은 PR → event 정확히 1회 (이후 iter 는 debounce 적중).
        self.assertEqual(emit.call_count, 1)
        self.assertEqual(sum(1 for m in channel.sent if "#777" in m), 1)

    async def test_poll_interval_zero_disables_loop(self) -> None:
        channel = FakeChannel()
        client = FakeClient(channel)
        called = {"count": 0}

        def fake_fetch() -> list[int]:
            called["count"] += 1
            return [1]

        with mock.patch.object(bot, "tmux_has_session", return_value=True), \
             mock.patch.object(bot, "tmux_inject_text", return_value=True) as inject:
            # poll_interval=0 → 즉시 return.
            await bot.rev_post_merge_audit_loop(
                client,
                digest_channel_id=222,
                inject_target="mobruji:0.0",
                poll_interval=0,
                initial_delay=0,
                candidate_fetcher=fake_fetch,
            )
        # fetch 호출 0 — initial_delay 도 안 거치고 return.
        self.assertEqual(called["count"], 0)
        inject.assert_not_called()


if __name__ == "__main__":
    unittest.main()
