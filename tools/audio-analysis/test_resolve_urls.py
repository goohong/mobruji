"""resolve_urls.py 순수 로직 단위 테스트.

외부 IO(yt-dlp)는 호출하지 않는다 — 정규화/토큰 매칭/신뢰도/판정/feed 병합/seed
변환만 검증한다. unittest.TestCase 라 `python3 -m unittest` 와 pytest 양쪽에서 실행된다.
"""
from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

import resolve_urls as ru

NEW_SONGS_SET = Path(__file__).parent / "tests" / "new-songs-verification.json"


class TestNormalizeTokenize(unittest.TestCase):
    def test_normalize_lowercases_and_strips_punct(self) -> None:
        self.assertEqual(ru.normalize_text("Hello, World!"), "hello world")

    def test_normalize_preserves_hangul(self) -> None:
        self.assertEqual(ru.normalize_text("윤하 - 사건의 지평선"), "윤하 사건의 지평선")

    def test_normalize_none_is_empty(self) -> None:
        self.assertEqual(ru.normalize_text(None), "")

    def test_tokenize_returns_set(self) -> None:
        self.assertEqual(ru.tokenize("a b a"), {"a", "b"})

    def test_tokenize_empty(self) -> None:
        self.assertEqual(ru.tokenize(""), set())


class TestTokenRecall(unittest.TestCase):
    def test_full_recall(self) -> None:
        self.assertEqual(ru.token_recall({"a", "b"}, {"a", "b", "c"}), 1.0)

    def test_partial_recall(self) -> None:
        self.assertEqual(ru.token_recall({"a", "b"}, {"a", "x"}), 0.5)

    def test_empty_query_is_zero(self) -> None:
        self.assertEqual(ru.token_recall(set(), {"a"}), 0.0)


class TestDurationPlausibility(unittest.TestCase):
    def test_none_is_neutral(self) -> None:
        self.assertEqual(ru.duration_plausibility(None), 0.5)

    def test_full_credit_window(self) -> None:
        self.assertEqual(ru.duration_plausibility(200), 1.0)
        self.assertEqual(ru.duration_plausibility(60), 1.0)
        self.assertEqual(ru.duration_plausibility(420), 1.0)

    def test_too_short_zero(self) -> None:
        self.assertEqual(ru.duration_plausibility(20), 0.0)
        self.assertEqual(ru.duration_plausibility(5), 0.0)

    def test_too_long_zero(self) -> None:
        self.assertEqual(ru.duration_plausibility(900), 0.0)
        self.assertEqual(ru.duration_plausibility(5000), 0.0)

    def test_taper_between(self) -> None:
        # 40초: (40-20)/(60-20)=0.5
        self.assertEqual(ru.duration_plausibility(40), 0.5)

    def test_non_positive_zero(self) -> None:
        self.assertEqual(ru.duration_plausibility(0), 0.0)
        self.assertEqual(ru.duration_plausibility(-3), 0.0)


class TestMatchConfidence(unittest.TestCase):
    def test_strong_match_high_confidence(self) -> None:
        conf = ru.match_confidence(
            "사건의 지평선",
            "윤하",
            "윤하 (YOUNHA) - 사건의 지평선 Official MV",
            "윤하 공식 채널",
            255,
        )
        # 텍스트 만점 + 길이 만점 → 1.0 근처
        self.assertGreaterEqual(conf, 0.9)

    def test_unrelated_low_confidence(self) -> None:
        conf = ru.match_confidence(
            "사건의 지평선",
            "윤하",
            "Cooking pasta tutorial",
            "Chef Channel",
            255,
        )
        # 텍스트 0 → 길이 보조(0.2)만 → 0.2 근처
        self.assertLess(conf, 0.5)

    def test_no_artist_uses_title_only(self) -> None:
        conf = ru.match_confidence(
            "Yesterday", None, "The Beatles - Yesterday", "Beatles", 125
        )
        self.assertGreaterEqual(conf, 0.8)

    def test_title_match_artist_miss_partial(self) -> None:
        # 제목 일치, 아티스트 불일치 → 텍스트 0.65*1 + 0.35*0 = 0.65, +길이 → < 1
        conf = ru.match_confidence(
            "봄날", "방탄소년단", "봄날 cover by someone", "Random Singer", 200
        )
        self.assertGreater(conf, 0.5)
        self.assertLess(conf, 0.9)


class TestClassify(unittest.TestCase):
    def test_resolved_above_threshold(self) -> None:
        self.assertEqual(ru.classify_resolution(0.6, 0.5), ru.STATUS_RESOLVED)

    def test_resolved_at_threshold(self) -> None:
        self.assertEqual(ru.classify_resolution(0.5, 0.5), ru.STATUS_RESOLVED)

    def test_skipped_below_threshold(self) -> None:
        self.assertEqual(ru.classify_resolution(0.49, 0.5), ru.STATUS_SKIPPED)


class TestToResolvedRecord(unittest.TestCase):
    def test_no_candidate_is_no_result(self) -> None:
        record = ru.to_resolved_record({"id": "x", "title": "t"}, None, 0.5)
        self.assertEqual(record["status"], ru.STATUS_NO_RESULT)
        self.assertEqual(record["matchConfidence"], 0.0)
        self.assertNotIn("youtubeUrl", record)

    def test_resolved_carries_url(self) -> None:
        candidate = {
            "url": "https://www.youtube.com/watch?v=abcd1234",
            "title": "윤하 - 사건의 지평선",
            "uploader": "윤하",
            "duration": 255,
        }
        record = ru.to_resolved_record(
            {"id": "x", "title": "사건의 지평선", "artist": "윤하"}, candidate, 0.5
        )
        self.assertEqual(record["status"], ru.STATUS_RESOLVED)
        self.assertEqual(record["youtubeUrl"], candidate["url"])

    def test_skipped_omits_url(self) -> None:
        candidate = {
            "url": "https://www.youtube.com/watch?v=zzzz",
            "title": "totally unrelated",
            "uploader": "nobody",
            "duration": 255,
        }
        record = ru.to_resolved_record(
            {"id": "x", "title": "사건의 지평선", "artist": "윤하"}, candidate, 0.5
        )
        self.assertEqual(record["status"], ru.STATUS_SKIPPED)
        self.assertNotIn("youtubeUrl", record)
        self.assertIn("candidateTitle", record)


class TestResumeChunk(unittest.TestCase):
    def test_done_ids_only_terminal(self) -> None:
        records = [
            {"id": "a", "status": ru.STATUS_RESOLVED},
            {"id": "b", "status": ru.STATUS_SKIPPED},
            {"id": "c", "status": ru.STATUS_NO_RESULT},
            {"id": "d", "status": ru.STATUS_FAILED},
        ]
        self.assertEqual(ru.done_ids_from_feed(records), {"a", "b", "c"})

    def test_select_pending_excludes_done_and_limits(self) -> None:
        seed = [{"id": str(i)} for i in range(5)]
        pending = ru.select_pending(seed, {"0", "1"}, limit=2)
        self.assertEqual([s["id"] for s in pending], ["2", "3"])

    def test_select_pending_no_limit(self) -> None:
        seed = [{"id": "0"}, {"id": "1"}]
        self.assertEqual(len(ru.select_pending(seed, set(), None)), 2)

    def test_merge_feed_fresh_wins(self) -> None:
        existing = [{"id": "a", "status": "failed"}]
        fresh = [{"id": "a", "status": "resolved"}, {"id": "b", "status": "resolved"}]
        merged = ru.merge_feed(existing, fresh)
        by_id = {r["id"]: r for r in merged}
        self.assertEqual(by_id["a"]["status"], "resolved")
        self.assertEqual(len(merged), 2)


class TestResolvedSeedSongs(unittest.TestCase):
    def test_only_resolved_included(self) -> None:
        records = [
            {
                "id": "a",
                "status": ru.STATUS_RESOLVED,
                "title": "t",
                "artist": "ar",
                "youtubeUrl": "u",
                "matchConfidence": 0.9,
            },
            {"id": "b", "status": ru.STATUS_SKIPPED},
            {"id": "c", "status": ru.STATUS_NO_RESULT},
        ]
        songs = ru.resolved_seed_songs(records)
        self.assertEqual(len(songs), 1)
        self.assertEqual(songs[0]["id"], "a")
        self.assertEqual(songs[0]["youtubeUrl"], "u")

    def test_seed_out_is_batch_loadable(self) -> None:
        # resolved seed 가 batch_analyze.load_seed 규약(id+youtubeUrl)을 만족하는지
        records = [
            {
                "id": "a",
                "status": ru.STATUS_RESOLVED,
                "title": "t",
                "artist": "ar",
                "youtubeUrl": "https://www.youtube.com/watch?v=x",
                "matchConfidence": 0.9,
            }
        ]
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "seed.json"
            ru.write_seed_out(records, str(out))
            payload = json.loads(out.read_text(encoding="utf-8"))
            self.assertEqual(len(payload["songs"]), 1)
            song = payload["songs"][0]
            self.assertIn("id", song)
            self.assertIn("youtubeUrl", song)


class TestBuildQuery(unittest.TestCase):
    def test_artist_and_title(self) -> None:
        self.assertEqual(ru.build_search_query("좋니", "윤종신"), "윤종신 좋니")

    def test_title_only(self) -> None:
        self.assertEqual(ru.build_search_query("Yesterday", None), "Yesterday")

    def test_blank_artist(self) -> None:
        self.assertEqual(ru.build_search_query("Yesterday", "  "), "Yesterday")


class TestLoadSeed(unittest.TestCase):
    def test_object_form(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "s.json"
            path.write_text(
                json.dumps({"songs": [{"id": "a", "title": "x"}]}), encoding="utf-8"
            )
            self.assertEqual(len(ru.load_seed(str(path))), 1)

    def test_missing_id_raises(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "s.json"
            path.write_text(json.dumps([{"title": "x"}]), encoding="utf-8")
            with self.assertRaises(ValueError):
                ru.load_seed(str(path))

    def test_missing_title_and_url_raises(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "s.json"
            path.write_text(json.dumps([{"id": "a"}]), encoding="utf-8")
            with self.assertRaises(ValueError):
                ru.load_seed(str(path))

    def test_shipped_new_songs_set_loads(self) -> None:
        # directive #1716 fixture(미보유 곡 title+artist) 가 resolve 입력으로 유효한지
        songs = ru.load_seed(str(NEW_SONGS_SET))
        self.assertGreaterEqual(len(songs), 5)
        for song in songs:
            self.assertIn("title", song)


class TestSummarize(unittest.TestCase):
    def test_counts_and_mean(self) -> None:
        records = [
            {"id": "a", "status": ru.STATUS_RESOLVED, "matchConfidence": 0.8},
            {"id": "b", "status": ru.STATUS_RESOLVED, "matchConfidence": 0.6},
            {"id": "c", "status": ru.STATUS_SKIPPED, "matchConfidence": 0.3},
            {"id": "d", "status": ru.STATUS_NO_RESULT, "matchConfidence": 0.0},
            {"id": "e", "status": ru.STATUS_FAILED},
        ]
        summary = ru.summarize(records)
        self.assertEqual(summary["resolved"], 2)
        self.assertEqual(summary["skipped"], 1)
        self.assertEqual(summary["noResult"], 1)
        self.assertEqual(summary["failed"], 1)
        self.assertEqual(summary["resolvedConfidenceMean"], 0.7)


if __name__ == "__main__":
    unittest.main()
