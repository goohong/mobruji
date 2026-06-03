"""discord-reply.sh `--status-channel` / `--channel` 채널 routing 테스트 (#1036).

배경 (2026-05-24 채널 분리 사고):
    nmae sub-agent launch / 완료 / cycle alert push 가 사용자 응답 채널
    (#모부르지 = MOBRUJI_CHANNEL_ID) 으로 leak. 사용자 정정 "이런게 모부르지
    채널로 오니". helper-relay-scope (PR #1034 / 메모리 [[feedback-helper-relay-scope]])
    의 nmae 측 거울 룰 신설 — `--status-channel` flag + `nmae-discord-push.sh`
    wrapper 로 nmae status 가 DIGEST_CHANNEL_ID 로 라우팅되도록 강제.

본 테스트는 외부 Discord REST 호출을 fake curl 로 대체 — payload 와 URL 을
캡처해 채널 routing 이 의도대로인지 검증.
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
NMAE_WRAPPER_PATH = (
    Path(__file__).resolve().parent.parent / "nmae-discord-push.sh"
)


def _write_fake_curl(tmpdir: str, url_capture: str, payload_capture: str) -> None:
    """payload + URL 동시 캡처용 fake curl.

    discord_curl_with_retry (PR #911 G-6) 가 `-w '\\n%{http_code}'` 로 status
    code 를 마지막 줄에 기대 → fake curl 도 `{"id":"..."}\\n200` 형태로 응답.
    URL 은 `http*` 패턴으로 캡처 — Discord REST endpoint 만 표적.
    """
    fake_curl = Path(tmpdir) / "curl"
    fake_curl.write_text(
        "#!/usr/bin/env bash\n"
        "URL=\"\"\n"
        "PAYLOAD=\"\"\n"
        "while [[ $# -gt 0 ]]; do\n"
        "  case \"$1\" in\n"
        "    -d) shift; PAYLOAD=\"$1\";;\n"
        "    http*) URL=\"$1\";;\n"
        "  esac\n"
        "  shift\n"
        "done\n"
        f"printf '%s\\n' \"$URL\" >> {url_capture}\n"
        f"printf '%s\\n' \"$PAYLOAD\" >> {payload_capture}\n"
        "printf '{\"id\": \"99999\"}\\n200'\n"
    )
    fake_curl.chmod(0o755)


def _write_env(tmpdir: str, *, mobruji: str | None = "42",
               digest: str | None = None, notify: str | None = None) -> str:
    """tmpdir 안에 .env 작성. None 인 키는 생략 — read_env_value 가 graceful empty."""
    env_path = Path(tmpdir) / "test.env"
    lines = [
        "DISCORD_BOT_TOKEN=stub\n",
        "DISCORD_RETRY_MAX=1\nDISCORD_RETRY_BASE_SEC=0\n",
    ]
    if mobruji is not None:
        lines.append(f"MOBRUJI_CHANNEL_ID={mobruji}\n")
    if digest is not None:
        lines.append(f"DIGEST_CHANNEL_ID={digest}\n")
    if notify is not None:
        lines.append(f"NOTIFY_CHANNEL_ID={notify}\n")
    env_path.write_text("".join(lines), encoding="utf-8")
    return str(env_path)


def _build_isolated_env(tmpdir: str, env_path: str) -> dict[str, str]:
    """fake curl + 모든 helper file 격리 env. 운영 ~/.mobruji 영향 차단."""
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
    })
    run_env.pop("HELPER_TURN_TARGET_MSG_ID", None)
    run_env.pop("LAUNCH_THREAD_ID", None)
    return run_env


class StatusChannelFlagRoutingTests(unittest.TestCase):
    """`--status-channel` flag — DIGEST_CHANNEL_ID (또는 NOTIFY backward-compat)
    로 채널 강제 override 검증. #모부르지 (MOBRUJI_CHANNEL_ID) leak 방지가 핵심.
    """

    def _run(self, *args: str, env: dict[str, str]) -> subprocess.CompletedProcess:
        return subprocess.run(
            ["bash", str(SCRIPT_PATH), *args],
            capture_output=True,
            text=True,
            env=env,
            timeout=5,
        )

    def test_status_channel_routes_to_digest_when_set(self) -> None:
        """--status-channel + DIGEST_CHANNEL_ID 설정 → DIGEST 로 push."""
        with tempfile.TemporaryDirectory() as tmpdir:
            url_capture = str(Path(tmpdir) / "urls.txt")
            payload_capture = str(Path(tmpdir) / "payloads.txt")
            _write_fake_curl(tmpdir, url_capture, payload_capture)
            env_path = _write_env(
                tmpdir, mobruji="42", digest="999", notify=None
            )
            run_env = _build_isolated_env(tmpdir, env_path)

            result = self._run("--status-channel", "status body", env=run_env)
            self.assertEqual(result.returncode, 0, msg=result.stderr)

            urls = Path(url_capture).read_text().splitlines()
            # (#1449/#1425 stale fix) ❓/⏹ control emoji(9b0ade1)가 reaction PUT 1건 추가 —
            # 메시지 POST 기준으로 검증.
            msg_urls = [u for u in urls if u.endswith("/messages")]
            self.assertEqual(len(msg_urls), 1, f"메시지 전송 1건 기대: {urls}")
            # DIGEST 채널 (999) 로 갔는지 — MOBRUJI (42) 는 안 됨.
            self.assertIn("/channels/999/messages", msg_urls[0])
            for url in urls:
                self.assertNotIn("/channels/42/", url)

    def test_status_channel_falls_back_to_notify_when_digest_missing(self) -> None:
        """DIGEST 미설정 + NOTIFY 설정 → NOTIFY 로 (backward-compat #1019)."""
        with tempfile.TemporaryDirectory() as tmpdir:
            url_capture = str(Path(tmpdir) / "urls.txt")
            payload_capture = str(Path(tmpdir) / "payloads.txt")
            _write_fake_curl(tmpdir, url_capture, payload_capture)
            env_path = _write_env(
                tmpdir, mobruji="42", digest=None, notify="888"
            )
            run_env = _build_isolated_env(tmpdir, env_path)

            result = self._run("--status-channel", "status body", env=run_env)
            self.assertEqual(result.returncode, 0, msg=result.stderr)

            urls = Path(url_capture).read_text().splitlines()
            self.assertIn("/channels/888/messages", urls[0])
            self.assertNotIn("/channels/42/", urls[0])
            # deprecation 메시지가 stderr 에.
            self.assertIn("NOTIFY_CHANNEL_ID", result.stderr)

    def test_status_channel_errors_when_no_status_channel_configured(self) -> None:
        """DIGEST + NOTIFY 모두 미설정 → 명시적 에러 (silent MOBRUJI fallback 금지).

        이 가드가 leak 방지의 핵심 — fallback 으로 MOBRUJI 로 가면 룰 본질이
        무력화되므로, 채널 분리가 안 됐다는 사실을 호출자에게 알린다.
        """
        with tempfile.TemporaryDirectory() as tmpdir:
            url_capture = str(Path(tmpdir) / "urls.txt")
            payload_capture = str(Path(tmpdir) / "payloads.txt")
            _write_fake_curl(tmpdir, url_capture, payload_capture)
            env_path = _write_env(
                tmpdir, mobruji="42", digest=None, notify=None
            )
            run_env = _build_isolated_env(tmpdir, env_path)

            result = self._run("--status-channel", "status body", env=run_env)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("DIGEST_CHANNEL_ID", result.stderr)
            # 어떤 push 도 발생하면 안 됨.
            self.assertFalse(
                Path(url_capture).exists() and Path(url_capture).read_text().strip(),
                "에러 시 push 발생하면 안 됨",
            )

    def test_default_no_flag_still_uses_mobruji_for_backward_compat(self) -> None:
        """flag 없으면 기존 동작 유지 — MOBRUJI_CHANNEL_ID 로 push (회귀 가드).

        helper 본체 본답은 #모부르지 채널 유지가 정답. 본 flag 는 nmae 만 사용.
        """
        with tempfile.TemporaryDirectory() as tmpdir:
            url_capture = str(Path(tmpdir) / "urls.txt")
            payload_capture = str(Path(tmpdir) / "payloads.txt")
            _write_fake_curl(tmpdir, url_capture, payload_capture)
            env_path = _write_env(
                tmpdir, mobruji="42", digest="999", notify="888"
            )
            run_env = _build_isolated_env(tmpdir, env_path)

            result = self._run("default body", env=run_env)
            self.assertEqual(result.returncode, 0, msg=result.stderr)
            urls = Path(url_capture).read_text().splitlines()
            self.assertIn("/channels/42/messages", urls[0])

    def test_status_channel_disables_message_reference(self) -> None:
        """--status-channel 은 자동 NO_REPLY=1 강제 → message_reference 미포함.

        status push 는 사용자 메시지에 답장 형태일 필요 없음 + reply 시도가
        다른 채널 message id 를 참조하면 Discord 가 404.
        """
        with tempfile.TemporaryDirectory() as tmpdir:
            url_capture = str(Path(tmpdir) / "urls.txt")
            payload_capture = str(Path(tmpdir) / "payloads.txt")
            _write_fake_curl(tmpdir, url_capture, payload_capture)
            env_path = _write_env(
                tmpdir, mobruji="42", digest="999", notify=None
            )
            run_env = _build_isolated_env(tmpdir, env_path)
            # 의도적으로 last-user-msg-id 도 valid snowflake 로 채워 — flag 가
            # 그래도 NO_REPLY 강제하는지 검증.
            last_id_path = Path(tmpdir) / "last-id.txt"
            last_id_path.write_text("12345678901234567", encoding="utf-8")
            run_env["LAST_USER_MSG_ID_FILE"] = str(last_id_path)

            result = self._run("--status-channel", "status body", env=run_env)
            self.assertEqual(result.returncode, 0, msg=result.stderr)
            payload = json.loads(
                Path(payload_capture).read_text().splitlines()[0]
            )
            self.assertNotIn(
                "message_reference", payload,
                "status push 는 message_reference 없어야 함",
            )

    def test_channel_explicit_override(self) -> None:
        """`--channel <id>` 가 status flag 보다 우선 — wrapper 작성 / 신설 채널용."""
        with tempfile.TemporaryDirectory() as tmpdir:
            url_capture = str(Path(tmpdir) / "urls.txt")
            payload_capture = str(Path(tmpdir) / "payloads.txt")
            _write_fake_curl(tmpdir, url_capture, payload_capture)
            env_path = _write_env(
                tmpdir, mobruji="42", digest="999", notify=None
            )
            run_env = _build_isolated_env(tmpdir, env_path)

            result = self._run(
                "--channel", "7777", "body to custom channel", env=run_env
            )
            self.assertEqual(result.returncode, 0, msg=result.stderr)
            urls = Path(url_capture).read_text().splitlines()
            self.assertIn("/channels/7777/messages", urls[0])
            # 다른 채널 id 들이 포함되면 안 됨.
            self.assertNotIn("/channels/42/", urls[0])
            self.assertNotIn("/channels/999/", urls[0])

    def test_channel_flag_requires_id(self) -> None:
        """--channel 뒤 id 누락 시 에러."""
        with tempfile.TemporaryDirectory() as tmpdir:
            env_path = _write_env(tmpdir, mobruji="42", digest="999")
            run_env = _build_isolated_env(tmpdir, env_path)
            # --channel 뒤에 아무 인자도 없음.
            result = self._run("--channel", env=run_env)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("--channel", result.stderr)


class NmaeWrapperRoutingTests(unittest.TestCase):
    """`nmae-discord-push.sh` thin wrapper — discord-reply.sh 에 `--status-channel`
    을 자동 prepend 하는지 검증.
    """

    def _run_wrapper(self, *args: str, env: dict[str, str]) -> subprocess.CompletedProcess:
        return subprocess.run(
            ["bash", str(NMAE_WRAPPER_PATH), *args],
            capture_output=True,
            text=True,
            env=env,
            timeout=5,
        )

    def test_wrapper_routes_to_digest_channel(self) -> None:
        """wrapper 호출이 DIGEST_CHANNEL_ID 로 라우팅 (MOBRUJI leak 방지)."""
        with tempfile.TemporaryDirectory() as tmpdir:
            url_capture = str(Path(tmpdir) / "urls.txt")
            payload_capture = str(Path(tmpdir) / "payloads.txt")
            _write_fake_curl(tmpdir, url_capture, payload_capture)
            env_path = _write_env(
                tmpdir, mobruji="42", digest="999", notify=None
            )
            run_env = _build_isolated_env(tmpdir, env_path)
            # wrapper 가 호출할 discord-reply.sh 경로를 워크트리 본체로 override.
            run_env["DISCORD_REPLY_SH"] = str(SCRIPT_PATH)

            result = self._run_wrapper(
                "🚀 be sub-agent launch — PR #1234", env=run_env
            )
            self.assertEqual(result.returncode, 0, msg=result.stderr)
            urls = Path(url_capture).read_text().splitlines()
            self.assertIn("/channels/999/messages", urls[0])
            self.assertNotIn("/channels/42/", urls[0])

    def test_wrapper_no_args_exits_nonzero(self) -> None:
        """인자 부족 시 사용법 print + exit 1."""
        with tempfile.TemporaryDirectory() as tmpdir:
            env_path = _write_env(tmpdir, mobruji="42", digest="999")
            run_env = _build_isolated_env(tmpdir, env_path)
            run_env["DISCORD_REPLY_SH"] = str(SCRIPT_PATH)
            result = self._run_wrapper(env=run_env)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("nmae-discord-push.sh", result.stderr)

    def test_wrapper_errors_when_discord_reply_sh_missing(self) -> None:
        """DISCORD_REPLY_SH 가 가리키는 파일 부재 시 명시 에러."""
        with tempfile.TemporaryDirectory() as tmpdir:
            env_path = _write_env(tmpdir, mobruji="42", digest="999")
            run_env = _build_isolated_env(tmpdir, env_path)
            run_env["DISCORD_REPLY_SH"] = "/nonexistent/discord-reply.sh"
            result = self._run_wrapper("body", env=run_env)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("discord-reply.sh", result.stderr)


if __name__ == "__main__":
    unittest.main()
