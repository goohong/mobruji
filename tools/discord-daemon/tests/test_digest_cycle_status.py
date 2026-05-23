"""cycle-status digest 단위 테스트 (#804).

`read_cycle_status` / `format_cycle_digest` 두 함수가
`~/.mobruji/cycle-status.json` 입력 4분기(정상 / 누락 필드 / 빈 dict / 깨진 JSON)
를 정확히 처리하는지 검증.

사용자 룰 (2026-05-23 #모부르지):
    기존 PR open/머지 24h digest 폐기.
    4 워크트리(be/fe/rev/plan) 각 한 줄씩:
        [be] 진행: <in_progress 또는 idle> / 최근: <pr> (<title>)
"""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

PARENT_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PARENT_DIR))

# discord/requests/dotenv 외부 의존 stub (실제 import 회피).
for missing in ("discord", "requests", "dotenv"):
    if missing not in sys.modules:
        stub = mock.MagicMock()
        if missing == "dotenv":
            stub.load_dotenv = lambda *a, **kw: None
        sys.modules[missing] = stub

import bot  # noqa: E402


# ─────────────────────────────────────────────────────────────────────────────
# read_cycle_status
# ─────────────────────────────────────────────────────────────────────────────


class ReadCycleStatusTest(unittest.TestCase):
    """파일 입출력 + JSON 파싱 분기 검증."""

    def test_valid_json_returns_dict(self) -> None:
        sample = {
            "be": {"in_progress": "PR #804", "last_completed": None},
            "fe": {"in_progress": None, "last_completed": None},
            "rev": {"in_progress": None, "last_completed": None},
            "plan": {"in_progress": None, "last_completed": None},
        }
        with tempfile.NamedTemporaryFile(
            mode="w", suffix=".json", delete=False, encoding="utf-8"
        ) as fp:
            json.dump(sample, fp)
            tmp_path = fp.name
        try:
            result = bot.read_cycle_status(tmp_path)
            self.assertEqual(result, sample)
        finally:
            Path(tmp_path).unlink(missing_ok=True)

    def test_missing_file_returns_none(self) -> None:
        # 존재하지 않는 path.
        result = bot.read_cycle_status("/tmp/__never_exists_cycle_status__.json")
        self.assertIsNone(result)

    def test_malformed_json_returns_none(self) -> None:
        with tempfile.NamedTemporaryFile(
            mode="w", suffix=".json", delete=False, encoding="utf-8"
        ) as fp:
            fp.write("{not valid json,,,")
            tmp_path = fp.name
        try:
            result = bot.read_cycle_status(tmp_path)
            self.assertIsNone(result)
        finally:
            Path(tmp_path).unlink(missing_ok=True)


# ─────────────────────────────────────────────────────────────────────────────
# format_cycle_digest
# ─────────────────────────────────────────────────────────────────────────────


class FormatCycleDigestTest(unittest.TestCase):
    """4 워크트리 라인 조립 + signature 산출 검증."""

    def test_full_status_renders_all_four_lines(self) -> None:
        status = {
            "be": {
                "in_progress": "PR #804 digest cron 작성 중",
                "last_completed": {
                    "pr": "#803",
                    "title": "bot.py auto-ack 동적화",
                    "merged_at": "2026-05-23T12:40:00Z",
                },
            },
            "fe": {
                "in_progress": None,
                "last_completed": {
                    "pr": "#796",
                    "title": "voice-range auto handleRetry",
                    "merged_at": "2026-05-23T09:23:03Z",
                },
            },
            "rev": {
                "in_progress": None,
                "last_completed": {
                    "pr": None,
                    "title": "P0 404 진단",
                    "merged_at": "2026-05-23T11:00:00Z",
                },
            },
            "plan": {
                "in_progress": "spec 보강",
                "last_completed": {
                    "pr": "#801",
                    "title": "helper agent spec",
                    "merged_at": "2026-05-23T11:12:10Z",
                },
            },
        }
        rendered, signature = bot.format_cycle_digest(status)

        # 헤더 + 4 워크트리 한 줄씩 = 5줄.
        lines = rendered.split("\n")
        self.assertEqual(len(lines), 5)
        self.assertIn("cycle digest", lines[0])

        # be: in_progress + 최근 (pr + title).
        self.assertIn("[be]", lines[1])
        self.assertIn("진행: PR #804 digest cron 작성 중", lines[1])
        self.assertIn("최근: #803 (bot.py auto-ack 동적화)", lines[1])

        # fe: in_progress null → "idle".
        self.assertIn("[fe]", lines[2])
        self.assertIn("진행: idle", lines[2])
        self.assertIn("최근: #796 (voice-range auto handleRetry)", lines[2])

        # rev: pr null 이어도 title 만으로 표시.
        self.assertIn("[rev]", lines[3])
        self.assertIn("진행: idle", lines[3])
        self.assertIn("최근: P0 404 진단", lines[3])

        # plan: in_progress + pr+title.
        self.assertIn("[plan]", lines[4])
        self.assertIn("진행: spec 보강", lines[4])
        self.assertIn("최근: #801 (helper agent spec)", lines[4])

        # signature 는 시간 무관 — be/fe/rev/plan 4 파트 포함.
        self.assertIn("be=", signature)
        self.assertIn("fe=", signature)
        self.assertIn("rev=", signature)
        self.assertIn("plan=", signature)

    def test_none_status_returns_fallback_message(self) -> None:
        rendered, signature = bot.format_cycle_digest(None)
        self.assertIn("cycle-status.json 읽기 실패", rendered)
        self.assertEqual(signature, "unavailable")

    def test_missing_workspace_renders_as_idle(self) -> None:
        # be 만 존재. 나머지(fe/rev/plan) 는 누락 — 모두 "idle" / "없음" 으로 출력.
        status = {
            "be": {
                "in_progress": "작업 중",
                "last_completed": {"pr": "#100", "title": "Sample"},
            },
        }
        rendered, _signature = bot.format_cycle_digest(status)
        lines = rendered.split("\n")
        # 헤더 1 + 4 워크트리 = 5
        self.assertEqual(len(lines), 5)
        # fe/rev/plan 누락 — idle / 없음
        for ws_line in lines[2:]:
            self.assertIn("진행: idle", ws_line)
            self.assertIn("최근: 없음", ws_line)

    def test_empty_dict_renders_all_idle(self) -> None:
        rendered, signature = bot.format_cycle_digest({})
        lines = rendered.split("\n")
        self.assertEqual(len(lines), 5)
        for ws_line in lines[1:]:
            self.assertIn("진행: idle", ws_line)
            self.assertIn("최근: 없음", ws_line)
        # signature: 모든 워크트리 missing.
        self.assertEqual(signature.count("missing"), 4)

    def test_in_progress_non_string_falls_back_to_idle(self) -> None:
        # in_progress 가 dict / int / list 등 잘못된 타입이면 idle 로 fallback.
        status = {
            "be": {"in_progress": 12345, "last_completed": None},
            "fe": {"in_progress": [], "last_completed": None},
            "rev": {"in_progress": {}, "last_completed": None},
            "plan": {"in_progress": "  ", "last_completed": None},  # 공백 trim
        }
        rendered, _signature = bot.format_cycle_digest(status)
        for ws_line in rendered.split("\n")[1:]:
            self.assertIn("진행: idle", ws_line)

    def test_last_completed_missing_title_uses_fallback(self) -> None:
        # title 누락 → "제목 없음".
        status = {
            "be": {
                "in_progress": None,
                "last_completed": {"pr": "#999"},
            },
            "fe": {"in_progress": None, "last_completed": None},
            "rev": {"in_progress": None, "last_completed": None},
            "plan": {"in_progress": None, "last_completed": None},
        }
        rendered, _signature = bot.format_cycle_digest(status)
        be_line = rendered.split("\n")[1]
        self.assertIn("#999 (제목 없음)", be_line)

    def test_signature_changes_with_in_progress(self) -> None:
        # signature 는 in_progress 변경에 민감해야 delta push 가 동작.
        status_v1 = {ws: {"in_progress": None, "last_completed": None}
                     for ws in bot.CYCLE_DIGEST_WORKSPACES}
        status_v2 = dict(status_v1)
        status_v2["be"] = {"in_progress": "새 작업", "last_completed": None}

        _r1, sig1 = bot.format_cycle_digest(status_v1)
        _r2, sig2 = bot.format_cycle_digest(status_v2)
        self.assertNotEqual(sig1, sig2)

    def test_mention_in_title_is_sanitized(self) -> None:
        # title 에 @everyone 이 들어가도 Discord mention 으로 발화되면 안 됨.
        status = {
            "be": {
                "in_progress": None,
                "last_completed": {"pr": "#100", "title": "@everyone fire"},
            },
            "fe": {"in_progress": None, "last_completed": None},
            "rev": {"in_progress": None, "last_completed": None},
            "plan": {"in_progress": None, "last_completed": None},
        }
        rendered, _signature = bot.format_cycle_digest(status)
        # zero-width space 가 삽입되어 mention 으로 해석되지 않음.
        self.assertNotIn("@everyone fire", rendered)
        self.assertIn("everyone", rendered)  # 본문은 보존

    def test_long_in_progress_truncated(self) -> None:
        # 한 줄 너무 길면 잘리고 … 표시.
        long_text = "x" * 500
        status = {
            "be": {"in_progress": long_text, "last_completed": None},
            "fe": {"in_progress": None, "last_completed": None},
            "rev": {"in_progress": None, "last_completed": None},
            "plan": {"in_progress": None, "last_completed": None},
        }
        rendered, _signature = bot.format_cycle_digest(status)
        be_line = rendered.split("\n")[1]
        self.assertLessEqual(len(be_line), bot.CYCLE_DIGEST_MAX_LINE_LEN)
        self.assertTrue(be_line.endswith("…"))


if __name__ == "__main__":
    unittest.main()
