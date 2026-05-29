"""helper UX infra 단위 테스트 (#880, reaction-only #1175).

세 가지 기능을 한 PR 에 묶어 검증:

1. **bot.py 1초 generic auto-ack** — on_message 진입 시 `BOT_AUTO_ACK=1` (default)
   이면 사용자 메시지에 👀 emoji reaction 만 add (text/both mode 폐기 #1175).
   `BOT_AUTO_ACK=0` 이면 skip.
2. **reply.referenced_message forwarding** — `build_reply_context_prefix` 가
   답장 컨텍스트가 있을 때 prefix `[답장→ ...] <body>` 를 붙이고, 없으면 그대로.
3. **discord-reply.sh thread mode** — 셸 스크립트 자체는 외부 Discord REST 호출이
   필요해 직접 호출 검증은 어렵지만, mode dispatch 인자 검증 (인자 부족 / 알 수
   없는 옵션 / 빈 메시지) 은 subprocess 로 가능.

테스트는 외부 네트워크 호출 없음. discord.py 의 ``message.channel.send`` 는
AsyncMock 으로 stub.
"""

from __future__ import annotations

import asyncio
import json
import os
import pathlib
import subprocess
import sys
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path
from unittest import mock

PARENT_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PARENT_DIR))

# discord 가 venv 에 없으면 가벼운 stub. embed 검증은 이 테스트에서 안 함.
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
# build_reply_context_prefix — reply.referenced_message forwarding
# ─────────────────────────────────────────────────────────────────────────────


class BuildReplyContextPrefixTests(unittest.TestCase):
    def test_no_reference_returns_body_unchanged(self) -> None:
        # referenced_content 가 None 이면 prefix 없음 (기존 호환).
        self.assertEqual(
            bot.build_reply_context_prefix(None, "hello"),
            "hello",
        )

    def test_empty_reference_returns_body_unchanged(self) -> None:
        # 빈 문자열도 답장으로 안 침.
        self.assertEqual(
            bot.build_reply_context_prefix("", "hello"),
            "hello",
        )

    def test_whitespace_only_reference_returns_body_unchanged(self) -> None:
        self.assertEqual(
            bot.build_reply_context_prefix("   \n   ", "hello"),
            "hello",
        )

    def test_short_reference_includes_full_text(self) -> None:
        result = bot.build_reply_context_prefix("PR #790 머지 필요", "응 해줘")
        self.assertEqual(result, "[답장→ PR #790 머지 필요] 응 해줘")

    def test_long_reference_truncates_with_ellipsis(self) -> None:
        # 30자 초과 → preview_len-1 자리에 … suffix.
        long_ref = "a" * 80
        result = bot.build_reply_context_prefix(long_ref, "ok")
        # `[답장→ ` + 30자 (마지막 1자는 …) + `] ok` 형태.
        self.assertTrue(result.startswith("[답장→ "))
        self.assertIn("…]", result)
        self.assertTrue(result.endswith(" ok"))
        # preview 부분 길이 정확히 preview_len.
        prefix_open = "[답장→ "
        prefix_close = "] ok"
        preview = result[len(prefix_open) : -len(prefix_close)]
        self.assertEqual(len(preview), bot.REPLY_CONTEXT_PREVIEW_LEN)

    def test_multiline_reference_flattens_to_single_line(self) -> None:
        # 줄바꿈/연속 공백은 space 1개로 압축.
        ref = "첫줄\n둘째줄\n\n세째"
        result = bot.build_reply_context_prefix(ref, "ack")
        self.assertEqual(result, "[답장→ 첫줄 둘째줄 세째] ack")

    def test_custom_preview_len(self) -> None:
        result = bot.build_reply_context_prefix(
            "abcdefghij", "x", preview_len=5
        )
        # 10자 ref, preview_len=5 → "abcd…" (5자).
        self.assertEqual(result, "[답장→ abcd…] x")


# ─────────────────────────────────────────────────────────────────────────────
# bot.py 1초 generic auto-ack — on_message 진입 시 push (BOT_AUTO_ACK 토글)
# ─────────────────────────────────────────────────────────────────────────────


def _make_fake_message(
    *,
    content: str,
    channel_id: int,
    author_id: int,
    message_id: int,
    is_bot: bool = False,
    referenced_message: object | None = None,
) -> mock.MagicMock:
    """on_message 핸들러에 줄 ducktyped discord.Message mock."""
    message = mock.MagicMock()
    message.content = content
    message.channel = mock.MagicMock()
    message.channel.id = channel_id
    message.channel.send = mock.AsyncMock()
    message.add_reaction = mock.AsyncMock()
    message.author = mock.MagicMock()
    message.author.id = author_id
    message.author.bot = is_bot
    message.author.name = "tester"
    message.id = message_id
    message.created_at = datetime(2026, 5, 23, 12, 0, tzinfo=timezone.utc)
    message.referenced_message = referenced_message
    return message


def _get_on_message_handler(client: object) -> object:
    """build_client 가 @client.event 로 등록한 on_message 콜백을 꺼내옵니다.

    실제 discord.Client 인스턴스라면 ``client.on_message`` 가 dispatcher 진입점.
    sys.modules['discord'] 가 MagicMock 인 환경에서도 동일 attr 로 노출.
    """
    return getattr(client, "on_message")


class BotAutoAckTests(unittest.TestCase):
    """on_message 가 BOT_AUTO_ACK 토글에 따라 generic ack 를 push 하는지 검증."""

    def _build_env(
        self,
        *,
        auto_ack: str = "1",
        auto_ack_mode: str | None = None,
        auto_ack_emoji: str | None = None,
        secondary_reaction_enabled: str = "0",
        cycle_status_path: str | None = None,
    ) -> dict[str, str]:
        env: dict[str, str] = {
            "DISCORD_BOT_TOKEN": "t",
            "ALLOWED_USER_IDS": "111",
            "MOBRUJI_CHANNEL_ID": "999",
            "DIGEST_CHANNEL_ID": "999",
            "TMUX_SESSION_NAME": "helper",
            "TMUX_TARGET_PANE": "helper:0.0",
            "CLAUDE_BIN": "claude",
            "DEDUP_LEDGER_PATH": "/tmp/test-dedup.sqlite",
            "DIGEST_ENABLED": "0",
            "CONTEXT_AUTO_CLEAR_ENABLED": "0",
            "BOT_AUTO_ACK": auto_ack,
            # 기존 BotAutoAck 테스트가 secondary reaction 부수효과를 만나지
            # 않도록 default disabled. secondary reaction 전용 테스트는
            # secondary_reaction_enabled="1" + cycle_status_path 명시.
            "BOT_SECONDARY_REACTION_ENABLED": secondary_reaction_enabled,
            "CYCLE_STATUS_PATH": (
                cycle_status_path
                if cycle_status_path is not None
                else "/tmp/cycle-status.json"
            ),
            "TMUX_PANE_TARGETS": "helper:0.0",
            "TMUX_PANE_TARGET": "helper:0.0",
            "CONTEXT_CLEAR_TRIGGER_PCT": "95",
            "CONTEXT_CLEAR_HYSTERESIS_PCT": "80",
        }
        if auto_ack_mode is not None:
            env["BOT_AUTO_ACK_MODE"] = auto_ack_mode
        if auto_ack_emoji is not None:
            env["BOT_AUTO_ACK_EMOJI"] = auto_ack_emoji
        return env

    def _run_handler(
        self,
        env: dict[str, str],
        message: mock.MagicMock,
    ) -> mock.MagicMock:
        """build_client → on_message 호출 + tmux/inbox 부수효과 차단."""
        ledger = bot.DedupLedger(":memory:")
        # 핸들러 등록 가로채기. discord.Client.event 데코레이터가
        # 내부에 콜백을 저장하므로, build_client 안의 @client.event 가 호출되면
        # client.on_message attr 로 노출되도록 stub.
        registered: dict[str, object] = {}

        class FakeClient:
            user = "fake-bot"
            loop = mock.MagicMock()

            def __init__(self):
                self._tasks: list[object] = []

            def event(self, func):
                # @client.event 데코레이터: 함수명으로 attr 저장.
                registered[func.__name__] = func
                setattr(self, func.__name__, func)
                return func

            def get_channel(self, channel_id):
                return None

        fake_client = FakeClient()
        with mock.patch.object(bot.discord, "Client", return_value=fake_client), \
             mock.patch.object(bot.discord, "Intents") as intents_cls, \
             mock.patch.object(bot, "ensure_tmux_session", return_value=True), \
             mock.patch.object(bot, "tmux_send_payload", return_value=True), \
             mock.patch.object(bot, "append_inbox"):
            intents_cls.default.return_value = mock.MagicMock()
            bot.build_client(env, ledger)

        handler = registered.get("on_message")
        self.assertIsNotNone(handler, "on_message 핸들러 등록 누락")

        with mock.patch.object(bot, "ensure_tmux_session", return_value=True), \
             mock.patch.object(bot, "tmux_send_payload", return_value=True), \
             mock.patch.object(bot, "append_inbox"):
            asyncio.run(handler(message))
        return message

    def test_auto_ack_enabled_adds_reaction_only(self) -> None:
        # #1175: default 동작 = 사용자 메시지에 👀 reaction 만 add (auto-ack).
        # 별도 채팅 ack push 없음 (채널 가독성 ↑).
        # 매 사용자 메시지엔 별도 분기로 📌 (PIN_REACTION_EMOJI) 도 부착됨 — 본
        # 테스트는 auto-ack 분기가 👀 를 add 했는지 만 검증.
        env = self._build_env()
        message = _make_fake_message(
            content="hello", channel_id=999, author_id=111, message_id=1
        )
        self._run_handler(env, message)
        message.channel.send.assert_not_awaited()
        calls = [c.args[0] for c in message.add_reaction.await_args_list]
        self.assertIn(bot.BOT_AUTO_ACK_EMOJI_DEFAULT, calls)

    def test_auto_ack_disabled_skips_reaction(self) -> None:
        # BOT_AUTO_ACK=0 이면 auto-ack 👀 reaction 안 함. 📌 PIN marker 는
        # auto-ack 와 무관한 별도 분기이므로 여전히 add 될 수 있음 — 여기선
        # 👀 가 호출되지 않았다는 것만 검증.
        env = self._build_env(auto_ack="0")
        message = _make_fake_message(
            content="hello", channel_id=999, author_id=111, message_id=2
        )
        self._run_handler(env, message)
        message.channel.send.assert_not_awaited()
        calls = [c.args[0] for c in message.add_reaction.await_args_list]
        self.assertNotIn(bot.BOT_AUTO_ACK_EMOJI_DEFAULT, calls)

    def test_auto_ack_emoji_override(self) -> None:
        # BOT_AUTO_ACK_EMOJI env 로 다른 emoji 지정 가능.
        env = self._build_env(auto_ack_emoji="🔥")
        message = _make_fake_message(
            content="hello", channel_id=999, author_id=111, message_id=5
        )
        self._run_handler(env, message)
        calls = [c.args[0] for c in message.add_reaction.await_args_list]
        self.assertIn("🔥", calls)
        self.assertNotIn(bot.BOT_AUTO_ACK_EMOJI_DEFAULT, calls)
        message.channel.send.assert_not_awaited()

    def test_auto_ack_default_constants(self) -> None:
        # default emoji = 👀. text/both mode 관련 상수는 #1175 에서 폐기.
        self.assertEqual(bot.BOT_AUTO_ACK_EMOJI_DEFAULT, "👀")
        self.assertEqual(bot.BOT_AUTO_ACK_DEFAULT_ENABLED, "1")

    def test_auto_ack_legacy_text_constants_removed(self) -> None:
        # #1175 회귀 가드: text/both mode 관련 상수가 bot 모듈에서 제거됐는지 확인.
        # 외부 import 잔존 시 본 테스트 실패 → cleanup 누락 알림.
        self.assertFalse(
            hasattr(bot, "BOT_AUTO_ACK_TEXT"),
            "BOT_AUTO_ACK_TEXT 상수는 #1175 에서 폐기됐어야 합니다",
        )
        self.assertFalse(
            hasattr(bot, "BOT_AUTO_ACK_MODE_DEFAULT"),
            "BOT_AUTO_ACK_MODE_DEFAULT 는 #1175 에서 폐기됐어야 합니다",
        )
        self.assertFalse(
            hasattr(bot, "BOT_AUTO_ACK_MODES_ALLOWED"),
            "BOT_AUTO_ACK_MODES_ALLOWED 는 #1175 에서 폐기됐어야 합니다",
        )

    def test_auto_ack_mode_env_is_ignored(self) -> None:
        # #1175: BOT_AUTO_ACK_MODE env 가 set 돼 있어도 reaction-only 동작.
        # backward-compat — 사용자 운영 env 잔존 시에도 silent 깨짐 없음.
        env = self._build_env(auto_ack_mode="text")
        message = _make_fake_message(
            content="hello", channel_id=999, author_id=111, message_id=6
        )
        self._run_handler(env, message)
        # text mode 무시 → channel.send 안 호출.
        message.channel.send.assert_not_awaited()
        # 👀 reaction 은 정상 add.
        calls = [c.args[0] for c in message.add_reaction.await_args_list]
        self.assertIn(bot.BOT_AUTO_ACK_EMOJI_DEFAULT, calls)

    # ------------------------------------------------------------------
    # secondary reaction (#1080) — nmae 점유 상태 emoji
    # ------------------------------------------------------------------

    def _write_cycle_status(
        self,
        path: pathlib.Path,
        *,
        occupied: int,
    ) -> None:
        """occupied 개수만큼 in_progress 채워진 cycle-status.json 작성."""
        worktrees = ("be", "fe", "rev", "plan")
        body: dict[str, dict] = {}
        for index, worktree in enumerate(worktrees):
            if index < occupied:
                body[worktree] = {
                    "in_progress": {
                        "title": f"work-{worktree}",
                        "started_at": "2026-05-26T00:00:00Z",
                    },
                    "last_completed": None,
                }
            else:
                body[worktree] = {
                    "in_progress": None,
                    "last_completed": None,
                }
        path.write_text(json.dumps(body, ensure_ascii=False))

    def test_secondary_reaction_default_constants(self) -> None:
        # default emoji 3종 = ⚡ / ⏳ / 🕐. 워크트리 = be/fe/rev/plan.
        self.assertEqual(bot.BOT_SECONDARY_REACTION_EMOJI_IDLE_DEFAULT, "⚡")
        self.assertEqual(
            bot.BOT_SECONDARY_REACTION_EMOJI_PARTIAL_DEFAULT, "⏳"
        )
        self.assertEqual(bot.BOT_SECONDARY_REACTION_EMOJI_FULL_DEFAULT, "🕐")
        self.assertEqual(bot.NMAE_WORKTREES, ("be", "fe", "rev", "plan"))
        self.assertEqual(bot.BOT_SECONDARY_REACTION_DEFAULT_ENABLED, "1")

    def test_classify_nmae_status_idle(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            cycle_path = pathlib.Path(tmpdir) / "cycle-status.json"
            self._write_cycle_status(cycle_path, occupied=0)
            result = bot.classify_nmae_status(str(cycle_path))
            self.assertEqual(result, "⚡")

    def test_classify_nmae_status_partial(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            cycle_path = pathlib.Path(tmpdir) / "cycle-status.json"
            for occupied in (1, 2, 3):
                self._write_cycle_status(cycle_path, occupied=occupied)
                result = bot.classify_nmae_status(str(cycle_path))
                self.assertEqual(
                    result, "⏳", f"occupied={occupied} → expected partial"
                )

    def test_classify_nmae_status_full(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            cycle_path = pathlib.Path(tmpdir) / "cycle-status.json"
            self._write_cycle_status(cycle_path, occupied=4)
            result = bot.classify_nmae_status(str(cycle_path))
            self.assertEqual(result, "🕐")

    def test_classify_nmae_status_file_missing(self) -> None:
        # 부재 → None (silent skip).
        result = bot.classify_nmae_status("/nonexistent/cycle-status.json")
        self.assertIsNone(result)

    def test_classify_nmae_status_parse_failure(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            cycle_path = pathlib.Path(tmpdir) / "cycle-status.json"
            cycle_path.write_text("not-json{{{")
            result = bot.classify_nmae_status(str(cycle_path))
            self.assertIsNone(result)

    def test_classify_nmae_status_custom_emojis(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            cycle_path = pathlib.Path(tmpdir) / "cycle-status.json"
            self._write_cycle_status(cycle_path, occupied=2)
            result = bot.classify_nmae_status(
                str(cycle_path),
                emoji_idle="I",
                emoji_partial="P",
                emoji_full="F",
            )
            self.assertEqual(result, "P")

    def test_secondary_reaction_disabled_by_default_skips(self) -> None:
        # _build_env default = secondary_reaction_enabled="0".
        # primary 👀 auto-ack + 📌 pin marker = 2회. secondary 안 함.
        env = self._build_env()
        message = _make_fake_message(
            content="hello", channel_id=999, author_id=111, message_id=10
        )
        self._run_handler(env, message)
        calls = [c.args[0] for c in message.add_reaction.await_args_list]
        self.assertIn(bot.BOT_AUTO_ACK_EMOJI_DEFAULT, calls)
        self.assertIn(bot.PIN_REACTION_EMOJI, calls)
        # secondary emoji (⚡⏳🕐) 부재 확인.
        for secondary in ("⚡", "⏳", "🕐"):
            self.assertNotIn(secondary, calls)

    def test_secondary_reaction_idle_adds_lightning(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            cycle_path = pathlib.Path(tmpdir) / "cycle-status.json"
            self._write_cycle_status(cycle_path, occupied=0)
            env = self._build_env(
                secondary_reaction_enabled="1",
                cycle_status_path=str(cycle_path),
            )
            message = _make_fake_message(
                content="hello", channel_id=999, author_id=111, message_id=11
            )
            self._run_handler(env, message)
            # primary 👀 + secondary ⚡ + 📌 pin marker.
            calls = [c.args[0] for c in message.add_reaction.await_args_list]
            self.assertIn(bot.BOT_AUTO_ACK_EMOJI_DEFAULT, calls)
            self.assertIn("⚡", calls)
            self.assertIn(bot.PIN_REACTION_EMOJI, calls)

    def test_secondary_reaction_partial_adds_hourglass(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            cycle_path = pathlib.Path(tmpdir) / "cycle-status.json"
            self._write_cycle_status(cycle_path, occupied=2)
            env = self._build_env(
                secondary_reaction_enabled="1",
                cycle_status_path=str(cycle_path),
            )
            message = _make_fake_message(
                content="hello", channel_id=999, author_id=111, message_id=12
            )
            self._run_handler(env, message)
            calls = [c.args[0] for c in message.add_reaction.await_args_list]
            self.assertIn(bot.BOT_AUTO_ACK_EMOJI_DEFAULT, calls)
            self.assertIn("⏳", calls)
            self.assertIn(bot.PIN_REACTION_EMOJI, calls)

    def test_secondary_reaction_full_adds_clock(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            cycle_path = pathlib.Path(tmpdir) / "cycle-status.json"
            self._write_cycle_status(cycle_path, occupied=4)
            env = self._build_env(
                secondary_reaction_enabled="1",
                cycle_status_path=str(cycle_path),
            )
            message = _make_fake_message(
                content="hello", channel_id=999, author_id=111, message_id=13
            )
            self._run_handler(env, message)
            calls = [c.args[0] for c in message.add_reaction.await_args_list]
            self.assertIn(bot.BOT_AUTO_ACK_EMOJI_DEFAULT, calls)
            self.assertIn("🕐", calls)
            self.assertIn(bot.PIN_REACTION_EMOJI, calls)

    def test_secondary_reaction_file_missing_silent_skip(self) -> None:
        # cycle-status.json 부재 → secondary skip.
        # primary 👀 + 📌 만 호출. ⚡⏳🕐 부재.
        env = self._build_env(
            secondary_reaction_enabled="1",
            cycle_status_path="/nonexistent/cycle-status.json",
        )
        message = _make_fake_message(
            content="hello", channel_id=999, author_id=111, message_id=14
        )
        self._run_handler(env, message)
        calls = [c.args[0] for c in message.add_reaction.await_args_list]
        self.assertIn(bot.BOT_AUTO_ACK_EMOJI_DEFAULT, calls)
        self.assertIn(bot.PIN_REACTION_EMOJI, calls)
        for secondary in ("⚡", "⏳", "🕐"):
            self.assertNotIn(secondary, calls)

    def test_reply_referenced_message_forwarded_to_tmux(self) -> None:
        env = self._build_env(auto_ack="0")  # ack 잡음 제거
        ref = mock.MagicMock()
        ref.content = "PR #790 머지 필요"
        message = _make_fake_message(
            content="응 해줘",
            channel_id=999,
            author_id=111,
            message_id=4,
            referenced_message=ref,
        )
        ledger = bot.DedupLedger(":memory:")
        registered: dict[str, object] = {}

        class FakeClient:
            user = "fake-bot"
            loop = mock.MagicMock()

            def event(self, func):
                registered[func.__name__] = func
                setattr(self, func.__name__, func)
                return func

            def get_channel(self, channel_id):
                return None

        fake_client = FakeClient()
        with mock.patch.object(bot.discord, "Client", return_value=fake_client), \
             mock.patch.object(bot.discord, "Intents") as intents_cls, \
             mock.patch.object(bot, "ensure_tmux_session", return_value=True), \
             mock.patch.object(bot, "tmux_send_payload", return_value=True) as send_keys, \
             mock.patch.object(bot, "append_inbox"):
            intents_cls.default.return_value = mock.MagicMock()
            bot.build_client(env, ledger)
            handler = registered["on_message"]
            asyncio.run(handler(message))

        # tmux_send_payload 가 prefix 가 붙은 텍스트를 받았는지 확인.
        send_keys.assert_called_once()
        sent_text = send_keys.call_args.args[1]
        self.assertEqual(sent_text, "[답장→ PR #790 머지 필요] 응 해줘")


# ─────────────────────────────────────────────────────────────────────────────
# discord-reply.sh mode dispatch — subprocess 호출 검증
# ─────────────────────────────────────────────────────────────────────────────


class DiscordReplyScriptModeDispatchTests(unittest.TestCase):
    """discord-reply.sh CLI 인자 검증.

    실제 Discord REST 호출은 발생시키지 않고, 인자 파싱 단계까지만 검증한다.
    .env 파일 부재 환경에서는 첫 단계에서 종료되므로 별도 mock 없이 호출 가능.
    """

    SCRIPT_PATH = (
        Path(__file__).resolve().parent.parent / "discord-reply.sh"
    )

    def _run(self, *args: str, env: dict[str, str] | None = None) -> subprocess.CompletedProcess:
        run_env = os.environ.copy()
        # .env 파일이 없는 임시 경로를 가리켜 외부 호출을 차단한다.
        # 단, --ack / --thread / bare 모드 모두 인자 파싱이 .env 체크 보다 뒤에
        # 있으므로 — bare 인자 부족 검증은 다른 방법으로.
        if env:
            run_env.update(env)
        return subprocess.run(
            ["bash", str(self.SCRIPT_PATH), *args],
            capture_output=True,
            text=True,
            env=run_env,
            timeout=5,
        )

    def test_no_args_exits_nonzero(self) -> None:
        # 인자 0 개 → 사용법 print 후 exit 1.
        # .env 로딩이 우선이라 .env 없는 경로로 우회.
        result = self._run(
            env={"DISCORD_DAEMON_ENV_PATH": "/nonexistent/.env"},
        )
        self.assertNotEqual(result.returncode, 0)

    def test_unknown_option_exits_nonzero(self) -> None:
        # .env 가 존재해야 옵션 파싱 단계에 도달. .env 없으면 그 단계에서 죽음.
        # 임시 .env 만들어 옵션 단계까지 진입.
        import tempfile
        with tempfile.NamedTemporaryFile(
            mode="w", suffix=".env", delete=False
        ) as fp:
            fp.write("DISCORD_BOT_TOKEN=stub\nMOBRUJI_CHANNEL_ID=1\n")
            env_path = fp.name
        try:
            result = self._run(
                "--frobnicate",
                "x",
                env={"DISCORD_DAEMON_ENV_PATH": env_path},
            )
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("알 수 없는 옵션", result.stderr)
        finally:
            Path(env_path).unlink(missing_ok=True)

    def test_thread_mode_requires_id_and_message(self) -> None:
        import tempfile
        with tempfile.NamedTemporaryFile(
            mode="w", suffix=".env", delete=False
        ) as fp:
            fp.write("DISCORD_BOT_TOKEN=stub\nMOBRUJI_CHANNEL_ID=1\n")
            env_path = fp.name
        try:
            result = self._run(
                "--thread",
                "12345",
                # 메시지 누락.
                env={"DISCORD_DAEMON_ENV_PATH": env_path},
            )
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("--thread", result.stderr)
        finally:
            Path(env_path).unlink(missing_ok=True)

    def test_ack_mode_requires_message(self) -> None:
        import tempfile
        with tempfile.NamedTemporaryFile(
            mode="w", suffix=".env", delete=False
        ) as fp:
            fp.write("DISCORD_BOT_TOKEN=stub\nMOBRUJI_CHANNEL_ID=1\n")
            env_path = fp.name
        try:
            result = self._run(
                "--ack",
                # 문구 누락.
                env={"DISCORD_DAEMON_ENV_PATH": env_path},
            )
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("--ack", result.stderr)
        finally:
            Path(env_path).unlink(missing_ok=True)

    def _make_fake_curl(self, tmpdir: str, capture_path: str) -> Path:
        """payload 캡처용 fake curl 생성 — #921 본답 ZWSP+\\n 검증용.

        - 매 호출마다 ``-d`` 다음 인자(payload) 를 capture_path 에 1줄 append.
        - discord_curl_with_retry (PR #911 G-6) 는 ``-w '\\n%{http_code}'`` 로
          마지막 줄에 status code 를 기대 → fake curl 도 ``{"id":"99999"}\\n200``
          형태로 응답 (status 200 = success path).
        """
        fake_curl = Path(tmpdir) / "curl"
        fake_curl.write_text(
            "#!/usr/bin/env bash\n"
            "# fake curl — -d 다음 인자(payload) 만 capture file 에 append.\n"
            "PAYLOAD=\"\"\n"
            "while [[ $# -gt 0 ]]; do\n"
            "  if [[ \"$1\" == \"-d\" ]]; then\n"
            "    shift\n"
            "    PAYLOAD=\"$1\"\n"
            "  fi\n"
            "  shift\n"
            "done\n"
            f"printf '%s\\n' \"$PAYLOAD\" >> {capture_path}\n"
            "# JSON body + newline + http_code (discord_curl_with_retry 기대 포맷).\n"
            "printf '{\"id\": \"99999\"}\\n200'\n"
        )
        fake_curl.chmod(0o755)
        return fake_curl

    def test_reply_mode_prepends_zwsp_newline(self) -> None:
        """본답 모드는 자동 leading ZWSP(U+200B) + \\n prepend (#921, 2026-05-24).

        ack 메시지와 본답 메시지가 Discord 채널에서 시각적으로 붙어 보이는
        문제 영구 해결 — jq escape 가 leading newline strip 해도 ZWSP 가
        invisible padding 으로 빈 줄 효과 보장.
        """
        import tempfile
        with tempfile.TemporaryDirectory() as tmpdir:
            capture_path = str(Path(tmpdir) / "payloads.txt")
            self._make_fake_curl(tmpdir, capture_path)

            with tempfile.NamedTemporaryFile(
                mode="w", suffix=".env", delete=False
            ) as fp:
                fp.write(
                    "DISCORD_BOT_TOKEN=stub\nMOBRUJI_CHANNEL_ID=1\n"
                    "DISCORD_RETRY_MAX=1\nDISCORD_RETRY_BASE_SEC=0\n"
                )
                env_path = fp.name
            try:
                new_path = f"{tmpdir}:{os.environ.get('PATH', '')}"
                result = self._run(
                    "hello body",
                    env={
                        "DISCORD_DAEMON_ENV_PATH": env_path,
                        "PATH": new_path,
                    },
                )
                self.assertEqual(result.returncode, 0, msg=result.stderr)
                captured = Path(capture_path).read_text()
                # jq -nc 는 비 ASCII (ZWSP) 를 literal UTF-8 byte 그대로
                # 통과. payload JSON: {"content":"​\nhello body"}
                # → raw U+200B 가 직접 포함.
                self.assertIn("​", captured)
                # \n 은 JSON escape → "\\n" 으로 직렬화됨.
                self.assertIn("\\n", captured)
                self.assertIn("hello body", captured)
                # ZWSP 가 hello 보다 앞에 있어야 함.
                zwsp_pos = captured.find("​")
                hello_pos = captured.find("hello body")
                self.assertGreater(hello_pos, zwsp_pos)
            finally:
                Path(env_path).unlink(missing_ok=True)

    def test_ack_mode_does_not_prepend_zwsp(self) -> None:
        """ack 모드는 ZWSP prepend 미적용 — 짧은 한 줄 보장 (#921)."""
        import tempfile
        with tempfile.TemporaryDirectory() as tmpdir:
            capture_path = str(Path(tmpdir) / "payloads.txt")
            self._make_fake_curl(tmpdir, capture_path)

            with tempfile.NamedTemporaryFile(
                mode="w", suffix=".env", delete=False
            ) as fp:
                fp.write(
                    "DISCORD_BOT_TOKEN=stub\nMOBRUJI_CHANNEL_ID=1\n"
                    "DISCORD_RETRY_MAX=1\nDISCORD_RETRY_BASE_SEC=0\n"
                )
                env_path = fp.name
            try:
                new_path = f"{tmpdir}:{os.environ.get('PATH', '')}"
                result = self._run(
                    "--ack",
                    "ack-text",
                    env={
                        "DISCORD_DAEMON_ENV_PATH": env_path,
                        "PATH": new_path,
                    },
                )
                self.assertEqual(result.returncode, 0, msg=result.stderr)
                # 첫 줄 = ack push payload (두번째 줄은 thread 생성 payload).
                captured_lines = Path(capture_path).read_text().splitlines()
                self.assertGreaterEqual(len(captured_lines), 1)
                ack_payload = captured_lines[0]
                # ack payload 에는 ZWSP 없어야 함.
                self.assertNotIn("​", ack_payload)
                self.assertIn("ack-text", ack_payload)
            finally:
                Path(env_path).unlink(missing_ok=True)



# ─────────────────────────────────────────────────────────────────────────────
# #946: bot.py last-user-msg-id.txt write — write_last_user_msg_id
# ─────────────────────────────────────────────────────────────────────────────


class WriteLastUserMsgIdTests(unittest.TestCase):
    """`write_last_user_msg_id` atomic file write 검증 (#946).

    bot.py 가 on_message 시 사용자 message_id 를 캐시 파일에 기록 →
    discord-reply.sh bare body 모드가 읽어 자동 reply payload 빌드.
    """

    def test_writes_message_id_to_path(self) -> None:
        import tempfile
        with tempfile.TemporaryDirectory() as tmpdir:
            target = Path(tmpdir) / "last-user-msg-id.txt"
            with mock.patch.object(bot, "LAST_USER_MSG_ID_PATH", target):
                bot.write_last_user_msg_id("1234567890123456789")
            self.assertTrue(target.exists())
            self.assertEqual(
                target.read_text(encoding="utf-8"), "1234567890123456789"
            )

    def test_overwrites_existing_value_atomically(self) -> None:
        # 두 번째 호출이 첫 값을 완전히 대체. mktemp + replace 패턴이므로
        # 부분 파일이 절대 남지 않음.
        # #964: fixture 를 valid snowflake (17+ digit) 로 교체 — 새 가드가
        # "111" / "222" 같은 짧은 값을 거부하므로 atomic overwrite 동작 자체를
        # 검증하려면 valid snowflake 가 필요.
        import tempfile
        with tempfile.TemporaryDirectory() as tmpdir:
            target = Path(tmpdir) / "last-user-msg-id.txt"
            with mock.patch.object(bot, "LAST_USER_MSG_ID_PATH", target):
                bot.write_last_user_msg_id("11111111111111111")  # 17 digits
                bot.write_last_user_msg_id("22222222222222222")  # 17 digits
            self.assertEqual(
                target.read_text(encoding="utf-8"), "22222222222222222"
            )
            # 임시 파일 (`.last-user-msg-id-XXXXXX`) 잔재 없음.
            leftover = [
                p for p in Path(tmpdir).iterdir()
                if p.name.startswith(".last-user-msg-id-")
            ]
            self.assertEqual(leftover, [], f"임시 파일 잔재: {leftover}")


# ─────────────────────────────────────────────────────────────────────────────
# #946: discord-reply.sh reply mode → message_reference payload
# ─────────────────────────────────────────────────────────────────────────────


class DiscordReplyMessageReferenceTests(unittest.TestCase):
    """본답 모드가 `~/.mobruji/last-user-msg-id.txt` 를 읽어 Discord REST
    payload 에 `message_reference` 를 포함시키는지 검증 (#946).

    fake curl 로 payload 캡처 → JSON 파싱.
    """

    SCRIPT_PATH = (
        Path(__file__).resolve().parent.parent / "discord-reply.sh"
    )

    def _make_fake_curl(self, tmpdir: str, capture_path: str) -> Path:
        fake_curl = Path(tmpdir) / "curl"
        fake_curl.write_text(
            "#!/usr/bin/env bash\n"
            "PAYLOAD=\"\"\n"
            "while [[ $# -gt 0 ]]; do\n"
            "  if [[ \"$1\" == \"-d\" ]]; then\n"
            "    shift\n"
            "    PAYLOAD=\"$1\"\n"
            "  fi\n"
            "  shift\n"
            "done\n"
            f"printf '%s\\n' \"$PAYLOAD\" >> {capture_path}\n"
            "printf '{\"id\": \"99999\"}\\n200'\n"
        )
        fake_curl.chmod(0o755)
        return fake_curl

    def _run_reply_test(
        self,
        *args: str,
        last_id_content: str | None,
    ):
        """tmpdir 안에서 fake curl + env + last-user-msg-id 파일 setup → 실행.

        Returns (CompletedProcess, captured_payloads_path, tmpdir_path).
        호출부가 tmpdir 정리 책임 (또는 그냥 ephemeral 로 둠).
        """
        import tempfile
        tmpdir = tempfile.mkdtemp()
        capture_path = str(Path(tmpdir) / "payloads.txt")
        self._make_fake_curl(tmpdir, capture_path)

        env_path = Path(tmpdir) / "test.env"
        env_path.write_text(
            "DISCORD_BOT_TOKEN=stub\n"
            "MOBRUJI_CHANNEL_ID=42\n"
            "DISCORD_RETRY_MAX=1\nDISCORD_RETRY_BASE_SEC=0\n",
            encoding="utf-8",
        )

        last_id_path = Path(tmpdir) / "last-user-msg-id.txt"
        if last_id_content is not None:
            last_id_path.write_text(last_id_content, encoding="utf-8")

        new_path = f"{tmpdir}:{os.environ.get('PATH', '')}"
        run_env = os.environ.copy()
        run_env.update({
            "DISCORD_DAEMON_ENV_PATH": str(env_path),
            "PATH": new_path,
            "LAST_USER_MSG_ID_FILE": str(last_id_path),
            # #987: 신규 우선순위 체인이 운영 ~/.mobruji 파일을 읽지 못하게 격리.
            "HELPER_TARGET_FILE": str(Path(tmpdir) / "helper-current-target.txt"),
            "HELPER_QUEUE_FILE": str(Path(tmpdir) / "helper-queue.jsonl"),
            # PR #1268: 본 클래스는 본답 main POST payload (message_reference) 만
            # 검증한다. PR #1233 의 control emoji 자동 부착 + PR #1262 (commit
            # 6a08c59) writing auto-hook default ON 이 fake curl 의 -d payload
            # capture 에 reaction PUT / typing POST / DELETE 라인을 끼워넣어
            # _first_payload 의 json.loads 가 실패 → 두 hook 모두 격리. 동작
            # 검증은 별도 케이스 (DiscordReplyControlEmojiTests / writing
            # auto-hook 시나리오) 가 담당하므로 scope 분리.
            "MOBRUJI_CONTROL_EMOJI": "0",
            "BOT_WRITING_AUTO_HOOK_ENABLED": "0",
        })
        # HELPER_TURN_TARGET_MSG_ID env 가 부모 프로세스에서 흘러들면 #987
        # 우선순위 2 가 LAST_USER_MSG_ID_FILE 보다 위라 테스트 의도 깨짐 → 명시 제거.
        run_env.pop("HELPER_TURN_TARGET_MSG_ID", None)
        result = subprocess.run(
            ["bash", str(self.SCRIPT_PATH), *args],
            capture_output=True,
            text=True,
            env=run_env,
            timeout=5,
        )
        return result, capture_path, tmpdir

    def _parse_first_payload(self, captured: str) -> dict:
        import json
        line = captured.splitlines()[0]
        return json.loads(line)

    def test_reply_mode_includes_message_reference_when_last_id_present(self) -> None:
        """본답 + last-user-msg-id 존재 → payload 에 message_reference 포함."""
        result, capture_path, _ = self._run_reply_test(
            "hello body",
            last_id_content="9876543210987654321\n",
        )
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        payload = self._parse_first_payload(Path(capture_path).read_text())
        self.assertIn("message_reference", payload)
        ref = payload["message_reference"]
        self.assertEqual(ref["message_id"], "9876543210987654321")
        self.assertEqual(ref["channel_id"], "42")
        self.assertFalse(ref["fail_if_not_exists"])
        # ZWSP+\n leading prepend (#921) 도 그대로 유지.
        self.assertIn("hello body", payload["content"])
        self.assertTrue(
            payload["content"].startswith("​\n"),
            f"ZWSP+\\n prefix 누락: {payload['content']!r}",
        )

    def test_reply_mode_no_reference_when_file_absent(self) -> None:
        """last-user-msg-id 파일 없음 → standalone (message_reference 누락)."""
        result, capture_path, _ = self._run_reply_test(
            "standalone body",
            last_id_content=None,
        )
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        payload = self._parse_first_payload(Path(capture_path).read_text())
        self.assertNotIn("message_reference", payload)
        self.assertIn("standalone body", payload["content"])

    def test_reply_mode_no_reference_when_file_empty(self) -> None:
        """파일 빈 값 → graceful standalone (parse 실패 무시)."""
        result, capture_path, _ = self._run_reply_test(
            "body",
            last_id_content="",
        )
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        payload = self._parse_first_payload(Path(capture_path).read_text())
        self.assertNotIn("message_reference", payload)

    def test_reply_mode_no_reference_when_file_non_numeric(self) -> None:
        """파일 비숫자 값 → graceful standalone (snowflake 형식 가드)."""
        result, capture_path, _ = self._run_reply_test(
            "body",
            last_id_content="not-a-snowflake",
        )
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        payload = self._parse_first_payload(Path(capture_path).read_text())
        self.assertNotIn("message_reference", payload)

    def test_reply_mode_no_reply_flag_disables_reference(self) -> None:
        """--no-reply flag 면 파일 (valid snowflake) 있어도 standalone.

        #964 (2026-05-24) 후 fixture 를 valid snowflake (17+ digit) 로 교체.
        그래야 --no-reply flag 자체의 효과를 검증 (이전 "12345" 는 새 가드에
        걸려 거부되므로 --no-reply 와 무관하게 message_reference 누락).
        """
        result, capture_path, _ = self._run_reply_test(
            "--no-reply",
            "body",
            last_id_content="12345678901234567",
        )
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        payload = self._parse_first_payload(Path(capture_path).read_text())
        self.assertNotIn("message_reference", payload)
        self.assertIn("body", payload["content"])

    def test_ack_mode_includes_message_reference_when_last_id_present(self) -> None:
        """#960: ack 모드도 last-user-msg-id 있으면 message_reference 적용.

        사용자 요청 (2026-05-24): "ack 같은 메세지들도 나의 어떤 메세지에 대한
        응답인지 답장 걸어주면 좋겠어". 첫 번째 payload (ack push) 가 reply
        형태여야 한다. 두 번째 payload (thread 생성) 는 별도 REST endpoint 이므로
        message_reference 무관.
        """
        result, capture_path, _ = self._run_reply_test(
            "--ack",
            "ack-text",
            last_id_content="12345678901234567",
        )
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        import json
        lines = Path(capture_path).read_text().splitlines()
        ack_payload = json.loads(lines[0])
        self.assertIn("message_reference", ack_payload)
        ref = ack_payload["message_reference"]
        self.assertEqual(ref["message_id"], "12345678901234567")
        self.assertEqual(ref["channel_id"], "42")
        self.assertFalse(ref["fail_if_not_exists"])
        self.assertIn("ack-text", ack_payload["content"])
        # ack 모드는 ZWSP+\n prepend 미적용 (본답 전용).
        self.assertFalse(
            ack_payload["content"].startswith("​\n"),
            f"ack 모드에 ZWSP 누락 보장: {ack_payload['content']!r}",
        )

    def test_ack_mode_no_reference_when_file_absent(self) -> None:
        """#960: ack 모드 — last-user-msg-id 부재 시 graceful standalone."""
        result, capture_path, _ = self._run_reply_test(
            "--ack",
            "ack-text",
            last_id_content=None,
        )
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        import json
        lines = Path(capture_path).read_text().splitlines()
        ack_payload = json.loads(lines[0])
        self.assertNotIn("message_reference", ack_payload)
        self.assertIn("ack-text", ack_payload["content"])

    def test_ack_mode_no_reply_flag_disables_reference(self) -> None:
        """#960: ack 모드 + --no-reply → standalone (명시적 disable)."""
        result, capture_path, _ = self._run_reply_test(
            "--no-reply",
            "--ack",
            "ack-text",
            last_id_content="12345678901234567",
        )
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        import json
        lines = Path(capture_path).read_text().splitlines()
        ack_payload = json.loads(lines[0])
        self.assertNotIn("message_reference", ack_payload)
        self.assertIn("ack-text", ack_payload["content"])

    def test_auto_ack_thread_mode_includes_message_reference(self) -> None:
        """#960: --auto-ack-thread (= --ack alias) 도 ack reply 적용."""
        result, capture_path, _ = self._run_reply_test(
            "--auto-ack-thread",
            "auto-ack-text",
            last_id_content="98765432109876543",
        )
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        import json
        lines = Path(capture_path).read_text().splitlines()
        ack_payload = json.loads(lines[0])
        self.assertIn("message_reference", ack_payload)
        self.assertEqual(
            ack_payload["message_reference"]["message_id"],
            "98765432109876543",
        )
        self.assertIn("auto-ack-text", ack_payload["content"])

    def test_auto_ack_thread_mode_no_reference_when_file_absent(self) -> None:
        """#960: --auto-ack-thread — 파일 부재 시 graceful standalone."""
        result, capture_path, _ = self._run_reply_test(
            "--auto-ack-thread",
            "auto-ack-text",
            last_id_content=None,
        )
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        import json
        lines = Path(capture_path).read_text().splitlines()
        ack_payload = json.loads(lines[0])
        self.assertNotIn("message_reference", ack_payload)
        self.assertIn("auto-ack-text", ack_payload["content"])

    def test_auto_ack_thread_mode_no_reply_flag_disables_reference(self) -> None:
        """#960: --auto-ack-thread + --no-reply → standalone."""
        result, capture_path, _ = self._run_reply_test(
            "--no-reply",
            "--auto-ack-thread",
            "auto-ack-text",
            last_id_content="98765432109876543",
        )
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        import json
        lines = Path(capture_path).read_text().splitlines()
        ack_payload = json.loads(lines[0])
        self.assertNotIn("message_reference", ack_payload)

    def test_thread_mode_does_not_include_message_reference(self) -> None:
        """thread 모드는 last-user-msg-id 있어도 message_reference 미적용 (회귀 가드).

        thread 자체가 사용자 메시지에 붙은 컨텍스트 안에서 흐르므로 thread
        안 push 메시지에 별도 reply 표시는 불필요/혼란.
        """
        result, capture_path, _ = self._run_reply_test(
            "--thread",
            "55555",
            "stream-line",
            last_id_content="12345678901234567",
        )
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        payload = self._parse_first_payload(Path(capture_path).read_text())
        self.assertNotIn("message_reference", payload)
        self.assertIn("stream-line", payload["content"])

    def test_auto_thread_mode_does_not_include_message_reference(self) -> None:
        """#960: --auto-thread 도 thread 모드와 동일하게 reply 미적용 (회귀 가드)."""
        import tempfile
        tmpdir = tempfile.mkdtemp()
        capture_path = str(Path(tmpdir) / "payloads.txt")
        self._make_fake_curl(tmpdir, capture_path)

        env_path = Path(tmpdir) / "test.env"
        env_path.write_text(
            "DISCORD_BOT_TOKEN=stub\n"
            "MOBRUJI_CHANNEL_ID=42\n"
            "DISCORD_RETRY_MAX=1\nDISCORD_RETRY_BASE_SEC=0\n",
            encoding="utf-8",
        )

        last_id_path = Path(tmpdir) / "last-user-msg-id.txt"
        last_id_path.write_text("12345678901234567", encoding="utf-8")

        # helper-current-thread.txt 에 thread_id 미리 저장.
        thread_file = Path(tmpdir) / "helper-current-thread.txt"
        thread_file.write_text("55555\n", encoding="utf-8")

        new_path = f"{tmpdir}:{os.environ.get('PATH', '')}"
        run_env = os.environ.copy()
        run_env.update({
            "DISCORD_DAEMON_ENV_PATH": str(env_path),
            "PATH": new_path,
            "LAST_USER_MSG_ID_FILE": str(last_id_path),
            "HELPER_THREAD_FILE": str(thread_file),
            # #987: 운영 ~/.mobruji 격리.
            "HELPER_TARGET_FILE": str(Path(tmpdir) / "helper-current-target.txt"),
            "HELPER_QUEUE_FILE": str(Path(tmpdir) / "helper-queue.jsonl"),
        })
        run_env.pop("HELPER_TURN_TARGET_MSG_ID", None)
        result = subprocess.run(
            ["bash", str(self.SCRIPT_PATH), "--auto-thread", "stream-line"],
            capture_output=True,
            text=True,
            env=run_env,
            timeout=5,
        )
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        payload = self._parse_first_payload(Path(capture_path).read_text())
        self.assertNotIn("message_reference", payload)
        self.assertIn("stream-line", payload["content"])

    # #964 (2026-05-24): 비-snowflake (짧은 정수 / 길이 < 17 / 길이 > 20) → graceful standalone.
    def test_reply_mode_rejects_short_non_snowflake_id(self) -> None:
        """파일에 "4" 같은 짧은 정수가 들어 있어도 message_reference 미적용.

        2026-05-24 실제 운영 사고: ~/.mobruji/last-user-msg-id.txt 가 "4" 로
        오염 → Discord API 10008 (Unknown Message) → "메시지를 불러올 수
        없어요" 채팅창 노출. read 단계 길이 가드로 graceful standalone fallback +
        stderr 알림.
        """
        result, capture_path, _ = self._run_reply_test(
            "body",
            last_id_content="4",
        )
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        payload = self._parse_first_payload(Path(capture_path).read_text())
        self.assertNotIn("message_reference", payload)
        self.assertIn("body", payload["content"])
        self.assertIn("비-snowflake", result.stderr)

    def test_reply_mode_rejects_too_long_id(self) -> None:
        """#964: 길이 > 20 (snowflake 범위 초과) 도 graceful standalone."""
        result, capture_path, _ = self._run_reply_test(
            "body",
            last_id_content="1" * 30,
        )
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        payload = self._parse_first_payload(Path(capture_path).read_text())
        self.assertNotIn("message_reference", payload)


# ─────────────────────────────────────────────────────────────────────────────
# #964: bot.py write_last_user_msg_id — invalid snowflake 거부 가드
# ─────────────────────────────────────────────────────────────────────────────


class WriteLastUserMsgIdValidatesSnowflakeTests(unittest.TestCase):
    """`write_last_user_msg_id` 가 invalid snowflake (짧은 정수 / 비숫자 /
    빈 값 / 너무 긴 값) 를 거부 — 파일 자체가 valid snowflake 만 보유하도록 보장.

    2026-05-24 운영 사고: 파일이 "4" 로 오염 → discord-reply.sh reply mode 가
    Discord API 10008 받음 → 사용자 채팅창 "메시지를 불러올 수 없어요" 노출.
    bot.py 정상 경로 (`str(message.id)`) 는 항상 18-19 digit snowflake 이므로
    이 가드에 걸리는 케이스는 외부 오염 / 수동 디버깅 잔재.
    """

    def test_rejects_single_digit_id(self) -> None:
        """\"4\" 같은 단일 digit (사고 재현) → skip + 기존 파일 미수정."""
        import tempfile
        with tempfile.TemporaryDirectory() as tmpdir:
            target = Path(tmpdir) / "last-user-msg-id.txt"
            target.write_text("9876543210987654321", encoding="utf-8")
            with mock.patch.object(bot, "LAST_USER_MSG_ID_PATH", target):
                bot.write_last_user_msg_id("4")
            # 기존 valid 값이 그대로 — invalid write 가 덮어쓰지 못함.
            self.assertEqual(
                target.read_text(encoding="utf-8"), "9876543210987654321"
            )

    def test_rejects_non_numeric_id(self) -> None:
        import tempfile
        with tempfile.TemporaryDirectory() as tmpdir:
            target = Path(tmpdir) / "last-user-msg-id.txt"
            with mock.patch.object(bot, "LAST_USER_MSG_ID_PATH", target):
                bot.write_last_user_msg_id("not-a-snowflake")
            self.assertFalse(
                target.exists(),
                "invalid write 가 파일 자체를 생성하면 안 됨",
            )

    def test_rejects_empty_string(self) -> None:
        import tempfile
        with tempfile.TemporaryDirectory() as tmpdir:
            target = Path(tmpdir) / "last-user-msg-id.txt"
            with mock.patch.object(bot, "LAST_USER_MSG_ID_PATH", target):
                bot.write_last_user_msg_id("")
            self.assertFalse(target.exists())

    def test_rejects_too_long_id(self) -> None:
        """길이 > 20 → 비-snowflake — graceful skip."""
        import tempfile
        with tempfile.TemporaryDirectory() as tmpdir:
            target = Path(tmpdir) / "last-user-msg-id.txt"
            with mock.patch.object(bot, "LAST_USER_MSG_ID_PATH", target):
                bot.write_last_user_msg_id("1" * 25)
            self.assertFalse(target.exists())

    def test_accepts_18_digit_snowflake(self) -> None:
        """경계값 — 정상 18-digit (현실 Discord 사용자 message id) 통과."""
        import tempfile
        with tempfile.TemporaryDirectory() as tmpdir:
            target = Path(tmpdir) / "last-user-msg-id.txt"
            with mock.patch.object(bot, "LAST_USER_MSG_ID_PATH", target):
                bot.write_last_user_msg_id("123456789012345678")
            self.assertEqual(
                target.read_text(encoding="utf-8"), "123456789012345678"
            )

    def test_accepts_17_digit_boundary(self) -> None:
        """최소 길이 17 통과 (LAST_USER_MSG_ID_MIN_DIGITS)."""
        import tempfile
        with tempfile.TemporaryDirectory() as tmpdir:
            target = Path(tmpdir) / "last-user-msg-id.txt"
            with mock.patch.object(bot, "LAST_USER_MSG_ID_PATH", target):
                bot.write_last_user_msg_id("12345678901234567")  # 17 digits
            self.assertEqual(
                target.read_text(encoding="utf-8"), "12345678901234567"
            )

    def test_accepts_20_digit_max_boundary(self) -> None:
        """최대 길이 20 통과 (LAST_USER_MSG_ID_MAX_DIGITS)."""
        import tempfile
        with tempfile.TemporaryDirectory() as tmpdir:
            target = Path(tmpdir) / "last-user-msg-id.txt"
            with mock.patch.object(bot, "LAST_USER_MSG_ID_PATH", target):
                bot.write_last_user_msg_id("1" * 20)
            self.assertEqual(
                target.read_text(encoding="utf-8"), "1" * 20
            )


# ─────────────────────────────────────────────────────────────────────────────
# #963: discord-reply.sh --ack mode deprecation warning
# ─────────────────────────────────────────────────────────────────────────────


class DiscordReplyAckDeprecationTests(unittest.TestCase):
    """`--ack` mode 는 helper 본체 ack push 폐지 (#963) 로 deprecated.

    호출 자체는 운영 호환성으로 유지하되 stderr 에 deprecation warning 을 emit 한다.
    `--auto-ack-thread` 는 thread 시작 용도로 redefine 됐기에 warning 없음.
    """

    SCRIPT_PATH = (
        Path(__file__).resolve().parent.parent / "discord-reply.sh"
    )

    def _make_fake_curl(self, tmpdir: str, capture_path: str) -> Path:
        fake_curl = Path(tmpdir) / "curl"
        fake_curl.write_text(
            "#!/usr/bin/env bash\n"
            "PAYLOAD=\"\"\n"
            "while [[ $# -gt 0 ]]; do\n"
            "  if [[ \"$1\" == \"-d\" ]]; then\n"
            "    shift\n"
            "    PAYLOAD=\"$1\"\n"
            "  fi\n"
            "  shift\n"
            "done\n"
            f"printf '%s\\n' \"$PAYLOAD\" >> {capture_path}\n"
            "printf '{\"id\": \"99999\"}\\n200'\n"
        )
        fake_curl.chmod(0o755)
        return fake_curl

    def _run_ack(self, flag: str) -> subprocess.CompletedProcess:
        import tempfile
        tmpdir = tempfile.mkdtemp()
        capture_path = str(Path(tmpdir) / "payloads.txt")
        self._make_fake_curl(tmpdir, capture_path)

        env_path = Path(tmpdir) / "test.env"
        env_path.write_text(
            "DISCORD_BOT_TOKEN=stub\n"
            "MOBRUJI_CHANNEL_ID=42\n"
            "DISCORD_RETRY_MAX=1\nDISCORD_RETRY_BASE_SEC=0\n",
            encoding="utf-8",
        )

        # last-user-msg-id 파일 없음 → standalone path (warning 검증에 충분).
        new_path = f"{tmpdir}:{os.environ.get('PATH', '')}"
        run_env = os.environ.copy()
        run_env.update({
            "DISCORD_DAEMON_ENV_PATH": str(env_path),
            "PATH": new_path,
            # 깨끗한 환경 — 실제 운영 파일 영향 차단.
            "LAST_USER_MSG_ID_FILE": str(Path(tmpdir) / "nonexistent.txt"),
            "HELPER_THREAD_FILE": str(Path(tmpdir) / "helper-current-thread.txt"),
            # #987: 운영 ~/.mobruji 격리.
            "HELPER_TARGET_FILE": str(Path(tmpdir) / "helper-current-target.txt"),
            "HELPER_QUEUE_FILE": str(Path(tmpdir) / "helper-queue.jsonl"),
        })
        run_env.pop("HELPER_TURN_TARGET_MSG_ID", None)
        return subprocess.run(
            ["bash", str(self.SCRIPT_PATH), flag, "ack-text"],
            capture_output=True,
            text=True,
            env=run_env,
            timeout=5,
        )

    def test_bare_ack_emits_deprecation_warning(self) -> None:
        """--ack 직접 호출 시 stderr 에 deprecation 메시지 + #963 참조 포함."""
        result = self._run_ack("--ack")
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        self.assertIn("deprecated", result.stderr.lower())
        self.assertIn("#963", result.stderr)

    def test_auto_ack_thread_no_deprecation_warning(self) -> None:
        """--auto-ack-thread 는 thread 시작 용도라 warning 없음 (회귀 가드)."""
        result = self._run_ack("--auto-ack-thread")
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        self.assertNotIn("deprecated", result.stderr.lower())


# ─────────────────────────────────────────────────────────────────────────────
# #963: 본답 (bare body) mode 가 main message 정상 push (auto-ack 대체)
# ─────────────────────────────────────────────────────────────────────────────


class DiscordReplyBareBodyTests(unittest.TestCase):
    """#963: helper 본체 ack push 제거 후 본답 (bare body) 만으로도 정상 push.

    가독성 목적 — 채널에 messages 2건 (bot auto-ack + helper 본답) 만.
    fake curl 로 payload 캡처 후 본답이 main channel POST 로 가는지 검증.
    """

    SCRIPT_PATH = (
        Path(__file__).resolve().parent.parent / "discord-reply.sh"
    )

    def test_bare_body_pushes_single_main_message(self) -> None:
        """본답 모드는 ack/thread 생성 없이 main channel POST 1건만 발생."""
        import tempfile
        tmpdir = tempfile.mkdtemp()
        capture_path = str(Path(tmpdir) / "payloads.txt")
        url_capture_path = str(Path(tmpdir) / "urls.txt")
        # url 도 같이 캡처 — main channel POST 단 1건임을 검증하기 위해.
        fake_curl = Path(tmpdir) / "curl"
        fake_curl.write_text(
            "#!/usr/bin/env bash\n"
            "URL=\"\"\n"
            "PAYLOAD=\"\"\n"
            "while [[ $# -gt 0 ]]; do\n"
            "  case \"$1\" in\n"
            "    -d) shift; PAYLOAD=\"$1\";;\n"
            "    http*) URL=\"$1\";;\n"
            "  esac\n"
            "  shift\n"
            "done\n"
            f"printf '%s\\n' \"$URL\" >> {url_capture_path}\n"
            f"printf '%s\\n' \"$PAYLOAD\" >> {capture_path}\n"
            "printf '{\"id\": \"99999\"}\\n200'\n"
        )
        fake_curl.chmod(0o755)

        env_path = Path(tmpdir) / "test.env"
        env_path.write_text(
            "DISCORD_BOT_TOKEN=stub\n"
            "MOBRUJI_CHANNEL_ID=42\n"
            "DISCORD_RETRY_MAX=1\nDISCORD_RETRY_BASE_SEC=0\n",
            encoding="utf-8",
        )

        new_path = f"{tmpdir}:{os.environ.get('PATH', '')}"
        run_env = os.environ.copy()
        run_env.update({
            "DISCORD_DAEMON_ENV_PATH": str(env_path),
            "PATH": new_path,
            "LAST_USER_MSG_ID_FILE": str(Path(tmpdir) / "nonexistent.txt"),
            # #987: 운영 ~/.mobruji 격리.
            "HELPER_TARGET_FILE": str(Path(tmpdir) / "helper-current-target.txt"),
            "HELPER_QUEUE_FILE": str(Path(tmpdir) / "helper-queue.jsonl"),
            # PR #1233 (9b0ade1) 본답 push 직후 ❓ control emoji 자동 부착이
            # main channel POST 외 reaction PUT 1건을 추가 — 본 테스트는 bare
            # body 의 단일 main POST 시나리오만 검증하므로 제어 emoji 격리.
            # 제어 emoji 자체 동작 검증은 별도 케이스 (scope 분리).
            "MOBRUJI_CONTROL_EMOJI": "0",
        })
        run_env.pop("HELPER_TURN_TARGET_MSG_ID", None)
        result = subprocess.run(
            ["bash", str(self.SCRIPT_PATH), "본답 메시지"],
            capture_output=True,
            text=True,
            env=run_env,
            timeout=5,
        )
        self.assertEqual(result.returncode, 0, msg=result.stderr)

        urls = Path(url_capture_path).read_text().splitlines()
        payloads = Path(capture_path).read_text().splitlines()
        # 본답 모드 = main channel POST 1건만 (ack/thread 생성 호출 없음).
        self.assertEqual(len(urls), 1, f"호출 1건 이상 발생: {urls}")
        self.assertIn("/channels/42/messages", urls[0])
        self.assertNotIn("/threads", urls[0])
        # payload content 가 본답 메시지 포함.
        import json
        payload = json.loads(payloads[0])
        self.assertIn("본답 메시지", payload["content"])


# ─────────────────────────────────────────────────────────────────────────────
# #987: reply race condition — resolve_reply_to_id 우선순위 체인
# ─────────────────────────────────────────────────────────────────────────────


class DiscordReplyResolvePriorityTests(unittest.TestCase):
    """`resolve_reply_to_id` 우선순위 체인 검증 (#987).

    helper turn 진행 중 새 user msg 도착으로 last-user-msg-id.txt 가 덮어쓰여
    reply 가 엉뚱한 msg 에 걸리는 race condition 차단.

    우선순위 (높음 → 낮음):
      1. --reply-to <id>
      2. HELPER_TURN_TARGET_MSG_ID env
      3. helper-current-target.txt (turn-start freeze)
      4. helper-queue.jsonl 마지막 pending entry
      5. last-user-msg-id.txt
    """

    SCRIPT_PATH = (
        Path(__file__).resolve().parent.parent / "discord-reply.sh"
    )

    def _make_fake_curl(self, tmpdir: str, capture_path: str) -> Path:
        fake_curl = Path(tmpdir) / "curl"
        fake_curl.write_text(
            "#!/usr/bin/env bash\n"
            "PAYLOAD=\"\"\n"
            "while [[ $# -gt 0 ]]; do\n"
            "  if [[ \"$1\" == \"-d\" ]]; then\n"
            "    shift\n"
            "    PAYLOAD=\"$1\"\n"
            "  fi\n"
            "  shift\n"
            "done\n"
            f"printf '%s\\n' \"$PAYLOAD\" >> {capture_path}\n"
            "printf '{\"id\": \"99999\"}\\n200'\n"
        )
        fake_curl.chmod(0o755)
        return fake_curl

    def _run(
        self,
        *args: str,
        last_id_content: str | None = None,
        target_content: str | None = None,
        queue_lines: list[str] | None = None,
        extra_env: dict[str, str] | None = None,
    ):
        """tmpdir 안에서 fake curl + env + 모든 fallback 파일 setup → 실행.

        last_id_content / target_content / queue_lines 가 None 이면 파일 자체 미생성
        (resolve_reply_to_id 가 graceful 하게 다음 fallback 으로 넘어가야 함).
        """
        import tempfile
        tmpdir = tempfile.mkdtemp()
        capture_path = str(Path(tmpdir) / "payloads.txt")
        self._make_fake_curl(tmpdir, capture_path)

        env_path = Path(tmpdir) / "test.env"
        env_path.write_text(
            "DISCORD_BOT_TOKEN=stub\n"
            "MOBRUJI_CHANNEL_ID=42\n"
            "DISCORD_RETRY_MAX=1\nDISCORD_RETRY_BASE_SEC=0\n",
            encoding="utf-8",
        )

        last_id_path = Path(tmpdir) / "last-user-msg-id.txt"
        if last_id_content is not None:
            last_id_path.write_text(last_id_content, encoding="utf-8")

        target_path = Path(tmpdir) / "helper-current-target.txt"
        if target_content is not None:
            target_path.write_text(target_content, encoding="utf-8")

        queue_path = Path(tmpdir) / "helper-queue.jsonl"
        if queue_lines is not None:
            queue_path.write_text(
                "\n".join(queue_lines) + "\n", encoding="utf-8"
            )

        new_path = f"{tmpdir}:{os.environ.get('PATH', '')}"
        run_env = os.environ.copy()
        run_env.update({
            "DISCORD_DAEMON_ENV_PATH": str(env_path),
            "PATH": new_path,
            "LAST_USER_MSG_ID_FILE": str(last_id_path),
            "HELPER_TARGET_FILE": str(target_path),
            "HELPER_QUEUE_FILE": str(queue_path),
            # PR #1268: 본 클래스는 resolve 우선순위 체인이 본답 main POST payload
            # 의 message_reference 에 어떤 msg_id 를 박는지만 검증한다. PR #1233
            # control emoji 자동 부착 + PR #1262 (commit 6a08c59) writing
            # auto-hook default ON 이 fake curl -d payload capture 에 reaction
            # PUT / typing POST / DELETE 라인을 끼워넣어 _first_payload 의
            # json.loads 가 실패 → 두 hook 모두 격리. 개별 케이스는 extra_env
            # 로 명시 override 가능.
            "MOBRUJI_CONTROL_EMOJI": "0",
            "BOT_WRITING_AUTO_HOOK_ENABLED": "0",
        })
        # HELPER_TURN_TARGET_MSG_ID 는 default 로 비움. 호출자가 extra_env 로 지정.
        run_env.pop("HELPER_TURN_TARGET_MSG_ID", None)
        if extra_env:
            run_env.update(extra_env)

        result = subprocess.run(
            ["bash", str(self.SCRIPT_PATH), *args],
            capture_output=True,
            text=True,
            env=run_env,
            timeout=5,
        )
        return result, capture_path

    def _first_payload(self, capture_path: str) -> dict:
        import json
        line = Path(capture_path).read_text().splitlines()[0]
        return json.loads(line)

    def test_reply_to_flag_overrides_all_other_sources(self) -> None:
        """--reply-to 가 env / file / queue / last-id 모두 무시하고 최우선 적용."""
        result, capture_path = self._run(
            "--reply-to",
            "11111111111111111",
            "body",
            last_id_content="22222222222222222",
            target_content="33333333333333333",
            queue_lines=['{"message_id":"44444444444444444","status":"pending"}'],
            extra_env={"HELPER_TURN_TARGET_MSG_ID": "55555555555555555"},
        )
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        payload = self._first_payload(capture_path)
        self.assertIn("message_reference", payload)
        self.assertEqual(
            payload["message_reference"]["message_id"], "11111111111111111"
        )

    def test_env_var_overrides_file_and_last_id(self) -> None:
        """HELPER_TURN_TARGET_MSG_ID env 가 file / queue / last-id 보다 우선."""
        result, capture_path = self._run(
            "body",
            last_id_content="22222222222222222",
            target_content="33333333333333333",
            queue_lines=['{"message_id":"44444444444444444","status":"pending"}'],
            extra_env={"HELPER_TURN_TARGET_MSG_ID": "55555555555555555"},
        )
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        payload = self._first_payload(capture_path)
        self.assertEqual(
            payload["message_reference"]["message_id"], "55555555555555555"
        )

    def test_target_file_freeze_overrides_last_id_race(self) -> None:
        """helper-current-target.txt freeze 가 last-user-msg-id.txt 보다 우선.

        실제 사고 재현: helper turn 시작에 target 9876... freeze, 도중에 user 새 msg
        도착해서 last-id 가 1234... 로 갱신 → reply 는 freeze 된 9876... 에 걸려야 함.
        """
        result, capture_path = self._run(
            "body",
            last_id_content="12345678901234567",  # race condition: 새 user msg
            target_content="98765432109876543",   # freeze 된 turn target
        )
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        payload = self._first_payload(capture_path)
        self.assertEqual(
            payload["message_reference"]["message_id"], "98765432109876543"
        )

    def test_queue_last_pending_used_when_target_file_missing(self) -> None:
        """target file 부재 + queue 마지막 pending entry → queue 값 사용.

        queue 에 여러 entry — pending / done 섞여 있을 때 마지막 pending 만 추출.
        """
        result, capture_path = self._run(
            "body",
            last_id_content="11111111111111111",
            queue_lines=[
                '{"message_id":"22222222222222222","status":"done"}',
                '{"message_id":"33333333333333333","status":"pending"}',
                '{"message_id":"44444444444444444","status":"done"}',
                '{"message_id":"55555555555555555","status":"pending"}',
            ],
        )
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        payload = self._first_payload(capture_path)
        # 마지막 pending = 5555...
        self.assertEqual(
            payload["message_reference"]["message_id"], "55555555555555555"
        )

    def test_falls_through_to_last_id_when_higher_sources_absent(self) -> None:
        """모든 상위 fallback 부재/실패 → last-user-msg-id.txt 사용 (기존 호환)."""
        result, capture_path = self._run(
            "body",
            last_id_content="99999999999999999",
            # target_content / queue_lines / env 모두 미설정.
        )
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        payload = self._first_payload(capture_path)
        self.assertEqual(
            payload["message_reference"]["message_id"], "99999999999999999"
        )


# ─────────────────────────────────────────────────────────────────────────────
# #1095: writing-marker / writing-done mode + bare body 자동 hook
# ─────────────────────────────────────────────────────────────────────────────


class DiscordReplyWritingMarkerTests(unittest.TestCase):
    """`--writing-marker` / `--writing-done` mode 인자 검증 + bare body 자동 hook.

    fake curl 로 endpoint URL 캡처 → PUT /reactions + POST /typing + DELETE /reactions
    호출 순서/패턴 검증.
    """

    SCRIPT_PATH = (
        Path(__file__).resolve().parent.parent / "discord-reply.sh"
    )

    def _make_fake_curl(
        self, tmpdir: str, url_capture_path: str, method_capture_path: str
    ) -> Path:
        """fake curl — URL + method 캡처용 (PUT/DELETE/POST 구분).

        - URL: 첫 http* 인자.
        - method: -X 다음 인자.
        - 반환: payload body + status 200 (reaction PUT/DELETE 는 204 가 정상이나
          stub 으로 200 충분).
        """
        fake_curl = Path(tmpdir) / "curl"
        fake_curl.write_text(
            "#!/usr/bin/env bash\n"
            "URL=\"\"\n"
            "METHOD=\"GET\"\n"
            "PAYLOAD=\"\"\n"
            "while [[ $# -gt 0 ]]; do\n"
            "  case \"$1\" in\n"
            "    -X) shift; METHOD=\"$1\";;\n"
            "    -d) shift; PAYLOAD=\"$1\";;\n"
            "    http*) URL=\"$1\";;\n"
            "  esac\n"
            "  shift\n"
            "done\n"
            f"printf '%s\\n' \"$URL\" >> {url_capture_path}\n"
            f"printf '%s\\n' \"$METHOD\" >> {method_capture_path}\n"
            "printf '{\"id\": \"99999\"}\\n200'\n"
        )
        fake_curl.chmod(0o755)
        return fake_curl

    def _run(
        self,
        *args: str,
        extra_env: dict[str, str] | None = None,
        last_id_content: str | None = None,
        target_content: str | None = None,
    ):
        """tmpdir + fake curl + env setup → 실행."""
        import tempfile
        tmpdir = tempfile.mkdtemp()
        url_capture = str(Path(tmpdir) / "urls.txt")
        method_capture = str(Path(tmpdir) / "methods.txt")
        self._make_fake_curl(tmpdir, url_capture, method_capture)

        env_path = Path(tmpdir) / "test.env"
        env_path.write_text(
            "DISCORD_BOT_TOKEN=stub\n"
            "MOBRUJI_CHANNEL_ID=42\n"
            "DISCORD_RETRY_MAX=1\nDISCORD_RETRY_BASE_SEC=0\n",
            encoding="utf-8",
        )

        last_id_path = Path(tmpdir) / "last-user-msg-id.txt"
        if last_id_content is not None:
            last_id_path.write_text(last_id_content, encoding="utf-8")

        target_path = Path(tmpdir) / "helper-current-target.txt"
        if target_content is not None:
            target_path.write_text(target_content, encoding="utf-8")

        new_path = f"{tmpdir}:{os.environ.get('PATH', '')}"
        run_env = os.environ.copy()
        run_env.update({
            "DISCORD_DAEMON_ENV_PATH": str(env_path),
            "PATH": new_path,
            "LAST_USER_MSG_ID_FILE": str(last_id_path),
            "HELPER_TARGET_FILE": str(target_path),
            "HELPER_QUEUE_FILE": str(Path(tmpdir) / "helper-queue.jsonl"),
            # PR #1233 (9b0ade1) 본답 push 직후 ❓ control emoji 자동 부착이
            # writing marker hook 호출 카운트에 reaction PUT 1건을 추가 — 본
            # 테스트 클래스는 writing marker / auto-hook 의 호출 패턴만
            # 검증하므로 제어 emoji 격리. 개별 케이스가 extra_env 로 명시
            # override 가능.
            "MOBRUJI_CONTROL_EMOJI": "0",
        })
        run_env.pop("HELPER_TURN_TARGET_MSG_ID", None)
        if extra_env:
            run_env.update(extra_env)

        result = subprocess.run(
            ["bash", str(self.SCRIPT_PATH), *args],
            capture_output=True,
            text=True,
            env=run_env,
            timeout=5,
        )
        return result, url_capture, method_capture

    # ── --writing-marker mode 인자 검증 ─────────────────────────────────────

    def test_writing_marker_missing_user_msg_id(self) -> None:
        """--writing-marker 뒤에 user_msg_id 없으면 exit 1."""
        result, _, _ = self._run("--writing-marker")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("--writing-marker", result.stderr)

    def test_writing_marker_rejects_non_snowflake(self) -> None:
        """--writing-marker user_msg_id 가 짧은 정수 (snowflake X) 면 exit 1."""
        result, _, _ = self._run("--writing-marker", "4")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("snowflake", result.stderr.lower())

    def test_writing_marker_calls_put_reaction_and_post_typing(self) -> None:
        """--writing-marker valid snowflake → PUT /reactions + POST /typing 호출."""
        result, url_capture, method_capture = self._run(
            "--writing-marker", "12345678901234567",
        )
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        urls = Path(url_capture).read_text().splitlines()
        methods = Path(method_capture).read_text().splitlines()
        # 2 건 호출 — PUT reaction + POST typing.
        self.assertEqual(len(urls), 2, f"호출 카운트 다름: {urls}")
        # 첫 번째 = PUT reaction.
        self.assertIn("PUT", methods[0])
        self.assertIn("/messages/12345678901234567/reactions/", urls[0])
        self.assertIn("@me", urls[0])
        # 두 번째 = POST typing.
        self.assertIn("POST", methods[1])
        self.assertIn("/channels/42/typing", urls[1])

    def test_writing_marker_skips_reaction_when_disabled(self) -> None:
        """BOT_WRITING_REACTION_ENABLED=0 시 reaction skip — typing 만 호출."""
        result, url_capture, method_capture = self._run(
            "--writing-marker", "12345678901234567",
            extra_env={"BOT_WRITING_REACTION_ENABLED": "0"},
        )
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        urls = Path(url_capture).read_text().splitlines()
        # 1 건 — typing 만.
        self.assertEqual(len(urls), 1)
        self.assertIn("/typing", urls[0])

    def test_writing_marker_skips_typing_when_disabled(self) -> None:
        """BOT_TYPING_INDICATOR_ENABLED=0 시 typing skip — reaction 만 호출."""
        result, url_capture, _ = self._run(
            "--writing-marker", "12345678901234567",
            extra_env={"BOT_TYPING_INDICATOR_ENABLED": "0"},
        )
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        urls = Path(url_capture).read_text().splitlines()
        self.assertEqual(len(urls), 1)
        self.assertIn("/reactions/", urls[0])

    # ── --writing-done mode 인자 검증 ───────────────────────────────────────

    def test_writing_done_missing_user_msg_id(self) -> None:
        result, _, _ = self._run("--writing-done")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("--writing-done", result.stderr)

    def test_writing_done_rejects_non_snowflake(self) -> None:
        result, _, _ = self._run("--writing-done", "abc")
        self.assertNotEqual(result.returncode, 0)

    def test_writing_done_calls_delete_reaction(self) -> None:
        """--writing-done valid snowflake → DELETE /reactions 호출 1건."""
        result, url_capture, method_capture = self._run(
            "--writing-done", "12345678901234567",
        )
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        urls = Path(url_capture).read_text().splitlines()
        methods = Path(method_capture).read_text().splitlines()
        self.assertEqual(len(urls), 1)
        self.assertIn("DELETE", methods[0])
        self.assertIn("/messages/12345678901234567/reactions/", urls[0])
        self.assertIn("@me", urls[0])

    def test_writing_done_skips_when_reaction_disabled(self) -> None:
        """BOT_WRITING_REACTION_ENABLED=0 시 done 호출이 no-op."""
        result, url_capture, _ = self._run(
            "--writing-done", "12345678901234567",
            extra_env={"BOT_WRITING_REACTION_ENABLED": "0"},
        )
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        # urls.txt 자체 미생성 (호출 0건) 또는 빈 파일.
        if Path(url_capture).exists():
            urls = Path(url_capture).read_text().splitlines()
            self.assertEqual(len(urls), 0)

    # ── bare body 자동 hook (default OFF — 사용자 directive 2026-05-29 #1294) ──
    #
    # PR #1252 (#1262) 가 default OFF → ON 전환했으나, 사용자가 노이즈로 판단해
    # 1일 만에 OFF 재전환 (#1294). 명시 opt-in (BOT_WRITING_AUTO_HOOK_ENABLED=1)
    # 시에만 자동 hook 동작.
    #
    # NOTE: 본 영역 테스트는 ❓ control emoji 자동 부착(PR #1233/#1234) 과 독립
    # 검증 목표 — 모든 _run 호출에 `MOBRUJI_CONTROL_EMOJI=0` 명시 부여로 control
    # emoji path 격리. writing auto hook default 값 자체만 회귀 검증.

    def test_bare_body_auto_hook_calls_reaction_typing_message_remove(self) -> None:
        """opt-in (BOT_WRITING_AUTO_HOOK_ENABLED=1) 본답 push 자동 hook 회귀 가드:
        PUT reaction + POST typing + POST message + DELETE reaction.

        target msg id 는 last-user-msg-id.txt (valid snowflake) 에서 resolve.
        자동 hook 은 default OFF (#1294) — env=1 명시 부여 시에만 동작.
        """
        result, url_capture, method_capture = self._run(
            "본답",
            last_id_content="12345678901234567",
            extra_env={
                "BOT_WRITING_AUTO_HOOK_ENABLED": "1",
                "MOBRUJI_CONTROL_EMOJI": "0",
            },
        )
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        urls = Path(url_capture).read_text().splitlines()
        methods = Path(method_capture).read_text().splitlines()
        # 4건 호출: PUT reaction + POST typing + POST message + DELETE reaction.
        self.assertEqual(len(urls), 4, f"호출 카운트: {urls}")
        # 순서 검증.
        self.assertIn("PUT", methods[0])
        self.assertIn("/reactions/", urls[0])
        self.assertIn("POST", methods[1])
        self.assertIn("/typing", urls[1])
        self.assertIn("POST", methods[2])
        self.assertIn("/channels/42/messages", urls[2])
        self.assertNotIn("/reactions", urls[2])
        self.assertNotIn("/typing", urls[2])
        self.assertIn("DELETE", methods[3])
        self.assertIn("/reactions/", urls[3])

    def test_bare_body_auto_hook_skipped_when_no_reply(self) -> None:
        """--no-reply → REPLY_TO_ID 빈 문자열 → writing hook 자동 skip (opt-in 환경에서도)."""
        result, url_capture, _ = self._run(
            "--no-reply", "본답",
            last_id_content="12345678901234567",
            extra_env={
                "BOT_WRITING_AUTO_HOOK_ENABLED": "1",
                "MOBRUJI_CONTROL_EMOJI": "0",
            },
        )
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        urls = Path(url_capture).read_text().splitlines()
        # 메시지 push 1건만 — reaction/typing 없음.
        self.assertEqual(len(urls), 1, f"호출 카운트: {urls}")
        self.assertIn("/channels/42/messages", urls[0])

    def test_bare_body_auto_hook_skipped_when_target_absent(self) -> None:
        """last-user-msg-id 없음 → REPLY_TO_ID 빈 → hook skip (opt-in 환경에서도)."""
        result, url_capture, _ = self._run(
            "본답",
            last_id_content=None,
            extra_env={
                "BOT_WRITING_AUTO_HOOK_ENABLED": "1",
                "MOBRUJI_CONTROL_EMOJI": "0",
            },
        )
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        urls = Path(url_capture).read_text().splitlines()
        self.assertEqual(len(urls), 1)
        self.assertIn("/messages", urls[0])

    def test_bare_body_auto_hook_off_by_default(self) -> None:
        """BOT_WRITING_AUTO_HOOK_ENABLED default OFF — env 미설정 시 자동 hook no-op.

        사용자 directive 2026-05-29 (#1294): 진행단계 자동 이모지 즉시 끄기.
        PR #1252 default ON 결정 1일 만에 OFF 재전환. 본 테스트 = default 값이
        OFF 인지 회귀 가드 (다음 default ON 재시도 PR 방지).
        """
        result, url_capture, _ = self._run(
            "본답",
            last_id_content="12345678901234567",
            extra_env={"MOBRUJI_CONTROL_EMOJI": "0"},
        )
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        urls = Path(url_capture).read_text().splitlines()
        # default OFF 이므로 message push 1건만 (reaction/typing 없음).
        self.assertEqual(len(urls), 1, f"호출 카운트: {urls}")
        self.assertIn("/messages", urls[0])

    def test_bare_body_auto_hook_opt_out_when_env_zero(self) -> None:
        """BOT_WRITING_AUTO_HOOK_ENABLED=0 명시 → 자동 hook no-op (default 와 동일 동작 확인).

        default OFF 와 동일하지만 env 명시 부여 path 회귀 가드 (#1294 default OFF
        후에도 systemd / launchd unit env 의 명시 0 부여가 동작하는지 검증).
        """
        result, url_capture, _ = self._run(
            "본답",
            last_id_content="12345678901234567",
            extra_env={
                "BOT_WRITING_AUTO_HOOK_ENABLED": "0",
                "MOBRUJI_CONTROL_EMOJI": "0",
            },
        )
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        urls = Path(url_capture).read_text().splitlines()
        # 명시 OFF 이므로 message push 1건만 (reaction/typing 없음).
        self.assertEqual(len(urls), 1, f"호출 카운트: {urls}")
        self.assertIn("/messages", urls[0])

    def test_bare_body_auto_hook_partial_reaction_only(self) -> None:
        """opt-in (AUTO_HOOK=1) + TYPING_INDICATOR_ENABLED=0 → PUT + POST message + DELETE 3건."""
        result, url_capture, methods_capture = self._run(
            "본답",
            last_id_content="12345678901234567",
            extra_env={
                "BOT_WRITING_AUTO_HOOK_ENABLED": "1",
                "BOT_TYPING_INDICATOR_ENABLED": "0",
                "MOBRUJI_CONTROL_EMOJI": "0",
            },
        )
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        urls = Path(url_capture).read_text().splitlines()
        methods = Path(methods_capture).read_text().splitlines()
        self.assertEqual(len(urls), 3, f"호출 카운트: {urls}")
        self.assertIn("PUT", methods[0])
        self.assertIn("POST", methods[1])
        self.assertIn("/messages", urls[1])
        self.assertIn("DELETE", methods[2])


# ─────────────────────────────────────────────────────────────────────────────
# #1267: MOBRUJI_CONTROL_EMOJI 양방향 정합 — bare body 본답 후 ❓ reaction PUT
# ─────────────────────────────────────────────────────────────────────────────


class DiscordReplyControlEmojiBidirectionalTests(unittest.TestCase):
    """`MOBRUJI_CONTROL_EMOJI` env 양방향 정합 검증 (#1267).

    PR #1233 (`9b0ade1`) / PR #1234 (`76f1f21`) 가 discord-reply.sh 본답 (bare
    body) push 직후 ❓ U+2753 control emoji reaction PUT 1건을 자동 부착. PR
    #1265 는 다른 test 6건 baseline 회귀를 `MOBRUJI_CONTROL_EMOJI=0` 격리로 fix
    했지만 정합 자체 검증은 hole — env 토글 동작이 변해도 fail 안 함.

    본 케이스는 토글 양방향:
    - case A (unset = default = "1"): 본답 POST 1건 + reaction PUT 1건 = 2 호출
    - case B (env "0"): 본답 POST 1건만 = 1 호출
    - case C (명시 "1"): case A 와 동일 — env explicit 도 default 와 같음

    회귀 가드: discord-reply.sh 의 control emoji 분기 (`MOBRUJI_CONTROL_EMOJI:-1`)
    가 폐기 / default 변경 / 분기 조건 변경 시 즉시 fail.

    BOT_WRITING_AUTO_HOOK_ENABLED 는 명시적으로 "0" 으로 격리 — writing hook
    의 추가 PUT/POST/DELETE 호출이 control emoji 카운트 검증과 섞이지 않게 함
    (writing hook 자체 동작은 DiscordReplyWritingMarkerTests 가 담당).
    """

    SCRIPT_PATH = (
        Path(__file__).resolve().parent.parent / "discord-reply.sh"
    )

    # ❓ U+2753 → URL-encoded UTF-8 byte sequence (PUT /reactions endpoint 용).
    # discord-reply.sh L1716 가 `reaction_add ... "%E2%9D%93"` 호출 — endpoint URL
    # 안 emoji 위치에 동일 sequence 가 포함되는지 검증.
    CONTROL_EMOJI_URLENC = "%E2%9D%93"

    def _make_fake_curl(
        self, tmpdir: str, url_capture_path: str, method_capture_path: str
    ) -> Path:
        """fake curl — URL + method 캡처 (PUT/POST/DELETE 구분).

        DiscordReplyWritingMarkerTests._make_fake_curl 와 동일 골격. 별도 함수로
        둔 이유는 본 클래스가 control emoji 검증만 책임지므로 helper 의존성을
        최소화.
        """
        fake_curl = Path(tmpdir) / "curl"
        fake_curl.write_text(
            "#!/usr/bin/env bash\n"
            "URL=\"\"\n"
            "METHOD=\"GET\"\n"
            "PAYLOAD=\"\"\n"
            "while [[ $# -gt 0 ]]; do\n"
            "  case \"$1\" in\n"
            "    -X) shift; METHOD=\"$1\";;\n"
            "    -d) shift; PAYLOAD=\"$1\";;\n"
            "    http*) URL=\"$1\";;\n"
            "  esac\n"
            "  shift\n"
            "done\n"
            f"printf '%s\\n' \"$URL\" >> {url_capture_path}\n"
            f"printf '%s\\n' \"$METHOD\" >> {method_capture_path}\n"
            # POST /channels/{id}/messages 응답 — discord-reply.sh 가 .id 를 jq
            # 로 parse 해 reaction PUT 의 message_id 로 사용. 18-digit snowflake
            # 형식으로 reaction_add URL 안에 정상 박힘.
            "printf '{\"id\": \"999999999999999999\"}\\n200'\n"
        )
        fake_curl.chmod(0o755)
        return fake_curl

    def _run(
        self,
        *args: str,
        control_emoji_env: str | None,
    ):
        """tmpdir 안에서 fake curl + env setup → discord-reply.sh 본답 실행.

        control_emoji_env:
          - None: env 자체 미설정 (default 동작 = "1" — control emoji 부착)
          - "0":  env 명시 "0" — control emoji skip
          - "1":  env 명시 "1" — default 와 동일 동작
        """
        import tempfile
        tmpdir = tempfile.mkdtemp()
        url_capture = str(Path(tmpdir) / "urls.txt")
        method_capture = str(Path(tmpdir) / "methods.txt")
        self._make_fake_curl(tmpdir, url_capture, method_capture)

        env_path = Path(tmpdir) / "test.env"
        env_path.write_text(
            "DISCORD_BOT_TOKEN=stub\n"
            "MOBRUJI_CHANNEL_ID=42\n"
            "DISCORD_RETRY_MAX=1\nDISCORD_RETRY_BASE_SEC=0\n",
            encoding="utf-8",
        )

        new_path = f"{tmpdir}:{os.environ.get('PATH', '')}"
        run_env = os.environ.copy()
        run_env.update({
            "DISCORD_DAEMON_ENV_PATH": str(env_path),
            "PATH": new_path,
            # 운영 ~/.mobruji 격리 — reply target / queue / thread 모두 부재.
            "LAST_USER_MSG_ID_FILE": str(Path(tmpdir) / "nonexistent.txt"),
            "HELPER_TARGET_FILE": str(Path(tmpdir) / "helper-current-target.txt"),
            "HELPER_QUEUE_FILE": str(Path(tmpdir) / "helper-queue.jsonl"),
            "HELPER_THREAD_FILE": str(Path(tmpdir) / "helper-current-thread.txt"),
            # writing hook 격리 — PR #1262 (`BOT_WRITING_AUTO_HOOK_ENABLED`
            # default ON) 가 본답 push 전후로 PUT reaction + POST typing +
            # DELETE reaction 호출을 추가. 본 클래스는 control emoji 카운트만
            # 검증하므로 명시 OFF. writing hook 자체 동작은
            # DiscordReplyWritingMarkerTests 가 담당.
            "BOT_WRITING_AUTO_HOOK_ENABLED": "0",
        })
        run_env.pop("HELPER_TURN_TARGET_MSG_ID", None)
        # MOBRUJI_CONTROL_EMOJI 명시 처리. control_emoji_env=None 이면 부모 env 에
        # 잔존할 수도 있으므로 pop. "0" / "1" 면 update.
        run_env.pop("MOBRUJI_CONTROL_EMOJI", None)
        if control_emoji_env is not None:
            run_env["MOBRUJI_CONTROL_EMOJI"] = control_emoji_env

        result = subprocess.run(
            ["bash", str(self.SCRIPT_PATH), "본답 메시지"],
            capture_output=True,
            text=True,
            env=run_env,
            timeout=5,
        )
        return result, url_capture, method_capture

    def _count_control_emoji_put(
        self, urls: list[str], methods: list[str]
    ) -> int:
        """PUT /reactions/{❓ urlenc}/@me 호출 카운트."""
        count = 0
        for url, method in zip(urls, methods):
            if (
                method == "PUT"
                and "/reactions/" in url
                and self.CONTROL_EMOJI_URLENC in url
                and url.endswith("/@me")
            ):
                count += 1
        return count

    def test_default_unset_attaches_control_emoji_reaction(self) -> None:
        """env 미설정 = default "1" — 본답 POST 후 ❓ reaction PUT 1건 발생."""
        result, url_capture, method_capture = self._run(
            control_emoji_env=None,
        )
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        urls = Path(url_capture).read_text().splitlines()
        methods = Path(method_capture).read_text().splitlines()
        # 2건 호출: POST /messages + PUT /reactions/❓/@me.
        self.assertEqual(len(urls), 2, f"호출 카운트 다름: {urls}")
        # 첫 번째 = 본답 POST.
        self.assertIn("POST", methods[0])
        self.assertIn("/channels/42/messages", urls[0])
        # ❓ reaction PUT 1건 (호출 순서 무관 — 카운트 검증).
        self.assertEqual(
            self._count_control_emoji_put(urls, methods),
            1,
            f"❓ reaction PUT 1건 기대: urls={urls} methods={methods}",
        )

    def test_env_zero_skips_control_emoji_reaction(self) -> None:
        """env "0" — ❓ reaction PUT 0건, 본답 POST 1건만."""
        result, url_capture, method_capture = self._run(
            control_emoji_env="0",
        )
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        urls = Path(url_capture).read_text().splitlines()
        methods = Path(method_capture).read_text().splitlines()
        # 1건 호출 — 본답 POST 만.
        self.assertEqual(len(urls), 1, f"호출 카운트 다름: {urls}")
        self.assertIn("POST", methods[0])
        self.assertIn("/channels/42/messages", urls[0])
        # ❓ reaction PUT 부재 확인.
        self.assertEqual(
            self._count_control_emoji_put(urls, methods),
            0,
            f"❓ reaction PUT 0건 기대: urls={urls} methods={methods}",
        )

    def test_env_one_attaches_control_emoji_reaction(self) -> None:
        """env "1" 명시 — default 와 동일 (정합 회귀 가드)."""
        result, url_capture, method_capture = self._run(
            control_emoji_env="1",
        )
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        urls = Path(url_capture).read_text().splitlines()
        methods = Path(method_capture).read_text().splitlines()
        self.assertEqual(len(urls), 2, f"호출 카운트 다름: {urls}")
        self.assertEqual(
            self._count_control_emoji_put(urls, methods),
            1,
            f"❓ reaction PUT 1건 기대 (env=1 명시): urls={urls} methods={methods}",
        )

    def test_control_emoji_targets_main_push_message_id(self) -> None:
        """❓ reaction 의 URL 안 message_id 가 본답 POST 응답 .id 와 일치.

        discord-reply.sh L1713-1716 의 분기 — POST 응답 jq parse → reaction_add
        에 message_id 전달. fake curl 이 모든 POST 에 동일 id
        "999999999999999999" 를 반환하므로 reaction PUT URL 안 message_id 위치에
        같은 값이 들어가야 한다.
        """
        result, url_capture, method_capture = self._run(
            control_emoji_env=None,
        )
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        urls = Path(url_capture).read_text().splitlines()
        methods = Path(method_capture).read_text().splitlines()
        # ❓ PUT URL 추출.
        put_urls = [
            url for url, method in zip(urls, methods)
            if method == "PUT" and self.CONTROL_EMOJI_URLENC in url
        ]
        self.assertEqual(
            len(put_urls), 1, f"❓ PUT URL 1건 기대: {put_urls}"
        )
        # message_id 위치 = /messages/{id}/reactions/...
        self.assertIn("/messages/999999999999999999/reactions/", put_urls[0])


if __name__ == "__main__":
    unittest.main()
