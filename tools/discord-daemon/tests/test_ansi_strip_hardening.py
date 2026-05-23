"""ANSI strip 풀세트 강화 단위 테스트 (#797).

사용자 실 사례 (`H*✶✻●✽✻✶*8 ✢·✢*●✶✻✽9✻ ✶3*✢·●✢50*✶✻✽`) 를 포함해
ANSI escape sequence + tmux/claude TUI spinner glyph 제거가 동작하는지 검증.

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


class AnsiStripFullSetTests(unittest.TestCase):
    """ANSI strip 풀세트: CSI/OSC/DEC private/단일 ESC/BEL 모두 cover."""

    def test_dec_private_charset_designator_removed(self) -> None:
        # ESC ( B → ASCII charset designator. 기존 패턴은 단일 ESC 로만 잡아
        # `B` 가 남았다. 풀세트는 designator letter 까지 제거한다.
        text = "before\x1b(Bafter"
        self.assertEqual(bot.strip_ansi(text), "beforeafter")

    def test_dec_private_g1_designator_removed(self) -> None:
        # ESC ) 0 → G1 line drawing 지정.
        text = "x\x1b)0y"
        self.assertEqual(bot.strip_ansi(text), "xy")

    def test_bel_character_removed(self) -> None:
        # 단순 BEL 만 단독으로 들어온 경우.
        text = "alert\x07done"
        self.assertEqual(bot.strip_ansi(text), "alertdone")

    def test_multiple_csi_combined(self) -> None:
        # SGR 컬러 + 커서 이동 + 화면 클리어가 한 문장에 섞인 경우.
        text = "\x1b[2J\x1b[H\x1b[31mhello\x1b[0m\x1b[1;1Hworld"
        self.assertEqual(bot.strip_ansi(text), "helloworld")


class SpinnerGlyphStripTests(unittest.TestCase):
    """tmux/claude TUI spinner glyph 잔재 제거."""

    def test_star_spinner_cluster_removed(self) -> None:
        # 별표/꽃 모양 spinner glyph 는 단일/cluster 모두 제거.
        text = "before✶✻●✽after"
        self.assertEqual(bot.strip_ansi(text), "beforeafter")

    def test_single_spinner_glyph_also_removed(self) -> None:
        # 단일 ✶ 도 spinner 잔재로 판단해 제거. (정상 한글 본문에는 거의 나오지 않음.)
        self.assertEqual(bot.strip_ansi("hello ✶ world"), "hello  world")

    def test_braille_spinner_removed(self) -> None:
        # braille 점자 spinner (U+2800-U+28FF) 연속 제거.
        text = "load⠂⠁⠃⠉ed"
        self.assertEqual(bot.strip_ansi(text), "loaded")

    def test_single_asterisk_preserved(self) -> None:
        # 본문의 단일 `*` (footnote/markdown 표기) 는 보존 — 연속 2개 이상만 제거.
        text = "포인터*를 참고하세요."
        self.assertEqual(bot.strip_ansi(text), "포인터*를 참고하세요.")

    def test_markdown_bold_preserved(self) -> None:
        # Discord push 본문에는 `**bold**` 가 정상 본문 — spinner 패턴에서 제외.
        # digest/status 메시지가 강조 표기를 사용한다.
        text = "**진행 중**"
        self.assertEqual(bot.strip_ansi(text), "**진행 중**")


class UserRealCaseRegressionTests(unittest.TestCase):
    """사용자 본 실 사례 그대로 (#797 트리거)."""

    def test_user_observed_glyph_blob(self) -> None:
        # `H*✶✻●✽✻✶*8 ✢·✢*●✶✻✽9✻ ✶3*✢·●✢50*✶✻✽` — 사용자 헷갈림 사례.
        # ANSI escape 가 아닌 raw spinner glyph 만 남은 형태.
        # spinner 핵심 glyph (✶✻●✽✢) 가 모두 제거되어야 한다.
        # 단, `·` (middle dot) 와 `*` 는 정상 본문에서도 쓰이므로 spinner 제거 대상 X.
        raw = "H*✶✻●✽✻✶*8 ✢·✢*●✶✻✽9✻ ✶3*✢·●✢50*✶✻✽"
        cleaned = bot.strip_ansi(raw)
        for glyph in ("✶", "✻", "●", "✽", "✢"):
            self.assertNotIn(
                glyph,
                cleaned,
                f"spinner glyph {glyph} 가 strip_ansi 후에도 남아있다: {cleaned!r}",
            )

    def test_user_observed_blob_sanitize_chunk_drops_as_noise(self) -> None:
        # sanitize_chunk 까지 통과시키면 noise line drop 으로 None 또는 빈 결과.
        raw = "H*✶✻●✽✻✶*8 ✢·✢*●✶✻✽9✻ ✶3*✢·●✢50*✶✻✽"
        result = bot.sanitize_chunk(raw)
        # 최소 길이(100) 미만이므로 None 이거나, partial 잔재가 noise 로 잡혀 사라짐.
        self.assertIsNone(result)


class NoiseLineDetectorTests(unittest.TestCase):
    """_is_noise_line — TUI partial line 식별."""

    def test_short_star_heavy_line_is_noise(self) -> None:
        # 별표 비율 15% 이상 + 짧음.
        self.assertTrue(bot._is_noise_line("H*8 ***9** 3*50*"))

    def test_short_digit_heavy_line_is_noise(self) -> None:
        # 숫자+공백 비율 70% 이상.
        self.assertTrue(bot._is_noise_line("  8  9  3  50  "))

    def test_normal_sentence_is_not_noise(self) -> None:
        # 영문자/한글 글자 4 이상이면 통과.
        self.assertFalse(bot._is_noise_line("작업을 시작합니다"))

    def test_long_line_is_not_noise(self) -> None:
        # 길이 40자 이상이면 통과 (긴 진행 메시지는 의미 가능).
        line = "x" * 50
        self.assertFalse(bot._is_noise_line(line))


class SanitizeChunkLineBufferingTests(unittest.TestCase):
    """sanitize_chunk 가 noise line 만 drop 하고 본문은 보존."""

    def test_keeps_meaningful_lines_drops_partial_glyph_line(self) -> None:
        # spinner partial line + 본문 라인 혼합.
        text = (
            "H*✶✻●✽9*\n"
            + "작업을 완료했습니다. PR #797 을 생성했습니다. " * 5
        )
        out = bot.sanitize_chunk(text)
        self.assertIsNotNone(out)
        # 본문은 살아있어야 한다.
        self.assertIn("작업을 완료했습니다", out or "")
        # spinner glyph 잔재는 모두 사라져야 한다.
        for glyph in ("✶", "✻", "●", "✽"):
            self.assertNotIn(glyph, out or "")


if __name__ == "__main__":
    unittest.main()
