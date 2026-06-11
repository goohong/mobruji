---
feature: 노래방 시드 곡 큐레이션 100곡 확장 (song-curation-seed-100)
slug: song-curation-seed-100
status: draft
owner: "@goohong"
scope: song
related_issues: [71, 120, 276, 277, 278, 279]
related_prs: [121]
last_reviewed: 2026-05-22
---

# 노래방 시드 곡 큐레이션 100곡 확장 (song-curation-seed-100)

## 1) 개요 (What / Why)
- 현재 `backend/src/main/resources/songs-seed.json`은 30곡만 보유. 사용자가 vocal range/장르/분위기로 추천을 요청해도 매칭 후보가 너무 적어 PoC 사용성이 떨어진다.
- 사용자 자체 분석 pivot([[project-self-analysis-pivot]]) 이후, 방대한 외부 카탈로그(Spotify/MusicBrainz) ingest는 폐기하고 **수기 큐레이션 + 자체 분석**으로 데이터셋을 만든다.
- 본 spec은 시드 곡을 **30곡 → 한국 노래방 인기곡 100곡**으로 확장하는 큐레이션 작업의 기준/범위/검증 방법을 정의한다.
- 액터: 운영자(큐레이션 책임) + AI(후보 곡 + 음역 초기값 제안). 본 데이터셋은 동시에 `song-self-analysis-pipeline.md`의 분석 입력으로 사용된다.
- **데이터 권위(authority)**: 수기 큐레이션이 1st-class, 자체 분석(`AUDIO_ANALYSIS`)은 미보강 곡의 fallback. 충돌 해소 규칙은 §5-7 참조.

## 2) 사용자 시나리오 (운영 + 사용자 효과)
운영 시나리오:

1. 운영자가 한국 노래방 인기곡 차트(TJ/금영/Melon)를 교차해 **후보 곡 100곡**을 정한다. AI가 1차 후보 리스트를 제안할 수 있다.
2. 각 곡에 대해 (a) 원곡 키, (b) lowMidi/highMidi, (c) 장르/mood, (d) BPM을 채운다.
   - **lowMidi/highMidi**는 (i) YouTube 원곡을 직접 듣고 추정 또는 (ii) `song-self-analysis-pipeline.md`의 자체 분석 결과를 1차값으로 사용 후 운영자가 검증.
   - 측정 가이드는 §5-8 참조.
3. `metadataSource = MANUAL_SEED`, `metadataConfidence` 표기 후 `songs-seed.json`에 추가.
4. `SongSeedLoader` 멱등 적재 → DB 반영.

사용자 효과:
- 사용자가 본인 음역대를 입력하면 **매칭 곡 수가 30 → 100으로 증가**해 추천 다양성이 향상된다.
- 동일 음역대라도 장르/시대 분포가 균형 잡혀 추천 결과 단조로움이 줄어든다.

## 3) 요구사항
### 기능 요구사항
- [ ] 한국 노래방 인기곡 100곡 큐레이션 (기존 30곡 포함, 70곡 신규 추가).
- [ ] 장르 균형 default — 발라드 30 / 댄스 20 / 락 15 / 트로트 15 / 팝 20 (= 100). 운영자 재량으로 ±5 조정 가능 (§9 결정 로그에 기록).
- [ ] 시대 균형 default — 90s 20 / 00s 25 / 10s 30 / 20s 25 (= 100). 운영자 재량으로 ±5 조정 가능.
- [ ] 각 곡 필드 — `title`, `artist`, `releaseYear`, `keyOriginal`, `bpm`, `mood`, `language`, `genre`, `tjNumber`(가능 시), `kyNumber`(가능 시), `lowMidi`, `highMidi`, `metadataConfidence`.
- [ ] `metadataSource = MANUAL_SEED` 고정 (loader 코드에 하드코딩되어 JSON 필드 노출 없음 — 현 구현 유지).
- [ ] `metadataConfidence` 0.7~1.0 범위 (수기 + 일부 차트/위키 참조). default 1.0, 음역 추정 자신 없을 시 0.7~0.9.
- [ ] `songs-seed.json` 포맷 그대로 호환 — `SongSeedLoader`가 별도 마이그레이션 없이 적재 가능 (§5-5).
- [ ] 적재 멱등 — 동일 (title, artist) 재실행 시 중복 row 생성 금지. `backfillMissingFields` 의미 유지(운영 중 수정 값 덮지 않음).
- [ ] 시드 JSON 형식 검증 테스트 — 필수 필드/MIDI 범위/lowMidi < highMidi sanity 단위 테스트(§7).

### 비기능 요구사항
- 외부 데이터 출처 명시 — Melon/지니/TJ/금영 차트는 참고용. **가사/원본 audio 무단 복제 금지**.
- 음역대 추정 신뢰도 0.7 이상 곡만 시드에 포함. 0.7 미만은 자체 분석 보강 후 재시도(검증 테스트가 강제).
- 큐레이션 작업 자체에 사용자의 개인 음역 데이터(=서비스 이용자 음역)는 사용하지 않는다(CLAUDE.md §4 보안).
- 적재 idempotency — `SongSeedLoader` 기존 동작(존재 시 backfill, 운영 수정 값 보존) 유지.
- 시드 파일 크기 — JSON 100곡 단일 파일은 ~5KB×100 = 500KB 미만 예상. 분할(예: `songs-seed-ko-ballad.json`)은 아직 비도입, 200곡 초과 시 재평가.
- 빌드 영향 — `SongSeedLoader` 부팅 시 100곡 upsert. 로컬 부팅 추가 시간 1초 미만(현 30곡 < 200ms).

## 4) 범위 / 비범위 (중요)
### 포함
- 100곡 후보 리스트 작성 (큐레이션 + 음역 초기값 추정).
- `backend/src/main/resources/songs-seed.json` 갱신.
- 적재 검증 — 단위(필드 유효성) + E2E(RestAssured로 100곡 모두 로드되는지).
- 장르/시대 균형 default 합의 (변경 시 §9에 결정 로그).
- 수기 vs 자동 분석 충돌 해소 규칙(§5-7).

### 제외 (Out of Scope)
- **자체 분석 자동 도구 자체** — `song-self-analysis-pipeline.md` 별 spec. 본 spec은 그 출력의 _소비자_.
- **ML 기반 자동 음역 추정** — v0.3+로 이연.
- **노래방 곡번호 자동 매핑** — ADR #70 (v0.2 보류). tjNumber/kyNumber는 _가능 시_만 채움.
- **외부 음원 API 대량 ingest** — pivot 이후 폐기됨.
- **사용자 업로드 audio 분석** — 별 의사결정.
- **운영자/일반 사용자가 웹 UI로 곡을 등록·수정하는 admin 페이지** — v0.4 P3 후보(§6 PR C). 본 v0.2 spec은 JSON 직접 편집.
- **외부 큐레이션 기여(타 운영자/유저 제출)** — v0.4 P3 후보(§4-1).

### 4-1) 미래 경로: 외부 큐레이션 기여 (v0.4 P3)
- 현 v0.2: 운영자(@goohong) 단독 수기 큐레이션.
- v0.4 P3 후보: GitHub PR 기반 큐레이션 컨트리뷰션 — `songs-seed.json`에 새 곡 row를 추가하는 PR을 외부 기여자가 제출할 수 있도록 한다.
  - JSON schema(§5-5) + 검증 테스트(§7)가 사실상 컨트리뷰션 게이트 역할.
  - 추가 산출물 (별 spec): `CONTRIBUTING.md` 곡 추가 섹션, PR 템플릿(곡 추가용), 라이선스/저작권 고지 체크리스트.
- v0.4 P3 진입 전까지는 본 spec 범위 외. 본 spec은 그 길을 막지 않는 형식(JSON + 검증 테스트)을 채택하는 것까지가 책임.

## 5) 설계

### 5-1) 도메인 모델
- 기존 `Song` 엔티티 사용. 새 필드/마이그레이션 없음.
- `metadataSource = MANUAL_SEED` (현 `MetadataSource` enum 값) — `SongSeedLoader`가 하드코딩.
- `metadataConfidence`는 V3에서 promote됨(`song-metadata-source.md` §9 2026-05-22 항목 참조). 현 30곡은 모두 1.0.
- 새 도메인 용어 없음.

### 5-2) API 엔드포인트
- 본 spec 단계에서 신규 endpoint 없음. 기존 `GET /api/v1/songs` 등이 100곡을 노출.

### 5-3) 외부 연동
- TJ/금영/Melon 차트는 **참고용** — 자동 크롤링하지 않는다. 운영자가 수기로 인기곡을 확인.
- YouTube — 음역 추정용 청취 또는 `song-self-analysis-pipeline.md` 자체 분석 입력.

### 5-4) 데이터 흐름
```
[차트 + 운영자 큐레이션]
        ↓
[100곡 후보 리스트 (제목/아티스트/장르/연도)]
        ↓ (a) 운영자 청취 + 수기 추정
        ↓ (b) song-self-analysis-pipeline 자동 추정 → 운영자 검증
[lowMidi/highMidi 확정]
        ↓
[songs-seed.json 갱신 PR]
        ↓
[SongSeedLoader (멱등 upsert + backfill)]
        ↓
[DB: song 테이블 100건]
```

### 5-5) 시드 JSON 스키마 (사실상의 계약)
파일: `backend/src/main/resources/songs-seed.json` — JSON 배열, 각 element는 다음 형식.

| 필드 | 타입 | 필수 | 검증 룰 | 비고 |
|---|---|---|---|---|
| `title` | string | yes | non-blank, length ≤ 200 | 원제 우선. 부제는 `(부제)` 형태. |
| `artist` | string | yes | non-blank, length ≤ 100 | 그룹은 단일 표기(예: "BTS"). |
| `releaseYear` | int | yes | 1970 ≤ year ≤ current year | |
| `keyOriginal` | enum (`MusicalKey`) | no | 기존 enum 값 중 하나 | 미상 시 null 허용. |
| `bpm` | int | no | 40 ≤ bpm ≤ 220 | 미상 시 null. |
| `mood` | enum (`Mood`) | yes | 기존 enum 값 1개 | 다중 분위기 미지원(현 모델). |
| `language` | string | yes | `ko`/`en`/`ja`/`zh` 중 하나 | 추가는 본 spec §9. |
| `genre` | string | yes | 균형 카테고리 5종(`발라드`/`댄스`/`락`/`트로트`/`팝`) 중 하나 | 자유 string 아닌 화이트리스트. |
| `tjNumber` | string | no | 숫자 또는 null | 가능 시. |
| `kyNumber` | string | no | 숫자 또는 null | 가능 시. |
| `isrc` | string | no | 12자 + 영숫자 패턴 또는 null | v0.2 단계 거의 null. |
| `metadataConfidence` | double | yes | 0.7 ≤ x ≤ 1.0 | 시드 곡은 0.7 미만 금지. |
| `lowMidi` | int | yes | 36 ≤ low ≤ 84 | C2~C6. |
| `highMidi` | int | yes | 48 ≤ high ≤ 96 + low < high | C3~C7, span ≤ 24 권장(범위 초과는 측정 오류 의심). |

> `metadataSource`는 JSON 필드에 두지 않는다 — `SongSeedLoader`가 `MANUAL_SEED`로 하드코딩한다. 외부 큐레이션 기여(v0.4 P3)에서도 동일 — 시드 루트는 항상 `MANUAL_SEED`.

### 5-6) DB 마이그레이션
- 없음. 기존 스키마로 충분.

### 5-7) 수기 vs 자동 분석 충돌 해소 규칙 (핵심)
`SongSeedLoader` (수기) 와 `audio-analysis` 도구 (자동) 가 같은 곡에 다른 `lowMidi`/`highMidi`를 쓰려 할 때:

| 우선순위 | 출처 | 규칙 |
|---|---|---|
| 1 | 운영자 수동 수정 (DB row 직접 편집) | 절대 덮지 않음. `backfillMissingFields` 의미상 null이 아닌 값은 보존. |
| 2 | `MANUAL_SEED` (본 spec, `songs-seed.json`) | 자동 분석을 덮을 수 있음. seed 적재가 자동 분석보다 시간상 항상 선행 (loader는 부팅 시, audio-analysis는 별 batch). |
| 3 | `AUDIO_ANALYSIS` (자동, confidence ≥ 0.6) | `MANUAL_SEED` 값이 있는 곡은 건드리지 않음. lowMidi/highMidi 가 null 인 곡만 채움(`backfillMissingFields` 동일 의미). |
| 4 | `INFERRED` | 위 셋 다 없을 때만. |

운영 구현 검증:
- `SongSeedLoader.run` — 기존 row면 `backfillMissingFields`로 null 만 채움. **이미 검증된 동작**. 본 spec은 이 의미를 spec 차원에서 못박는 것.
- audio-analysis tool — `audio-tooling-bootstrap.md` PR C 명세 따름. spec 충돌 시 audio-analysis 가 본 spec §5-7을 우선.
- 회귀 방지: `SongSeedLoaderIntegrationTest` 가 "수기 → 자동 → 다시 수기" 시나리오에서 수기 값이 살아남는지 확인하는 케이스를 추가 (PR A).

### 5-8) lowMidi/highMidi 측정 가이드 (운영자/AI 공통)
1. **대상**: 원곡 메인 보컬 멜로디 라인의 최저음~최고음. 코러스 화음·애드립·랩(피치 부정형) 제외.
2. **참조 소스 우선순위**:
   1. 원곡 음원(공식 음원/YouTube 공식 채널) — 1차.
   2. 가창자 본인 공지(음역 인터뷰/공식 키 정보) — 보강.
   3. 노래방 키 +/-(TJ/금영 표기) 역산 — 참고용. 노래방 기본 키가 원곡 키와 다른 경우 주의.
3. **MIDI 변환**:
   - A4 = MIDI 69 기준. `lowestNoteName`/`highestNoteName` 노트명 변환은 `song.domain.NoteName` 유틸과 동일 컨벤션(sharp).
   - 옥타브 헷갈리는 경우 (자주 발생) — 단위 테스트 sanity check(`highMidi - lowMidi ≤ 24`)가 1차 가드.
4. **키 변경/리메이크 처리**:
   - 시드는 **원곡 키** 기준. 리메이크/커버 별도 row 생성하지 않음(v0.2 한정).
   - 가창자 본인 라이브 키 변경은 무시 (음원 기준).
5. **자동 분석값 사용 시**:
   - `audio-analysis` 결과 confidence < 0.7 이면 수기 검증 필수.
   - 자동값을 그대로 채택해도 `metadataConfidence`는 운영자 청취 검증 시점에 갱신.
6. **불확실한 곡**:
   - 측정 자신 없으면 시드에 포함시키지 않는다(skip). 시드 100곡 목표보다 곡당 신뢰도 우선.
   - skip한 후보는 PR description에 사유 1줄(예: "랩 비중 높아 vocal pitch 추정 어려움").

### 5-9) 프론트엔드 화면
- 범위 외. 사용자 화면은 변경 없음(동일 API가 더 많은 곡을 반환).

## 6) 작업 분할 (예상 PR 리스트)

본 작업은 **콘텐츠 작업 + 코드 변경 혼합**이라 다음 사이클로 분할한다.

- [x] PR A0 (docs): 본 spec 초안 — 이전 PR #121.
- [ ] **PR A (test:song, 본 spec 후속)** — #276: 시드 JSON 스키마 검증 단위 테스트 — §5-5 룰 + §5-7 충돌 시나리오 회귀 테스트. **데이터 추가 없이** 형식 게이트만 잠금. 30곡 기존 데이터로 그린.
- [ ] **PR B (chore:song)** — #277: AI가 100곡 후보 리스트 + 음역 초기값 추정 (장르/시대 균형 default 준수). 산출물은 작업용 마크다운/CSV (PR이 아닌 리뷰용 첨부 자료). 사용자 검토 단계.
- [ ] **PR C (chore:song)** — #278: PR B 결과를 사용자 검토·조정 후 `backend/src/main/resources/songs-seed.json` 갱신. 적재 검증 E2E. **PR A 통과 후에만 진행** (스키마 게이트 통과 필수).
- [ ] **PR D (test:song, 선택)** — #279: 장르/시대 균형 통계 테스트 — default ±5 이내 자동 검증.
- [ ] **PR E (docs/feat, v0.4 P3 후보)**: 외부 큐레이션 컨트리뷰션 가이드 (§4-1). v0.4 진입 시점에 별 spec으로 분리 가능.

> 협업 패턴: **AI 1차 제안 → 사용자 검증** 사이클. AI가 단독으로 100곡을 확정해 머지하지 않는다.
> 사용자 협조 필요: PR B/C는 사용자가 곡 청취·음역 검증을 수행. maestro AI는 후보 제안/JSON 편집/PR 작성을 담당.

## 7) 테스트 전략
- **단위 (PR A)**: `songs-seed.json` 스키마 유효성 — §5-5 룰을 1:1 변환. 모든 곡이 필수 필드 보유, `lowMidi < highMidi`, MIDI 범위, `metadataConfidence` 범위, `genre`/`language` 화이트리스트, `bpm` 범위, `releaseYear` 범위.
- **통합 (PR C)**: `SongSeedLoader` 적재 후 `song` row 수 == 100, 동일 (title, artist) 키 중복 없음. 멱등성 — 동일 입력 2회 적재해도 row 수 불변.
- **회귀 (PR A)**: 충돌 해소 시나리오(§5-7) — 수기 입력 → 자동 분석 backfill → 다시 수기 입력 흐름에서 수기 값이 살아남는지.
- **E2E (RestAssured, PR C)**: 기존 `GET /api/v1/songs` 응답이 100건을 반환하는지 (또는 페이지네이션 정상 동작).
- **균형 통계 (PR D, 선택)**: 장르/시대 분포가 default와 ±5 이내인지 단위 테스트.
- **외부 호출 mock**: 본 spec은 외부 API 호출 없음. mock 불필요.

## 8) 오픈 질문
| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | 100곡 후보 1차 작성 주체 | (a) AI가 1차 100곡 → 사용자 검토, (b) 사용자가 직접 100곡 → AI는 음역 추정만 보조 | @goohong / PR B 직전 |
| Q2 | 음역 추정 신뢰도 임계값 | (a) 0.7 이상이면 시드 포함(default), (b) 0.8 이상만 포함(엄격) | @goohong / PR C 직전 |
| Q3 | 시대 균형(90s 20 / 00s 25 / 10s 30 / 20s 25)이 실 사용자 인구통계와 맞나 | 현재 가정 = 주 사용자 30~40대. 사용자 인구통계 수집 전까지 가정 유지. v0.3에서 재평가 | @goohong / v0.3 진입 시 |
| Q4 | self-analysis-pipeline §4-1 장르 분포(발라드30/댄스20/락15/트로트15/팝10/힙합10)와 본 spec 분포(발라드30/댄스20/락15/트로트15/팝20)가 다름. 통일 필요 | (a) 본 spec 분포로 통일 (팝 흡수), (b) self-analysis spec을 본 spec에 맞춤 | @goohong / PR B 직전 |
| Q5 | 외부 큐레이션 기여(§4-1) v0.4 진입 시점 | release 일정 미정 | v0.4 plan 사이클 |

## 9) 결정 로그
> 연대기 순. "YYYY-MM-DD: 결정 / 이유 / 출처(PR 번호 등)"

- 2026-05-21: 초안 작성 (status=draft). 사용자 자체 분석 pivot 반영, 외부 catalog 대량 ingest 폐기, 수기 큐레이션 100곡으로 확정. 장르 균형(발라드 30/댄스 20/락 15/트로트 15/팝 20) + 시대 균형(90s 20/00s 25/10s 30/20s 25) default 제시(plan 사이클 7). 출처: #121
- 2026-05-22: spec 갱신 — 이슈 #71 후속. (a) JSON 스키마(§5-5) 명문화 — 필수 필드/타입/범위 게이트, (b) 수기 vs 자동 분석 충돌 해소 규칙(§5-7) — `MANUAL_SEED` > `AUDIO_ANALYSIS` > `INFERRED` 우선순위 못박음, (c) lowMidi/highMidi 측정 가이드(§5-8) — 원곡 보컬 라인 기준·랩/애드립 제외·MIDI 변환·키변경 처리, (d) PR 분할 재구성 — PR A(스키마 검증 테스트 선행) → PR B(후보) → PR C(데이터 머지) → PR D(균형 통계, 선택) → PR E(v0.4 외부 컨트리뷰션), (e) 외부 큐레이션 기여 경로(§4-1) v0.4 P3 후보로 명시 — 본 spec이 그 길을 막지 않는 형식 채택. 충돌 발견: self-analysis-pipeline §4-1 장르 분포(힙합 포함)와 본 spec(팝 단일) 다름 → Q4로 분기. 출처: 본 PR
