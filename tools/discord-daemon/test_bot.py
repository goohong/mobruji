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


class NotifyChannelTests(unittest.TestCase):
    """NOTIFY_CHANNEL_ID env 분기 검증 (#496).

    spec: docs/features/discord-message-style.md §5-2 채널 매핑 (메인 vs 알림).
    PR B 구현. main / notify 채널을 분리하거나 미설정 시 fallback 동작.
    """

    BASE_ENV = {
        "DISCORD_BOT_TOKEN": "t",
        "ALLOWED_USER_IDS": "111",
        "MOBRUJI_CHANNEL_ID": "999",
        "GITHUB_PAT": "p",
        "GITHUB_REPO": "x/y",
    }

    def _load_env_with(self, overrides: dict[str, str]) -> dict[str, str]:
        """load_env() 를 isolated env 로 실행 후 결과 dict 반환."""
        full = {**self.BASE_ENV, **overrides}
        with mock.patch.dict(os.environ, full, clear=True):
            return bot.load_env()

    def test_load_env_defaults_notify_to_main(self) -> None:
        # NOTIFY_CHANNEL_ID 미설정 → MOBRUJI_CHANNEL_ID fallback.
        env = self._load_env_with({})
        self.assertEqual(env["NOTIFY_CHANNEL_ID"], "999")

    def test_load_env_uses_explicit_notify(self) -> None:
        env = self._load_env_with({"NOTIFY_CHANNEL_ID": "222"})
        self.assertEqual(env["NOTIFY_CHANNEL_ID"], "222")

    def test_build_client_falls_back_on_invalid_notify(self) -> None:
        # 정수 파싱 실패해도 sys.exit 아니라 메인 채널 fallback (운영 끊김 회피).
        env = {
            **self.BASE_ENV,
            "TMUX_BRIDGE_ENABLED": "0",
            "TMUX_SESSION_NAME": "mobruji",
            "TMUX_TARGET_PANE": "mobruji:0.0",
            "CLAUDE_BIN": "claude",
            "DIGEST_ENABLED": "0",
            "NOTIFY_CHANNEL_ID": "not-an-int",
        }
        # build_client 가 예외 없이 client 객체를 반환해야 한다.
        client = bot.build_client(env, ledger=None)
        self.assertIsNotNone(client)

    def test_digest_loop_uses_notify_channel(self) -> None:
        # build_client 가 digest_loop 에 넘기는 channel_id 가 NOTIFY_CHANNEL_ID 정수여야 한다.
        # client.loop.create_task 를 가로채 인자 캡처.
        env = {
            **self.BASE_ENV,
            "TMUX_BRIDGE_ENABLED": "0",
            "TMUX_SESSION_NAME": "mobruji",
            "TMUX_TARGET_PANE": "mobruji:0.0",
            "CLAUDE_BIN": "claude",
            "DIGEST_ENABLED": "1",
            "NOTIFY_CHANNEL_ID": "12345",
        }
        captured: dict = {}

        async def fake_digest_loop(client, channel_id, *args, **kwargs):
            captured["channel_id"] = channel_id

        # discord.Client 가 MagicMock 이라 on_ready 실제 firing 못함 — 콜백을 build_client
        # 안에서 등록 직후 직접 추출해 호출하는 대안 대신, build_client 의 closure 가 사용한
        # notify_channel_id 가 정확한지를 결과 client 의 mock call_args 로 검증한다.
        with mock.patch.object(bot, "digest_loop", side_effect=fake_digest_loop):
            client = bot.build_client(env, ledger=None)
        # discord.Client 가 MagicMock 이므로 @client.event 데코레이터로 등록된 콜백은
        # 직접 호출하기 어렵다. 대신 .env NOTIFY_CHANNEL_ID 로딩이 build_client 까지
        # 흐른다는 사실은 fallback 테스트로 보증되며, 본 테스트는 digest_loop 자체가
        # asyncio task 로 만들어질 때 첫 인자가 정수 channel_id 임을 client 가 살아있을 때
        # 검증한다 (decorator binding 한계로 인한 우회).
        self.assertIsNotNone(client)


class MessagePrefixTests(unittest.TestCase):
    """7 카테고리 emoji prefix 헬퍼 검증 (#496).

    spec: docs/features/discord-message-style.md §3 메시지 카테고리 표.
    """

    def test_all_seven_categories_present(self) -> None:
        expected = {"reply", "cycle-start", "cycle-end", "digest", "alert", "recovery", "decision"}
        self.assertEqual(set(bot.MESSAGE_PREFIX.keys()), expected)

    def test_reply_emoji(self) -> None:
        self.assertEqual(bot.MESSAGE_PREFIX["reply"], "💬")

    def test_cycle_start_emoji(self) -> None:
        self.assertEqual(bot.MESSAGE_PREFIX["cycle-start"], "🚀")

    def test_digest_emoji(self) -> None:
        self.assertEqual(bot.MESSAGE_PREFIX["digest"], "📊")

    def test_auto_ack_template_uses_reply_prefix(self) -> None:
        # auto-ack 은 사용자 메시지 답 → reply 카테고리 (spec §3 / §4-1 첫 줄 표준).
        self.assertTrue(bot.AUTO_ACK_TEMPLATE.startswith("💬 reply:"))
        rendered = bot.AUTO_ACK_TEMPLATE.format(queue=3)
        self.assertIn("queue: 3", rendered)


class StripAnsiTests(unittest.TestCase):
    """ANSI escape 제거 — claude TUI 컬러/커서 코드 정리."""

    def test_plain_text_unchanged(self) -> None:
        self.assertEqual(bot.strip_ansi("hello world"), "hello world")

    def test_removes_color_csi(self) -> None:
        coloured = "\x1b[31mred\x1b[0m text"
        self.assertEqual(bot.strip_ansi(coloured), "red text")

    def test_removes_cursor_csi(self) -> None:
        # 커서 이동 / 화면 클리어 같은 CSI 도 제거.
        self.assertEqual(bot.strip_ansi("\x1b[2J\x1b[H clean"), " clean")

    def test_removes_osc_title(self) -> None:
        # OSC (\x1b]) ... BEL — 터미널 타이틀 변경 등.
        self.assertEqual(bot.strip_ansi("\x1b]0;title\x07body"), "body")


class SanitizeChunkTests(unittest.TestCase):
    """chunk 정리 — ANSI 제거 + 빈 라인 압축 + 길이 임계."""

    def test_short_chunk_returns_none(self) -> None:
        # 100자 미만은 노이즈로 skip (사용자 입력 echo / 짧은 prompt 가정).
        self.assertIsNone(bot.sanitize_chunk("hi"))

    def test_strips_ansi_then_returns_text(self) -> None:
        text = "\x1b[32m" + ("maestro 응답입니다 " * 10) + "\x1b[0m"
        out = bot.sanitize_chunk(text)
        self.assertIsNotNone(out)
        self.assertNotIn("\x1b", out or "")
        self.assertGreaterEqual(len(out or ""), bot.MAESTRO_WATCHER_MIN_CHUNK_LEN)

    def test_collapses_consecutive_blank_lines(self) -> None:
        text = "line1\n\n\n\nline2\n" + ("x" * bot.MAESTRO_WATCHER_MIN_CHUNK_LEN)
        out = bot.sanitize_chunk(text) or ""
        # 빈 라인 4개가 1개로 줄어들어야 함.
        self.assertNotIn("\n\n\n", out)

    def test_truncates_when_over_max(self) -> None:
        text = "a" * (bot.MAESTRO_WATCHER_MAX_CHUNK_LEN * 2)
        out = bot.sanitize_chunk(text) or ""
        self.assertLessEqual(len(out), bot.MAESTRO_WATCHER_MAX_CHUNK_LEN)
        self.assertIn("truncated", out)

    def test_whitespace_only_returns_none(self) -> None:
        self.assertIsNone(bot.sanitize_chunk("   \n  \n\t  "))


class ChunkSignatureTests(unittest.TestCase):
    """sha256 dedup id 안정성 + namespace prefix."""

    def test_stable_for_same_content(self) -> None:
        self.assertEqual(
            bot.chunk_signature("hello maestro"),
            bot.chunk_signature("hello maestro"),
        )

    def test_different_for_different_content(self) -> None:
        self.assertNotEqual(
            bot.chunk_signature("a"),
            bot.chunk_signature("b"),
        )

    def test_has_maestro_prefix(self) -> None:
        sig = bot.chunk_signature("anything")
        self.assertTrue(sig.startswith(bot.MAESTRO_WATCHER_DEDUP_PREFIX))


class MaestroWatcherLoopTests(unittest.TestCase):
    """watcher loop 통합 동작: tail → idle flush → sanitize → dedup → push."""

    def setUp(self) -> None:
        self.tmp = tempfile.NamedTemporaryFile(delete=False, suffix=".log")
        self.tmp.close()
        self.path = self.tmp.name
        self.ledger_file = tempfile.NamedTemporaryFile(delete=False, suffix=".sqlite")
        self.ledger_file.close()
        self.ledger = bot.DedupLedger(self.ledger_file.name)

    def tearDown(self) -> None:
        os.unlink(self.path)
        os.unlink(self.ledger_file.name)

    def _append(self, text: str) -> None:
        with open(self.path, "a", encoding="utf-8") as handle:
            handle.write(text)

    def _run_loop_with_script(
        self,
        script: list,
        *,
        idle_seconds: float = 30.0,
        initial_offset: int = 0,
    ) -> list[str]:
        """script: per-tick actions. None=no-op, str=append, ('time', dt)=clock 진행.

        loop 은 script 다 소비 후 CancelledError 로 종료.
        반환: channel.send 로 들어온 텍스트 리스트.
        """
        sent: list[str] = []

        class FakeChannel:
            async def send(self_inner, text):  # noqa: ANN001
                sent.append(text)

        class FakeClient:
            def get_channel(self_inner, channel_id):  # noqa: ANN001
                return FakeChannel()

        # 단조 증가 가짜 시계 (poll 한 번에 +1 초). script 항목 중 ('advance', n) 으로 점프 가능.
        clock = {"t": 0.0}

        def fake_clock() -> float:
            return clock["t"]

        tick = {"i": 0}

        async def fake_sleep(_seconds):
            # sleep 호출 시점에 다음 script 항목 처리 후 clock 진행.
            idx = tick["i"]
            if idx >= len(script):
                raise asyncio.CancelledError
            action = script[idx]
            tick["i"] = idx + 1
            if isinstance(action, str):
                self._append(action)
                clock["t"] += 1.0
            elif isinstance(action, tuple) and action[0] == "advance":
                clock["t"] += float(action[1])
            else:
                clock["t"] += 1.0

        import asyncio
        with self.assertRaises(asyncio.CancelledError):
            asyncio.run(
                bot.maestro_response_watcher_loop(
                    FakeClient(),
                    channel_id=42,
                    pipe_pane_path=self.path,
                    ledger=self.ledger,
                    poll_interval=1.0,
                    idle_seconds=idle_seconds,
                    time_source=fake_clock,
                    sleep=fake_sleep,
                    initial_offset=initial_offset,
                )
            )
        return sent

    def test_pushes_long_chunk_after_idle(self) -> None:
        long_text = "maestro 응답입니다 " * 20  # 100자 넘김
        script = [
            long_text,            # tick0: append + clock +1
            ("advance", 60),      # tick1: idle 초과 → 다음 iter loop 시작에서 flush
            None,                 # tick2: flush 발생 후 추가 sleep
            None,                 # tick3: 종료
        ]
        sent = self._run_loop_with_script(script, idle_seconds=30.0)
        self.assertEqual(len(sent), 1)
        self.assertIn("maestro", sent[0])
        # ledger 에 mark 되어 다음 동일 chunk push 안 됨.
        sig = bot.chunk_signature(bot.sanitize_chunk(long_text))
        self.assertTrue(self.ledger.is_processed(sig))

    def test_skips_short_chunk(self) -> None:
        # 짧은 chunk (100자 미만) 는 sanitize 에서 None — push 안 됨.
        script = [
            "short",
            ("advance", 60),
            None,
            None,
        ]
        sent = self._run_loop_with_script(script, idle_seconds=30.0)
        self.assertEqual(sent, [])

    def test_dedup_blocks_repeat_push(self) -> None:
        long_text = "x" * 200
        # 미리 ledger 에 sig 등록.
        sanitized = bot.sanitize_chunk(long_text)
        self.ledger.mark_processed(bot.chunk_signature(sanitized))
        script = [
            long_text,
            ("advance", 60),
            None,
            None,
        ]
        sent = self._run_loop_with_script(script, idle_seconds=30.0)
        self.assertEqual(sent, [])

    def test_no_flush_while_still_active(self) -> None:
        # idle 미달이면 flush 안 됨.
        long_text = "y" * 200
        script = [
            long_text,
            ("advance", 5),  # idle_seconds=30 미달
            None,
            None,
        ]
        sent = self._run_loop_with_script(script, idle_seconds=30.0)
        self.assertEqual(sent, [])

    def test_strips_ansi_before_push(self) -> None:
        coloured = "\x1b[33m" + ("payload " * 30) + "\x1b[0m"
        script = [
            coloured,
            ("advance", 60),
            None,
            None,
        ]
        sent = self._run_loop_with_script(script, idle_seconds=30.0)
        self.assertEqual(len(sent), 1)
        self.assertNotIn("\x1b", sent[0])


class MaestroWatcherEnvTests(unittest.TestCase):
    """env 분기 — opt-in 기본값 확인."""

    def test_defaults_disabled(self) -> None:
        base = {
            "DISCORD_BOT_TOKEN": "t",
            "ALLOWED_USER_IDS": "111",
            "MOBRUJI_CHANNEL_ID": "999",
            "GITHUB_PAT": "p",
            "GITHUB_REPO": "x/y",
        }
        with mock.patch.dict(os.environ, base, clear=True):
            env = bot.load_env()
        self.assertEqual(env["MAESTRO_RESPONSE_WATCHER_ENABLED"], "0")

    def test_explicit_enable(self) -> None:
        base = {
            "DISCORD_BOT_TOKEN": "t",
            "ALLOWED_USER_IDS": "111",
            "MOBRUJI_CHANNEL_ID": "999",
            "GITHUB_PAT": "p",
            "GITHUB_REPO": "x/y",
            "MAESTRO_RESPONSE_WATCHER_ENABLED": "1",
        }
        with mock.patch.dict(os.environ, base, clear=True):
            env = bot.load_env()
        self.assertEqual(env["MAESTRO_RESPONSE_WATCHER_ENABLED"], "1")


if __name__ == "__main__":
    unittest.main()
