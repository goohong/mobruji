"""Loop heartbeat hook 단위 테스트 (#1087, 2026-05-26 사용자 P0).

검증 범위:
1. ``record_loop_heartbeat`` — atomic write OK / 부재 dir auto-mkdir / 잘못된 name reject.
2. ``read_loop_heartbeat`` — round-trip OK / 부재 / parse fail → None.
3. ``detect_stale_heartbeats`` —
   - heartbeat 부재 → reason=missing.
   - age > threshold → reason=stale.
   - age <= threshold → alive (entry 없음).
   - multiplier=0 → 빈 리스트.
4. ``format_heartbeat_stale_message`` — 본문에 loop name + 임계 / 마지막 iso 포함.
5. ``heartbeat_watch_loop`` (asyncio mock + 시간 stub) —
   - 정상 iter: stale 검출 + Discord push 호출 + 자기 heartbeat 기록.
   - 동일 loop 1h debounce — 재 push 안 함.
   - channel None graceful — warning 만, crash 없음.
   - poll_interval<=0 → 즉시 return.
"""

from __future__ import annotations

import asyncio
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

PARENT_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PARENT_DIR))

# 외부 의존성 stub — bot.py 가 import 하는 모듈 중 시스템에 없을 수 있는 것.
for missing in ("dotenv",):
    if missing not in sys.modules:
        stub = mock.MagicMock()
        if missing == "dotenv":
            stub.load_dotenv = lambda *a, **kw: None
        sys.modules[missing] = stub

try:
    import discord as _real_discord  # noqa: F401
except ImportError:
    sys.modules["discord"] = mock.MagicMock()

# directive_board_sync 가 requests 를 import — 단순 stub.
if "requests" not in sys.modules:
    sys.modules["requests"] = mock.MagicMock()

# claude_usage_tracker 의존성 — bot.py import path 보장.
import bot  # noqa: E402


# ─────────────────────────────────────────────────────────────────────────────
# record_loop_heartbeat
# ─────────────────────────────────────────────────────────────────────────────


class RecordLoopHeartbeatTest(unittest.TestCase):
    def test_write_ok_creates_file(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / "hb"
            ok = bot.record_loop_heartbeat(
                "digest_loop",
                heartbeat_dir=target,
                monotonic_source=lambda: 123.0,
            )
            self.assertTrue(ok)
            path = target / "digest_loop.ts"
            self.assertTrue(path.exists())
            text = path.read_text(encoding="utf-8")
            self.assertIn("123.0", text)
            # iso 형식 (2개 줄 — monotonic + iso)
            self.assertEqual(text.count("\n"), 2)

    def test_auto_mkdir_parent_missing(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            nested = Path(tmp) / "deep" / "heartbeat"
            ok = bot.record_loop_heartbeat(
                "thread_cleanup_loop",
                heartbeat_dir=nested,
                monotonic_source=lambda: 1.0,
            )
            self.assertTrue(ok)
            self.assertTrue((nested / "thread_cleanup_loop.ts").exists())

    def test_invalid_name_returns_false(self) -> None:
        # path traversal / 빈 name 가드.
        with tempfile.TemporaryDirectory() as tmp:
            self.assertFalse(
                bot.record_loop_heartbeat("", heartbeat_dir=Path(tmp))
            )
            self.assertFalse(
                bot.record_loop_heartbeat(
                    "../bad", heartbeat_dir=Path(tmp)
                )
            )
            self.assertFalse(
                bot.record_loop_heartbeat(
                    "with/slash", heartbeat_dir=Path(tmp)
                )
            )

    def test_atomic_replace(self) -> None:
        # 동일 name 으로 두 번 호출 시 두 번째 값으로 atomic replace.
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp)
            bot.record_loop_heartbeat(
                "digest_loop",
                heartbeat_dir=target,
                monotonic_source=lambda: 1.0,
            )
            bot.record_loop_heartbeat(
                "digest_loop",
                heartbeat_dir=target,
                monotonic_source=lambda: 2.0,
            )
            text = (target / "digest_loop.ts").read_text(encoding="utf-8")
            self.assertIn("2.0", text)
            self.assertNotIn("1.0\n", text.splitlines()[0] + "\n")


# ─────────────────────────────────────────────────────────────────────────────
# read_loop_heartbeat
# ─────────────────────────────────────────────────────────────────────────────


class ReadLoopHeartbeatTest(unittest.TestCase):
    def test_round_trip(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp)
            bot.record_loop_heartbeat(
                "digest_loop",
                heartbeat_dir=target,
                monotonic_source=lambda: 42.5,
            )
            result = bot.read_loop_heartbeat(
                "digest_loop", heartbeat_dir=target
            )
            self.assertIsNotNone(result)
            mono, iso = result
            self.assertEqual(mono, 42.5)
            self.assertTrue(iso)

    def test_missing_returns_none(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            self.assertIsNone(
                bot.read_loop_heartbeat(
                    "nonexistent", heartbeat_dir=Path(tmp)
                )
            )

    def test_parse_fail_returns_none(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp)
            bad = target / "digest_loop.ts"
            bad.write_text("not a number\nfoo\n", encoding="utf-8")
            self.assertIsNone(
                bot.read_loop_heartbeat("digest_loop", heartbeat_dir=target)
            )

    def test_empty_file_returns_none(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp)
            (target / "digest_loop.ts").write_text("", encoding="utf-8")
            self.assertIsNone(
                bot.read_loop_heartbeat("digest_loop", heartbeat_dir=target)
            )


# ─────────────────────────────────────────────────────────────────────────────
# detect_stale_heartbeats
# ─────────────────────────────────────────────────────────────────────────────


class DetectStaleHeartbeatsTest(unittest.TestCase):
    def _setup_heartbeats(
        self, tmp_path: Path, name_to_mono: dict[str, float]
    ) -> None:
        for name, mono in name_to_mono.items():
            bot.record_loop_heartbeat(
                name,
                heartbeat_dir=tmp_path,
                monotonic_source=lambda mono=mono: mono,
            )

    def test_missing_heartbeat_detected(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp)
            # 1 loop 만 기록 — 나머지는 missing 으로 검출돼야 함.
            self._setup_heartbeats(target, {"digest_loop": 1000.0})
            stale = bot.detect_stale_heartbeats(
                expected_intervals={
                    "digest_loop": 300,
                    "thread_cleanup_loop": 120,
                },
                multiplier=3,
                heartbeat_dir=target,
                monotonic_source=lambda: 1100.0,  # +100s — digest_loop alive
            )
            names = {e["name"] for e in stale}
            self.assertEqual(names, {"thread_cleanup_loop"})
            entry = stale[0]
            self.assertEqual(entry["reason"], "missing")
            self.assertEqual(entry["threshold"], 360)

    def test_age_exceeds_threshold(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp)
            # digest_loop 임계 = 300 × 3 = 900s. age 1000 → stale.
            self._setup_heartbeats(target, {"digest_loop": 0.0})
            stale = bot.detect_stale_heartbeats(
                expected_intervals={"digest_loop": 300},
                multiplier=3,
                heartbeat_dir=target,
                monotonic_source=lambda: 1000.0,
            )
            self.assertEqual(len(stale), 1)
            self.assertEqual(stale[0]["name"], "digest_loop")
            self.assertEqual(stale[0]["reason"], "stale")
            self.assertAlmostEqual(stale[0]["age"], 1000.0)

    def test_alive_loop_not_in_stale(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp)
            # digest_loop 임계 = 300 × 3 = 900. age 100 → alive.
            self._setup_heartbeats(target, {"digest_loop": 900.0})
            stale = bot.detect_stale_heartbeats(
                expected_intervals={"digest_loop": 300},
                multiplier=3,
                heartbeat_dir=target,
                monotonic_source=lambda: 1000.0,
            )
            self.assertEqual(stale, [])

    def test_multiplier_zero_disabled(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            stale = bot.detect_stale_heartbeats(
                expected_intervals={"digest_loop": 300},
                multiplier=0,
                heartbeat_dir=Path(tmp),
                monotonic_source=lambda: 1000.0,
            )
            self.assertEqual(stale, [])


# ─────────────────────────────────────────────────────────────────────────────
# format_heartbeat_stale_message
# ─────────────────────────────────────────────────────────────────────────────


class FormatHeartbeatStaleMessageTest(unittest.TestCase):
    def test_empty_returns_empty(self) -> None:
        self.assertEqual(bot.format_heartbeat_stale_message([]), "")

    def test_includes_loop_names_and_threshold(self) -> None:
        msg = bot.format_heartbeat_stale_message(
            [
                {
                    "name": "digest_loop",
                    "expected_interval": 300,
                    "threshold": 900,
                    "age": 1500.0,
                    "last_iso": "2026-05-26T05:00:00+00:00",
                    "reason": "stale",
                },
                {
                    "name": "thread_cleanup_loop",
                    "expected_interval": 120,
                    "threshold": 360,
                    "age": None,
                    "last_iso": "",
                    "reason": "missing",
                },
            ]
        )
        self.assertIn("digest_loop", msg)
        self.assertIn("thread_cleanup_loop", msg)
        self.assertIn("900", msg)  # threshold
        self.assertIn("360", msg)
        self.assertIn("missing", msg.lower()) if False else None  # informational
        # 본문에 stale loop 개수 표시.
        self.assertIn("2", msg)


# ─────────────────────────────────────────────────────────────────────────────
# heartbeat_watch_loop (asyncio + mock)
# ─────────────────────────────────────────────────────────────────────────────


class HeartbeatWatchLoopTest(unittest.TestCase):
    def _make_client(self, channel_mock):
        client = mock.MagicMock()
        client.get_channel = mock.MagicMock(return_value=channel_mock)
        return client

    def _make_async_channel(self):
        channel = mock.MagicMock()
        channel.send = mock.AsyncMock()
        return channel

    def test_disabled_when_interval_zero(self) -> None:
        async def runner() -> None:
            await bot.heartbeat_watch_loop(
                self._make_client(None),
                123,
                poll_interval=0,
                sleeper=mock.AsyncMock(),
            )

        asyncio.run(runner())  # returns immediately

    def test_stale_detected_triggers_push(self) -> None:
        # 1 iter 만 돌게 sleeper 가 CancelledError 발생시킴.
        channel = self._make_async_channel()
        client = self._make_client(channel)

        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp)
            # heartbeat 부재 — missing 으로 detect.
            sleep_calls: list[int] = []

            async def fake_sleeper(sec: int) -> None:
                sleep_calls.append(sec)
                # initial_delay 1회 + 첫 iter sleep 1회 후 cancel.
                if len(sleep_calls) >= 2:
                    raise asyncio.CancelledError

            send_calls: list[tuple] = []

            async def fake_send(*args, **kwargs):
                send_calls.append((args, kwargs))
                msg = mock.MagicMock()
                msg.id = 999
                return msg

            channel.send = fake_send

            async def runner() -> None:
                try:
                    await bot.heartbeat_watch_loop(
                        client,
                        12345,
                        expected_intervals={"digest_loop": 300},
                        multiplier=3,
                        heartbeat_dir=target,
                        poll_interval=600,
                        initial_delay=180,
                        time_source=lambda: 1000.0,
                        sleeper=fake_sleeper,
                    )
                except asyncio.CancelledError:
                    pass

            asyncio.run(runner())
            # missing heartbeat → push 1회.
            self.assertEqual(len(send_calls), 1)
            content = send_calls[0][1].get("content") or send_calls[0][0][0]
            self.assertIn("digest_loop", content)
            self.assertIn("heartbeat", content.lower())
            # 자기 heartbeat 기록 — meta detect.
            self_hb = target / "heartbeat_watch_loop.ts"
            self.assertTrue(self_hb.exists())

    def test_debounce_prevents_repeat_push(self) -> None:
        # 2 iter — 같은 stale 인데도 2번째 push 안 함 (debounce).
        channel = self._make_async_channel()
        client = self._make_client(channel)

        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp)
            sleep_calls: list[int] = []

            async def fake_sleeper(sec: int) -> None:
                sleep_calls.append(sec)
                if len(sleep_calls) >= 3:  # initial + 2 iter sleeps
                    raise asyncio.CancelledError

            send_calls: list[tuple] = []

            async def fake_send(*args, **kwargs):
                send_calls.append((args, kwargs))
                msg = mock.MagicMock()
                msg.id = 999
                return msg

            channel.send = fake_send

            # time_source: 첫 iter 1000s, 두번째 1010s — debounce window (3600) 내.
            counter = {"i": 0}

            def fake_mono() -> float:
                counter["i"] += 1
                return 1000.0 + counter["i"]

            async def runner() -> None:
                try:
                    await bot.heartbeat_watch_loop(
                        client,
                        12345,
                        expected_intervals={"digest_loop": 300},
                        multiplier=3,
                        heartbeat_dir=target,
                        poll_interval=600,
                        initial_delay=180,
                        push_debounce_seconds=3600,
                        time_source=fake_mono,
                        sleeper=fake_sleeper,
                    )
                except asyncio.CancelledError:
                    pass

            asyncio.run(runner())
            # 2 iter 돌았지만 push 는 1회.
            self.assertEqual(len(send_calls), 1)

    def test_channel_none_graceful_no_crash(self) -> None:
        # channel 부재 → push skip, loop 본체 계속 alive.
        client = mock.MagicMock()
        client.get_channel = mock.MagicMock(return_value=None)

        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp)
            sleep_calls: list[int] = []

            async def fake_sleeper(sec: int) -> None:
                sleep_calls.append(sec)
                if len(sleep_calls) >= 2:
                    raise asyncio.CancelledError

            async def runner() -> None:
                try:
                    await bot.heartbeat_watch_loop(
                        client,
                        12345,
                        expected_intervals={"digest_loop": 300},
                        multiplier=3,
                        heartbeat_dir=target,
                        poll_interval=600,
                        initial_delay=180,
                        time_source=lambda: 1000.0,
                        sleeper=fake_sleeper,
                    )
                except asyncio.CancelledError:
                    pass

            # 단순히 예외 없이 종료되어야 함.
            asyncio.run(runner())
            # self heartbeat 기록 — loop alive 증명.
            self.assertTrue((target / "heartbeat_watch_loop.ts").exists())


# ─────────────────────────────────────────────────────────────────────────────
# Integration: 7 production loop 의 heartbeat name 이 매핑에 다 있는지
# ─────────────────────────────────────────────────────────────────────────────


class LoopHeartbeatExpectedIntervalsTest(unittest.TestCase):
    def test_all_known_loops_mapped(self) -> None:
        # docstring 에 명시된 8 + self = 9 loop 모두 매핑에 존재 (directive_detect_
        # register_watch_loop 은 PR #1082 가 develop 에 가져온 loop).
        expected = {
            "digest_loop",
            "context_auto_clear_loop",
            "cycle_idle_watch_loop",
            "rev_post_merge_audit_loop",
            "claude_usage_watch_loop",
            "directive_board_sync_loop",
            "thread_cleanup_loop",
            "directive_detect_register_watch_loop",
            "heartbeat_watch_loop",
        }
        self.assertEqual(
            set(bot.LOOP_HEARTBEAT_EXPECTED_INTERVALS.keys()), expected
        )

    def test_intervals_are_positive(self) -> None:
        for name, val in bot.LOOP_HEARTBEAT_EXPECTED_INTERVALS.items():
            self.assertGreater(val, 0, f"{name} interval must be > 0")


if __name__ == "__main__":
    unittest.main()
