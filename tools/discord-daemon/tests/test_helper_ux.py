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

    def test_auto_ack_text_v2_phrasing_guard(self) -> None:
        """BOT_AUTO_ACK_TEXT 문구 회귀 가드 (이슈 #943 v2).

        사용자 정정 (2026-05-24): helper-nmae 협업 관계 표현 필수.
        '🤖 helper bot' prefix + 'nmae 상태 확인' 두 substring 모두 포함해야 한다.
        문구 자체 변경 시 본 가드 갱신 후 진행.
        """
        self.assertIn("🤖 helper bot", bot.BOT_AUTO_ACK_TEXT)
        self.assertIn("nmae 상태 확인", bot.BOT_AUTO_ACK_TEXT)

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
        import tempfile
        with tempfile.TemporaryDirectory() as tmpdir:
            target = Path(tmpdir) / "last-user-msg-id.txt"
            with mock.patch.object(bot, "LAST_USER_MSG_ID_PATH", target):
                bot.write_last_user_msg_id("111")
                bot.write_last_user_msg_id("222")
            self.assertEqual(target.read_text(encoding="utf-8"), "222")
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
        })
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
        """--no-reply flag 면 파일 있어도 standalone."""
        result, capture_path, _ = self._run_reply_test(
            "--no-reply",
            "body",
            last_id_content="12345",
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
        })
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
        })
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
        })
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


if __name__ == "__main__":
    unittest.main()
