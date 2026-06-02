---
feature: 곡 분석 데이터 스키마 + 소비자 계약 (song-analysis-data-and-consumers)
slug: song-analysis-data-and-consumers
status: draft
owner: "@goohong"
scope: song
related_issues: [1490, 1484, 1485, 1486, 1488, 1494]
related_prs: []
last_reviewed: 2026-06-03
---

# 곡 분석 데이터 스키마 + 소비자 계약 (song-analysis-data-and-consumers)

## 1) 개요 (What / Why)
- self-analysis pivot(`[[project-self-analysis-pivot]]`)의 토대 — 곡 메타(음역 범위 / key / energy / 분위기 태그)를 **분석·저장하는 데이터 스키마**와, 그 데이터를 **추천 기능들이 읽는 소비자 계약**을 정의한다.
- 기존 `song-self-analysis-pipeline.md`(yt-dlp/spleeter/librosa DSP **생산자**)·`audio-tooling-bootstrap.md`(Python 툴링)가 *어떻게 분석값을 만드는가*를 다룬다면, 본 spec은 *그 분석값이 어떤 형태로 저장되고 누가 어떻게 소비하는가*(데이터 계약)를 다룬다. 둘은 생산자/소비자 관계로 의도적 분리.
- #1490 의 핵심 신규 기여는 (1) **energy 차원 추가**(기존 파이프라인 §10-9 가 자동 산출을 보류했던 영역을 **스키마 + 수기/배치 적재 스캐폴드**로 먼저 채움), (2) **소비자 계약** — #1484 voiceFit / #1485 mood / #1486 next-song / #1488 트렌딩 / #1494 연습이 동일한 분석 데이터 표면(`SongAnalysisProfile`)을 읽도록 단일화.
- 액터: 시스템(시드/배치 적재) + 운영자(큐레이션·수기 보정) + 다운스트림 추천 기능(소비자).

## 2) 사용자 시나리오
직접 화면은 없다(데이터/계약 layer). 시나리오는 적재(쓰기)·소비(읽기) 두 축:

1. **시드 적재** — 운영자가 큐레이션 100곡(`song-curation-seed-100.md`)에 곡별 `energy`(0~1)·`mood` enum 을 수기로 채워 시드 JSON 에 넣으면 부팅 시 멱등 적재된다.
2. **배치 적재** — 분석 파이프라인(`AudioAnalysisRunner` / backfill)이 `lowMidi`/`highMidi`/`keyOriginal` 을 자동 채운다. `energy`/`mood` 자동 산출은 후속(§8 Q2).
3. **소비(읽기)** — 추천 요청 시 `RecommendationService` 가 후보 곡의 `SongAnalysisProfile` 을 읽어 voiceFit·mood 변별·유사도·난이도 표시에 사용하고, 곡 상세 응답이 동일 프로파일을 노출한다.

## 3) 요구사항
### 기능 요구사항
- [ ] `Song` 에 `energy`(Float 0.0~1.0, nullable) 컬럼 + 도메인 등재(§4-1 완료).
- [ ] 곡 분석 파생 속성 묶음 read-model `SongAnalysisProfile`(`lowMidi`/`highMidi`/`keyOriginal`/`difficulty`/`mood`/`energy`/`metadataConfidence`) 정의 — 영속 엔티티 아님, `Song` 컬럼들의 view.
- [ ] 시드 JSON(`songs-seed.json`)에 `energy`/`mood` 필드 추가 + `SongSeedLoader` 멱등 적재.
- [ ] `GET /api/v1/songs/{id}` 응답에 `SongAnalysisProfile` 노출(소비자·연습 화면용).
- [ ] **소비자 계약(§5-3)** 의 5개 다운스트림(#1484/#1485/#1486/#1488/#1494)이 읽는 필드 ↔ 사용 방식이 본 spec 표와 일치.
- [ ] `energy` 미적재(null) 곡은 소비자가 **graceful degrade**(해당 신호 가중 0, 다른 신호로 추천 — 결정성 유지).

### 비기능 요구사항
- **결정성**: 분석 필드 적재는 추천 점수 산식을 바꾸지 않는다 — 소비자(#1485 등)가 가중치를 바꿀 때만 별 PR/회귀 가드. null 곡 처리로 결정성 회귀 가드.
- **관측성**: `energy` 적재율(non-null 비율) 메트릭 1종 — `mobruji.song.energy.coverage`(gauge). observability-baseline §5-3 정합.
- **보안**: 분석 원본 audio/contour 영속 금지(기존 `song-self-analysis-pipeline.md` §3 룰 유지). 본 spec 은 파생 스칼라(0~1, MIDI, enum)만 저장.
- **성능**: 소비자가 `SongAnalysisProfile` 을 읽을 때 추가 쿼리 없음 — `Song` 단일 row 에서 파생(N+1 회피).

## 4) 범위 / 비범위 (중요)
### 포함
- `Song.energy` 스키마 + `SongAnalysisProfile` read-model 정의.
- 수기/시드 + 배치 적재 스캐폴드(단계 분리, be 단계적 구현).
- 5개 다운스트림 소비자 계약 표(읽는 필드 ↔ 사용 방식).
- 곡 상세 API 의 프로파일 노출.

### 제외 (Out of Scope)
- **energy/mood 자동 산출 알고리즘** — librosa MFCC 또는 Spotify valence/energy fallback 의 도입은 후속(§8 Q2 / `song-self-analysis-pipeline.md` §10-9). 본 spec 은 *스키마 + 적재 경로*만.
- **DSP 추출 파이프라인 자체** — `song-self-analysis-pipeline.md` / `audio-tooling-bootstrap.md` 범위.
- **각 소비자 기능의 구현** — #1484/#1485/#1486/#1488/#1494 는 각자 별 PR/이슈. 본 spec 은 그들이 의존하는 *데이터 계약*만 못박는다.
- **mood 다중 태그** — 현행 `Song.mood` 단일 enum 유지(다중화는 v2, `song-metadata-source.md`).
- **트렌딩 집계 로직** — #1488 의 history 집계는 별 spec. 본 spec 은 트렌딩이 분석 태그(음역/분위기)를 *결합*하는 계약만 명시.

## 5) 설계

### 5-1) 도메인 모델
- 건드리는 컨텍스트: `song`(쓰기·스키마), `recommendation`(읽기·소비). `06-domain-model.md` §4-1 / §5-2.
- 신규 용어(§4-1 등재 완료): **`Energy`**(곡 음향 에너지 0~1), **`SongAnalysisProfile`**(분석 파생 속성 read-model).
- `SongAnalysisProfile` 구성:

  | 필드 | 타입 | 출처 | 비고 |
  |---|---|---|---|
  | `lowMidi` / `highMidi` | Integer | DSP 파이프라인 / 수기 | 음역 범위. #1484/#1486/#1494 소비 |
  | `keyOriginal` | enum `MusicalKey` | DSP chroma / 수기 | #1486 유사도 |
  | `difficulty` | enum `Difficulty` | `lowMidi`/`highMidi` 자동 분류 | #1494 표시 |
  | `mood` | enum `Mood` | 수기/시드 1차 | #1485/#1486/#1488 |
  | `energy` | Float 0~1 | 수기/시드 1차(자동화 후속) | #1485/#1486 |
  | `metadataConfidence` | Float 0~1 | 분석 신뢰도 | 소비자 graceful degrade 판단 |

- `SongAnalysisProfile` 은 **영속 엔티티가 아니다** — `Song` 엔티티 컬럼들의 조회용 묶음(`song-self-analysis-pipeline.md` §10-7 의 "cache view" 원칙과 정합). 별 `SongAnalysis` 엔티티(raw 통계)는 `audio-tooling-bootstrap.md` §5-1 책임 — 본 spec 범위 외 cross-ref.

### 5-2) API 엔드포인트
| Method | Path | 설명 | 인증 | Req | Res |
|---|---|---|---|---|---|
| GET | /api/v1/songs/{id} | 곡 상세 — `SongAnalysisProfile` 포함 | 불필요 | - | `SongResponse`(+ `analysisProfile`) |

- 추천 응답(`POST /api/v1/recommendations`)의 곡별 근거 필드(#1484 `voiceFit` 등)는 **각 소비자 spec 이 정의** — 본 spec 은 그 근거가 `SongAnalysisProfile` 에서 파생됨만 보장.

### 5-3) 소비자 계약 (★ #1490 핵심)
각 다운스트림이 읽는 분석 필드 ↔ 사용 방식. 모든 소비자는 동일 `SongAnalysisProfile` 표면을 읽고, null 필드는 graceful degrade 한다.

| 소비자(이슈) | 읽는 분석 필드 | 사용 방식 | 연결 신호 |
|---|---|---|---|
| **#1484 voiceFit 설명가능성** (P-A) | `lowMidi`/`highMidi` | 사용자 음역 ∩ 곡 음역 overlap → `voiceFit` 0~1 + 한국어 사유. 응답 DTO 노출 | `ScoreBreakdown.rangeFit` |
| **#1494 연습 음역/난이도 표시** (P-A) | `lowMidi`/`highMidi`, `difficulty` | 곡 상세/배지에 음역 범위 + 난이도 표시 | (표시 전용, 점수 무관) |
| **#1485 mood 변별력 강화** (P-C) | `mood` (+ `energy`) | 추천 랭킹 mood 가중 강화 — before/after 변별 입증 | `ScoreBreakdown.moodMatch` |
| **#1486 부른곡 next-song** (P-B) | `lowMidi`/`highMidi`, `keyOriginal`, `mood`, `energy` | `seedSongIds` 곡들의 프로파일 → 음역/key/mood/energy 유사도 기반 next-song | 신규 유사도 함수(별 spec) |
| **#1488 트렌딩** | `mood`, `lowMidi`/`highMidi` | history 집계 인기곡을 음역/분위기로 결합·필터(섹션) | `ScoreBreakdown.popularity` + 필터 |

- **계약 불변식**: 소비자는 `Song` 컬럼을 직접 읽지 않고 `SongAnalysisProfile`(애플리케이션 read-model)을 경유한다 — 분석 필드 추가/이름 변경 시 단일 지점 갱신.
- **graceful degrade**: `energy` null → #1485/#1486 의 energy 가중 0(다른 신호로 추천). `mood` null → mood 신호 0. `lowMidi`/`highMidi` null → voiceFit/난이도 미표시(배지 숨김). 결정성 회귀 가드(비기능).

### 5-4) 데이터 흐름 / 시퀀스
```
[적재(쓰기)]
 수기 시드(energy/mood) ─┐
 DSP 배치(range/key)  ─┼─→ Song 컬럼(lowMidi/highMidi/keyOriginal/difficulty/mood/energy/metadataConfidence)
 운영자 수기 override ─┘        │ (충돌 해소: song-curation-seed-100.md §5-7)
                                ▼
[소비(읽기)]  Song ──→ SongAnalysisProfile(read-model) ──→ 추천/연습/트렌딩/next-song 소비자
```

### 5-5) DB 마이그레이션
- `song` 테이블에 `energy DECIMAL(3,2)` nullable 추가(Flyway 신규 Vx). 단순 컬럼 추가 — 기존 row 는 null(graceful degrade).
- `SongAnalysisProfile` 은 read-model 이라 **테이블 없음**.
- 엔티티 변경(`Song.energy`)은 구현 PR(단계 1)에서 `06-domain-model.md` §5-2 Song 표 + §6 ERD 를 **같은 PR** 로 갱신(CLAUDE.md §4 도메인 룰). 본 spec 은 용어(§4-1)만 선등재.

### 5-6) 프론트엔드 화면
- 본 spec 범위 아님. #1494(음역/난이도 배지)·#1484(voiceFit 배지)가 각자 fe PR 로 `SongAnalysisProfile` 소비.

## 6) 작업 분할 (예상 PR 리스트)
> be 단계적 구현 가능하도록 스키마 → 적재 → 소비 노출 순서 분리. PR 1 선행, 이후 병렬 가능.

- [ ] **PR 1 (feat:song)** — 스키마: `Song.energy` 컬럼 + Flyway 마이그레이션 + `06-domain-model.md` §5-2/§6 갱신. E2E: 곡 생성/조회 시 `energy` round-trip.
- [ ] **PR 2 (feat:song)** — read-model: `SongAnalysisProfile` 정의 + `GET /api/v1/songs/{id}` 응답 노출 + RestAssured E2E.
- [ ] **PR 3 (chore:song)** — 수기/시드 적재: `songs-seed.json` 에 `energy`/`mood` 채움 + `SongSeedLoader` 멱등 적재 + 적재율 메트릭(`mobruji.song.energy.coverage`).
- [ ] **PR 4 (chore:song / 조건부)** — 배치 적재 연결: 분석 backfill 이 `energy`/`mood` 자동 산출(§8 Q2 결정 후). 미결 시 보류.

### 보호 영역 변경 여부 (필수 명시)
- 보호 영역 변경 여부: ☑ 있음 — 변경 파일과 사유:
  - `**/db/migration/**`(Flyway) — `song` 테이블 `energy` 컬럼 추가(PR 1). nullable 단순 추가, 기존 row 영향 없음(default null). rev 단계 1 에서 마이그레이션 멱등성·롤백 확인 권고.

## 7) 테스트 전략
- **단위**: `SongAnalysisProfile.from(Song)` 매핑(null 필드 → graceful 표현), `difficulty` 자동 분류 재사용.
- **통합/E2E (RestAssured)**: `GET /api/v1/songs/{id}` 응답에 `analysisProfile` 포함 + `energy` null/non-null 두 케이스. (CLAUDE.md §4 — 신규 엔드포인트 변경 성공 E2E 필수, 단 기존 엔드포인트 확장이므로 회귀 E2E.)
- **소비자 계약 회귀**: 각 소비자 spec 의 E2E 가 `SongAnalysisProfile` 필드를 읽는다는 전제 — 본 spec 은 계약 표(§5-3)가 소비자 구현과 drift 나지 않는지 rev 단계 1 cross-ref.
- **결정성 가드**: `energy`/`mood` null 곡이 추천 결과 순서를 바꾸지 않음(기존 결정성 회귀 테스트에 null-profile 곡 fixture 추가).

## 8) 오픈 질문
> 구현 전에 답이 나와야 하는 것들. 해소되면 §9 결정 로그로 이동.

| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | `energy` 저장 위치 — `Song` 컬럼 vs `SongAnalysis` 엔티티? | (a) `Song` 컬럼(mood 와 동일, 소비자 단일 row) / (b) `SongAnalysis`(raw 통계와 동거) | @goohong / PR 1 전 — 잠정 (a) |
| Q2 | `energy`/`mood` **자동 산출** 출처? | (a) 수기/시드만(현 스캐폴드) / (b) Spotify valence·energy fallback(`song-self-analysis-pipeline.md` §10-9) / (c) librosa MFCC 자체 분석 | @goohong / PR 4 진입 시 |
| Q3 | `voiceFit` 0~1 산식을 본 spec 이 정의 vs #1484 spec 이 정의? | (a) #1484 가 정의, 본 spec 은 입력 필드(range)만 보장(잠정) / (b) 본 spec 이 공통 산식 정의 | @goohong / #1484 착수 시 |
| Q4 | next-song(#1486) 유사도 함수에 `energy` 가중치? | (a) 포함(4 신호: range/key/mood/energy) / (b) energy 자동 산출 전까지 제외(3 신호) | @goohong / #1486 착수 시 |

## 9) 결정 로그
> 연대기 순. "YYYY-MM-DD: 결정 / 이유 / 출처(PR 번호 등)"

- 2026-06-03: 초안 작성 (status=draft). #1490 — self-analysis pivot 토대의 **데이터 스키마 + 소비자 계약** layer 신설. 기존 `song-self-analysis-pipeline.md`(DSP 생산자)와 생산자/소비자로 분리. `energy` 차원을 스키마 + 수기/배치 적재 스캐폴드로 선도입(자동 산출은 §8 Q2 보류 — 기존 §10-9 정확도 우려 정합). 소비자 5종(#1484/#1485/#1486/#1488/#1494) 계약 표(§5-3) 단일화. 도메인 용어 `Energy`/`SongAnalysisProfile` §4-1 등재.
