"""cycle-counter (KST 자정 기준 sub-agent 별 누적) 단위 테스트 (#996).

검증 대상 — `tools/cycle-status/update.py`:
- `increment_cycle_counter(workspace)` — 같은 날 누적, 날짜 바뀜 reset, 첫 호출,
  atomic write, 미지원 워크트리 skip.
- `read_cycle_counts()` — 정상 read, 부재 None, JSON 깨짐 None, lazy reset
  (date 다르면 모두 0).
- `_kst_today_str(now)` — KST 자정 경계 (UTC 14:59 → 23:59 KST 어제, UTC 15:00
  → 00:00 KST 오늘).

CLI integration (`update.py --action set-active`) 도 한 케이스 — 별 프로세스로
실행해 counter 파일이 실제로 갱신되는지 확인.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

PARENT_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PARENT_DIR))

import update  # noqa: E402

KST = timezone(timedelta(hours=9))


class KstTodayStrTest(unittest.TestCase):
    """KST 자정 경계 — UTC 15:00 직전/직후."""

    def test_utc_15h_is_next_kst_day(self) -> None:
        # UTC 2026-05-23 15:00 = KST 2026-05-24 00:00.
        now_utc = datetime(2026, 5, 23, 15, 0, tzinfo=timezone.utc)
        self.assertEqual(update._kst_today_str(now_utc), "2026-05-24")

    def test_utc_14_59_is_same_kst_day(self) -> None:
        # UTC 2026-05-23 14:59 = KST 2026-05-23 23:59.
        now_utc = datetime(2026, 5, 23, 14, 59, tzinfo=timezone.utc)
        self.assertEqual(update._kst_today_str(now_utc), "2026-05-23")

    def test_kst_aware_input_preserves_day(self) -> None:
        # KST aware datetime — astimezone 후 동일 날짜.
        now_kst = datetime(2026, 5, 24, 9, 0, tzinfo=KST)
        self.assertEqual(update._kst_today_str(now_kst), "2026-05-24")


class IncrementCycleCounterTest(unittest.TestCase):
    """`increment_cycle_counter` 같은 날 / 다른 날 / 첫 호출 / 다중."""

    def setUp(self) -> None:
        self.tmp = tempfile.NamedTemporaryFile(
            mode="w", suffix=".json", delete=False, encoding="utf-8"
        )
        self.tmp.close()
        # 빈 파일이 load_existing 단계에서 깨졌다고 판단되도록 일단 unlink.
        Path(self.tmp.name).unlink(missing_ok=True)
        self.path = self.tmp.name
        self.now_kst = datetime(2026, 5, 24, 10, 0, tzinfo=KST)

    def tearDown(self) -> None:
        Path(self.path).unlink(missing_ok=True)

    def test_first_call_creates_file_with_count_1(self) -> None:
        # 파일 부재 + 첫 호출 → 신규 payload + count=1.
        result = update.increment_cycle_counter(
            "be", path=self.path, now=self.now_kst
        )
        self.assertEqual(result["date"], "2026-05-24")
        self.assertEqual(result["counts"]["be"], 1)
        self.assertEqual(result["counts"]["fe"], 0)
        # 파일에도 실제 기록.
        with open(self.path, encoding="utf-8") as fp:
            on_disk = json.load(fp)
        self.assertEqual(on_disk, result)

    def test_same_day_accumulates(self) -> None:
        # 같은 날 3번 → count=3.
        for _ in range(3):
            update.increment_cycle_counter("be", path=self.path, now=self.now_kst)
        with open(self.path, encoding="utf-8") as fp:
            on_disk = json.load(fp)
        self.assertEqual(on_disk["counts"]["be"], 3)

    def test_independent_workspaces(self) -> None:
        # 여러 워크트리 호출 — 각자 독립 카운트.
        update.increment_cycle_counter("be", path=self.path, now=self.now_kst)
        update.increment_cycle_counter("be", path=self.path, now=self.now_kst)
        update.increment_cycle_counter("fe", path=self.path, now=self.now_kst)
        update.increment_cycle_counter("rev", path=self.path, now=self.now_kst)
        update.increment_cycle_counter("rev", path=self.path, now=self.now_kst)
        update.increment_cycle_counter("plan", path=self.path, now=self.now_kst)

        with open(self.path, encoding="utf-8") as fp:
            on_disk = json.load(fp)
        self.assertEqual(on_disk["counts"], {"be": 2, "fe": 1, "rev": 2, "plan": 1})

    def test_date_rollover_resets_counts(self) -> None:
        # day1: be 5번 / fe 3번 / rev 7번 / plan 2번.
        day1 = datetime(2026, 5, 23, 22, 0, tzinfo=KST)
        for _ in range(5):
            update.increment_cycle_counter("be", path=self.path, now=day1)
        for _ in range(3):
            update.increment_cycle_counter("fe", path=self.path, now=day1)

        # 자정 경과 후 day2 첫 호출 — 모두 reset 후 호출한 워크트리만 1.
        day2 = datetime(2026, 5, 24, 0, 5, tzinfo=KST)
        result = update.increment_cycle_counter("rev", path=self.path, now=day2)

        self.assertEqual(result["date"], "2026-05-24")
        self.assertEqual(result["counts"]["be"], 0)
        self.assertEqual(result["counts"]["fe"], 0)
        self.assertEqual(result["counts"]["rev"], 1)
        self.assertEqual(result["counts"]["plan"], 0)

    def test_unknown_workspace_silently_skipped(self) -> None:
        # 미지원 워크트리 → counter 갱신 안 함. 기존 파일 유지.
        update.increment_cycle_counter("be", path=self.path, now=self.now_kst)
        update.increment_cycle_counter("ml", path=self.path, now=self.now_kst)  # noop
        with open(self.path, encoding="utf-8") as fp:
            on_disk = json.load(fp)
        # 카운트 변화 없음 — be=1 그대로.
        self.assertEqual(on_disk["counts"], {"be": 1, "fe": 0, "rev": 0, "plan": 0})

    def test_atomic_write_no_temp_left_behind(self) -> None:
        # increment 성공 후 디렉토리에 .cycle-counter-*.tmp 잔재 없음.
        update.increment_cycle_counter("be", path=self.path, now=self.now_kst)
        dir_path = Path(self.path).parent
        leftover_tmps = list(dir_path.glob(".cycle-counter-*.tmp"))
        self.assertEqual(leftover_tmps, [])

    def test_corrupted_file_recovered_as_new_day(self) -> None:
        # 깨진 JSON → load_existing 가 {} 반환 → 새 payload 로 초기화.
        with open(self.path, "w", encoding="utf-8") as fp:
            fp.write("{not valid")
        result = update.increment_cycle_counter(
            "fe", path=self.path, now=self.now_kst
        )
        self.assertEqual(result["counts"]["fe"], 1)
        self.assertEqual(result["counts"]["be"], 0)

    def test_negative_existing_count_treated_as_zero(self) -> None:
        # 외부 손상으로 count 가 음수면 0 으로 정정 후 ++.
        with open(self.path, "w", encoding="utf-8") as fp:
            json.dump(
                {"date": "2026-05-24", "counts": {"be": -5, "fe": 2, "rev": 0, "plan": 0}},
                fp,
            )
        result = update.increment_cycle_counter(
            "be", path=self.path, now=self.now_kst
        )
        self.assertEqual(result["counts"]["be"], 1)  # -5 → 0 → +1
        self.assertEqual(result["counts"]["fe"], 2)  # 보존


class ReadCycleCountsTest(unittest.TestCase):
    """`read_cycle_counts` reader — 정상 / 부재 / 깨짐 / lazy reset."""

    def setUp(self) -> None:
        self.tmp = tempfile.NamedTemporaryFile(
            mode="w", suffix=".json", delete=False, encoding="utf-8"
        )
        self.tmp.close()
        Path(self.tmp.name).unlink(missing_ok=True)
        self.path = self.tmp.name
        self.now_kst = datetime(2026, 5, 24, 10, 0, tzinfo=KST)

    def tearDown(self) -> None:
        Path(self.path).unlink(missing_ok=True)

    def test_missing_returns_none(self) -> None:
        self.assertIsNone(update.read_cycle_counts(path=self.path))

    def test_malformed_returns_none(self) -> None:
        with open(self.path, "w", encoding="utf-8") as fp:
            fp.write("{nope")
        self.assertIsNone(update.read_cycle_counts(path=self.path))

    def test_non_dict_returns_none(self) -> None:
        with open(self.path, "w", encoding="utf-8") as fp:
            json.dump([1, 2, 3], fp)
        self.assertIsNone(update.read_cycle_counts(path=self.path))

    def test_same_day_returns_counts(self) -> None:
        with open(self.path, "w", encoding="utf-8") as fp:
            json.dump(
                {"date": "2026-05-24", "counts": {"be": 12, "fe": 8, "rev": 15, "plan": 4}},
                fp,
            )
        result = update.read_cycle_counts(path=self.path, now=self.now_kst)
        self.assertEqual(result["date"], "2026-05-24")
        self.assertEqual(result["counts"], {"be": 12, "fe": 8, "rev": 15, "plan": 4})

    def test_different_day_lazy_reset_to_zero(self) -> None:
        # 어제 date — reader 는 lazy reset 후 모두 0 반환 (디스크 갱신 안 함).
        with open(self.path, "w", encoding="utf-8") as fp:
            json.dump(
                {"date": "2026-05-23", "counts": {"be": 99, "fe": 99, "rev": 99, "plan": 99}},
                fp,
            )
        result = update.read_cycle_counts(path=self.path, now=self.now_kst)
        self.assertEqual(result["date"], "2026-05-24")
        self.assertEqual(result["counts"], {"be": 0, "fe": 0, "rev": 0, "plan": 0})

        # 디스크는 변경 안 됨 — reader 책임 외.
        with open(self.path, encoding="utf-8") as fp:
            on_disk = json.load(fp)
        self.assertEqual(on_disk["date"], "2026-05-23")
        self.assertEqual(on_disk["counts"]["be"], 99)

    def test_missing_workspace_field_defaults_zero(self) -> None:
        with open(self.path, "w", encoding="utf-8") as fp:
            json.dump({"date": "2026-05-24", "counts": {"be": 3}}, fp)
        result = update.read_cycle_counts(path=self.path, now=self.now_kst)
        self.assertEqual(result["counts"], {"be": 3, "fe": 0, "rev": 0, "plan": 0})


class CliSetActiveIncrementsCounterTest(unittest.TestCase):
    """update.py CLI 통합 — set-active 호출 시 counter 가 갱신되는지."""

    def setUp(self) -> None:
        self.tmpdir = tempfile.TemporaryDirectory()
        self.status_path = os.path.join(self.tmpdir.name, "cycle-status.json")
        self.counter_path = os.path.join(self.tmpdir.name, "cycle-counter.json")

    def tearDown(self) -> None:
        self.tmpdir.cleanup()

    def test_set_active_creates_counter(self) -> None:
        update_py = str(PARENT_DIR / "update.py")
        result = subprocess.run(
            [
                sys.executable,
                update_py,
                "--path",
                self.status_path,
                "--counter-path",
                self.counter_path,
                "--worktree",
                "be",
                "--action",
                "set-active",
                "--title",
                "test task",
            ],
            capture_output=True,
            text=True,
        )
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        self.assertTrue(os.path.exists(self.counter_path), "counter file 미생성")
        with open(self.counter_path, encoding="utf-8") as fp:
            on_disk = json.load(fp)
        self.assertEqual(on_disk["counts"]["be"], 1)

    def test_set_idle_does_not_increment_counter(self) -> None:
        # 먼저 set-active 로 베이스 1 만든 뒤 set-idle 호출 — counter 변화 X.
        update_py = str(PARENT_DIR / "update.py")
        for _ in range(2):
            subprocess.run(
                [
                    sys.executable,
                    update_py,
                    "--path",
                    self.status_path,
                    "--counter-path",
                    self.counter_path,
                    "--worktree",
                    "be",
                    "--action",
                    "set-active",
                    "--title",
                    "task",
                ],
                check=True,
                capture_output=True,
            )
        subprocess.run(
            [
                sys.executable,
                update_py,
                "--path",
                self.status_path,
                "--counter-path",
                self.counter_path,
                "--worktree",
                "be",
                "--action",
                "set-idle",
                "--note",
                "다음 후보: PR #999",
            ],
            check=True,
            capture_output=True,
        )
        with open(self.counter_path, encoding="utf-8") as fp:
            on_disk = json.load(fp)
        # set-active 2번만 카운트 — set-idle 은 영향 없음.
        self.assertEqual(on_disk["counts"]["be"], 2)


if __name__ == "__main__":
    unittest.main()
