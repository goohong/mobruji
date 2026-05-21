"""analyze.py 순수 helper 단위 테스트.

외부 IO (yt-dlp/librosa)는 PR B에서 통합 테스트로 다룬다.
"""
from __future__ import annotations

import math

import pytest

from analyze import (
    confidence_score,
    extract_range,
    frequency_to_midi,
    mask_url,
)


class TestFrequencyToMidi:
    def test_a4_is_69(self) -> None:
        assert frequency_to_midi(440.0) == 69

    def test_c4_is_60(self) -> None:
        # C4 = 261.625565 Hz
        assert frequency_to_midi(261.625565) == 60

    def test_a3_is_57(self) -> None:
        assert frequency_to_midi(220.0) == 57

    def test_zero_returns_none(self) -> None:
        assert frequency_to_midi(0) is None

    def test_negative_returns_none(self) -> None:
        assert frequency_to_midi(-100.0) is None

    def test_nan_returns_none(self) -> None:
        assert frequency_to_midi(float("nan")) is None

    def test_none_returns_none(self) -> None:
        assert frequency_to_midi(None) is None  # type: ignore[arg-type]

    def test_string_returns_none(self) -> None:
        assert frequency_to_midi("not-a-number") is None  # type: ignore[arg-type]


class TestExtractRange:
    def test_returns_none_when_too_few_samples(self) -> None:
        assert extract_range([440.0, 441.0]) == (None, None)

    def test_returns_none_when_all_invalid(self) -> None:
        nan = float("nan")
        assert extract_range([0, -1, nan, nan, nan, nan]) == (None, None)

    def test_extracts_low_high_midi_from_vocal_range(self) -> None:
        # G3(196Hz) ~ G4(392Hz) 균등 분포 + 양쪽 outlier
        samples = [98.0]  # G2 outlier (5pct 미만으로 잘려야 함)
        samples += [196.0 + i for i in range(0, 197, 2)]  # G3 ~ G4
        samples += [784.0]  # G5 outlier
        low, high = extract_range(samples)
        # 5~95 percentile은 outlier를 제거하므로 G3(55) ~ G4(67) 근방
        assert low is not None and high is not None
        assert 54 <= low <= 60
        assert 64 <= high <= 70
        assert low <= high

    def test_filters_nan_and_nonpositive(self) -> None:
        nan = float("nan")
        samples = [nan, 0, -10, 220.0, 230.0, 240.0, 250.0, 260.0, 270.0]
        low, high = extract_range(samples)
        assert low is not None
        assert high is not None
        assert low <= high


class TestConfidenceScore:
    def test_zero_samples_is_zero(self) -> None:
        assert confidence_score(1.0, 0) == 0.0

    def test_high_voiced_high_samples_near_one(self) -> None:
        score = confidence_score(0.9, 1000)
        assert score >= 0.9
        assert score <= 1.0

    def test_low_voiced_low_samples_near_zero(self) -> None:
        score = confidence_score(0.1, 10)
        assert score < 0.2

    def test_voiced_ratio_dominates_capped(self) -> None:
        # voiced 1.0 + sample 0 보너스 → 0.7
        assert confidence_score(1.0, 1) <= 0.8
        # voiced 0.0 + sample 풍부 → 0.3
        assert confidence_score(0.0, 500) == pytest.approx(0.3, abs=0.01)


class TestMaskUrl:
    def test_none(self) -> None:
        assert mask_url(None) == "<none>"

    def test_youtube_watch_url_masks_videoid(self) -> None:
        url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ&feature=share"
        masked = mask_url(url)
        assert "dQw4" in masked
        assert "w9WgXcQ" not in masked

    def test_non_youtube_truncates(self) -> None:
        url = "https://example.com/very/long/secret/path"
        masked = mask_url(url)
        assert len(masked) < len(url)
        assert "secret" not in masked


def test_module_has_tooling_version() -> None:
    from analyze import TOOLING_VERSION

    assert TOOLING_VERSION.startswith("analyze-py-")
