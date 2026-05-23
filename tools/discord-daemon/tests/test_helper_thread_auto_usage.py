"""discord-reply.sh `--auto-ack-thread` / `--auto-thread` 모드 단위 테스트 (#947).

PR #891 thread 인프라 위에 helper 본체 룰을 자동화하기 위한 두 신규 모드 검증:

- ``--auto-ack-thread`` — ``--ack`` alias. ack push + thread 생성 + thread_id 를
  ``~/.mobruji/helper-current-thread.txt`` 에 atomic 저장.
- ``--auto-thread`` — 위 파일을 자동 읽어 ``--thread <id>`` 처럼 동작.
  파일 없거나 비어 있으면 graceful skip (exit 0, stderr warning).

테스트는 실제 Discord REST 호출을 우회하기 위해 discord-reply.sh 의 함수만 추출해
bash subshell 에서 직접 평가 (test_discord_resilience.py 와 동일한 패턴).
"""

from __future__ import annotations

import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT_PATH = Path(__file__).resolve().parent.parent / "discord-reply.sh"


def _extract_function(script_text: str, func_name: str) -> str:
    """discord-reply.sh 에서 단일 함수 정의 블록 추출 (brace counter)."""
    start = script_text.find(f"{func_name}()")
    if start < 0:
        raise AssertionError(f"{func_name} 함수 정의를 찾을 수 없음")
    brace_open = script_text.find("{", start)
    depth = 0
    for idx in range(brace_open, len(script_text)):
        ch = script_text[idx]
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return script_text[start:idx + 1]
    raise AssertionError(f"{func_name} 종료 brace 찾기 실패")


def _bash_eval(program: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["bash", "-c", program],
        capture_output=True,
        text=True,
        timeout=5,
    )


# ─────────────────────────────────────────────────────────────────────────────
# Mode dispatch — argument parsing (실제 REST 호출 우회 위해 stub env)
# ─────────────────────────────────────────────────────────────────────────────


class AutoAckThreadModeDispatchTests(unittest.TestCase):
    """``--auto-ack-thread`` 가 ``--ack`` 와 동일하게 MODE=ack 으로 dispatch.

    실제 ack 모드는 Discord REST 호출까지 가므로 여기선 dispatcher 진입까지만
    검증 — 인자 부족 시 (`$# -lt 2`) stderr 메시지에 옵션 이름이 들어가는지 확인.
    """

    def test_auto_ack_thread_requires_ack_message(self) -> None:
        # .env 없이 호출 — env 검증 단계에서 종료. 그래도 인자 부족 stderr 우선.
        # 환경변수로 임시 .env 경로 빈 파일 지정 → env 단계 통과 후 인자 검증 도달.
        with tempfile.TemporaryDirectory() as tmpdir:
            envfile = Path(tmpdir) / ".env"
            envfile.write_text(
                "DISCORD_BOT_TOKEN=dummy-token\n"
                "MOBRUJI_CHANNEL_ID=123\n"
            )
            result = subprocess.run(
                ["bash", str(SCRIPT_PATH), "--auto-ack-thread"],
                env={
                    "DISCORD_DAEMON_ENV_PATH": str(envfile),
                    "PATH": "/usr/bin:/bin",
                    "HOME": tmpdir,
                },
                capture_output=True,
                text=True,
                timeout=5,
            )
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("--auto-ack-thread", result.stderr)
            self.assertIn("ack 문구", result.stderr)

    def test_usage_lists_both_auto_modes(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            envfile = Path(tmpdir) / ".env"
            envfile.write_text(
                "DISCORD_BOT_TOKEN=dummy-token\n"
                "MOBRUJI_CHANNEL_ID=123\n"
            )
            # 인자 0 — usage 출력.
            result = subprocess.run(
                ["bash", str(SCRIPT_PATH)],
                env={
                    "DISCORD_DAEMON_ENV_PATH": str(envfile),
                    "PATH": "/usr/bin:/bin",
                    "HOME": tmpdir,
                },
                capture_output=True,
                text=True,
                timeout=5,
            )
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("--auto-ack-thread", result.stderr)
            self.assertIn("--auto-thread", result.stderr)


# ─────────────────────────────────────────────────────────────────────────────
# --auto-thread mode — graceful skip 시나리오 (실제 REST 호출 X)
# ─────────────────────────────────────────────────────────────────────────────


class AutoThreadGracefulSkipTests(unittest.TestCase):
    """``--auto-thread`` 가 thread 파일 없거나 비어 있을 때 graceful skip.

    파일 없거나 비어 있으면 Discord REST 호출 자체를 안 하고 exit 0 + stderr
    warning. helper turn 안 깨지게 하는 것이 핵심.
    """

    def _run_auto_thread(
        self, thread_file: Path, message: str
    ) -> subprocess.CompletedProcess[str]:
        with tempfile.TemporaryDirectory() as tmpdir:
            envfile = Path(tmpdir) / ".env"
            envfile.write_text(
                "DISCORD_BOT_TOKEN=dummy-token\n"
                "MOBRUJI_CHANNEL_ID=123\n"
            )
            return subprocess.run(
                ["bash", str(SCRIPT_PATH), "--auto-thread", message],
                env={
                    "DISCORD_DAEMON_ENV_PATH": str(envfile),
                    "HELPER_THREAD_FILE": str(thread_file),
                    "PATH": "/usr/bin:/bin",
                },
                capture_output=True,
                text=True,
                timeout=5,
            )

    def test_auto_thread_skips_when_file_missing(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            missing = Path(tmpdir) / "no-such-file.txt"
            result = self._run_auto_thread(missing, "progress 1줄")
            self.assertEqual(
                result.returncode,
                0,
                f"파일 없을 때 graceful skip 실패: stderr={result.stderr}",
            )
            self.assertIn("없음", result.stderr)
            self.assertIn("auto-thread skip", result.stderr)

    def test_auto_thread_skips_when_file_empty(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            empty = Path(tmpdir) / "empty.txt"
            empty.write_text("")
            result = self._run_auto_thread(empty, "progress 1줄")
            self.assertEqual(
                result.returncode,
                0,
                f"파일 비어 있을 때 graceful skip 실패: stderr={result.stderr}",
            )
            self.assertIn("비어 있음", result.stderr)


# ─────────────────────────────────────────────────────────────────────────────
# atomic_write_thread_file — --auto-ack-thread 가 사용하는 캐시 파일 동작
# ─────────────────────────────────────────────────────────────────────────────


class AutoAckThreadCachesThreadIdTests(unittest.TestCase):
    """``--auto-ack-thread`` (== ``--ack``) 가 thread_id 를 파일에 저장하는지.

    Discord REST 까지 가지 않고 ``atomic_write_thread_file`` 함수만 추출해 직접
    호출 — 모드 alias 가 동일 코드 경로를 타는지 확인.
    """

    def test_atomic_write_thread_file_persists_id(self) -> None:
        script_text = SCRIPT_PATH.read_text(encoding="utf-8")
        func_block = _extract_function(script_text, "atomic_write_thread_file")
        with tempfile.TemporaryDirectory() as tmpdir:
            target = Path(tmpdir) / "helper-current-thread.txt"
            program = (
                "set -euo pipefail\n"
                + func_block
                + f"\natomic_write_thread_file '9999888877776666' '{target}'\n"
            )
            result = _bash_eval(program)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertTrue(target.exists())
            self.assertEqual(target.read_text(), "9999888877776666\n")


if __name__ == "__main__":
    unittest.main()
