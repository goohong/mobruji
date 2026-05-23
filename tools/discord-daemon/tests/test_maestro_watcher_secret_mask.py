"""maestro_response_watcher 시크릿 마스킹 단위 테스트 (#742).

tmux pane 캡처 chunk 가 Discord 로 push 되기 전 PAT/토큰/패스워드가 마스킹되는지 검증.
표준 라이브러리 unittest + unittest.mock 만 사용 — pytest 로도 자동 수집된다.
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path
from unittest import mock

# bot.py 가 한 단계 상위 (tools/discord-daemon/) 에 있다. import path 등록.
PARENT_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PARENT_DIR))

# discord / dotenv / requests 가 venv 에 없어도 import 가능하도록 stub.
for missing in ("discord", "requests", "dotenv"):
    if missing not in sys.modules:
        stub = mock.MagicMock()
        if missing == "dotenv":
            stub.load_dotenv = lambda *a, **kw: None
        sys.modules[missing] = stub

import bot  # noqa: E402


class MaskSecretsTests(unittest.TestCase):
    """mask_secrets 함수가 각 시크릿 패턴을 안전한 placeholder 로 치환하는지 확인."""

    def test_github_classic_pat_masked(self) -> None:
        sample = "export GITHUB_PAT=ghp_AbCdEf0123456789AbCdEf0123456789AbCd"
        masked = bot.mask_secrets(sample)
        self.assertNotIn("AbCdEf0123456789", masked)
        self.assertIn("ghp_***", masked)

    def test_github_fine_grained_pat_masked(self) -> None:
        # github_pat_ + 11자 ID + _ + 70자 secret = 총 82자 본문
        body = "11AAABBBCCC_" + ("D" * 70)
        sample = f"gh auth login --with-token <<< github_pat_{body}"
        masked = bot.mask_secrets(sample)
        self.assertNotIn(body, masked)
        self.assertIn("github_pat_***", masked)

    def test_discord_bot_token_masked(self) -> None:
        # Discord bot token: [MN] + 23자 + "." + 6자 + "." + 27+자
        token = "M" + "A" * 23 + "." + "B" * 6 + "." + "C" * 27
        sample = f"DISCORD_BOT_TOKEN={token}"
        masked = bot.mask_secrets(sample)
        self.assertNotIn(token, masked)
        # *_TOKEN= 패턴이 먼저 매칭되어 통째로 가려질 수도, discord_token_*** 일 수도
        # 어느 쪽이든 raw token 은 사라져야 함.
        self.assertTrue(
            "discord_token_***" in masked or "DISCORD_BOT_TOKEN=***" in masked,
            f"raw token leaked: {masked}",
        )

    def test_password_env_masked(self) -> None:
        sample = "mysql --user=root password=Sup3rSecret!"
        masked = bot.mask_secrets(sample)
        self.assertNotIn("Sup3rSecret!", masked)
        self.assertIn("password=***", masked)

    def test_uppercase_token_env_masked(self) -> None:
        sample = "MY_API_TOKEN=raw-secret-value-xyz"
        masked = bot.mask_secrets(sample)
        self.assertNotIn("raw-secret-value-xyz", masked)
        self.assertEqual(masked, "MY_API_TOKEN=***")

    def test_secret_env_masked(self) -> None:
        sample = "APP_SECRET=hunter2-very-long"
        masked = bot.mask_secrets(sample)
        self.assertNotIn("hunter2-very-long", masked)
        self.assertIn("APP_SECRET=***", masked)

    def test_key_env_masked(self) -> None:
        sample = "AWS_ACCESS_KEY=AKIAIOSFODNN7EXAMPLE"
        masked = bot.mask_secrets(sample)
        self.assertNotIn("AKIAIOSFODNN7EXAMPLE", masked)
        self.assertIn("AWS_ACCESS_KEY=***", masked)

    def test_benign_text_unchanged(self) -> None:
        sample = (
            "PR #742 머지 완료. test 8건 통과.\n"
            "변경 파일: tools/discord-daemon/bot.py\n"
            "다음 사이클: rev audit 시작"
        )
        masked = bot.mask_secrets(sample)
        self.assertEqual(masked, sample)

    def test_multiple_secrets_in_one_chunk(self) -> None:
        sample = (
            "환경변수 점검:\n"
            "  GH_TOKEN=ghp_AbCdEf0123456789AbCdEf0123456789AbCd\n"
            "  MYSQL password=root1234\n"
            "끝"
        )
        masked = bot.mask_secrets(sample)
        self.assertNotIn("ghp_AbCdEf", masked)
        self.assertNotIn("root1234", masked)
        # GH_TOKEN= 가 *_TOKEN= 패턴에 먼저 잡혀 통째로 마스킹될 수 있음 — raw 만 안 보이면 OK.
        self.assertTrue("***" in masked)


class SanitizeChunkAppliesMaskingTests(unittest.TestCase):
    """sanitize_chunk pipeline 이 mask_secrets 를 호출하는지 통합 검증.
    Discord 로 실제 push 되는 경로이므로 end-to-end 보장이 필요하다.
    """

    def test_sanitize_chunk_masks_pat(self) -> None:
        # MIN_CHUNK_LEN 통과를 위해 충분히 긴 입력 구성.
        raw = (
            "maestro 응답: PR 머지 직전 환경변수 확인 결과\n"
            "GITHUB_TOKEN=ghp_AbCdEf0123456789AbCdEf0123456789AbCd 노출됨\n"
            "수정 필요. 추가 컨텍스트: " + "x" * 80
        )
        result = bot.sanitize_chunk(raw)
        self.assertIsNotNone(result)
        assert result is not None  # mypy/type narrowing
        self.assertNotIn("ghp_AbCdEf", result)
        self.assertNotIn("AbCdEf0123456789AbCdEf0123456789AbCd", result)
        self.assertIn("***", result)

    def test_sanitize_chunk_preserves_non_secret_content(self) -> None:
        raw = (
            "PR #742 머지 완료.\n"
            "변경: tools/discord-daemon/bot.py 에 mask_secrets 추가.\n"
            "test 8건 통과. 다음 사이클 시작 예정. " + "필요 컨텍스트 충분히 길게 " * 5
        )
        result = bot.sanitize_chunk(raw)
        self.assertIsNotNone(result)
        assert result is not None
        # 시크릿 패턴 없으면 본문 내용 유지.
        self.assertIn("mask_secrets", result)
        self.assertIn("test 8건", result)


if __name__ == "__main__":
    unittest.main()
