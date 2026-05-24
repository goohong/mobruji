"""discord-reply.sh `--directive-board` / `--update-status` mode 라우팅 테스트 (#1039).

배경 (2026-05-24 사용자 지시 백로그 채널):
    사용자 명시: "여기에 답하지 말고 별도의 지시 백로그? 모아두는 채널 (이름은
    너가 정해) 를 파서. 거기에 추가하고, 예를들어 ~하는 작업 (언제언제 지시)
    하고 그 아래에 스레드 파서 진행상황을 적어줘". helper 결정 채널 이름
    `#모부르지-지시` (env `DIRECTIVE_BOARD_CHANNEL_ID`).

본 테스트는 외부 Discord REST 호출을 fake curl 로 대체 — payload / URL /
jsonl append 결과를 캡처해 채널 라우팅 + 본문 포맷 + jsonl write 가 의도대로인지
검증.
"""

from __future__ import annotations

import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT_PATH = (
    Path(__file__).resolve().parent.parent / "discord-reply.sh"
)


def _write_fake_curl(
    tmpdir: str,
    url_capture: str,
    payload_capture: str,
    method_capture: str | None = None,
    response_body: str = '{"id": "99999"}',
) -> None:
    """payload + URL + (옵션) method 동시 캡처용 fake curl.

    discord_curl_with_retry 가 `-w '\\n%{http_code}'` 로 status code 를 기대하므로
    fake curl 도 `{...}\\n200` 형태로 응답.

    `--update-status` 가 PATCH 호출을 하므로 method 도 캡처.
    """
    method_line = (
        f"printf '%s\\n' \"$METHOD\" >> {method_capture}\n"
        if method_capture
        else ""
    )
    fake_curl = Path(tmpdir) / "curl"
    fake_curl.write_text(
        "#!/usr/bin/env bash\n"
        "URL=\"\"\n"
        "PAYLOAD=\"\"\n"
        "METHOD=\"GET\"\n"
        "while [[ $# -gt 0 ]]; do\n"
        "  case \"$1\" in\n"
        "    -X) shift; METHOD=\"$1\";;\n"
        "    -d) shift; PAYLOAD=\"$1\";;\n"
        "    http*) URL=\"$1\";;\n"
        "  esac\n"
        "  shift\n"
        "done\n"
        f"printf '%s\\n' \"$URL\" >> {url_capture}\n"
        f"printf '%s\\n' \"$PAYLOAD\" >> {payload_capture}\n"
        f"{method_line}"
        f"printf '{response_body}\\n200'\n"
    )
    fake_curl.chmod(0o755)


def _write_env(
    tmpdir: str,
    *,
    mobruji: str | None = "42",
    digest: str | None = None,
    directive_board: str | None = None,
) -> str:
    env_path = Path(tmpdir) / "test.env"
    lines = [
        "DISCORD_BOT_TOKEN=stub\n",
        "DISCORD_RETRY_MAX=1\nDISCORD_RETRY_BASE_SEC=0\n",
    ]
    if mobruji is not None:
        lines.append(f"MOBRUJI_CHANNEL_ID={mobruji}\n")
    if digest is not None:
        lines.append(f"DIGEST_CHANNEL_ID={digest}\n")
    if directive_board is not None:
        lines.append(f"DIRECTIVE_BOARD_CHANNEL_ID={directive_board}\n")
    env_path.write_text("".join(lines), encoding="utf-8")
    return str(env_path)


def _build_isolated_env(
    tmpdir: str, env_path: str, jsonl_path: str | None = None
) -> dict[str, str]:
    """fake curl + helper file 격리 env. 운영 ~/.mobruji 영향 차단."""
    new_path = f"{tmpdir}:{os.environ.get('PATH', '')}"
    run_env = os.environ.copy()
    run_env.update({
        "DISCORD_DAEMON_ENV_PATH": env_path,
        "PATH": new_path,
        "LAST_USER_MSG_ID_FILE": str(Path(tmpdir) / "nonexistent-last.txt"),
        "HELPER_TARGET_FILE": str(Path(tmpdir) / "nonexistent-target.txt"),
        "HELPER_QUEUE_FILE": str(Path(tmpdir) / "nonexistent-queue.jsonl"),
        "HELPER_THREAD_FILE": str(Path(tmpdir) / "nonexistent-thread.txt"),
        "LAUNCH_THREAD_FILE": str(Path(tmpdir) / "nonexistent-launch.txt"),
        "DIRECTIVE_BOARD_FILE": jsonl_path or str(
            Path(tmpdir) / "directive-board.jsonl"
        ),
    })
    run_env.pop("HELPER_TURN_TARGET_MSG_ID", None)
    run_env.pop("LAUNCH_THREAD_ID", None)
    run_env.pop("DIRECTIVE_BOARD_CHANNEL_ID", None)
    return run_env


class DirectiveBoardModeTests(unittest.TestCase):
    """`--directive-board` mode — 채널 강제 + 포맷 표준 + thread + jsonl append."""

    def _run(self, *args: str, env: dict[str, str]) -> subprocess.CompletedProcess:
        return subprocess.run(
            ["bash", str(SCRIPT_PATH), *args],
            capture_output=True,
            text=True,
            env=env,
            timeout=5,
        )

    def test_directive_board_routes_to_dedicated_channel(self) -> None:
        """DIRECTIVE_BOARD_CHANNEL_ID 설정 → 본 채널로 강제."""
        with tempfile.TemporaryDirectory() as tmpdir:
            url_capture = str(Path(tmpdir) / "urls.txt")
            payload_capture = str(Path(tmpdir) / "payloads.txt")
            _write_fake_curl(tmpdir, url_capture, payload_capture)
            env_path = _write_env(
                tmpdir, mobruji="42", digest="999", directive_board="7777"
            )
            run_env = _build_isolated_env(tmpdir, env_path)

            result = self._run(
                "--directive-board", "테스트 지시",
                "--delegated-to", "be",
                "--related", "PR #1234",
                env=run_env,
            )
            self.assertEqual(result.returncode, 0, msg=result.stderr)
            urls = Path(url_capture).read_text().splitlines()
            # 최소 2 호출 — message POST + thread 생성. 첫 호출은 messages POST.
            self.assertTrue(any("/channels/7777/messages" in u for u in urls),
                            f"directive-board 채널 (7777) push 부재: {urls}")
            self.assertFalse(any("/channels/42/messages" == u.split("?")[0]
                                 for u in urls),
                             f"MOBRUJI (42) leak: {urls}")

    def test_directive_board_body_format(self) -> None:
        """본문 포맷 표준 자동 생성 — 📌 / 상태 / 담당 / 관련 4 라인."""
        with tempfile.TemporaryDirectory() as tmpdir:
            url_capture = str(Path(tmpdir) / "urls.txt")
            payload_capture = str(Path(tmpdir) / "payloads.txt")
            _write_fake_curl(tmpdir, url_capture, payload_capture)
            env_path = _write_env(
                tmpdir, mobruji="42", directive_board="7777"
            )
            run_env = _build_isolated_env(tmpdir, env_path)

            result = self._run(
                "--directive-board", "신규 기능 X 추가",
                "--delegated-to", "be sub-agent",
                "--related", "이슈 #500",
                "--ts", "2026-05-24 15:00 KST",
                env=run_env,
            )
            self.assertEqual(result.returncode, 0, msg=result.stderr)
            payloads = Path(payload_capture).read_text().splitlines()
            # 첫 payload = messages POST 본문.
            first = json.loads(payloads[0])
            content = first["content"]
            self.assertIn("📌 신규 기능 X 추가", content)
            self.assertIn("(지시 2026-05-24 15:00 KST)", content)
            self.assertIn("상태: 진행 중", content)
            self.assertIn("담당: be sub-agent", content)
            self.assertIn("관련: 이슈 #500", content)

    def test_directive_board_appends_jsonl_entry(self) -> None:
        """~/.mobruji/directive-board.jsonl 에 append 되는지 + 8건 backfill 호환 키 집합."""
        with tempfile.TemporaryDirectory() as tmpdir:
            url_capture = str(Path(tmpdir) / "urls.txt")
            payload_capture = str(Path(tmpdir) / "payloads.txt")
            jsonl_path = str(Path(tmpdir) / "directive-board.jsonl")
            # message POST 응답 id="100" / thread 생성 응답 id="200" 으로 분기하기
            # 어려우므로 fake curl 은 모두 같은 id 반환 — id 값은 무관, 키 존재만
            # 검증.
            _write_fake_curl(tmpdir, url_capture, payload_capture,
                             response_body='{"id": "1234567890123456789"}')
            env_path = _write_env(
                tmpdir, mobruji="42", directive_board="7777"
            )
            run_env = _build_isolated_env(tmpdir, env_path, jsonl_path)

            result = self._run(
                "--directive-board", "jsonl 테스트",
                "--delegated-to", "helper",
                "--related", "PR #99",
                env=run_env,
            )
            self.assertEqual(result.returncode, 0, msg=result.stderr)
            self.assertTrue(Path(jsonl_path).exists(),
                            "directive-board.jsonl 생성되지 않음")
            entries = [json.loads(line) for line in
                       Path(jsonl_path).read_text().splitlines() if line.strip()]
            self.assertEqual(len(entries), 1)
            entry = entries[0]
            # 8건 backfill 호환 키 집합 검증.
            for key in ("ts", "summary", "status", "owner", "related",
                        "message_id", "thread_id"):
                self.assertIn(key, entry, f"키 누락: {key}")
            self.assertEqual(entry["summary"], "jsonl 테스트")
            self.assertEqual(entry["owner"], "helper")
            self.assertEqual(entry["related"], "PR #99")
            self.assertEqual(entry["status"], "진행 중")

    def test_directive_board_outputs_thread_id_to_stdout(self) -> None:
        """stdout 으로 thread_id 만 출력 — helper/sub-agent 가 capture 가능."""
        with tempfile.TemporaryDirectory() as tmpdir:
            url_capture = str(Path(tmpdir) / "urls.txt")
            payload_capture = str(Path(tmpdir) / "payloads.txt")
            _write_fake_curl(tmpdir, url_capture, payload_capture,
                             response_body='{"id": "1234567890123456789"}')
            env_path = _write_env(
                tmpdir, mobruji="42", directive_board="7777"
            )
            run_env = _build_isolated_env(tmpdir, env_path)

            result = self._run(
                "--directive-board", "stdout 테스트",
                "--delegated-to", "be",
                "--related", "PR #1",
                env=run_env,
            )
            self.assertEqual(result.returncode, 0, msg=result.stderr)
            self.assertEqual(result.stdout.strip(), "1234567890123456789")

    def test_directive_board_requires_delegated_to(self) -> None:
        """--delegated-to 누락 시 에러."""
        with tempfile.TemporaryDirectory() as tmpdir:
            env_path = _write_env(
                tmpdir, mobruji="42", directive_board="7777"
            )
            run_env = _build_isolated_env(tmpdir, env_path)
            result = self._run(
                "--directive-board", "필수 인자 테스트",
                "--related", "PR #1",
                env=run_env,
            )
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("--delegated-to", result.stderr)

    def test_directive_board_requires_related(self) -> None:
        """--related 누락 시 에러."""
        with tempfile.TemporaryDirectory() as tmpdir:
            env_path = _write_env(
                tmpdir, mobruji="42", directive_board="7777"
            )
            run_env = _build_isolated_env(tmpdir, env_path)
            result = self._run(
                "--directive-board", "필수 인자 테스트",
                "--delegated-to", "be",
                env=run_env,
            )
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("--related", result.stderr)

    def test_directive_board_graceful_fallback_when_channel_missing(self) -> None:
        """DIRECTIVE_BOARD_CHANNEL_ID 미설정 → MOBRUJI fallback + stderr warning."""
        with tempfile.TemporaryDirectory() as tmpdir:
            url_capture = str(Path(tmpdir) / "urls.txt")
            payload_capture = str(Path(tmpdir) / "payloads.txt")
            _write_fake_curl(tmpdir, url_capture, payload_capture)
            env_path = _write_env(
                tmpdir, mobruji="42", directive_board=None
            )
            run_env = _build_isolated_env(tmpdir, env_path)

            result = self._run(
                "--directive-board", "fallback 테스트",
                "--delegated-to", "be",
                "--related", "PR #1",
                env=run_env,
            )
            self.assertEqual(result.returncode, 0, msg=result.stderr)
            # MOBRUJI 로 fallback.
            urls = Path(url_capture).read_text().splitlines()
            self.assertTrue(any("/channels/42/messages" in u for u in urls),
                            f"MOBRUJI fallback 실패: {urls}")
            # stderr warning.
            self.assertIn("DIRECTIVE_BOARD_CHANNEL_ID", result.stderr)

    def test_directive_board_uses_status_override(self) -> None:
        """--status 옵션 → 본문 상태 라인이 override 값으로."""
        with tempfile.TemporaryDirectory() as tmpdir:
            url_capture = str(Path(tmpdir) / "urls.txt")
            payload_capture = str(Path(tmpdir) / "payloads.txt")
            _write_fake_curl(tmpdir, url_capture, payload_capture)
            env_path = _write_env(
                tmpdir, mobruji="42", directive_board="7777"
            )
            run_env = _build_isolated_env(tmpdir, env_path)

            result = self._run(
                "--directive-board", "status override 테스트",
                "--delegated-to", "be",
                "--related", "이슈 #1",
                "--status", "⏳ 대기",
                env=run_env,
            )
            self.assertEqual(result.returncode, 0, msg=result.stderr)
            payloads = Path(payload_capture).read_text().splitlines()
            content = json.loads(payloads[0])["content"]
            self.assertIn("상태: ⏳ 대기", content)

    def test_directive_board_disables_message_reference(self) -> None:
        """directive-board 도 status 처럼 NO_REPLY 강제 — message_reference 없어야."""
        with tempfile.TemporaryDirectory() as tmpdir:
            url_capture = str(Path(tmpdir) / "urls.txt")
            payload_capture = str(Path(tmpdir) / "payloads.txt")
            _write_fake_curl(tmpdir, url_capture, payload_capture)
            env_path = _write_env(
                tmpdir, mobruji="42", directive_board="7777"
            )
            run_env = _build_isolated_env(tmpdir, env_path)
            last_id_path = Path(tmpdir) / "last-id.txt"
            last_id_path.write_text("12345678901234567", encoding="utf-8")
            run_env["LAST_USER_MSG_ID_FILE"] = str(last_id_path)

            result = self._run(
                "--directive-board", "reply gard 테스트",
                "--delegated-to", "be",
                "--related", "이슈 #1",
                env=run_env,
            )
            self.assertEqual(result.returncode, 0, msg=result.stderr)
            payloads = Path(payload_capture).read_text().splitlines()
            first = json.loads(payloads[0])
            self.assertNotIn(
                "message_reference", first,
                "directive-board push 는 message_reference 없어야 함",
            )

    def test_directive_board_uses_env_variable_channel(self) -> None:
        """DIRECTIVE_BOARD_CHANNEL_ID env 가 .env 파일보다 우선."""
        with tempfile.TemporaryDirectory() as tmpdir:
            url_capture = str(Path(tmpdir) / "urls.txt")
            payload_capture = str(Path(tmpdir) / "payloads.txt")
            _write_fake_curl(tmpdir, url_capture, payload_capture)
            env_path = _write_env(
                tmpdir, mobruji="42", directive_board="7777"
            )
            run_env = _build_isolated_env(tmpdir, env_path)
            # env 가 .env 파일 값 (7777) 보다 우선.
            run_env["DIRECTIVE_BOARD_CHANNEL_ID"] = "8888"

            result = self._run(
                "--directive-board", "env 우선 테스트",
                "--delegated-to", "be",
                "--related", "이슈 #1",
                env=run_env,
            )
            self.assertEqual(result.returncode, 0, msg=result.stderr)
            urls = Path(url_capture).read_text().splitlines()
            self.assertTrue(any("/channels/8888/messages" in u for u in urls),
                            f"env 우선 적용 실패: {urls}")
            self.assertFalse(any("/channels/7777/messages" in u for u in urls),
                             ".env 채널이 잘못 사용됨")


if __name__ == "__main__":
    unittest.main()
