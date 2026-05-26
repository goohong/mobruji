"""directive-board auto-PATCH + mismatch detector 단위 테스트 (#P11).

검증 범위:
1. ``parse_directive_line`` — 정상 / 누락 필드 / 깨진 JSON / 빈 줄 (5건).
2. ``read_directive_board`` — 정상 / 부재 파일 / 일부 깨짐 (3건).
3. ``read_state`` / ``write_state`` — 정상 round-trip / 부재 / atomic (3건).
4. ``format_directive_body`` — 모든 필드 / 빈 필드 fallback / 길이 cap (3건).
5. ``sync_once`` —
   - 첫 실행: PATCH 호출 + state 갱신 (1건).
   - 동일 last_updated_kst + ok state: skip (1건).
   - last_updated_kst 변경: PATCH 재호출 (1건).
   - 404 응답 → mismatched 카운트 + state status="mismatch" (1건).
   - mismatch state 였다가 last_updated 변경 시 retry (1건).
   - patch_func 예외 → errors 카운트 (1건).
6. ``directive_board_summary`` — total/ok/mismatch 카운트 (1건).
7. ``directive_board_sync_loop`` (asyncio mock) —
   - 정상 1 iter (1건).
   - poll_interval=0 → 즉시 return (1건).
   - channel_id 빈 문자열 → 즉시 return (1건).
"""

from __future__ import annotations

import asyncio
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

PARENT_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PARENT_DIR))

# discord 는 embed 검증을 위해 반드시 실제 모듈을 사용한다 (test_digest_cycle_status 동일 패턴).
# 진짜 import 시도 후 실패 시에만 MagicMock.
try:
    import discord as _real_discord  # noqa: F401
except ImportError:
    sys.modules["discord"] = mock.MagicMock()

# requests/dotenv 는 단순 stub. (requests 는 directive_board_sync 가 import 하지만
# 실제 호출은 fake patch_func 로 우회.)
for missing in ("dotenv",):
    if missing not in sys.modules:
        stub = mock.MagicMock()
        if missing == "dotenv":
            stub.load_dotenv = lambda *a, **kw: None
        sys.modules[missing] = stub

import directive_board_sync as dbs  # noqa: E402


# ─────────────────────────────────────────────────────────────────────────────
# parse_directive_line
# ─────────────────────────────────────────────────────────────────────────────


class ParseDirectiveLineTest(unittest.TestCase):
    def test_valid_full_record(self) -> None:
        line = json.dumps(
            {
                "ts": "2026-05-24 12:31 KST",
                "summary": "test",
                "status": "✅ 완료",
                "owner": "be",
                "related": "#1031",
                "message_id": "1507983360562303209",
                "last_updated_kst": "2026-05-24 15:00 KST",
            }
        )
        entry = dbs.parse_directive_line(line)
        assert entry is not None
        self.assertEqual(entry.message_id, "1507983360562303209")
        self.assertEqual(entry.summary, "test")
        self.assertEqual(entry.last_updated_kst, "2026-05-24 15:00 KST")

    def test_missing_message_id_returns_none(self) -> None:
        line = json.dumps({"last_updated_kst": "x", "summary": "s"})
        self.assertIsNone(dbs.parse_directive_line(line))

    def test_missing_last_updated_returns_none(self) -> None:
        line = json.dumps({"message_id": "1507983360562303209", "summary": "s"})
        self.assertIsNone(dbs.parse_directive_line(line))

    def test_broken_json_returns_none(self) -> None:
        self.assertIsNone(dbs.parse_directive_line("{not valid"))

    def test_empty_line_returns_none(self) -> None:
        self.assertIsNone(dbs.parse_directive_line(""))
        self.assertIsNone(dbs.parse_directive_line("   "))

    def test_thread_id_parsed_when_present(self) -> None:
        """forum entry — thread_id 필드 파싱 (#1122).

        forum thread starter PATCH route 의 채널 식별자.
        """
        line = json.dumps(
            {
                "message_id": "1507983360562303209",
                "thread_id": "1507983360562303209",
                "last_updated_kst": "2026-05-26 12:00 KST",
                "summary": "forum entry",
            }
        )
        entry = dbs.parse_directive_line(line)
        assert entry is not None
        self.assertEqual(entry.thread_id, "1507983360562303209")

    def test_thread_id_defaults_empty_when_absent(self) -> None:
        """legacy text channel entry — thread_id 누락 시 빈 문자열 default."""
        line = json.dumps(
            {
                "message_id": "M1",
                "last_updated_kst": "T1",
                "summary": "legacy",
            }
        )
        entry = dbs.parse_directive_line(line)
        assert entry is not None
        self.assertEqual(entry.thread_id, "")


# ─────────────────────────────────────────────────────────────────────────────
# read_directive_board
# ─────────────────────────────────────────────────────────────────────────────


class ReadDirectiveBoardTest(unittest.TestCase):
    def test_valid_jsonl(self) -> None:
        line1 = json.dumps(
            {
                "message_id": "1507983360562303209",
                "last_updated_kst": "2026-05-24 15:00 KST",
                "summary": "a",
            }
        )
        line2 = json.dumps(
            {
                "message_id": "1507983369965797448",
                "last_updated_kst": "2026-05-24 15:01 KST",
                "summary": "b",
            }
        )
        with tempfile.NamedTemporaryFile(
            mode="w", suffix=".jsonl", delete=False, encoding="utf-8"
        ) as fp:
            fp.write(line1 + "\n" + line2 + "\n")
            tmp = Path(fp.name)
        try:
            entries = dbs.read_directive_board(tmp)
            self.assertEqual(len(entries), 2)
            self.assertEqual(entries[0].summary, "a")
            self.assertEqual(entries[1].summary, "b")
        finally:
            tmp.unlink(missing_ok=True)

    def test_missing_file_returns_empty(self) -> None:
        self.assertEqual(
            dbs.read_directive_board(Path("/tmp/__never_exists_dbs__.jsonl")), []
        )

    def test_skips_broken_lines(self) -> None:
        good = json.dumps(
            {
                "message_id": "1507983360562303209",
                "last_updated_kst": "x",
            }
        )
        with tempfile.NamedTemporaryFile(
            mode="w", suffix=".jsonl", delete=False, encoding="utf-8"
        ) as fp:
            fp.write("not json\n" + good + "\n\n")
            tmp = Path(fp.name)
        try:
            entries = dbs.read_directive_board(tmp)
            self.assertEqual(len(entries), 1)
            self.assertEqual(entries[0].message_id, "1507983360562303209")
        finally:
            tmp.unlink(missing_ok=True)


# ─────────────────────────────────────────────────────────────────────────────
# read_state / write_state
# ─────────────────────────────────────────────────────────────────────────────


class StateTest(unittest.TestCase):
    def test_round_trip(self) -> None:
        with tempfile.TemporaryDirectory() as tmpd:
            path = Path(tmpd) / "state.json"
            # 신규 schema (#1068): mismatch_count 필드 포함.
            state = {
                "1507983360562303209": {
                    "last_updated_kst": "2026-05-24 15:00 KST",
                    "status": "ok",
                    "mismatch_count": 0,
                }
            }
            dbs.write_state(state, path)
            self.assertTrue(path.exists())
            self.assertEqual(path.stat().st_mode & 0o777, dbs.STATE_FILE_MODE)
            loaded = dbs.read_state(path)
            self.assertEqual(loaded, state)

    def test_backward_compat_missing_mismatch_count(self) -> None:
        """기존 state 파일 (mismatch_count 누락) 도 graceful 로 0 default (#1068)."""
        with tempfile.TemporaryDirectory() as tmpd:
            path = Path(tmpd) / "state.json"
            # 기존 schema (mismatch_count 누락) — backward-compat.
            path.write_text(
                json.dumps(
                    {
                        "entries": {
                            "M1": {
                                "last_updated_kst": "T1",
                                "status": "ok",
                            }
                        }
                    }
                ),
                encoding="utf-8",
            )
            loaded = dbs.read_state(path)
            self.assertEqual(loaded["M1"]["mismatch_count"], 0)
            self.assertEqual(loaded["M1"]["status"], "ok")

    def test_missing_file_returns_empty(self) -> None:
        self.assertEqual(
            dbs.read_state(Path("/tmp/__never_exists_dbs_state__.json")), {}
        )

    def test_broken_json_returns_empty(self) -> None:
        with tempfile.NamedTemporaryFile(
            mode="w", suffix=".json", delete=False, encoding="utf-8"
        ) as fp:
            fp.write("{not valid")
            tmp = Path(fp.name)
        try:
            self.assertEqual(dbs.read_state(tmp), {})
        finally:
            tmp.unlink(missing_ok=True)


# ─────────────────────────────────────────────────────────────────────────────
# format_directive_body
# ─────────────────────────────────────────────────────────────────────────────


class FormatBodyTest(unittest.TestCase):
    def test_full_record(self) -> None:
        entry = dbs.DirectiveEntry(
            message_id="x",
            ts="2026-05-24 12:31 KST",
            summary="test summary",
            status="✅ 완료",
            owner="be sub-agent",
            related="#1031 MERGED",
            last_updated_kst="2026-05-24 15:00 KST",
        )
        body = dbs.format_directive_body(entry)
        self.assertIn("📌 test summary", body)
        self.assertIn("지시  2026-05-24 12:31 KST", body)
        self.assertIn("상태  ✅ 완료", body)
        self.assertIn("담당  be sub-agent", body)
        self.assertIn("관련  #1031 MERGED", body)
        self.assertIn("업데이트 2026-05-24 15:00 KST", body)

    def test_empty_fields_fallback_to_dash(self) -> None:
        entry = dbs.DirectiveEntry(
            message_id="x",
            ts="",
            summary="",
            status="",
            owner="",
            related="",
            last_updated_kst="2026-05-24",
        )
        body = dbs.format_directive_body(entry)
        self.assertIn("📌 -", body)
        self.assertIn("지시  -", body)
        self.assertIn("업데이트 2026-05-24", body)

    def test_length_cap(self) -> None:
        long = "x" * 5000
        entry = dbs.DirectiveEntry(
            message_id="x",
            ts="t",
            summary=long,
            status="s",
            owner="o",
            related="r",
            last_updated_kst="u",
        )
        body = dbs.format_directive_body(entry)
        self.assertLessEqual(len(body), dbs.DIRECTIVE_BODY_MAX_LEN)


# ─────────────────────────────────────────────────────────────────────────────
# sync_once
# ─────────────────────────────────────────────────────────────────────────────


def _write_jsonl(path: Path, entries: list[dict]) -> None:
    path.write_text(
        "\n".join(json.dumps(e) for e in entries) + "\n", encoding="utf-8"
    )


class SyncOnceTest(unittest.TestCase):
    def _setup(self, tmpd: str, entries: list[dict]) -> tuple[Path, Path]:
        jsonl = Path(tmpd) / "directive-board.jsonl"
        state = Path(tmpd) / "state.json"
        _write_jsonl(jsonl, entries)
        return jsonl, state

    def test_first_run_patches_all(self) -> None:
        with tempfile.TemporaryDirectory() as tmpd:
            jsonl, state = self._setup(
                tmpd,
                [
                    {
                        "message_id": "1507983360562303209",
                        "last_updated_kst": "2026-05-24 15:00 KST",
                        "summary": "a",
                    },
                    {
                        "message_id": "1507983369965797448",
                        "last_updated_kst": "2026-05-24 15:01 KST",
                        "summary": "b",
                    },
                ],
            )
            calls: list[tuple[str, str, str]] = []

            def fake_patch(ch, mid, body):
                calls.append((ch, mid, body))
                return dbs.PatchResult(status_code=200, ok=True, mismatch=False)

            result = dbs.sync_once(
                channel_id="1507982903269920788",
                token="x",
                jsonl_path=jsonl,
                state_path=state,
                patch_func=fake_patch,
            )
            self.assertEqual(result.scanned, 2)
            self.assertEqual(result.patched, 2)
            self.assertEqual(result.skipped, 0)
            self.assertEqual(result.mismatched, 0)
            self.assertEqual(result.errors, 0)
            self.assertEqual(len(calls), 2)
            # state 갱신 확인.
            loaded = dbs.read_state(state)
            self.assertEqual(
                loaded["1507983360562303209"]["status"], "ok"
            )

    def test_second_run_skips_unchanged(self) -> None:
        with tempfile.TemporaryDirectory() as tmpd:
            jsonl, state = self._setup(
                tmpd,
                [
                    {
                        "message_id": "M1",
                        "last_updated_kst": "T1",
                        "summary": "a",
                    }
                ],
            )

            def fake_patch(ch, mid, body):
                return dbs.PatchResult(status_code=200, ok=True, mismatch=False)

            # 1st run
            dbs.sync_once(
                channel_id="C",
                token="x",
                jsonl_path=jsonl,
                state_path=state,
                patch_func=fake_patch,
            )
            # 2nd run — same jsonl content → should skip.
            calls: list[str] = []

            def fake_patch2(ch, mid, body):
                calls.append(mid)
                return dbs.PatchResult(status_code=200, ok=True, mismatch=False)

            result = dbs.sync_once(
                channel_id="C",
                token="x",
                jsonl_path=jsonl,
                state_path=state,
                patch_func=fake_patch2,
            )
            self.assertEqual(result.skipped, 1)
            self.assertEqual(result.patched, 0)
            self.assertEqual(calls, [])

    def test_changed_last_updated_triggers_patch(self) -> None:
        with tempfile.TemporaryDirectory() as tmpd:
            jsonl, state = self._setup(
                tmpd,
                [
                    {
                        "message_id": "M1",
                        "last_updated_kst": "T1",
                        "summary": "a",
                    }
                ],
            )

            def patch_ok(ch, mid, body):
                return dbs.PatchResult(status_code=200, ok=True, mismatch=False)

            dbs.sync_once(
                channel_id="C",
                token="x",
                jsonl_path=jsonl,
                state_path=state,
                patch_func=patch_ok,
            )
            # 변경: last_updated_kst T1 → T2.
            _write_jsonl(
                jsonl,
                [
                    {
                        "message_id": "M1",
                        "last_updated_kst": "T2",
                        "summary": "a updated",
                    }
                ],
            )
            calls = []

            def patch_ok2(ch, mid, body):
                calls.append(body)
                return dbs.PatchResult(status_code=200, ok=True, mismatch=False)

            result = dbs.sync_once(
                channel_id="C",
                token="x",
                jsonl_path=jsonl,
                state_path=state,
                patch_func=patch_ok2,
            )
            self.assertEqual(result.patched, 1)
            self.assertEqual(result.skipped, 0)
            self.assertEqual(len(calls), 1)
            self.assertIn("a updated", calls[0])

    def test_404_marks_mismatch(self) -> None:
        with tempfile.TemporaryDirectory() as tmpd:
            jsonl, state = self._setup(
                tmpd,
                [
                    {
                        "message_id": "M1",
                        "last_updated_kst": "T1",
                        "summary": "a",
                    }
                ],
            )

            def patch_404(ch, mid, body):
                return dbs.PatchResult(status_code=404, ok=False, mismatch=True)

            result = dbs.sync_once(
                channel_id="C",
                token="x",
                jsonl_path=jsonl,
                state_path=state,
                patch_func=patch_404,
            )
            self.assertEqual(result.mismatched, 1)
            self.assertEqual(result.patched, 0)
            self.assertEqual(result.mismatched_ids, ["M1"])
            loaded = dbs.read_state(state)
            self.assertEqual(loaded["M1"]["status"], "mismatch")

    def test_mismatch_state_retries(self) -> None:
        with tempfile.TemporaryDirectory() as tmpd:
            jsonl, state = self._setup(
                tmpd,
                [
                    {
                        "message_id": "M1",
                        "last_updated_kst": "T1",
                        "summary": "a",
                    }
                ],
            )

            def patch_404(ch, mid, body):
                return dbs.PatchResult(status_code=404, ok=False, mismatch=True)

            dbs.sync_once(
                channel_id="C",
                token="x",
                jsonl_path=jsonl,
                state_path=state,
                patch_func=patch_404,
            )
            # 동일 last_updated_kst 라도 mismatch 였으면 retry.
            calls = []

            def patch_recovered(ch, mid, body):
                calls.append(mid)
                return dbs.PatchResult(status_code=200, ok=True, mismatch=False)

            result = dbs.sync_once(
                channel_id="C",
                token="x",
                jsonl_path=jsonl,
                state_path=state,
                patch_func=patch_recovered,
            )
            self.assertEqual(result.patched, 1)
            self.assertEqual(calls, ["M1"])
            loaded = dbs.read_state(state)
            self.assertEqual(loaded["M1"]["status"], "ok")

    def test_actor_exception_counts_error(self) -> None:
        with tempfile.TemporaryDirectory() as tmpd:
            jsonl, state = self._setup(
                tmpd,
                [
                    {
                        "message_id": "M1",
                        "last_updated_kst": "T1",
                        "summary": "a",
                    }
                ],
            )

            def patch_boom(ch, mid, body):
                raise RuntimeError("boom")

            result = dbs.sync_once(
                channel_id="C",
                token="x",
                jsonl_path=jsonl,
                state_path=state,
                patch_func=patch_boom,
            )
            self.assertEqual(result.errors, 1)
            self.assertEqual(result.patched, 0)
            # state 미갱신.
            self.assertFalse(state.exists())

    def test_404_repeated_marks_stale_after_threshold(self) -> None:
        """404 가 임계 (3회) 이상 연속이면 status='stale' + 영구 skip (#1068)."""
        with tempfile.TemporaryDirectory() as tmpd:
            jsonl, state = self._setup(
                tmpd,
                [
                    {
                        "message_id": "M1",
                        "last_updated_kst": "T1",
                        "summary": "deleted",
                    }
                ],
            )

            patch_call_count = {"n": 0}

            def patch_404(ch, mid, body):
                patch_call_count["n"] += 1
                return dbs.PatchResult(
                    status_code=404, ok=False, mismatch=True
                )

            # 3회 sync_once 호출 — 매번 같은 jsonl, 404 누적.
            for i in range(dbs.MISMATCH_STALE_THRESHOLD):
                dbs.sync_once(
                    channel_id="C",
                    token="x",
                    jsonl_path=jsonl,
                    state_path=state,
                    patch_func=patch_404,
                    min_interval_seconds=0,
                )
            loaded = dbs.read_state(state)
            self.assertEqual(loaded["M1"]["status"], "stale")
            self.assertEqual(
                loaded["M1"]["mismatch_count"], dbs.MISMATCH_STALE_THRESHOLD
            )

            # 4회째 호출 — PATCH 호출 안 됨 (영구 skip).
            patch_call_count["n"] = 0
            result = dbs.sync_once(
                channel_id="C",
                token="x",
                jsonl_path=jsonl,
                state_path=state,
                patch_func=patch_404,
                min_interval_seconds=0,
            )
            self.assertEqual(patch_call_count["n"], 0)
            self.assertEqual(result.stale, 1)
            self.assertEqual(result.mismatched, 0)

    def test_stale_entry_resets_on_last_updated_change(self) -> None:
        """stale 후 jsonl 의 last_updated_kst 가 변경되면 mismatch_count 리셋 + 재시도 (#1068)."""
        with tempfile.TemporaryDirectory() as tmpd:
            jsonl, state = self._setup(
                tmpd,
                [
                    {
                        "message_id": "M1",
                        "last_updated_kst": "T1",
                        "summary": "a",
                    }
                ],
            )

            def patch_404(ch, mid, body):
                return dbs.PatchResult(
                    status_code=404, ok=False, mismatch=True
                )

            # 3 회 404 → stale.
            for _ in range(dbs.MISMATCH_STALE_THRESHOLD):
                dbs.sync_once(
                    channel_id="C",
                    token="x",
                    jsonl_path=jsonl,
                    state_path=state,
                    patch_func=patch_404,
                    min_interval_seconds=0,
                )
            self.assertEqual(dbs.read_state(state)["M1"]["status"], "stale")

            # jsonl 수정 — last_updated_kst 갱신 (사용자 직접 jsonl PR 또는
            # DIRECTIVE_AUTO_RECREATE 후속 작업으로 message_id 갱신 시뮬레이션).
            _write_jsonl(
                jsonl,
                [
                    {
                        "message_id": "M1",
                        "last_updated_kst": "T2",
                        "summary": "a updated",
                    }
                ],
            )

            calls = []

            def patch_ok(ch, mid, body):
                calls.append(mid)
                return dbs.PatchResult(
                    status_code=200, ok=True, mismatch=False
                )

            result = dbs.sync_once(
                channel_id="C",
                token="x",
                jsonl_path=jsonl,
                state_path=state,
                patch_func=patch_ok,
                min_interval_seconds=0,
            )
            self.assertEqual(result.patched, 1)
            self.assertEqual(calls, ["M1"])
            loaded = dbs.read_state(state)
            self.assertEqual(loaded["M1"]["status"], "ok")
            self.assertEqual(loaded["M1"]["mismatch_count"], 0)

    def test_min_interval_between_patches(self) -> None:
        """sync_once 가 PATCH 호출 사이 minimum delay sleep 호출 (#1068)."""
        with tempfile.TemporaryDirectory() as tmpd:
            jsonl, state = self._setup(
                tmpd,
                [
                    {
                        "message_id": "M1",
                        "last_updated_kst": "T1",
                        "summary": "a",
                    },
                    {
                        "message_id": "M2",
                        "last_updated_kst": "T1",
                        "summary": "b",
                    },
                    {
                        "message_id": "M3",
                        "last_updated_kst": "T1",
                        "summary": "c",
                    },
                ],
            )

            def patch_ok(ch, mid, body):
                return dbs.PatchResult(
                    status_code=200, ok=True, mismatch=False
                )

            sleep_calls: list[float] = []

            def fake_sleep(seconds: float) -> None:
                sleep_calls.append(seconds)

            result = dbs.sync_once(
                channel_id="C",
                token="x",
                jsonl_path=jsonl,
                state_path=state,
                patch_func=patch_ok,
                min_interval_seconds=0.5,
                sleep_func=fake_sleep,
            )
            self.assertEqual(result.patched, 3)
            # PATCH 3건 → sleep 2건 (호출 사이).
            self.assertEqual(len(sleep_calls), 2)
            self.assertEqual(sleep_calls, [0.5, 0.5])

    def test_min_interval_zero_disables_sleep(self) -> None:
        """min_interval_seconds=0 이면 sleep 호출 없음 (테스트 + 후방호환)."""
        with tempfile.TemporaryDirectory() as tmpd:
            jsonl, state = self._setup(
                tmpd,
                [
                    {
                        "message_id": "M1",
                        "last_updated_kst": "T1",
                        "summary": "a",
                    },
                    {
                        "message_id": "M2",
                        "last_updated_kst": "T1",
                        "summary": "b",
                    },
                ],
            )

            def patch_ok(ch, mid, body):
                return dbs.PatchResult(
                    status_code=200, ok=True, mismatch=False
                )

            sleep_calls: list[float] = []

            dbs.sync_once(
                channel_id="C",
                token="x",
                jsonl_path=jsonl,
                state_path=state,
                patch_func=patch_ok,
                min_interval_seconds=0,
                sleep_func=lambda s: sleep_calls.append(s),
            )
            self.assertEqual(sleep_calls, [])

    def test_forum_thread_uses_thread_id_as_route(self) -> None:
        """jsonl entry 에 thread_id 가 있으면 PATCH route 가 thread_id 사용 (#1122).

        Discord forum thread starter PATCH route:
            PATCH /channels/{thread_id}/messages/{thread_id}

        text channel route (board_channel_id) 와 다르며, 기존 sync 가 board
        channel route 로 forum starter 를 PATCH 시도해 404 무한 반복 → 36 entry
        영구 stale 사고 박제.
        """
        with tempfile.TemporaryDirectory() as tmpd:
            jsonl, state = self._setup(
                tmpd,
                [
                    {
                        "message_id": "1507983360562303209",
                        "thread_id": "1507983360562303209",
                        "last_updated_kst": "2026-05-26 12:00 KST",
                        "summary": "forum thread entry",
                    },
                ],
            )

            captured_routes: list[str] = []

            def fake_patch(ch, mid, body):
                captured_routes.append(ch)
                return dbs.PatchResult(
                    status_code=200, ok=True, mismatch=False
                )

            result = dbs.sync_once(
                channel_id="BOARD_CH_777",  # board channel — 사용 안 돼야 함.
                token="x",
                jsonl_path=jsonl,
                state_path=state,
                patch_func=fake_patch,
                min_interval_seconds=0,
            )
            self.assertEqual(result.patched, 1)
            # route 가 board channel 이 아니라 thread_id 여야 한다.
            self.assertEqual(captured_routes, ["1507983360562303209"])
            loaded = dbs.read_state(state)
            self.assertEqual(
                loaded["1507983360562303209"]["route_channel_id"],
                "1507983360562303209",
            )

    def test_legacy_text_entry_uses_board_channel_route(self) -> None:
        """jsonl entry 에 thread_id 가 비어 있으면 board channel route 사용 (legacy)."""
        with tempfile.TemporaryDirectory() as tmpd:
            jsonl, state = self._setup(
                tmpd,
                [
                    {
                        "message_id": "M1",
                        # thread_id 없음 — legacy text channel entry.
                        "last_updated_kst": "T1",
                        "summary": "legacy text",
                    },
                ],
            )

            captured_routes: list[str] = []

            def fake_patch(ch, mid, body):
                captured_routes.append(ch)
                return dbs.PatchResult(
                    status_code=200, ok=True, mismatch=False
                )

            dbs.sync_once(
                channel_id="BOARD_CH_777",
                token="x",
                jsonl_path=jsonl,
                state_path=state,
                patch_func=fake_patch,
                min_interval_seconds=0,
            )
            self.assertEqual(captured_routes, ["BOARD_CH_777"])

    def test_stale_entry_resets_on_route_change(self) -> None:
        """route 가 변경되면 stale → retry 자동 해제 (#1122 사고 회복 path).

        시나리오: directive-board 가 forum channel 로 전환 (#17, 2026-05-24) 된
        후 기존 sync 가 board channel route 로 PATCH 시도 → 모든 entry 404 →
        영구 stale. 본 PR 이 thread_id route 로 자동 전환할 때 stale 도 강제로
        retry 진입 가능해야 함.
        """
        with tempfile.TemporaryDirectory() as tmpd:
            jsonl, state = self._setup(
                tmpd,
                [
                    {
                        "message_id": "M1",
                        # 초기에 thread_id 없는 jsonl — board channel route.
                        "last_updated_kst": "T1",
                        "summary": "a",
                    }
                ],
            )

            def patch_404(ch, mid, body):
                return dbs.PatchResult(
                    status_code=404, ok=False, mismatch=True
                )

            # 1) 3회 sync_once 호출 — 매번 같은 jsonl, 404 누적 → stale.
            for _ in range(dbs.MISMATCH_STALE_THRESHOLD):
                dbs.sync_once(
                    channel_id="BOARD_CH",
                    token="x",
                    jsonl_path=jsonl,
                    state_path=state,
                    patch_func=patch_404,
                    min_interval_seconds=0,
                )
            self.assertEqual(dbs.read_state(state)["M1"]["status"], "stale")

            # 2) jsonl 갱신 — thread_id 추가 (forum 전환 시뮬레이션).
            #    last_updated_kst 는 그대로 (사용자가 jsonl 본문은 안 건드림).
            _write_jsonl(
                jsonl,
                [
                    {
                        "message_id": "M1",
                        "thread_id": "FORUM_THREAD_M1",
                        "last_updated_kst": "T1",
                        "summary": "a",
                    }
                ],
            )

            calls: list[tuple[str, str]] = []

            def patch_ok(ch, mid, body):
                calls.append((ch, mid))
                return dbs.PatchResult(
                    status_code=200, ok=True, mismatch=False
                )

            # 3) route 변경 detect → stale 자동 해제 + 새 route 로 PATCH 호출.
            result = dbs.sync_once(
                channel_id="BOARD_CH",
                token="x",
                jsonl_path=jsonl,
                state_path=state,
                patch_func=patch_ok,
                min_interval_seconds=0,
            )
            self.assertEqual(result.patched, 1)
            self.assertEqual(calls, [("FORUM_THREAD_M1", "M1")])
            loaded = dbs.read_state(state)
            self.assertEqual(loaded["M1"]["status"], "ok")
            self.assertEqual(
                loaded["M1"]["route_channel_id"], "FORUM_THREAD_M1"
            )

    def test_ok_state_skips_when_route_unchanged(self) -> None:
        """state 가 ok + route 동일 + last_updated 동일 → skip (no PATCH)."""
        with tempfile.TemporaryDirectory() as tmpd:
            jsonl, state = self._setup(
                tmpd,
                [
                    {
                        "message_id": "M1",
                        "thread_id": "T1_FORUM",
                        "last_updated_kst": "T1",
                        "summary": "x",
                    }
                ],
            )

            calls: list[str] = []

            def patch_ok(ch, mid, body):
                calls.append(ch)
                return dbs.PatchResult(
                    status_code=200, ok=True, mismatch=False
                )

            # 1st run — patch once.
            dbs.sync_once(
                channel_id="C",
                token="x",
                jsonl_path=jsonl,
                state_path=state,
                patch_func=patch_ok,
                min_interval_seconds=0,
            )
            self.assertEqual(calls, ["T1_FORUM"])
            calls.clear()

            # 2nd run — same jsonl, same route → skip.
            result = dbs.sync_once(
                channel_id="C",
                token="x",
                jsonl_path=jsonl,
                state_path=state,
                patch_func=patch_ok,
                min_interval_seconds=0,
            )
            self.assertEqual(result.skipped, 1)
            self.assertEqual(result.patched, 0)
            self.assertEqual(calls, [])


# ─────────────────────────────────────────────────────────────────────────────
# patch_message 429 retry (requests-level)
# ─────────────────────────────────────────────────────────────────────────────


class FakeResponse:
    """requests.Response stub — patch_message 단위 테스트 용."""

    def __init__(
        self,
        status_code: int,
        body: dict | None = None,
        headers: dict | None = None,
    ) -> None:
        self.status_code = status_code
        self._body = body or {}
        self.headers = headers or {}
        self.text = json.dumps(self._body) if body else ""

    def json(self) -> dict:
        return self._body


class FakeSession:
    """requests.Session stub — patch 시퀀스 응답."""

    def __init__(self, responses: list[FakeResponse]) -> None:
        self._responses = list(responses)
        self.call_count = 0

    def patch(self, url, headers=None, json=None, timeout=None):
        self.call_count += 1
        if not self._responses:
            raise RuntimeError("FakeSession: response queue empty")
        return self._responses.pop(0)


class PatchMessage429RetryTest(unittest.TestCase):
    def test_429_then_200_retry_succeeds(self) -> None:
        """429 1회 후 200 — retry 로 성공 + ok=True (#1068)."""
        session = FakeSession(
            [
                FakeResponse(429, body={"retry_after": 0.01}),
                FakeResponse(200, body={}),
            ]
        )
        with mock.patch.object(dbs.time, "sleep") as fake_sleep:
            result = dbs.patch_message(
                "C", "M1", "body", token="x", session=session
            )
        self.assertTrue(result.ok)
        self.assertFalse(result.rate_limited)
        self.assertEqual(result.status_code, 200)
        self.assertEqual(session.call_count, 2)
        fake_sleep.assert_called()  # retry_after sleep 1회.

    def test_429_retry_exhausted_returns_rate_limited(self) -> None:
        """429 가 max_retries+1 회 연속이면 rate_limited=True (#1068)."""
        session = FakeSession(
            [FakeResponse(429, body={"retry_after": 0.01}) for _ in range(10)]
        )
        with mock.patch.object(dbs.time, "sleep"):
            result = dbs.patch_message(
                "C",
                "M1",
                "body",
                token="x",
                session=session,
                max_retries=2,
            )
        self.assertFalse(result.ok)
        self.assertTrue(result.rate_limited)
        self.assertEqual(result.status_code, 429)
        # max_retries=2 → 총 3회 호출 (초기 1 + retry 2).
        self.assertEqual(session.call_count, 3)

    def test_404_no_retry(self) -> None:
        """404 는 retry 없이 mismatch=True 즉시 반환 (#1068 기존 동작 보존)."""
        session = FakeSession([FakeResponse(404, body={})])
        result = dbs.patch_message(
            "C", "M1", "body", token="x", session=session
        )
        self.assertFalse(result.ok)
        self.assertTrue(result.mismatch)
        self.assertFalse(result.rate_limited)
        self.assertEqual(session.call_count, 1)

    def test_500_no_retry(self) -> None:
        """5xx 는 retry 없이 ok=False 반환 (loop 가 다음 iter 재시도)."""
        session = FakeSession([FakeResponse(500, body={})])
        result = dbs.patch_message(
            "C", "M1", "body", token="x", session=session
        )
        self.assertFalse(result.ok)
        self.assertFalse(result.mismatch)
        self.assertFalse(result.rate_limited)
        self.assertEqual(session.call_count, 1)


# ─────────────────────────────────────────────────────────────────────────────
# directive_board_summary
# ─────────────────────────────────────────────────────────────────────────────


class SummaryTest(unittest.TestCase):
    def test_counts(self) -> None:
        with tempfile.TemporaryDirectory() as tmpd:
            state_path = Path(tmpd) / "state.json"
            state = {
                "M1": {
                    "last_updated_kst": "T1",
                    "status": "ok",
                    "mismatch_count": 0,
                },
                "M2": {
                    "last_updated_kst": "T2",
                    "status": "ok",
                    "mismatch_count": 0,
                },
                "M3": {
                    "last_updated_kst": "T3",
                    "status": "mismatch",
                    "mismatch_count": 1,
                },
            }
            dbs.write_state(state, state_path)
            summary = dbs.directive_board_summary(state_path)
            self.assertEqual(summary["total"], 3)
            self.assertEqual(summary["ok"], 2)
            self.assertEqual(summary["mismatch"], 1)
            self.assertEqual(summary["mismatch_ids"], ["M3"])
            self.assertEqual(summary["stale"], 0)
            self.assertEqual(summary["stale_ids"], [])

    def test_counts_with_stale(self) -> None:
        """stale 카운트가 summary 에 포함되어야 함 (#1068)."""
        with tempfile.TemporaryDirectory() as tmpd:
            state_path = Path(tmpd) / "state.json"
            state = {
                "M1": {
                    "last_updated_kst": "T1",
                    "status": "ok",
                    "mismatch_count": 0,
                },
                "M2": {
                    "last_updated_kst": "T2",
                    "status": "stale",
                    "mismatch_count": 3,
                },
                "M3": {
                    "last_updated_kst": "T3",
                    "status": "stale",
                    "mismatch_count": 5,
                },
            }
            dbs.write_state(state, state_path)
            summary = dbs.directive_board_summary(state_path)
            self.assertEqual(summary["total"], 3)
            self.assertEqual(summary["ok"], 1)
            self.assertEqual(summary["mismatch"], 0)
            self.assertEqual(summary["stale"], 2)
            self.assertEqual(sorted(summary["stale_ids"]), ["M2", "M3"])

    def test_missing_file_returns_zero(self) -> None:
        summary = dbs.directive_board_summary(
            Path("/tmp/__never_exists_dbs_summary__.json")
        )
        self.assertEqual(summary["total"], 0)
        self.assertEqual(summary["ok"], 0)
        self.assertEqual(summary["mismatch"], 0)
        self.assertEqual(summary["mismatch_ids"], [])
        self.assertEqual(summary["stale"], 0)
        self.assertEqual(summary["stale_ids"], [])


# ─────────────────────────────────────────────────────────────────────────────
# directive_board_sync_loop (async)
# ─────────────────────────────────────────────────────────────────────────────


class SyncLoopTest(unittest.TestCase):
    def test_poll_interval_zero_returns_immediately(self) -> None:
        import bot

        async def go():
            await asyncio.wait_for(
                bot.directive_board_sync_loop(
                    client=mock.MagicMock(),
                    channel_id="C",
                    token="x",
                    jsonl_path=Path("/tmp/__nope.jsonl"),
                    state_path=Path("/tmp/__nope_state.json"),
                    poll_interval=0,
                ),
                timeout=1.0,
            )

        asyncio.run(go())

    def test_empty_channel_returns_immediately(self) -> None:
        import bot

        async def go():
            await asyncio.wait_for(
                bot.directive_board_sync_loop(
                    client=mock.MagicMock(),
                    channel_id="",
                    token="x",
                    jsonl_path=Path("/tmp/__nope.jsonl"),
                    state_path=Path("/tmp/__nope_state.json"),
                    poll_interval=300,
                ),
                timeout=1.0,
            )

        asyncio.run(go())

    def test_one_iter_then_cancel(self) -> None:
        import bot

        with tempfile.TemporaryDirectory() as tmpd:
            jsonl = Path(tmpd) / "dir.jsonl"
            state = Path(tmpd) / "state.json"
            _write_jsonl(
                jsonl,
                [
                    {
                        "message_id": "M1",
                        "last_updated_kst": "T1",
                        "summary": "a",
                    }
                ],
            )

            call_count = {"n": 0}

            def fake_sync_once(**kwargs):
                call_count["n"] += 1
                result = dbs.SyncResult(scanned=1, patched=1)
                return result

            async def go():
                with mock.patch.object(
                    bot, "directive_board_sync_once", side_effect=fake_sync_once
                ):
                    task = asyncio.create_task(
                        bot.directive_board_sync_loop(
                            client=mock.MagicMock(),
                            channel_id="C",
                            token="x",
                            jsonl_path=jsonl,
                            state_path=state,
                            poll_interval=300,
                            initial_delay=0,
                        )
                    )
                    # 1 iter 돌아갈 시간만 yield.
                    await asyncio.sleep(0.05)
                    task.cancel()
                    try:
                        await task
                    except asyncio.CancelledError:
                        pass

            asyncio.run(go())
            self.assertGreaterEqual(call_count["n"], 1)


# ─────────────────────────────────────────────────────────────────────────────
# bot.format_cycle_digest integration (directive_summary field)
# ─────────────────────────────────────────────────────────────────────────────


class FormatDigestDirectiveTest(unittest.TestCase):
    def test_directive_field_with_mismatch(self) -> None:
        # discord 실제 모듈 필요 — tests/__init__.py 가 venv import 보장.
        try:
            import discord as _real_discord  # noqa: F401
        except ImportError:
            self.skipTest("discord module not available")
        import bot

        summary = {
            "total": 8,
            "ok": 7,
            "mismatch": 1,
            "mismatch_ids": ["M3"],
        }
        embed, sig = bot.format_cycle_digest(
            {
                "be": {"in_progress": None, "last_completed": None},
                "fe": {"in_progress": None, "last_completed": None},
                "rev": {"in_progress": None, "last_completed": None},
                "plan": {"in_progress": None, "last_completed": None},
            },
            directive_summary=summary,
        )
        # field name 'mismatch' 표기 확인.
        names = [f.name for f in embed.fields]
        self.assertTrue(any("지시 보드" in n for n in names))
        # signature 안 directives 토큰 확인.
        self.assertIn("directives=7/1/8", sig)

    def test_directive_field_skipped_when_total_zero(self) -> None:
        try:
            import discord as _real_discord  # noqa: F401
        except ImportError:
            self.skipTest("discord module not available")
        import bot

        summary = {"total": 0, "ok": 0, "mismatch": 0, "mismatch_ids": []}
        embed, _sig = bot.format_cycle_digest(
            {
                "be": {"in_progress": None, "last_completed": None},
                "fe": {"in_progress": None, "last_completed": None},
                "rev": {"in_progress": None, "last_completed": None},
                "plan": {"in_progress": None, "last_completed": None},
            },
            directive_summary=summary,
        )
        names = [f.name for f in embed.fields]
        self.assertFalse(any("지시 보드" in n for n in names))


if __name__ == "__main__":
    unittest.main()
