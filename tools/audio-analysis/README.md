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

## 사용

```bash
# 1) YouTube URL 직접 지정
python analyze.py --youtube-url "https://www.youtube.com/watch?v=xxxx"

# 2) 제목 + 아티스트로 ytsearch
python analyze.py --song-title "Yesterday" --artist "The Beatles"

# 3) clip 길이 조정 (기본 45초)
python analyze.py --youtube-url "..." --clip-seconds 30 --verbose
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
  "toolingVersion": "analyze-py-0.1.0"
}
```

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

## 알려진 한계

- `spleeter` (보컬 stem 분리)는 본 PR에 포함하지 않았다. Python 3.10 의존성 + TensorFlow 무게 문제로 PR B에서 도입 여부를 결정한다 (대안: demucs).
- `key` 추정은 chroma 평균 기반 단순 휴리스틱. 정확도 향상은 별도 spec.
- 단일 코어 기준 곡당 30초 목표는 PR B 통합 후 측정.
