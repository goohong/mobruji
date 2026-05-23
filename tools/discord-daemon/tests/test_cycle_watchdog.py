"""cycle_idle_watch_loop 단위 테스트 (#941).

spec: docs/features/nmae-cycle-watchdog.md.

검증 범위:
1. `resolve_cycle_targets` — CSV / None / 공백 / 중복 (4건).
2. `parse_cycle_status` — 정상 / null 본문 / 파일 부재 / malformed JSON (4건).
3. `detect_idle_worktrees` — 모두 idle / 일부 idle / 모두 active / in_progress dict (4건).
4. `cycle_idle_watch_loop` (asyncio mock) — idle → inject+push / debounce /
   tmux 부재 graceful / parse fail graceful / threshold 0 disabled (5건).

총 17건.
"""

from __future__ import annotations

import asyncio
import json
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest import mock

PARENT_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PARENT_DIR))

# discord — 실제 모듈 우선 (embed 호환), 없으면 stub.
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
# 1) resolve_cycle_targets — 4건
# ─────────────────────────────────────────────────────────────────────────────


class ResolveCycleTargetsTest(unittest.TestCase):

    def test_none_returns_default_four(self) -> None:
        self.assertEqual(
            bot.resolve_cycle_targets(None), ["be", "fe", "rev", "plan"]
        )

    def test_csv_with_whitespace_trimmed(self) -> None:
        self.assertEqual(
            bot.resolve_cycle_targets("be , fe , rev , plan"),
            ["be", "fe", "rev", "plan"],
        )

    def test_empty_tokens_ignored(self) -> None:
        self.assertEqual(
            bot.resolve_cycle_targets("be,,fe,"),
            ["be", "fe"],
        )

    def test_duplicates_removed_preserving_order(self) -> None:
        self.assertEqual(
            bot.resolve_cycle_targets("fe,be,fe,plan"),
            ["fe", "be", "plan"],
        )


# ─────────────────────────────────────────────────────────────────────────────
# 2) parse_cycle_status — 4건
# ─────────────────────────────────────────────────────────────────────────────


class ParseCycleStatusTest(unittest.TestCase):

    def test_valid_json_returns_dict(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            path = Path(tmp_dir) / "cycle.json"
            payload = {"be": {"in_progress": None, "last_completed": None}}
            path.write_text(json.dumps(payload), encoding="utf-8")
            result = bot.parse_cycle_status(str(path))
            self.assertEqual(result, payload)

    def test_explicit_null_returns_none(self) -> None:
        # JSON 본문이 "null" 이면 json.load 가 None 반환 — 호출부에서 status None
        # 으로 받아 graceful skip.
        with tempfile.TemporaryDirectory() as tmp_dir:
            path = Path(tmp_dir) / "cycle.json"
            path.write_text("null", encoding="utf-8")
            self.assertIsNone(bot.parse_cycle_status(str(path)))

    def test_missing_file_returns_none(self) -> None:
        self.assertIsNone(bot.parse_cycle_status("/nonexistent/path.json"))

    def test_malformed_json_returns_none(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            path = Path(tmp_dir) / "cycle.json"
            path.write_text("{not valid json", encoding="utf-8")
            self.assertIsNone(bot.parse_cycle_status(str(path)))


# ─────────────────────────────────────────────────────────────────────────────
# 3) detect_idle_worktrees — 4건
# ─────────────────────────────────────────────────────────────────────────────


class DetectIdleWorktreesTest(unittest.TestCase):

    def setUp(self) -> None:
        self.now = datetime(2026, 5, 24, 12, 0, 0, tzinfo=timezone.utc)

    def _make_entry(self, in_progress, minutes_ago: int | None) -> dict:
        last_completed = None
        if minutes_ago is not None:
            ts = self.now - timedelta(minutes=minutes_ago)
            last_completed = {
                "pr": "#000",
                "title": "test title",
                "completed_at": ts.isoformat().replace("+00:00", "Z"),
            }
        return {"in_progress": in_progress, "last_completed": last_completed}

    def test_all_idle_returns_all_four(self) -> None:
        status = {
            "be": self._make_entry(None, minutes_ago=30),
            "fe": self._make_entry(None, minutes_ago=20),
            "rev": self._make_entry(None, minutes_ago=15),
            "plan": self._make_entry(None, minutes_ago=999),
        }
        idle = bot.detect_idle_worktrees(
            status, threshold_minutes=10, now=self.now
        )
        self.assertEqual(
            sorted(e["workspace"] for e in idle),
            ["be", "fe", "plan", "rev"],
        )

    def test_partial_idle_skips_recent(self) -> None:
        # be idle (30분 전), fe 최근 완료 (5분 전 < threshold 10), rev/plan active.
        status = {
            "be": self._make_entry(None, minutes_ago=30),
            "fe": self._make_entry(None, minutes_ago=5),
            "rev": self._make_entry({"target": "PR #1", "title": "audit"}, None),
            "plan": self._make_entry("작업 중", None),
        }
        idle = bot.detect_idle_worktrees(
            status, threshold_minutes=10, now=self.now
        )
        self.assertEqual([e["workspace"] for e in idle], ["be"])

    def test_all_active_returns_empty(self) -> None:
        status = {
            "be": self._make_entry({"issue": "#1", "title": "feat"}, None),
            "fe": self._make_entry({"issue": "#2", "title": "fix"}, None),
            "rev": self._make_entry({"target": "PR #3", "title": "audit"}, None),
            "plan": self._make_entry({"issue": "#4", "title": "docs"}, None),
        }
        idle = bot.detect_idle_worktrees(
            status, threshold_minutes=10, now=self.now
        )
        self.assertEqual(idle, [])

    def test_in_progress_dict_active_recognized(self) -> None:
        # in_progress dict 인 동안엔 last_completed.completed_at 시각과 무관하게
        # active 로 인정 (현행 nmae 스키마).
        status = {
            "be": self._make_entry({"issue": "#1", "title": "feat"}, minutes_ago=999),
            "fe": self._make_entry(None, minutes_ago=999),
        }
        idle = bot.detect_idle_worktrees(
            status,
            threshold_minutes=10,
            now=self.now,
            workspaces=["be", "fe"],
        )
        self.assertEqual([e["workspace"] for e in idle], ["fe"])

    def test_future_completed_at_treated_as_idle(self) -> None:
        # #969 root cause — completed_at 이 now 보다 미래 (clock skew / KST 시각을
        # Z suffix 로 잘못 적은 경우) 면 elapsed 가 음수라 silently pass 됐다.
        # 보수적으로 idle 로 간주해 watchdog 침묵 회피.
        future_ts = (self.now + timedelta(hours=9)).isoformat().replace(
            "+00:00", "Z"
        )
        status = {
            "be": {
                "in_progress": None,
                "last_completed": {
                    "pr": "#000",
                    "title": "future skew",
                    "completed_at": future_ts,
                },
            },
        }
        idle = bot.detect_idle_worktrees(
            status,
            threshold_minutes=10,
            now=self.now,
            workspaces=["be"],
        )
        self.assertEqual([e["workspace"] for e in idle], ["be"])
        self.assertEqual(idle[0]["last_completed_title"], "future skew")


# ─────────────────────────────────────────────────────────────────────────────
# 4) cycle_idle_watch_loop — 5건 (asyncio mock)
# ─────────────────────────────────────────────────────────────────────────────


class FakeChannel:
    def __init__(self) -> None:
        self.sent: list[str] = []

    async def send(self, content=None, embed=None) -> None:
        if content is not None:
            self.sent.append(content)


class FakeClient:
    def __init__(self, channel: FakeChannel | None) -> None:
        self._channel = channel

    def get_channel(self, channel_id: int):
        return self._channel


async def _run_loop_iters(loop_coro, iterations: int) -> None:
    """loop coroutine 을 task 로 띄우고 iterations 만큼 양보 후 cancel."""
    task = asyncio.create_task(loop_coro)
    for _ in range(iterations):
        await asyncio.sleep(0)
    task.cancel()
    try:
        await task
    except asyncio.CancelledError:
        pass


class CycleIdleWatchLoopTest(unittest.IsolatedAsyncioTestCase):

    def _write_status(self, dir_path: Path, payload: dict) -> Path:
        path = dir_path / "cycle.json"
        path.write_text(json.dumps(payload), encoding="utf-8")
        return path

    async def test_idle_triggers_tmux_inject_and_discord_push(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            now = datetime(2026, 5, 24, 12, 0, 0, tzinfo=timezone.utc)
            old_ts = (now - timedelta(minutes=30)).isoformat().replace(
                "+00:00", "Z"
            )
            status_payload = {
                "be": {
                    "in_progress": None,
                    "last_completed": {
                        "pr": "#100",
                        "title": "old",
                        "completed_at": old_ts,
                    },
                },
                "fe": {
                    "in_progress": {"issue": "#1", "title": "active"},
                    "last_completed": None,
                },
                "rev": {
                    "in_progress": {"target": "X", "title": "y"},
                    "last_completed": None,
                },
                "plan": {
                    "in_progress": {"issue": "#2", "title": "doc"},
                    "last_completed": None,
                },
            }
            status_path = self._write_status(Path(tmp_dir), status_payload)
            channel = FakeChannel()
            client = FakeClient(channel)

            with mock.patch.object(bot, "tmux_has_session", return_value=True), \
                 mock.patch.object(bot, "tmux_inject_text", return_value=True) as inject:
                coro = bot.cycle_idle_watch_loop(
                    client,
                    notify_channel_id=222,
                    cycle_status_path=str(status_path),
                    inject_target="mobruji:0.0",
                    threshold_minutes=10,
                    poll_interval=0,
                    workspaces=["be", "fe", "rev", "plan"],
                    debounce_seconds=900,
                    now_provider=lambda: now,
                )
                await _run_loop_iters(coro, iterations=5)

            self.assertGreaterEqual(inject.call_count, 1)
            inject_args = inject.call_args_list[0].args
            self.assertEqual(inject_args[0], "mobruji:0.0")
            self.assertIn("watchdog", inject_args[1])
            self.assertIn("be", inject_args[1])
            self.assertTrue(any("nmae watchdog" in m for m in channel.sent))
            self.assertTrue(any("be" in m for m in channel.sent))

    async def test_debounce_prevents_repeat_alert(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            now = datetime(2026, 5, 24, 12, 0, 0, tzinfo=timezone.utc)
            old_ts = (now - timedelta(minutes=30)).isoformat().replace(
                "+00:00", "Z"
            )
            status_payload = {
                "be": {
                    "in_progress": None,
                    "last_completed": {
                        "pr": "#100",
                        "title": "old",
                        "completed_at": old_ts,
                    },
                },
                "fe": {
                    "in_progress": {"issue": "#1", "title": "active"},
                    "last_completed": None,
                },
                "rev": {
                    "in_progress": {"target": "X", "title": "y"},
                    "last_completed": None,
                },
                "plan": {
                    "in_progress": {"issue": "#2", "title": "doc"},
                    "last_completed": None,
                },
            }
            status_path = self._write_status(Path(tmp_dir), status_payload)
            channel = FakeChannel()
            client = FakeClient(channel)
            # monotonic 시각이 진행 안 함 → debounce 영구 활성.
            fake_mono = [1000.0]

            with mock.patch.object(bot, "tmux_has_session", return_value=True), \
                 mock.patch.object(bot, "tmux_inject_text", return_value=True) as inject:
                coro = bot.cycle_idle_watch_loop(
                    client,
                    notify_channel_id=222,
                    cycle_status_path=str(status_path),
                    inject_target="mobruji:0.0",
                    threshold_minutes=10,
                    poll_interval=0,
                    workspaces=["be", "fe", "rev", "plan"],
                    debounce_seconds=900,
                    time_source=lambda: fake_mono[0],
                    now_provider=lambda: now,
                )
                await _run_loop_iters(coro, iterations=15)

            # 여러 iter 돌았어도 inject 는 정확히 1번 (debounce 활성).
            self.assertEqual(inject.call_count, 1)

    async def test_tmux_session_absent_graceful_skip(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            now = datetime(2026, 5, 24, 12, 0, 0, tzinfo=timezone.utc)
            old_ts = (now - timedelta(minutes=30)).isoformat().replace(
                "+00:00", "Z"
            )
            status_payload = {
                "be": {
                    "in_progress": None,
                    "last_completed": {
                        "pr": "#100",
                        "title": "old",
                        "completed_at": old_ts,
                    },
                },
                "fe": {"in_progress": {"issue": "#1", "title": "x"}, "last_completed": None},
                "rev": {"in_progress": {"target": "X", "title": "y"}, "last_completed": None},
                "plan": {"in_progress": {"issue": "#2", "title": "d"}, "last_completed": None},
            }
            status_path = self._write_status(Path(tmp_dir), status_payload)
            channel = FakeChannel()
            client = FakeClient(channel)

            with mock.patch.object(bot, "tmux_has_session", return_value=False), \
                 mock.patch.object(bot, "tmux_inject_text", return_value=True) as inject:
                coro = bot.cycle_idle_watch_loop(
                    client,
                    notify_channel_id=222,
                    cycle_status_path=str(status_path),
                    inject_target="mobruji:0.0",
                    threshold_minutes=10,
                    poll_interval=0,
                    workspaces=["be", "fe", "rev", "plan"],
                    debounce_seconds=900,
                    now_provider=lambda: now,
                )
                await _run_loop_iters(coro, iterations=5)

            # tmux session 부재 → inject 호출 0, Discord push 0.
            inject.assert_not_called()
            self.assertEqual(channel.sent, [])

    async def test_parse_failure_graceful_skip(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            broken = Path(tmp_dir) / "cycle.json"
            broken.write_text("{broken json", encoding="utf-8")
            channel = FakeChannel()
            client = FakeClient(channel)

            with mock.patch.object(bot, "tmux_has_session", return_value=True), \
                 mock.patch.object(bot, "tmux_inject_text", return_value=True) as inject:
                coro = bot.cycle_idle_watch_loop(
                    client,
                    notify_channel_id=222,
                    cycle_status_path=str(broken),
                    inject_target="mobruji:0.0",
                    threshold_minutes=10,
                    poll_interval=0,
                    workspaces=["be", "fe", "rev", "plan"],
                    debounce_seconds=900,
                    now_provider=lambda: datetime(2026, 5, 24, tzinfo=timezone.utc),
                )
                await _run_loop_iters(coro, iterations=5)

            # parse fail → 조용히 skip (raise 없음, inject/push 없음).
            inject.assert_not_called()
            self.assertEqual(channel.sent, [])

    async def test_threshold_zero_disables_loop(self) -> None:
        """threshold_minutes <= 0 → 즉시 return (테스트 용 disable 경로)."""
        channel = FakeChannel()
        client = FakeClient(channel)

        with mock.patch.object(bot, "tmux_has_session", return_value=True), \
             mock.patch.object(bot, "tmux_inject_text", return_value=True) as inject:
            # 짧게 await — return 즉시 끝나야 함.
            await asyncio.wait_for(
                bot.cycle_idle_watch_loop(
                    client,
                    notify_channel_id=222,
                    cycle_status_path="/tmp/none.json",
                    inject_target="mobruji:0.0",
                    threshold_minutes=0,
                    poll_interval=0,
                    workspaces=["be", "fe", "rev", "plan"],
                ),
                timeout=1.0,
            )

        inject.assert_not_called()
        self.assertEqual(channel.sent, [])


# ─────────────────────────────────────────────────────────────────────────────
# 5) reason note 검사 — 3건 (#956)
# ─────────────────────────────────────────────────────────────────────────────


class DetectIdleNoteFieldTest(unittest.TestCase):
    """detect_idle_worktrees 가 note / idle_since 를 idle dict 에 전파하는지."""

    def setUp(self) -> None:
        self.now = datetime(2026, 5, 24, 12, 0, 0, tzinfo=timezone.utc)

    def _entry(self, *, note=None, idle_since=None) -> dict:
        old_ts = (self.now - timedelta(minutes=30)).isoformat().replace(
            "+00:00", "Z"
        )
        result = {
            "in_progress": None,
            "last_completed": {
                "pr": "#1",
                "title": "old",
                "completed_at": old_ts,
            },
        }
        if note is not None:
            result["note"] = note
        if idle_since is not None:
            result["idle_since"] = idle_since
        return result

    def test_note_present_propagated(self) -> None:
        status = {
            "be": self._entry(
                note="다음 launch 후보: PR #857", idle_since="2026-05-24T02:14:00Z"
            )
        }
        idle = bot.detect_idle_worktrees(
            status, threshold_minutes=10, now=self.now, workspaces=["be"]
        )
        self.assertEqual(len(idle), 1)
        self.assertEqual(idle[0]["note"], "다음 launch 후보: PR #857")
        self.assertEqual(idle[0]["idle_since"], "2026-05-24T02:14:00Z")

    def test_note_absent_is_none(self) -> None:
        status = {"be": self._entry()}
        idle = bot.detect_idle_worktrees(
            status, threshold_minutes=10, now=self.now, workspaces=["be"]
        )
        self.assertEqual(len(idle), 1)
        self.assertIsNone(idle[0]["note"])
        self.assertIsNone(idle[0]["idle_since"])

    def test_note_empty_string_is_none(self) -> None:
        # 빈 string / whitespace → None 으로 정규화 (strict 처리 대상).
        status = {"be": self._entry(note="   ")}
        idle = bot.detect_idle_worktrees(
            status, threshold_minutes=10, now=self.now, workspaces=["be"]
        )
        self.assertEqual(len(idle), 1)
        self.assertIsNone(idle[0]["note"])


# ─────────────────────────────────────────────────────────────────────────────
# 6) STRICT relaunch trigger — 2건 (#956)
# ─────────────────────────────────────────────────────────────────────────────


class CycleStrictRelaunchTest(unittest.IsolatedAsyncioTestCase):

    def _write_status(self, dir_path: Path, payload: dict) -> Path:
        path = dir_path / "cycle.json"
        path.write_text(json.dumps(payload), encoding="utf-8")
        return path

    async def test_strict_template_when_note_absent(self) -> None:
        """note 없으면 STRICT template 사용 (note 기록 의무 문구 포함)."""
        with tempfile.TemporaryDirectory() as tmp_dir:
            now = datetime(2026, 5, 24, 12, 0, 0, tzinfo=timezone.utc)
            old_ts = (now - timedelta(minutes=30)).isoformat().replace(
                "+00:00", "Z"
            )
            status_payload = {
                "be": {
                    "in_progress": None,
                    "last_completed": {
                        "pr": "#100",
                        "title": "old",
                        "completed_at": old_ts,
                    },
                    # note 없음 → STRICT 대상.
                },
                "fe": {"in_progress": {"issue": "#1", "title": "x"}, "last_completed": None},
                "rev": {"in_progress": {"target": "X", "title": "y"}, "last_completed": None},
                "plan": {"in_progress": {"issue": "#2", "title": "d"}, "last_completed": None},
            }
            status_path = self._write_status(Path(tmp_dir), status_payload)
            channel = FakeChannel()
            client = FakeClient(channel)

            with mock.patch.object(bot, "tmux_has_session", return_value=True), \
                 mock.patch.object(bot, "tmux_inject_text", return_value=True) as inject:
                coro = bot.cycle_idle_watch_loop(
                    client,
                    notify_channel_id=222,
                    cycle_status_path=str(status_path),
                    inject_target="mobruji:0.0",
                    threshold_minutes=10,
                    poll_interval=0,
                    workspaces=["be", "fe", "rev", "plan"],
                    debounce_seconds=900,
                    reason_required=True,
                    now_provider=lambda: now,
                )
                await _run_loop_iters(coro, iterations=5)

            self.assertGreaterEqual(inject.call_count, 1)
            inject_text = inject.call_args_list[0].args[1]
            self.assertIn("STRICT", inject_text)
            self.assertIn("be", inject_text)
            self.assertIn("note", inject_text)

    async def test_soft_template_when_note_present(self) -> None:
        """note 명시되어 있으면 일반 (soft) template 사용 — STRICT 문구 없음."""
        with tempfile.TemporaryDirectory() as tmp_dir:
            now = datetime(2026, 5, 24, 12, 0, 0, tzinfo=timezone.utc)
            old_ts = (now - timedelta(minutes=30)).isoformat().replace(
                "+00:00", "Z"
            )
            status_payload = {
                "be": {
                    "in_progress": None,
                    "last_completed": {
                        "pr": "#100",
                        "title": "old",
                        "completed_at": old_ts,
                    },
                    "note": "다음 launch 후보: PR #857 audit",
                },
                "fe": {"in_progress": {"issue": "#1", "title": "x"}, "last_completed": None},
                "rev": {"in_progress": {"target": "X", "title": "y"}, "last_completed": None},
                "plan": {"in_progress": {"issue": "#2", "title": "d"}, "last_completed": None},
            }
            status_path = self._write_status(Path(tmp_dir), status_payload)
            channel = FakeChannel()
            client = FakeClient(channel)

            with mock.patch.object(bot, "tmux_has_session", return_value=True), \
                 mock.patch.object(bot, "tmux_inject_text", return_value=True) as inject:
                coro = bot.cycle_idle_watch_loop(
                    client,
                    notify_channel_id=222,
                    cycle_status_path=str(status_path),
                    inject_target="mobruji:0.0",
                    threshold_minutes=10,
                    poll_interval=0,
                    workspaces=["be", "fe", "rev", "plan"],
                    debounce_seconds=900,
                    reason_required=True,
                    now_provider=lambda: now,
                )
                await _run_loop_iters(coro, iterations=5)

            self.assertGreaterEqual(inject.call_count, 1)
            inject_text = inject.call_args_list[0].args[1]
            # 일반 template — STRICT 문구 없어야 함.
            self.assertNotIn("STRICT", inject_text)
            self.assertIn("watchdog", inject_text)


# ─────────────────────────────────────────────────────────────────────────────
# 7) Discord push reason 표시 — 2건 (#956)
# ─────────────────────────────────────────────────────────────────────────────


class CycleDiscordReasonPushTest(unittest.IsolatedAsyncioTestCase):

    def _write_status(self, dir_path: Path, payload: dict) -> Path:
        path = dir_path / "cycle.json"
        path.write_text(json.dumps(payload), encoding="utf-8")
        return path

    async def test_discord_push_includes_reason_when_present(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            now = datetime(2026, 5, 24, 12, 0, 0, tzinfo=timezone.utc)
            old_ts = (now - timedelta(minutes=30)).isoformat().replace(
                "+00:00", "Z"
            )
            status_payload = {
                "be": {
                    "in_progress": None,
                    "last_completed": {
                        "pr": "#100",
                        "title": "old",
                        "completed_at": old_ts,
                    },
                    "note": "be cache eviction 완료 후 audit",
                    "idle_since": "2026-05-24T02:17:00Z",
                },
                "fe": {"in_progress": {"issue": "#1", "title": "x"}, "last_completed": None},
                "rev": {"in_progress": {"target": "X", "title": "y"}, "last_completed": None},
                "plan": {"in_progress": {"issue": "#2", "title": "d"}, "last_completed": None},
            }
            status_path = self._write_status(Path(tmp_dir), status_payload)
            channel = FakeChannel()
            client = FakeClient(channel)

            with mock.patch.object(bot, "tmux_has_session", return_value=True), \
                 mock.patch.object(bot, "tmux_inject_text", return_value=True):
                coro = bot.cycle_idle_watch_loop(
                    client,
                    notify_channel_id=222,
                    cycle_status_path=str(status_path),
                    inject_target="mobruji:0.0",
                    threshold_minutes=10,
                    poll_interval=0,
                    workspaces=["be", "fe", "rev", "plan"],
                    debounce_seconds=900,
                    reason_required=True,
                    now_provider=lambda: now,
                )
                await _run_loop_iters(coro, iterations=5)

            self.assertEqual(len(channel.sent), 1)
            discord_text = channel.sent[0]
            self.assertIn("be cache eviction 완료 후 audit", discord_text)
            self.assertIn("2026-05-24T02:17:00Z", discord_text)
            # STRICT 라벨 없어야 함 (note 명시 idle).
            self.assertNotIn("STRICT relaunch", discord_text)

    async def test_discord_push_includes_strict_label_when_note_absent(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            now = datetime(2026, 5, 24, 12, 0, 0, tzinfo=timezone.utc)
            old_ts = (now - timedelta(minutes=30)).isoformat().replace(
                "+00:00", "Z"
            )
            status_payload = {
                "be": {
                    "in_progress": None,
                    "last_completed": {
                        "pr": "#100",
                        "title": "old",
                        "completed_at": old_ts,
                    },
                    # note 없음.
                },
                "fe": {"in_progress": {"issue": "#1", "title": "x"}, "last_completed": None},
                "rev": {"in_progress": {"target": "X", "title": "y"}, "last_completed": None},
                "plan": {"in_progress": {"issue": "#2", "title": "d"}, "last_completed": None},
            }
            status_path = self._write_status(Path(tmp_dir), status_payload)
            channel = FakeChannel()
            client = FakeClient(channel)

            with mock.patch.object(bot, "tmux_has_session", return_value=True), \
                 mock.patch.object(bot, "tmux_inject_text", return_value=True):
                coro = bot.cycle_idle_watch_loop(
                    client,
                    notify_channel_id=222,
                    cycle_status_path=str(status_path),
                    inject_target="mobruji:0.0",
                    threshold_minutes=10,
                    poll_interval=0,
                    workspaces=["be", "fe", "rev", "plan"],
                    debounce_seconds=900,
                    reason_required=True,
                    now_provider=lambda: now,
                )
                await _run_loop_iters(coro, iterations=5)

            self.assertEqual(len(channel.sent), 1)
            discord_text = channel.sent[0]
            self.assertIn("STRICT relaunch (1): be", discord_text)
            self.assertIn("reason 없음", discord_text)


# ─────────────────────────────────────────────────────────────────────────────
# 8) future timestamp ERROR + Discord push — 3건 (#971)
# ─────────────────────────────────────────────────────────────────────────────


class CycleFutureTimestampErrorTest(unittest.TestCase):
    """detect_idle_worktrees 가 future timestamp 발견 시 ERROR log + is_future flag."""

    def setUp(self) -> None:
        self.now = datetime(2026, 5, 24, 12, 0, 0, tzinfo=timezone.utc)

    def _entry_with_future_completed_at(self) -> dict:
        future_ts = (self.now + timedelta(hours=9)).isoformat().replace(
            "+00:00", "Z"
        )
        return {
            "in_progress": None,
            "last_completed": {
                "pr": "#000",
                "title": "kst as z hand-edit",
                "completed_at": future_ts,
            },
        }

    def test_future_completed_at_flags_is_future(self) -> None:
        status = {"be": self._entry_with_future_completed_at()}
        idle = bot.detect_idle_worktrees(
            status, threshold_minutes=10, now=self.now, workspaces=["be"]
        )
        self.assertEqual(len(idle), 1)
        self.assertTrue(idle[0]["is_future"])

    def test_normal_past_completed_at_is_future_false(self) -> None:
        old_ts = (self.now - timedelta(minutes=30)).isoformat().replace(
            "+00:00", "Z"
        )
        status = {
            "be": {
                "in_progress": None,
                "last_completed": {
                    "pr": "#000",
                    "title": "old",
                    "completed_at": old_ts,
                },
            }
        }
        idle = bot.detect_idle_worktrees(
            status, threshold_minutes=10, now=self.now, workspaces=["be"]
        )
        self.assertEqual(len(idle), 1)
        self.assertFalse(idle[0]["is_future"])

    def test_future_completed_at_emits_error_log(self) -> None:
        status = {"be": self._entry_with_future_completed_at()}
        with self.assertLogs("mobruji-discord-daemon", level="ERROR") as cm:
            bot.detect_idle_worktrees(
                status,
                threshold_minutes=10,
                now=self.now,
                workspaces=["be"],
            )
        # ERROR level log 안에 "미래 completed_at 감지" 메시지 + #971 마커.
        joined = "\n".join(cm.output)
        self.assertIn("미래 completed_at", joined)
        self.assertIn("#971", joined)
        self.assertIn("ERROR", joined)


class CycleFutureTimestampDiscordPushTest(unittest.IsolatedAsyncioTestCase):

    def _write_status(self, dir_path: Path, payload: dict) -> Path:
        path = dir_path / "cycle.json"
        path.write_text(json.dumps(payload), encoding="utf-8")
        return path

    async def test_future_timestamp_triggers_discord_error_push(self) -> None:
        """future timestamp 발견 시 별 🚨 ERROR Discord push 발사."""
        with tempfile.TemporaryDirectory() as tmp_dir:
            now = datetime(2026, 5, 24, 12, 0, 0, tzinfo=timezone.utc)
            future_ts = (now + timedelta(hours=9)).isoformat().replace(
                "+00:00", "Z"
            )
            status_payload = {
                "be": {
                    "in_progress": None,
                    "last_completed": {
                        "pr": "#100",
                        "title": "kst as z",
                        "completed_at": future_ts,
                    },
                },
                "fe": {"in_progress": {"issue": "#1", "title": "x"}, "last_completed": None},
                "rev": {"in_progress": {"target": "X", "title": "y"}, "last_completed": None},
                "plan": {"in_progress": {"issue": "#2", "title": "d"}, "last_completed": None},
            }
            status_path = self._write_status(Path(tmp_dir), status_payload)
            channel = FakeChannel()
            client = FakeClient(channel)

            with mock.patch.object(bot, "tmux_has_session", return_value=True), \
                 mock.patch.object(bot, "tmux_inject_text", return_value=True):
                coro = bot.cycle_idle_watch_loop(
                    client,
                    notify_channel_id=222,
                    cycle_status_path=str(status_path),
                    inject_target="mobruji:0.0",
                    threshold_minutes=10,
                    poll_interval=0,
                    workspaces=["be", "fe", "rev", "plan"],
                    debounce_seconds=900,
                    future_ts_debounce_seconds=3600,
                    now_provider=lambda: now,
                )
                await _run_loop_iters(coro, iterations=5)

            # 메시지 중 ERROR push 한 줄 + 일반 idle push 한 줄 (총 2건).
            error_pushes = [m for m in channel.sent if "🚨 ERROR" in m]
            self.assertEqual(len(error_pushes), 1)
            push_text = error_pushes[0]
            self.assertIn("future timestamp", push_text)
            self.assertIn("be", push_text)
            self.assertIn("update.sh", push_text)
            self.assertIn("#971", push_text)

    async def test_future_timestamp_push_debounced(self) -> None:
        """동일 워크트리 1h 내 future ts ERROR push 는 1회만 발사."""
        with tempfile.TemporaryDirectory() as tmp_dir:
            now = datetime(2026, 5, 24, 12, 0, 0, tzinfo=timezone.utc)
            future_ts = (now + timedelta(hours=9)).isoformat().replace(
                "+00:00", "Z"
            )
            status_payload = {
                "be": {
                    "in_progress": None,
                    "last_completed": {
                        "pr": "#100",
                        "title": "future",
                        "completed_at": future_ts,
                    },
                },
                "fe": {"in_progress": {"issue": "#1", "title": "x"}, "last_completed": None},
                "rev": {"in_progress": {"target": "X", "title": "y"}, "last_completed": None},
                "plan": {"in_progress": {"issue": "#2", "title": "d"}, "last_completed": None},
            }
            status_path = self._write_status(Path(tmp_dir), status_payload)
            channel = FakeChannel()
            client = FakeClient(channel)
            # monotonic 시각 진행 안 함 → 첫 push 후 영구 debounce 활성.
            # 초기값이 debounce 한도보다 커야 last_alert_at.get(..., 0.0) 와 비교 시
            # 첫 push 가 통과.
            fake_mono = [10000.0]

            with mock.patch.object(bot, "tmux_has_session", return_value=True), \
                 mock.patch.object(bot, "tmux_inject_text", return_value=True):
                coro = bot.cycle_idle_watch_loop(
                    client,
                    notify_channel_id=222,
                    cycle_status_path=str(status_path),
                    inject_target="mobruji:0.0",
                    threshold_minutes=10,
                    poll_interval=0,
                    workspaces=["be", "fe", "rev", "plan"],
                    debounce_seconds=900,
                    future_ts_debounce_seconds=3600,
                    time_source=lambda: fake_mono[0],
                    now_provider=lambda: now,
                )
                await _run_loop_iters(coro, iterations=15)

            error_pushes = [m for m in channel.sent if "🚨 ERROR" in m]
            # debounce 활성 → 정확히 1회.
            self.assertEqual(len(error_pushes), 1)


if __name__ == "__main__":
    unittest.main()
