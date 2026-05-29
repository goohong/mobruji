"""directive_polish status 필터 회귀 테스트 (#1248, 2026-05-29).

배경:
    2026-05-29 10:39~10:44 KST 사이 nmae 본진에 6+ false positive directive
    polish nmae inject 발생. 매칭 entry 가 모두 ``status=완료`` 였음에도
    ``mark-polished.sh`` + ``_process_directive_polish_queue`` 가 directive-board
    entry status 를 보지 않아 polish + inject 강행.

본 테스트:
    1. ``_directive_board_status_for`` — message_id / source_queue_msg_id /
       thread_id 매칭 + 한국어 정규화 ("✅ 완료" → "완료" / "🔄 진행 중" →
       "진행 중") + 매칭 없음 → None.
    2. ``_process_directive_polish_queue`` — directive-board entry status
       in {완료, 실패} 면 polish 자체 skip + helper-queue entry 가 done +
       skipped_reason 으로 mark + claude polish subprocess 호출 안 함.
    3. status=대기 entry 는 정상 polish 진행 (회귀 없음).
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

# 외부 의존성 stub — test_loop_heartbeat 패턴과 동일.
for missing in ("dotenv",):
    if missing not in sys.modules:
        stub = mock.MagicMock()
        if missing == "dotenv":
            stub.load_dotenv = lambda *a, **kw: None
        sys.modules[missing] = stub

try:
    import discord as _real_discord  # noqa: F401
except ImportError:
    sys.modules["discord"] = mock.MagicMock()

if "requests" not in sys.modules:
    sys.modules["requests"] = mock.MagicMock()

import bot  # noqa: E402


class DirectiveBoardStatusForTests(unittest.TestCase):
    """_directive_board_status_for 매칭 + 정규화 단위 검증."""

    def _write_jsonl(self, entries: list[dict]) -> Path:
        tmp = tempfile.NamedTemporaryFile(
            mode="w", suffix=".jsonl", delete=False, encoding="utf-8",
        )
        for entry in entries:
            tmp.write(json.dumps(entry, ensure_ascii=False) + "\n")
        tmp.close()
        return Path(tmp.name)

    def test_match_by_message_id_completed(self) -> None:
        jsonl_path = self._write_jsonl([
            {"message_id": "M_DONE", "status": "완료"},
        ])
        self.assertEqual(
            bot._directive_board_status_for(jsonl_path, "M_DONE"),
            "완료",
        )

    def test_match_by_thread_id_in_progress(self) -> None:
        jsonl_path = self._write_jsonl([
            {"message_id": "M_OTHER", "thread_id": "T_X", "status": "진행 중"},
        ])
        self.assertEqual(
            bot._directive_board_status_for(jsonl_path, "T_X"),
            "진행 중",
        )

    def test_match_by_source_queue_msg_id_waiting(self) -> None:
        jsonl_path = self._write_jsonl([
            {"message_id": "M_X", "source_queue_msg_id": "Q_Y", "status": "대기"},
        ])
        self.assertEqual(
            bot._directive_board_status_for(jsonl_path, "Q_Y"),
            "대기",
        )

    def test_emoji_prefix_normalization_completed(self) -> None:
        # 일부 backfill entry 가 "✅ 완료" 사용 (jsonl-forum-diff.sh 와 동일 패턴).
        jsonl_path = self._write_jsonl([
            {"message_id": "M_E", "status": "✅ 완료"},
        ])
        self.assertEqual(
            bot._directive_board_status_for(jsonl_path, "M_E"),
            "완료",
        )

    def test_emoji_prefix_normalization_in_progress(self) -> None:
        jsonl_path = self._write_jsonl([
            {"message_id": "M_P", "status": "🔄 진행 중 (rev 보고 후)"},
        ])
        self.assertEqual(
            bot._directive_board_status_for(jsonl_path, "M_P"),
            "진행 중",
        )

    def test_no_match_returns_none(self) -> None:
        jsonl_path = self._write_jsonl([
            {"message_id": "M_OTHER", "status": "대기"},
        ])
        self.assertIsNone(
            bot._directive_board_status_for(jsonl_path, "M_MISSING"),
        )

    def test_missing_file_returns_none(self) -> None:
        self.assertIsNone(
            bot._directive_board_status_for(Path("/nonexistent.jsonl"), "X"),
        )

    def test_empty_id_returns_none(self) -> None:
        # 빈 id 는 매칭 시도 자체 skip — entry 의 빈 키와 정합해서 잘못
        # 매칭되면 안 됨.
        jsonl_path = self._write_jsonl([
            {"message_id": "", "status": "완료"},
        ])
        self.assertIsNone(
            bot._directive_board_status_for(jsonl_path, ""),
        )

    def test_invalid_json_line_skipped(self) -> None:
        tmp = tempfile.NamedTemporaryFile(
            mode="w", suffix=".jsonl", delete=False, encoding="utf-8",
        )
        tmp.write("not-json\n")
        tmp.write(json.dumps({"message_id": "M_VALID", "status": "완료"}) + "\n")
        tmp.close()
        self.assertEqual(
            bot._directive_board_status_for(Path(tmp.name), "M_VALID"),
            "완료",
        )


class ProcessDirectivePolishQueueStatusFilterTests(unittest.TestCase):
    """_process_directive_polish_queue — directive-board status 필터 검증."""

    def setUp(self) -> None:
        self.tmpdir = Path(tempfile.mkdtemp())
        self.queue_path = self.tmpdir / "helper-queue.jsonl"
        self.board_path = self.tmpdir / "directive-board.jsonl"
        # mark_polished_sh / discord_reply_sh 는 .exists() False 분기로 no-op.
        self.mark_polished_sh = self.tmpdir / "mark-polished.sh"
        self.discord_reply_sh = self.tmpdir / "discord-reply.sh"

    def _write_queue(self, tasks: list[dict]) -> None:
        with self.queue_path.open("w", encoding="utf-8") as handle:
            for task in tasks:
                handle.write(json.dumps(task, ensure_ascii=False) + "\n")

    def _write_board(self, entries: list[dict]) -> None:
        with self.board_path.open("w", encoding="utf-8") as handle:
            for entry in entries:
                handle.write(json.dumps(entry, ensure_ascii=False) + "\n")

    def _read_queue(self) -> list[dict]:
        return [
            json.loads(line)
            for line in self.queue_path.read_text(encoding="utf-8").splitlines()
            if line.strip()
        ]

    def test_completed_directive_skip_polish_and_mark_done(self) -> None:
        # #1248 — status=완료 directive 의 polish task → polish skip + queue
        # entry done + skipped_reason 박힘 + claude polish subprocess 호출 X.
        self._write_queue([
            {
                "type": "directive_polish",
                "directive_id": "M_DONE",
                "raw_body": "이미 처리 완료된 directive",
                "ts": "2026-05-29T01:00:00+00:00",
                "status": "pending",
            },
        ])
        self._write_board([
            {"message_id": "M_DONE", "status": "완료"},
        ])

        with mock.patch.object(bot, "_run_claude_polish") as mock_polish:
            bot._process_directive_polish_queue(
                self.queue_path,
                self.mark_polished_sh,
                self.discord_reply_sh,
                self.board_path,
            )

        # claude polish 호출되면 안 됨 (cost + nmae inject 차단).
        mock_polish.assert_not_called()

        # queue entry 상태 갱신 확인.
        entries = self._read_queue()
        self.assertEqual(len(entries), 1)
        self.assertEqual(entries[0]["status"], "done")
        self.assertEqual(
            entries[0]["skipped_reason"], "directive_board_status=완료",
        )
        self.assertIn("polished_at", entries[0])

    def test_failed_directive_skip_polish(self) -> None:
        self._write_queue([
            {
                "type": "directive_polish",
                "directive_id": "M_FAIL",
                "raw_body": "실패 처리된 directive",
                "ts": "2026-05-29T01:00:00+00:00",
                "status": "pending",
            },
        ])
        self._write_board([
            {"message_id": "M_FAIL", "status": "실패"},
        ])

        with mock.patch.object(bot, "_run_claude_polish") as mock_polish:
            bot._process_directive_polish_queue(
                self.queue_path,
                self.mark_polished_sh,
                self.discord_reply_sh,
                self.board_path,
            )

        mock_polish.assert_not_called()
        entries = self._read_queue()
        self.assertEqual(entries[0]["status"], "done")
        self.assertEqual(
            entries[0]["skipped_reason"], "directive_board_status=실패",
        )

    def test_waiting_directive_proceeds_to_polish(self) -> None:
        # status=대기 entry 는 polish 정상 진행 — 회귀 검증.
        self._write_queue([
            {
                "type": "directive_polish",
                "directive_id": "M_WAIT",
                "raw_body": "대기 중 directive",
                "ts": "2026-05-29T01:00:00+00:00",
                "status": "pending",
            },
        ])
        self._write_board([
            {"message_id": "M_WAIT", "status": "대기"},
        ])

        with mock.patch.object(
            bot, "_run_claude_polish", return_value="polished body",
        ) as mock_polish:
            bot._process_directive_polish_queue(
                self.queue_path,
                self.mark_polished_sh,
                self.discord_reply_sh,
                self.board_path,
            )

        # 대기 entry 는 polish 진행 — mock 1회 호출.
        mock_polish.assert_called_once()
        entries = self._read_queue()
        # mark_polished_sh / discord_reply_sh 가 .exists() False 라 forum-edit /
        # mark-polished subprocess 호출은 skip 되지만, polish 자체는 수행 →
        # queue entry status=done + polished_at 박힘.
        self.assertEqual(entries[0]["status"], "done")
        self.assertIn("polished_at", entries[0])
        # skipped_reason 은 안 박혀야 함 (정상 polish 흐름).
        self.assertNotIn("skipped_reason", entries[0])

    def test_in_progress_directive_proceeds_to_polish(self) -> None:
        self._write_queue([
            {
                "type": "directive_polish",
                "directive_id": "M_PROG",
                "raw_body": "진행 중 directive",
                "ts": "2026-05-29T01:00:00+00:00",
                "status": "pending",
            },
        ])
        self._write_board([
            {"message_id": "M_PROG", "status": "진행 중"},
        ])

        with mock.patch.object(
            bot, "_run_claude_polish", return_value="polished body",
        ) as mock_polish:
            bot._process_directive_polish_queue(
                self.queue_path,
                self.mark_polished_sh,
                self.discord_reply_sh,
                self.board_path,
            )

        mock_polish.assert_called_once()

    def test_unknown_directive_board_status_proceeds(self) -> None:
        # directive-board.jsonl 에 매칭 entry 없음 → None → polish 진행
        # (jsonl 미반영 race 보호 — 신규 directive 가 board append 전에 polish
        # queue 에 먼저 들어간 케이스 가드).
        self._write_queue([
            {
                "type": "directive_polish",
                "directive_id": "M_NEW",
                "raw_body": "신규 directive",
                "ts": "2026-05-29T01:00:00+00:00",
                "status": "pending",
            },
        ])
        self._write_board([
            {"message_id": "M_OTHER", "status": "대기"},
        ])

        with mock.patch.object(
            bot, "_run_claude_polish", return_value="polished",
        ) as mock_polish:
            bot._process_directive_polish_queue(
                self.queue_path,
                self.mark_polished_sh,
                self.discord_reply_sh,
                self.board_path,
            )

        mock_polish.assert_called_once()

    def test_directive_board_path_none_backwards_compat(self) -> None:
        # 호출자가 directive_board_path None 전달 시 status 가드 skip + 기존
        # 동작 (모든 pending polish 진행) 유지.
        self._write_queue([
            {
                "type": "directive_polish",
                "directive_id": "M_X",
                "raw_body": "raw",
                "ts": "2026-05-29T01:00:00+00:00",
                "status": "pending",
            },
        ])

        with mock.patch.object(
            bot, "_run_claude_polish", return_value="polished",
        ) as mock_polish:
            bot._process_directive_polish_queue(
                self.queue_path,
                self.mark_polished_sh,
                self.discord_reply_sh,
                None,
            )

        mock_polish.assert_called_once()


if __name__ == "__main__":
    unittest.main()
