"""context_auto_clear_loop 단위 테스트 (spec PR C, 이슈 #773).

검증:
- parse_context_pct: 마지막 occurrence 추출, 없으면 None.
- trigger: 95% 도달 시 inject_fn 호출 + state AWAITING_MARKER 전환.
- hysteresis: 96% → trigger, debounced 상태에서 90% → 무장 해제 안 됨,
  75% → 무장 해제, 96% → 재trigger.
- CLEAR_READY 마커: 감지 시 clear_fn 호출 + 채널 push.
- dedup: 같은 96% 가 ARMED 가 아닐 때 추가 trigger 없음.

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


class _FakeChannel:
    """channel.send 호출 기록."""

    def __init__(self) -> None:
        self.send_calls: list[str] = []

    async def send(self, content: str) -> None:
        self.send_calls.append(content)


class _FakeClient:
    def __init__(self, channel: _FakeChannel | None) -> None:
        self._channel = channel

    def get_channel(self, channel_id: int) -> _FakeChannel | None:  # noqa: ARG002
        return self._channel


class _ScriptedFile:
    """매 iter 마다 미리 정의된 sequence 의 content 로 파일 내용을 갱신.

    각 시점에서 파일에 어떤 marker 가 있는지 시나리오로 제어한다.
    """

    def __init__(self, path: Path, scripts: list[str]) -> None:
        self.path = path
        self.scripts = scripts
        self.cursor = 0

    def advance(self) -> None:
        if self.cursor >= len(self.scripts):
            return
        self.path.write_text(self.scripts[self.cursor], encoding="utf-8")
        self.cursor += 1


class _LoopHarness:
    """polling iteration 마다 _ScriptedFile.advance 호출 + max_iterations 에서 cancel."""

    def __init__(
        self,
        *,
        scripted_file: _ScriptedFile,
        max_iterations: int,
    ) -> None:
        self.scripted_file = scripted_file
        self.iter_count = 0
        self.max_iterations = max_iterations

    async def sleep(self, _seconds: float) -> None:
        self.iter_count += 1
        # sleep 직후 (== 다음 iter 가 read 하기 전) 파일 내용을 갱신.
        self.scripted_file.advance()
        if self.iter_count >= self.max_iterations:
            raise asyncio.CancelledError()


def _run_loop_until_cancel(coro) -> None:
    try:
        asyncio.run(coro)
    except asyncio.CancelledError:
        pass


def _marker_block(pct: int) -> str:
    """maestro 가 한 turn 끝에 emit 한 marker 가 포함된 buffer 흉내."""
    return (
        f"some narration line\n"
        f"another tool call\n"
        f"===CTX:{pct}%===\n"
    )


class ParseContextPctTests(unittest.TestCase):
    """parse_context_pct 단위."""

    def test_returns_none_when_no_marker(self) -> None:
        self.assertIsNone(bot.parse_context_pct("hello world\nno marker here"))

    def test_returns_int_from_single_marker(self) -> None:
        self.assertEqual(bot.parse_context_pct("===CTX:42%===\n"), 42)

    def test_returns_last_occurrence(self) -> None:
        text = "===CTX:30%===\nlater\n===CTX:88%===\nfooter"
        self.assertEqual(bot.parse_context_pct(text), 88)

    def test_handles_three_digit(self) -> None:
        # 105% 같은 추정치도 정상 파싱 (호출부에서 임계 비교).
        self.assertEqual(bot.parse_context_pct("===CTX:105%==="), 105)

    def test_ignores_partial_marker(self) -> None:
        # `===CTX:%===` (숫자 없음) 은 regex 에 안 잡힘.
        self.assertIsNone(bot.parse_context_pct("===CTX:%==="))


class ContextAutoClearLoopTests(unittest.TestCase):
    """state machine + hysteresis 시나리오."""

    def setUp(self) -> None:
        self._tmpdir = tempfile.TemporaryDirectory()
        self.pipe_path = Path(self._tmpdir.name) / "pane.log"
        self.pipe_path.touch()

    def tearDown(self) -> None:
        self._tmpdir.cleanup()

    def _run(
        self,
        *,
        scripts: list[str],
        inject_fn,
        clear_fn,
        max_iterations: int | None = None,
        channel: _FakeChannel | None = None,
    ) -> _FakeChannel:
        scripted = _ScriptedFile(self.pipe_path, scripts)
        # 첫 read 전에 이미 첫 script 를 깔아두면 결정적이다.
        scripted.advance()
        harness = _LoopHarness(
            scripted_file=scripted,
            max_iterations=max_iterations if max_iterations is not None else len(scripts) + 2,
        )
        ch = channel if channel is not None else _FakeChannel()
        client = _FakeClient(ch)
        _run_loop_until_cancel(
            bot.context_auto_clear_loop(
                client,
                channel_id=12345,
                pipe_pane_path=str(self.pipe_path),
                target_pane="mobruji:0.0",
                trigger_pct=95,
                hysteresis_pct=80,
                poll_interval=0.0,
                sleep=harness.sleep,
                inject_fn=inject_fn,
                clear_fn=clear_fn,
            )
        )
        return ch

    def test_trigger_at_96_inject_called_once(self) -> None:
        inject = mock.Mock(return_value=True)
        clear = mock.Mock(return_value=True)
        channel = self._run(
            scripts=[_marker_block(96)],
            inject_fn=inject,
            clear_fn=clear,
        )
        self.assertEqual(inject.call_count, 1, "trigger 1회 inject")
        self.assertEqual(clear.call_count, 0, "marker 없으면 /clear 안 함")
        # 채널에 trigger push 1건.
        self.assertTrue(
            any("96%" in msg for msg in channel.send_calls),
            f"trigger push 누락: {channel.send_calls}",
        )

    def test_dedup_no_double_trigger_same_pct(self) -> None:
        """같은 96% 가 2회 연속 polling 에 보여도 inject 1회만."""
        inject = mock.Mock(return_value=True)
        clear = mock.Mock(return_value=True)
        self._run(
            scripts=[_marker_block(96), _marker_block(96), _marker_block(96)],
            inject_fn=inject,
            clear_fn=clear,
        )
        self.assertEqual(inject.call_count, 1, "같은 pct 연속이라도 inject 1회")

    def test_hysteresis_90_does_not_rearm(self) -> None:
        """trigger 후 90% (>80) 로 떨어져도 ARMED 복귀 안 함 → 96% 다시 와도 inject 추가 없음."""
        inject = mock.Mock(return_value=True)
        clear = mock.Mock(return_value=True)
        self._run(
            scripts=[
                _marker_block(96),  # trigger
                _marker_block(90),  # debounced, 90 > 80 → rearm 안 됨
                _marker_block(96),  # 여전히 debounced
            ],
            inject_fn=inject,
            clear_fn=clear,
        )
        self.assertEqual(inject.call_count, 1)

    def test_hysteresis_75_rearms_then_96_triggers_again(self) -> None:
        """trigger 96 → marker 후 /clear → debounced. 75% 로 떨어지면 ARMED 복귀.
        다시 96% 오면 재trigger.
        """
        inject = mock.Mock(return_value=True)
        clear = mock.Mock(return_value=True)
        self._run(
            scripts=[
                _marker_block(96),  # trigger #1 → AWAITING_MARKER
                _marker_block(96) + "===CLEAR_READY===\n",  # CLEAR_READY → /clear → DEBOUNCED
                _marker_block(75),  # 75 <= 80 → ARMED 복귀
                _marker_block(96),  # 재trigger #2
            ],
            inject_fn=inject,
            clear_fn=clear,
            max_iterations=8,
        )
        self.assertEqual(inject.call_count, 2, "rearm 후 재trigger 발생")
        self.assertEqual(clear.call_count, 1, "/clear 1회만")

    def test_clear_ready_marker_triggers_clear(self) -> None:
        """trigger 후 같은 buffer 에 CLEAR_READY 가 있으면 /clear 호출 + 채널 push."""
        inject = mock.Mock(return_value=True)
        clear = mock.Mock(return_value=True)
        channel = self._run(
            scripts=[
                _marker_block(96),  # trigger
                _marker_block(96) + "===CLEAR_READY===\n",  # marker 도착
            ],
            inject_fn=inject,
            clear_fn=clear,
        )
        self.assertEqual(clear.call_count, 1)
        self.assertTrue(
            any("정리 완료" in msg for msg in channel.send_calls),
            f"clear 완료 push 누락: {channel.send_calls}",
        )

    def test_no_trigger_below_threshold(self) -> None:
        """80% 같은 안전한 pct 는 trigger 안 함."""
        inject = mock.Mock(return_value=True)
        clear = mock.Mock(return_value=True)
        self._run(
            scripts=[_marker_block(50), _marker_block(80), _marker_block(94)],
            inject_fn=inject,
            clear_fn=clear,
        )
        self.assertEqual(inject.call_count, 0)
        self.assertEqual(clear.call_count, 0)

    def test_inject_failure_retries_next_iter(self) -> None:
        """inject_fn 첫 시도 실패 → ARMED 유지 → 다음 iter 재시도 → 성공."""
        # 처음엔 False, 그 다음부턴 True 반환.
        inject = mock.Mock(side_effect=[False, True])
        clear = mock.Mock(return_value=True)
        self._run(
            scripts=[_marker_block(96), _marker_block(96)],
            inject_fn=inject,
            clear_fn=clear,
        )
        self.assertEqual(inject.call_count, 2, "inject 실패 시 ARMED 유지하고 재시도")


class ContextHelperMockTests(unittest.TestCase):
    """inject_cleanup_prompt / send_clear_command 가 tmux send-keys subprocess 호출하는지 검증."""

    def test_inject_cleanup_prompt_invokes_tmux_send_keys(self) -> None:
        with mock.patch.object(bot.subprocess, "run") as mock_run:
            mock_run.return_value = mock.Mock(returncode=0, stderr="")
            result = bot.inject_cleanup_prompt("mobruji:0.0")
        self.assertTrue(result)
        # tmux_send_payload 는 한 줄당 (-l, Enter) 2회 호출. prompt 가 single-line 이라 정확히 2.
        self.assertGreaterEqual(mock_run.call_count, 2)
        # 첫 호출은 literal payload.
        first_args = mock_run.call_args_list[0].args[0]
        self.assertEqual(first_args[:4], ["tmux", "send-keys", "-t", "mobruji:0.0"])
        self.assertIn("-l", first_args)
        # payload 내용에 95% 도달 prompt 포함.
        joined = " ".join(first_args)
        self.assertIn("===CLEAR_READY===", joined)

    def test_send_clear_command_invokes_tmux_send_keys(self) -> None:
        with mock.patch.object(bot.subprocess, "run") as mock_run:
            mock_run.return_value = mock.Mock(returncode=0, stderr="")
            result = bot.send_clear_command("mobruji:0.0")
        self.assertTrue(result)
        self.assertGreaterEqual(mock_run.call_count, 2)
        # 첫 호출에 /clear payload.
        first_args = mock_run.call_args_list[0].args[0]
        self.assertIn("/clear", first_args)


if __name__ == "__main__":
    unittest.main()
