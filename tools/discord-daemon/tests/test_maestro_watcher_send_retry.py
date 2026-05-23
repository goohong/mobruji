"""maestro_response_watcher_loop send 실패 재시도 단위 테스트 (#755).

문제: `channel.send` 가 transient 에러로 실패하면 buffer 가 이미 비워져 chunk 영구 손실.
해결: send 실패 시 candidate 를 pending 으로 보존 → 다음 iteration 재시도.
       MAESTRO_WATCHER_SEND_MAX_RETRIES 회 연속 실패 시만 drop + ERROR 로그.

표준 라이브러리 unittest + unittest.mock 만 사용 (pytest 로도 자동 수집).
"""

from __future__ import annotations

import asyncio
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

# bot.py 가 한 단계 상위 (tools/discord-daemon/) 에 있다. import path 등록.
PARENT_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PARENT_DIR))

# discord / dotenv / requests 가 venv 에 없어도 import 가능하도록 stub.
for missing in ("discord", "requests", "dotenv"):
    if missing not in sys.modules:
        stub = mock.MagicMock()
        if missing == "dotenv":
            stub.load_dotenv = lambda *a, **kw: None
        sys.modules[missing] = stub

import bot  # noqa: E402


def _make_long_chunk(label: str) -> str:
    """sanitize_chunk MIN_CHUNK_LEN(100) 통과를 위한 충분히 긴 chunk."""
    body = f"maestro 응답 chunk [{label}]: PR 머지 완료. test 통과. " + ("필요 컨텍스트 " * 20)
    # 줄 끝 newline (pipe-pane 캡처는 항상 line 단위)
    return body + "\n"


class _FakeChannel:
    """channel.send 를 시뮬레이션. send_fail_count 만큼 실패 후 성공."""

    def __init__(self, send_fail_count: int = 0) -> None:
        self.send_fail_count = send_fail_count
        self.send_calls: list[str] = []

    async def send(self, content: str) -> None:
        self.send_calls.append(content)
        if len(self.send_calls) <= self.send_fail_count:
            raise RuntimeError(f"simulated transient send failure #{len(self.send_calls)}")


class _FakeClient:
    """discord.Client 의 get_channel 만 흉내."""

    def __init__(self, channel: _FakeChannel | None) -> None:
        self._channel = channel

    def get_channel(self, channel_id: int) -> _FakeChannel | None:  # noqa: ARG002
        return self._channel


class _FakeLedger:
    """DedupLedger 의 최소 동작만 구현."""

    def __init__(self) -> None:
        self.processed: set[str] = set()

    def is_processed(self, sig: str) -> bool:
        return sig in self.processed

    def mark_processed(self, sig: str) -> None:
        self.processed.add(sig)


class _LoopHarness:
    """maestro_response_watcher_loop 를 결정적 step 단위로 구동.

    실제 시간 대신 monotonically increasing time_source 를 주입한다.
    sleep 은 카운터만 증가하고 즉시 반환 → loop iteration 을 빠르게 수행.
    max_iterations 도달하면 loop 종료를 강제한다.
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

    def time_source(self) -> float:
        return self._current_time

    async def sleep(self, _seconds: float) -> None:
        self.iter_count += 1
        self._current_time += self.time_per_iter
        if self.iter_count >= self.max_iterations:
            raise asyncio.CancelledError()


def _run_loop_until_cancel(coro):
    """asyncio.CancelledError 를 종료 신호로 사용. 일반 종료로 변환."""
    try:
        asyncio.run(coro)
    except asyncio.CancelledError:
        pass


class MaestroWatcherSendRetryTests(unittest.TestCase):
    """send 실패 → 재시도 → 결국 성공 또는 drop 시나리오를 결정적으로 검증."""

    def setUp(self) -> None:
        self._tmpdir = tempfile.TemporaryDirectory()
        self.pipe_path = Path(self._tmpdir.name) / "maestro.log"
        # 파일은 미리 만들어두고 watcher 가 시작 후 누적된 내용을 읽도록 함.
        self.pipe_path.touch()

    def tearDown(self) -> None:
        self._tmpdir.cleanup()

    def _write_chunk(self, text: str) -> None:
        with self.pipe_path.open("ab") as handle:
            handle.write(text.encode("utf-8"))

    def test_send_success_clears_pending_and_marks_ledger(self) -> None:
        """첫 시도에 성공 → ledger mark + pending 없음."""
        chunk = _make_long_chunk("ok-once")
        self._write_chunk(chunk)
        channel = _FakeChannel(send_fail_count=0)
        client = _FakeClient(channel)
        ledger = _FakeLedger()
        harness = _LoopHarness(max_iterations=20, time_per_iter=5.0)

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

        self.assertEqual(len(channel.send_calls), 1, "성공 시 send 는 1회만")
        self.assertEqual(len(ledger.processed), 1, "성공 시 ledger 1건 mark")

    def test_send_fails_twice_then_succeeds_no_chunk_loss(self) -> None:
        """2회 실패 후 3번째 성공 → 같은 chunk 재시도, ledger 1건 mark, chunk 손실 없음."""
        chunk = _make_long_chunk("retry-then-ok")
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

        # 정확히 3회 호출 (실패 2 + 성공 1).
        self.assertEqual(len(channel.send_calls), 3, f"send 호출 횟수 mismatch: {channel.send_calls}")
        # 세 번 다 같은 candidate (chunk 손실 / 재구성 없음).
        self.assertEqual(channel.send_calls[0], channel.send_calls[1])
        self.assertEqual(channel.send_calls[1], channel.send_calls[2])
        # 성공한 마지막 시도만 ledger mark.
        self.assertEqual(len(ledger.processed), 1)

    def test_send_fails_max_retries_then_drop_no_ledger_mark(self) -> None:
        """MAX_RETRIES(3) 회 연속 실패 → drop + ledger mark 없음 + 후속 chunk 처리 가능."""
        chunk = _make_long_chunk("always-fail")
        self._write_chunk(chunk)
        # MAX_RETRIES 보다 많이 실패하도록 설정 → 절대 성공 안 함.
        channel = _FakeChannel(send_fail_count=999)
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

        # 정확히 MAX_RETRIES 회 시도 후 drop. 그 이상 호출되지 않음.
        self.assertEqual(
            len(channel.send_calls),
            bot.MAESTRO_WATCHER_SEND_MAX_RETRIES,
            f"send 시도가 MAX_RETRIES 와 다름: {len(channel.send_calls)}",
        )
        # 한 번도 성공 못 했으므로 ledger 비어 있어야 함.
        self.assertEqual(len(ledger.processed), 0, "실패한 chunk 는 ledger mark 금지")

    def test_pending_chunk_preserved_while_new_buffer_accumulates(self) -> None:
        """pending 재시도 중 새 buffer 가 누적되어도 두 chunk 모두 손실 없이 전달.

        시나리오:
          - chunk1 idle flush → send 실패 → pending 보존
          - 그 사이 chunk2 가 파일에 추가 → buffer 누적
          - 다음 iteration: pending(chunk1) send 성공 → pending clear
          - chunk2 가 idle 임계 통과 → 새 candidate → send 성공
        """
        chunk1 = _make_long_chunk("first")
        chunk2 = _make_long_chunk("second")

        # 첫 send 만 실패하도록 설정. 두 번째 시도부터는 성공.
        channel = _FakeChannel(send_fail_count=1)
        client = _FakeClient(channel)
        ledger = _FakeLedger()

        # chunk1 미리 write. chunk2 는 loop 진행 중 동적으로 write.
        self._write_chunk(chunk1)

        class _DynamicHarness(_LoopHarness):
            """iteration 5 에 chunk2 를 추가 write — pending 재시도와 새 buffer 누적 공존 검증."""

            def __init__(self, outer: "MaestroWatcherSendRetryTests") -> None:
                super().__init__(max_iterations=40, time_per_iter=5.0)
                self.outer = outer
                self.injected = False

            async def sleep(self, seconds: float) -> None:
                if self.iter_count == 5 and not self.injected:
                    self.outer._write_chunk(chunk2)
                    self.injected = True
                await super().sleep(seconds)

        harness = _DynamicHarness(self)

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

        # 총 3회 send: chunk1 실패 + chunk1 재시도 성공 + chunk2 성공.
        self.assertGreaterEqual(
            len(channel.send_calls),
            3,
            f"세 chunk 모두 전달돼야 함. 실제 호출: {channel.send_calls}",
        )
        # 첫 두 호출은 같은 chunk(=chunk1) 여야 함 (재시도 = 동일 내용).
        self.assertEqual(channel.send_calls[0], channel.send_calls[1])
        # chunk1 과 chunk2 는 서로 다른 내용이어야 함.
        self.assertNotEqual(channel.send_calls[1], channel.send_calls[2])
        # ledger 2건 mark (chunk1 성공 + chunk2 성공).
        self.assertEqual(len(ledger.processed), 2)


class MaestroWatcherRetryConstantTests(unittest.TestCase):
    """MAESTRO_WATCHER_SEND_MAX_RETRIES 상수가 노출되고 합리적 값인지 가드."""

    def test_max_retries_constant_exposed_and_positive(self) -> None:
        self.assertTrue(hasattr(bot, "MAESTRO_WATCHER_SEND_MAX_RETRIES"))
        self.assertGreaterEqual(bot.MAESTRO_WATCHER_SEND_MAX_RETRIES, 2)
        self.assertLessEqual(bot.MAESTRO_WATCHER_SEND_MAX_RETRIES, 10)


if __name__ == "__main__":
    unittest.main()
