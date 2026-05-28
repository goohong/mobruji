"""discord-reply.sh `--cycle-channel` + nmae-discord-push.sh `--cycle` 라우팅 테스트 (P12).

배경 (2026-05-24 사용자 P0 인계 — per-cycle 채널 분리):
    nmae 가 4 사이클 (be/fe/rev/plan) 별로 별도 Discord 채널을 만들어
    BE_CHANNEL_ID / FE_CHANNEL_ID / REV_CHANNEL_ID / PLAN_CHANNEL_ID 환경변수로
    제공. nmae status push (launch / 완료 / 오류) 가 해당 사이클 채널로 분기되도록
    discord-reply.sh `--cycle-channel <name>` flag + nmae-discord-push.sh `--cycle <name>`
    wrapper 옵션 도입. 미설정 시 DIGEST_CHANNEL_ID 로 graceful fallback.

거울 룰: PR #1036 `--status-channel` (DIGEST 강제). 본 PR 은 보다 fine-grained.

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
from unittest import mock

SCRIPT_PATH = (
    Path(__file__).resolve().parent.parent / "discord-reply.sh"
)
NMAE_WRAPPER_PATH = (
    Path(__file__).resolve().parent.parent / "nmae-discord-push.sh"
)


def _write_fake_curl(tmpdir: str, url_capture: str, payload_capture: str) -> None:
    """payload + URL 동시 캡처용 fake curl. test_status_channel_routing.py 와 동일 패턴.

    PR #1155 (2026-05-27, forum adapter impl):
        `--cycle-channel <name>` 호출 시 channel type 자동 감지 GET 가 1회 선행 호출됨.
        fake curl 은 GET 응답에 `type:0` (text) 을 포함시켜 adapter 가 기존 reply 경로를
        유지하도록 한다. GET 호출은 url_capture / payload_capture 에 안 기록 — 기존
        assertion 호환성 (urls[0] == 첫 POST messages) 유지.
    """
    fake_curl = Path(tmpdir) / "curl"
    fake_curl.write_text(
        "#!/usr/bin/env bash\n"
        "METHOD=\"GET\"\n"
        "URL=\"\"\n"
        "PAYLOAD=\"\"\n"
        "while [[ $# -gt 0 ]]; do\n"
        "  case \"$1\" in\n"
        "    -X) shift; METHOD=\"$1\";;\n"
        "    -d) shift; PAYLOAD=\"$1\";;\n"
        "    http*) URL=\"$1\";;\n"
        "  esac\n"
        "  shift\n"
        "done\n"
        "# PR #1155 forum adapter: cycle channel type detect GET 는 routing 관심사 외\n"
        "#   → capture skip. text channel (type=0) 응답으로 adapter 가 기존 path 유지.\n"
        "if [[ \"$METHOD\" == \"GET\" ]]; then\n"
        "  printf '{\"id\":\"stub\",\"type\":0}\\n200'\n"
        "  exit 0\n"
        "fi\n"
        f"printf '%s\\n' \"$URL\" >> {url_capture}\n"
        f"printf '%s\\n' \"$PAYLOAD\" >> {payload_capture}\n"
        "printf '{\"id\": \"99999\"}\\n200'\n"
    )
    fake_curl.chmod(0o755)


def _write_env(
    tmpdir: str,
    *,
    mobruji: str | None = "42",
    digest: str | None = None,
    notify: str | None = None,
    be: str | None = None,
    fe: str | None = None,
    rev: str | None = None,
    plan: str | None = None,
) -> str:
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
    if be is not None:
        lines.append(f"BE_CHANNEL_ID={be}\n")
    if fe is not None:
        lines.append(f"FE_CHANNEL_ID={fe}\n")
    if rev is not None:
        lines.append(f"REV_CHANNEL_ID={rev}\n")
    if plan is not None:
        lines.append(f"PLAN_CHANNEL_ID={plan}\n")
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


class CycleChannelFlagRoutingTests(unittest.TestCase):
    """`--cycle-channel <name>` flag — BE/FE/REV/PLAN_CHANNEL_ID 로 라우팅 검증."""

    def _run(self, *args: str, env: dict[str, str]) -> subprocess.CompletedProcess:
        return subprocess.run(
            ["bash", str(SCRIPT_PATH), *args],
            capture_output=True,
            text=True,
            env=env,
            timeout=5,
        )

    # parametrize 4 cycle name → 해당 *_CHANNEL_ID 라우팅.
    def test_be_routes_to_be_channel(self) -> None:
        self._assert_cycle_routes("be", "1001")

    def test_fe_routes_to_fe_channel(self) -> None:
        self._assert_cycle_routes("fe", "2002")

    def test_rev_routes_to_rev_channel(self) -> None:
        self._assert_cycle_routes("rev", "3003")

    def test_plan_routes_to_plan_channel(self) -> None:
        self._assert_cycle_routes("plan", "4004")

    def _assert_cycle_routes(self, cycle_name: str, channel_id: str) -> None:
        """parametrize helper — cycle_name 으로 호출했을 때 channel_id 로 push 검증."""
        with tempfile.TemporaryDirectory() as tmpdir:
            url_capture = str(Path(tmpdir) / "urls.txt")
            payload_capture = str(Path(tmpdir) / "payloads.txt")
            _write_fake_curl(tmpdir, url_capture, payload_capture)
            kwargs = {"mobruji": "42", "digest": "999", cycle_name: channel_id}
            env_path = _write_env(tmpdir, **kwargs)
            run_env = _build_isolated_env(tmpdir, env_path)

            result = self._run(
                "--cycle-channel", cycle_name, f"{cycle_name} status body", env=run_env
            )
            self.assertEqual(result.returncode, 0, msg=result.stderr)

            urls = Path(url_capture).read_text().splitlines()
            self.assertEqual(len(urls), 1, f"호출 1건 기대: {urls}")
            self.assertIn(f"/channels/{channel_id}/messages", urls[0])
            # MOBRUJI / DIGEST 로 가면 안 됨.
            self.assertNotIn("/channels/42/", urls[0])
            self.assertNotIn("/channels/999/", urls[0])

    def test_falls_back_to_digest_when_cycle_channel_missing(self) -> None:
        """*_CHANNEL_ID 미설정 + DIGEST 설정 → DIGEST 로 fallback + stderr warning."""
        with tempfile.TemporaryDirectory() as tmpdir:
            url_capture = str(Path(tmpdir) / "urls.txt")
            payload_capture = str(Path(tmpdir) / "payloads.txt")
            _write_fake_curl(tmpdir, url_capture, payload_capture)
            # BE 미설정. DIGEST 만 있음.
            env_path = _write_env(tmpdir, mobruji="42", digest="999", be=None)
            run_env = _build_isolated_env(tmpdir, env_path)

            result = self._run("--cycle-channel", "be", "be body", env=run_env)
            self.assertEqual(result.returncode, 0, msg=result.stderr)

            urls = Path(url_capture).read_text().splitlines()
            self.assertIn("/channels/999/messages", urls[0])
            # deprecation warning emitted to stderr.
            self.assertIn("BE_CHANNEL_ID", result.stderr)
            self.assertIn("fallback", result.stderr.lower())

    def test_falls_back_to_notify_when_digest_also_missing(self) -> None:
        """*_CHANNEL_ID + DIGEST 둘 다 미설정 + NOTIFY 만 → NOTIFY (backward-compat).

        deprecation warning emit. 모두 미설정인 케이스는 별 테스트.
        """
        with tempfile.TemporaryDirectory() as tmpdir:
            url_capture = str(Path(tmpdir) / "urls.txt")
            payload_capture = str(Path(tmpdir) / "payloads.txt")
            _write_fake_curl(tmpdir, url_capture, payload_capture)
            env_path = _write_env(
                tmpdir, mobruji="42", digest=None, notify="888", fe=None
            )
            run_env = _build_isolated_env(tmpdir, env_path)

            result = self._run("--cycle-channel", "fe", "fe body", env=run_env)
            self.assertEqual(result.returncode, 0, msg=result.stderr)

            urls = Path(url_capture).read_text().splitlines()
            self.assertIn("/channels/888/messages", urls[0])
            self.assertIn("NOTIFY", result.stderr)

    def test_errors_when_all_channels_missing(self) -> None:
        """cycle / DIGEST / NOTIFY 모두 미설정 → 명시 에러 (silent MOBRUJI fallback 금지).

        sub-agent leak 방지 — 채널 분리 의도가 명백한데 fallback 으로 회귀 금지.
        """
        with tempfile.TemporaryDirectory() as tmpdir:
            url_capture = str(Path(tmpdir) / "urls.txt")
            payload_capture = str(Path(tmpdir) / "payloads.txt")
            _write_fake_curl(tmpdir, url_capture, payload_capture)
            env_path = _write_env(
                tmpdir, mobruji="42", digest=None, notify=None, rev=None
            )
            run_env = _build_isolated_env(tmpdir, env_path)

            result = self._run("--cycle-channel", "rev", "rev body", env=run_env)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("REV_CHANNEL_ID", result.stderr)
            self.assertFalse(
                Path(url_capture).exists() and Path(url_capture).read_text().strip(),
                "에러 시 push 발생하면 안 됨",
            )

    def test_invalid_cycle_name_errors(self) -> None:
        """be|fe|rev|plan 이외 이름 → 명시 에러."""
        with tempfile.TemporaryDirectory() as tmpdir:
            env_path = _write_env(tmpdir, mobruji="42", digest="999")
            run_env = _build_isolated_env(tmpdir, env_path)
            result = self._run("--cycle-channel", "infra", "body", env=run_env)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("be|fe|rev|plan", result.stderr)

    def test_cycle_channel_requires_name(self) -> None:
        """--cycle-channel 뒤 이름 누락 시 에러."""
        with tempfile.TemporaryDirectory() as tmpdir:
            env_path = _write_env(tmpdir, mobruji="42", digest="999")
            run_env = _build_isolated_env(tmpdir, env_path)
            result = self._run("--cycle-channel", env=run_env)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("--cycle-channel", result.stderr)

    def test_cycle_channel_disables_message_reference(self) -> None:
        """--cycle-channel 자동 NO_REPLY=1 → message_reference 미포함."""
        with tempfile.TemporaryDirectory() as tmpdir:
            url_capture = str(Path(tmpdir) / "urls.txt")
            payload_capture = str(Path(tmpdir) / "payloads.txt")
            _write_fake_curl(tmpdir, url_capture, payload_capture)
            env_path = _write_env(
                tmpdir, mobruji="42", digest="999", be="1001"
            )
            run_env = _build_isolated_env(tmpdir, env_path)
            last_id_path = Path(tmpdir) / "last-id.txt"
            last_id_path.write_text("12345678901234567", encoding="utf-8")
            run_env["LAST_USER_MSG_ID_FILE"] = str(last_id_path)

            result = self._run("--cycle-channel", "be", "be body", env=run_env)
            self.assertEqual(result.returncode, 0, msg=result.stderr)
            payload = json.loads(
                Path(payload_capture).read_text().splitlines()[0]
            )
            self.assertNotIn(
                "message_reference", payload,
                "cycle-channel push 는 message_reference 없어야 함",
            )

    def test_explicit_channel_wins_over_cycle_channel(self) -> None:
        """--channel <id> 가 --cycle-channel 보다 우선 (명시 > 의미)."""
        with tempfile.TemporaryDirectory() as tmpdir:
            url_capture = str(Path(tmpdir) / "urls.txt")
            payload_capture = str(Path(tmpdir) / "payloads.txt")
            _write_fake_curl(tmpdir, url_capture, payload_capture)
            env_path = _write_env(
                tmpdir, mobruji="42", digest="999", be="1001"
            )
            run_env = _build_isolated_env(tmpdir, env_path)

            # --channel 7777 명시 + --cycle-channel be → 7777 우선.
            result = self._run(
                "--channel", "7777", "--cycle-channel", "be", "body", env=run_env
            )
            self.assertEqual(result.returncode, 0, msg=result.stderr)
            urls = Path(url_capture).read_text().splitlines()
            self.assertIn("/channels/7777/messages", urls[0])
            self.assertNotIn("/channels/1001/", urls[0])

    def test_default_no_flag_still_uses_mobruji_for_backward_compat(self) -> None:
        """flag 없으면 MOBRUJI 유지 — 회귀 가드 (helper bare body 는 영향 없음)."""
        with tempfile.TemporaryDirectory() as tmpdir:
            url_capture = str(Path(tmpdir) / "urls.txt")
            payload_capture = str(Path(tmpdir) / "payloads.txt")
            _write_fake_curl(tmpdir, url_capture, payload_capture)
            env_path = _write_env(
                tmpdir, mobruji="42", digest="999", be="1001", fe="2002"
            )
            run_env = _build_isolated_env(tmpdir, env_path)

            result = self._run("default body", env=run_env)
            self.assertEqual(result.returncode, 0, msg=result.stderr)
            urls = Path(url_capture).read_text().splitlines()
            self.assertIn("/channels/42/messages", urls[0])
            # cycle 채널 안 건드림.
            self.assertNotIn("/channels/1001/", urls[0])
            self.assertNotIn("/channels/2002/", urls[0])


class NmaeWrapperCycleOptionTests(unittest.TestCase):
    """`nmae-discord-push.sh --cycle <name>` — discord-reply.sh `--cycle-channel` forward."""

    def _run_wrapper(self, *args: str, env: dict[str, str]) -> subprocess.CompletedProcess:
        return subprocess.run(
            ["bash", str(NMAE_WRAPPER_PATH), *args],
            capture_output=True,
            text=True,
            env=env,
            timeout=5,
        )

    def test_wrapper_cycle_be_routes_to_be_channel(self) -> None:
        """wrapper --cycle be → BE_CHANNEL_ID 로."""
        with tempfile.TemporaryDirectory() as tmpdir:
            url_capture = str(Path(tmpdir) / "urls.txt")
            payload_capture = str(Path(tmpdir) / "payloads.txt")
            _write_fake_curl(tmpdir, url_capture, payload_capture)
            env_path = _write_env(
                tmpdir, mobruji="42", digest="999", be="1001"
            )
            run_env = _build_isolated_env(tmpdir, env_path)
            run_env["DISCORD_REPLY_SH"] = str(SCRIPT_PATH)

            result = self._run_wrapper(
                "--cycle", "be", "🚀 be #1234 launch", env=run_env
            )
            self.assertEqual(result.returncode, 0, msg=result.stderr)
            urls = Path(url_capture).read_text().splitlines()
            self.assertIn("/channels/1001/messages", urls[0])
            self.assertNotIn("/channels/42/", urls[0])
            self.assertNotIn("/channels/999/", urls[0])

    def test_wrapper_cycle_missing_name_errors(self) -> None:
        """--cycle 뒤 이름 누락 시 에러."""
        with tempfile.TemporaryDirectory() as tmpdir:
            env_path = _write_env(tmpdir, mobruji="42", digest="999", be="1001")
            run_env = _build_isolated_env(tmpdir, env_path)
            run_env["DISCORD_REPLY_SH"] = str(SCRIPT_PATH)
            result = self._run_wrapper("--cycle", env=run_env)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("--cycle", result.stderr)

    def test_wrapper_default_without_cycle_uses_digest(self) -> None:
        """--cycle 없으면 기존 동작 (--status-channel = DIGEST 강제) 유지."""
        with tempfile.TemporaryDirectory() as tmpdir:
            url_capture = str(Path(tmpdir) / "urls.txt")
            payload_capture = str(Path(tmpdir) / "payloads.txt")
            _write_fake_curl(tmpdir, url_capture, payload_capture)
            env_path = _write_env(
                tmpdir, mobruji="42", digest="999", be="1001"
            )
            run_env = _build_isolated_env(tmpdir, env_path)
            run_env["DISCORD_REPLY_SH"] = str(SCRIPT_PATH)

            result = self._run_wrapper("status body", env=run_env)
            self.assertEqual(result.returncode, 0, msg=result.stderr)
            urls = Path(url_capture).read_text().splitlines()
            self.assertIn("/channels/999/messages", urls[0])

    def test_wrapper_cycle_with_auto_ack_thread_forwards_mode(self) -> None:
        """--cycle be --auto-ack-thread "..." — mode flag 와 조합 forward."""
        with tempfile.TemporaryDirectory() as tmpdir:
            url_capture = str(Path(tmpdir) / "urls.txt")
            payload_capture = str(Path(tmpdir) / "payloads.txt")
            _write_fake_curl(tmpdir, url_capture, payload_capture)
            env_path = _write_env(
                tmpdir, mobruji="42", digest="999", be="1001"
            )
            run_env = _build_isolated_env(tmpdir, env_path)
            run_env["DISCORD_REPLY_SH"] = str(SCRIPT_PATH)

            result = self._run_wrapper(
                "--cycle", "be", "--auto-ack-thread", "🚀 launch", env=run_env
            )
            self.assertEqual(result.returncode, 0, msg=result.stderr)
            urls = Path(url_capture).read_text().splitlines()
            # auto-ack-thread = ack message + thread 생성 = 2 호출 (POST messages + POST threads).
            self.assertGreaterEqual(len(urls), 2)
            # ack 메시지가 BE 채널로 가야 함.
            self.assertIn("/channels/1001/messages", urls[0])


class BotPyCycleChannelEnvLoadTests(unittest.TestCase):
    """bot.py load_env 가 BE/FE/REV/PLAN_CHANNEL_ID 인식하는지 검증.

    bot.py 본체는 라우팅 책임을 안 짊어짐 (discord-reply.sh 가 직접 .env read).
    하지만 운영자 가시화 (on_ready 로그) 및 향후 digest 확장 진입점으로 env dict
    안에 보존 필요. 본 테스트는 load_env 만 검증 — discord.py runtime 미동원.
    """

    BASE = {
        "DISCORD_BOT_TOKEN": "t",
        "ALLOWED_USER_IDS": "111",
        "MOBRUJI_CHANNEL_ID": "999",
    }

    def setUp(self) -> None:
        # bot.py import 는 무거움 + discord/dotenv 의존 — test 환경에선 stub.
        # 본 test class 안에서만 실행해 다른 테스트 영향 최소화.
        # test_bot.py 와 동일 stub 패턴 (#807).
        import sys as _sys
        sys_path_addon = str(Path(__file__).resolve().parent.parent)
        if sys_path_addon not in _sys.path:
            _sys.path.insert(0, sys_path_addon)
        # discord stub.
        try:
            import discord as _real_discord  # noqa: F401
        except ImportError:
            _sys.modules.setdefault("discord", mock.MagicMock())
        # dotenv stub — load_dotenv 가 실제 .env 를 안 읽도록 no-op.
        if "dotenv" not in _sys.modules:
            stub = mock.MagicMock()
            stub.load_dotenv = lambda *a, **kw: None
            _sys.modules["dotenv"] = stub
        import bot  # noqa: E402
        self._bot = bot

    def test_cycle_channels_loaded_when_all_set(self) -> None:
        """4 cycle 채널 env 모두 설정 → env dict 에 stripped 로 보존."""
        ext = {
            **self.BASE,
            "BE_CHANNEL_ID": "1001",
            "FE_CHANNEL_ID": "2002",
            "REV_CHANNEL_ID": "3003",
            "PLAN_CHANNEL_ID": "4004",
        }
        with mock.patch.dict(os.environ, ext, clear=True):
            env = self._bot.load_env()
        self.assertEqual(env["BE_CHANNEL_ID"], "1001")
        self.assertEqual(env["FE_CHANNEL_ID"], "2002")
        self.assertEqual(env["REV_CHANNEL_ID"], "3003")
        self.assertEqual(env["PLAN_CHANNEL_ID"], "4004")

    def test_cycle_channels_empty_when_unset(self) -> None:
        """미설정 → 빈 문자열 (discord-reply.sh 측이 fallback 처리)."""
        with mock.patch.dict(os.environ, self.BASE, clear=True):
            env = self._bot.load_env()
        self.assertEqual(env["BE_CHANNEL_ID"], "")
        self.assertEqual(env["FE_CHANNEL_ID"], "")
        self.assertEqual(env["REV_CHANNEL_ID"], "")
        self.assertEqual(env["PLAN_CHANNEL_ID"], "")

    def test_cycle_channels_partial_setup(self) -> None:
        """일부만 설정 → 설정된 것만 값, 나머지는 빈 문자열."""
        ext = {**self.BASE, "BE_CHANNEL_ID": "1001", "REV_CHANNEL_ID": "3003"}
        with mock.patch.dict(os.environ, ext, clear=True):
            env = self._bot.load_env()
        self.assertEqual(env["BE_CHANNEL_ID"], "1001")
        self.assertEqual(env["FE_CHANNEL_ID"], "")
        self.assertEqual(env["REV_CHANNEL_ID"], "3003")
        self.assertEqual(env["PLAN_CHANNEL_ID"], "")

    def test_cycle_channels_whitespace_treated_as_unset(self) -> None:
        """공백만 있는 값 → 빈 문자열 (미설정과 동일)."""
        ext = {**self.BASE, "BE_CHANNEL_ID": "   ", "FE_CHANNEL_ID": ""}
        with mock.patch.dict(os.environ, ext, clear=True):
            env = self._bot.load_env()
        self.assertEqual(env["BE_CHANNEL_ID"], "")
        self.assertEqual(env["FE_CHANNEL_ID"], "")


if __name__ == "__main__":
    unittest.main()
