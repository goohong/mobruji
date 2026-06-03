"""directive_status.sh + discord-reply.sh (--update-status / --forum-state-dump)
pytest 단위 (PR #1129 impl PR 2).

spec: docs/features/directive-board-event-driven-redesign.md §3 (b) (c) + §4.

검증 5건 (spec 명시):
  1. status valid (in_progress / completed) — exit 0, jsonl 갱신
  2. status invalid (blocked / cancelled) — exit 1, jsonl 무변동
  3. id 미존재 — exit 1, jsonl 무변동
  4. 멱등 (같은 status 재호출) — exit 0, discord-reply 호출 0회
  5. forum-retag + update-status atomic 호출 — capture file 에 두 호출 모두 기록

shell test (test_directive_status.sh) 가 더 dense 한 케이스 (서브 케이스 포함 8 케이스
37 assertion) 를 다루므로 본 pytest 는 spec §3 (b)/(c) 의 핵심 invariant 만 회귀
가드한다. fake discord-reply.sh + jsonl fixture 를 tmp_path 에 작성 후 subprocess 로
directive_status.sh 호출 → exit code / jsonl 상태 / discord 호출 캡처 assert.
"""

from __future__ import annotations

import hashlib
import json
import shutil
import subprocess
import unittest
from pathlib import Path


SCRIPT_DIR = Path(__file__).resolve().parent.parent
DIRECTIVE_STATUS = SCRIPT_DIR / "directive_status.sh"


def _write_fake_discord_reply(tmpdir: Path, capture: Path) -> Path:
    """fake discord-reply.sh — 호출 매개 변수를 capture file 에 append."""
    bin_path = tmpdir / "discord-reply.sh"
    bin_path.write_text(
        f"""#!/usr/bin/env bash
printf '%s\\n' "$*" >> "{capture}"
exit 0
"""
    )
    bin_path.chmod(0o755)
    return bin_path


def _write_jsonl_fixture(jsonl_path: Path) -> None:
    """jsonl 초기 fixture — 3 entry (대기 / 진행 중 / 완료)."""
    entries = [
        {
            "ts": "2026-05-27 10:00 KST",
            "summary": "테스트 directive 1",
            "status": "대기",
            "message_id": "msg-001",
            "last_updated_kst": "2026-05-27 10:00 KST",
            "source_queue_msg_id": "msg-001",
            "thread_id": "thread-A",
        },
        {
            "ts": "2026-05-27 10:30 KST",
            "summary": "테스트 directive 2",
            "status": "진행 중",
            "message_id": "msg-002",
            "last_updated_kst": "2026-05-27 10:30 KST",
            "source_queue_msg_id": "msg-002",
            "thread_id": "thread-B",
        },
        {
            "ts": "2026-05-27 11:00 KST",
            "summary": "테스트 directive 3",
            "status": "완료",
            "message_id": "msg-003",
            "last_updated_kst": "2026-05-27 11:00 KST",
            "source_queue_msg_id": "msg-003",
            "thread_id": "thread-C",
            "completed_kst": "2026-05-27 11:30 KST",
        },
    ]
    jsonl_path.write_text(
        "".join(json.dumps(e, ensure_ascii=False) + "\n" for e in entries)
    )


def _run_status(
    tmpdir: Path,
    jsonl_path: Path,
    *args: str,
) -> subprocess.CompletedProcess[str]:
    """subprocess 로 directive_status.sh 호출."""
    capture = tmpdir / "capture.txt"
    if not capture.exists():
        capture.touch()
    bin_path = tmpdir / "discord-reply.sh"
    if not bin_path.exists():
        _write_fake_discord_reply(tmpdir, capture)
    return subprocess.run(
        ["bash", str(DIRECTIVE_STATUS), *args],
        env={
            "PATH": "/usr/bin:/bin",
            "HOME": str(tmpdir),
            "DIRECTIVE_BOARD_JSONL_PATH": str(jsonl_path),
            "DISCORD_REPLY_BIN": str(bin_path),
        },
        capture_output=True,
        text=True,
    )


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _read_entries(jsonl_path: Path) -> list[dict]:
    return [
        json.loads(line)
        for line in jsonl_path.read_text().splitlines()
        if line.strip()
    ]


# (#1449/#1425) directive_status.sh 가 jsonl 원자적 쓰기에 `flock`(util-linux) 사용 —
# Linux/NCP·CI 엔 있으나 macOS 엔 미설치라 rc=127. 환경 의존이지 코드 결함 아님 →
# flock 없는 환경(mac dev)에선 skip, Linux 에선 정상 실행.
@unittest.skipUnless(shutil.which("flock") is not None, "flock 미설치 — Linux 전용 (mac dev skip)")
class DirectiveStatusTest(unittest.TestCase):
    def setUp(self) -> None:
        # tmp_path 는 pytest 전용 — unittest 호환 위해 tempfile.TemporaryDirectory 사용.
        import tempfile

        self._tmp = tempfile.TemporaryDirectory()
        self.tmpdir = Path(self._tmp.name)
        self.jsonl = self.tmpdir / "directive-board.jsonl"
        _write_jsonl_fixture(self.jsonl)
        self.capture = self.tmpdir / "capture.txt"
        self.capture.touch()
        _write_fake_discord_reply(self.tmpdir, self.capture)

    def tearDown(self) -> None:
        self._tmp.cleanup()

    # ── case 1: status valid (in_progress / completed) ────────────────────────
    def test_status_valid_in_progress(self) -> None:
        result = _run_status(self.tmpdir, self.jsonl, "msg-001", "in_progress")
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        entries = _read_entries(self.jsonl)
        msg001 = next(e for e in entries if e["message_id"] == "msg-001")
        self.assertEqual(msg001["status"], "진행 중")
        capture_text = self.capture.read_text()
        self.assertIn("forum-retag thread-A directive", capture_text)
        self.assertIn("update-status thread-A 진행 중", capture_text)

    def test_status_valid_completed_with_pr(self) -> None:
        pr_url = "https://github.com/owner/repo/pull/9999"
        result = _run_status(
            self.tmpdir, self.jsonl, "msg-001", "completed", pr_url
        )
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        msg001 = next(
            e for e in _read_entries(self.jsonl) if e["message_id"] == "msg-001"
        )
        self.assertEqual(msg001["status"], "완료")
        self.assertIn("completed_kst", msg001)
        self.assertEqual(msg001["related_pr"], pr_url)
        self.assertIn(pr_url, self.capture.read_text())

    # ── case 2: status invalid 거부 ──────────────────────────────────────────
    def test_status_invalid_rejected(self) -> None:
        before = _sha256(self.jsonl)
        for invalid in ("blocked", "cancelled", "completed_wrong", "", "TODO"):
            result = _run_status(
                self.tmpdir, self.jsonl, "msg-001", invalid
            )
            self.assertNotEqual(
                result.returncode,
                0,
                msg=f"invalid status {invalid!r} 가 거부되지 않음: {result.stderr}",
            )
            self.assertEqual(
                _sha256(self.jsonl),
                before,
                msg=f"invalid status {invalid!r} 호출에서 jsonl 변경됨",
            )
        # discord-reply 미호출 (capture 비어 있음).
        self.assertEqual(self.capture.read_text().strip(), "")

    # ── case 3: id 미존재 거부 ──────────────────────────────────────────────
    def test_id_missing_rejected(self) -> None:
        before = _sha256(self.jsonl)
        result = _run_status(
            self.tmpdir, self.jsonl, "msg-999-nonexistent", "in_progress"
        )
        self.assertEqual(result.returncode, 1, msg=result.stderr)
        self.assertEqual(_sha256(self.jsonl), before)
        self.assertEqual(self.capture.read_text().strip(), "")

    # ── case 4: 멱등 (같은 status 재호출 no-op) ──────────────────────────────
    def test_idempotent_same_status(self) -> None:
        # msg-002 는 fixture 에서 "진행 중" — in_progress 재호출 = no-op.
        result = _run_status(
            self.tmpdir, self.jsonl, "msg-002", "in_progress"
        )
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        self.assertEqual(self.capture.read_text().strip(), "")

        # msg-003 는 "완료" — completed 재호출 = no-op.
        result = _run_status(self.tmpdir, self.jsonl, "msg-003", "completed")
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        self.assertEqual(self.capture.read_text().strip(), "")

    # ── case 5: forum-retag + update-status atomic 호출 ──────────────────────
    def test_forum_atomic_calls_in_order(self) -> None:
        # thread_id 로 매칭 — spec §3-b 는 msg_id_or_thread_id 둘 다 허용.
        result = _run_status(
            self.tmpdir, self.jsonl, "thread-A", "in_progress"
        )
        self.assertEqual(result.returncode, 0, msg=result.stderr)

        lines = [
            line for line in self.capture.read_text().splitlines() if line
        ]
        retag_calls = [i for i, line in enumerate(lines) if "forum-retag" in line]
        update_calls = [
            i for i, line in enumerate(lines) if "update-status" in line
        ]
        self.assertEqual(
            len(retag_calls), 1, msg=f"forum-retag 호출 1회 기대, 실제 {len(retag_calls)}회"
        )
        self.assertEqual(
            len(update_calls),
            1,
            msg=f"update-status 호출 1회 기대, 실제 {len(update_calls)}회",
        )
        # 호출 순서: retag 먼저, update-status 다음 (spec §3-b 의 atomic 순서).
        self.assertLess(retag_calls[0], update_calls[0])

        # thread_id 매칭으로 jsonl entry 의 status 도 변경됐는지.
        msg001 = next(
            e for e in _read_entries(self.jsonl) if e["thread_id"] == "thread-A"
        )
        self.assertEqual(msg001["status"], "진행 중")


if __name__ == "__main__":
    unittest.main()
