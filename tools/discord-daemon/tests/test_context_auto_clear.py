"""context_auto_clear_loop 단위 테스트 (#809, #855 multi-pane).

PR #807 단순화에서 누락된 `context_auto_clear_loop` 를 복구하면서 함께 작성.
#855 에서 multi-pane (nmae + helper) polling 으로 확장.
spec: docs/features/context-auto-clear.md §5-2 / §5-4 / §5-5 / §5-6 / §7.

검증 범위:
1. `parse_context_pct` — marker 단일/다중/없음/잘못된 형식/범위 초과.
2. `resolve_pane_targets` — CSV / 공백 / 빈 토큰 / 중복 제거 / None fallback.
3. `context_auto_clear_loop` (asyncio mock) —
   - 트리거 (single pane / legacy 호출): pct 96 → inject 호출 + debounced True + log append.
   - 트리거 (multi-pane): 2 pane 모두 96 → pane 별 inject 1번씩 + Discord push 에 pane 이름 포함.
   - pane 별 독립 state: pane A 트리거 시 pane B debounce 영향 없음.
   - 한 pane 의 세션 부재 → 해당 pane skip, 다른 pane 정상 polling.
   - hysteresis: pct 96 유지 시 재트리거 없음. pct 75 → debounced 해제.
   - marker: stdout `===CLEAR_READY===` → send_clear_command 호출 (pane 별).
   - paused flag: 파일 존재 시 polling skip (inject/send 호출 0).
   - disabled flag: build_client 단에서 task 생성 자체 차단 (별 검증).
4. `append_clear_log` — 신규 파일 헤더 + row append (pane 컬럼 포함).
5. `resolve_context_pct_env` — 정수/이상값/범위 초과 → default.
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

# discord 는 가능한 한 실제 모듈을 사용 (다른 테스트의 embed 검증 호환, #840).
import discord
for missing in ("requests", "dotenv"):
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
# resolve_pane_targets (#855 multi-pane)
# ─────────────────────────────────────────────────────────────────────────────


class ResolvePaneTargetsTest(unittest.TestCase):
    """`TMUX_PANE_TARGETS` (plural CSV) 파싱."""

    def test_none_returns_default(self) -> None:
        self.assertEqual(
            bot.resolve_pane_targets(None), ["mobruji:0.0", "helper:0.0"]
        )

    def test_empty_string_returns_default(self) -> None:
        self.assertEqual(
            bot.resolve_pane_targets(""), ["mobruji:0.0", "helper:0.0"]
        )

    def test_single_pane_returned_as_one_element_list(self) -> None:
        self.assertEqual(bot.resolve_pane_targets("mobruji:0.0"), ["mobruji:0.0"])

    def test_csv_split_with_whitespace_trimmed(self) -> None:
        self.assertEqual(
            bot.resolve_pane_targets("mobruji:0.0 , helper:0.0"),
            ["mobruji:0.0", "helper:0.0"],
        )

    def test_empty_tokens_ignored(self) -> None:
        self.assertEqual(
            bot.resolve_pane_targets("mobruji:0.0,,helper:0.0,"),
            ["mobruji:0.0", "helper:0.0"],
        )

    def test_duplicates_removed_preserving_order(self) -> None:
        self.assertEqual(
            bot.resolve_pane_targets("helper:0.0,mobruji:0.0,helper:0.0"),
            ["helper:0.0", "mobruji:0.0"],
        )


# ─────────────────────────────────────────────────────────────────────────────
# append_clear_log
# ─────────────────────────────────────────────────────────────────────────────


class AppendClearLogTest(unittest.TestCase):

    def test_creates_header_when_missing(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            log_path = Path(tmp_dir) / "subdir" / "log.md"
            with mock.patch.object(bot, "CONTEXT_CLEAR_LOG_PATH", log_path):
                bot.append_clear_log(96, "triggered", pane="mobruji:0.0")
            content = log_path.read_text(encoding="utf-8")
            self.assertIn("name: project-context-clear-log", content)
            # 신규 헤더 (#855) — pane 컬럼 추가.
            self.assertIn("| timestamp | pane | event | context%", content)
            # 새 row 한 줄.
            self.assertIn("| mobruji:0.0 | triggered | 96 |", content)

    def test_appends_to_existing_file_without_dup_header(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            log_path = Path(tmp_dir) / "log.md"
            with mock.patch.object(bot, "CONTEXT_CLEAR_LOG_PATH", log_path):
                bot.append_clear_log(96, "triggered", pane="mobruji:0.0")
                bot.append_clear_log(73, "cleared", pane="helper:0.0")
            content = log_path.read_text(encoding="utf-8")
            self.assertEqual(content.count("name: project-context-clear-log"), 1)
            self.assertIn("| mobruji:0.0 | triggered | 96 |", content)
            self.assertIn("| helper:0.0 | cleared | 73 |", content)

    def test_pane_defaults_to_dash_when_omitted(self) -> None:
        # legacy 호출 — pane 인자 미지정 시 `-` 로 표기.
        with tempfile.TemporaryDirectory() as tmp_dir:
            log_path = Path(tmp_dir) / "log.md"
            with mock.patch.object(bot, "CONTEXT_CLEAR_LOG_PATH", log_path):
                bot.append_clear_log(50, "triggered")
            content = log_path.read_text(encoding="utf-8")
            self.assertIn("| - | triggered | 50 |", content)


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
        # #855: loop 가 매 iter `tmux_has_session` 호출 — 테스트는 항상 True 로.
        # 일부 테스트(missing pane) 만 별도 side_effect 로 덮어 씀.
        self._has_session_patcher = mock.patch.object(
            bot, "tmux_has_session", return_value=True
        )
        self._has_session_patcher.start()

    def tearDown(self) -> None:
        self._flag_patcher.stop()
        self._log_patcher.stop()
        self._has_session_patcher.stop()
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
                pane_target="mobruji:0.0",  # legacy 단일 인자.
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
        # Discord push 첫 줄 = trigger ack (pane 이름 포함, #855).
        self.assertTrue(
            any("🧠 mobruji:0.0 context 96%" in m for m in channel.sent)
        )

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
        # Discord push 에 "정리 완료" 라인이 등장 (pane 이름 포함).
        self.assertTrue(any("mobruji:0.0 정리 완료" in m for m in channel.sent))

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

    # ─────────────────────────────────────────────────────────────────────
    # #855 multi-pane 테스트
    # ─────────────────────────────────────────────────────────────────────

    async def test_multi_pane_both_trigger_independently(self) -> None:
        """두 pane 모두 96% → 각각 inject 1번씩 + pane 이름이 Discord push 에 들어감."""
        channel = FakeChannel()
        client = FakeClient(channel)

        def fake_capture(pane: str) -> str:
            return "===CTX:96%==="

        with mock.patch.object(bot, "capture_pane_text", side_effect=fake_capture), \
             mock.patch.object(bot, "inject_cleanup_prompt", return_value=True) as inject, \
             mock.patch.object(bot, "send_clear_command", return_value=True):
            coro = bot.context_auto_clear_loop(
                client,
                channel_id=111,
                pane_targets=["mobruji:0.0", "helper:0.0"],
                trigger_pct=95,
                hysteresis_pct=80,
                poll_interval=0,
            )
            await _run_loop_iters(coro, iterations=10)

        # 각 pane 마다 inject 1번씩 (debounce 후 추가 없음).
        injected_panes = [c.args[0] for c in inject.call_args_list]
        self.assertEqual(injected_panes.count("mobruji:0.0"), 1)
        self.assertEqual(injected_panes.count("helper:0.0"), 1)
        # Discord push 에 두 pane 이름 각각 등장.
        self.assertTrue(any("mobruji:0.0 context 96%" in m for m in channel.sent))
        self.assertTrue(any("helper:0.0 context 96%" in m for m in channel.sent))

    async def test_multi_pane_state_isolation(self) -> None:
        """pane A 만 96% / pane B 만 50% → A 만 트리거, B 미트리거."""
        channel = FakeChannel()
        client = FakeClient(channel)

        def fake_capture(pane: str) -> str:
            if pane == "mobruji:0.0":
                return "===CTX:96%==="
            return "===CTX:50%==="

        with mock.patch.object(bot, "capture_pane_text", side_effect=fake_capture), \
             mock.patch.object(bot, "inject_cleanup_prompt", return_value=True) as inject, \
             mock.patch.object(bot, "send_clear_command", return_value=True):
            coro = bot.context_auto_clear_loop(
                client,
                channel_id=111,
                pane_targets=["mobruji:0.0", "helper:0.0"],
                trigger_pct=95,
                hysteresis_pct=80,
                poll_interval=0,
            )
            await _run_loop_iters(coro, iterations=10)

        injected_panes = [c.args[0] for c in inject.call_args_list]
        self.assertEqual(injected_panes, ["mobruji:0.0"])

    async def test_multi_pane_missing_session_graceful_skip(self) -> None:
        """helper 세션 부재 → helper polling 시도조차 안 함, mobruji 만 polling."""
        channel = FakeChannel()
        client = FakeClient(channel)

        # setUp 의 mock 을 풀고 pane 별 분기로 다시 패치.
        self._has_session_patcher.stop()
        try:
            def has_session_per_target(session: str) -> bool:
                # session 부 (`mobruji` / `helper`) 비교.
                return session == "mobruji"

            captured_panes: list[str] = []

            def fake_capture(pane: str) -> str:
                captured_panes.append(pane)
                return "===CTX:96%==="

            with mock.patch.object(bot, "tmux_has_session", side_effect=has_session_per_target), \
                 mock.patch.object(bot, "capture_pane_text", side_effect=fake_capture), \
                 mock.patch.object(bot, "inject_cleanup_prompt", return_value=True) as inject, \
                 mock.patch.object(bot, "send_clear_command", return_value=True):
                coro = bot.context_auto_clear_loop(
                    client,
                    channel_id=111,
                    pane_targets=["mobruji:0.0", "helper:0.0"],
                    trigger_pct=95,
                    hysteresis_pct=80,
                    poll_interval=0,
                )
                await _run_loop_iters(coro, iterations=10)

            # helper pane 은 has-session 실패 → capture 호출 자체가 일어나지 않음.
            self.assertNotIn("helper:0.0", captured_panes)
            self.assertIn("mobruji:0.0", captured_panes)
            # mobruji 만 inject (helper 는 그래스풀 skip).
            injected_panes = [c.args[0] for c in inject.call_args_list]
            self.assertEqual(injected_panes, ["mobruji:0.0"])
        finally:
            # tearDown 이 stop 을 호출하므로 다시 start.
            self._has_session_patcher = mock.patch.object(
                bot, "tmux_has_session", return_value=True
            )
            self._has_session_patcher.start()

    async def test_multi_pane_per_pane_debounce_independent(self) -> None:
        """pane A 트리거 후에도 pane B 는 처음 96% 시 별도 트리거."""
        channel = FakeChannel()
        client = FakeClient(channel)

        # iter 1: A=96 (trigger), B=50 (no)
        # iter 2: A=96 (debounced), B=96 (trigger — A 의 debounce 영향 없음)
        # iter 3+: 둘 다 96 — 추가 트리거 없음
        state = {"call": 0}

        def fake_capture(pane: str) -> str:
            # 한 iter 안에서 두 pane 이 차례로 capture 됨.
            # iteration 단위로 정확한 분리는 어려우므로 호출 시점 카운터 기반.
            state["call"] += 1
            if pane == "mobruji:0.0":
                return "===CTX:96%==="
            # helper:0.0
            # 첫 호출 (iter 1) 은 50, 그 이후엔 96.
            return "===CTX:50%===" if state["call"] <= 2 else "===CTX:96%==="

        with mock.patch.object(bot, "capture_pane_text", side_effect=fake_capture), \
             mock.patch.object(bot, "inject_cleanup_prompt", return_value=True) as inject, \
             mock.patch.object(bot, "send_clear_command", return_value=True):
            coro = bot.context_auto_clear_loop(
                client,
                channel_id=111,
                pane_targets=["mobruji:0.0", "helper:0.0"],
                trigger_pct=95,
                hysteresis_pct=80,
                poll_interval=0,
            )
            await _run_loop_iters(coro, iterations=15)

        injected_panes = [c.args[0] for c in inject.call_args_list]
        # 두 pane 각각 정확히 1번 트리거 (pane 별 독립 debounce).
        self.assertEqual(injected_panes.count("mobruji:0.0"), 1)
        self.assertEqual(injected_panes.count("helper:0.0"), 1)

    async def test_multi_pane_marker_pane_specific(self) -> None:
        """pane A 가 marker 보이면 pane A 에만 /clear, pane B 는 영향 없음."""
        channel = FakeChannel()
        client = FakeClient(channel)

        seq_a = iter([
            "===CTX:96%===",  # trigger
            "===CTX:96%===\n===CLEAR_READY===",  # marker → clear
            "===CTX:96%===",
        ])
        seq_b = iter([
            "===CTX:96%===",  # trigger
            "===CTX:96%===",  # no marker yet
            "===CTX:96%===",
        ])

        def fake_capture(pane: str) -> str:
            seq = seq_a if pane == "mobruji:0.0" else seq_b
            try:
                return next(seq)
            except StopIteration:
                return "===CTX:96%==="

        with mock.patch.object(bot, "capture_pane_text", side_effect=fake_capture), \
             mock.patch.object(bot, "inject_cleanup_prompt", return_value=True), \
             mock.patch.object(bot, "send_clear_command", return_value=True) as send_clear:
            coro = bot.context_auto_clear_loop(
                client,
                channel_id=111,
                pane_targets=["mobruji:0.0", "helper:0.0"],
                trigger_pct=95,
                hysteresis_pct=80,
                poll_interval=0,
            )
            await _run_loop_iters(coro, iterations=15)

        cleared_panes = [c.args[0] for c in send_clear.call_args_list]
        # mobruji 만 clear, helper 는 marker 없으니 clear 안 함.
        self.assertIn("mobruji:0.0", cleared_panes)
        self.assertNotIn("helper:0.0", cleared_panes)


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


# ─────────────────────────────────────────────────────────────────────────────
# clear_pane_history (#910 G-1)
# ─────────────────────────────────────────────────────────────────────────────


class ClearPaneHistoryTest(unittest.TestCase):
    """`tmux clear-history -t <pane>` 호출 + 실패 graceful."""

    def test_clear_pane_history_calls_tmux_clear_history(self) -> None:
        with mock.patch.object(bot.subprocess, "run") as run_mock:
            run_mock.return_value = mock.MagicMock(returncode=0, stderr="")
            ok = bot.clear_pane_history("mobruji:0.0")
            self.assertTrue(ok)
            run_mock.assert_called_once()
            args = run_mock.call_args.args[0]
            self.assertEqual(args, ["tmux", "clear-history", "-t", "mobruji:0.0"])

    def test_clear_pane_history_returns_false_on_failure(self) -> None:
        with mock.patch.object(bot.subprocess, "run") as run_mock:
            run_mock.return_value = mock.MagicMock(
                returncode=1, stderr="no such pane"
            )
            ok = bot.clear_pane_history("ghost:0.0")
            self.assertFalse(ok)

    def test_clear_pane_history_returns_false_on_oserror(self) -> None:
        with mock.patch.object(
            bot.subprocess, "run", side_effect=OSError("tmux missing")
        ):
            ok = bot.clear_pane_history("mobruji:0.0")
            self.assertFalse(ok)


# ─────────────────────────────────────────────────────────────────────────────
# G-1: marker stale 시나리오 — /clear 후 clear_pane_history 호출 + 재트리거 시
#       baseline 이 cleared scrollback 이라 false detect 없음
# ─────────────────────────────────────────────────────────────────────────────


class ContextAutoClearMarkerStaleTest(unittest.IsolatedAsyncioTestCase):
    """#910 G-1: CLEAR_READY_MARKER 스크롤백 stale 방지."""

    def setUp(self) -> None:
        self._tmp_dir = tempfile.TemporaryDirectory()
        self._flag_path = Path(self._tmp_dir.name) / "autoclear-paused"
        self._flag_patcher = mock.patch.object(
            bot, "AUTOCLEAR_PAUSED_FLAG", self._flag_path
        )
        self._flag_patcher.start()
        self._log_path = Path(self._tmp_dir.name) / "log.md"
        self._log_patcher = mock.patch.object(
            bot, "CONTEXT_CLEAR_LOG_PATH", self._log_path
        )
        self._log_patcher.start()
        self._has_session_patcher = mock.patch.object(
            bot, "tmux_has_session", return_value=True
        )
        self._has_session_patcher.start()

    def tearDown(self) -> None:
        self._flag_patcher.stop()
        self._log_patcher.stop()
        self._has_session_patcher.stop()
        self._tmp_dir.cleanup()

    async def test_clear_pane_history_called_after_marker_detect(self) -> None:
        """marker 감지 → send_clear_command 호출 직후 clear_pane_history 호출."""
        channel = FakeChannel()
        client = FakeClient(channel)
        seq = iter([
            "===CTX:96%===",  # trigger
            "===CTX:96%===\n===CLEAR_READY===",  # marker → /clear + clear-history
            "===CTX:50%===",
        ])

        def fake_capture(pane: str) -> str:
            try:
                return next(seq)
            except StopIteration:
                return "===CTX:50%==="

        with mock.patch.object(bot, "capture_pane_text", side_effect=fake_capture), \
             mock.patch.object(bot, "inject_cleanup_prompt", return_value=True), \
             mock.patch.object(bot, "send_clear_command", return_value=True) as send_clear, \
             mock.patch.object(bot, "clear_pane_history", return_value=True) as clear_hist:
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
        clear_hist.assert_called_with("mobruji:0.0")
        # send_clear 와 clear_pane_history 호출 횟수가 같아야 한다
        # (/clear 마다 scrollback cleanup).
        self.assertEqual(clear_hist.call_count, send_clear.call_count)

    async def test_no_false_detect_when_scrollback_cleaned(self) -> None:
        """stale marker 시나리오: /clear 후 capture 가 깨끗하면 재트리거 시
        false detect 로 즉시 /clear 가 한 번 더 나가지 않는다.

        시나리오: trigger → marker → /clear (#1) → clear-history 가 scrollback
        cleanup → 다음 iter capture 깨끗 → pct 50 (debounce 풀림) → 96 재트리거
        → awaiting_marker 상태에서 marker 없는 capture → /clear 추가 호출 0.
        """
        channel = FakeChannel()
        client = FakeClient(channel)
        seq = iter([
            "===CTX:96%===",  # 1: trigger
            "===CTX:96%===\n===CLEAR_READY===",  # 2: marker → /clear (#1)
            "===CTX:50%===",  # 3: 깨끗 (clear-history 시뮬레이션) — debounce 해제
            "===CTX:96%===",  # 4: 재트리거
            "===CTX:96%===",  # 5: awaiting_marker — marker 없음 → /clear 추가 X
            "===CTX:96%===",  # 6: 동일
        ])

        def fake_capture(pane: str) -> str:
            try:
                return next(seq)
            except StopIteration:
                return "===CTX:96%==="

        with mock.patch.object(bot, "capture_pane_text", side_effect=fake_capture), \
             mock.patch.object(bot, "inject_cleanup_prompt", return_value=True), \
             mock.patch.object(bot, "send_clear_command", return_value=True) as send_clear, \
             mock.patch.object(bot, "clear_pane_history", return_value=True):
            coro = bot.context_auto_clear_loop(
                client,
                channel_id=111,
                pane_target="mobruji:0.0",
                trigger_pct=95,
                hysteresis_pct=80,
                poll_interval=0,
            )
            await _run_loop_iters(coro, iterations=12)

        # /clear 는 정확히 1번 (첫 marker 만). 재트리거 후 marker 없으므로 추가 X.
        self.assertEqual(send_clear.call_count, 1)


# ─────────────────────────────────────────────────────────────────────────────
# G-4: on_ready 한번 skip 후 영구 미가동 방지
#      → loop 자체 항상 launch, pane 부재 시 loop 내부에서 graceful skip
# ─────────────────────────────────────────────────────────────────────────────


class OnReadyAlwaysLaunchTest(unittest.IsolatedAsyncioTestCase):
    """#910 G-4: 모든 pane 의 tmux 세션 부재해도 loop 는 launch 되어야 한다."""

    def setUp(self) -> None:
        self._tmp_dir = tempfile.TemporaryDirectory()
        self._flag_path = Path(self._tmp_dir.name) / "autoclear-paused"
        self._flag_patcher = mock.patch.object(
            bot, "AUTOCLEAR_PAUSED_FLAG", self._flag_path
        )
        self._flag_patcher.start()
        self._log_path = Path(self._tmp_dir.name) / "log.md"
        self._log_patcher = mock.patch.object(
            bot, "CONTEXT_CLEAR_LOG_PATH", self._log_path
        )
        self._log_patcher.start()

    def tearDown(self) -> None:
        self._flag_patcher.stop()
        self._log_patcher.stop()
        self._tmp_dir.cleanup()

    async def test_loop_launches_then_recovers_when_pane_appears_later(self) -> None:
        """초기 has-session=False 라도 loop 가 launch 되고, 이후 True 가 되면
        polling 이 정상 동작한다 (NCP 재부팅 순서 의존성 해소).
        """
        channel = FakeChannel()
        client = FakeClient(channel)

        # has-session: 첫 3 iter False (세션 없음) → 그 후 True.
        call_state = {"count": 0}

        def has_session_late(session: str) -> bool:
            call_state["count"] += 1
            return call_state["count"] > 3

        capture_calls: list[str] = []

        def fake_capture(pane: str) -> str:
            capture_calls.append(pane)
            return "===CTX:96%==="

        with mock.patch.object(bot, "tmux_has_session", side_effect=has_session_late), \
             mock.patch.object(bot, "capture_pane_text", side_effect=fake_capture), \
             mock.patch.object(bot, "inject_cleanup_prompt", return_value=True) as inject, \
             mock.patch.object(bot, "send_clear_command", return_value=True), \
             mock.patch.object(bot, "clear_pane_history", return_value=True):
            coro = bot.context_auto_clear_loop(
                client,
                channel_id=111,
                pane_target="mobruji:0.0",
                trigger_pct=95,
                hysteresis_pct=80,
                poll_interval=0,
            )
            await _run_loop_iters(coro, iterations=20)

        # 초기 세션 부재 동안 capture 호출 0, 세션 등장 후 polling 시작.
        # → 최소 1번은 capture 가 호출돼야 한다 (loop 가 죽지 않고 살아 있음).
        self.assertGreaterEqual(len(capture_calls), 1)
        # 세션 등장 후 96% capture → inject 최소 1번.
        self.assertGreaterEqual(inject.call_count, 1)


if __name__ == "__main__":
    unittest.main()
