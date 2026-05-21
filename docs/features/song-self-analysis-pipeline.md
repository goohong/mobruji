---
feature: 자체 곡 분석 파이프라인 (song-self-analysis-pipeline)
slug: song-self-analysis-pipeline
status: draft
owner: "@goohong"
scope: song
related_issues: [67, 98]
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
- [ ] 추출 결과를 `Song` 엔티티에 update (manual override 가능, `metadataSource = SELF_ANALYSIS` 표기).
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
  - `metadataSource` — 신규 enum 값 `SELF_ANALYSIS` 추가 (기존 `MANUAL`, `EXTERNAL_API`, `INFERRED`와 병행).
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
        ↓ Song 엔티티 update (metadataSource=SELF_ANALYSIS)
[DB]
        ↓ (임시 캐시 audio 즉시 삭제)
```
실패 시 곡은 `metadataConfidence`=LOW로 표기하고 운영자 검토 큐로.

### 5-5) DB 마이그레이션
- `metadata_source` enum 값에 `SELF_ANALYSIS` 추가 (Flyway).
- `metadata_confidence` 필드가 없으면 신설.
- 기존 `song` 테이블 구조 변경 영향은 작음.

### 5-6) 프론트엔드 화면
- 본 spec 범위 아님.

## 6) 작업 분할 (예상 PR 리스트)
- [ ] PR A (docs): 본 spec 초안 — 본 PR #99.
- [ ] PR B (chore:song): Python worker 부트스트랩(yt-dlp + Spleeter + Librosa 환경) + 단일 곡 end-to-end smoke 스크립트.
- [ ] PR C (feat:song): `metadata_source = SELF_ANALYSIS` 추가 + 분석 결과 ingest API (운영자/내부용).
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
