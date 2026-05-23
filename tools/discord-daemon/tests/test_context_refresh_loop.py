"""context_refresh_loop 단위 테스트 (ADR-0016 옵션 D, 이슈 #787).

검증:
- parse_context_response: 표준/콤마/다중 occurrence/형식 부정합 → None.
- inject_context_slash: tmux send-keys 호출 + payload `/context`.
- context_refresh_loop:
  - interval sleep 후 inject 호출.
  - 응답 매칭 시 cache pct + updated_at 갱신.
  - regex 부정합 시 cache 미갱신 (graceful).
  - inject 실패 시 다음 iter 재시도 (cache 미갱신).
- get_cached_context_pct: stale 판정 (max_age 초과 → None).

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


class _FakeClient:
    """context_refresh_loop 의 client 인자는 현재 미사용 — 단순 placeholder."""


class _ScriptedFile:
    """매 iter 마다 미리 정의된 sequence 의 content 로 파일 내용을 갱신."""

    def __init__(self, path: Path, scripts: list[str]) -> None:
        self.path = path
        self.scripts = scripts
        self.cursor = 0

    def advance(self) -> None:
        if self.cursor >= len(self.scripts):
            return
        self.path.write_text(self.scripts[self.cursor], encoding="utf-8")
        self.cursor += 1


class _RefreshHarness:
    """context_refresh_loop 는 sleep(interval) → inject → sleep(1.0) → tail 패턴.

    각 sleep 호출마다 step counter 증가. main interval sleep (sleep 0) 시점에
    scripted_file.advance() 로 다음 iter 응답 준비. max_iterations (interval 호출 횟수)
    도달 시 CancelledError.
    """

    def __init__(
        self,
        *,
        scripted_file: _ScriptedFile,
        max_iterations: int,
    ) -> None:
        self.scripted_file = scripted_file
        self.iter_count = 0
        self.max_iterations = max_iterations
        self.sleep_calls: list[float] = []

    async def sleep(self, seconds: float) -> None:
        self.sleep_calls.append(seconds)
        # interval sleep (큰 값, 1.0 grace 와 구분) 시점에만 advance + iter 증가.
        # grace sleep (1.0) 는 inject 후 응답 받기 위한 짧은 대기 — iter 카운트 안 함.
        if seconds >= 5.0:  # interval 은 테스트에서 5.0 이상으로 둠
            self.iter_count += 1
            if self.iter_count > self.max_iterations:
                raise asyncio.CancelledError()
            self.scripted_file.advance()


def _run_loop_until_cancel(coro) -> None:
    try:
        asyncio.run(coro)
    except asyncio.CancelledError:
        pass


def _response_block(used: int, total: int = 1_000_000) -> str:
    """`/context` 응답 화면 흉내. 실측 형식 변동 시 갱신."""
    return (
        f"some prior narration\n"
        f"Context window\n"
        f"  Total tokens used: {used:,} / {total:,}\n"
        f"  ... rest of context table ...\n"
    )


class ParseContextResponseTests(unittest.TestCase):
    """parse_context_response 단위."""

    def test_returns_none_when_no_match(self) -> None:
        self.assertIsNone(bot.parse_context_response("nothing here"))

    def test_parses_standard_format(self) -> None:
        text = "Total tokens used: 250,000 / 1,000,000"
        self.assertEqual(bot.parse_context_response(text), 25)

    def test_parses_without_commas(self) -> None:
        text = "Total tokens used: 500 / 1000"
        self.assertEqual(bot.parse_context_response(text), 50)

    def test_uses_last_occurrence(self) -> None:
        text = (
            "Total tokens used: 100 / 1000\n"
            "later refresh:\n"
            "Total tokens used: 800 / 1000\n"
        )
        self.assertEqual(bot.parse_context_response(text), 80)

    def test_returns_none_on_zero_total(self) -> None:
        text = "Total tokens used: 100 / 0"
        self.assertIsNone(bot.parse_context_response(text))

    def test_rounds_to_nearest_int(self) -> None:
        # 333 / 1000 = 33.3 → 33
        self.assertEqual(bot.parse_context_response("Total tokens used: 333 / 1000"), 33)
        # 667 / 1000 = 66.7 → 67
        self.assertEqual(bot.parse_context_response("Total tokens used: 667 / 1000"), 67)


class InjectContextSlashTests(unittest.TestCase):
    """inject_context_slash 가 tmux send-keys 로 `/context` 전송하는지."""

    def test_invokes_tmux_send_keys_with_slash(self) -> None:
        with mock.patch.object(bot.subprocess, "run") as mock_run:
            mock_run.return_value = mock.Mock(returncode=0, stderr="")
            result = bot.inject_context_slash("mobruji:0.0")
        self.assertTrue(result)
        # tmux_send_payload 는 한 줄당 (-l, Enter) 2회 호출.
        self.assertGreaterEqual(mock_run.call_count, 2)
        first_args = mock_run.call_args_list[0].args[0]
        self.assertEqual(first_args[:4], ["tmux", "send-keys", "-t", "mobruji:0.0"])
        self.assertIn("-l", first_args)
        # payload 에 `/context` 포함.
        self.assertIn("/context", " ".join(first_args))


class GetCachedContextPctTests(unittest.TestCase):
    """get_cached_context_pct stale 판정."""

    def setUp(self) -> None:
        # 테스트 격리 — 모듈 전역 cache 초기화.
        bot.context_pct_cache["pct"] = None
        bot.context_pct_cache["updated_at"] = 0.0

    def tearDown(self) -> None:
        bot.context_pct_cache["pct"] = None
        bot.context_pct_cache["updated_at"] = 0.0

    def test_returns_none_when_empty(self) -> None:
        self.assertIsNone(bot.get_cached_context_pct())

    def test_returns_fresh_value(self) -> None:
        import time as _time
        bot.context_pct_cache["pct"] = 73
        bot.context_pct_cache["updated_at"] = _time.monotonic()
        self.assertEqual(bot.get_cached_context_pct(max_age_seconds=600.0), 73)

    def test_returns_none_when_stale(self) -> None:
        import time as _time
        bot.context_pct_cache["pct"] = 73
        # 1시간 전 — max_age 600s 초과.
        bot.context_pct_cache["updated_at"] = _time.monotonic() - 3600.0
        self.assertIsNone(bot.get_cached_context_pct(max_age_seconds=600.0))


class ContextRefreshLoopTests(unittest.TestCase):
    """context_refresh_loop 시나리오."""

    def setUp(self) -> None:
        self._tmpdir = tempfile.TemporaryDirectory()
        self.pipe_path = Path(self._tmpdir.name) / "pane.log"
        self.pipe_path.touch()
        # local cache (모듈 전역 오염 방지).
        self.cache: dict[str, float | int | None] = {"pct": None, "updated_at": 0.0}
        self.fake_now = 1000.0

    def tearDown(self) -> None:
        self._tmpdir.cleanup()

    def _monotonic(self) -> float:
        return self.fake_now

    def _run(
        self,
        *,
        scripts: list[str],
        inject_fn,
        max_iterations: int,
    ) -> _RefreshHarness:
        scripted = _ScriptedFile(self.pipe_path, scripts)
        # 첫 sleep(interval) 시점에 advance → 첫 iter 가 첫 script 를 본다.
        harness = _RefreshHarness(
            scripted_file=scripted,
            max_iterations=max_iterations,
        )
        client = _FakeClient()
        _run_loop_until_cancel(
            bot.context_refresh_loop(
                client,
                target_pane="mobruji:0.0",
                pipe_pane_path=str(self.pipe_path),
                interval_seconds=300.0,
                sleep=harness.sleep,
                inject_fn=inject_fn,
                cache=self.cache,
                monotonic=self._monotonic,
            )
        )
        return harness

    def test_inject_called_each_interval(self) -> None:
        inject = mock.Mock(return_value=True)
        harness = self._run(
            scripts=[_response_block(250_000), _response_block(500_000)],
            inject_fn=inject,
            max_iterations=2,
        )
        # max_iterations=2 → 첫 2 iter 정상 진행, 3번째 interval sleep 에서 cancel.
        # inject 는 2회 (3번째 iter 는 sleep cancel 로 도달 못 함).
        self.assertEqual(inject.call_count, 2)
        # interval sleep 3회 (마지막은 cancel 직전 record), grace sleep 2회.
        big_sleeps = [s for s in harness.sleep_calls if s >= 5.0]
        small_sleeps = [s for s in harness.sleep_calls if 0.0 < s < 5.0]
        self.assertEqual(len(big_sleeps), 3, f"interval sleep 누락: {harness.sleep_calls}")
        self.assertEqual(len(small_sleeps), 2, f"grace sleep 누락: {harness.sleep_calls}")

    def test_cache_updated_on_successful_parse(self) -> None:
        inject = mock.Mock(return_value=True)
        self._run(
            scripts=[_response_block(250_000)],  # 25%
            inject_fn=inject,
            max_iterations=1,
        )
        self.assertEqual(self.cache["pct"], 25)
        self.assertEqual(self.cache["updated_at"], 1000.0)

    def test_cache_not_updated_on_parse_failure(self) -> None:
        """응답 형식이 regex 부정합 → cache 미갱신 (graceful)."""
        inject = mock.Mock(return_value=True)
        self._run(
            scripts=["no Total tokens line here\nrandom output\n"],
            inject_fn=inject,
            max_iterations=1,
        )
        # cache 초기값 그대로.
        self.assertIsNone(self.cache["pct"])
        self.assertEqual(self.cache["updated_at"], 0.0)

    def test_inject_failure_skips_parse(self) -> None:
        """inject 실패 시 grace sleep + tail/parse 안 함 → cache 미갱신."""
        inject = mock.Mock(return_value=False)
        harness = self._run(
            scripts=[_response_block(250_000)],
            inject_fn=inject,
            max_iterations=1,
        )
        self.assertEqual(inject.call_count, 1)
        # cache 미갱신.
        self.assertIsNone(self.cache["pct"])
        # grace sleep (1.0) 호출되지 않음 (inject 실패로 continue).
        small_sleeps = [s for s in harness.sleep_calls if 0.0 < s < 5.0]
        self.assertEqual(
            len(small_sleeps),
            0,
            f"inject 실패면 grace sleep 안 해야 함: {harness.sleep_calls}",
        )

    def test_cache_updates_with_latest_value(self) -> None:
        """여러 iter 거치며 cache 가 최신 값으로 덮어쓰기."""
        inject = mock.Mock(return_value=True)
        self._run(
            scripts=[
                _response_block(100_000),  # 10%
                _response_block(750_000),  # 75%
            ],
            inject_fn=inject,
            max_iterations=2,
        )
        self.assertEqual(self.cache["pct"], 75)


if __name__ == "__main__":
    unittest.main()
