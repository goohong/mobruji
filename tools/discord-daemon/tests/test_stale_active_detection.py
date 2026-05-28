"""STALE_ACTIVE 검출 단위 테스트 (#1015 follow-up — P3a remediation).

배경: 9h stale incident root cause Layer 2 fix.
기존 cycle_idle_watch_loop 의 detect 정의가
``in_progress=null AND last_completed.completed_at > now - 10min`` 만 검사 →
``in_progress`` 가 set 됐는데 ``started_at`` 만 stale 인 케이스 미검출.

검증 범위:
1. detect_idle_worktrees stale_active 검출 — 4건
   - started_at 1h 전 → STALE_ACTIVE 검출
   - started_at 30분 전 (임계 60분 미만) → 검출 안 함
   - in_progress=null + last_completed 9h 전 → 기존 idle 검출 (regression guard)
   - stale_active_enabled=False → 검출 안 함 (후방호환)
2. cycle_idle_watch_loop 통합 — 2건
   - STALE_ACTIVE 발생 시 inject + Discord push
   - escalation 카운트 stale_active 도 누적
"""

from __future__ import annotations

import asyncio
import json
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
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
# 1) detect_idle_worktrees — STALE_ACTIVE 4건
# ─────────────────────────────────────────────────────────────────────────────


class DetectStaleActiveTest(unittest.TestCase):
    """detect_idle_worktrees stale_active 분기."""

    def setUp(self) -> None:
        self.now = datetime(2026, 5, 24, 12, 0, 0, tzinfo=timezone.utc)

    def _entry_active(self, started_minutes_ago: int) -> dict:
        started_at = self.now - timedelta(minutes=started_minutes_ago)
        return {
            "in_progress": {
                "issue": "#999",
                "title": "frozen sub-agent",
                "started_at": started_at.isoformat().replace("+00:00", "Z"),
            },
            "last_completed": None,
        }

    def _entry_idle_long(self, hours_ago: int) -> dict:
        completed_at = self.now - timedelta(hours=hours_ago)
        return {
            "in_progress": None,
            "last_completed": {
                "pr": "#100",
                "title": "old",
                "completed_at": completed_at.isoformat().replace("+00:00", "Z"),
            },
        }

    def test_stale_active_started_1h_ago_detected(self) -> None:
        """in_progress.started_at 이 60분 전 (임계 60분 도달) → STALE_ACTIVE 검출."""
        # 임계 정확히 60분 — `>` 비교라 60분 0초는 검출 안 함. 61분으로 확정 검출.
        status = {"be": self._entry_active(started_minutes_ago=61)}
        result = bot.detect_idle_worktrees(
            status,
            threshold_minutes=10,
            now=self.now,
            workspaces=["be"],
            stale_active_enabled=True,
            stale_active_threshold_minutes=60,
        )
        self.assertEqual(len(result), 1)
        self.assertEqual(result[0]["workspace"], "be")
        self.assertTrue(result[0]["is_stale_active"])
        self.assertIsNotNone(result[0]["started_at"])
        self.assertEqual(result[0]["stale_title"], "frozen sub-agent")

    def test_stale_active_started_30m_ago_not_detected(self) -> None:
        """started_at 이 30분 전 (임계 60분 미만) → 검출 안 함 (정상 active)."""
        status = {"be": self._entry_active(started_minutes_ago=30)}
        result = bot.detect_idle_worktrees(
            status,
            threshold_minutes=10,
            now=self.now,
            workspaces=["be"],
            stale_active_enabled=True,
            stale_active_threshold_minutes=60,
        )
        self.assertEqual(result, [])

    def test_regular_idle_still_detected_when_stale_active_enabled(self) -> None:
        """기존 idle 검출 (in_progress=null + last_completed 임계 초과) 유지 — regression guard."""
        status = {"be": self._entry_idle_long(hours_ago=9)}
        result = bot.detect_idle_worktrees(
            status,
            threshold_minutes=10,
            now=self.now,
            workspaces=["be"],
            stale_active_enabled=True,
            stale_active_threshold_minutes=60,
        )
        self.assertEqual(len(result), 1)
        self.assertEqual(result[0]["workspace"], "be")
        # 기존 idle path — is_stale_active=False.
        self.assertFalse(result[0]["is_stale_active"])
        self.assertIsNone(result[0]["started_at"])

    def test_stale_active_disabled_skips_detection(self) -> None:
        """stale_active_enabled=False → started_at 9h 전이어도 검출 안 함 (후방호환)."""
        status = {"be": self._entry_active(started_minutes_ago=9 * 60)}
        result = bot.detect_idle_worktrees(
            status,
            threshold_minutes=10,
            now=self.now,
            workspaces=["be"],
            stale_active_enabled=False,
        )
        self.assertEqual(result, [])


# ─────────────────────────────────────────────────────────────────────────────
# 2) cycle_idle_watch_loop — STALE_ACTIVE 통합 2건
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


class FakeMultiChannelClient:
    def __init__(self, channels: dict[int, "FakeChannel"]) -> None:
        self._channels = channels

    def get_channel(self, channel_id: int):
        return self._channels.get(channel_id)


async def _run_loop_iters(loop_coro, iterations: int) -> None:
    task = asyncio.create_task(loop_coro)
    for _ in range(iterations):
        await asyncio.sleep(0)
    task.cancel()
    try:
        await task
    except asyncio.CancelledError:
        pass


class CycleIdleWatchStaleActiveLoopTest(unittest.IsolatedAsyncioTestCase):

    def _write_status(self, dir_path: Path, payload: dict) -> Path:
        path = dir_path / "cycle.json"
        path.write_text(json.dumps(payload), encoding="utf-8")
        return path

    def _stale_be_payload(self, now: datetime) -> dict:
        """be 워크트리 9h stale_active + fe/rev/plan 정상 active."""
        stale_started_at = (now - timedelta(hours=9)).isoformat().replace(
            "+00:00", "Z"
        )
        fresh_started_at = (now - timedelta(minutes=5)).isoformat().replace(
            "+00:00", "Z"
        )
        return {
            "be": {
                "in_progress": {
                    "issue": "#777",
                    "title": "long-frozen audit",
                    "started_at": stale_started_at,
                },
                "last_completed": None,
            },
            "fe": {
                "in_progress": {
                    "issue": "#1",
                    "title": "active",
                    "started_at": fresh_started_at,
                },
                "last_completed": None,
            },
            "rev": {
                "in_progress": {
                    "target": "X",
                    "title": "audit",
                    "started_at": fresh_started_at,
                },
                "last_completed": None,
            },
            "plan": {
                "in_progress": {
                    "issue": "#2",
                    "title": "doc",
                    "started_at": fresh_started_at,
                },
                "last_completed": None,
            },
        }

    async def test_stale_active_triggers_inject_and_discord_push(self) -> None:
        """STALE_ACTIVE 발생 시 watchdog inject + Discord push 발사."""
        with tempfile.TemporaryDirectory() as tmp_dir:
            now = datetime(2026, 5, 24, 12, 0, 0, tzinfo=timezone.utc)
            status_path = self._write_status(
                Path(tmp_dir), self._stale_be_payload(now)
            )
            channel = FakeChannel()
            client = FakeClient(channel)

            with mock.patch.object(bot, "tmux_has_session", return_value=True), \
                 mock.patch.object(bot, "tmux_inject_text", return_value=True) as inject:
                coro = bot.cycle_idle_watch_loop(
                    client,
                    digest_channel_id=222,
                    cycle_status_path=str(status_path),
                    inject_target="mobruji:0.0",
                    threshold_minutes=10,
                    poll_interval=0,
                    workspaces=["be", "fe", "rev", "plan"],
                    debounce_seconds=900,
                    reason_required=True,
                    stale_active_enabled=True,
                    stale_active_threshold_minutes=60,
                    now_provider=lambda: now,
                )
                await _run_loop_iters(coro, iterations=5)

            # inject 호출 — STALE_ACTIVE template 발사.
            self.assertGreaterEqual(inject.call_count, 1)
            inject_text = inject.call_args_list[0].args[1]
            self.assertIn("STALE_ACTIVE", inject_text)
            self.assertIn("be", inject_text)
            self.assertIn("started_at", inject_text)
            # Discord push — STALE_ACTIVE 라벨 + sub-agent freeze 추정 문구.
            self.assertEqual(len(channel.sent), 1)
            discord_text = channel.sent[0]
            self.assertIn("STALE_ACTIVE", discord_text)
            self.assertIn("be", discord_text)
            self.assertIn("sub-agent freeze 또는 nmae 완료 통지 처리 누락 추정", discord_text)

    async def test_escalation_counter_includes_stale_active(self) -> None:
        """STALE_ACTIVE 도 escalation 카운트 누적 — threshold 도달 시 사용자 채널 push."""
        with tempfile.TemporaryDirectory() as tmp_dir:
            now = datetime(2026, 5, 24, 12, 0, 0, tzinfo=timezone.utc)
            status_path = self._write_status(
                Path(tmp_dir), self._stale_be_payload(now)
            )
            notify_channel = FakeChannel()
            escalation_channel = FakeChannel()
            client = FakeMultiChannelClient({
                111: notify_channel,
                222: escalation_channel,
            })
            mono_state = [1000.0]

            def advancing_time() -> float:
                mono_state[0] += 1000.0
                return mono_state[0]

            with mock.patch.object(bot, "tmux_has_session", return_value=True), \
                 mock.patch.object(bot, "tmux_inject_text", return_value=True):
                coro = bot.cycle_idle_watch_loop(
                    client,
                    digest_channel_id=111,
                    cycle_status_path=str(status_path),
                    inject_target="mobruji:0.0",
                    threshold_minutes=10,
                    poll_interval=0,
                    workspaces=["be", "fe", "rev", "plan"],
                    debounce_seconds=1,
                    reason_required=True,
                    escalation_threshold=3,
                    escalation_debounce_seconds=3600,
                    escalation_channel_id=222,
                    stale_active_enabled=True,
                    stale_active_threshold_minutes=60,
                    time_source=advancing_time,
                    now_provider=lambda: now,
                )
                await _run_loop_iters(coro, iterations=20)

            # notify 채널 push >= 3 (각 iter 마다 fresh idle 발사).
            self.assertGreaterEqual(len(notify_channel.sent), 3)
            # escalation push 적어도 1회 발사 — STALE_ACTIVE 도 inject_count 누적함을 검증.
            self.assertGreaterEqual(len(escalation_channel.sent), 1)
            escalate_msg = escalation_channel.sent[0]
            self.assertIn("be", escalate_msg)
            self.assertIn("3회 연속", escalate_msg)


if __name__ == "__main__":
    unittest.main()
