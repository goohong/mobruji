#!/usr/bin/env python3
"""
Audio analysis CLI — YouTube audio extraction + librosa pitch detection.

Spec: docs/features/audio-tooling-bootstrap.md (PR A 부트스트랩)
ADR:  docs/decisions/0006-audio-source-youtube.md, 0010-self-analysis-pipeline-stack.md

운영 주의 (저작권):
  - YouTube audio는 30~60초 clip만 임시 추출하며 분석 직후 즉시 삭제한다.
  - 영구 저장/공유/2차 배포 금지. ToS 준수는 호출자 책임.

사용 예:
  python analyze.py --youtube-url "https://www.youtube.com/watch?v=xxxx"
  python analyze.py --song-title "Yesterday" --artist "The Beatles"
"""
from __future__ import annotations

import argparse
import json
import logging
import math
import os
import shutil
import sys
import tempfile
import time
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Optional

LOG = logging.getLogger("analyze")

TOOLING_VERSION = "analyze-py-0.1.0"
DEFAULT_CLIP_SECONDS = 45
PITCH_LOW_PERCENTILE = 5.0
PITCH_HIGH_PERCENTILE = 95.0


@dataclass
class SongMeta:
    title: Optional[str]
    artist: Optional[str]
    youtube_url: Optional[str]
    duration_sec: Optional[float]


@dataclass
class AnalysisResult:
    songMeta: dict
    lowMidi: Optional[int]
    highMidi: Optional[int]
    key: Optional[str]
    tempo: Optional[float]
    durationSec: Optional[float]
    confidence: float
    toolingVersion: str = TOOLING_VERSION


# ----------------------------------------------------------------------
# Pure helpers (단위 테스트 대상)
# ----------------------------------------------------------------------
def frequency_to_midi(freq_hz: float) -> Optional[int]:
    """주파수(Hz)를 MIDI note number로 변환. 0 이하나 NaN이면 None."""
    if freq_hz is None:
        return None
    try:
        f = float(freq_hz)
    except (TypeError, ValueError):
        return None
    if not math.isfinite(f) or f <= 0:
        return None
    midi = 69.0 + 12.0 * math.log2(f / 440.0)
    return int(round(midi))


def extract_range(
    frequencies: list[float],
    low_pct: float = PITCH_LOW_PERCENTILE,
    high_pct: float = PITCH_HIGH_PERCENTILE,
) -> tuple[Optional[int], Optional[int]]:
    """주파수 시퀀스에서 안정 음역대(MIDI low/high)를 percentile로 추출.

    - NaN/0/음수는 무시한다 (librosa.pyin이 unvoiced frame에서 NaN을 반환).
    - 표본이 너무 적으면 (None, None) 반환.
    """
    valid = [f for f in frequencies if f is not None and math.isfinite(f) and f > 0]
    if len(valid) < 5:
        return (None, None)

    # numpy 없이도 동작하도록 순수 파이썬 percentile 구현
    sorted_v = sorted(valid)

    def _pct(p: float) -> float:
        if not sorted_v:
            return float("nan")
        k = (len(sorted_v) - 1) * (p / 100.0)
        lo = int(math.floor(k))
        hi = int(math.ceil(k))
        if lo == hi:
            return sorted_v[lo]
        return sorted_v[lo] + (sorted_v[hi] - sorted_v[lo]) * (k - lo)

    low_hz = _pct(low_pct)
    high_hz = _pct(high_pct)
    return (frequency_to_midi(low_hz), frequency_to_midi(high_hz))


def confidence_score(voiced_ratio: float, sample_count: int) -> float:
    """voiced ratio + 표본 수로 0.0~1.0 신뢰도 산출."""
    if sample_count <= 0:
        return 0.0
    sample_bonus = min(sample_count / 500.0, 1.0)
    return round(max(0.0, min(1.0, voiced_ratio * 0.7 + sample_bonus * 0.3)), 3)


def mask_url(url: Optional[str]) -> str:
    """로깅용 URL 마스킹 (videoId 일부만 노출)."""
    if not url:
        return "<none>"
    if "v=" in url:
        vid = url.split("v=", 1)[1].split("&", 1)[0]
        return f"yt:{vid[:4]}***"
    return url[:16] + "***"


# ----------------------------------------------------------------------
# Audio extraction / analysis (외부 IO)
# ----------------------------------------------------------------------
def _download_youtube_audio(
    url: str, out_dir: Path, clip_seconds: int = DEFAULT_CLIP_SECONDS
) -> Path:
    """yt-dlp로 audio를 mp3 clip으로 추출. 30~60초 short clip."""
    import yt_dlp  # type: ignore

    out_template = str(out_dir / "clip.%(ext)s")
    ydl_opts = {
        "format": "bestaudio/best",
        "outtmpl": out_template,
        "quiet": True,
        "noprogress": True,
        "postprocessors": [
            {
                "key": "FFmpegExtractAudio",
                "preferredcodec": "mp3",
                "preferredquality": "128",
            }
        ],
        # 짧은 clip만 받기 위한 ffmpeg 옵션 (post-process)
        "postprocessor_args": ["-t", str(clip_seconds)],
    }
    with yt_dlp.YoutubeDL(ydl_opts) as ydl:
        ydl.download([url])
    candidates = list(out_dir.glob("clip.*"))
    audio = next((c for c in candidates if c.suffix in (".mp3", ".m4a", ".webm")), None)
    if audio is None or not audio.exists():
        raise RuntimeError("yt-dlp post-process 후 audio 파일 없음")
    return audio


def _build_youtube_search_url(title: str, artist: Optional[str]) -> str:
    """ytsearch 쿼리로 변환 (yt-dlp 내부 검색 핸들러)."""
    query = title if not artist else f"{artist} {title}"
    return f"ytsearch1:{query}"


def _analyze_audio_file(audio_path: Path) -> dict:
    """librosa로 pitch/key/tempo 추정."""
    import librosa  # type: ignore
    import numpy as np  # type: ignore

    y, sr = librosa.load(str(audio_path), mono=True)
    duration_sec = float(len(y) / sr) if sr else None

    # pyin pitch detection (librosa 0.10+ 기본 vocal range)
    f0, voiced_flag, _ = librosa.pyin(
        y,
        fmin=float(librosa.note_to_hz("C2")),
        fmax=float(librosa.note_to_hz("C7")),
    )
    voiced = [float(x) for x in f0 if x is not None and np.isfinite(x)]
    voiced_ratio = float(np.mean(voiced_flag)) if len(voiced_flag) else 0.0

    low_midi, high_midi = extract_range(voiced)

    # key 추정 — chroma 평균 최대치 → pitch class
    chroma = librosa.feature.chroma_cqt(y=y, sr=sr)
    pitch_classes = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]
    key_idx = int(np.argmax(np.mean(chroma, axis=1)))
    key = pitch_classes[key_idx]

    # tempo
    tempo, _ = librosa.beat.beat_track(y=y, sr=sr)
    tempo_val = float(tempo) if tempo is not None else None

    confidence = confidence_score(voiced_ratio, len(voiced))

    return {
        "lowMidi": low_midi,
        "highMidi": high_midi,
        "key": key,
        "tempo": tempo_val,
        "durationSec": duration_sec,
        "confidence": confidence,
    }


# ----------------------------------------------------------------------
# CLI entry
# ----------------------------------------------------------------------
def run_analysis(
    youtube_url: Optional[str],
    song_title: Optional[str],
    artist: Optional[str],
    clip_seconds: int = DEFAULT_CLIP_SECONDS,
) -> AnalysisResult:
    """엔드 투 엔드 분석. 임시 audio 파일은 finally에서 무조건 삭제."""
    if not youtube_url and not song_title:
        raise ValueError("--youtube-url 또는 --song-title 중 하나는 필수")

    target_url = youtube_url or _build_youtube_search_url(song_title or "", artist)

    tmp_dir = Path(tempfile.mkdtemp(prefix="audio-analysis-"))
    started = time.time()
    LOG.info("analysis start url=%s tmp=%s", mask_url(target_url), tmp_dir)
    try:
        audio_path = _download_youtube_audio(target_url, tmp_dir, clip_seconds)
        features = _analyze_audio_file(audio_path)
    finally:
        # 저작권 회피 — audio 임시 파일 즉시 삭제
        shutil.rmtree(tmp_dir, ignore_errors=True)
        LOG.info("tmp cleanup done elapsedMs=%d", int((time.time() - started) * 1000))

    meta = SongMeta(
        title=song_title,
        artist=artist,
        youtube_url=youtube_url,
        duration_sec=features.get("durationSec"),
    )
    return AnalysisResult(
        songMeta=asdict(meta),
        lowMidi=features.get("lowMidi"),
        highMidi=features.get("highMidi"),
        key=features.get("key"),
        tempo=features.get("tempo"),
        durationSec=features.get("durationSec"),
        confidence=features.get("confidence", 0.0),
    )


def _parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="YouTube audio 음역대/key/tempo 분석")
    parser.add_argument("--song-title", dest="song_title", default=None)
    parser.add_argument("--artist", dest="artist", default=None)
    parser.add_argument("--youtube-url", dest="youtube_url", default=None)
    parser.add_argument(
        "--clip-seconds",
        dest="clip_seconds",
        type=int,
        default=DEFAULT_CLIP_SECONDS,
        help="추출할 clip 길이(초). 30~60 권장.",
    )
    parser.add_argument(
        "--verbose", "-v", action="store_true", help="DEBUG 레벨 로깅"
    )
    return parser.parse_args(argv)


def main(argv: Optional[list[str]] = None) -> int:
    args = _parse_args(argv if argv is not None else sys.argv[1:])
    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
        stream=sys.stderr,
    )
    try:
        result = run_analysis(
            youtube_url=args.youtube_url,
            song_title=args.song_title,
            artist=args.artist,
            clip_seconds=args.clip_seconds,
        )
    except Exception as exc:  # noqa: BLE001 — CLI surface
        LOG.error("analysis failed: %s", exc)
        json.dump({"error": str(exc), "toolingVersion": TOOLING_VERSION}, sys.stdout)
        sys.stdout.write(os.linesep)
        return 1
    json.dump(asdict(result), sys.stdout, ensure_ascii=False)
    sys.stdout.write(os.linesep)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
