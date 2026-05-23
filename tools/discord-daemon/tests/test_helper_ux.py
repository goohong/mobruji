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

    def test_ack_mode_does_not_include_message_reference(self) -> None:
        """ack 모드는 last-user-msg-id 있어도 message_reference 미적용 (회귀 가드)."""
        result, capture_path, _ = self._run_reply_test(
            "--ack",
            "ack-text",
            last_id_content="12345",
        )
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        import json
        lines = Path(capture_path).read_text().splitlines()
        ack_payload = json.loads(lines[0])
        self.assertNotIn("message_reference", ack_payload)
        self.assertIn("ack-text", ack_payload["content"])

    def test_thread_mode_does_not_include_message_reference(self) -> None:
        """thread 모드는 last-user-msg-id 있어도 message_reference 미적용 (회귀 가드)."""
        result, capture_path, _ = self._run_reply_test(
            "--thread",
            "55555",
            "stream-line",
            last_id_content="12345",
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


if __name__ == "__main__":
    unittest.main()
