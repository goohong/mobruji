#!/usr/bin/env python3
"""Batch audio-analysis 파이프라인 — 시드 곡 묶음 end-to-end 분석 + 정확도 검증.

단일 곡 분석(analyze.py)을 시드 JSON의 곡 묶음에 대해 순차 실행하고,
backfill-ready NDJSON feed 를 emit 한다. `--ground-truth` 모드에서는 시드의
라벨(label)과 분석 결과를 비교해 lowMidi/highMidi MAE · key 정확도 · confidence
평균을 정확도 리포트로 로깅한다.

Spec: docs/features/song-self-analysis-pipeline.md
  - §5-4 데이터 흐름 (yt-dlp → Spleeter → librosa → 결과)
  - §10-4 ground truth set + 회귀 가드 (허용 오차)
  - §10-8 vocal range = self-analysis 1차 권위 (metadataSource=AUDIO_ANALYSIS)

운영 주의:
  - 디스크: audio/모델 임시 파일은 /data 경로(--tmpdir, default /data/tmp)에 두고
    분석 직후 삭제한다. analyze.py 가 tempfile.mkdtemp + rmtree 로 보장하며,
    본 batch 는 TMPDIR 을 /data/tmp 로 고정해 큰 파일이 시스템 / 에 쌓이지 않게 한다.
  - 저작권: 분석 목적 pitch 추출만. 원본 audio / vocal stem 영구 저장·재배포 금지.
    시드 / feed / 정확도 리포트는 메타데이터(URL·MIDI·confidence)만 담는다.

사용 예:
  # 시드 분석 + feed NDJSON 생성 + 정확도 리포트
  python batch_analyze.py --seed tests/validation_set.json \
    --out /data/tmp/feed.ndjson --ground-truth

  # 네트워크/의존성 없이 사전 feed 로 정확도만 재계산 (offline)
  python batch_analyze.py --seed tests/validation_set.json \
    --from-results /data/tmp/feed.ndjson --ground-truth

  # 라벨 없는 신규 임포트 곡 — 음역 합리성(가창 범위) 검증 후 backfill (directive #1716)
  python batch_analyze.py --seed tests/new-songs-verification.json \
    --out /data/tmp/new-feed.ndjson --plausibility

  # 음역 미보유 곡 대량 backfill — 안전 단위(20곡) chunk + rate 대기로 반복 실행 (directive #1734)
  # 같은 명령을 반복하면 --resume 가 완료 곡을 skip 해 진척이 누적되고, --limit 으로
  # invocation 당 처리량을 한정해 디스크/rate 폭주를 막는다. 시드(미보유 곡 id+YouTube)는
  # DB 후보(SongAudioBackfillCommand findCandidatesForBackfill) 에서 운영 단계에 생성한다.
  python batch_analyze.py --seed /data/tmp/missing-range-candidates.json \
    --out /data/tmp/backfill.ndjson --resume /data/tmp/backfill.ndjson \
    --limit 20 --sleep-seconds 3 --plausibility
"""
from __future__ import annotations

import argparse
import json
import logging
import os
import sys
import time
from pathlib import Path
from typing import Optional

LOG = logging.getLogger("batch-analyze")

METADATA_SOURCE_AUDIO_ANALYSIS = "AUDIO_ANALYSIS"
DEFAULT_TMPDIR = "/data/tmp"

# §10-4 허용 오차 (회귀 가드). semitone 단위.
TOLERANCE_MAE_SEMITONE = 2.0
TOLERANCE_MAX_SEMITONE = 4.0
TOLERANCE_CONFIDENCE_MEAN = 0.6

# 라벨 없는 신규곡 backfill 가드 — 사람 가창 음역의 물리적 타당 범위(MIDI).
# 신규 임포트 곡은 ground truth 라벨이 없어 compute_accuracy 로 검증할 수 없다.
# 자체분석 결과를 추천에 적재(backfill)하기 전, 범위가 가창적으로 합리적인지 판정해
# 분석 오류(예: 반주 저음 오검출, 옥타브 폴딩)를 걸러낸다. directive #1716.
PLAUSIBLE_MIDI_FLOOR = 36   # C2 — 이 미만 lowMidi 는 가창 음역 밖(반주 저음 의심)
PLAUSIBLE_MIDI_CEIL = 88    # E6 — 이 초과 highMidi 는 가창 음역 밖
PLAUSIBLE_LOW_MAX = 67      # G4 — "최저음"이 이보다 높으면 분석 오류 의심
PLAUSIBLE_HIGH_MIN = 52     # E3 — "최고음"이 이보다 낮으면 분석 오류 의심
PLAUSIBLE_SPAN_MIN = 5      # 단4도 미만 음역폭은 멜로디로 비현실적
PLAUSIBLE_SPAN_MAX = 40     # 3옥타브+ 음역폭은 단일 멜로디로 비현실적


# ----------------------------------------------------------------------
# Pure helpers (단위 테스트 대상 — librosa/yt-dlp 불요)
# ----------------------------------------------------------------------
def resolve_tmpdir(explicit: Optional[str]) -> Path:
    """임시 작업 디렉토리를 결정하고 TMPDIR 환경변수를 고정한다.

    우선순위: --tmpdir > AUDIO_ANALYSIS_TMPDIR > /data/tmp.
    analyze.py 의 tempfile.mkdtemp 이 이 TMPDIR 을 사용하므로 audio/stem 임시
    파일이 시스템 / 가 아닌 /data 영속 볼륨에 쌓인다(디스크 안전 — directive).
    """
    chosen = explicit or os.environ.get("AUDIO_ANALYSIS_TMPDIR") or DEFAULT_TMPDIR
    path = Path(chosen)
    path.mkdir(parents=True, exist_ok=True)
    os.environ["TMPDIR"] = str(path)
    return path


def load_seed(path: str) -> list[dict]:
    """시드 JSON 을 곡 리스트로 로드한다.

    허용 형식: {"songs": [...]} 또는 최상위 배열 [...].
    각 곡은 최소 id 와 (youtubeUrl 또는 title) 을 가져야 한다.
    """
    with open(path, encoding="utf-8") as handle:
        data = json.load(handle)
    songs = data.get("songs") if isinstance(data, dict) else data
    if not isinstance(songs, list):
        raise ValueError("시드 형식 오류: songs 배열이 필요합니다")
    for index, song in enumerate(songs):
        if not isinstance(song, dict) or "id" not in song:
            raise ValueError(f"시드[{index}] 에 id 필드가 없습니다")
        if not song.get("youtubeUrl") and not song.get("title"):
            raise ValueError(f"시드[{song['id']}] 에 youtubeUrl 또는 title 이 필요합니다")
    return songs


def abs_semitone_error(predicted: Optional[float], label: Optional[float]) -> Optional[float]:
    """예측 MIDI 와 라벨 MIDI 의 절대 오차(semitone). 한쪽이라도 없으면 None."""
    if predicted is None or label is None:
        return None
    return abs(float(predicted) - float(label))


def _mean(values: list[float]) -> Optional[float]:
    valid = [v for v in values if v is not None]
    if not valid:
        return None
    return round(sum(valid) / len(valid), 3)


def _max(values: list[float]) -> Optional[float]:
    valid = [v for v in values if v is not None]
    if not valid:
        return None
    return round(max(valid), 3)


def compute_accuracy(results: list[dict], seed: list[dict]) -> dict:
    """분석 feed 와 시드 라벨을 id 로 조인해 정확도 지표를 산출한다(순수 함수).

    feed 항목: {"id", "status", "lowMidi", "highMidi", "key", "confidence", ...}
    시드 항목: {"id", "label": {"lowMidi", "highMidi", "key"}, ...}
    라벨이 없는 곡 또는 status != success 인 곡은 비교에서 제외한다.
    """
    labels = {song["id"]: song.get("label") for song in seed}
    by_id = {item.get("id"): item for item in results}

    per_song: list[dict] = []
    low_errors: list[float] = []
    high_errors: list[float] = []
    key_hits: list[int] = []
    confidences: list[float] = []

    for song_id, label in labels.items():
        item = by_id.get(song_id)
        if label is None or item is None or item.get("status") != "success":
            continue
        low_err = abs_semitone_error(item.get("lowMidi"), label.get("lowMidi"))
        high_err = abs_semitone_error(item.get("highMidi"), label.get("highMidi"))
        key_hit = (
            1
            if label.get("key") is not None and item.get("key") == label.get("key")
            else 0
        )
        confidence = item.get("confidence")
        if low_err is not None:
            low_errors.append(low_err)
        if high_err is not None:
            high_errors.append(high_err)
        if label.get("key") is not None:
            key_hits.append(key_hit)
        if confidence is not None:
            confidences.append(float(confidence))
        per_song.append(
            {
                "id": song_id,
                "lowMidiError": low_err,
                "highMidiError": high_err,
                "keyMatch": bool(key_hit) if label.get("key") is not None else None,
                "confidence": confidence,
            }
        )

    key_accuracy = round(sum(key_hits) / len(key_hits), 3) if key_hits else None
    metrics = {
        "songsTotal": len(seed),
        "songsCompared": len(per_song),
        "lowMidiMae": _mean(low_errors),
        "highMidiMae": _mean(high_errors),
        "lowMidiMax": _max(low_errors),
        "highMidiMax": _max(high_errors),
        "keyAccuracy": key_accuracy,
        "confidenceMean": _mean(confidences),
        "perSong": per_song,
    }
    metrics["withinTolerance"] = accuracy_within_tolerance(metrics)
    return metrics


def accuracy_within_tolerance(metrics: dict) -> bool:
    """§10-4 회귀 가드 — MAE ≤ 2 / MAX ≤ 4 / confidence 평균 ≥ 0.6 충족 여부.

    비교 표본이 없으면(songsCompared == 0) 판정 불가 → False.
    """
    if metrics.get("songsCompared", 0) == 0:
        return False
    checks = [
        (metrics.get("lowMidiMae"), TOLERANCE_MAE_SEMITONE, "le"),
        (metrics.get("highMidiMae"), TOLERANCE_MAE_SEMITONE, "le"),
        (metrics.get("lowMidiMax"), TOLERANCE_MAX_SEMITONE, "le"),
        (metrics.get("highMidiMax"), TOLERANCE_MAX_SEMITONE, "le"),
        (metrics.get("confidenceMean"), TOLERANCE_CONFIDENCE_MEAN, "ge"),
    ]
    for value, bound, op in checks:
        if value is None:
            return False
        if op == "le" and value > bound:
            return False
        if op == "ge" and value < bound:
            return False
    return True


def format_accuracy_report(metrics: dict) -> str:
    """정확도 지표를 사람이 읽는 로그 블록으로 포맷한다."""
    lines = [
        "=== audio-analysis 정확도 리포트 ===",
        f"곡 수: 전체 {metrics['songsTotal']} / 비교 {metrics['songsCompared']}",
        f"lowMidi  MAE={metrics['lowMidiMae']} MAX={metrics['lowMidiMax']} (허용 MAE≤{TOLERANCE_MAE_SEMITONE} MAX≤{TOLERANCE_MAX_SEMITONE})",
        f"highMidi MAE={metrics['highMidiMae']} MAX={metrics['highMidiMax']} (허용 MAE≤{TOLERANCE_MAE_SEMITONE} MAX≤{TOLERANCE_MAX_SEMITONE})",
        f"key 정확도: {metrics['keyAccuracy']}",
        f"confidence 평균: {metrics['confidenceMean']} (허용 ≥{TOLERANCE_CONFIDENCE_MEAN})",
        f"회귀 가드 통과: {metrics['withinTolerance']}",
        "곡별:",
    ]
    for row in metrics["perSong"]:
        lines.append(
            f"  - {row['id']}: lowErr={row['lowMidiError']} "
            f"highErr={row['highMidiError']} keyMatch={row['keyMatch']} "
            f"conf={row['confidence']}"
        )
    return "\n".join(lines)


def range_plausibility(record: dict) -> dict:
    """단일 feed 레코드의 음역(lowMidi/highMidi)이 가창적으로 합리적인지 판정한다(순수 함수).

    신규 임포트 곡은 ground truth 라벨이 없어 compute_accuracy 로 검증할 수 없다.
    자체분석 결과를 추천에 backfill 하기 전, 사람 가창 음역의 물리적 한계와 멜로디
    음역폭 상식 안에 드는지(=합리적 범위) 확인해 분석 오류를 걸러낸다.

    반환: {"id", "plausible": bool, "reasons": [str, ...]}.
    status != success 또는 MIDI 누락 시 plausible=False.
    """
    song_id = record.get("id")
    if record.get("status") != "success":
        return {"id": song_id, "plausible": False, "reasons": ["status != success"]}
    low = record.get("lowMidi")
    high = record.get("highMidi")
    if low is None or high is None:
        return {"id": song_id, "plausible": False, "reasons": ["lowMidi/highMidi 누락"]}
    low = int(low)
    high = int(high)
    reasons: list[str] = []
    if low >= high:
        reasons.append(f"lowMidi({low}) >= highMidi({high})")
    if low < PLAUSIBLE_MIDI_FLOOR:
        reasons.append(f"lowMidi({low}) < {PLAUSIBLE_MIDI_FLOOR}(C2)")
    if low > PLAUSIBLE_LOW_MAX:
        reasons.append(f"lowMidi({low}) > {PLAUSIBLE_LOW_MAX}(G4)")
    if high > PLAUSIBLE_MIDI_CEIL:
        reasons.append(f"highMidi({high}) > {PLAUSIBLE_MIDI_CEIL}(E6)")
    if high < PLAUSIBLE_HIGH_MIN:
        reasons.append(f"highMidi({high}) < {PLAUSIBLE_HIGH_MIN}(E3)")
    if low < high:  # 음역폭은 low < high 일 때만 의미가 있다
        span = high - low
        if span < PLAUSIBLE_SPAN_MIN:
            reasons.append(f"음역폭({span}) < {PLAUSIBLE_SPAN_MIN} 반음")
        if span > PLAUSIBLE_SPAN_MAX:
            reasons.append(f"음역폭({span}) > {PLAUSIBLE_SPAN_MAX} 반음")
    return {"id": song_id, "plausible": not reasons, "reasons": reasons}


def summarize_plausibility(records: list[dict]) -> dict:
    """feed 전체의 음역 타당성을 집계한다(라벨 불요 — 신규곡 backfill 검증용).

    분석은 성공했으나 범위가 비합리적인 곡(implausible)은 추천 backfill 에서
    제외해야 한다 — 잘못된 음역이 추천 결과를 오염시키지 않도록.
    """
    per_song = [range_plausibility(item) for item in records]
    status_by_id = {item.get("id"): item.get("status") for item in records}
    success_count = sum(1 for item in records if item.get("status") == "success")
    plausible = [row for row in per_song if row["plausible"]]
    implausible_success = [
        row
        for row in per_song
        if not row["plausible"] and status_by_id.get(row["id"]) == "success"
    ]
    return {
        "songsTotal": len(records),
        "songsSuccess": success_count,
        "plausibleCount": len(plausible),
        "implausibleCount": len(implausible_success),
        "plausibleRate": (
            round(len(plausible) / success_count, 3) if success_count else None
        ),
        "backfillReady": [row["id"] for row in plausible],
        "blocked": [
            {"id": row["id"], "reasons": row["reasons"]} for row in implausible_success
        ],
        "perSong": per_song,
    }


def format_plausibility_report(summary: dict) -> str:
    """음역 타당성 집계를 사람이 읽는 로그 블록으로 포맷한다."""
    lines = [
        "=== audio-analysis 음역 타당성(합리적 범위) 리포트 ===",
        f"곡 수: 전체 {summary['songsTotal']} / 분석성공 {summary['songsSuccess']}",
        f"타당(backfill 안전): {summary['plausibleCount']} / 비합리(차단): {summary['implausibleCount']}",
        f"타당 비율: {summary['plausibleRate']}",
        (
            f"가창 음역 한계: low∈[{PLAUSIBLE_MIDI_FLOOR},{PLAUSIBLE_LOW_MAX}] "
            f"high∈[{PLAUSIBLE_HIGH_MIN},{PLAUSIBLE_MIDI_CEIL}] "
            f"span∈[{PLAUSIBLE_SPAN_MIN},{PLAUSIBLE_SPAN_MAX}]"
        ),
    ]
    if summary["blocked"]:
        lines.append("차단된 곡(분석 성공이나 범위 비합리 — backfill 제외):")
        for row in summary["blocked"]:
            lines.append(f"  - {row['id']}: {', '.join(row['reasons'])}")
    lines.append(f"backfill 대상 id: {summary['backfillReady']}")
    return "\n".join(lines)


def to_feed_record(song_id: str, result: object, method: str) -> dict:
    """analyze.AnalysisResult 를 backfill-ready feed 레코드로 변환한다.

    metadataSource=AUDIO_ANALYSIS — vocal range 는 self-analysis 가 1차 권위(§10-8).
    """
    return {
        "id": song_id,
        "status": "success",
        "metadataSource": METADATA_SOURCE_AUDIO_ANALYSIS,
        "lowMidi": getattr(result, "lowMidi", None),
        "highMidi": getattr(result, "highMidi", None),
        "key": getattr(result, "key", None),
        "tempo": getattr(result, "tempo", None),
        "durationSec": getattr(result, "durationSec", None),
        "confidence": getattr(result, "confidence", None),
        "analysisMethod": getattr(result, "analysisMethod", method),
        "toolingVersion": getattr(result, "toolingVersion", None),
    }


def resume_existing(resume_path: Optional[str]) -> list[dict]:
    """resume feed 를 로드한다. 경로 미지정 또는 첫 실행(파일 없음)이면 빈 리스트.

    반복 실행에서는 --resume 와 --out 을 같은 경로로 두므로 첫 invocation 에는
    파일이 아직 없다 — 이를 "진척 없음"으로 취급해 정상 진행한다.
    """
    if not resume_path or not Path(resume_path).exists():
        return []
    return load_results(resume_path)


def done_ids_from_feed(records: list[dict]) -> set:
    """resume 시 재분석을 건너뛸 곡 id 집합 — status success 인 곡만.

    실패(status != success)곡은 포함하지 않아 다음 invocation 에서 재시도된다.
    """
    return {record.get("id") for record in records if record.get("status") == "success"}


def select_pending(seed: list[dict], done_ids: set, limit: Optional[int]) -> list[dict]:
    """이번 invocation 에서 분석할 곡을 고른다(순수 함수 — 안전 단위 반복의 핵심).

    이미 완료된 곡(done_ids)을 제외하고, limit 이 주어지면 시드 순서 앞에서 N곡만
    chunk 한다. 미보유 곡 수백 곡을 한 번에 돌려 디스크/rate 가 폭주하지 않도록
    invocation 당 처리량을 한정하고, resume(done_ids)으로 반복 실행 시 진척이
    누적되게 한다. directive #1734.
    """
    pending = [song for song in seed if song["id"] not in done_ids]
    if limit is not None:
        pending = pending[:limit]
    return pending


def merge_feed(existing: list[dict], fresh: list[dict]) -> list[dict]:
    """기존 feed 와 이번 invocation 결과를 id 기준 병합한다(새 결과 우선).

    같은 id 는 새 결과로 갱신(실패→성공 재시도 반영), 기존 곡의 순서는 보존하고
    신규 곡은 뒤에 덧붙인다. 반복 실행이 단일 누적 feed 로 모이게 한다.
    """
    by_id: dict = {record.get("id"): record for record in existing}
    for record in fresh:
        by_id[record.get("id")] = record
    return list(by_id.values())


def load_results(path: str) -> list[dict]:
    """NDJSON feed 를 레코드 리스트로 로드한다(offline 정확도 재계산용)."""
    records: list[dict] = []
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if line:
                records.append(json.loads(line))
    return records


# ----------------------------------------------------------------------
# IO — 곡 묶음 분석 (외부 yt-dlp/librosa 호출)
# ----------------------------------------------------------------------
def run_batch(
    seed: list[dict],
    clip_seconds: int,
    vocal_separation: bool,
    sleep_seconds: float = 0.0,
) -> list[dict]:
    """시드 곡을 순차 분석한다. 곡 단위 실패는 격리되어 batch 가 중단되지 않는다.

    sleep_seconds > 0 이면 곡 사이에 대기해 YouTube rate limit 을 완화한다(directive #1734).
    """
    import analyze  # 외부 의존성(yt-dlp/librosa)은 analyze 내부에서 lazy import

    method = analyze.analysis_method_label(vocal_separation)
    records: list[dict] = []
    successful = 0
    failed = 0
    for index, song in enumerate(seed):
        if index > 0 and sleep_seconds > 0:
            time.sleep(sleep_seconds)
        song_id = song["id"]
        started = time.time()
        try:
            result = analyze.run_analysis(
                youtube_url=song.get("youtubeUrl"),
                song_title=song.get("title"),
                artist=song.get("artist"),
                clip_seconds=clip_seconds,
                vocal_separation=vocal_separation,
            )
            record = to_feed_record(song_id, result, method)
            successful += 1
            LOG.info(
                "song done id=%s low=%s high=%s key=%s conf=%s elapsedMs=%d",
                song_id,
                record["lowMidi"],
                record["highMidi"],
                record["key"],
                record["confidence"],
                int((time.time() - started) * 1000),
            )
        except Exception as exc:  # noqa: BLE001 — 곡 단위 실패 격리
            record = {"id": song_id, "status": "failed", "error": str(exc)}
            failed += 1
            LOG.error("song failed id=%s error=%s", song_id, exc)
        records.append(record)

    LOG.info(
        "batch done total=%d successful=%d failed=%d method=%s",
        len(seed),
        successful,
        failed,
        method,
    )
    return records


def write_feed(records: list[dict], out_path: Optional[str]) -> None:
    """feed 레코드를 NDJSON 으로 기록(--out 없으면 stdout)."""
    if out_path:
        with open(out_path, "w", encoding="utf-8") as handle:
            for record in records:
                handle.write(json.dumps(record, ensure_ascii=False) + os.linesep)
        LOG.info("feed written path=%s records=%d", out_path, len(records))
    else:
        for record in records:
            sys.stdout.write(json.dumps(record, ensure_ascii=False) + os.linesep)


# ----------------------------------------------------------------------
# CLI
# ----------------------------------------------------------------------
def _parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="시드 곡 묶음 batch 분석 + 정확도 검증")
    parser.add_argument("--seed", required=True, help="시드 JSON 경로(곡 + 라벨)")
    parser.add_argument("--out", default=None, help="feed NDJSON 출력 경로(미지정 시 stdout)")
    parser.add_argument(
        "--from-results",
        dest="from_results",
        default=None,
        help="기존 feed NDJSON 으로 분석 skip, 정확도만 재계산(offline)",
    )
    parser.add_argument(
        "--ground-truth",
        dest="ground_truth",
        action="store_true",
        help="시드 label 과 비교해 정확도 리포트 로깅",
    )
    parser.add_argument(
        "--plausibility",
        action="store_true",
        help="라벨 없는 신규곡 — 음역 합리성(가창 범위) 리포트 로깅 + 비합리 곡 차단",
    )
    parser.add_argument(
        "--resume",
        default=None,
        help="기존 feed NDJSON — 이미 성공한 곡은 skip 하고 결과를 누적(반복 실행)",
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=None,
        help="이번 invocation 에서 분석할 최대 곡 수(안전 단위 chunk, 미지정 시 남은 전체)",
    )
    parser.add_argument(
        "--sleep-seconds",
        dest="sleep_seconds",
        type=float,
        default=0.0,
        help="곡 사이 대기 초(YouTube rate limit 완화)",
    )
    parser.add_argument("--tmpdir", default=None, help="임시 작업 경로(default /data/tmp)")
    parser.add_argument(
        "--clip-seconds", dest="clip_seconds", type=int, default=45, help="clip 길이(초)"
    )
    parser.add_argument(
        "--vocal-separation",
        dest="vocal_separation",
        action="store_true",
        help="Spleeter 2stems vocal 분리 후 분석(opt-in)",
    )
    parser.add_argument("--verbose", "-v", action="store_true", help="DEBUG 로깅")
    return parser.parse_args(argv)


def main(argv: Optional[list[str]] = None) -> int:
    args = _parse_args(argv if argv is not None else sys.argv[1:])
    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
        stream=sys.stderr,
    )
    seed = load_seed(args.seed)

    if args.limit is not None and args.limit <= 0:
        raise ValueError("--limit 은 양의 정수여야 합니다")
    if args.sleep_seconds < 0:
        raise ValueError("--sleep-seconds 는 음수일 수 없습니다")

    if args.from_results:
        records = load_results(args.from_results)
        LOG.info("offline mode: loaded %d records from %s", len(records), args.from_results)
    else:
        resolve_tmpdir(args.tmpdir)
        existing = resume_existing(args.resume)
        done_ids = done_ids_from_feed(existing)
        pending = select_pending(seed, done_ids, args.limit)
        LOG.info(
            "chunk: 시드 %d곡 / 완료 skip %d곡 / 이번 분석 %d곡 (남은 후보 %d, limit=%s)",
            len(seed),
            len(done_ids),
            len(pending),
            len(seed) - len(done_ids),
            args.limit,
        )
        fresh = run_batch(
            pending, args.clip_seconds, args.vocal_separation, args.sleep_seconds
        )
        records = merge_feed(existing, fresh)
        write_feed(records, args.out)

    if args.ground_truth:
        metrics = compute_accuracy(records, seed)
        LOG.info("\n%s", format_accuracy_report(metrics))
        if not metrics["withinTolerance"]:
            LOG.warning("정확도 회귀 가드 미통과 — 라벨 대비 오차 확인 필요")

    if args.plausibility:
        summary = summarize_plausibility(records)
        LOG.info("\n%s", format_plausibility_report(summary))
        if summary["implausibleCount"] > 0:
            LOG.warning(
                "%d 곡 음역 비합리 — 추천 backfill 에서 제외 필요",
                summary["implausibleCount"],
            )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
