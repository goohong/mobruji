"""Discord API resilience 묶음 C 단위 테스트 (#911).

대상:
- **G-5 (thread file race)**: ``discord-reply.sh`` 의 ``HELPER_THREAD_FILE`` 쓰기가
  ``mktemp + mv`` atomic 패턴으로 동작해, 동시 두 writer 가 부분 파일을 남기지
  않는지 검증.
- **G-6 (Discord 429/5xx retry)**: ``bot.send_with_retry`` 가 ``retry_after`` 를
  존중하고, 5xx 에서 exponential backoff 하고, 4xx 에서 즉시 포기하며,
  네트워크 transient 예외 시 retry 후 graceful 종료하는지 검증.

테스트는 외부 네트워크 호출 없음. ``discord.HTTPException`` / ``channel.send`` 는
stub 으로 대체.
"""

from __future__ import annotations

import asyncio
import os
import subprocess
import sys
import tempfile
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from unittest import mock

PARENT_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PARENT_DIR))

# discord stub — HTTPException 은 status / retry_after 속성을 그대로 가지는 단순
# 예외로 흉내. 실제 discord.py 가 import 가능하면 진짜를 쓰고, 없으면 stub.
try:
    import discord as _real_discord  # noqa: F401
except ImportError:
    _stub_discord = mock.MagicMock()

    class _StubHTTPException(Exception):
        def __init__(self, status: int, retry_after: float | None = None):
            super().__init__(f"HTTP {status}")
            self.status = status
            self.retry_after = retry_after

    _stub_discord.HTTPException = _StubHTTPException
    sys.modules["discord"] = _stub_discord

for missing in ("requests", "dotenv"):
    if missing not in sys.modules:
        stub = mock.MagicMock()
        if missing == "dotenv":
            stub.load_dotenv = lambda *a, **kw: None
        sys.modules[missing] = stub

import bot  # noqa: E402


# 실제 discord.py 가 있어도 HTTPException 시그니처 다양성 (status/retry_after
# 만 사용) 만 의존하면 되도록 가벼운 wrapper 사용.
def _make_http_exception(status: int, retry_after: float | None = None) -> Exception:
    """bot.send_with_retry 가 보는 discord.HTTPException 인스턴스 생성.

    실제 discord.HTTPException 은 (response, message) 시그니처라 직접 만들기
    번거롭다. 테스트 한정으로 동일 base 의 subclass 를 만들어 status /
    retry_after attr 만 세팅.
    """

    class _TestHTTPException(bot.discord.HTTPException):
        def __init__(self, status_code: int, retry_after_sec: float | None):
            # base init 호출 회피 (real discord.HTTPException 시그니처가 무겁다).
            Exception.__init__(self, f"HTTP {status_code}")
            self.status = status_code
            self.retry_after = retry_after_sec

    return _TestHTTPException(status, retry_after)


# ─────────────────────────────────────────────────────────────────────────────
# G-5: discord-reply.sh thread file atomic write
# ─────────────────────────────────────────────────────────────────────────────


class HelperThreadFileAtomicWriteTests(unittest.TestCase):
    """discord-reply.sh 의 thread file 쓰기 race 검증 (#911 G-5).

    discord-reply.sh 의 ``atomic_write_thread_file`` 함수만 source 해 호출.
    Discord REST 호출 우회 — env 검증 단계 진입 전 함수만 추출해 단위 검증.
    """

    SCRIPT_PATH = Path(__file__).resolve().parent.parent / "discord-reply.sh"

    def _bash_eval_atomic_write(self, thread_id: str, target: Path) -> None:
        """discord-reply.sh 안 atomic_write_thread_file 만 골라 실행.

        스크립트 본체는 .env 파싱 → mode dispatch 로 진입하므로 직접 source
        못 함. 대신 함수 정의 부분만 정규식으로 잘라 bash subshell 에서 평가.
        """
        script_text = self.SCRIPT_PATH.read_text(encoding="utf-8")
        # `atomic_write_thread_file()` 함수 블록 추출.
        start = script_text.find("atomic_write_thread_file()")
        self.assertGreater(start, 0, "atomic_write_thread_file 함수 정의를 찾을 수 없음")
        # 단순 brace counter — 첫 `{` 부터 매칭되는 `}` 까지.
        brace_open = script_text.find("{", start)
        depth = 0
        end = -1
        for idx in range(brace_open, len(script_text)):
            ch = script_text[idx]
            if ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
                if depth == 0:
                    end = idx + 1
                    break
        self.assertGreater(end, 0, "함수 종료 brace 찾기 실패")
        func_block = script_text[start:end]
        # subshell 에 함수 정의 + 호출 inject.
        bash_program = (
            "set -euo pipefail\n"
            + func_block
            + f"\natomic_write_thread_file '{thread_id}' '{target}'\n"
        )
        result = subprocess.run(
            ["bash", "-c", bash_program],
            capture_output=True,
            text=True,
            timeout=5,
        )
        self.assertEqual(
            result.returncode,
            0,
            f"atomic_write_thread_file 실행 실패: stderr={result.stderr}",
        )

    def test_atomic_write_creates_file_with_trailing_newline(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            target = Path(tmpdir) / "helper-current-thread.txt"
            self._bash_eval_atomic_write("123456789", target)
            self.assertTrue(target.exists())
            self.assertEqual(target.read_text(), "123456789\n")

    def test_atomic_write_overwrites_existing(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            target = Path(tmpdir) / "helper-current-thread.txt"
            target.write_text("old-id\n")
            self._bash_eval_atomic_write("new-id-999", target)
            self.assertEqual(target.read_text(), "new-id-999\n")

    def test_atomic_write_creates_parent_directory(self) -> None:
        # mkdir -p 보장: 부모 디렉터리 없을 때도 생성.
        with tempfile.TemporaryDirectory() as tmpdir:
            target = Path(tmpdir) / "nested" / "dir" / "thread.txt"
            self.assertFalse(target.parent.exists())
            self._bash_eval_atomic_write("abc", target)
            self.assertTrue(target.exists())
            self.assertEqual(target.read_text(), "abc\n")

    def test_concurrent_writes_produce_complete_file(self) -> None:
        """동시 N 회 호출 시 최종 파일이 항상 완전한 thread id 한 줄.

        race 가 깨졌다면 (e.g. `printf > FILE` 직접 사용) reader 가 부분 내용을
        볼 수 있다. mktemp + mv 패턴에서는 마지막 mv 가 atomic 이므로 reader 가
        보는 내용은 항상 어떤 writer 의 완전한 thread id.
        """
        with tempfile.TemporaryDirectory() as tmpdir:
            target = Path(tmpdir) / "thread.txt"
            ids = [f"thread-{i:04d}" for i in range(20)]
            with ThreadPoolExecutor(max_workers=8) as pool:
                futures = [
                    pool.submit(self._bash_eval_atomic_write, tid, target)
                    for tid in ids
                ]
                for fut in futures:
                    fut.result()
            self.assertTrue(target.exists())
            # 마지막 내용은 ids 중 하나여야 함 (부분 파일 아님).
            content = target.read_text().rstrip("\n")
            self.assertIn(content, ids, f"부분 파일 / 깨진 내용 감지: {content!r}")


# ─────────────────────────────────────────────────────────────────────────────
# G-6: bot.send_with_retry — 429 / 5xx / 4xx / transient
# ─────────────────────────────────────────────────────────────────────────────


class _FakeChannel:
    """``channel.send`` 호출 history 와 raise 시나리오를 컨트롤하는 stub."""

    def __init__(self, responses: list[object]):
        # responses 의 각 원소: None 이면 success, Exception 이면 raise.
        self._responses = list(responses)
        self.calls: list[dict[str, object]] = []

    async def send(self, content: str | None = None, embed: object | None = None) -> None:
        self.calls.append({"content": content, "embed": embed})
        if not self._responses:
            return
        next_resp = self._responses.pop(0)
        if isinstance(next_resp, BaseException):
            raise next_resp
        # 외 None / 그 외 → success


class SendWithRetryTests(unittest.TestCase):
    """bot.send_with_retry 동작 검증 (#911 G-6)."""

    def setUp(self) -> None:
        # 테스트 sleep 은 즉시 통과 — 실시간 backoff 기다리지 않음.
        self.slept: list[float] = []

        async def _no_sleep(sec: float) -> None:
            self.slept.append(sec)

        self.sleeper = _no_sleep

    def _run(self, channel, **kwargs) -> bool:
        return asyncio.run(
            bot.send_with_retry(channel, sleeper=self.sleeper, **kwargs)
        )

    def test_first_attempt_success_returns_true(self) -> None:
        channel = _FakeChannel(responses=[None])
        ok = self._run(channel, content="hello")
        self.assertTrue(ok)
        self.assertEqual(len(channel.calls), 1)
        self.assertEqual(self.slept, [])

    def test_429_uses_retry_after_then_succeeds(self) -> None:
        channel = _FakeChannel(
            responses=[
                _make_http_exception(429, retry_after=2.5),
                None,  # 2번째 시도 성공.
            ]
        )
        ok = self._run(channel, content="x")
        self.assertTrue(ok)
        self.assertEqual(len(channel.calls), 2)
        self.assertEqual(self.slept, [2.5])

    def test_429_without_retry_after_uses_base_sleep(self) -> None:
        channel = _FakeChannel(
            responses=[_make_http_exception(429, retry_after=None), None]
        )
        ok = self._run(channel, content="x", base_sleep=0.7)
        self.assertTrue(ok)
        self.assertEqual(self.slept, [0.7])

    def test_5xx_exponential_backoff(self) -> None:
        # 1, 2, 4 초 — base=1, attempts 1,2,3.
        channel = _FakeChannel(
            responses=[
                _make_http_exception(500),
                _make_http_exception(503),
                None,
            ]
        )
        ok = self._run(channel, content="x", base_sleep=1.0)
        self.assertTrue(ok)
        self.assertEqual(self.slept, [1.0, 2.0])

    def test_4xx_returns_false_without_retry(self) -> None:
        # 400 같은 client error 는 retry 무의미 → 즉시 False, sleep 없음.
        channel = _FakeChannel(responses=[_make_http_exception(403)])
        ok = self._run(channel, content="x")
        self.assertFalse(ok)
        self.assertEqual(len(channel.calls), 1)
        self.assertEqual(self.slept, [])

    def test_max_attempts_exhausted_returns_false(self) -> None:
        channel = _FakeChannel(
            responses=[
                _make_http_exception(503),
                _make_http_exception(503),
                _make_http_exception(503),
            ]
        )
        ok = self._run(channel, content="x", max_attempts=3, base_sleep=0.1)
        self.assertFalse(ok)
        self.assertEqual(len(channel.calls), 3)
        # 마지막 시도 후엔 sleep 안 함 — 2번 sleep.
        self.assertEqual(len(self.slept), 2)

    def test_transient_network_error_retries_and_succeeds(self) -> None:
        # discord.HTTPException 외 일반 Exception (ConnectionError 등) → backoff.
        channel = _FakeChannel(
            responses=[
                ConnectionError("network blip"),
                None,
            ]
        )
        ok = self._run(channel, content="x", base_sleep=0.5)
        self.assertTrue(ok)
        self.assertEqual(self.slept, [0.5])

    def test_embed_passed_through(self) -> None:
        # embed 인자가 channel.send 까지 전달.
        sentinel_embed = object()
        channel = _FakeChannel(responses=[None])
        ok = self._run(channel, embed=sentinel_embed)
        self.assertTrue(ok)
        self.assertIs(channel.calls[0]["embed"], sentinel_embed)


if __name__ == "__main__":
    unittest.main()
