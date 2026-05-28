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
            # #963: error message 가 "ack 문구" → "문구" 로 단순화됨 (ack 의미 분리).
            self.assertIn("문구가 필요합니다", result.stderr)

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


# ─────────────────────────────────────────────────────────────────────────────
# --auto-thread fallback chain (#1021 launch thread file passthrough)
# ─────────────────────────────────────────────────────────────────────────────


class AutoThreadFallbackChainTests(unittest.TestCase):
    """``--auto-thread`` resolve chain 우선순위 검증 (#1021).

    우선순위 (높음 → 낮음):
        1. ``LAUNCH_THREAD_ID`` env (sub-agent inherit)
        2. ``LAUNCH_THREAD_FILE`` (~/.mobruji/last-launch-thread.txt)
        3. ``HELPER_THREAD_FILE`` (~/.mobruji/helper-current-thread.txt)

    각 소스 snowflake (17–20 digit) 검증 → 실패 시 다음 fallback.
    실제 REST 호출은 우회하기 위해 invalid snowflake 만 넣어 skip 경로 확인 +
    각 소스가 stderr 메시지에 reflect 되는지로 chain 진행 단계를 식별.
    """

    def _run(
        self,
        *,
        launch_env: str | None = None,
        launch_file_content: str | None = None,
        helper_file_content: str | None = None,
        invalid_helper: bool = False,
    ) -> subprocess.CompletedProcess[str]:
        """auto-thread 호출. 각 소스 옵션:

        - launch_env: LAUNCH_THREAD_ID 환경변수 값 (None 이면 unset)
        - launch_file_content: LAUNCH_THREAD_FILE 내용 (None 이면 파일 미존재)
        - helper_file_content: HELPER_THREAD_FILE 내용 (None 이면 파일 미존재)
        - invalid_helper: True 면 helper file 도 invalid 값 — 모든 fallback 실패
        """
        with tempfile.TemporaryDirectory() as tmpdir:
            envfile = Path(tmpdir) / ".env"
            envfile.write_text(
                "DISCORD_BOT_TOKEN=dummy-token\n"
                "MOBRUJI_CHANNEL_ID=123\n"
            )
            launch_file = Path(tmpdir) / "last-launch-thread.txt"
            helper_file = Path(tmpdir) / "helper-current-thread.txt"
            if launch_file_content is not None:
                launch_file.write_text(launch_file_content)
            if helper_file_content is not None:
                helper_file.write_text(helper_file_content)
            env: dict[str, str] = {
                "DISCORD_DAEMON_ENV_PATH": str(envfile),
                "LAUNCH_THREAD_FILE": str(launch_file),
                "HELPER_THREAD_FILE": str(helper_file),
                "PATH": "/usr/bin:/bin",
            }
            if launch_env is not None:
                env["LAUNCH_THREAD_ID"] = launch_env
            return subprocess.run(
                ["bash", str(SCRIPT_PATH), "--auto-thread", "milestone"],
                env=env,
                capture_output=True,
                text=True,
                timeout=5,
            )

    def test_invalid_env_falls_through_to_file(self) -> None:
        """LAUNCH_THREAD_ID env 가 invalid (짧은 정수) 면 파일 fallback 으로 진행.

        env 값이 4 자리 → validate_snowflake 실패 → stderr 에 "LAUNCH_THREAD_ID env"
        label 포함. 그 후 launch file (역시 invalid) 시도 → stderr 에 launch file
        path label 포함. 마지막 helper file 미존재 → skip 메시지.
        """
        result = self._run(launch_env="9999", launch_file_content="invalid-id\n")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("LAUNCH_THREAD_ID env", result.stderr)
        self.assertIn("last-launch-thread.txt", result.stderr)
        self.assertIn("auto-thread skip", result.stderr)

    def test_launch_file_used_when_env_missing(self) -> None:
        """env 없고 launch file 만 invalid → launch file label 이 stderr 에 나옴.

        chain 이 launch file 까지 도달했음을 입증.
        """
        result = self._run(launch_file_content="bad-id\n")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("last-launch-thread.txt", result.stderr)
        # env 는 unset 이므로 env label 은 stderr 에 안 나옴.
        self.assertNotIn("LAUNCH_THREAD_ID env", result.stderr)

    def test_helper_file_used_when_launch_missing(self) -> None:
        """env / launch file 둘 다 없을 때 helper file 로 fallback.

        helper file 은 기존 호환을 위해 lenient (snowflake 검증 X). 실제 Discord
        REST 호출은 stub 안 됐으므로 비-2xx 응답 → "auto-thread push 실패" 경로
        도달. 핵심은 chain 이 helper file 까지 도달했음 입증 (재시도 메시지 포함).
        """
        result = self._run(helper_file_content="not-a-snowflake\n")
        self.assertEqual(result.returncode, 0, result.stderr)
        # helper file 까지 도달 → push 실패 분기 (실제 호출 stub 안 됨 → 404 등).
        # stderr 에 push 실패 또는 retry 메시지가 보이면 chain 진입 입증.
        self.assertTrue(
            "auto-thread push 실패" in result.stderr
            or "retry" in result.stderr.lower(),
            f"helper file fallback chain 미진입: stderr={result.stderr}",
        )

    def test_all_sources_missing_skips(self) -> None:
        """env / launch file / helper file 모두 없으면 skip (helper file 없음 메시지)."""
        result = self._run()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("auto-thread skip", result.stderr)


# ─────────────────────────────────────────────────────────────────────────────
# --auto-ack-thread 가 LAUNCH_THREAD_FILE 도 함께 atomic write 하는지 (#1021)
# ─────────────────────────────────────────────────────────────────────────────


class AutoAckThreadWritesLaunchFileTests(unittest.TestCase):
    """``--auto-ack-thread`` 가 thread_id 를 HELPER + LAUNCH 두 파일에 동시 write.

    구현: ack mode 안 atomic_write_thread_file 호출 2회 (HELPER_THREAD_FILE +
    LAUNCH_THREAD_FILE). 실제 REST 호출은 우회하기 어려우므로 script 자체에서
    두 파일 경로 변수가 모두 atomic_write_thread_file 호출 대상에 포함되는지
    텍스트 기반으로 검증.
    """

    def test_script_writes_both_files_in_ack_mode(self) -> None:
        script_text = SCRIPT_PATH.read_text(encoding="utf-8")
        # ack mode block 안에서 두 변수 모두 atomic write 의 인자로 등장해야 함.
        self.assertIn(
            'atomic_write_thread_file "$NEW_THREAD_ID" "$HELPER_THREAD_FILE"',
            script_text,
        )
        self.assertIn(
            'atomic_write_thread_file "$NEW_THREAD_ID" "$LAUNCH_THREAD_FILE"',
            script_text,
        )

    def test_launch_thread_file_default_path(self) -> None:
        """LAUNCH_THREAD_FILE default = ~/.mobruji/last-launch-thread.txt."""
        script_text = SCRIPT_PATH.read_text(encoding="utf-8")
        self.assertIn(
            'LAUNCH_THREAD_FILE="${LAUNCH_THREAD_FILE:-${HOME:-/tmp}/.mobruji/last-launch-thread.txt}"',
            script_text,
        )


if __name__ == "__main__":
    unittest.main()
