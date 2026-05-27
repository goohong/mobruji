"""Discord mention sanitize 단위 테스트 (#754).

PR title / maestro tmux pane chunk 의 `@everyone` / `@here` / `<@USER_ID>` 등
Discord mention 토큰이 zero-width space (U+200B) 삽입으로 무력화되는지 검증.

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

ZWSP = "​"  # zero-width space


class SanitizeMentionsTests(unittest.TestCase):
    """sanitize_mentions: Discord mention 토큰을 ZWSP 로 무력화."""

    def test_everyone_neutralized(self) -> None:
        result = bot.sanitize_mentions("hello @everyone bye")
        self.assertNotIn("@everyone", result)
        self.assertIn(f"@{ZWSP}everyone", result)

    def test_here_neutralized(self) -> None:
        result = bot.sanitize_mentions("ping @here please")
        self.assertNotIn("@here", result)
        self.assertIn(f"@{ZWSP}here", result)

    def test_user_mention_neutralized(self) -> None:
        result = bot.sanitize_mentions("ping <@123456789012345678> now")
        self.assertNotIn("<@1", result)
        self.assertIn(f"<{ZWSP}@123456789012345678>", result)

    def test_user_mention_with_bang_neutralized(self) -> None:
        # `<@!USER_ID>` — nickname mention 변형.
        result = bot.sanitize_mentions("hey <@!987654321098765432> wake up")
        self.assertNotIn("<@!", result)
        self.assertIn(f"<{ZWSP}@!987654321098765432>", result)

    def test_role_mention_neutralized(self) -> None:
        # `<@&ROLE_ID>` — role mention.
        result = bot.sanitize_mentions("alert <@&111222333444555666> rollout")
        self.assertNotIn("<@&", result)
        self.assertIn(f"<{ZWSP}@&111222333444555666>", result)

    def test_plain_text_unchanged(self) -> None:
        # mention 토큰 없으면 원문 유지.
        sample = "fix(infra): digest mention sanitize"
        self.assertEqual(bot.sanitize_mentions(sample), sample)

    def test_lone_at_not_touched(self) -> None:
        # `@` 단독 (email, decorator 등) 은 mention 아니므로 변경하지 않음.
        sample = "contact me at user@example.com"
        self.assertEqual(bot.sanitize_mentions(sample), sample)

    def test_lone_angle_at_without_digit_not_touched(self) -> None:
        # `<@>` 처럼 ID 없는 경우는 mention 으로 해석되지 않으므로 변경 없음.
        sample = "literal <@> token"
        self.assertEqual(bot.sanitize_mentions(sample), sample)

    def test_multiple_mentions_in_one_string(self) -> None:
        # 여러 mention 토큰이 한 문자열에 섞여 있어도 모두 무력화.
        result = bot.sanitize_mentions("@everyone and @here plus <@!42424242424242>")
        self.assertNotIn("@everyone", result)
        self.assertNotIn("@here", result)
        self.assertNotIn("<@!", result)
        self.assertIn(f"@{ZWSP}everyone", result)
        self.assertIn(f"@{ZWSP}here", result)
        self.assertIn(f"<{ZWSP}@!42424242424242", result)


if __name__ == "__main__":
    unittest.main()
