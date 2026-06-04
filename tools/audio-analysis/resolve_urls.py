#!/usr/bin/env python3
"""YouTube URL 자동매칭 — 음역 미보유 곡(title, artist)에 분석 대상 URL 을 자동해결.

MusicBrainz 대량 임포트(#1705/#1707)로 곡이 100→371 로 늘었으나 신규곡은 YouTube
URL 이 없어 자체분석(analyze.py/batch_analyze.py)에 곧장 넣을 수 없다. 본 단계는
(title, artist) 로 yt-dlp `ytsearch1` 검색을 돌려 후보 영상을 찾고, 질의어와 후보
제목/업로더의 토큰 일치 + 영상 길이 타당성으로 **매칭 신뢰도**를 산출한다. 신뢰도가
임계 미만이면 잘못된 영상(라이브/커버/리액션/무관 영상)을 음역 분석에 넣지 않도록
**skip + 로그**하고, 신뢰도가 충분한 곡만 resolved seed 로 내보낸다. directive #1739.

파이프라인 위치:
  (미보유 곡 title+artist)  ──resolve_urls.py──▶  (resolved seed: id+youtubeUrl)
                                                       │
                                          batch_analyze.py(#1735 chunk/resume)
                                                       ▼
                                          (lowMidi/highMidi backfill feed)

운영 주의(저작권 — analyze.py 와 동일 원칙):
  - 본 단계는 **검색 메타데이터만** 조회한다(`download=False`). audio 를 내려받지 않으며
    pitch 추출은 후속 analyze.py 가 30~60초 clip 으로만 수행한 뒤 즉시 삭제한다.
  - 로그에는 URL 원문을 마스킹한다(analyze.mask_url).
  - 곡 사이 `--sleep-seconds` 로 YouTube rate limit 을 완화한다.

사용 예:
  cd tools/audio-analysis

  # 1) 미보유 곡 seed → URL 자동매칭 → 감사용 feed + 분석용 resolved seed 생성
  python resolve_urls.py --seed tests/new-songs-verification.json \
    --out /data/tmp/resolved-feed.ndjson \
    --seed-out /data/tmp/resolved-seed.json --sleep-seconds 2

  # 2) 안전 단위 chunk + 반복 resume — 수백 곡을 invocation 당 20곡씩 누적 처리
  python resolve_urls.py --seed /data/tmp/missing-range.json \
    --out /data/tmp/resolved-feed.ndjson --resume /data/tmp/resolved-feed.ndjson \
    --seed-out /data/tmp/resolved-seed.json --limit 20 --sleep-seconds 2

  # 3) 매칭 신뢰도 임계 조정(엄격하게)
  python resolve_urls.py --seed tests/new-songs-verification.json \
    --seed-out /data/tmp/resolved-seed.json --min-confidence 0.65
"""
from __future__ import annotations

import argparse
import json
import logging
import os
import re
import sys
import time
from pathlib import Path
from typing import Optional

import analyze  # mask_url 재사용 — 모듈 최상위는 경량(yt_dlp/librosa 는 lazy import)

LOG = logging.getLogger("resolve-urls")

DEFAULT_TMPDIR = "/data/tmp"
RESOLVER_VERSION = "resolve-urls-0.1.0"

# 매칭 신뢰도 임계 — 이 미만이면 잘못된 영상 의심 → 분석에서 제외(skip).
DEFAULT_MIN_CONFIDENCE = 0.5

# 신뢰도 가중 — 텍스트(제목/아티스트 토큰 일치)가 주, 영상 길이 타당성이 보조.
TEXT_WEIGHT = 0.8
DURATION_WEIGHT = 0.2
TITLE_SUBWEIGHT = 0.65   # 아티스트가 있을 때 텍스트 점수 내 제목 비중
ARTIST_SUBWEIGHT = 0.35

# 가창곡 영상 길이 타당 범위(초). 안쪽은 만점, 바깥은 0 까지 선형 감점.
DURATION_FULL_LOW = 60.0
DURATION_FULL_HIGH = 420.0
DURATION_ZERO_LOW = 20.0
DURATION_ZERO_HIGH = 900.0

STATUS_RESOLVED = "resolved"
STATUS_SKIPPED = "skipped_low_confidence"
STATUS_NO_RESULT = "no_search_result"
STATUS_FAILED = "failed"


# ----------------------------------------------------------------------
# Pure helpers (단위 테스트 대상 — yt-dlp 불요)
# ----------------------------------------------------------------------
def normalize_text(text: Optional[str]) -> str:
    """매칭 비교용 정규화 — 소문자화 + 비단어문자를 공백으로(한글/영문/숫자 보존)."""
    if not text:
        return ""
    lowered = text.lower()
    # \w 는 유니코드 letter(한글 포함)·숫자·_ 를 포함한다(re.UNICODE 기본).
    spaced = re.sub(r"[^\w]+", " ", lowered, flags=re.UNICODE)
    return re.sub(r"\s+", " ", spaced).strip()


def tokenize(text: Optional[str]) -> set:
    """정규화 후 공백 토큰 집합. 빈 입력은 빈 집합."""
    normalized = normalize_text(text)
    return set(normalized.split()) if normalized else set()


def token_recall(query_tokens: set, candidate_tokens: set) -> float:
    """질의 토큰 중 후보에 등장한 비율(0.0~1.0). 질의가 비면 0.0."""
    if not query_tokens:
        return 0.0
    hit = len(query_tokens & candidate_tokens)
    return round(hit / len(query_tokens), 3)


def duration_plausibility(duration_sec: Optional[float]) -> float:
    """영상 길이가 가창곡으로 타당한지 0.0~1.0. None 은 중립(0.5).

    [60,420]초 만점, [20,900] 바깥은 0, 그 사이는 선형 감점. 너무 짧은(클립/쇼츠)·
    너무 긴(라이브 풀셋/플레이리스트) 영상은 오매칭 가능성이 높아 점수를 깎는다.
    """
    if duration_sec is None:
        return 0.5
    try:
        value = float(duration_sec)
    except (TypeError, ValueError):
        return 0.5
    if value <= 0:
        return 0.0
    if DURATION_FULL_LOW <= value <= DURATION_FULL_HIGH:
        return 1.0
    if value < DURATION_FULL_LOW:
        span = DURATION_FULL_LOW - DURATION_ZERO_LOW
        return round(max(0.0, (value - DURATION_ZERO_LOW) / span), 3)
    span = DURATION_ZERO_HIGH - DURATION_FULL_HIGH
    return round(max(0.0, (DURATION_ZERO_HIGH - value) / span), 3)


def match_confidence(
    title: Optional[str],
    artist: Optional[str],
    candidate_title: Optional[str],
    candidate_uploader: Optional[str],
    duration_sec: Optional[float],
) -> float:
    """질의 곡과 검색 후보의 매칭 신뢰도(0.0~1.0).

    후보 토큰 = 후보 제목 ∪ 업로더(채널). 질의 제목·아티스트 토큰이 후보에 얼마나
    재현되는지(recall)로 텍스트 점수를, 영상 길이 타당성으로 보조 점수를 매긴다.
    아티스트가 없으면 제목 recall 만 텍스트 점수로 쓴다.
    """
    candidate_tokens = tokenize(candidate_title) | tokenize(candidate_uploader)
    title_recall = token_recall(tokenize(title), candidate_tokens)
    if artist:
        artist_recall = token_recall(tokenize(artist), candidate_tokens)
        text_score = TITLE_SUBWEIGHT * title_recall + ARTIST_SUBWEIGHT * artist_recall
    else:
        text_score = title_recall
    duration_score = duration_plausibility(duration_sec)
    return round(TEXT_WEIGHT * text_score + DURATION_WEIGHT * duration_score, 3)


def build_search_query(title: Optional[str], artist: Optional[str]) -> str:
    """ytsearch 쿼리 문자열. 아티스트가 있으면 '아티스트 제목'."""
    title_part = (title or "").strip()
    artist_part = (artist or "").strip()
    return f"{artist_part} {title_part}".strip() if artist_part else title_part


def classify_resolution(confidence: float, min_confidence: float) -> str:
    """신뢰도로 resolved / skipped 판정."""
    return STATUS_RESOLVED if confidence >= min_confidence else STATUS_SKIPPED


def to_resolved_record(
    song: dict,
    candidate: Optional[dict],
    min_confidence: float,
) -> dict:
    """곡 + 검색 후보로 결과 레코드를 만든다(순수 함수).

    candidate 가 None(검색 결과 없음)이면 status=no_search_result.
    그 외에는 match_confidence 로 resolved/skipped 를 가른다. resolved 만 youtubeUrl 을
    싣고, skipped 는 사람이 점검할 수 있도록 후보 메타·신뢰도를 남긴다.
    """
    song_id = song.get("id")
    base = {
        "id": song_id,
        "title": song.get("title"),
        "artist": song.get("artist"),
        "resolverVersion": RESOLVER_VERSION,
    }
    if candidate is None:
        return {**base, "status": STATUS_NO_RESULT, "matchConfidence": 0.0}
    confidence = match_confidence(
        song.get("title"),
        song.get("artist"),
        candidate.get("title"),
        candidate.get("uploader"),
        candidate.get("duration"),
    )
    status = classify_resolution(confidence, min_confidence)
    record = {
        **base,
        "status": status,
        "matchConfidence": confidence,
        "candidateTitle": candidate.get("title"),
        "candidateUploader": candidate.get("uploader"),
        "candidateDuration": candidate.get("duration"),
    }
    if status == STATUS_RESOLVED:
        record["youtubeUrl"] = candidate.get("url")
    return record


def resume_existing(resume_path: Optional[str]) -> list[dict]:
    """resume feed 로드. 미지정 또는 첫 실행(파일 없음)이면 빈 리스트.

    --resume 와 --out 을 같은 경로로 두므로 첫 invocation 에는 파일이 없다 —
    "진척 없음"으로 취급해 정상 진행한다(batch_analyze 와 동일 규약).
    """
    if not resume_path or not Path(resume_path).exists():
        return []
    return load_records(resume_path)


def done_ids_from_feed(records: list[dict]) -> set:
    """재해결을 건너뛸 곡 id — 이미 판정난 곡(resolved/skipped/no_result)만.

    transient 실패(status=failed)는 포함하지 않아 다음 invocation 에서 재시도된다.
    """
    terminal = {STATUS_RESOLVED, STATUS_SKIPPED, STATUS_NO_RESULT}
    return {r.get("id") for r in records if r.get("status") in terminal}


def select_pending(seed: list[dict], done_ids: set, limit: Optional[int]) -> list[dict]:
    """이번 invocation 에서 해결할 곡 선택 — 완료분 제외 + limit chunk(순수 함수)."""
    pending = [song for song in seed if song.get("id") not in done_ids]
    if limit is not None:
        pending = pending[:limit]
    return pending


def merge_feed(existing: list[dict], fresh: list[dict]) -> list[dict]:
    """기존 feed 와 이번 결과를 id 기준 병합(새 결과 우선, 순서 보존)."""
    by_id: dict = {record.get("id"): record for record in existing}
    for record in fresh:
        by_id[record.get("id")] = record
    return list(by_id.values())


def resolved_seed_songs(records: list[dict]) -> list[dict]:
    """resolved 레코드만 batch_analyze 가 곧장 먹는 seed 곡으로 변환한다.

    batch_analyze.load_seed 는 id + (youtubeUrl 또는 title) 을 요구한다 — resolved 곡은
    youtubeUrl 을 가지므로 그대로 분석 대상이 된다. skipped/no_result 는 제외한다.
    """
    songs: list[dict] = []
    for record in records:
        if record.get("status") != STATUS_RESOLVED:
            continue
        songs.append(
            {
                "id": record.get("id"),
                "title": record.get("title"),
                "artist": record.get("artist"),
                "youtubeUrl": record.get("youtubeUrl"),
                "matchConfidence": record.get("matchConfidence"),
            }
        )
    return songs


def load_records(path: str) -> list[dict]:
    """NDJSON feed 를 레코드 리스트로 로드(resume/offline 집계용)."""
    records: list[dict] = []
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if line:
                records.append(json.loads(line))
    return records


def load_seed(path: str) -> list[dict]:
    """미보유 곡 seed 로드. {"songs": [...]} 또는 최상위 배열.

    각 곡은 id + title 을 가져야 한다(아티스트는 선택). 이미 youtubeUrl 이 있는 곡은
    해결 대상이 아니므로 그대로 통과시키되, title 없이 url 만 있는 곡도 허용한다.
    """
    with open(path, encoding="utf-8") as handle:
        data = json.load(handle)
    songs = data.get("songs") if isinstance(data, dict) else data
    if not isinstance(songs, list):
        raise ValueError("seed 형식 오류: songs 배열이 필요합니다")
    for index, song in enumerate(songs):
        if not isinstance(song, dict) or "id" not in song:
            raise ValueError(f"seed[{index}] 에 id 필드가 없습니다")
        if not song.get("title") and not song.get("youtubeUrl"):
            raise ValueError(f"seed[{song['id']}] 에 title 또는 youtubeUrl 이 필요합니다")
    return songs


def resolve_tmpdir(explicit: Optional[str]) -> Path:
    """임시 작업 경로 결정 + TMPDIR 고정(batch_analyze 와 동일 규약, /data 안전)."""
    chosen = explicit or os.environ.get("AUDIO_ANALYSIS_TMPDIR") or DEFAULT_TMPDIR
    path = Path(chosen)
    path.mkdir(parents=True, exist_ok=True)
    os.environ["TMPDIR"] = str(path)
    return path


def summarize(records: list[dict]) -> dict:
    """feed 전체의 매칭 결과 집계(리포트용)."""
    counts = {STATUS_RESOLVED: 0, STATUS_SKIPPED: 0, STATUS_NO_RESULT: 0, STATUS_FAILED: 0}
    for record in records:
        status = record.get("status")
        if status in counts:
            counts[status] += 1
    resolved_conf = [
        r.get("matchConfidence")
        for r in records
        if r.get("status") == STATUS_RESOLVED and r.get("matchConfidence") is not None
    ]
    mean_conf = (
        round(sum(resolved_conf) / len(resolved_conf), 3) if resolved_conf else None
    )
    return {
        "total": len(records),
        "resolved": counts[STATUS_RESOLVED],
        "skipped": counts[STATUS_SKIPPED],
        "noResult": counts[STATUS_NO_RESULT],
        "failed": counts[STATUS_FAILED],
        "resolvedConfidenceMean": mean_conf,
    }


def format_report(summary: dict, min_confidence: float) -> str:
    """매칭 집계를 사람이 읽는 로그 블록으로 포맷."""
    return "\n".join(
        [
            "=== YouTube URL 자동매칭 리포트 ===",
            f"곡 수: 전체 {summary['total']}",
            f"resolved(분석 대상): {summary['resolved']} "
            f"(평균 신뢰도 {summary['resolvedConfidenceMean']}, 임계 ≥{min_confidence})",
            f"skipped(신뢰도 미달): {summary['skipped']}",
            f"검색 결과 없음: {summary['noResult']} / 검색 실패: {summary['failed']}",
        ]
    )


# ----------------------------------------------------------------------
# IO — YouTube 검색 (외부 yt-dlp 호출, download 없음)
# ----------------------------------------------------------------------
def search_youtube(query: str) -> Optional[dict]:
    """ytsearch1 로 후보 영상 1건의 메타데이터를 조회한다(audio 다운로드 없음).

    반환: {"url", "title", "uploader", "duration"} 또는 결과 없으면 None.
    """
    base_opts = {
        "quiet": True,
        "noprogress": True,
        "skip_download": True,
        "extract_flat": False,
    }
    # player_client 폴백 체인 + 쿠키(있으면)로 검색을 견고화한다(#1802, analyze 재사용).
    info = analyze.run_with_client_chain(
        base_opts, lambda ydl: ydl.extract_info(f"ytsearch1:{query}", download=False)
    )
    entries = (info or {}).get("entries") or []
    if not entries:
        return None
    entry = entries[0]
    video_id = entry.get("id")
    url = entry.get("webpage_url") or (
        f"https://www.youtube.com/watch?v={video_id}" if video_id else None
    )
    return {
        "url": url,
        "title": entry.get("title"),
        "uploader": entry.get("uploader") or entry.get("channel"),
        "duration": entry.get("duration"),
    }


def run_resolve(
    seed: list[dict],
    min_confidence: float,
    sleep_seconds: float = 0.0,
) -> list[dict]:
    """미보유 곡을 순차로 URL 자동매칭한다. 곡 단위 실패는 격리한다.

    sleep_seconds > 0 이면 검색 사이에 대기해 YouTube rate limit 을 완화한다.
    """
    records: list[dict] = []
    for index, song in enumerate(seed):
        if index > 0 and sleep_seconds > 0:
            time.sleep(sleep_seconds)
        song_id = song.get("id")
        query = build_search_query(song.get("title"), song.get("artist"))
        started = time.time()
        try:
            candidate = search_youtube(query)
            record = to_resolved_record(song, candidate, min_confidence)
            LOG.info(
                "resolve id=%s status=%s conf=%s url=%s elapsedMs=%d",
                song_id,
                record["status"],
                record.get("matchConfidence"),
                analyze.mask_url(record.get("youtubeUrl")),
                int((time.time() - started) * 1000),
            )
            if record["status"] == STATUS_SKIPPED:
                LOG.warning(
                    "skip 저신뢰 매칭 id=%s conf=%s candidate=%r — 분석 제외",
                    song_id,
                    record.get("matchConfidence"),
                    record.get("candidateTitle"),
                )
        except Exception as exc:  # noqa: BLE001 — 곡 단위 실패 격리
            record = {
                "id": song_id,
                "title": song.get("title"),
                "artist": song.get("artist"),
                "status": STATUS_FAILED,
                "error": str(exc),
                "resolverVersion": RESOLVER_VERSION,
            }
            LOG.error("resolve failed id=%s error=%s", song_id, exc)
        records.append(record)
    return records


def write_feed(records: list[dict], out_path: Optional[str]) -> None:
    """결과 레코드를 NDJSON 으로 기록(--out 없으면 stdout)."""
    if out_path:
        with open(out_path, "w", encoding="utf-8") as handle:
            for record in records:
                handle.write(json.dumps(record, ensure_ascii=False) + os.linesep)
        LOG.info("feed written path=%s records=%d", out_path, len(records))
    else:
        for record in records:
            sys.stdout.write(json.dumps(record, ensure_ascii=False) + os.linesep)


def write_seed_out(records: list[dict], seed_out_path: str) -> int:
    """resolved 곡만 batch_analyze 용 seed JSON 으로 기록. 곡 수 반환."""
    songs = resolved_seed_songs(records)
    payload = {
        "description": (
            "resolve_urls.py(#1739) 자동매칭으로 youtubeUrl 을 채운 분석 대상 seed. "
            "batch_analyze.py --seed 로 곧장 사용(음역 backfill)."
        ),
        "resolverVersion": RESOLVER_VERSION,
        "songs": songs,
    }
    with open(seed_out_path, "w", encoding="utf-8") as handle:
        json.dump(payload, handle, ensure_ascii=False, indent=2)
        handle.write(os.linesep)
    LOG.info("resolved seed written path=%s songs=%d", seed_out_path, len(songs))
    return len(songs)


# ----------------------------------------------------------------------
# CLI
# ----------------------------------------------------------------------
def _parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="음역 미보유 곡(title, artist) → YouTube URL 자동매칭(#1739)"
    )
    parser.add_argument("--seed", required=True, help="미보유 곡 seed JSON(id+title[+artist])")
    parser.add_argument("--out", default=None, help="매칭 feed NDJSON 출력(미지정 시 stdout)")
    parser.add_argument(
        "--seed-out",
        dest="seed_out",
        default=None,
        help="resolved 곡만 batch_analyze 용 seed JSON 으로 저장",
    )
    parser.add_argument(
        "--resume",
        default=None,
        help="기존 feed NDJSON — 이미 판정난 곡은 skip 하고 결과 누적(반복 실행)",
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=None,
        help="이번 invocation 에서 해결할 최대 곡 수(안전 단위 chunk)",
    )
    parser.add_argument(
        "--min-confidence",
        dest="min_confidence",
        type=float,
        default=DEFAULT_MIN_CONFIDENCE,
        help=f"매칭 신뢰도 임계(default {DEFAULT_MIN_CONFIDENCE}). 미만은 skip.",
    )
    parser.add_argument(
        "--sleep-seconds",
        dest="sleep_seconds",
        type=float,
        default=0.0,
        help="검색 사이 대기 초(YouTube rate limit 완화)",
    )
    parser.add_argument("--tmpdir", default=None, help="임시 작업 경로(default /data/tmp)")
    parser.add_argument("--verbose", "-v", action="store_true", help="DEBUG 로깅")
    return parser.parse_args(argv)


def main(argv: Optional[list[str]] = None) -> int:
    args = _parse_args(argv if argv is not None else sys.argv[1:])
    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
        stream=sys.stderr,
    )
    if args.limit is not None and args.limit <= 0:
        raise ValueError("--limit 은 양의 정수여야 합니다")
    if args.sleep_seconds < 0:
        raise ValueError("--sleep-seconds 는 음수일 수 없습니다")
    if not 0.0 <= args.min_confidence <= 1.0:
        raise ValueError("--min-confidence 는 0.0~1.0 범위여야 합니다")

    seed = load_seed(args.seed)
    resolve_tmpdir(args.tmpdir)

    existing = resume_existing(args.resume)
    done_ids = done_ids_from_feed(existing)
    pending = select_pending(seed, done_ids, args.limit)
    LOG.info(
        "chunk: seed %d곡 / 완료 skip %d곡 / 이번 해결 %d곡 (남은 후보 %d, limit=%s)",
        len(seed),
        len(done_ids),
        len(pending),
        len(seed) - len(done_ids),
        args.limit,
    )

    fresh = run_resolve(pending, args.min_confidence, args.sleep_seconds)
    records = merge_feed(existing, fresh)
    write_feed(records, args.out)

    if args.seed_out:
        write_seed_out(records, args.seed_out)

    summary = summarize(records)
    LOG.info("\n%s", format_report(summary, args.min_confidence))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
