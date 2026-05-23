---
feature: 자체 곡 분석 파이프라인 (song-self-analysis-pipeline)
slug: song-self-analysis-pipeline
status: draft
owner: "@goohong"
scope: song
related_issues: [67, 98, 180, 207, 226]
related_prs: [99]
last_reviewed: 2026-05-22
---

# 자체 곡 분석 파이프라인 (song-self-analysis-pipeline)

## 1) 개요 (What / Why)
- v0.1에서는 외부 메타 카탈로그(Spotify audio-features + MusicBrainz)에 의존해 `Song`을 채울 계획이었으나, 실제 운영 시 (1) 한국 곡 커버리지 불균일, (2) Spotify 약관상 audio-features 캐싱 제약, (3) **곡 vocal range 자체는 어느 외부 API도 제공하지 않음** 이라는 본질적 한계가 드러났다.
- 본 spec은 외부 카탈로그 의존을 폐기하고, 곡 audio를 자체 확보·분석해 `Song`의 vocal range/key/tempo를 직접 추출하는 파이프라인을 정의한다.
- 액터: 시스템(배치 파이프라인) + 운영자(큐레이션·수기 보정).
- 본 spec은 `docs/features/song-metadata-source.md`의 일부 결정(특히 Q1, Q3)을 자체 분석 방식으로 갱신·대체한다.

## 2) 사용자 시나리오 (시스템 / 운영)
직접 사용자가 보는 화면은 없다. 운영·배치 시나리오:

1. **시드 곡 큐레이션** — 운영자가 분석 대상 곡 ID 리스트(예: 한국 노래방 인기곡 100곡)를 큐레이션해 입력한다.
2. **audio 매핑** — 각 곡 ID를 YouTube URL 또는 검색어와 매핑한다. (수기 또는 검색 API 자동)
3. **audio 추출** — yt-dlp로 영상에서 audio(mp3/wav) 추출. **임시 캐시**에만 저장.
4. **vocal 분리** — Spleeter(Deezer) 5-stem 또는 2-stem으로 vocal stem을 분리.
5. **pitch detection** — Librosa(pyin 등)로 vocal stem의 pitch contour 추출 → low/high MIDI note 추정.
6. **부가 분석** — Librosa chroma로 key 추정, beat tracker로 tempo 추정. (필요 시 valence/energy 같은 음향 특성도 추후 확장).
7. **`Song` 엔티티 update** — 결과를 DB에 반영. 운영자는 manual override로 자동 추정을 보정할 수 있다.
8. **정기 batch** — yt-dlp 버전 변경/시드 추가 시 재실행. 멱등(idempotent).

## 3) 요구사항
### 기능 요구사항
- [ ] yt-dlp(또는 동등 도구) 호출로 YouTube URL에서 audio 추출 (mp3 또는 wav).
- [ ] Spleeter로 vocal stem 분리.
- [ ] Librosa로 vocal stem pitch detection → `songRangeLow`/`songRangeHigh`(MIDI) 추정.
- [ ] Librosa로 key(chroma) 및 tempo(beat tracker) 자동 추출.
- [ ] 추출 결과를 `Song` 엔티티에 update (manual override 가능, `metadataSource = AUDIO_ANALYSIS` 표기).
- [ ] 분석 실패 처리 (audio 없음/접근 차단/vocal stem 미검출/pitch contour 신뢰도 미달 등) — `metadataConfidence` 필드로 표시, 실패 곡은 운영자 검토 큐로.
- [ ] 멱등 batch — 동일 곡 재분석 시 결과 갱신 (created 아님). 시드 추가/yt-dlp 버전 변경 시 재실행.

### 비기능 요구사항
- 외부 호출(YouTube) 약관 준수 — ADR 0006 참조. PoC 한정.
- **audio 파일은 임시 캐시에만 보관**하고 분석 완료 즉시 삭제. 저작권 회피 + 로그/백업/디스크 어디에도 원본 audio가 영속되지 않도록.
- 분석 소요 시간 곡당 30초~수 분 허용(Spleeter + Librosa). 100곡 batch 기준 수 시간 내 완료.
- 외부 호출 rate limit 준수 (YouTube 동시 요청 자제, retry/backoff).
- 관측성: 분석 단계별 성공/실패/소요 시간 로깅. 곡 audio 원문은 로그에 남기지 않음(보안 정책).

## 4) 범위 / 비범위 (중요)
### 포함
- 파이프라인 도구 선정 (yt-dlp + Spleeter + Librosa) — PoC 한도.
- `Song` 엔티티 필드 추가/갱신 매핑.
- 실패 처리 + manual override 정책.
- **v0.2 PoC 큐레이션 기준**(아래 §4-1).

### 4-1) v0.2 PoC 큐레이션 기준
- 분석 대상: **한국 노래방 인기곡 100곡**.
- 장르 분포(권장 default): 발라드 30 / 댄스 20 / 락 15 / 트로트 15 / 팝 10 / 힙합 10. 운영자 재량으로 조정 가능.
- 출처: TJ/금영 인기 차트 + Melon 차트 교차. 큐레이션 SQL/JSON은 별 PR.
- 본 기준은 자기 결정(reasonable default)이며 운영 착수 시 운영자가 §9에 결정 로그를 남기고 갱신할 수 있다.

### 제외 (Out of Scope)
- **실시간 분석** — 사용자 입력 시점에 곡을 분석하지 않는다. 사전 batch만.
- **추천 알고리즘 변경** — vocal range 매칭 로직은 `recommendation-algorithm-v1.md` 범위.
- **자체 vocal pitch 모델 학습** — Librosa pyin 등 기성 도구 사용. ML 학습은 별 spec.
- **유저 업로드 audio 분석** — UX/저작권 관점 별 의사결정 필요.
- **운영 단계 audio 출처 재평가** — ADR 0006 운영 진입 전 재평가 항목.

## 5) 설계

### 5-1) 도메인 모델
- 기존 `Song` 엔티티에 다음을 활용/추가:
  - `songRangeLow`, `songRangeHigh` (MIDI) — 분석으로 채움.
  - `keyOriginal` — chroma 기반 자동 추정.
  - `bpm` — beat tracker 기반.
  - `metadataSource` — enum 값 `AUDIO_ANALYSIS` 사용 (기존 `MANUAL`, `EXTERNAL_API`, `INFERRED`와 병행). 본 spec 초안의 `SELF_ANALYSIS` 표기는 §10-1 결정에 따라 `AUDIO_ANALYSIS` 로 통일 (실제 코드: `MetadataSource.AUDIO_ANALYSIS`, V3 마이그레이션).
  - `metadataConfidence` — 분석 신뢰도 표기 (수치 또는 enum). 실패 시 낮은 값.
- 새 용어 후보: `AudioAnalysisJob`, `VocalStem`, `PitchContour`. 도입 시 `docs/ai-harness/06-domain-model.md §4 유비쿼터스 랭귀지`에 등재.

### 5-2) API 엔드포인트
- 본 spec 단계에선 사용자 노출 API 없음. 운영자용 admin API는 별 spec.
- 분석 결과는 `GET /api/v1/songs/{id}` (기존 `song-metadata-source.md` §5-2) 로 노출.

### 5-3) 외부 연동 / 도구 스택
| 단계 | 도구 후보 | 비고 |
|------|-----------|------|
| audio 추출 | **yt-dlp** | ADR 0006. PoC 한정. |
| vocal 분리 | **Spleeter** (Deezer, MIT) | 2-stem(vocal/accompaniment) 또는 5-stem. PyTorch/TF 의존. |
| pitch detection | **Librosa** (`librosa.pyin`) | MIDI 변환은 후처리. |
| key/tempo | **Librosa** chroma + beat tracker | |
| 호스팅 | 로컬 batch (PoC) | Cloud 호스팅은 Q1. |
| 호출 방식 | Python 별 worker + Spring `ProcessBuilder` | ADR 0010. PoC 한정. microservice 분리는 v0.3 이상 재평가. |

### 5-4) 데이터 흐름 / 시퀀스
```
[시드 곡 ID 리스트] (운영자 큐레이션)
        ↓ (곡 → YouTube URL 매핑, 수기 또는 검색)
[YouTube]
        ↓ yt-dlp (audio 추출 → 임시 캐시)
[audio.mp3]
        ↓ Spleeter (vocal stem 분리)
[vocal.wav]
        ↓ Librosa (pyin pitch / chroma key / beat tempo)
[분석 결과: range, key, tempo, confidence]
        ↓ Song 엔티티 update (metadataSource=AUDIO_ANALYSIS)
[DB]
        ↓ (임시 캐시 audio 즉시 삭제)
```
실패 시 곡은 `metadataConfidence`=LOW로 표기하고 운영자 검토 큐로.

### 5-5) DB 마이그레이션
- `metadata_source` enum 값에 `AUDIO_ANALYSIS` 추가 (Flyway V3, PR #204 — 본 spec 초안의 `SELF_ANALYSIS` 표기는 §10-1 결정에 따라 `AUDIO_ANALYSIS` 로 통일).
- `metadata_confidence` 필드가 없으면 신설.
- 기존 `song` 테이블 구조 변경 영향은 작음.

### 5-6) 프론트엔드 화면
- 본 spec 범위 아님.

## 6) 작업 분할 (예상 PR 리스트)
- [ ] PR A (docs): 본 spec 초안 — 본 PR #99.
- [x] PR B (chore:song): Python worker 부트스트랩(yt-dlp + Spleeter + Librosa 환경) + 단일 곡 end-to-end smoke 스크립트.
- [x] PR C (feat:song, #204): `metadata_source = AUDIO_ANALYSIS` 추가 + 분석 결과 ingest 경로 (`Song.applyAudioAnalysisResult` + `AudioAnalysisRunner`).
- [ ] PR D (chore:song): 시드 100곡 큐레이션 JSON + YouTube URL 매핑.
- [ ] PR E (chore:infra): batch 실행 자동화 (cron 또는 수동 트리거).
- [ ] PR F (test): pitch detection 회귀 테스트 (알려진 정답 곡 ~5곡으로 MIDI low/high 검증).

## 7) 테스트 전략
- **단위**: pitch contour → MIDI low/high 변환 함수, chroma → key 매핑.
- **회귀**: 사전 라벨된 곡 ~5곡(정답 vocal range 알려진)을 매번 분석해 결과가 허용 오차 내인지 확인.
- **통합**: 단일 곡 end-to-end (yt-dlp → Spleeter → Librosa → DB update) smoke 테스트. CI에서는 외부 호출 mock 또는 사전 캐시 audio 사용.
- **외부 호출 mock**: WireMock는 부적합(바이너리 stream). 대신 사전 다운로드 audio fixture 사용.
- **E2E (RestAssured)**: 분석 결과가 반영된 `GET /api/v1/songs/{id}` 응답에 vocal range가 채워졌는지.

## 8) 오픈 질문
| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | 분석 호스팅 위치 | (a) 로컬 batch, (b) GitHub Actions runner, (c) cloud(예: GCP Cloud Run Jobs) | @goohong / v0.2 착수 전 |
| ~~Q2~~ | ~~JVM ↔ Python 통합 방식~~ | ADR 0010에서 (a) Python worker + Spring `ProcessBuilder`로 결정 (PoC 한정, v0.3 이상 microservice 재평가). | closed 2026-05-21 |
| Q3 | 전체 audio vs vocal stem 분석 비교 | A/B 비교 측정 후 결정 | @goohong / PR F |
| Q4 | 곡 → YouTube URL 매핑 자동화 여부 | (a) 100% 수기, (b) YouTube Data API 검색 + 수기 검수 | @goohong / PR D 직전 |

## 9) 결정 로그
> 연대기 순. "YYYY-MM-DD: 결정 / 이유 / 출처(PR 번호 등)"

- 2026-05-21: 초안 작성 (status=draft). 자체 분석 pivot 확정, audio 출처 = YouTube extract (ADR 0006). v0.2 PoC 큐레이션 기준(§4-1) 합리적 default 제시. 출처: #99
- 2026-05-21: 이슈 #67(외부 ingestion spec)을 본 spec으로 promote. 외부 메타 카탈로그 의존은 v0.x 한정 잔존, v0.2부터 자체 분석으로 전환. 출처: #99
- 2026-05-21: Q2(JVM↔Python 통합 방식) 종결. ADR 0010에서 **Python worker + Spring `ProcessBuilder`**로 결정. monorepo 단일 배포 유지, microservice 분리는 v0.3 이상 재평가. 출처: #163
- 2026-05-22: cross-ref — `song-curation-seed-100.md` Q4 (장르 분포 통일) 제기됨. 본 spec §4-1 (발라드30/댄스20/락15/트로트15/팝10/힙합10) vs curation spec §3 (발라드30/댄스20/락15/트로트15/팝20)이 다름. 결정은 curation spec Q4에서 단일화. 본 spec §4-1은 결정 후 후속 PR에서 동기화. 출처: 본 sequel PR (#71)
- 2026-05-23 (plan, spec-drift-cleanup-pt1): **§10-1 enum 표기 정정 finalize** — §3 기능 요구사항 / §5-1 도메인 모델 / §5-4 데이터 흐름 / §5-5 마이그레이션 / §6 PR C 의 `SELF_ANALYSIS` 표기를 모두 `AUDIO_ANALYSIS` 로 통일. §11 PR E (정정 spec PR) 본 PR 로 완료 처리. 결정 로그 2026-05-22 plan 35 의 (a) 항목 spec 반영 잔여를 닫음.
- 2026-05-22 (plan 35): **v0.2 양산 단계 spec 신설 (§10~§13)**. PoC (현행 librosa 단독 + AudioAnalysisRunner ProcessBuilder) 를 v0.2 양산으로 끌어올리기 위한 결정 묶음. (a) **enum 표기 정정** — spec의 `SELF_ANALYSIS` 가 실제 코드는 `AUDIO_ANALYSIS` (PR #204 promote, `MetadataSource.AUDIO_ANALYSIS`) — 본 spec 도 `AUDIO_ANALYSIS` 로 통일 (§10-1). (b) **vocal/instrumental 분리 도입 여부** — Spleeter (TF 의존) vs Demucs (PyTorch) vs vocal-skip 평가, ADR 후보 0015 트리거 조건 (§10-2). (c) **신뢰도 점수 공식 정형화** — 현행 `result.confidence()` 가 Python tool 내부 black-box → `f1 (pitch coverage) × f2 (vocal isolation) × f3 (duration adequacy)` 다항 공식으로 정형화, ADR 후보 0016 (§10-3). (d) **ground truth set + 회귀 가드** — 10 곡 라벨된 정답 set (low/high MIDI ± 2 semitone 허용 오차) 로 매 PR 회귀 가드 (§10-4). (e) **확장성** — 현행 cron (`AudioAnalysisScheduledBackfill`) → 큐 시스템 전환 트리거 조건 (분석 곡 ≥ 1k 또는 동시 분석 ≥ 5), 그 전까지 cron + selective query (#226 머지) 유지 (§10-5). (f) **운영 모니터링** — `mobruji.song.audio.analysis.*` 카운터/타이머 표 (observability-baseline §5-3 와 정합) + librosa CI 모니터링 spec 과의 메트릭 경계 (§10-6). (g) **결과 저장 위치** — 현행 Song 컬럼 직접 갱신 유지, 별 `SongAnalysis` 엔티티 신설은 audio-tooling-bootstrap §5-1 가 책임 (본 spec 범위 외, cross-ref 만) (§10-7). (h) **PR 분할** — A (Spleeter/Demucs 평가 spike + ADR-0015) → B (신뢰도 공식 정형화 + 메타 컬럼 + ADR-0016) → C (ground truth set + 회귀 가드 테스트) → D (운영 모니터링 — `mobruji.song.audio.analysis.*` 카운터 + Discord 알림 1 규칙) → E (조건부) 큐 시스템 전환 (§11). 출처: 본 PR

---

## 10) v0.2 양산 단계 결정 (PoC → 양산)

> 본 절은 PoC (§1~§7, 2026-05-21 작성) 가 단일 곡 end-to-end 가 돈 직후의 "다음 단계" 를 정한다. 1차 추천 가치 (`recommendation-algorithm-v1.md`) 의 곡 음역대 신호 정확도가 직접 의존하므로 본 spec 의 양산 단계 결정이 v0.3 P2 핵심 가치다.
>
> 이 절의 결정은 다음 spec 들과 의도적으로 정합:
> - `audio-tooling-bootstrap.md` (Python 분석 파이프라인 구현체) — 본 spec 의 §10-7 (저장 위치) 은 그 spec §5-1 의 `SongAnalysis` 엔티티 결정을 따른다.
> - `librosa-ci-build-monitoring.md` (CI 빌드 시간) — 본 spec 의 §10-6 운영 메트릭과 메트릭 키가 의도적으로 분리 (운영 = `mobruji.song.audio.analysis.*` / CI = `audio_analysis_ci.*`).
> - `observability-baseline.md` §5-3 — 본 spec 의 §10-6 카운터/타이머는 그 표가 단일 진실.
> - `song-curation-seed-100.md` §5-7 — 본 spec 의 §10-4 ground truth set 은 그 spec 의 수기 검증 곡 중 일부를 정답으로 채택.

### 10-1) MetadataSource enum 표기 정정 (필수 선행 정합)

기존 spec §5-1 / §3 기능 요구사항이 `metadataSource = SELF_ANALYSIS` 로 적혀 있으나 **실제 코드는 `MetadataSource.AUDIO_ANALYSIS`** (V3 마이그레이션, PR #204 promote, `Song.applyAudioAnalysisResult` 가 사용). 본 spec 도 `AUDIO_ANALYSIS` 로 통일한다.

- §5-1 / §3 기능 요구사항의 `SELF_ANALYSIS` 표기는 본 PR (plan, spec-drift-cleanup-pt1) 에서 `AUDIO_ANALYSIS` 로 일괄 정정 완료.
- `song-curation-seed-100.md` §5-7 충돌 해소 표의 `AUDIO_ANALYSIS` 와 동일 enum 값 — cross-ref 정합.
- 신규 enum 값 추가 불요. 마이그레이션 불요.

### 10-2) vocal/instrumental 분리 도입 평가

**현황**: PoC 의 `analyze.py` 는 spleeter 미도입 (`requirements.txt` 에서 comment-out). librosa `pyin` 을 곡 전체 audio 에 직접 적용해 pitch contour 추출 — 반주/드럼 의 harmonics 가 vocal 로 오인되어 lowMidi/highMidi 가 양쪽으로 늘어나는 회귀 위험이 있다.

**평가 후보**:

| 후보 | 모델 | 의존성 무게 | vocal isolation 품질 | 운영 부담 |
|---|---|---|---|---|
| **vocal-skip (현행)** | — | librosa 단독 | 낮음 — 반주 harmonics 오염 | 0 (현행) |
| **Spleeter 2stems** | Deezer, MIT | TensorFlow + ~200MB 모델 | 중상 (보컬/반주 2-stem) | TF 의존 → CI 빌드 시간 ↑ (librosa-ci-build-monitoring §5-7 조건 C 트리거) |
| **Spleeter 5stems** | Deezer, MIT | TF + ~300MB | 상 (보컬/드럼/베이스/피아노/기타) | 5stems 는 PoC 에 과함 |
| **Demucs v4** | Facebook Research, MIT | PyTorch + ~400MB | 상 (현 SOTA) | PyTorch 가 TF 보다 무거움 — CI 시간 회귀 더 심각 |
| **Demucs (hybrid lite)** | facebookresearch/demucs | PyTorch lite | 중상 | 의존성 무게 trade-off |

**의사결정 방식**: ADR (`docs/decisions/0015-self-analysis-vocal-separation.md`, 가칭) 로 분리. 본 spec 은 평가 기준만 정의:

- **품질 기준**: §10-4 ground truth set 의 lowMidi/highMidi 평균 절대 오차 (MAE, semitone 단위). 현행 vocal-skip 의 MAE 를 baseline 으로 두고, 후보 도입 시 MAE 가 **≥ 1 semitone 감소** 해야 채택.
- **운영 기준**: 곡당 분석 시간이 60s 를 넘지 않아야 한다 (현행 PoC 30s + 분리 추가 30s 한도). audio-tooling-bootstrap §3 비기능 (30s) 보다 +30s 완화 — 양산 단계의 trade-off.
- **CI 기준**: librosa-ci-build-monitoring §5-4 의 `total_seconds` 가 300s 를 넘으면 채택 보류 (CI 회귀 우선).

**default** (본 spec 머지 시점): **vocal-skip 유지** + ADR-0015 트리거 조건 미충족 시 PoC 그대로 양산. 트리거:

- (조건 1) §10-4 회귀 가드의 MAE 가 2 semitone 초과
- (조건 2) 운영 (`mobruji.song.audio.analysis.duration` p95) 에서 곡별 confidence 가 평균 0.5 미만으로 누적 100건 이상
- (조건 3) 사용자 / 큐레이터의 "음역대 추정 부정확" 피드백이 큐레이션 100곡 (#71) 검증 단계에서 ≥ 20% 보고

위 3 조건 중 1 개 충족 시 ADR-0015 신설 → Spleeter/Demucs 비교 실험.

### 10-3) 신뢰도 점수 공식 정형화

**현황**: `Song.applyAudioAnalysisResult(result)` 가 `result.confidence()` 를 그대로 컬럼에 저장 — 그 값이 어떻게 계산되는지는 Python tool 내부 black-box. 운영자/큐레이터가 confidence 값을 신뢰할 근거가 없다.

**v0.2 공식 (제안)**:

```
metadataConfidence = clamp(0.0, 1.0,
    w_coverage * f_coverage
  + w_vocal    * f_vocal
  + w_duration * f_duration
  + w_method   * f_method
)

where:
  f_coverage  = (pyin 으로 pitch 가 감지된 frame 수) / (전체 frame 수)
                — 보컬 멜로디가 곡 전체에 잘 분포되어 있을수록 ↑
  f_vocal     = vocal-skip 시 1.0 / Spleeter 2stems 사용 시 1.0 + 0.1 보너스 (단, clamp)
                — vocal isolation 가 정확도 보강
  f_duration  = min(1.0, duration_sec / 60)
                — 60초 미만 곡은 sample 부족 → confidence 하향
  f_method    = 0.9 (librosa pyin), 1.0 (Spleeter+pyin), 0.5 (fallback piptrack)
                — 분석 방법별 신뢰 가중

기본 가중치 (default, 합 1.0):
  w_coverage = 0.5
  w_vocal    = 0.2
  w_duration = 0.1
  w_method   = 0.2
```

**근거**:

- `f_coverage` 가 가장 강한 신호 (pitch 가 곡 전체에 분포 ≈ 멜로디가 안정) → 50% 가중.
- `f_method` 는 도구 자체의 baseline 신뢰 → 20%. 추후 ML 추정 도입 시 가중 재조정.
- `f_vocal` 는 vocal-skip vs Spleeter 의 효과 — Spleeter 도입 시 가산.
- `f_duration` 은 짧은 곡 (intro/snippet) 의 sample bias 보정.

**의사결정 방식**: 공식 자체는 **ADR-0016 (`docs/decisions/0016-self-analysis-confidence-formula.md`, 가칭)** 로 분리. 본 spec §10-3 은 v0.2 default 만 못박는다. 가중치 변경은 항상 ADR-0016 PR 로.

**구현**:

- Python tool (`tools/audio-analysis/analyze.py`) 의 JSON 출력 schema 에 4 개 sub-factor (`coverage`, `vocal`, `duration`, `method`) 와 합산 `confidence` 동시 노출 (관측성 + ADR 가중치 변경 시 재계산 가능).
- Spring 측 `AudioAnalysisRunner` 는 합산 `confidence` 만 Song 컬럼에 저장. sub-factor 는 로그 + (옵션) `SongAnalysis` 엔티티 저장 (audio-tooling-bootstrap §5-1).
- 기존 `metadataConfidence < threshold` selective query (#226 머지) 는 그대로 작동 — 공식이 정형화돼도 컬럼 의미는 동일.

**호환성**:

- `Song.applyAudioAnalysisResult` 의 signature 무변경 — `result.confidence()` 만 새 공식으로 계산.
- 기존 confidence 값이 있는 row 는 다음 backfill cycle 에서 자동 재계산 (`metadataConfidence < threshold` 가 selective query 의 트리거).
- 운영자의 수기 confidence override (수기 시드의 `metadataConfidence` 1.0) 는 충돌 해소 규칙 (`song-curation-seed-100.md` §5-7) 에 의해 보존 — 본 공식의 영향 없음.

### 10-4) Ground truth set + 회귀 가드

**목적**: 자동 분석 결과의 lowMidi/highMidi/key 가 사람 라벨 정답과 얼마나 어긋나는지를 매 PR 측정. 분석 알고리즘 (librosa 버전, vocal separation, confidence 공식) 변경 시 회귀를 즉시 잡는다.

**Ground truth set 구성**:

- **10 곡**, `song-curation-seed-100.md` 의 수기 검증 곡 중에서 선정 (curation spec §5-8 측정 가이드 준수). 장르 분포: 발라드 3 / 댄스 2 / 락 2 / 트로트 1 / 팝 2.
- 라벨: `tools/audio-analysis/tests/ground_truth.json` — 곡 ID, YouTube URL (또는 사전 캐시 audio fixture 경로), 라벨 `lowMidi`, `highMidi`, `keyOriginal`, 라벨러, 라벨 일자.
- audio fixture 는 저작권상 영구 저장 금지 (§3 비기능) — CI 에서는 매 실행마다 yt-dlp 로 재추출 (cache evict 시 cold start 비용은 librosa-ci-build-monitoring 의 wheel cache 효과로 상쇄).

**허용 오차 (회귀 가드)**:

| 지표 | 허용 오차 | 회귀 판정 |
|---|---|---|
| `lowMidi` MAE | ≤ 2 semitone | 평균 > 2 → fail |
| `highMidi` MAE | ≤ 2 semitone | 평균 > 2 → fail |
| `lowMidi` MAX (단곡) | ≤ 4 semitone | 단일 곡 > 4 → warn (3건 이상이면 fail) |
| `keyOriginal` 정확도 | 60% (관련 키 — perfect 5th 이내) | < 60% → warn |
| confidence 평균 | ≥ 0.6 | 평균 < 0.6 → warn |

- **MAE 기준 2 semitone**: 추천 알고리즘 v1 의 ±3 semitone 매칭 룰 (`recommendation-algorithm-v1.md`) 보다 1 semitone 엄격 — 분석 오차가 추천 매칭의 1/3 을 넘지 않도록.
- **key 정확도 60%**: librosa chroma 기반 key estimation 의 알려진 한계 (특히 marginal key — A minor vs C major 혼동) 를 인정. 추천에 key 가중치가 들어가는 시점 (`recommendation-algorithm-v2.md` w4 활성화) 에 임계 상향.

**테스트 통합**:

- Python: `tools/audio-analysis/tests/test_ground_truth.py` — pytest. CI (`audio-analysis-ci.yml` — librosa-ci-build-monitoring §5-1) 에서 실행. 실패 시 PR 차단.
- Spring 측은 별 통합 테스트 추가 불요 — `AudioAnalysisRunner` 의 ProcessBuilder mock 테스트는 그대로 유지.
- **실행 비용**: 10 곡 × ~30s = 5분. CI total_seconds 임계 (300s) 와 동일 — 임계 상향 또는 trend job 으로 분리 결정은 PR C 에서 (옵션 a: total_seconds 임계 600s 로 상향 / 옵션 b: ground truth 만 nightly cron 으로 분리).

### 10-5) 확장성 — cron vs 큐 시스템

**현황**: `AudioAnalysisScheduledBackfill` 가 `metadataConfidence < threshold` OR `metadataSource != AUDIO_ANALYSIS` selective query (#226 머지) 로 후보를 뽑고 cron (default daily) 으로 배치 분석. PR #235 가 systemd StartLimitInterval + selective backfill 정합 처리.

**한계**:

- 단일 인스턴스 — 동시 분석 불가 (Python ProcessBuilder 가 순차).
- cron 간격이 1일 → 신규 시드 곡의 분석 결과 반영이 24h delay.
- 곡 수가 1k 를 넘으면 daily 1회로 backlog 처리 불가.

**큐 시스템 전환 트리거**:

- (조건 1) 분석 대상 곡 누계 ≥ 1k
- (조건 2) 동시 분석 동시성 요구 ≥ 5 (운영자 수동 trigger 빈도 ↑)
- (조건 3) backfill 단일 cycle 의 처리 시간 ≥ 6h (24h cron 의 25% 초과)

위 조건 중 1개 충족 시 별 spec / ADR (`infra-audio-analysis-queue`) 신설. v0.3~v0.4 에선 트리거 미충족 가정 — 현행 cron + selective query 유지.

**후보 큐 시스템** (조건 충족 시 비교):

| 후보 | 강점 | 약점 |
|---|---|---|
| **Spring `@Async` + ThreadPoolTaskExecutor** | 별 인프라 0, 단일 JVM 내 | JVM 재시작 시 큐 손실, 분산 불가 |
| **Redis (Lettuce) + Spring Integration** | 단순, 영속 큐 | Redis 운영 추가, 단일 인스턴스 한계 |
| **RabbitMQ + Spring AMQP** | 표준 메시징, retry/DLQ | 운영 부담 ↑ |
| **AWS SQS** | 매니지드, retry 내장 | vendor lock-in, 비용 |

**default**: cron 유지. 트리거 충족 시 spike PR 로 비교.

### 10-6) 운영 모니터링 (Micrometer 카운터/타이머)

> **CI 메트릭과 의도적 분리**: 본 절의 `mobruji.song.audio.analysis.*` 는 운영 메트릭. `audio_analysis_ci.*` (GitHub Actions artifacts) 는 별 spec `librosa-ci-build-monitoring.md` 가 단일 진실. 두 spec 의 메트릭 키가 절대 충돌하지 않는다.

**필수 메트릭 (observability-baseline §5-3 표에 등재)**:

| Metric | Type | 라벨 | 의미 | 출처 |
|---|---|---|---|---|
| `mobruji.song.audio.analysis.requested` | counter | — | 분석 1회 trigger (수동 또는 cron) | 본 spec 신설 |
| `mobruji.song.audio.analysis.duration` | timer | `outcome` (success/failed) | 곡당 분석 처리 시간 (`AudioAnalysisRunner` 전체) | observability-baseline §5-3 (기 등재) |
| `mobruji.song.audio.analysis.confidence` | distribution summary | — | confidence 값 분포 (p10/p50/p95) | 본 spec 신설 |
| `mobruji.song.audio.analysis.failed` | counter | `reason` (python/io/parse/timeout/separation) | 분석 실패 1건 | audio-tooling-bootstrap §3 정합 |
| `mobruji.song.audio.backfill.requested` | counter | — | 스케줄 backfill 1회 trigger | observability-baseline (기) |
| `mobruji.song.audio.backfill.success` | counter | — | backfill 성공 1건 | observability-baseline (기) |
| `mobruji.song.audio.backfill.failed` | counter | `reason` | backfill 실패 1건 | observability-baseline (기) |

**알림 규칙 1개** (본 spec 신설, observability-baseline §5-6 표에 추가 권장):

| 규칙 | 트리거 | 채널 | 우선순위 |
|---|---|---|---|
| 자체 분석 confidence 평균 저하 | `mobruji.song.audio.analysis.confidence` 1시간 평균 < 0.5 (분석 ≥ 10건일 때만) | Discord webhook (`MOBRUJI_ALERT_WEBHOOK_URL`) | P2 |

- 위 규칙은 §10-2 vocal separation 트리거 조건 2 (confidence < 0.5 누적 100건) 의 사전 신호 역할.

**라벨 화이트리스트 정합**: `outcome` / `reason` / 본 표 외 라벨 추가 시 observability-baseline §5-7 차단 룰에 걸린다. 곡 `id` / `title` 등 카디널리티 폭발 라벨 금지.

### 10-7) 결과 저장 위치

**현황**: `Song.applyAudioAnalysisResult` 가 `lowMidi`, `highMidi`, `metadataSource`, `metadataConfidence` 4 컬럼만 직접 갱신. pitch contour / 시간별 통계 / 분리된 vocal stem 등은 영속화하지 않고 즉시 폐기 (저작권 + 디스크 비용).

**v0.2 결정**: **현행 유지** — Song 컬럼 직접 갱신. 별 `SongAnalysis` 엔티티는 `audio-tooling-bootstrap.md §5-1` 가 책임 (본 spec 범위 외). 본 spec 은 그 엔티티가 만들어졌을 때의 cross-ref 만 보장:

- `SongAnalysis` 엔티티 도입 시 — `tooling_version`, `pitch_std_hz`, sub-factor (§10-3 의 `f_coverage` 등) 4개, `analyzed_at` 컬럼이 우선 후보.
- Song 컬럼 (lowMidi/highMidi/metadataConfidence) 은 항상 SongAnalysis 의 최신 결과 + 충돌 해소 규칙 (`song-curation-seed-100.md` §5-7) 의 cache view 역할. 두 곳의 정합은 `AudioAnalysisRunner` 가 단일 트랜잭션으로 갱신.
- 본 spec §10-7 결정은 audio-tooling-bootstrap §5-1 의 엔티티 결정이 머지될 때 자동 정합.

### 10-8) BPM/key 추출 — Spotify Audio Features 와의 우선순위

> plan 29 (#264) 의 결정 재확인: **self-analysis 가 BPM/key 의 1차 권위**, Spotify Audio Features 의 BPM/key 는 fallback 또는 검증용.

- `spotify-audio-features-integration.md` 의 `key`/`tempo` 컬럼 도입 결정이 본 spec 의 BPM/key 추출과 충돌 가능 — plan 29 결정에 의해 self-analysis 가 우선.
- Spotify Audio Features 는 `valence`/`energy` 2 차원만 추천에 사용 (mood 산출), key/tempo 는 self-analysis 결과와 비교 검증용으로만 활용.
- 추천 알고리즘 v2 (`recommendation-algorithm-v2.md`) 의 w4 활성화 시 mood signal 은 Spotify valence/energy 사용, key 매칭은 self-analysis `keyOriginal` 사용.

### 10-9) mood 분류 — Spotify valence/energy fallback

- 본 spec 의 자체 분석에서 valence/energy 추정은 **도입하지 않는다** (PoC 정확도가 librosa 기반으로 매우 낮음 — Spotify 의 사전 학습 모델 대비 열위).
- mood 신호는 Spotify Audio Features (`valence`/`energy`) 1차, Spotify 미매칭 곡은 큐레이션 수기 `mood` enum (현행 `Song.mood`) 사용.
- 추후 별 ML 모델 도입 시 (v0.4+) 본 spec 에 §10-9 갱신 또는 별 spec.

## 11) v0.2 양산 단계 PR 분할

기존 §6 의 PR A~F (PoC) 는 머지/진행 중. 본 절은 v0.2 양산 단계의 후속 PR 만 정의.

- [ ] **PR D (chore:song, spec 묶음)** — **본 PR**: 본 spec §10~§13 신설. 라벨 `type:docs`, `scope:song`, `ai-generated`, `ai:claude`.
- [x] **PR E (chore:song)** — **§10-1 enum 표기 정정**: 기존 §3 / §5-1 의 `SELF_ANALYSIS` → `AUDIO_ANALYSIS` 정정 spec PR. 분량 XS. 코드 변경 없음 (코드는 이미 `AUDIO_ANALYSIS`). plan `docs/spec-drift-cleanup-pt1` PR 에서 완료.
- [ ] **PR F (chore:song)** — **§10-2 vocal separation 평가 spike + ADR-0015**: Spleeter 2stems / Demucs / vocal-skip 3 후보에 대해 ground truth set (PR H 후) 의 MAE / 분석 시간 / CI 비용 측정 → ADR-0015 작성. 분량 M. **PR H 선행 (ground truth set 필요)**.
- [ ] **PR G (feat:song)** — **§10-3 신뢰도 공식 정형화 + ADR-0016**: Python tool 의 JSON schema 에 sub-factor 4개 노출 + 합산 공식 구현 + Spring 측 `AudioAnalysisRunner` 의 schema 파싱 갱신. `Song.applyAudioAnalysisResult` 무변경 (sub-factor 는 SongAnalysis 가 받을 때 도입). 분량 M.
- [ ] **PR H (test:song)** — **§10-4 ground truth set 신설**: `tools/audio-analysis/tests/ground_truth.json` 10 곡 라벨 + `test_ground_truth.py` 회귀 가드 테스트. curation 100곡 (#71) PR C 중 수기 검증 끝난 곡 중에서 선정. 분량 M. **PR G 와 독립 — 병렬 가능**.
- [ ] **PR I (feat:song / scope:infra)** — **§10-6 운영 모니터링**: Micrometer counter/timer 4개 (`requested` / `duration` / `confidence` / `failed`) + observability-baseline §5-3 표에 cross-ref 추가 + Discord 알림 1 규칙. 분량 S~M. observability-baseline PR 2 (be 카운터 통일) 와 통합 가능.
- [ ] **PR J (조건부)** — **§10-5 큐 시스템 전환 spec**: 트리거 조건 충족 시 별 spec 신설. v0.3 안에서는 미진입 예상.

### 11-1) PR 의존 그래프

```
PR D (본 PR — spec)
  ├─ PR E (enum 표기 정정)
  └─ PR H (ground truth set)
        ├─ PR F (vocal separation 평가 + ADR-0015)
        └─ PR G (신뢰도 공식 + ADR-0016)
              └─ PR I (운영 모니터링 + 알림)
```

### 11-2) 협업 패턴

- **PR D / E** — plan 세션이 즉시 수행.
- **PR F / G / H / I** — be 세션이 구현. maestro가 이슈 등록 후 위임.
- **PR J** — 트리거 조건 자동 감지 후 (관측성 알림) plan 세션이 spec 작성.

## 12) v0.2 양산 단계 오픈 질문 (§10~§11 신설분)

기존 §8 (Q1/Q3/Q4) 와 별도로 v0.2 양산 결정 과정에서 새로 생긴 질문:

| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q5 | §10-2 vocal separation 트리거 조건 1 (MAE > 2 semitone) 의 정확도 기준이 너무 관대한가? | (a) 2 semitone 유지 — PoC 분석 정확도 합리적 / (b) 1.5 semitone — 엄격하지만 vocal separation 도입 가능성 ↑ | @goohong / PR H 머지 후 baseline 측정 시 |
| Q6 | §10-3 신뢰도 공식의 default 가중치 (0.5/0.2/0.1/0.2) 가 적절한가? | ADR-0016 작성 시 1차 곡 10건 분석 후 조정 | @goohong / PR G 진입 시 |
| Q7 | §10-4 ground truth set 10 곡이 충분한가? | (a) 10 곡 — CI 시간 / 라벨링 비용 합리적 / (b) 30 곡 — 통계적 의미 ↑ but CI +15분 | @goohong / PR H 머지 후 |
| Q8 | §10-5 큐 시스템 전환 조건 1 (곡 ≥ 1k) 의 절대값 vs `백로그 곡 수 / cron 간격` 의 상대값? | (a) 절대값 — 단순 / (b) 상대값 — backlog 누적 감지에 민감 | @goohong / 트리거 진입 시 |
| Q9 | §10-9 mood 자체 분석 도입 시점은 v0.4 ML 도입과 같이? | (a) v0.4 — ML 모델과 통합 / (b) v0.3 후반 — librosa MFCC 기반 단순 분류 | @goohong / v0.3 P3 |

## 13) v0.2 양산 단계 비기능 요구사항 (§3 보강)

기존 §3 비기능 (PoC 한정) 에 양산 단계에서 추가되는 요구사항:

- **결정성**: §10-3 신뢰도 공식의 가중치 변경은 항상 ADR-0016 PR 로. spec / 코드 / 운영 confidence 값이 단일 진실 (ADR-0016) 을 가리킨다.
- **응답시간**: 곡당 분석 시간 — vocal-skip 30s / Spleeter 도입 시 60s 한도 (§10-2). p95 측정은 `mobruji.song.audio.analysis.duration` (observability-baseline §5-3 등재).
- **설정 외부화**:
  - vocal separation 도입 여부는 `application.yml` 의 `audio.analysis.vocal-separation.enabled` (default false). ADR-0015 결정 후 변경.
  - confidence 공식 가중치는 `application.yml` 외부화 하지 않는다 — 결정성 깨짐. ADR-0016 + 코드 상수.
  - CI / 운영 메트릭의 임계는 librosa-ci-build-monitoring §5-4 / observability-baseline §5-6 가 단일 진실.
- **관측성**: §10-6 표의 4 메트릭 모두 등재. CI 메트릭 (`audio_analysis_ci.*`) 과 의도적 분리 (librosa-ci-build-monitoring §5-3 정합).
- **품질 게이트**: §10-4 ground truth set 회귀 가드는 CI 필수 (audio-analysis-ci.yml — librosa-ci-build-monitoring §5-1). PR 마다 통과 필수.
- **보안**: audio fixture / vocal stem / pitch contour 모두 임시 캐시 한정 (§3 기존 룰 유지). ground truth JSON 에 audio 메타만 (URL, 곡 ID, MIDI 라벨) — audio 바이너리 미커밋.

