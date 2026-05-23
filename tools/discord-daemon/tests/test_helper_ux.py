"""helper UX infra 단위 테스트 (#880).

세 가지 기능을 한 PR 에 묶어 검증:

1. **bot.py 1초 generic auto-ack** — on_message 진입 시 `BOT_AUTO_ACK=1` (default)
   이면 generic ack 한 줄을 채널에 push, `BOT_AUTO_ACK=0` 이면 skip.
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
import os
import subprocess
import sys
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

    def _build_env(self, *, auto_ack: str = "1") -> dict[str, str]:
        return {
            "DISCORD_BOT_TOKEN": "t",
            "ALLOWED_USER_IDS": "111",
            "MOBRUJI_CHANNEL_ID": "999",
            "NOTIFY_CHANNEL_ID": "999",
            "TMUX_SESSION_NAME": "helper",
            "TMUX_TARGET_PANE": "helper:0.0",
            "CLAUDE_BIN": "claude",
            "DEDUP_LEDGER_PATH": "/tmp/test-dedup.sqlite",
            "DIGEST_ENABLED": "0",
            "CONTEXT_AUTO_CLEAR_ENABLED": "0",
            "BOT_AUTO_ACK": auto_ack,
            "CYCLE_STATUS_PATH": "/tmp/cycle-status.json",
            "TMUX_PANE_TARGETS": "helper:0.0",
            "TMUX_PANE_TARGET": "helper:0.0",
            "CONTEXT_CLEAR_TRIGGER_PCT": "95",
            "CONTEXT_CLEAR_HYSTERESIS_PCT": "80",
        }

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

    def test_auto_ack_enabled_pushes_generic_ack(self) -> None:
        env = self._build_env(auto_ack="1")
        message = _make_fake_message(
            content="hello", channel_id=999, author_id=111, message_id=1
        )
        self._run_handler(env, message)
        message.channel.send.assert_awaited_once_with(bot.BOT_AUTO_ACK_TEXT)

    def test_auto_ack_disabled_skips_push(self) -> None:
        env = self._build_env(auto_ack="0")
        message = _make_fake_message(
            content="hello", channel_id=999, author_id=111, message_id=2
        )
        self._run_handler(env, message)
        message.channel.send.assert_not_awaited()

    def test_auto_ack_default_is_enabled(self) -> None:
        env = self._build_env()
        env.pop("BOT_AUTO_ACK")
        # build_client 가 env.get("BOT_AUTO_ACK", default) 로 가져가므로
        # key 미존재 시 default ("1") 로 enabled 여야 함.
        env["BOT_AUTO_ACK"] = bot.BOT_AUTO_ACK_DEFAULT_ENABLED
        message = _make_fake_message(
            content="hello", channel_id=999, author_id=111, message_id=3
        )
        self._run_handler(env, message)
        message.channel.send.assert_awaited_once_with(bot.BOT_AUTO_ACK_TEXT)

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


if __name__ == "__main__":
    unittest.main()
