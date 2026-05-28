"""worker.py 디스패치 단위 테스트.

worker 가 큐에서 꺼낸 작업을 nmae pane 으로 thread-aware 하게 전달하고,
전달 성공/실패에 따라 task 상태를 갱신하는지 검증한다. tmux / claude 실제
실행 없이 mock 으로만 확인.
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path
from unittest import mock

THIS_DIR = Path(__file__).resolve().parent
ROOT = THIS_DIR.parent
sys.path.insert(0, str(ROOT))

# dotenv 미설치 환경 대비 stub (bot import 가 load_dotenv 를 참조).
if "dotenv" not in sys.modules:
    stub = mock.MagicMock()
    stub.load_dotenv = lambda *a, **kw: None
    sys.modules["dotenv"] = stub

import worker  # noqa: E402


class FormatWorkerInjectTests(unittest.TestCase):
    def test_includes_task_thread_and_text(self) -> None:
        task = {"task_id": 7, "thread_id": "1506925497651560458", "payload": {"text": "PR #790 머지 해줘"}}
        out = worker.format_worker_inject(task)
        self.assertIn("task #7", out)
        self.assertIn("discord-thread 1506925497651560458", out)
        self.assertIn("PR #790 머지 해줘", out)

    def test_missing_text_is_blank(self) -> None:
        task = {"task_id": 1, "thread_id": "999", "payload": {}}
        out = worker.format_worker_inject(task)
        self.assertIn("task #1", out)
        self.assertIn("discord-thread 999", out)


class ProcessTaskTests(unittest.TestCase):
    def _task(self) -> dict:
        return {"task_id": 42, "thread_id": "999", "payload": {"text": "hi"}}

    def test_success_marks_working(self) -> None:
        ledger = mock.MagicMock()
        with mock.patch.object(worker, "ensure_tmux_session", return_value=True), \
             mock.patch.object(worker, "tmux_send_payload", return_value=True) as send:
            worker.process_task(ledger, self._task())
        # nmae pane 으로 thread-aware 프롬프트 전달.
        sent_text = send.call_args.args[1]
        self.assertIn("task #42", sent_text)
        ledger.update_task_status.assert_called_once()
        self.assertEqual(ledger.update_task_status.call_args.args[1], "WORKING")

    def test_failure_marks_failed(self) -> None:
        ledger = mock.MagicMock()
        with mock.patch.object(worker, "ensure_tmux_session", return_value=True), \
             mock.patch.object(worker, "tmux_send_payload", return_value=False):
            worker.process_task(ledger, self._task())
        self.assertEqual(ledger.update_task_status.call_args.args[1], "FAILED")


if __name__ == "__main__":
    unittest.main()
