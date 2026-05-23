"""maestro watcher exponential backoff + buffer cap + drop counter 단위 테스트 (#764).

배경: PR #761 (chunk 재시도) round 2 audit 에서 두 약점 발견.
  A. 재시도 사이 sleep 없음 → transient outage 시 1초 안에 3회 burst 후 drop.
  B. pending 재시도 중 buffer `+=` 무한 누적 → Discord 장기 outage 시 메모리 폭주.

이 테스트는 다음을 결정적으로 검증:
  1. 재시도 시 exponential backoff sleep 이 추가 호출된다 (poll_interval 외).
  2. buffer 가 MAX_BUFFER_LEN 을 초과하면 oldest 절반이 drop 되고 counter 가 증가한다.
  3. MAX_RETRIES 초과 drop 시 `maestro_watcher_counters['send_drop']` 가 증가한다.

표준 라이브러리 unittest + unittest.mock 만 사용 (pytest 로도 자동 수집).
"""

from __future__ import annotations

import asyncio
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

PARENT_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PARENT_DIR))

# discord / dotenv / requests stub. test_maestro_watcher_send_retry 와 동일.
for missing in ("discord", "requests", "dotenv"):
    if missing not in sys.modules:
        stub = mock.MagicMock()
        if missing == "dotenv":
            stub.load_dotenv = lambda *a, **kw: None
        sys.modules[missing] = stub

import bot  # noqa: E402


def _make_long_chunk(label: str) -> str:
    """sanitize_chunk MIN_CHUNK_LEN(100) 통과를 위한 충분히 긴 chunk."""
    body = f"maestro 응답 chunk [{label}]: 테스트 케이스. " + ("필요 컨텍스트 " * 20)
    return body + "\n"


class _FakeChannel:
    def __init__(self, send_fail_count: int = 0) -> None:
        self.send_fail_count = send_fail_count
        self.send_calls: list[str] = []

    async def send(self, content: str) -> None:
        self.send_calls.append(content)
        if len(self.send_calls) <= self.send_fail_count:
            raise RuntimeError(f"simulated transient send failure #{len(self.send_calls)}")


class _FakeClient:
    def __init__(self, channel: _FakeChannel | None) -> None:
        self._channel = channel

    def get_channel(self, channel_id: int) -> _FakeChannel | None:  # noqa: ARG002
        return self._channel


class _FakeLedger:
    def __init__(self) -> None:
        self.processed: set[str] = set()

    def is_processed(self, sig: str) -> bool:
        return sig in self.processed

    def mark_processed(self, sig: str) -> None:
        self.processed.add(sig)


class _LoopHarness:
    """poll_interval sleep 과 backoff sleep 을 모두 캡처.

    sleep_calls: 모든 sleep 호출의 (iter_count, seconds) 튜플 기록.
    iter_count 는 sleep 호출 횟수와 동일하며, max_iterations 도달 시 CancelledError.
    """

    def __init__(
        self,
        *,
        max_iterations: int,
        time_per_iter: float = 5.0,
    ) -> None:
        self.iter_count = 0
        self.max_iterations = max_iterations
        self.time_per_iter = time_per_iter
        self._current_time = 0.0
        self.sleep_calls: list[tuple[int, float]] = []

    def time_source(self) -> float:
        return self._current_time

    async def sleep(self, seconds: float) -> None:
        self.iter_count += 1
        self.sleep_calls.append((self.iter_count, seconds))
        self._current_time += self.time_per_iter
        if self.iter_count >= self.max_iterations:
            raise asyncio.CancelledError()


def _run_loop_until_cancel(coro):
    try:
        asyncio.run(coro)
    except asyncio.CancelledError:
        pass


class MaestroWatcherBackoffTests(unittest.TestCase):
    """재시도 시 exponential backoff sleep 이 추가 호출되는지 검증."""

    def setUp(self) -> None:
        self._tmpdir = tempfile.TemporaryDirectory()
        self.pipe_path = Path(self._tmpdir.name) / "maestro.log"
        self.pipe_path.touch()
        # counter 초기화 (테스트 간 격리).
        bot.maestro_watcher_counters["send_drop"] = 0
        bot.maestro_watcher_counters["buffer_overflow_drop"] = 0

    def tearDown(self) -> None:
        self._tmpdir.cleanup()

    def _write_chunk(self, text: str) -> None:
        with self.pipe_path.open("ab") as handle:
            handle.write(text.encode("utf-8"))

    def test_backoff_sleep_called_between_retries(self) -> None:
        """send 2회 실패 후 성공 → backoff sleep 이 정확한 값으로 호출됨.

        retry 1 실패 후 → sleep(BASE^1=2.0) 호출.
        retry 2 실패 후 → sleep(BASE^2=4.0) 호출.
        retry 3 성공 → 추가 backoff sleep 없음.
        """
        chunk = _make_long_chunk("backoff")
        self._write_chunk(chunk)
        channel = _FakeChannel(send_fail_count=2)
        client = _FakeClient(channel)
        ledger = _FakeLedger()
        harness = _LoopHarness(max_iterations=30, time_per_iter=5.0)

        _run_loop_until_cancel(
            bot.maestro_response_watcher_loop(
                client,
                channel_id=12345,
                pipe_pane_path=str(self.pipe_path),
                ledger=ledger,
                poll_interval=0.0,
                idle_seconds=10.0,
                time_source=harness.time_source,
                sleep=harness.sleep,
                initial_offset=0,
            )
        )

        # 모든 sleep 호출에서 backoff 값(2.0, 4.0) 이 적어도 한 번씩 등장해야 함.
        sleep_seconds = [s for _, s in harness.sleep_calls]
        base = bot.MAESTRO_WATCHER_SEND_RETRY_BACKOFF_BASE
        self.assertIn(base ** 1, sleep_seconds, f"retry 1 backoff 누락: {sleep_seconds}")
        self.assertIn(base ** 2, sleep_seconds, f"retry 2 backoff 누락: {sleep_seconds}")
        # 3번째 시도는 성공이므로 backoff 추가 없음.
        # 단, send 호출은 정확히 3회 (실패 2 + 성공 1).
        self.assertEqual(len(channel.send_calls), 3, f"send 호출 횟수 mismatch: {channel.send_calls}")
        self.assertEqual(len(ledger.processed), 1, "성공 시 ledger 1건 mark")

    def test_max_retries_exceeded_increments_send_drop_counter(self) -> None:
        """MAX_RETRIES 회 연속 실패 → drop + send_drop counter 1 증가."""
        chunk = _make_long_chunk("always-fail")
        self._write_chunk(chunk)
        channel = _FakeChannel(send_fail_count=999)
        client = _FakeClient(channel)
        ledger = _FakeLedger()
        harness = _LoopHarness(max_iterations=30, time_per_iter=5.0)

        before_drop = bot.maestro_watcher_counters["send_drop"]
        _run_loop_until_cancel(
            bot.maestro_response_watcher_loop(
                client,
                channel_id=12345,
                pipe_pane_path=str(self.pipe_path),
                ledger=ledger,
                poll_interval=0.0,
                idle_seconds=10.0,
                time_source=harness.time_source,
                sleep=harness.sleep,
                initial_offset=0,
            )
        )

        self.assertEqual(
            len(channel.send_calls),
            bot.MAESTRO_WATCHER_SEND_MAX_RETRIES,
            "MAX_RETRIES 만큼만 시도",
        )
        self.assertEqual(len(ledger.processed), 0, "실패 chunk 는 ledger mark 금지")
        self.assertEqual(
            bot.maestro_watcher_counters["send_drop"],
            before_drop + 1,
            "MAX_RETRIES 초과 drop 시 send_drop counter 1 증가",
        )


class MaestroWatcherBufferCapTests(unittest.TestCase):
    """buffer 가 MAX_BUFFER_LEN 을 초과하면 oldest 절반 drop + counter 증가."""

    def setUp(self) -> None:
        self._tmpdir = tempfile.TemporaryDirectory()
        self.pipe_path = Path(self._tmpdir.name) / "maestro.log"
        self.pipe_path.touch()
        bot.maestro_watcher_counters["send_drop"] = 0
        bot.maestro_watcher_counters["buffer_overflow_drop"] = 0

    def tearDown(self) -> None:
        self._tmpdir.cleanup()

    def _write_chunk(self, text: str) -> None:
        with self.pipe_path.open("ab") as handle:
            handle.write(text.encode("utf-8"))

    def test_buffer_overflow_drops_oldest_and_increments_counter(self) -> None:
        """MAX_BUFFER_LEN 을 초과하는 chunk 를 한 번에 write → drop + counter 증가.

        idle 임계 도달 전(send 미발생) 단계에서 buffer 가 cap 을 넘으면
        oldest 절반 drop 후에도 watcher 가 계속 동작해야 함 (loop 종료 X).
        """
        cap = bot.MAESTRO_WATCHER_MAX_BUFFER_LEN
        # cap 의 1.5 배 길이 chunk 한 방에 write → cap 초과 즉시 trigger.
        huge_chunk = "x" * (cap + cap // 2) + "\n"
        self._write_chunk(huge_chunk)

        # send 가 호출되더라도 ledger / counter 영향 없도록 channel 은 항상 성공.
        channel = _FakeChannel(send_fail_count=0)
        client = _FakeClient(channel)
        ledger = _FakeLedger()
        harness = _LoopHarness(max_iterations=10, time_per_iter=1.0)

        before_overflow = bot.maestro_watcher_counters["buffer_overflow_drop"]
        _run_loop_until_cancel(
            bot.maestro_response_watcher_loop(
                client,
                channel_id=12345,
                pipe_pane_path=str(self.pipe_path),
                ledger=ledger,
                poll_interval=0.0,
                # idle 매우 길게 → loop 가 종료될 때까지 send 트리거 안 됨
                # → buffer cap 동작만 격리 검증.
                idle_seconds=10_000.0,
                time_source=harness.time_source,
                sleep=harness.sleep,
                initial_offset=0,
            )
        )

        self.assertGreaterEqual(
            bot.maestro_watcher_counters["buffer_overflow_drop"],
            before_overflow + 1,
            "buffer overflow 시 counter 1 이상 증가",
        )

    def test_max_buffer_len_constant_exposed_and_reasonable(self) -> None:
        """MAESTRO_WATCHER_MAX_BUFFER_LEN 상수가 노출되고 합리적 값인지 가드."""
        self.assertTrue(hasattr(bot, "MAESTRO_WATCHER_MAX_BUFFER_LEN"))
        # MAX_CHUNK_LEN 보다는 명확히 커야 함 (단일 chunk 1건은 절대 잘리지 않게).
        self.assertGreater(
            bot.MAESTRO_WATCHER_MAX_BUFFER_LEN,
            bot.MAESTRO_WATCHER_MAX_CHUNK_LEN * 2,
        )
        # 1 MB 미만 (메모리 폭주 방지 목적).
        self.assertLess(bot.MAESTRO_WATCHER_MAX_BUFFER_LEN, 1_000_000)


class MaestroWatcherBackoffConstantTests(unittest.TestCase):
    """MAESTRO_WATCHER_SEND_RETRY_BACKOFF_BASE 상수 가드."""

    def test_backoff_base_exposed_and_reasonable(self) -> None:
        self.assertTrue(hasattr(bot, "MAESTRO_WATCHER_SEND_RETRY_BACKOFF_BASE"))
        base = bot.MAESTRO_WATCHER_SEND_RETRY_BACKOFF_BASE
        # 1 미만이면 backoff 무의미. 10 초과면 운영상 너무 김.
        self.assertGreater(base, 1.0)
        self.assertLessEqual(base, 10.0)


if __name__ == "__main__":
    unittest.main()
