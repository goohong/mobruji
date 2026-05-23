"""context_auto_clear_loop 단위 테스트 (#809).

PR #807 단순화에서 누락된 `context_auto_clear_loop` 를 복구하면서 함께 작성.
spec: docs/features/context-auto-clear.md §5-2 / §5-4 / §5-6 / §7.

검증 범위:
1. `parse_context_pct` — marker 단일/다중/없음/잘못된 형식/범위 초과.
2. `context_auto_clear_loop` (asyncio mock) —
   - 트리거: pct 96 → inject 호출 + debounced True + log append.
   - hysteresis: pct 96 유지 시 재트리거 없음. pct 75 → debounced 해제.
   - marker: stdout `===CLEAR_READY===` → send_clear_command 호출.
   - paused flag: 파일 존재 시 polling skip (inject/send 호출 0).
   - disabled flag: build_client 단에서 task 생성 자체 차단 (별 검증).
3. `append_clear_log` — 신규 파일 헤더 + row append.
4. `resolve_context_pct_env` — 정수/이상값/범위 초과 → default.
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

# discord/requests/dotenv 외부 의존 stub.
for missing in ("discord", "requests", "dotenv"):
    if missing not in sys.modules:
        stub = mock.MagicMock()
        if missing == "dotenv":
            stub.load_dotenv = lambda *a, **kw: None
        sys.modules[missing] = stub

import bot  # noqa: E402


# ─────────────────────────────────────────────────────────────────────────────
# parse_context_pct
# ─────────────────────────────────────────────────────────────────────────────


class ParseContextPctTest(unittest.TestCase):
    """spec §5-6 옵션 A regex `r'===CTX:(\\d{1,3})%==='`."""

    def test_single_marker_returns_int(self) -> None:
        self.assertEqual(bot.parse_context_pct("hello\n===CTX:42%==="), 42)

    def test_multiple_markers_returns_last(self) -> None:
        # 여러 turn 누적 시 마지막 = 가장 최신 turn 의 컨텍스트율.
        text = "===CTX:10%===\nfoo\n===CTX:55%===\nbar\n===CTX:97%==="
        self.assertEqual(bot.parse_context_pct(text), 97)

    def test_no_marker_returns_none(self) -> None:
        self.assertIsNone(bot.parse_context_pct("no marker here"))

    def test_empty_string_returns_none(self) -> None:
        self.assertIsNone(bot.parse_context_pct(""))

    def test_malformed_marker_returns_none(self) -> None:
        # 숫자 없는 경우 / 형식 깨짐 → 매치 자체 안 됨.
        self.assertIsNone(bot.parse_context_pct("===CTX:%==="))
        self.assertIsNone(bot.parse_context_pct("===CTX:abc%==="))
        self.assertIsNone(bot.parse_context_pct("CTX:50%"))

    def test_out_of_range_returns_none(self) -> None:
        # 100 초과 → regex 는 \d{1,3} 까지 받지만 후처리에서 차단.
        # 999 는 regex 매치되지만 범위 초과.
        self.assertIsNone(bot.parse_context_pct("===CTX:101%==="))
        self.assertIsNone(bot.parse_context_pct("===CTX:999%==="))

    def test_unknown_marker_returns_none(self) -> None:
        # spec: `===CTX:?===` 는 unknown 처리 (regex 매치 안 됨).
        self.assertIsNone(bot.parse_context_pct("===CTX:?==="))

    def test_zero_and_hundred_valid(self) -> None:
        self.assertEqual(bot.parse_context_pct("===CTX:0%==="), 0)
        self.assertEqual(bot.parse_context_pct("===CTX:100%==="), 100)


# ─────────────────────────────────────────────────────────────────────────────
# resolve_context_pct_env
# ─────────────────────────────────────────────────────────────────────────────


class ResolveContextPctEnvTest(unittest.TestCase):

    def test_none_returns_default(self) -> None:
        self.assertEqual(bot.resolve_context_pct_env(None, 95), 95)

    def test_valid_int_string_returns_int(self) -> None:
        self.assertEqual(bot.resolve_context_pct_env("90", 95), 90)

    def test_non_integer_returns_default(self) -> None:
        self.assertEqual(bot.resolve_context_pct_env("abc", 95), 95)

    def test_negative_returns_default(self) -> None:
        self.assertEqual(bot.resolve_context_pct_env("-5", 95), 95)

    def test_over_100_returns_default(self) -> None:
        self.assertEqual(bot.resolve_context_pct_env("150", 95), 95)


# ─────────────────────────────────────────────────────────────────────────────
# append_clear_log
# ─────────────────────────────────────────────────────────────────────────────


class AppendClearLogTest(unittest.TestCase):

    def test_creates_header_when_missing(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            log_path = Path(tmp_dir) / "subdir" / "log.md"
            with mock.patch.object(bot, "CONTEXT_CLEAR_LOG_PATH", log_path):
                bot.append_clear_log(96, "triggered")
            content = log_path.read_text(encoding="utf-8")
            self.assertIn("name: project-context-clear-log", content)
            self.assertIn("| timestamp | event | context%", content)
            # 새 row 한 줄.
            self.assertIn("| triggered | 96 |", content)

    def test_appends_to_existing_file_without_dup_header(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            log_path = Path(tmp_dir) / "log.md"
            with mock.patch.object(bot, "CONTEXT_CLEAR_LOG_PATH", log_path):
                bot.append_clear_log(96, "triggered")
                bot.append_clear_log(73, "cleared")
            content = log_path.read_text(encoding="utf-8")
            self.assertEqual(content.count("name: project-context-clear-log"), 1)
            self.assertIn("| triggered | 96 |", content)
            self.assertIn("| cleared | 73 |", content)


# ─────────────────────────────────────────────────────────────────────────────
# context_auto_clear_loop (asyncio mock)
# ─────────────────────────────────────────────────────────────────────────────


class FakeChannel:
    """awaitable .send 를 가진 fake Discord channel."""

    def __init__(self) -> None:
        self.sent: list[str] = []

    async def send(self, content: str) -> None:
        self.sent.append(content)


class FakeClient:
    """get_channel(channel_id) 호출에 FakeChannel 을 반환하는 fake client."""

    def __init__(self, channel: FakeChannel) -> None:
        self._channel = channel

    def get_channel(self, channel_id: int) -> FakeChannel:
        return self._channel


async def _run_loop_iters(
    loop_coro,
    iterations: int,
) -> None:
    """loop coroutine 을 task 로 띄우고 iterations 번 sleep 만큼 진행 후 cancel."""
    task = asyncio.create_task(loop_coro)
    # 각 iter 는 asyncio.sleep(poll_interval) 한 번을 통과해야 한다.
    # poll_interval 을 0 으로 patch 했으므로 await asyncio.sleep(0) 으로 양보만 하면 진행.
    for _ in range(iterations):
        await asyncio.sleep(0)
    task.cancel()
    try:
        await task
    except asyncio.CancelledError:
        pass


class ContextAutoClearLoopTest(unittest.IsolatedAsyncioTestCase):

    def setUp(self) -> None:
        # AUTOCLEAR_PAUSED_FLAG 가 진짜 파일을 참조하지 않도록 fake path 로 교체.
        self._tmp_dir = tempfile.TemporaryDirectory()
        self._flag_path = Path(self._tmp_dir.name) / "autoclear-paused"
        self._flag_patcher = mock.patch.object(
            bot, "AUTOCLEAR_PAUSED_FLAG", self._flag_path
        )
        self._flag_patcher.start()
        # log path 도 tmp 로 격리.
        self._log_path = Path(self._tmp_dir.name) / "log.md"
        self._log_patcher = mock.patch.object(
            bot, "CONTEXT_CLEAR_LOG_PATH", self._log_path
        )
        self._log_patcher.start()

    def tearDown(self) -> None:
        self._flag_patcher.stop()
        self._log_patcher.stop()
        self._tmp_dir.cleanup()

    async def test_trigger_at_high_pct_injects_and_logs(self) -> None:
        channel = FakeChannel()
        client = FakeClient(channel)
        capture_calls: list[str] = []

        def fake_capture(pane: str) -> str:
            capture_calls.append(pane)
            return "===CTX:96%==="

        with mock.patch.object(bot, "capture_pane_text", side_effect=fake_capture), \
             mock.patch.object(bot, "inject_cleanup_prompt", return_value=True) as inject, \
             mock.patch.object(bot, "send_clear_command", return_value=True) as send_clear:
            coro = bot.context_auto_clear_loop(
                client,
                channel_id=111,
                pane_target="mobruji:0.0",
                trigger_pct=95,
                hysteresis_pct=80,
                poll_interval=0,
            )
            await _run_loop_iters(coro, iterations=5)

        # 트리거 1회 inject 호출.
        self.assertGreaterEqual(inject.call_count, 1)
        inject.assert_called_with("mobruji:0.0")
        # marker 가 아직 안 보였으므로 clear 호출 0.
        send_clear.assert_not_called()
        # Discord push 첫 줄 = trigger ack.
        self.assertTrue(any("🧠 context 96%" in m for m in channel.sent))

    async def test_hysteresis_blocks_retrigger_until_falloff(self) -> None:
        channel = FakeChannel()
        client = FakeClient(channel)
        # 두 iter 모두 96% — debounced 해제 안 됨 → inject 1번만.
        with mock.patch.object(bot, "capture_pane_text", return_value="===CTX:96%==="), \
             mock.patch.object(bot, "inject_cleanup_prompt", return_value=True) as inject, \
             mock.patch.object(bot, "send_clear_command", return_value=True):
            coro = bot.context_auto_clear_loop(
                client,
                channel_id=111,
                pane_target="mobruji:0.0",
                trigger_pct=95,
                hysteresis_pct=80,
                poll_interval=0,
            )
            await _run_loop_iters(coro, iterations=10)
        # 여러 iter 돌았어도 inject 는 정확히 1번.
        self.assertEqual(inject.call_count, 1)

    async def test_hysteresis_releases_on_low_pct(self) -> None:
        channel = FakeChannel()
        client = FakeClient(channel)
        # 시퀀스: 96 (trigger) → 75 (hysteresis 해제) → 96 (재트리거).
        seq = iter([
            "===CTX:96%===",
            "===CTX:75%===",
            "===CTX:96%===",
            "===CTX:96%===",  # 여분
        ])

        def fake_capture(pane: str) -> str:
            try:
                return next(seq)
            except StopIteration:
                return "===CTX:96%==="

        with mock.patch.object(bot, "capture_pane_text", side_effect=fake_capture), \
             mock.patch.object(bot, "inject_cleanup_prompt", return_value=True) as inject, \
             mock.patch.object(bot, "send_clear_command", return_value=True):
            coro = bot.context_auto_clear_loop(
                client,
                channel_id=111,
                pane_target="mobruji:0.0",
                trigger_pct=95,
                hysteresis_pct=80,
                poll_interval=0,
            )
            await _run_loop_iters(coro, iterations=10)
        # 트리거 두 번 (첫 96, 회복 후 다시 96).
        self.assertEqual(inject.call_count, 2)

    async def test_marker_triggers_send_clear(self) -> None:
        channel = FakeChannel()
        client = FakeClient(channel)
        # 시퀀스: 96 (trigger 후 awaiting_marker) → marker 등장 → /clear 송신.
        seq = iter([
            "===CTX:96%===",
            "===CTX:96%===\n===CLEAR_READY===",
            "===CTX:96%===",
        ])

        def fake_capture(pane: str) -> str:
            try:
                return next(seq)
            except StopIteration:
                return "===CTX:50%==="

        with mock.patch.object(bot, "capture_pane_text", side_effect=fake_capture), \
             mock.patch.object(bot, "inject_cleanup_prompt", return_value=True), \
             mock.patch.object(bot, "send_clear_command", return_value=True) as send_clear:
            coro = bot.context_auto_clear_loop(
                client,
                channel_id=111,
                pane_target="mobruji:0.0",
                trigger_pct=95,
                hysteresis_pct=80,
                poll_interval=0,
            )
            await _run_loop_iters(coro, iterations=10)

        send_clear.assert_called_with("mobruji:0.0")
        # Discord push 에 "정리 완료" 라인이 등장.
        self.assertTrue(any("정리 완료" in m for m in channel.sent))

    async def test_paused_flag_skips_polling(self) -> None:
        channel = FakeChannel()
        client = FakeClient(channel)
        # paused flag 생성.
        self._flag_path.touch()

        with mock.patch.object(bot, "capture_pane_text", return_value="===CTX:96%===") as capture, \
             mock.patch.object(bot, "inject_cleanup_prompt", return_value=True) as inject, \
             mock.patch.object(bot, "send_clear_command", return_value=True) as send_clear:
            coro = bot.context_auto_clear_loop(
                client,
                channel_id=111,
                pane_target="mobruji:0.0",
                trigger_pct=95,
                hysteresis_pct=80,
                poll_interval=0,
            )
            await _run_loop_iters(coro, iterations=10)

        # paused → capture/inject/send 모두 호출 0.
        capture.assert_not_called()
        inject.assert_not_called()
        send_clear.assert_not_called()

    async def test_pct_none_skips_iteration(self) -> None:
        channel = FakeChannel()
        client = FakeClient(channel)

        with mock.patch.object(bot, "capture_pane_text", return_value="no marker"), \
             mock.patch.object(bot, "inject_cleanup_prompt", return_value=True) as inject, \
             mock.patch.object(bot, "send_clear_command", return_value=True) as send_clear:
            coro = bot.context_auto_clear_loop(
                client,
                channel_id=111,
                pane_target="mobruji:0.0",
                trigger_pct=95,
                hysteresis_pct=80,
                poll_interval=0,
            )
            await _run_loop_iters(coro, iterations=10)

        inject.assert_not_called()
        send_clear.assert_not_called()


# ─────────────────────────────────────────────────────────────────────────────
# tmux send-keys mock (inject / send_clear)
# ─────────────────────────────────────────────────────────────────────────────


class TmuxSendKeysTest(unittest.TestCase):
    """inject_cleanup_prompt / send_clear_command 의 subprocess 호출 인자 검증."""

    def test_inject_calls_tmux_send_keys_with_prompt(self) -> None:
        with mock.patch.object(bot.subprocess, "run") as run_mock:
            run_mock.return_value = mock.MagicMock(returncode=0, stderr="")
            ok = bot.inject_cleanup_prompt("mobruji:0.0")
            self.assertTrue(ok)
            # 최소 2번 호출 (literal payload + Enter).
            self.assertGreaterEqual(run_mock.call_count, 2)
            # 첫 호출 args = ["tmux", "send-keys", "-t", "mobruji:0.0", "-l", "..."]
            first_args = run_mock.call_args_list[0].args[0]
            self.assertEqual(first_args[0:4], ["tmux", "send-keys", "-t", "mobruji:0.0"])
            self.assertEqual(first_args[4], "-l")

    def test_send_clear_command_sends_slash_clear(self) -> None:
        with mock.patch.object(bot.subprocess, "run") as run_mock:
            run_mock.return_value = mock.MagicMock(returncode=0, stderr="")
            ok = bot.send_clear_command("mobruji:0.0")
            self.assertTrue(ok)
            # 첫 호출 payload literal = "/clear".
            first_args = run_mock.call_args_list[0].args[0]
            self.assertEqual(first_args, ["tmux", "send-keys", "-t", "mobruji:0.0", "-l", "/clear"])


if __name__ == "__main__":
    unittest.main()
