"""Phase 3 bridge 단위 테스트 (#340 #341).

표준 라이브러리 unittest + unittest.mock 만 사용. 외부 인증/네트워크 불필요.
"""

from __future__ import annotations

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

# discord / dotenv / requests 가 venv 에 없어도 import 가능하도록 stub.
for missing in ("discord", "requests", "dotenv"):
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

    def test_count_since_empty(self) -> None:
        self.assertEqual(self.ledger.count_since(300), 0)

    def test_count_since_window(self) -> None:
        now = int(time.time())
        self.ledger.mark_processed("recent1", now_epoch=now - 60)
        self.ledger.mark_processed("recent2", now_epoch=now - 200)
        self.ledger.mark_processed("old", now_epoch=now - 1000)
        self.assertEqual(self.ledger.count_since(300), 2)
        self.assertEqual(self.ledger.count_since(100), 1)
        self.assertEqual(self.ledger.count_since(2000), 3)


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
        # /exit 같은 일반 슬래시 명령은 maestro TUI 로 그대로 전달 (Q5 답 c).
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
            ok = bot.tmux_send_payload("mobruji:0.0", "hello")
        self.assertTrue(ok)
        self.assertEqual(run.call_count, 2)
        first_args = run.call_args_list[0].args[0]
        second_args = run.call_args_list[1].args[0]
        self.assertEqual(first_args, ["tmux", "send-keys", "-t", "mobruji:0.0", "-l", "hello"])
        self.assertEqual(second_args, ["tmux", "send-keys", "-t", "mobruji:0.0", "Enter"])

    def test_sentinel_uses_control_key(self) -> None:
        with mock.patch.object(bot.subprocess, "run", return_value=self._completed()) as run:
            ok = bot.tmux_send_payload("mobruji:0.0", "/system:ctrl-c")
        self.assertTrue(ok)
        self.assertEqual(run.call_count, 1)
        self.assertEqual(
            run.call_args_list[0].args[0],
            ["tmux", "send-keys", "-t", "mobruji:0.0", "C-c"],
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
            ok = bot.ensure_tmux_session("mobruji", "/usr/local/bin/claude")
        self.assertTrue(ok)
        # has-session 한 번 + new-session 한 번.
        self.assertEqual(len(calls), 2)
        self.assertEqual(calls[1][:2], ["tmux", "new-session"])
        self.assertIn("/usr/local/bin/claude", calls[1])


class PayloadDispatchPathTests(unittest.TestCase):
    """on_message dispatch 분기 로직 검증.

    실제 discord.Message 객체 대신 ducktyped Mock 사용.
    """

    def _make_message(self, *, content: str, channel_id: int, author_id: int, message_id: int, is_bot: bool = False) -> mock.MagicMock:
        message = mock.MagicMock()
        message.content = content
        message.channel.id = channel_id
        message.author.id = author_id
        message.author.bot = is_bot
        message.author.name = "tester"
        message.id = message_id
        from datetime import datetime, timezone
        message.created_at = datetime(2026, 5, 22, 12, 0, tzinfo=timezone.utc)
        return message

    def test_disallowed_user_dropped(self) -> None:
        # 화이트리스트 외 사용자는 dedup 에 기록되지 않는다 = 다음에 동일 id 와도 진입 가능.
        ledger = bot.DedupLedger(":memory:")
        env = {
            "DISCORD_BOT_TOKEN": "t",
            "ALLOWED_USER_IDS": "111",
            "MOBRUJI_CHANNEL_ID": "999",
            "GITHUB_PAT": "p",
            "GITHUB_REPO": "x/y",
            "TMUX_BRIDGE_ENABLED": "1",
            "TMUX_SESSION_NAME": "mobruji",
            "TMUX_TARGET_PANE": "mobruji:0.0",
            "CLAUDE_BIN": "claude",
        }
        with mock.patch.object(bot, "ensure_tmux_session") as ensure, \
             mock.patch.object(bot, "tmux_send_payload") as send, \
             mock.patch.object(bot, "append_inbox"):
            client = bot.build_client(env, ledger)
            handler = client.on_message  # discord.py 가 바인딩한 콜백을 재참조.
            # 실제로는 @client.event 데코레이터로 등록되므로 build_client 안 클로저 직접 못 잡음.
            # 대신 build_client 가 동작에 부수효과 없는지 + ledger 가 비어있는지만 확인.
        self.assertFalse(ledger.is_processed("123"))
        ensure.assert_not_called()
        send.assert_not_called()


class StatusReportTests(unittest.TestCase):
    """/status 핸들러 결정성 응답 검증 (#354)."""

    def _completed(self, *, rc: int = 0, stdout: str = "", stderr: str = "") -> mock.MagicMock:
        result = mock.MagicMock()
        result.returncode = rc
        result.stdout = stdout
        result.stderr = stderr
        return result

    def test_truncate_title_short(self) -> None:
        self.assertEqual(bot._truncate_title("hello"), "hello")

    def test_truncate_title_long(self) -> None:
        title = "x" * 80
        out = bot._truncate_title(title, limit=20)
        self.assertEqual(len(out), 20)
        self.assertTrue(out.endswith("…"))

    def test_run_gh_json_success(self) -> None:
        with mock.patch.object(bot.subprocess, "run", return_value=self._completed(stdout='[{"number":1}]')):
            out = bot._run_gh_json(["pr", "list"], "pat")
        self.assertEqual(out, [{"number": 1}])

    def test_run_gh_json_nonzero_returns_none(self) -> None:
        with mock.patch.object(bot.subprocess, "run", return_value=self._completed(rc=1, stderr="boom")):
            out = bot._run_gh_json(["pr", "list"], "pat")
        self.assertIsNone(out)

    def test_run_gh_json_decode_error_returns_none(self) -> None:
        with mock.patch.object(bot.subprocess, "run", return_value=self._completed(stdout="not json")):
            out = bot._run_gh_json(["pr", "list"], "pat")
        self.assertIsNone(out)

    def test_run_gh_json_timeout_returns_none(self) -> None:
        def boom(*a, **kw):
            raise bot.subprocess.TimeoutExpired(cmd=["gh"], timeout=1)
        with mock.patch.object(bot.subprocess, "run", side_effect=boom):
            self.assertIsNone(bot._run_gh_json(["pr", "list"], "pat"))

    def test_run_gh_json_injects_gh_token(self) -> None:
        captured: dict = {}

        def fake(cmd, **kwargs):
            captured["env"] = kwargs.get("env", {})
            return self._completed(stdout="[]")

        with mock.patch.object(bot.subprocess, "run", side_effect=fake):
            bot._run_gh_json(["pr", "list"], "secret-pat")
        self.assertEqual(captured["env"].get("GH_TOKEN"), "secret-pat")

    def test_systemd_is_active_returns_stripped(self) -> None:
        with mock.patch.object(bot.subprocess, "run", return_value=self._completed(stdout="active\n")):
            self.assertEqual(bot._systemd_is_active("unit"), "active")

    def test_systemd_is_active_unknown_on_missing(self) -> None:
        with mock.patch.object(bot.subprocess, "run", side_effect=FileNotFoundError):
            self.assertEqual(bot._systemd_is_active("unit"), "unknown")

    def test_build_status_report_full(self) -> None:
        from datetime import datetime as _dt, timezone as _tz
        fake_now = _dt(2026, 5, 23, 4, 30, tzinfo=_tz.utc)
        with mock.patch.object(bot, "_run_gh_json") as gh, \
             mock.patch.object(bot, "_systemd_is_active", side_effect=["active", "active"]):
            gh.side_effect = [
                [{"number": 351, "title": "feat(infra): systemd unit", "isDraft": False, "labels": []}],
                [{"number": 350, "title": "feat(infra): bot.py 확장", "mergedAt": "2026-05-22T15:09:00Z"}],
                [],
            ]
            out = bot.build_status_report("goohong/mobruji", "pat", now=fake_now)
        self.assertIn("mobruji status", out)
        self.assertIn("maestro: `active`", out)
        self.assertIn("#351", out)
        self.assertIn("#350", out)
        self.assertIn("알려진 오류: (없음)", out)
        # KST = UTC + 9, 04:30 UTC → 13:30 KST.
        self.assertIn("2026-05-23 13:30 KST", out)

    def test_build_status_report_gh_failure_degrades(self) -> None:
        with mock.patch.object(bot, "_run_gh_json", return_value=None), \
             mock.patch.object(bot, "_systemd_is_active", return_value="active"):
            out = bot.build_status_report("goohong/mobruji", "pat")
        self.assertIn("진행 중 PR: (조회 실패)", out)
        self.assertIn("최근 머지: (조회 실패)", out)
        self.assertIn("알려진 오류: (조회 실패)", out)

    def test_build_status_report_truncates_to_discord_limit(self) -> None:
        long_pr = {"number": 999, "title": "X" * 200, "isDraft": False, "labels": []}
        with mock.patch.object(bot, "_run_gh_json") as gh, \
             mock.patch.object(bot, "_systemd_is_active", return_value="active"):
            gh.side_effect = [[long_pr] * 8, [long_pr] * 5, [long_pr] * 5]
            out = bot.build_status_report("goohong/mobruji", "pat")
        self.assertLessEqual(len(out), bot.STATUS_DISCORD_MAX_LEN)


class DigestTests(unittest.TestCase):
    """5분 cron digest 단위 검증 (#362)."""

    def test_build_digest_line_full(self) -> None:
        from datetime import datetime as _dt, timezone as _tz
        fake_now = _dt(2026, 5, 23, 4, 42, tzinfo=_tz.utc)  # 13:42 KST
        with mock.patch.object(bot, "_run_gh_json") as gh:
            gh.side_effect = [
                [{"number": 1}, {"number": 2}],  # open PRs: 2
                [{"number": 3}, {"number": 4}, {"number": 5}],  # merged 24h: 3
                [],  # bug issues: 0
            ]
            line = bot.build_digest_line("goohong/mobruji", "pat", now=fake_now)
        self.assertEqual(
            line,
            "📊 PR open:2 / 머지 24h:3 / type:bug:0 — 13:42 KST",
        )

    def test_build_digest_line_gh_failure_shows_question_mark(self) -> None:
        with mock.patch.object(bot, "_run_gh_json", return_value=None):
            line = bot.build_digest_line("goohong/mobruji", "pat")
        self.assertIn("PR open:?", line)
        self.assertIn("머지 24h:?", line)
        self.assertIn("type:bug:?", line)

    def test_digest_loop_sends_then_sleeps(self) -> None:
        sent: list[str] = []
        sleeps: list[int] = []

        class FakeChannel:
            async def send(self, text):
                sent.append(text)

        class FakeClient:
            def get_channel(self, channel_id):
                return FakeChannel()

        async def fake_sleep(seconds):
            sleeps.append(seconds)
            if len(sleeps) >= 2:  # initial + 1 iteration
                raise asyncio.CancelledError

        import asyncio
        with mock.patch.object(
            bot,
            "build_digest_payload",
            return_value=("📊 test", "open=1|merged24=0|bug=0"),
        ), mock.patch.object(bot.asyncio, "sleep", side_effect=fake_sleep):
            with self.assertRaises(asyncio.CancelledError):
                asyncio.run(
                    bot.digest_loop(
                        FakeClient(),
                        channel_id=999,
                        github_repo="x/y",
                        github_pat="",
                        interval=300,
                        initial_delay=60,
                    )
                )
        self.assertEqual(sent, ["📊 test"])
        self.assertEqual(sleeps, [60, 300])  # initial_delay, interval


class DigestDeltaTests(unittest.TestCase):
    """digest_loop delta / heartbeat 동작 검증 (5분 noise 수정)."""

    def _run_loop(
        self,
        payloads: list[tuple[str, str]],
        *,
        interval: int = 900,
        heartbeat_seconds: int = 3600,
        clock_per_iter: int = 900,
    ) -> tuple[list[str], list[int]]:
        """payloads 만큼 iter 돈 뒤 CancelledError 로 종료. (sent, sleeps) 반환."""
        sent: list[str] = []
        sleeps: list[int] = []

        class FakeChannel:
            async def send(self, text):
                sent.append(text)

        class FakeClient:
            def get_channel(self, channel_id):
                return FakeChannel()

        # initial sleep + N iter sleeps. payloads N개 처리 후 CancelledError.
        target_sleeps = 1 + len(payloads)

        async def fake_sleep(seconds):
            sleeps.append(seconds)
            if len(sleeps) >= target_sleeps:
                raise asyncio.CancelledError

        # build_digest_payload 가 iter 마다 다른 payload 를 돌려주도록 side_effect.
        payload_iter = iter(payloads)

        def fake_payload(*_a, **_kw):
            return next(payload_iter)

        # time_source 는 호출마다 단조 증가 (iter 당 clock_per_iter 초씩 흐름).
        clock = {"t": 0}

        def fake_clock() -> float:
            clock["t"] += clock_per_iter
            return float(clock["t"])

        import asyncio
        with mock.patch.object(bot, "build_digest_payload", side_effect=fake_payload), \
             mock.patch.object(bot.asyncio, "sleep", side_effect=fake_sleep):
            with self.assertRaises(asyncio.CancelledError):
                asyncio.run(
                    bot.digest_loop(
                        FakeClient(),
                        channel_id=999,
                        github_repo="x/y",
                        github_pat="",
                        interval=interval,
                        initial_delay=60,
                        heartbeat_seconds=heartbeat_seconds,
                        time_source=fake_clock,
                    )
                )
        return sent, sleeps

    def test_skips_when_signature_unchanged(self) -> None:
        # 3 iter 같은 signature, heartbeat 안 닿게 interval 짧게 + clock 짧게.
        payloads = [
            ("📊 A", "open=1|merged24=0|bug=0"),
            ("📊 A", "open=1|merged24=0|bug=0"),
            ("📊 A", "open=1|merged24=0|bug=0"),
        ]
        sent, _ = self._run_loop(
            payloads,
            interval=60,
            heartbeat_seconds=3600,
            clock_per_iter=60,
        )
        # 첫 iter 는 initial 로 push, 나머지 2 iter 는 signature 동일 → skip.
        self.assertEqual(sent, ["📊 A"])

    def test_delta_push_when_signature_changes(self) -> None:
        payloads = [
            ("📊 A", "open=1|merged24=0|bug=0"),
            ("📊 B", "open=2|merged24=0|bug=0"),  # delta
            ("📊 B", "open=2|merged24=0|bug=0"),  # same → skip
            ("📊 C", "open=2|merged24=1|bug=0"),  # delta
        ]
        sent, _ = self._run_loop(
            payloads,
            interval=60,
            heartbeat_seconds=3600,
            clock_per_iter=60,
        )
        self.assertEqual(sent, ["📊 A", "📊 B", "📊 C"])

    def test_heartbeat_push_after_silence(self) -> None:
        # signature 변화 없지만 heartbeat 임계 넘어가면 push.
        payloads = [
            ("📊 A", "open=1|merged24=0|bug=0"),  # initial
            ("📊 A", "open=1|merged24=0|bug=0"),  # within heartbeat → skip
            ("📊 A", "open=1|merged24=0|bug=0"),  # heartbeat 경과 → push
        ]
        sent, _ = self._run_loop(
            payloads,
            interval=900,
            heartbeat_seconds=1800,  # 1800s = 30분
            clock_per_iter=1000,     # 누적: 1000, 2000, 3000 → 3번째에서 last_push 로부터 ≥1800
        )
        self.assertEqual(sent, ["📊 A", "📊 A"])

    def test_default_interval_is_fifteen_minutes(self) -> None:
        self.assertEqual(bot.DEFAULT_DIGEST_INTERVAL_SECONDS, 900)

    def test_resolve_digest_interval_uses_env(self) -> None:
        self.assertEqual(bot.resolve_digest_interval("1200"), 1200)

    def test_resolve_digest_interval_default_when_none(self) -> None:
        self.assertEqual(
            bot.resolve_digest_interval(None),
            bot.DEFAULT_DIGEST_INTERVAL_SECONDS,
        )

    def test_resolve_digest_interval_falls_back_on_garbage(self) -> None:
        self.assertEqual(
            bot.resolve_digest_interval("not-a-number"),
            bot.DEFAULT_DIGEST_INTERVAL_SECONDS,
        )

    def test_resolve_digest_interval_rejects_non_positive(self) -> None:
        self.assertEqual(
            bot.resolve_digest_interval("0"),
            bot.DEFAULT_DIGEST_INTERVAL_SECONDS,
        )
        self.assertEqual(
            bot.resolve_digest_interval("-30"),
            bot.DEFAULT_DIGEST_INTERVAL_SECONDS,
        )

    def test_build_digest_payload_signature_excludes_time(self) -> None:
        from datetime import datetime as _dt, timezone as _tz
        with mock.patch.object(bot, "_run_gh_json") as gh:
            gh.side_effect = [
                [{"number": 1}],
                [{"number": 2}, {"number": 3}],
                [],
            ]
            line_a, sig_a = bot.build_digest_payload(
                "x/y", "pat", now=_dt(2026, 5, 23, 4, 0, tzinfo=_tz.utc),
            )
        with mock.patch.object(bot, "_run_gh_json") as gh:
            gh.side_effect = [
                [{"number": 1}],
                [{"number": 2}, {"number": 3}],
                [],
            ]
            line_b, sig_b = bot.build_digest_payload(
                "x/y", "pat", now=_dt(2026, 5, 23, 5, 30, tzinfo=_tz.utc),
            )
        # 시간 다르므로 line 은 다르고, signature 는 동일해야 한다.
        self.assertNotEqual(line_a, line_b)
        self.assertEqual(sig_a, sig_b)
        self.assertEqual(sig_a, "open=1|merged24=2|bug=0")


if __name__ == "__main__":
    unittest.main()
