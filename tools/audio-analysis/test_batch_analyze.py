"""batch_analyze.py 순수 로직 단위 테스트.

외부 IO(yt-dlp/librosa)는 호출하지 않는다 — load_seed / 정확도 산출 / feed 변환 /
회귀 가드 판정만 검증한다. unittest.TestCase 라 `python3 -m unittest` 와 pytest
양쪽에서 실행된다.
"""
from __future__ import annotations

import json
import os
import tempfile
import unittest
from pathlib import Path

import batch_analyze as ba

VALIDATION_SET = Path(__file__).parent / "tests" / "validation_set.json"
NEW_SONGS_SET = Path(__file__).parent / "tests" / "new-songs-verification.json"


class TestLoadSeed(unittest.TestCase):
    def test_loads_object_form(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "s.json"
            path.write_text(
                json.dumps({"songs": [{"id": "a", "title": "x"}]}), encoding="utf-8"
            )
            songs = ba.load_seed(str(path))
            self.assertEqual(len(songs), 1)
            self.assertEqual(songs[0]["id"], "a")

    def test_loads_array_form(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "s.json"
            path.write_text(
                json.dumps([{"id": "a", "youtubeUrl": "u"}]), encoding="utf-8"
            )
            self.assertEqual(len(ba.load_seed(str(path))), 1)

    def test_missing_id_raises(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "s.json"
            path.write_text(json.dumps([{"title": "x"}]), encoding="utf-8")
            with self.assertRaises(ValueError):
                ba.load_seed(str(path))

    def test_missing_url_and_title_raises(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "s.json"
            path.write_text(json.dumps([{"id": "a"}]), encoding="utf-8")
            with self.assertRaises(ValueError):
                ba.load_seed(str(path))

    def test_shipped_validation_set_is_valid(self) -> None:
        songs = ba.load_seed(str(VALIDATION_SET))
        self.assertGreaterEqual(len(songs), 3)
        self.assertLessEqual(len(songs), 5)
        for song in songs:
            self.assertIn("label", song)
            self.assertIn("lowMidi", song["label"])
            self.assertIn("highMidi", song["label"])

    def test_shipped_new_songs_set_is_valid(self) -> None:
        # directive #1716 신규곡 검증 fixture — 라벨 없음(plausibility 가드 대상).
        songs = ba.load_seed(str(NEW_SONGS_SET))
        self.assertGreaterEqual(len(songs), 5)
        self.assertLessEqual(len(songs), 10)
        ids = [song["id"] for song in songs]
        self.assertEqual(len(ids), len(set(ids)))  # id 중복 없음
        for song in songs:
            self.assertIn("title", song)
            self.assertNotIn("label", song)  # 신규곡 = ground truth 없음


class TestAbsSemitoneError(unittest.TestCase):
    def test_basic(self) -> None:
        self.assertEqual(ba.abs_semitone_error(60, 62), 2)

    def test_order_independent(self) -> None:
        self.assertEqual(ba.abs_semitone_error(62, 60), 2)

    def test_none_predicted(self) -> None:
        self.assertIsNone(ba.abs_semitone_error(None, 60))

    def test_none_label(self) -> None:
        self.assertIsNone(ba.abs_semitone_error(60, None))


class TestComputeAccuracy(unittest.TestCase):
    def _seed(self) -> list[dict]:
        return [
            {"id": "a", "label": {"lowMidi": 50, "highMidi": 70, "key": "C"}},
            {"id": "b", "label": {"lowMidi": 55, "highMidi": 72, "key": "G"}},
            {"id": "c", "label": None},
        ]

    def test_perfect_match(self) -> None:
        results = [
            {"id": "a", "status": "success", "lowMidi": 50, "highMidi": 70, "key": "C", "confidence": 0.9},
            {"id": "b", "status": "success", "lowMidi": 55, "highMidi": 72, "key": "G", "confidence": 0.8},
        ]
        metrics = ba.compute_accuracy(results, self._seed())
        self.assertEqual(metrics["songsCompared"], 2)
        self.assertEqual(metrics["lowMidiMae"], 0)
        self.assertEqual(metrics["highMidiMae"], 0)
        self.assertEqual(metrics["keyAccuracy"], 1.0)
        self.assertTrue(metrics["withinTolerance"])

    def test_excludes_unlabeled_and_failed(self) -> None:
        results = [
            {"id": "a", "status": "success", "lowMidi": 51, "highMidi": 69, "key": "C", "confidence": 0.7},
            {"id": "b", "status": "failed", "error": "x"},
            {"id": "c", "status": "success", "lowMidi": 40, "highMidi": 80, "key": "D", "confidence": 0.2},
        ]
        metrics = ba.compute_accuracy(results, self._seed())
        # b 실패 + c 라벨 없음 → 비교 1곡(a)
        self.assertEqual(metrics["songsCompared"], 1)
        self.assertEqual(metrics["lowMidiMae"], 1)
        self.assertEqual(metrics["highMidiMae"], 1)

    def test_mae_and_max(self) -> None:
        results = [
            {"id": "a", "status": "success", "lowMidi": 52, "highMidi": 73, "key": "C", "confidence": 0.7},
            {"id": "b", "status": "success", "lowMidi": 55, "highMidi": 72, "key": "F", "confidence": 0.6},
        ]
        metrics = ba.compute_accuracy(results, self._seed())
        # a: low|52-50|=2 high|73-70|=3 ; b: low 0 high 0
        self.assertEqual(metrics["lowMidiMae"], 1.0)
        self.assertEqual(metrics["highMidiMae"], 1.5)
        self.assertEqual(metrics["lowMidiMax"], 2)
        self.assertEqual(metrics["highMidiMax"], 3)
        self.assertEqual(metrics["keyAccuracy"], 0.5)

    def test_no_comparable_songs(self) -> None:
        metrics = ba.compute_accuracy([], self._seed())
        self.assertEqual(metrics["songsCompared"], 0)
        self.assertFalse(metrics["withinTolerance"])


class TestAccuracyWithinTolerance(unittest.TestCase):
    def test_fails_when_mae_exceeds(self) -> None:
        metrics = {
            "songsCompared": 1,
            "lowMidiMae": 3.0,
            "highMidiMae": 1.0,
            "lowMidiMax": 3.0,
            "highMidiMax": 1.0,
            "confidenceMean": 0.8,
        }
        self.assertFalse(ba.accuracy_within_tolerance(metrics))

    def test_fails_when_confidence_low(self) -> None:
        metrics = {
            "songsCompared": 1,
            "lowMidiMae": 1.0,
            "highMidiMae": 1.0,
            "lowMidiMax": 2.0,
            "highMidiMax": 2.0,
            "confidenceMean": 0.4,
        }
        self.assertFalse(ba.accuracy_within_tolerance(metrics))

    def test_passes_within_bounds(self) -> None:
        metrics = {
            "songsCompared": 2,
            "lowMidiMae": 2.0,
            "highMidiMae": 1.5,
            "lowMidiMax": 4.0,
            "highMidiMax": 3.0,
            "confidenceMean": 0.6,
        }
        self.assertTrue(ba.accuracy_within_tolerance(metrics))

    def test_fails_when_no_samples(self) -> None:
        self.assertFalse(ba.accuracy_within_tolerance({"songsCompared": 0}))


class TestRangePlausibility(unittest.TestCase):
    def _ok(self) -> dict:
        return {"id": "a", "status": "success", "lowMidi": 55, "highMidi": 77}

    def test_plausible_range(self) -> None:
        row = ba.range_plausibility(self._ok())
        self.assertTrue(row["plausible"])
        self.assertEqual(row["reasons"], [])

    def test_failed_status_implausible(self) -> None:
        row = ba.range_plausibility({"id": "a", "status": "failed", "error": "x"})
        self.assertFalse(row["plausible"])

    def test_missing_midi_implausible(self) -> None:
        row = ba.range_plausibility({"id": "a", "status": "success", "lowMidi": 55})
        self.assertFalse(row["plausible"])

    def test_low_ge_high(self) -> None:
        row = ba.range_plausibility(
            {"id": "a", "status": "success", "lowMidi": 70, "highMidi": 60}
        )
        self.assertFalse(row["plausible"])
        self.assertTrue(any(">=" in r for r in row["reasons"]))

    def test_low_below_floor(self) -> None:
        # 반주 저음 오검출 — C2 미만.
        row = ba.range_plausibility(
            {"id": "a", "status": "success", "lowMidi": 24, "highMidi": 60}
        )
        self.assertFalse(row["plausible"])

    def test_high_above_ceil(self) -> None:
        row = ba.range_plausibility(
            {"id": "a", "status": "success", "lowMidi": 55, "highMidi": 100}
        )
        self.assertFalse(row["plausible"])

    def test_span_too_wide(self) -> None:
        # 옥타브 폴딩 등으로 음역폭이 비현실적으로 넓음.
        row = ba.range_plausibility(
            {"id": "a", "status": "success", "lowMidi": 40, "highMidi": 85}
        )
        self.assertFalse(row["plausible"])
        self.assertTrue(any("음역폭" in r for r in row["reasons"]))

    def test_span_too_narrow(self) -> None:
        row = ba.range_plausibility(
            {"id": "a", "status": "success", "lowMidi": 60, "highMidi": 62}
        )
        self.assertFalse(row["plausible"])

    def test_validation_labels_are_plausible(self) -> None:
        # 운영자 PoC 라벨은 모두 합리적 범위여야 한다(가드 보정 sanity).
        songs = ba.load_seed(str(VALIDATION_SET))
        for song in songs:
            label = song["label"]
            row = ba.range_plausibility(
                {
                    "id": song["id"],
                    "status": "success",
                    "lowMidi": label["lowMidi"],
                    "highMidi": label["highMidi"],
                }
            )
            self.assertTrue(row["plausible"], f"{song['id']} {row['reasons']}")


class TestSummarizePlausibility(unittest.TestCase):
    def _records(self) -> list[dict]:
        return [
            {"id": "a", "status": "success", "lowMidi": 55, "highMidi": 77},
            {"id": "b", "status": "success", "lowMidi": 24, "highMidi": 60},  # 비합리
            {"id": "c", "status": "failed", "error": "download"},
        ]

    def test_counts_and_partition(self) -> None:
        summary = ba.summarize_plausibility(self._records())
        self.assertEqual(summary["songsTotal"], 3)
        self.assertEqual(summary["songsSuccess"], 2)
        self.assertEqual(summary["plausibleCount"], 1)
        self.assertEqual(summary["implausibleCount"], 1)  # 실패곡은 제외, b만
        self.assertEqual(summary["plausibleRate"], 0.5)
        self.assertEqual(summary["backfillReady"], ["a"])
        self.assertEqual(summary["blocked"][0]["id"], "b")

    def test_all_plausible_rate_one(self) -> None:
        records = [{"id": "a", "status": "success", "lowMidi": 50, "highMidi": 70}]
        summary = ba.summarize_plausibility(records)
        self.assertEqual(summary["plausibleRate"], 1.0)
        self.assertEqual(summary["implausibleCount"], 0)

    def test_no_success_rate_none(self) -> None:
        summary = ba.summarize_plausibility([{"id": "a", "status": "failed"}])
        self.assertIsNone(summary["plausibleRate"])
        self.assertEqual(summary["implausibleCount"], 0)

    def test_report_renders(self) -> None:
        report = ba.format_plausibility_report(ba.summarize_plausibility(self._records()))
        self.assertIn("음역 타당성", report)
        self.assertIn("backfill 대상", report)


class TestToFeedRecord(unittest.TestCase):
    def test_maps_fields_and_metadata_source(self) -> None:
        class _R:
            lowMidi = 55
            highMidi = 71
            key = "C"
            tempo = 120.0
            durationSec = 45.0
            confidence = 0.8
            analysisMethod = "vocal-skip"
            toolingVersion = "analyze-py-0.2.0"

        record = ba.to_feed_record("val-001", _R(), "vocal-skip")
        self.assertEqual(record["id"], "val-001")
        self.assertEqual(record["status"], "success")
        self.assertEqual(record["metadataSource"], "AUDIO_ANALYSIS")
        self.assertEqual(record["lowMidi"], 55)
        self.assertEqual(record["highMidi"], 71)


class TestResolveTmpdir(unittest.TestCase):
    def test_sets_tmpdir_env_and_creates(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / "data" / "tmp"
            resolved = ba.resolve_tmpdir(str(target))
            self.assertTrue(resolved.exists())
            self.assertEqual(os.environ["TMPDIR"], str(target))

    def test_env_fallback(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / "env-tmp"
            os.environ["AUDIO_ANALYSIS_TMPDIR"] = str(target)
            try:
                resolved = ba.resolve_tmpdir(None)
                self.assertEqual(resolved, target)
                self.assertTrue(resolved.exists())
            finally:
                del os.environ["AUDIO_ANALYSIS_TMPDIR"]


class TestLoadResultsRoundtrip(unittest.TestCase):
    def test_write_then_load(self) -> None:
        records = [
            {"id": "a", "status": "success", "lowMidi": 50},
            {"id": "b", "status": "failed", "error": "x"},
        ]
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "feed.ndjson"
            ba.write_feed(records, str(path))
            loaded = ba.load_results(str(path))
            self.assertEqual(loaded, records)


if __name__ == "__main__":
    unittest.main()
