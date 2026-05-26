"""bot.verify_deploy_dir 단위 테스트 (PR #1126).

bridge 배포 dir 분리 spec — `docs/features/bridge-deployment-dir-separation.md`
의 fail-fast 검증 함수 동작 확인. 외부 네트워크 호출 없음.

대상 시나리오:

- ``mode=off`` — 검증 skip, "off" 반환.
- ``mode=warn`` (default) — 잘못된 dir 일 때 WARNING 로그만, "warn" 반환.
- ``mode=strict`` — 잘못된 dir 일 때 sys.exit(2) trigger.
- 정상 dir — mode 무관 "ok" 반환 + INFO 로그.
- 알 수 없는 mode 값 — "warn" 으로 fallback.
"""

from __future__ import annotations

import logging
import sys
import unittest
from pathlib import Path
from unittest import mock

PARENT_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PARENT_DIR))

try:
    import discord as _real_discord  # noqa: F401
except ImportError:
    sys.modules["discord"] = mock.MagicMock()

for missing in ("requests", "dotenv"):
    if missing not in sys.modules:
        stub = mock.MagicMock()
        if missing == "dotenv":
            stub.load_dotenv = lambda *a, **kw: None
        sys.modules[missing] = stub

import bot  # noqa: E402


class VerifyDeployDirTests(unittest.TestCase):
    """``bot.verify_deploy_dir`` 동작 확인."""

    def _make_env_get(self, env_map: dict[str, str]):
        def _get(key: str, default: str | None = None) -> str | None:
            return env_map.get(key, default)

        return _get

    def test_ok_when_bot_file_under_expected_dir(self) -> None:
        # given: bot.py 가 expected dir 의 자식
        final_expected_dir = Path("/tmp/mobruji-bridge").resolve()
        final_bot_file = final_expected_dir / "tools/discord-daemon/bot.py"
        env_map = {
            "MOBRUJI_BRIDGE_DEPLOY_DIR_CHECK": "warn",
            "MOBRUJI_BRIDGE_DEPLOY_DIR": str(final_expected_dir),
        }
        final_logger = logging.getLogger("test-verify-deploy-dir")

        # when
        final_result = bot.verify_deploy_dir(
            bot_file=final_bot_file,
            env_get=self._make_env_get(env_map),
            logger_override=final_logger,
        )

        # then
        self.assertEqual(final_result, "ok")

    def test_off_mode_skips_check(self) -> None:
        # given: mode=off, bot.py 가 entirely 다른 dir
        env_map = {"MOBRUJI_BRIDGE_DEPLOY_DIR_CHECK": "off"}
        final_bot_file = Path("/some/other/path/bot.py")
        final_logger = logging.getLogger("test-verify-deploy-dir")

        # when
        final_result = bot.verify_deploy_dir(
            bot_file=final_bot_file,
            env_get=self._make_env_get(env_map),
            logger_override=final_logger,
        )

        # then
        self.assertEqual(final_result, "off")

    def test_warn_mode_returns_warn_when_mismatch(self) -> None:
        # given: bot.py 가 메인 repo (잘못된 dir)
        env_map = {
            "MOBRUJI_BRIDGE_DEPLOY_DIR_CHECK": "warn",
            "MOBRUJI_BRIDGE_DEPLOY_DIR": "/home/mobruji/mobruji-bridge",
        }
        final_bot_file = Path("/home/mobruji/mobruji/tools/discord-daemon/bot.py")
        final_logger = logging.getLogger("test-verify-deploy-dir")

        # when
        with self.assertLogs(final_logger, level="WARNING") as final_log_ctx:
            final_result = bot.verify_deploy_dir(
                bot_file=final_bot_file,
                env_get=self._make_env_get(env_map),
                logger_override=final_logger,
            )

        # then
        self.assertEqual(final_result, "warn")
        self.assertTrue(
            any("MISMATCH" in msg for msg in final_log_ctx.output),
            f"WARNING 에 MISMATCH 키워드 누락 — output={final_log_ctx.output}",
        )

    def test_strict_mode_exits_when_mismatch(self) -> None:
        # given: bot.py 가 메인 repo, mode=strict
        env_map = {
            "MOBRUJI_BRIDGE_DEPLOY_DIR_CHECK": "strict",
            "MOBRUJI_BRIDGE_DEPLOY_DIR": "/home/mobruji/mobruji-bridge",
        }
        final_bot_file = Path("/home/mobruji/mobruji/tools/discord-daemon/bot.py")
        final_logger = logging.getLogger("test-verify-deploy-dir")

        # when / then
        with self.assertRaises(SystemExit) as final_ctx:
            bot.verify_deploy_dir(
                bot_file=final_bot_file,
                env_get=self._make_env_get(env_map),
                logger_override=final_logger,
            )
        self.assertEqual(final_ctx.exception.code, 2)

    def test_unknown_mode_falls_back_to_warn(self) -> None:
        # given: 알 수 없는 mode 값
        env_map = {
            "MOBRUJI_BRIDGE_DEPLOY_DIR_CHECK": "bogus-mode",
            "MOBRUJI_BRIDGE_DEPLOY_DIR": "/home/mobruji/mobruji-bridge",
        }
        final_bot_file = Path("/home/mobruji/mobruji/tools/discord-daemon/bot.py")
        final_logger = logging.getLogger("test-verify-deploy-dir")

        # when
        with self.assertLogs(final_logger, level="WARNING") as final_log_ctx:
            final_result = bot.verify_deploy_dir(
                bot_file=final_bot_file,
                env_get=self._make_env_get(env_map),
                logger_override=final_logger,
            )

        # then: fallback warn + mismatch warning 두 줄 모두 emit
        self.assertEqual(final_result, "warn")
        self.assertTrue(
            any("알 수 없음" in msg for msg in final_log_ctx.output),
            f"fallback warning 누락 — output={final_log_ctx.output}",
        )

    def test_default_mode_is_warn(self) -> None:
        # given: env 미설정 (default 적용)
        env_map: dict[str, str] = {}
        final_bot_file = Path("/home/mobruji/mobruji/tools/discord-daemon/bot.py")
        final_logger = logging.getLogger("test-verify-deploy-dir")

        # when
        final_result = bot.verify_deploy_dir(
            bot_file=final_bot_file,
            env_get=self._make_env_get(env_map),
            logger_override=final_logger,
        )

        # then: default expected dir = /home/mobruji/mobruji-bridge, 메인 repo 는 mismatch
        self.assertEqual(final_result, "warn")


if __name__ == "__main__":
    unittest.main()
