"""Claude API usage tracker 단위 테스트 (#1020).

검증 범위:
1. ``_format_token_count`` — K/M scale 및 trailing 0 trim (3건).
2. ``compute_pct`` / ``bucket_pct`` — 10% 단위 분류 (4건).
3. ``scan_usage`` — jsonl 합산 정확도 + assistant filter + timestamp 누락 skip (4건).
4. ``read_state`` / ``write_state`` — atomic write + 깨진 파일 graceful (3건).
5. ``update_and_detect_thresholds`` —
   - 첫 10% bucket 진입 event 1건
   - 같은 bucket 두 번째 호출 시 event 0건 (dedup)
   - 자정 reset 시 daily 0 + last_pushed 0 reset
   - 월요일 reset 시 weekly 0 + last_pushed 0 reset
   - daily + weekly 동시 10% 진입 시 events 2건
6. ``claude_usage_watch_loop`` (asyncio mock) —
   - 정상 scan + threshold push 1건
   - 같은 % 두 번째 iter 에서 push skip (dedup)
   - poll_interval=0 → 즉시 return (disabled)
"""

from __future__ import annotations

import asyncio
import json
import sys
import tempfile
import unittest
from datetime import datetime, timedelta
from pathlib import Path
from unittest import mock
from zoneinfo import ZoneInfo

PARENT_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PARENT_DIR))

import claude_usage_tracker as cut  # noqa: E402
import bot as bot_module  # noqa: E402

KST = ZoneInfo("Asia/Seoul")
UTC = ZoneInfo("UTC")


# ─────────────────────────────────────────────────────────────────────────────
# helpers — fixture builders
# ─────────────────────────────────────────────────────────────────────────────


def make_assistant_record(
    *,
    ts: datetime,
    input_tokens: int = 0,
    output_tokens: int = 0,
    cache_creation: int = 0,
    cache_read: int = 0,
) -> str:
    """assistant message record 한 줄 jsonl 직렬화."""
    record = {
        "type": "assistant",
        "timestamp": ts.astimezone(UTC).isoformat().replace("+00:00", "Z"),
        "message": {
            "role": "assistant",
            "usage": {
                "input_tokens": input_tokens,
                "output_tokens": output_tokens,
                "cache_creation_input_tokens": cache_creation,
                "cache_read_input_tokens": cache_read,
            },
        },
    }
    return json.dumps(record)


def make_user_record(*, ts: datetime, content: str = "hi") -> str:
    """user 메시지 — scan 에서 무시되어야 함."""
    record = {
        "type": "user",
        "timestamp": ts.astimezone(UTC).isoformat().replace("+00:00", "Z"),
        "message": {"role": "user", "content": content},
    }
    return json.dumps(record)


def write_jsonl(path: Path, lines: list[str]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as handle:
        for line in lines:
            handle.write(line + "\n")


# ─────────────────────────────────────────────────────────────────────────────
# unit helpers
# ─────────────────────────────────────────────────────────────────────────────


class TestFormatTokenCount(unittest.TestCase):
    def test_under_thousand(self) -> None:
        self.assertEqual(cut._format_token_count(500), "500")

    def test_thousand_scale(self) -> None:
        self.assertEqual(cut._format_token_count(300_000), "300K")

    def test_million_scale_trim(self) -> None:
        self.assertEqual(cut._format_token_count(1_050_000), "1.05M")
        self.assertEqual(cut._format_token_count(1_000_000), "1M")
        self.assertEqual(cut._format_token_count(7_000_000), "7M")


class TestDefaultLimits(unittest.TestCase):
    """Default token limit 회귀 가드 (#1120).

    2026-05-26 사용자 P0 정정 — 기존 default 1M/7M 이 실측 (daily 298M / weekly
    311M) 의 1/1000 수준 → "100% 초과" 노이즈 5분 마다 반복. 1B/7B raise 후 본
    테스트가 회귀 가드 — default 가 다시 1M/7M 로 떨어지면 fail.
    """

    def test_default_daily_limit_at_least_1B(self) -> None:
        self.assertGreaterEqual(cut.DEFAULT_DAILY_LIMIT, 1_000_000_000)

    def test_default_weekly_limit_at_least_7B(self) -> None:
        self.assertGreaterEqual(cut.DEFAULT_WEEKLY_LIMIT, 7_000_000_000)

    def test_default_limits_proportional(self) -> None:
        """주간 한도는 일 한도의 7배 이상이어야 한다 — 1 week = 7 days."""
        self.assertGreaterEqual(
            cut.DEFAULT_WEEKLY_LIMIT, cut.DEFAULT_DAILY_LIMIT * 7
        )


class TestComputePct(unittest.TestCase):
    def test_compute_pct_basic(self) -> None:
        self.assertEqual(cut.compute_pct(300_000, 1_000_000), 30)

    def test_compute_pct_zero_limit(self) -> None:
        self.assertEqual(cut.compute_pct(100, 0), 0)

    def test_bucket_pct_below_threshold(self) -> None:
        self.assertEqual(cut.bucket_pct(5), 0)

    def test_bucket_pct_above(self) -> None:
        self.assertEqual(cut.bucket_pct(34), 30)
        self.assertEqual(cut.bucket_pct(100), 100)


# ─────────────────────────────────────────────────────────────────────────────
# scan_usage
# ─────────────────────────────────────────────────────────────────────────────


class TestScanUsage(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name) / "projects"
        self.root.mkdir(parents=True)
        # now fixed: 2026-05-24 15:00 KST (목요일 — ISO week 2026-W22).
        self.now = datetime(2026, 5, 24, 15, 0, tzinfo=KST)

    def tearDown(self) -> None:
        self.tmp.cleanup()

    def test_sum_assistant_usage(self) -> None:
        ts_today = datetime(2026, 5, 24, 10, 0, tzinfo=KST)
        path = self.root / "proj-a" / "session-1.jsonl"
        write_jsonl(
            path,
            [
                make_assistant_record(
                    ts=ts_today,
                    input_tokens=100,
                    output_tokens=200,
                    cache_creation=50,
                    cache_read=10,
                ),
                make_assistant_record(
                    ts=ts_today,
                    input_tokens=1000,
                    output_tokens=500,
                ),
            ],
        )
        snapshot = cut.scan_usage(
            projects_root=self.root,
            now=self.now,
            daily_limit=1_000_000,
            weekly_limit=7_000_000,
        )
        # 360 + 1500 = 1860
        self.assertEqual(snapshot.daily_tokens, 1860)
        self.assertEqual(snapshot.weekly_tokens, 1860)
        self.assertEqual(snapshot.daily_date, "2026-05-24")
        self.assertEqual(snapshot.weekly_iso_week, "2026-W21")

    def test_skip_user_records(self) -> None:
        ts = datetime(2026, 5, 24, 10, 0, tzinfo=KST)
        path = self.root / "proj-a" / "session.jsonl"
        write_jsonl(
            path,
            [
                make_user_record(ts=ts),
                make_assistant_record(ts=ts, input_tokens=42),
                make_user_record(ts=ts),
            ],
        )
        snapshot = cut.scan_usage(projects_root=self.root, now=self.now)
        self.assertEqual(snapshot.daily_tokens, 42)

    def test_skip_records_without_timestamp(self) -> None:
        # timestamp 누락 record — 합산 제외.
        path = self.root / "proj-a" / "session.jsonl"
        no_ts_record = json.dumps({
            "type": "assistant",
            "message": {"usage": {"input_tokens": 999}},
        })
        valid_ts = datetime(2026, 5, 24, 10, 0, tzinfo=KST)
        write_jsonl(
            path,
            [
                no_ts_record,
                make_assistant_record(ts=valid_ts, input_tokens=10),
            ],
        )
        snapshot = cut.scan_usage(projects_root=self.root, now=self.now)
        self.assertEqual(snapshot.daily_tokens, 10)

    def test_only_within_today_kst(self) -> None:
        # 어제 record — daily 미포함, weekly 포함 (같은 주).
        yesterday = datetime(2026, 5, 23, 10, 0, tzinfo=KST)  # 토요일 같은 주
        today = datetime(2026, 5, 24, 10, 0, tzinfo=KST)
        path = self.root / "proj-a" / "session.jsonl"
        write_jsonl(
            path,
            [
                make_assistant_record(ts=yesterday, input_tokens=500),
                make_assistant_record(ts=today, input_tokens=100),
            ],
        )
        snapshot = cut.scan_usage(projects_root=self.root, now=self.now)
        self.assertEqual(snapshot.daily_tokens, 100)
        # 2026-05-24 일요일 → ISO week 2026-W21. 2026-05-23 토 → 2026-W21.
        # 같은 ISO week 이므로 weekly 600.
        self.assertEqual(snapshot.weekly_tokens, 600)

    def test_invalid_utf8_jsonl_graceful_skip(self) -> None:
        """invalid utf-8 (0xec position 0) jsonl 파일이 섞여도 scan 이 graceful skip (#1068, 이슈 #1065).

        AS-IS: ``open(..., encoding=\"utf-8\")`` 가 raise →
            ``claude_usage_watch_loop iter 실패: 'utf-8' codec can't decode byte 0xec``
        TO-BE: ``errors=\"replace\"`` + 파일 단위 ``UnicodeDecodeError`` 흡수.
        """
        # 유효 record 1건 → 합산 보존.
        valid_ts = datetime(2026, 5, 24, 10, 0, tzinfo=KST)
        valid_path = self.root / "proj-a" / "valid.jsonl"
        write_jsonl(
            valid_path,
            [make_assistant_record(ts=valid_ts, input_tokens=42)],
        )
        # invalid utf-8 byte sequence 파일 — 0xec position 0 (이슈 #1065 재현).
        invalid_path = self.root / "proj-b" / "invalid.jsonl"
        invalid_path.parent.mkdir(parents=True, exist_ok=True)
        invalid_path.write_bytes(b"\xec\xbc\x8c invalid line 1\n")
        # scan_usage 가 invalid file 만나도 raise 없이 valid 합산만 반환.
        snapshot = cut.scan_usage(projects_root=self.root, now=self.now)
        self.assertEqual(snapshot.daily_tokens, 42)

    def test_invalid_utf8_inline_with_valid_records(self) -> None:
        """동일 파일 안에 invalid utf-8 line + valid record 가 섞여도 valid 합산 보존 (#1068).

        errors=\"replace\" 가 적용되어 invalid line 은 replace char 로 들어오고,
        json.loads 가 raise → 해당 line skip. valid record 는 합산.
        """
        ts = datetime(2026, 5, 24, 10, 0, tzinfo=KST)
        mixed_path = self.root / "proj-a" / "mixed.jsonl"
        mixed_path.parent.mkdir(parents=True, exist_ok=True)
        # invalid utf-8 bytes + 정상 record 1건.
        valid_line = make_assistant_record(ts=ts, input_tokens=77).encode(
            "utf-8"
        )
        mixed_path.write_bytes(b"\xec\xbc\x8c invalid\n" + valid_line + b"\n")
        snapshot = cut.scan_usage(projects_root=self.root, now=self.now)
        self.assertEqual(snapshot.daily_tokens, 77)


# ─────────────────────────────────────────────────────────────────────────────
# read_state / write_state
# ─────────────────────────────────────────────────────────────────────────────


class TestStateIO(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.path = Path(self.tmp.name) / "state.json"

    def tearDown(self) -> None:
        self.tmp.cleanup()

    def test_read_missing_returns_empty(self) -> None:
        state = cut.read_state(self.path)
        self.assertEqual(state["daily"]["tokens"], 0)
        self.assertEqual(state["weekly"]["tokens"], 0)
        self.assertEqual(state["last_pushed"]["daily_pct"], 0)

    def test_write_then_read_roundtrip(self) -> None:
        state = cut._empty_state()
        state["daily"]["date"] = "2026-05-24"
        state["daily"]["tokens"] = 1234
        state["last_pushed"]["daily_pct"] = 10
        cut.write_state(state, self.path)
        loaded = cut.read_state(self.path)
        self.assertEqual(loaded["daily"]["tokens"], 1234)
        self.assertEqual(loaded["last_pushed"]["daily_pct"], 10)

    def test_corrupted_file_graceful(self) -> None:
        self.path.write_text("not-json-{{")
        state = cut.read_state(self.path)
        self.assertEqual(state["daily"]["tokens"], 0)


# ─────────────────────────────────────────────────────────────────────────────
# update_and_detect_thresholds
# ─────────────────────────────────────────────────────────────────────────────


class TestUpdateAndDetect(unittest.TestCase):
    def setUp(self) -> None:
        self.daily_limit = 1_000_000
        self.weekly_limit = 7_000_000
        self.state = cut._empty_state(
            daily_limit=self.daily_limit, weekly_limit=self.weekly_limit
        )

    def _snapshot(
        self,
        *,
        daily_date: str = "2026-05-24",
        daily_tokens: int = 0,
        weekly_iso_week: str = "2026-W21",
        weekly_tokens: int = 0,
    ) -> cut.UsageSnapshot:
        return cut.UsageSnapshot(
            daily_date=daily_date,
            daily_tokens=daily_tokens,
            weekly_iso_week=weekly_iso_week,
            weekly_tokens=weekly_tokens,
            daily_pct=cut.compute_pct(daily_tokens, self.daily_limit),
            weekly_pct=cut.compute_pct(weekly_tokens, self.weekly_limit),
        )

    def test_first_threshold_emits_event(self) -> None:
        # daily 30% = 300K / 1M.
        snap = self._snapshot(daily_tokens=300_000)
        new_state, events = cut.update_and_detect_thresholds(self.state, snap)
        self.assertEqual(len(events), 1)
        self.assertEqual(events[0].kind, "daily")
        self.assertEqual(events[0].bucket_pct, 30)
        self.assertEqual(new_state["last_pushed"]["daily_pct"], 30)

    def test_same_bucket_second_call_no_event(self) -> None:
        snap = self._snapshot(daily_tokens=300_000)
        new_state, _ = cut.update_and_detect_thresholds(self.state, snap)
        # second call with same bucket — events 0.
        _, events2 = cut.update_and_detect_thresholds(new_state, snap)
        self.assertEqual(events2, [])

    def test_midnight_reset(self) -> None:
        # 이전 일자 30% 도달 상태.
        self.state["daily"]["date"] = "2026-05-23"
        self.state["daily"]["tokens"] = 300_000
        self.state["last_pushed"]["daily_pct"] = 30
        # 자정 지나 새 일자 + 100K (10% bucket) → event 발사.
        snap = self._snapshot(
            daily_date="2026-05-24", daily_tokens=100_000
        )
        new_state, events = cut.update_and_detect_thresholds(self.state, snap)
        self.assertEqual(new_state["daily"]["date"], "2026-05-24")
        self.assertEqual(new_state["daily"]["tokens"], 100_000)
        # 자정 reset 후 10% bucket 진입 event.
        self.assertEqual(len(events), 1)
        self.assertEqual(events[0].bucket_pct, 10)

    def test_monday_reset(self) -> None:
        self.state["weekly"]["iso_week"] = "2026-W20"
        self.state["weekly"]["tokens"] = 4_000_000
        self.state["last_pushed"]["weekly_pct"] = 50
        snap = self._snapshot(
            weekly_iso_week="2026-W21", weekly_tokens=700_000
        )
        new_state, events = cut.update_and_detect_thresholds(self.state, snap)
        self.assertEqual(new_state["weekly"]["iso_week"], "2026-W21")
        self.assertEqual(new_state["weekly"]["tokens"], 700_000)
        # weekly 10% = 700K / 7M.
        weekly_events = [e for e in events if e.kind == "weekly"]
        self.assertEqual(len(weekly_events), 1)
        self.assertEqual(weekly_events[0].bucket_pct, 10)

    def test_dual_axis_events(self) -> None:
        snap = self._snapshot(
            daily_tokens=300_000, weekly_tokens=1_400_000
        )  # 30%, 20%
        _, events = cut.update_and_detect_thresholds(self.state, snap)
        kinds = sorted(e.kind for e in events)
        self.assertEqual(kinds, ["daily", "weekly"])

    def test_threshold_message_format(self) -> None:
        snap = self._snapshot(
            daily_tokens=300_000, weekly_tokens=1_050_000
        )  # daily 30%, weekly 15%
        _, events = cut.update_and_detect_thresholds(self.state, snap)
        daily_event = next(e for e in events if e.kind == "daily")
        msg = cut.format_threshold_message(
            daily_event,
            daily_limit=self.daily_limit,
            weekly_limit=self.weekly_limit,
        )
        # 사용자 spec 예시 형식:
        # "📊 Claude usage daily 30% 도달 (300K / 1M tokens). 주간 15% (1.05M / 7M)."
        self.assertIn("daily 30%", msg)
        self.assertIn("300K", msg)
        self.assertIn("1M tokens", msg)
        self.assertIn("주간 15%", msg)
        self.assertIn("1.05M", msg)


# ─────────────────────────────────────────────────────────────────────────────
# claude_usage_watch_loop (asyncio mock)
# ─────────────────────────────────────────────────────────────────────────────


class _FakeChannel:
    def __init__(self) -> None:
        self.sent: list[str] = []

    async def send(self, content=None, embed=None):  # noqa: D401
        if content is not None:
            self.sent.append(content)


class _FakeClient:
    def __init__(self, channel: _FakeChannel | None) -> None:
        self._channel = channel

    def get_channel(self, _id: int):  # noqa: D401
        return self._channel


async def _run_loop_once(
    *,
    projects_root: Path,
    state_path: Path,
    channel: _FakeChannel | None,
    fake_now: datetime | None = None,
) -> None:
    """loop 1 iter + cancel."""
    client = _FakeClient(channel)
    # initial_delay=0, poll_interval 충분히 크게 — 1 iter 후 cancel.
    # asyncio.sleep 을 mock 해 시간 단축.
    original_sleep = asyncio.sleep
    sleep_calls: list[float] = []

    async def fake_sleep(seconds: float) -> None:
        sleep_calls.append(seconds)
        # 두 번째 sleep (loop end) 에서 raise → 1 iter 후 종료.
        if len(sleep_calls) >= 2:
            raise asyncio.CancelledError
        # initial_delay sleep — 짧게.
        await original_sleep(0)

    with mock.patch("bot.asyncio.sleep", side_effect=fake_sleep):
        try:
            await bot_module.claude_usage_watch_loop(
                client,
                alert_channel_id=999,
                projects_root=projects_root,
                state_path=state_path,
                daily_limit=1_000_000,
                weekly_limit=7_000_000,
                poll_interval=300,
                initial_delay=0,
            )
        except asyncio.CancelledError:
            pass


class TestClaudeUsageWatchLoop(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name) / "projects"
        self.root.mkdir(parents=True)
        self.state_path = Path(self.tmp.name) / "claude-usage.json"

    def tearDown(self) -> None:
        self.tmp.cleanup()

    def test_loop_pushes_threshold_event(self) -> None:
        # daily 30% 충족 jsonl 작성.
        now = datetime.now(KST)
        path = self.root / "proj" / "s.jsonl"
        # 300K tokens — daily 30%.
        write_jsonl(
            path,
            [make_assistant_record(ts=now, input_tokens=300_000)],
        )
        channel = _FakeChannel()
        asyncio.run(
            _run_loop_once(
                projects_root=self.root,
                state_path=self.state_path,
                channel=channel,
            )
        )
        self.assertTrue(any("daily 30%" in m for m in channel.sent))
        # state 파일 atomic write 후 last_pushed.daily_pct=30 기록 확인.
        state = cut.read_state(self.state_path)
        self.assertEqual(state["last_pushed"]["daily_pct"], 30)

    def test_second_iter_same_bucket_skips_push(self) -> None:
        # state 에 이미 30% pushed 기록.
        state = cut._empty_state()
        state["daily"]["date"] = cut.kst_date_string()
        state["daily"]["tokens"] = 300_000
        state["last_pushed"]["daily_pct"] = 30
        state["weekly"]["iso_week"] = cut.kst_iso_week_string()
        state["weekly"]["tokens"] = 300_000
        cut.write_state(state, self.state_path)
        # 같은 300K jsonl.
        now = datetime.now(KST)
        path = self.root / "proj" / "s.jsonl"
        write_jsonl(
            path,
            [make_assistant_record(ts=now, input_tokens=300_000)],
        )
        channel = _FakeChannel()
        asyncio.run(
            _run_loop_once(
                projects_root=self.root,
                state_path=self.state_path,
                channel=channel,
            )
        )
        # 같은 bucket — push 없음.
        self.assertEqual(channel.sent, [])

    def test_disabled_when_poll_interval_zero(self) -> None:
        # poll_interval=0 → 즉시 return, 아무것도 안 함.
        async def _runner() -> None:
            await bot_module.claude_usage_watch_loop(
                _FakeClient(None),
                alert_channel_id=999,
                projects_root=self.root,
                state_path=self.state_path,
                poll_interval=0,
                initial_delay=0,
            )

        # 1초 안에 끝나야 함.
        asyncio.run(asyncio.wait_for(_runner(), timeout=1.0))


# ─────────────────────────────────────────────────────────────────────────────
# bot.py env routing — ALERT_CHANNEL_ID fallback
# ─────────────────────────────────────────────────────────────────────────────


class TestAlertChannelFallback(unittest.TestCase):
    """`load_env` ALERT_CHANNEL_ID fallback 체인 검증 (#1020).

    체인: ALERT_CHANNEL_ID → DIGEST_CHANNEL_ID → MOBRUJI_CHANNEL_ID.
    """

    def _load(self, env_overrides: dict[str, str]) -> dict[str, str]:
        env_min = {
            "DISCORD_BOT_TOKEN": "x",
            "ALLOWED_USER_IDS": "1",
            "MOBRUJI_CHANNEL_ID": "11111",
        }
        env_min.update(env_overrides)
        with mock.patch.dict("os.environ", env_min, clear=True):
            # load_dotenv 가 .env 를 덮어쓰지 않도록 mock.
            with mock.patch("bot.load_dotenv", lambda *a, **kw: None):
                return bot_module.load_env()

    def test_alert_set_wins(self) -> None:
        env = self._load(
            {"DIGEST_CHANNEL_ID": "22222", "ALERT_CHANNEL_ID": "33333"}
        )
        self.assertEqual(env["ALERT_CHANNEL_ID"], "33333")

    def test_alert_unset_falls_back_to_digest(self) -> None:
        env = self._load({"DIGEST_CHANNEL_ID": "22222"})
        self.assertEqual(env["ALERT_CHANNEL_ID"], "22222")

    def test_alert_empty_falls_back_to_digest(self) -> None:
        env = self._load(
            {"DIGEST_CHANNEL_ID": "22222", "ALERT_CHANNEL_ID": "  "}
        )
        self.assertEqual(env["ALERT_CHANNEL_ID"], "22222")

    def test_both_unset_falls_back_to_mobruji(self) -> None:
        env = self._load({})
        # DIGEST 가 MOBRUJI 로 fallback → ALERT 도 MOBRUJI 로 chain.
        self.assertEqual(env["ALERT_CHANNEL_ID"], "11111")


if __name__ == "__main__":
    unittest.main()
