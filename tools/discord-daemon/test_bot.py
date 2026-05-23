"""Discord daemon 단순화본 단위 테스트 (이슈 #807).

표준 라이브러리 unittest + unittest.mock 만 사용. 외부 인증/네트워크 불필요.

폐기된 기능 (uniform watcher / context auto-clear / formal generator 등) 의
테스트는 삭제되었습니다. 본 파일은 단순화본 보존 함수만 검증합니다.
"""

from __future__ import annotations

import asyncio
import os
import sqlite3
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest import mock

THIS_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(THIS_DIR))

# discord / dotenv 가 venv 에 없어도 import 가능하도록 stub.
for missing in ("discord", "dotenv"):
    if missing not in sys.modules:
        stub = mock.MagicMock()
        if missing == "dotenv":
            stub.load_dotenv = lambda *a, **kw: None
        sys.modules[missing] = stub

import bot  # noqa: E402


class DedupLedgerTests(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.NamedTemporaryFile(delete=False, suffix=".sqlite")
        self.tmp.close()
        self.ledger = bot.DedupLedger(self.tmp.name)

    def tearDown(self) -> None:
        os.unlink(self.tmp.name)

    def test_mark_and_check(self) -> None:
        self.assertFalse(self.ledger.is_processed("m1"))
        self.ledger.mark_processed("m1")
        self.assertTrue(self.ledger.is_processed("m1"))

    def test_idempotent_mark(self) -> None:
        self.ledger.mark_processed("m1", now_epoch=1000)
        self.ledger.mark_processed("m1", now_epoch=2000)
        rows = sqlite3.connect(self.tmp.name).execute(
            "SELECT processed_at FROM processed_messages WHERE message_id=?",
            ("m1",),
        ).fetchall()
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0][0], 1000)

    def test_gc_removes_old(self) -> None:
        now = int(time.time())
        self.ledger.mark_processed("old", now_epoch=now - bot.DEDUP_TTL_SECONDS - 10)
        self.ledger.mark_processed("fresh", now_epoch=now)
        deleted = self.ledger.gc()
        self.assertEqual(deleted, 1)
        self.assertFalse(self.ledger.is_processed("old"))
        self.assertTrue(self.ledger.is_processed("fresh"))


class SentinelTests(unittest.TestCase):
    def test_ctrl_c(self) -> None:
        self.assertEqual(bot.resolve_sentinel("/system:ctrl-c"), ["C-c"])

    def test_enter(self) -> None:
        self.assertEqual(bot.resolve_sentinel("/system:enter"), ["Enter"])

    def test_case_insensitive(self) -> None:
        self.assertEqual(bot.resolve_sentinel("/system:CTRL-C"), ["C-c"])

    def test_plain_text_not_sentinel(self) -> None:
        self.assertIsNone(bot.resolve_sentinel("hello"))

    def test_slash_command_not_sentinel(self) -> None:
        # /exit 같은 일반 슬래시 명령은 helper TUI 로 그대로 전달.
        self.assertIsNone(bot.resolve_sentinel("/exit"))

    def test_unknown_sentinel_returns_none(self) -> None:
        self.assertIsNone(bot.resolve_sentinel("/system:nuke"))


class TmuxSendPayloadTests(unittest.TestCase):
    def _completed(self, rc: int = 0, stderr: str = "") -> mock.MagicMock:
        result = mock.MagicMock()
        result.returncode = rc
        result.stderr = stderr
        return result

    def test_sends_literal_then_enter(self) -> None:
        with mock.patch.object(bot.subprocess, "run", return_value=self._completed()) as run:
            ok = bot.tmux_send_payload("helper:0.0", "hello")
        self.assertTrue(ok)
        self.assertEqual(run.call_count, 2)
        first_args = run.call_args_list[0].args[0]
        second_args = run.call_args_list[1].args[0]
        self.assertEqual(first_args, ["tmux", "send-keys", "-t", "helper:0.0", "-l", "hello"])
        self.assertEqual(second_args, ["tmux", "send-keys", "-t", "helper:0.0", "Enter"])

    def test_sentinel_uses_control_key(self) -> None:
        with mock.patch.object(bot.subprocess, "run", return_value=self._completed()) as run:
            ok = bot.tmux_send_payload("helper:0.0", "/system:ctrl-c")
        self.assertTrue(ok)
        self.assertEqual(run.call_count, 1)
        self.assertEqual(
            run.call_args_list[0].args[0],
            ["tmux", "send-keys", "-t", "helper:0.0", "C-c"],
        )

    def test_multiline_sends_each_line(self) -> None:
        with mock.patch.object(bot.subprocess, "run", return_value=self._completed()) as run:
            ok = bot.tmux_send_payload("p", "line1\nline2")
        self.assertTrue(ok)
        # 두 줄 각각 -l + Enter = 4 호출.
        self.assertEqual(run.call_count, 4)

    def test_failure_returns_false(self) -> None:
        with mock.patch.object(bot.subprocess, "run", return_value=self._completed(rc=1, stderr="boom")):
            self.assertFalse(bot.tmux_send_payload("p", "hello"))


class TmuxSessionTests(unittest.TestCase):
    def test_ensure_creates_if_missing(self) -> None:
        calls: list[list[str]] = []

        def fake_run(cmd, **kwargs):  # noqa: ANN001
            calls.append(cmd)
            result = mock.MagicMock()
            result.returncode = 1 if cmd[:2] == ["tmux", "has-session"] else 0
            result.stderr = ""
            return result

        with mock.patch.object(bot.subprocess, "run", side_effect=fake_run):
            ok = bot.ensure_tmux_session("helper", "/usr/local/bin/claude")
        self.assertTrue(ok)
        self.assertEqual(len(calls), 2)
        self.assertEqual(calls[1][:2], ["tmux", "new-session"])
        self.assertIn("/usr/local/bin/claude", calls[1])


class MentionSanitizeTests(unittest.TestCase):
    """digest 안 PR title @everyone 등이 실제 mention 으로 발화되지 않는지 확인."""

    def test_everyone_blocked(self) -> None:
        out = bot.sanitize_mentions("@everyone hello")
        self.assertNotEqual(out, "@everyone hello")
        self.assertIn("everyone", out)

    def test_here_blocked(self) -> None:
        out = bot.sanitize_mentions("@here ping")
        self.assertNotEqual(out, "@here ping")

    def test_user_mention_blocked(self) -> None:
        out = bot.sanitize_mentions("<@123456> hi")
        self.assertNotEqual(out, "<@123456> hi")
        self.assertIn("123456", out)

    def test_plain_text_preserved(self) -> None:
        self.assertEqual(bot.sanitize_mentions("hello world"), "hello world")


class LoadEnvTests(unittest.TestCase):
    """단순화본 load_env — GITHUB_PAT/REPO 폐기, helper 가 기본 세션 이름."""

    BASE = {
        "DISCORD_BOT_TOKEN": "t",
        "ALLOWED_USER_IDS": "111",
        "MOBRUJI_CHANNEL_ID": "999",
    }

    def test_minimal_required_only(self) -> None:
        with mock.patch.dict(os.environ, self.BASE, clear=True):
            env = bot.load_env()
        self.assertEqual(env["DISCORD_BOT_TOKEN"], "t")
        self.assertEqual(env["ALLOWED_USER_IDS"], "111")
        self.assertEqual(env["MOBRUJI_CHANNEL_ID"], "999")
        # 기본 helper 세션.
        self.assertEqual(env["TMUX_SESSION_NAME"], "helper")
        self.assertEqual(env["TMUX_TARGET_PANE"], "helper:0.0")
        # NOTIFY 미설정 → MOBRUJI fallback.
        self.assertEqual(env["NOTIFY_CHANNEL_ID"], "999")
        # digest 기본 ON.
        self.assertEqual(env["DIGEST_ENABLED"], "1")

    def test_notify_channel_explicit(self) -> None:
        with mock.patch.dict(os.environ, {**self.BASE, "NOTIFY_CHANNEL_ID": "222"}, clear=True):
            env = bot.load_env()
        self.assertEqual(env["NOTIFY_CHANNEL_ID"], "222")

    def test_missing_required_exits(self) -> None:
        incomplete = {"DISCORD_BOT_TOKEN": "t"}
        with mock.patch.dict(os.environ, incomplete, clear=True):
            with self.assertRaises(SystemExit):
                bot.load_env()


class MessagePrefixTests(unittest.TestCase):
    """7 카테고리 emoji prefix — 단순화본도 호환 유지."""

    def test_all_seven_categories_present(self) -> None:
        expected = {"reply", "cycle-start", "cycle-end", "digest", "alert", "recovery", "decision"}
        self.assertEqual(set(bot.MESSAGE_PREFIX.keys()), expected)

    def test_digest_emoji(self) -> None:
        self.assertEqual(bot.MESSAGE_PREFIX["digest"], "📊")


class ResolveDigestIntervalTests(unittest.TestCase):
    def test_default_when_none(self) -> None:
        self.assertEqual(bot.resolve_digest_interval(None), bot.DEFAULT_DIGEST_INTERVAL_SECONDS)

    def test_uses_explicit(self) -> None:
        self.assertEqual(bot.resolve_digest_interval("1200"), 1200)

    def test_falls_back_on_garbage(self) -> None:
        self.assertEqual(
            bot.resolve_digest_interval("not-a-number"),
            bot.DEFAULT_DIGEST_INTERVAL_SECONDS,
        )

    def test_rejects_non_positive(self) -> None:
        self.assertEqual(bot.resolve_digest_interval("0"), bot.DEFAULT_DIGEST_INTERVAL_SECONDS)
        self.assertEqual(bot.resolve_digest_interval("-30"), bot.DEFAULT_DIGEST_INTERVAL_SECONDS)


class DigestLoopTests(unittest.TestCase):
    """digest_loop — initial push + delta + heartbeat 분기 검증.

    단순화본 시그니처: digest_loop(client, channel_id, *, interval, ...).
    legacy github_repo / github_pat 인자 제거.
    """

    def _run_loop(
        self,
        signatures: list[tuple[str, str]],
        *,
        interval: int = 900,
        heartbeat_seconds: int = 3600,
        clock_per_iter: int = 900,
    ) -> tuple[list[str], list[int]]:
        sent: list[str] = []
        sleeps: list[int] = []

        class FakeChannel:
            async def send(self_inner, text):  # noqa: ANN001
                sent.append(text)

        class FakeClient:
            def get_channel(self_inner, channel_id):  # noqa: ANN001
                return FakeChannel()

        target_sleeps = 1 + len(signatures)

        async def fake_sleep(seconds):
            sleeps.append(seconds)
            if len(sleeps) >= target_sleeps:
                raise asyncio.CancelledError

        sig_iter = iter(signatures)

        def fake_format(_status):
            line, sig = next(sig_iter)
            return line, sig

        # read_cycle_status 는 반환값 무시되므로 dummy 만.
        clock = {"t": 0}

        def fake_clock() -> float:
            clock["t"] += clock_per_iter
            return float(clock["t"])

        with mock.patch.object(bot, "read_cycle_status", return_value={}), \
             mock.patch.object(bot, "format_cycle_digest", side_effect=fake_format), \
             mock.patch.object(bot.asyncio, "sleep", side_effect=fake_sleep):
            with self.assertRaises(asyncio.CancelledError):
                asyncio.run(
                    bot.digest_loop(
                        FakeClient(),
                        channel_id=999,
                        interval=interval,
                        initial_delay=60,
                        heartbeat_seconds=heartbeat_seconds,
                        time_source=fake_clock,
                    )
                )
        return sent, sleeps

    def test_initial_always_pushes(self) -> None:
        sent, sleeps = self._run_loop(
            [("📊 A", "be=x|fe=y")],
            interval=60,
            heartbeat_seconds=3600,
            clock_per_iter=60,
        )
        self.assertEqual(sent, ["📊 A"])
        self.assertEqual(sleeps, [60, 60])  # initial_delay, interval

    def test_skips_when_signature_unchanged(self) -> None:
        sent, _ = self._run_loop(
            [
                ("📊 A", "be=x"),
                ("📊 A", "be=x"),
                ("📊 A", "be=x"),
            ],
            interval=60,
            heartbeat_seconds=3600,
            clock_per_iter=60,
        )
        # 첫 iter 만 push, 나머지 skip.
        self.assertEqual(sent, ["📊 A"])

    def test_delta_pushes_on_signature_change(self) -> None:
        sent, _ = self._run_loop(
            [
                ("📊 A", "be=x"),
                ("📊 B", "be=y"),
                ("📊 B", "be=y"),
                ("📊 C", "be=z"),
            ],
            interval=60,
            heartbeat_seconds=3600,
            clock_per_iter=60,
        )
        self.assertEqual(sent, ["📊 A", "📊 B", "📊 C"])

    def test_heartbeat_pushes_after_silence(self) -> None:
        sent, _ = self._run_loop(
            [
                ("📊 A", "be=x"),  # initial
                ("📊 A", "be=x"),  # within heartbeat → skip
                ("📊 A", "be=x"),  # heartbeat 경과 → push
            ],
            interval=900,
            heartbeat_seconds=1800,
            clock_per_iter=1000,
        )
        self.assertEqual(sent, ["📊 A", "📊 A"])


class OnMessageRoutingTests(unittest.TestCase):
    """단순화본 핵심: 사용자 메시지 = tmux send-keys 로 단순 routing.

    discord.Client 가 stub 이라 @client.event 콜백을 직접 호출하기 어려우므로
    build_client 가 정상 셋업 되는지 (예외 없이 client 반환) + 분기 의존 함수가
    각각 callable 한지를 확인합니다.
    """

    BASE_ENV = {
        "DISCORD_BOT_TOKEN": "t",
        "ALLOWED_USER_IDS": "111",
        "MOBRUJI_CHANNEL_ID": "999",
        "TMUX_SESSION_NAME": "helper",
        "TMUX_TARGET_PANE": "helper:0.0",
        "CLAUDE_BIN": "claude",
        "DEDUP_LEDGER_PATH": "/tmp/test-dedup.sqlite",
        "NOTIFY_CHANNEL_ID": "999",
        "DIGEST_ENABLED": "0",
    }

    def test_build_client_smoke(self) -> None:
        client = bot.build_client(self.BASE_ENV, ledger=None)
        self.assertIsNotNone(client)

    def test_build_client_with_invalid_notify_falls_back(self) -> None:
        env = {**self.BASE_ENV, "NOTIFY_CHANNEL_ID": "not-an-int"}
        client = bot.build_client(env, ledger=None)
        self.assertIsNotNone(client)


if __name__ == "__main__":
    unittest.main()
