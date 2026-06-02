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

# discord 는 가능한 한 실제 모듈을 사용 — embed 검증과 일관성 (#840).
try:
    import discord as _real_discord  # noqa: F401
except ImportError:
    sys.modules["discord"] = mock.MagicMock()

# dotenv 는 단순 stub.
if "dotenv" not in sys.modules:
    stub = mock.MagicMock()
    stub.load_dotenv = lambda *a, **kw: None
    sys.modules["dotenv"] = stub

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
        # DIGEST 미설정 → MOBRUJI fallback (#1019 rename).
        self.assertEqual(env["DIGEST_CHANNEL_ID"], "999")
        # digest 기본 ON.
        self.assertEqual(env["DIGEST_ENABLED"], "1")

    def test_digest_channel_explicit(self) -> None:
        with mock.patch.dict(os.environ, {**self.BASE, "DIGEST_CHANNEL_ID": "222"}, clear=True):
            env = bot.load_env()
        self.assertEqual(env["DIGEST_CHANNEL_ID"], "222")

    def test_notify_channel_legacy_backward_compat(self) -> None:
        """#1019: 기존 NOTIFY_CHANNEL_ID 만 설정해도 DIGEST_CHANNEL_ID 로 fallback."""
        with mock.patch.dict(
            os.environ, {**self.BASE, "NOTIFY_CHANNEL_ID": "333"}, clear=True
        ):
            # deprecation warning idempotent 보장 위해 plug reset.
            if hasattr(bot.load_env, "_notify_deprecation_warned"):
                delattr(bot.load_env, "_notify_deprecation_warned")
            env = bot.load_env()
        self.assertEqual(env["DIGEST_CHANNEL_ID"], "333")

    def test_digest_channel_wins_over_legacy_notify(self) -> None:
        """DIGEST_CHANNEL_ID 와 NOTIFY_CHANNEL_ID 둘 다 있으면 DIGEST 우선."""
        with mock.patch.dict(
            os.environ,
            {
                **self.BASE,
                "DIGEST_CHANNEL_ID": "444",
                "NOTIFY_CHANNEL_ID": "555",
            },
            clear=True,
        ):
            env = bot.load_env()
        self.assertEqual(env["DIGEST_CHANNEL_ID"], "444")

    def test_missing_required_exits(self) -> None:
        incomplete = {"DISCORD_BOT_TOKEN": "t"}
        with mock.patch.dict(os.environ, incomplete, clear=True):
            with self.assertRaises(SystemExit):
                bot.load_env()


class MessagePrefixTests(unittest.TestCase):
    """단순화본은 digest 만 사용 — 나머지 6종 #819 에서 dead branch 제거."""

    def test_only_digest_present(self) -> None:
        self.assertEqual(set(bot.MESSAGE_PREFIX.keys()), {"digest"})

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
            async def send(self_inner, content=None, *, embed=None):  # noqa: ANN001
                # 단순화본 #840: digest 는 embed 로 push. content 는 사용 안 함.
                sent.append(embed if embed is not None else content)

        class FakeClient:
            def get_channel(self_inner, channel_id):  # noqa: ANN001
                return FakeChannel()

        target_sleeps = 1 + len(signatures)

        async def fake_sleep(seconds):
            sleeps.append(seconds)
            if len(sleeps) >= target_sleeps:
                raise asyncio.CancelledError

        sig_iter = iter(signatures)

        # `interval_seconds` keyword 를 받는 새 시그니처 (#840).
        def fake_format(_status, *_, **_kwargs):
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
        "DIGEST_CHANNEL_ID": "999",
        "DIGEST_ENABLED": "0",
    }

    def test_build_client_smoke(self) -> None:
        client = bot.build_client(self.BASE_ENV, ledger=None)
        self.assertIsNotNone(client)

    def test_build_client_with_invalid_digest_falls_back(self) -> None:
        env = {**self.BASE_ENV, "DIGEST_CHANNEL_ID": "not-an-int"}
        client = bot.build_client(env, ledger=None)
        self.assertIsNotNone(client)


class ChoicePromptTests(unittest.TestCase):
    """spec: docs/features/discord-reaction-choice-input.md — reaction-choice helpers."""

    def setUp(self) -> None:
        self.tmpdir = tempfile.TemporaryDirectory()
        self.path = Path(self.tmpdir.name) / "choice-prompts.jsonl"

    def tearDown(self) -> None:
        self.tmpdir.cleanup()

    def test_parse_choice_emoji_keycaps(self) -> None:
        cases = [
            ("1️⃣", 0),
            ("2️⃣", 1),
            ("3️⃣", 2),
            ("4️⃣", 3),
            ("5️⃣", 4),
            ("6️⃣", 5),
            ("7️⃣", 6),
            ("8️⃣", 7),
            ("9️⃣", 8),
            ("\U0001f51f", 9),
        ]
        for emoji, expected_idx in cases:
            with self.subTest(emoji=emoji):
                self.assertEqual(bot.parse_choice_emoji(emoji), expected_idx)

    def test_parse_choice_emoji_non_keycap_returns_none(self) -> None:
        self.assertIsNone(bot.parse_choice_emoji("👀"))
        self.assertIsNone(bot.parse_choice_emoji("0️⃣"))
        self.assertIsNone(bot.parse_choice_emoji(""))
        self.assertIsNone(bot.parse_choice_emoji("hello"))

    def test_lookup_choice_prompt_missing_file(self) -> None:
        self.assertIsNone(bot.lookup_choice_prompt("999", path=self.path))

    def _write_register(self, message_id: str, choices: list[str]) -> None:
        import json as _json

        with self.path.open("a", encoding="utf-8") as fh:
            fh.write(
                _json.dumps(
                    {
                        "event": "register",
                        "message_id": message_id,
                        "channel_id": "C1",
                        "choices": choices,
                        "ts": "2026-05-28T00:00:00Z",
                    }
                )
                + "\n"
            )

    def test_lookup_choice_prompt_active(self) -> None:
        self._write_register("M1", ["yes", "no"])
        result = bot.lookup_choice_prompt("M1", path=self.path)
        self.assertIsNotNone(result)
        self.assertEqual(result["choices"], ["yes", "no"])

    def test_lookup_choice_prompt_consumed_returns_none(self) -> None:
        self._write_register("M1", ["yes", "no"])
        bot.mark_choice_consumed(
            message_id="M1", choice_idx=0, user_id="U1", path=self.path
        )
        self.assertIsNone(bot.lookup_choice_prompt("M1", path=self.path))

    def test_lookup_choice_prompt_ignores_other_message_ids(self) -> None:
        self._write_register("M2", ["a"])
        self.assertIsNone(bot.lookup_choice_prompt("M1", path=self.path))

    def test_lookup_choice_prompt_corrupt_lines_graceful(self) -> None:
        with self.path.open("w", encoding="utf-8") as fh:
            fh.write("not-json-line\n")
            fh.write("\n")
        self._write_register("M1", ["a"])
        result = bot.lookup_choice_prompt("M1", path=self.path)
        self.assertIsNotNone(result)
        self.assertEqual(result["choices"], ["a"])


class UserModeTests(unittest.TestCase):
    """spec: docs/features/discord-reaction-choice-input.md — user mode toggle file."""

    def setUp(self) -> None:
        self.tmpdir = tempfile.TemporaryDirectory()
        self.path = Path(self.tmpdir.name) / "user-mode.txt"

    def tearDown(self) -> None:
        self.tmpdir.cleanup()

    def test_read_default_when_missing(self) -> None:
        self.assertEqual(bot.read_user_mode(self.path), "AUTO")

    def test_read_default_when_invalid(self) -> None:
        self.path.write_text("HELLO\n", encoding="utf-8")
        self.assertEqual(bot.read_user_mode(self.path), "AUTO")

    def test_read_ask_case_insensitive(self) -> None:
        self.path.write_text("ask\n", encoding="utf-8")
        self.assertEqual(bot.read_user_mode(self.path), "ASK")

    def test_read_auto_normalized(self) -> None:
        self.path.write_text(" auto ", encoding="utf-8")
        self.assertEqual(bot.read_user_mode(self.path), "AUTO")

    def test_write_round_trip(self) -> None:
        bot.write_user_mode("ASK", self.path)
        self.assertEqual(bot.read_user_mode(self.path), "ASK")
        bot.write_user_mode("auto", self.path)
        self.assertEqual(bot.read_user_mode(self.path), "AUTO")

    def test_write_invalid_raises(self) -> None:
        with self.assertRaises(ValueError):
            bot.write_user_mode("MAYBE", self.path)


class PinReactionTests(unittest.IsolatedAsyncioTestCase):
    """spec: docs/features/directive-pushpin-registration.md — 📌 reaction → directive 등록."""

    def test_pin_emoji_constants(self) -> None:
        self.assertEqual(bot.PIN_REACTION_EMOJI, "📌")
        self.assertEqual(bot.PIN_REGISTERED_EMOJI, "✅")

    async def test_handle_pin_reaction_skips_when_channel_missing(self) -> None:
        client = mock.MagicMock()
        client.get_channel.return_value = None
        # 부재 channel → graceful return + warning. 예외 raise 금지.
        await bot._handle_pin_reaction(
            client=client,
            channel_id=999,
            message_id="123",
            user_id=42,
        )
        client.get_channel.assert_called_once_with(999)

    # 2026-05-29 폐기: 즉시 등록 검증 2종 — Phase B+C dialogue path 도입 후
    # 매칭 없을 때 = dialogue thread + O/X (사용자 명시 확인). 즉시 등록 path 는
    # empty body + dialogue 시작 실패 fallback 시만. dialogue unit test 별도 작성 권장.

    async def test_handle_pin_reaction_empty_body_uses_placeholder(self) -> None:
        msg = mock.MagicMock()
        msg.content = ""  # 빈 본문 (image-only 메시지 등)
        msg.add_reaction = mock.AsyncMock()

        channel = mock.MagicMock()
        channel.fetch_message = mock.AsyncMock(return_value=msg)

        client = mock.MagicMock()
        client.get_channel.return_value = channel

        fake_result = mock.MagicMock()
        fake_result.returncode = 0

        with mock.patch.object(bot.subprocess, "run", return_value=fake_result) as run, \
             mock.patch.object(bot.Path, "exists", return_value=True):
            await bot._handle_pin_reaction(
                client=client,
                channel_id=1506,
                message_id="9001",
                user_id=42,
            )

        # 빈 본문 → "(빈 본문)" placeholder 로 호출됐는지 확인.
        run.assert_called_once()
        call_args = run.call_args.args[0]
        self.assertEqual(call_args[3], "(빈 본문)")


class DirectiveCompleteOnMergeTests(unittest.TestCase):
    """PR B: directive_complete_on_merge_loop — PR body grep + completed 호출."""

    def test_extract_directive_id_simple(self) -> None:
        body = "Closes directive 1509466456230989926\n\n## Summary\nfoo"
        ids = bot.extract_directive_ids_from_body(body)
        self.assertEqual(ids, ["1509466456230989926"])

    def test_extract_directive_id_colon_format(self) -> None:
        body = "## Foo\ndirective: 1509427220802830336\nbar"
        ids = bot.extract_directive_ids_from_body(body)
        self.assertEqual(ids, ["1509427220802830336"])

    def test_extract_directive_id_multiple(self) -> None:
        body = "directive: 1111111111111\ndirective: 2222222222222\nCloses directive 3333333333333"
        ids = bot.extract_directive_ids_from_body(body)
        self.assertEqual(ids, ["1111111111111", "2222222222222", "3333333333333"])

    def test_extract_directive_id_dedup_preserves_order(self) -> None:
        body = "directive: 1111111111111\nCloses directive 1111111111111"
        ids = bot.extract_directive_ids_from_body(body)
        self.assertEqual(ids, ["1111111111111"])

    def test_extract_directive_id_none_match(self) -> None:
        body = "## Summary\nno directive id here\n"
        ids = bot.extract_directive_ids_from_body(body)
        self.assertEqual(ids, [])

    def test_extract_directive_id_empty_body(self) -> None:
        self.assertEqual(bot.extract_directive_ids_from_body(""), [])
        self.assertEqual(bot.extract_directive_ids_from_body(None), [])  # type: ignore[arg-type]

    def test_extract_directive_id_case_insensitive(self) -> None:
        # #1473: 줄 시작 앵커 도입 — 줄 중간 prose 언급은 의도적으로 미매칭.
        # 줄 시작 토큰만 대소문자 무관하게 추출.
        body = (
            "DIRECTIVE: 1234567890123\n"
            "closes Directive 1234567890124\n"
            "본문 중간 mentions directive 9999999999999 는 미매칭"
        )
        ids = bot.extract_directive_ids_from_body(body)
        self.assertIn("1234567890123", ids)
        self.assertIn("1234567890124", ids)
        self.assertNotIn("9999999999999", ids)

    def test_fetch_recent_merged_prs_graceful_on_gh_failure(self) -> None:
        # subprocess.run mock — rc=1 simulating gh fail.
        fake = mock.MagicMock()
        fake.returncode = 1
        fake.stdout = ""
        fake.stderr = "error"
        result = bot.fetch_recent_merged_prs_with_body(runner=mock.MagicMock(return_value=fake))
        self.assertEqual(result, [])

    def test_fetch_recent_merged_prs_json_parse(self) -> None:
        fake = mock.MagicMock()
        fake.returncode = 0
        fake.stdout = '[{"number": 1234, "url": "https://github.com/x/y/pull/1234", "body": "directive: 999"}]'
        result = bot.fetch_recent_merged_prs_with_body(runner=mock.MagicMock(return_value=fake))
        self.assertEqual(len(result), 1)
        self.assertEqual(result[0]["number"], 1234)
        self.assertIn("directive: 999", result[0]["body"])


class CycleForumThreadCompleteOnMergeTests(unittest.TestCase):
    """PR cf-3: cycle_thread_complete_on_merge_loop — PR body grep + retag."""

    def test_extract_cycle_forum_simple(self) -> None:
        body = "Closes #1234\ncycle-forum: be:1509466456230989926\n"
        refs = bot.extract_cycle_forum_refs_from_body(body)
        self.assertEqual(refs, [("be", "1509466456230989926")])

    def test_extract_cycle_forum_multiple(self) -> None:
        body = (
            "cycle-forum: be:1111111111111111111\n"
            "cycle-forum: fe:2222222222222222222\n"
        )
        refs = bot.extract_cycle_forum_refs_from_body(body)
        self.assertEqual(len(refs), 2)
        self.assertIn(("be", "1111111111111111111"), refs)
        self.assertIn(("fe", "2222222222222222222"), refs)

    def test_extract_cycle_forum_dedup(self) -> None:
        body = (
            "cycle-forum: be:1111111111111111111\n"
            "cycle-forum: be:1111111111111111111\n"
        )
        refs = bot.extract_cycle_forum_refs_from_body(body)
        self.assertEqual(refs, [("be", "1111111111111111111")])

    def test_extract_cycle_forum_case_insensitive(self) -> None:
        body = "CYCLE-FORUM: BE:1234567890123456789"
        refs = bot.extract_cycle_forum_refs_from_body(body)
        self.assertEqual(refs, [("be", "1234567890123456789")])

    def test_extract_cycle_forum_none_match(self) -> None:
        self.assertEqual(bot.extract_cycle_forum_refs_from_body(""), [])
        self.assertEqual(
            bot.extract_cycle_forum_refs_from_body("no cycle forum ref here"),
            [],
        )

    def test_extract_cycle_forum_invalid_cycle_skipped(self) -> None:
        body = "cycle-forum: xx:1234567890123456789"
        self.assertEqual(bot.extract_cycle_forum_refs_from_body(body), [])

    def test_extract_cycle_forum_short_thread_id_skipped(self) -> None:
        body = "cycle-forum: be:1234"
        self.assertEqual(bot.extract_cycle_forum_refs_from_body(body), [])


# 2026-05-29 폐기: ModeToggleContentTests + FindModeToggleMessageTests.
# button UI 폐기 (사용자 정정), `/mb auto` / `/mb ask` / `/mb status` slash command 로 대체.


class DirectiveSummaryParseTests(unittest.TestCase):
    """#1385 — 쓰레드 맥락 요약 출력 파싱 / fallback 제목."""

    def test_parse_title_and_body(self) -> None:
        out = (
            "제목: 브라우저 자동 QA 환경 도입\n"
            "===본문===\n"
            "- **요약**: rev 사이클에 Playwright 도입\n"
            "- **유형**: 신규 기능\n"
            "- **위임 권장**: rev — QA 전담\n"
            "- **상태**: 대기"
        )
        title, body = bot._parse_summary_output(out)
        self.assertEqual(title, "브라우저 자동 QA 환경 도입")
        self.assertTrue(body.startswith("- **요약**"))
        self.assertNotIn("제목:", body)
        self.assertNotIn("===본문===", body)

    def test_parse_title_truncated_to_max(self) -> None:
        long_title = "가" * 80
        title, _ = bot._parse_summary_output(f"제목: {long_title}\n===본문===\n본문")
        self.assertEqual(len(title), bot._DIRECTIVE_TITLE_MAX_LEN)

    def test_parse_no_marker_returns_body_as_is(self) -> None:
        title, body = bot._parse_summary_output("그냥 본문만 있는 경우")
        self.assertEqual(title, "")
        self.assertEqual(body, "그냥 본문만 있는 경우")

    def test_parse_empty(self) -> None:
        self.assertEqual(bot._parse_summary_output(""), ("", ""))
        self.assertEqual(bot._parse_summary_output("   "), ("", ""))

    def test_fallback_title_empty_and_blank_body(self) -> None:
        self.assertEqual(bot._fallback_title(""), "(제목 미정)")
        self.assertEqual(bot._fallback_title("(빈 본문)"), "(제목 미정)")

    def test_fallback_title_collapses_whitespace_and_truncates(self) -> None:
        title = bot._fallback_title("여러   줄\n공백   포함 " + "끝" * 100)
        self.assertLessEqual(len(title), bot._DIRECTIVE_TITLE_MAX_LEN)
        self.assertNotIn("\n", title)

    def test_summary_prompt_uses_thread_context_when_present(self) -> None:
        prompt = bot._summary_prompt(
            "raw", "id-1", thread_context="- 사용자: A\n- 키키(nmae): B",
        )
        self.assertIn("대화 쓰레드 전체", prompt)
        self.assertIn("키키(nmae): B", prompt)
        self.assertIn("제목:", prompt)

    def test_summary_prompt_single_message_when_no_context(self) -> None:
        prompt = bot._summary_prompt("단건 메시지", "id-2")
        self.assertIn("원본 사용자 메시지: 단건 메시지", prompt)


if __name__ == "__main__":
    unittest.main()
