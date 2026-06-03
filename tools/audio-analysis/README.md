# tools/audio-analysis

YouTube audio에서 곡의 음역대(MIDI low/high) · key · tempo를 추출하는 Python CLI.

- Spec: [`docs/features/audio-tooling-bootstrap.md`](../../docs/features/audio-tooling-bootstrap.md)
- ADR: [`0006-audio-source-youtube`](../../docs/decisions/0006-audio-source-youtube.md), [`0010-self-analysis-pipeline-stack`](../../docs/decisions/0010-self-analysis-pipeline-stack.md)

본 PR(A)은 **부트스트랩** 범위이다. Spring 측 `AudioAnalysisRunner` 연동은 PR C, 단일 곡 API는 PR D에서 추가한다.

## 설치

### 로컬 (macOS / Linux)

```bash
# ffmpeg 사전 설치 필요 (yt-dlp 후처리에 사용)
brew install ffmpeg              # macOS
# sudo apt-get install -y ffmpeg # Ubuntu

cd tools/audio-analysis
python3.11 -m venv .venv
source .venv/bin/activate
pip install --upgrade pip
pip install -r requirements.txt
```

### Docker (격리 실행)

```bash
docker build -t mobruji/audio-analysis:dev tools/audio-analysis
docker run --rm mobruji/audio-analysis:dev \
  python analyze.py --youtube-url "https://www.youtube.com/watch?v=xxxx"
```

### docker compose (권장, spec PR D)

호스트에 Python/ffmpeg/librosa 설치 없이 격리 실행한다.

```bash
# 빌드 + 단발 실행 (--rm 으로 종료 시 컨테이너 정리)
docker compose -f docker-compose.audio.yml run --rm audio-analysis \
  --song-title "Yesterday" --artist "The Beatles"

# YouTube URL 직접 지정
docker compose -f docker-compose.audio.yml run --rm audio-analysis \
  --youtube-url "https://www.youtube.com/watch?v=xxxx"
```

Spring 측 `AudioAnalysisRunner` 도 본 compose 파일을 호출 가능하다 — `audio.analysis.use-docker=true` (또는 `AUDIO_ANALYSIS_USE_DOCKER=true`) 환경변수로 활성화한다. 호스트 Python 의존성 미설치 환경 (예: 운영 서버 컨테이너 내부) 에서 유용하다.

## 사용

```bash
# 1) YouTube URL 직접 지정
python analyze.py --youtube-url "https://www.youtube.com/watch?v=xxxx"

# 2) 제목 + 아티스트로 ytsearch
python analyze.py --song-title "Yesterday" --artist "The Beatles"

# 3) clip 길이 조정 (기본 45초)
python analyze.py --youtube-url "..." --clip-seconds 30 --verbose

# 4) Spleeter vocal stem 분리 후 분석 (opt-in)
python analyze.py --youtube-url "..." --vocal-separation
```

stdout에 JSON 한 줄을 출력한다.

```json
{
  "songMeta": {"title": null, "artist": null, "youtube_url": "...", "duration_sec": 45.0},
  "lowMidi": 55,
  "highMidi": 71,
  "key": "C",
  "tempo": 120.5,
  "durationSec": 45.0,
  "confidence": 0.78,
  "analysisMethod": "vocal-skip",
  "toolingVersion": "analyze-py-0.2.0"
}
```

`analysisMethod` 는 pitch 추출 입력을 나타낸다 — `vocal-skip`(곡 전체 audio) 또는
`spleeter-2stems`(분리된 vocal stem). Spring 측 `AudioAnalysisRunner` 는 JSON 을
필드명 기준으로 파싱하므로 본 필드 추가는 하위 호환이다.

## Spleeter vocal stem 분리 (opt-in)

`--vocal-separation` 플래그는 yt-dlp 추출 audio 를 Spleeter 2stems 로 분리한 뒤
vocal stem 에만 librosa pyin 을 적용한다. 반주·드럼 harmonics 가 pitch contour 를
오염시켜 lowMidi/highMidi 가 양쪽으로 늘어나는 회귀를 줄이기 위함이다
(spec [`song-self-analysis-pipeline.md`](../../docs/features/song-self-analysis-pipeline.md) §10-2).

- **default 는 vocal-skip** — spec §13 의 `audio.analysis.vocal-separation.enabled`
  default false 와 정합. Spleeter 정식 도입 여부는 ADR-0015 트리거 충족 시 결정한다.
- Spleeter 는 TensorFlow 의존이 무거워 메인 `requirements.txt` 와 분리한다. 별도 설치:

  ```bash
  pip install -r requirements.txt -r requirements-vocal.txt
  ```

- pretrained model 캐시는 `AUDIO_ANALYSIS_MODEL_DIR` 환경변수로 외부화한다.
  **NCP 운영에서는 `/data` 등 영속 볼륨** 을 지정해 매 실행 재다운로드를 방지한다:

  ```bash
  export AUDIO_ANALYSIS_MODEL_DIR=/data/spleeter-models
  python analyze.py --youtube-url "..." --vocal-separation
  ```

- 분리된 vocal stem 도 임시 캐시(`tmpdir/stems`)에만 두고 분석 직후 삭제한다
  (저작권 — §3 비기능, 원본/stem 모두 영속 금지).

실패 시 exit code 1 + stdout JSON `{"error": "...", "toolingVersion": "..."}`.

## 테스트

```bash
cd tools/audio-analysis
pytest -q
```

순수 helper (`frequency_to_midi`, `extract_range`, `confidence_score`, `mask_url`)에 대한 단위 테스트만 포함한다. 외부 IO(yt-dlp/librosa)는 PR B에서 sample wav 기반 통합 테스트로 추가한다.

## 운영 주의 (저작권 / YouTube ToS)

- audio 임시 파일은 **분석 종료 직후 무조건 삭제**한다 (`tempfile.mkdtemp` + `shutil.rmtree` 보장).
- 30~60초 clip만 추출하며 영구 저장 · 재배포 · 외부 공유 금지.
- YouTube ToS 및 각국 저작권법을 준수할 책임은 호출자에게 있다. 운영 환경에서 차단(403/age-gate) 발생 시 spec §3 fallback 정책을 따른다.
- 로그에는 URL 원문을 마스킹해서 남긴다 (`mask_url` helper).

## 시드 30곡 backfill batch (Spring 측)

Spec PR C — `SongAudioBackfillCommand` 가 DB 의 곡 전체를 순회하며 `analyzeByMetadata(title, artist)` 결과로 `lowMidi/highMidi/difficulty/metadataSource` 를 갱신한다.

```bash
# 0) MySQL 기동
docker compose up -d

# 1) venv 활성화 + Python tool 동작 확인
source tools/audio-analysis/.venv/bin/activate
python tools/audio-analysis/analyze.py --song-title "Yesterday" --artist "The Beatles"

# 2) 백엔드에 backfill 옵션 전달 (수동 trigger, 운영 안전)
cd backend
AUDIO_ANALYSIS_PYTHON_CMD="$(pwd)/../tools/audio-analysis/.venv/bin/python" \
  ./gradlew bootRun --args='--spring.profiles.active=local --mobruji.backfill-audio=true'
```

- 옵션 미지정 시 부팅에 영향 없음 (no-op).
- 시간 예상: **30곡 × ~30s ≈ 15분** (단일 코어 기준).
- 적용 임계 confidence 기본 **0.6** — 미달 곡은 수기 시드 값을 보존한다.
- 곡 단위 실패(timeout/403/parse error)는 로그 + 다음 곡으로 진행. 전체 batch 중단 X.
- 완료 시 `audio backfill done analyzed=N successful=M updated=K skipped_low_confidence=J failed=F` 1줄 요약 로그.
- audio 임시 파일은 Python 측에서 즉시 삭제 (ADR 0006).

## 정기 batch (Spring 측, spec PR D)

`AudioAnalysisScheduledBackfill` 가 **매주 일요일 새벽 4시 KST** (`cron = "0 0 4 * * SUN"`, zone `Asia/Seoul`) 에 자동 실행된다.

- 활성 조건: **`prod` 프로파일만** (`@Profile("prod")`). 로컬/CI 에서는 빈 자체가 등록되지 않는다.
- 대상 곡: `metadataSource != AUDIO_ANALYSIS` — audio 분석으로 갱신된 적 없는 곡 (신규 곡, 시드, 외부 출처). spec 의 "metadataConfidence &lt; 0.6" 의도와 정합.
- 위임: 일회성 backfill 과 동일한 `SongAudioBackfillCommand.runBackfill(songs, 0.6)` 호출.
- 곡 단위 실패는 격리되어 전체 batch 가 중단되지 않는다.
- 로그: `audio scheduled backfill: done analyzed=N successful=M updated=K skipped=J failed=F`.
- 결정성 영향 없음 — 추천 알고리즘 입력 데이터만 정확해지고 알고리즘 코드 변경 없음 (ADR 0010).

## 알려진 한계

- `spleeter` (보컬 stem 분리)는 `--vocal-separation` opt-in 으로 지원하나 **default 는 vocal-skip** 이다. TensorFlow 무게 + CI 빌드 시간 회귀(spec §10-2) 때문에 메인 의존성과 분리(`requirements-vocal.txt`)했고, 정식 도입(default 전환)은 ADR-0015 트리거 충족 시 결정한다 (대안: demucs).
- `key` 추정은 chroma 평균 기반 단순 휴리스틱. 정확도 향상은 별도 spec.
- 단일 코어 기준 곡당 30초 목표는 PR B 통합 후 측정.
