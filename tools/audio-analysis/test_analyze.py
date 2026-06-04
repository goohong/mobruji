"""analyze.py 순수 helper 단위 테스트.

외부 IO (yt-dlp/librosa)는 PR B에서 통합 테스트로 다룬다.
"""
from __future__ import annotations

import math

import pytest

from analyze import (
    METHOD_SPLEETER_2STEMS,
    METHOD_VOCAL_SKIP,
    PLAYER_CLIENT_CHAIN,
    POT_EXTRACTOR_KEY,
    analysis_method_label,
    confidence_score,
    extract_range,
    frequency_to_midi,
    harden_ydl_opts,
    mask_url,
    run_with_client_chain,
    youtube_cookies_file,
    youtube_pot_provider_url,
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


class TestAnalysisMethodLabel:
    def test_vocal_skip_when_disabled(self) -> None:
        assert analysis_method_label(False) == METHOD_VOCAL_SKIP

    def test_spleeter_when_enabled(self) -> None:
        assert analysis_method_label(True) == METHOD_SPLEETER_2STEMS

    def test_labels_are_distinct(self) -> None:
        assert METHOD_VOCAL_SKIP != METHOD_SPLEETER_2STEMS


class TestYoutubeCookiesFile:
    def test_unset_returns_none(self, monkeypatch) -> None:
        monkeypatch.delenv("YTDLP_COOKIES_FILE", raising=False)
        assert youtube_cookies_file() is None

    def test_missing_file_returns_none(self, monkeypatch, tmp_path) -> None:
        monkeypatch.setenv("YTDLP_COOKIES_FILE", str(tmp_path / "nope.txt"))
        assert youtube_cookies_file() is None

    def test_existing_file_returns_path(self, monkeypatch, tmp_path) -> None:
        cookie = tmp_path / "cookies.txt"
        cookie.write_text("# Netscape HTTP Cookie File\n")
        monkeypatch.setenv("YTDLP_COOKIES_FILE", str(cookie))
        assert youtube_cookies_file() == str(cookie)

    def test_directory_path_returns_none(self, monkeypatch, tmp_path) -> None:
        # 디렉토리는 쿠키 파일이 아니다 (예: /dev/null 마운트 fallback 도 is_file False).
        monkeypatch.setenv("YTDLP_COOKIES_FILE", str(tmp_path))
        assert youtube_cookies_file() is None


class TestYoutubePotProviderUrl:
    def test_unset_returns_none(self, monkeypatch) -> None:
        monkeypatch.delenv("YTDLP_POT_PROVIDER_URL", raising=False)
        assert youtube_pot_provider_url() is None

    def test_blank_returns_none(self, monkeypatch) -> None:
        monkeypatch.setenv("YTDLP_POT_PROVIDER_URL", "   ")
        assert youtube_pot_provider_url() is None

    def test_set_returns_trimmed_url(self, monkeypatch) -> None:
        monkeypatch.setenv("YTDLP_POT_PROVIDER_URL", "  http://bgutil-provider:4416  ")
        assert youtube_pot_provider_url() == "http://bgutil-provider:4416"


class TestHardenYdlOpts:
    def test_sets_single_player_client(self, monkeypatch) -> None:
        monkeypatch.delenv("YTDLP_COOKIES_FILE", raising=False)
        opts = harden_ydl_opts({"quiet": True}, "android")
        assert opts["extractor_args"]["youtube"]["player_client"] == ["android"]
        assert opts["quiet"] is True

    def test_does_not_mutate_base(self, monkeypatch) -> None:
        monkeypatch.delenv("YTDLP_COOKIES_FILE", raising=False)
        base = {"quiet": True}
        harden_ydl_opts(base, "web")
        assert "extractor_args" not in base

    def test_omits_cookies_when_unset(self, monkeypatch) -> None:
        monkeypatch.delenv("YTDLP_COOKIES_FILE", raising=False)
        assert "cookiefile" not in harden_ydl_opts({}, "web")

    def test_includes_cookies_when_present(self, monkeypatch, tmp_path) -> None:
        cookie = tmp_path / "cookies.txt"
        cookie.write_text("# Netscape HTTP Cookie File\n")
        monkeypatch.setenv("YTDLP_COOKIES_FILE", str(cookie))
        assert harden_ydl_opts({}, "web")["cookiefile"] == str(cookie)

    def test_omits_pot_provider_when_unset(self, monkeypatch) -> None:
        monkeypatch.delenv("YTDLP_COOKIES_FILE", raising=False)
        monkeypatch.delenv("YTDLP_POT_PROVIDER_URL", raising=False)
        assert POT_EXTRACTOR_KEY not in harden_ydl_opts({}, "web")["extractor_args"]

    def test_injects_pot_base_url_when_set(self, monkeypatch) -> None:
        monkeypatch.delenv("YTDLP_COOKIES_FILE", raising=False)
        monkeypatch.setenv("YTDLP_POT_PROVIDER_URL", "http://bgutil-provider:4416")
        opts = harden_ydl_opts({}, "web")
        assert opts["extractor_args"][POT_EXTRACTOR_KEY]["base_url"] == [
            "http://bgutil-provider:4416"
        ]
        # web client 와 PO token provider 가 함께 설정돼야 토큰으로 차단을 푼다.
        assert opts["extractor_args"]["youtube"]["player_client"] == ["web"]


class TestRunWithClientChain:
    def test_chain_covers_known_clients(self) -> None:
        assert PLAYER_CLIENT_CHAIN[0] == "web"
        assert "android" in PLAYER_CLIENT_CHAIN

    def test_falls_back_to_next_client_on_download_error(self, monkeypatch) -> None:
        yt_dlp = pytest.importorskip("yt_dlp")
        monkeypatch.delenv("YTDLP_COOKIES_FILE", raising=False)
        seen: list[str] = []

        class FakeYDL:
            def __init__(self, opts: dict) -> None:
                self.client = opts["extractor_args"]["youtube"]["player_client"][0]

            def __enter__(self) -> "FakeYDL":
                return self

            def __exit__(self, *exc) -> bool:
                return False

        monkeypatch.setattr(yt_dlp, "YoutubeDL", FakeYDL)

        def action(ydl: "FakeYDL"):
            seen.append(ydl.client)
            if ydl.client != "ios":
                raise yt_dlp.utils.DownloadError("blocked")
            return "ok"

        assert run_with_client_chain({"quiet": True}, action) == "ok"
        assert seen == ["web", "android", "ios"]

    def test_raises_last_error_when_all_clients_fail(self, monkeypatch) -> None:
        yt_dlp = pytest.importorskip("yt_dlp")
        monkeypatch.delenv("YTDLP_COOKIES_FILE", raising=False)

        class FakeYDL:
            def __init__(self, opts: dict) -> None:
                pass

            def __enter__(self) -> "FakeYDL":
                return self

            def __exit__(self, *exc) -> bool:
                return False

        monkeypatch.setattr(yt_dlp, "YoutubeDL", FakeYDL)

        def action(ydl: "FakeYDL"):
            raise yt_dlp.utils.DownloadError("blocked")

        with pytest.raises(yt_dlp.utils.DownloadError):
            run_with_client_chain({}, action, clients=("web", "android"))


def test_module_has_tooling_version() -> None:
    from analyze import TOOLING_VERSION

    assert TOOLING_VERSION.startswith("analyze-py-")


def test_result_defaults_to_vocal_skip_method() -> None:
    from analyze import AnalysisResult

    result = AnalysisResult(
        songMeta={},
        lowMidi=55,
        highMidi=71,
        key="C",
        tempo=120.0,
        durationSec=45.0,
        confidence=0.8,
    )
    assert result.analysisMethod == METHOD_VOCAL_SKIP
