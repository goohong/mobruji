"""auto-ack 동적화 단위 테스트 (#802).

`inspect_nmae_state` / `formal_dynamic_ack` 두 함수가 tmux capture 입력 4분기를
정확히 매핑하는지 검증.

사용자 룰 (2026-05-23 #모부르지) 4분기:
    1) idle / 짧은 작업   → "곧 답변 드릴게요."
    2) reasoning N분      → "reasoning N분째라 답이 늦을 수 있어요."
    3) sub-agent 가동     → "[작업명] 처리 중입니다. 곧 helper가 우선 답변 드립니다."
    4) stall / 무응답     → "응답이 없어 helper가 직접 답합니다."

이모지 📥 는 사용자 룰에 명시 허용.
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path
from unittest import mock

PARENT_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PARENT_DIR))

# discord/requests/dotenv 외부 의존 stub (실제 import 회피).
for missing in ("discord", "requests", "dotenv"):
    if missing not in sys.modules:
        stub = mock.MagicMock()
        if missing == "dotenv":
            stub.load_dotenv = lambda *a, **kw: None
        sys.modules[missing] = stub

import bot  # noqa: E402


class InspectNmaeStateTest(unittest.TestCase):
    """inspect_nmae_state 4분기 분기 검증."""

    def setUp(self) -> None:
        # stall 추적 모듈 전역 초기화 — 케이스 간 오염 방지.
        bot._LAST_CAPTURE_HASH.clear()

    def test_idle_when_only_prompt_visible(self) -> None:
        capture = """\
Some previous output line.
Another finished line.

❯
"""
        state = bot.inspect_nmae_state(
            target_pane="mobruji:0.0",
            now_ts=1000.0,
            capture_override=capture,
        )
        self.assertEqual(state["state"], bot.NMAE_STATE_IDLE)
        self.assertIsNone(state["reasoning_minutes"])
        self.assertIsNone(state["task_name"])

    def test_reasoning_with_minutes_and_seconds(self) -> None:
        # "thought for 3m 12s" → 4분 (올림).
        capture = "Claude is working...\nthought for 3m 12s\n"
        state = bot.inspect_nmae_state(
            target_pane="mobruji:0.0",
            now_ts=1000.0,
            capture_override=capture,
        )
        self.assertEqual(state["state"], bot.NMAE_STATE_REASONING)
        self.assertEqual(state["reasoning_minutes"], 4)

    def test_reasoning_with_seconds_only(self) -> None:
        # "Kneading… (42s)" → 1분 (최소 1분 표기 보장).
        capture = "Kneading… (42s)\n"
        state = bot.inspect_nmae_state(
            target_pane="mobruji:0.0",
            now_ts=1000.0,
            capture_override=capture,
        )
        self.assertEqual(state["state"], bot.NMAE_STATE_REASONING)
        self.assertEqual(state["reasoning_minutes"], 1)

    def test_subagent_in_progress_task(self) -> None:
        capture = """\
Task list:
[in_progress] codegen-refactor running
[pending] cleanup
"""
        state = bot.inspect_nmae_state(
            target_pane="mobruji:0.0",
            now_ts=1000.0,
            capture_override=capture,
        )
        self.assertEqual(state["state"], bot.NMAE_STATE_SUBAGENT)
        self.assertEqual(state["task_name"], "codegen-refactor")

    def test_subagent_async_launched(self) -> None:
        capture = "Async agent launched: feature-spec-writer\nWaiting for completion…\n"
        state = bot.inspect_nmae_state(
            target_pane="mobruji:0.0",
            now_ts=1000.0,
            capture_override=capture,
        )
        self.assertEqual(state["state"], bot.NMAE_STATE_SUBAGENT)
        self.assertEqual(state["task_name"], "feature-spec-writer")

    def test_stall_when_hash_unchanged_past_threshold(self) -> None:
        capture = "stuck output\n❯ "
        # 첫 호출 — 기록.
        first = bot.inspect_nmae_state(
            target_pane="mobruji:0.0",
            now_ts=1000.0,
            capture_override=capture,
        )
        self.assertEqual(first["state"], bot.NMAE_STATE_IDLE)
        # 임계치(300s) 이상 후 동일 capture → stall.
        second = bot.inspect_nmae_state(
            target_pane="mobruji:0.0",
            now_ts=1000.0 + bot.NMAE_STALL_THRESHOLD_SECONDS + 1,
            capture_override=capture,
        )
        self.assertEqual(second["state"], bot.NMAE_STATE_STALL)

    def test_capture_failure_fallbacks_to_idle(self) -> None:
        # _capture_pane 이 None 반환 (tmux 미가용) → idle fallback.
        with mock.patch.object(bot, "_capture_pane", return_value=None):
            state = bot.inspect_nmae_state(
                target_pane="mobruji:0.0",
                now_ts=1000.0,
            )
        self.assertEqual(state["state"], bot.NMAE_STATE_IDLE)


class FormalDynamicAckTest(unittest.TestCase):
    """formal_dynamic_ack 4분기 매핑 문자열 검증."""

    def test_idle_ack_format(self) -> None:
        ack = bot.formal_dynamic_ack(
            {"state": bot.NMAE_STATE_IDLE, "reasoning_minutes": None, "task_name": None}
        )
        self.assertEqual(ack, "📥 받았어요. 곧 답변 드릴게요.")

    def test_reasoning_ack_includes_minutes(self) -> None:
        ack = bot.formal_dynamic_ack(
            {"state": bot.NMAE_STATE_REASONING, "reasoning_minutes": 7, "task_name": None}
        )
        self.assertEqual(
            ack,
            "📥 받았어요. nmae가 reasoning 7분째라 답이 늦을 수 있어요.",
        )

    def test_subagent_ack_includes_task_name(self) -> None:
        ack = bot.formal_dynamic_ack(
            {
                "state": bot.NMAE_STATE_SUBAGENT,
                "reasoning_minutes": None,
                "task_name": "spec-writer",
            }
        )
        self.assertEqual(
            ack,
            "📥 받았어요. nmae가 spec-writer 처리 중입니다. 곧 helper가 우선 답변 드립니다.",
        )

    def test_stall_ack_format(self) -> None:
        ack = bot.formal_dynamic_ack(
            {"state": bot.NMAE_STATE_STALL, "reasoning_minutes": None, "task_name": None}
        )
        self.assertEqual(ack, "📥 받았어요. nmae가 응답이 없어 helper가 직접 답합니다.")

    def test_unknown_state_fallbacks_to_idle_message(self) -> None:
        ack = bot.formal_dynamic_ack({"state": "unexpected"})
        self.assertEqual(ack, "📥 받았어요. 곧 답변 드릴게요.")

    def test_subagent_task_name_fallback_when_missing(self) -> None:
        ack = bot.formal_dynamic_ack(
            {"state": bot.NMAE_STATE_SUBAGENT, "reasoning_minutes": None, "task_name": None}
        )
        self.assertIn("sub-agent 처리 중입니다", ack)

    def test_reasoning_minutes_default_when_missing(self) -> None:
        ack = bot.formal_dynamic_ack(
            {"state": bot.NMAE_STATE_REASONING, "reasoning_minutes": None, "task_name": None}
        )
        self.assertIn("reasoning 1분째", ack)


class InspectionWithRealTmuxCallTest(unittest.TestCase):
    """_capture_pane subprocess 호출 contract — tmux 명령 인자 검증."""

    def test_capture_pane_command_arguments(self) -> None:
        with mock.patch.object(bot.subprocess, "run") as run_mock:
            run_mock.return_value = mock.Mock(returncode=0, stdout="❯ ")
            out = bot._capture_pane("mobruji:0.0")
        self.assertEqual(out, "❯ ")
        args, kwargs = run_mock.call_args
        self.assertEqual(args[0], ["tmux", "capture-pane", "-t", "mobruji:0.0", "-p"])
        self.assertTrue(kwargs.get("capture_output"))
        self.assertTrue(kwargs.get("text"))


if __name__ == "__main__":
    unittest.main()
