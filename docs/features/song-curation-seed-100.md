---
feature: 노래방 시드 곡 큐레이션 100곡 확장 (song-curation-seed-100)
slug: song-curation-seed-100
status: draft
owner: "@goohong"
scope: song
related_issues: [71, 120]
related_prs: [121]
last_reviewed: 2026-05-21
---

# 노래방 시드 곡 큐레이션 100곡 확장 (song-curation-seed-100)

## 1) 개요 (What / Why)
- 현재 `backend/src/main/resources/songs-seed.json`은 30곡만 보유. 사용자가 vocal range/장르/분위기로 추천을 요청해도 매칭 후보가 너무 적어 PoC 사용성이 떨어진다.
- 사용자 자체 분석 pivot([[project-self-analysis-pivot]]) 이후, 방대한 외부 카탈로그(Spotify/MusicBrainz) ingest는 폐기하고 **수기 큐레이션 + 자체 분석**으로 데이터셋을 만든다.
- 본 spec은 시드 곡을 **30곡 → 한국 노래방 인기곡 100곡**으로 확장하는 큐레이션 작업의 기준/범위/검증 방법을 정의한다.
- 액터: 운영자(큐레이션 책임) + AI(후보 곡 + 음역 초기값 제안). 본 데이터셋은 동시에 `song-self-analysis-pipeline.md`의 분석 입력으로 사용된다.

## 2) 사용자 시나리오 (운영 + 사용자 효과)
운영 시나리오:

1. 운영자가 한국 노래방 인기곡 차트(TJ/금영/Melon)를 교차해 **후보 곡 100곡**을 정한다. AI가 1차 후보 리스트를 제안할 수 있다.
2. 각 곡에 대해 (a) 원곡 키, (b) lowMidi/highMidi, (c) 장르/mood, (d) BPM을 채운다.
   - **lowMidi/highMidi**는 (i) YouTube 원곡을 직접 듣고 추정 또는 (ii) `song-self-analysis-pipeline.md`의 자체 분석 결과를 1차값으로 사용 후 운영자가 검증.
3. `metadataSource = MANUAL`, `metadataConfidence` 표기 후 `songs-seed.json`에 추가.
4. `SongSeedLoader` 멱등 적재 → DB 반영.

사용자 효과:
- 사용자가 본인 음역대를 입력하면 **매칭 곡 수가 30 → 100으로 증가**해 추천 다양성이 향상된다.

## 3) 요구사항
### 기능 요구사항
- [ ] 한국 노래방 인기곡 100곡 큐레이션 (기존 30곡 포함, 70곡 신규 추가).
- [ ] 장르 균형 default — 발라드 30 / 댄스 20 / 락 15 / 트로트 15 / 팝 20 (= 100). 운영자 재량으로 ±5 조정 가능 (§9 결정 로그에 기록).
- [ ] 시대 균형 default — 90s 20 / 00s 25 / 10s 30 / 20s 25 (= 100). 운영자 재량으로 ±5 조정 가능.
- [ ] 각 곡 필드 — `title`, `artist`, `releaseYear`, `keyOriginal`, `bpm`, `mood`, `language`, `genre`, `tjNumber`(가능 시), `kyNumber`(가능 시), `lowMidi`, `highMidi`.
- [ ] `metadataSource = MANUAL`, `metadataConfidence` 0.7~0.9 범위 (수기 + 일부 차트/위키 참조).
- [ ] `songs-seed.json` 포맷 그대로 호환 — `SongSeedLoader`가 별도 마이그레이션 없이 적재 가능.
- [ ] 적재 멱등 — 동일 title/artist 재실행 시 중복 row 생성 금지.

### 비기능 요구사항
- 외부 데이터 출처 명시 — Melon/지니/TJ/금영 차트는 참고용. **가사/원본 audio 무단 복제 금지**.
- 음역대 추정 신뢰도 0.7 이상 곡만 시드에 포함. 0.7 미만은 자체 분석 보강 후 재시도.
- 큐레이션 작업 자체에 사용자의 개인 음역 데이터(=서비스 이용자 음역)는 사용하지 않는다(CLAUDE.md §4 보안).
- 적재 idempotency — `SongSeedLoader` 기존 동작(존재 시 skip 또는 update) 유지.

## 4) 범위 / 비범위 (중요)
### 포함
- 100곡 후보 리스트 작성 (큐레이션 + 음역 초기값 추정).
- `backend/src/main/resources/songs-seed.json` 갱신.
- 적재 검증 — 단위(필드 유효성) + E2E(RestAssured로 100곡 모두 로드되는지).
- 장르/시대 균형 default 합의 (변경 시 §9에 결정 로그).

### 제외 (Out of Scope)
- **자체 분석 자동 도구 자체** — `song-self-analysis-pipeline.md` 별 spec. 본 spec은 그 출력의 _소비자_.
- **ML 기반 자동 음역 추정** — v0.3+로 이연.
- **노래방 곡번호 자동 매핑** — ADR #70 (v0.2 보류). tjNumber/kyNumber는 _가능 시_만 채움.
- **외부 음원 API 대량 ingest** — pivot 이후 폐기됨.
- **사용자 업로드 audio 분석** — 별 의사결정.

## 5) 설계

### 5-1) 도메인 모델
- 기존 `Song` 엔티티 사용. 새 필드/마이그레이션 없음.
- `metadataSource = MANUAL` 사용 (기존 enum 값).
- `metadataConfidence`가 컬럼에 없다면 본 spec과 동시 갱신 대상은 아님 — `song-self-analysis-pipeline.md` PR C에서 도입 예정. 도입 전까지는 JSON 필드만 두고 loader는 무시할 수 있음.

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
[SongSeedLoader (멱등)]
        ↓
[DB: song 테이블 100건]
```

### 5-5) DB 마이그레이션
- 없음. 기존 스키마로 충분.

### 5-6) 프론트엔드 화면
- 범위 외. 사용자 화면은 변경 없음(동일 API가 더 많은 곡을 반환).

## 6) 작업 분할 (예상 PR 리스트)

본 작업은 **콘텐츠 작업 + 코드 변경 혼합**이라 다음 사이클로 분할한다.

- [ ] PR A (docs): 본 spec 초안 — 본 PR #121.
- [ ] PR B (chore:song): AI가 100곡 후보 리스트 + 음역 초기값 추정 (장르/시대 균형 default 준수). 산출물은 작업용 마크다운/CSV.
- [ ] PR C (chore:song): 운영자 검증 + 조정 — PR B 결과를 사용자가 검토하며 음역/장르 보정.
- [ ] PR D (chore:song): `backend/src/main/resources/songs-seed.json` 갱신 + `SongSeedLoader` 멱등 적재 검증.
- [ ] PR E (test:song): 적재 E2E + 장르/시대 균형 통계 테스트 (선택).

> 협업 패턴: **AI 1차 제안 → 사용자 검증** 사이클. AI가 단독으로 100곡을 확정해 머지하지 않는다.

## 7) 테스트 전략
- **단위**: `songs-seed.json` 스키마 유효성 — 모든 곡이 필수 필드 보유, `lowMidi < highMidi`, MIDI 범위(예: 40~84) 내, `metadataSource == 'MANUAL'`.
- **통합**: `SongSeedLoader` 적재 후 `song` row 수 == 100, 동일 (title, artist) 키 중복 없음.
- **E2E (RestAssured)**: 기존 `GET /api/v1/songs` 응답이 100건을 반환하는지 (또는 페이지네이션 정상 동작).
- **균형 통계** (선택): 장르/시대 분포가 default와 ±5 이내인지 단위 테스트.
- **외부 호출 mock**: 본 spec은 외부 API 호출 없음. mock 불필요.

## 8) 오픈 질문
| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | 100곡 후보 1차 작성 주체 | (a) AI가 1차 100곡 → 사용자 검토, (b) 사용자가 직접 100곡 → AI는 음역 추정만 보조 | @goohong / PR B 직전 |
| Q2 | 음역 추정 신뢰도 임계값 | (a) 0.7 이상이면 시드 포함, (b) 0.8 이상만 포함(엄격), (c) 0.6 이상 + low-confidence flag | @goohong / PR C 직전 |
| Q3 | 시대 균형(90s 20 / 00s 25 / 10s 30 / 20s 25)이 실 사용자 인구통계와 맞나 | 현재 가정 = 주 사용자 30~40대. 사용자 인구통계 수집 전까지는 가정 유지. v0.3에서 재평가 | @goohong / v0.3 진입 시 |

## 9) 결정 로그
> 연대기 순. "YYYY-MM-DD: 결정 / 이유 / 출처(PR 번호 등)"

- 2026-05-21: 초안 작성 (status=draft). 사용자 자체 분석 pivot 반영, 외부 catalog 대량 ingest 폐기, 수기 큐레이션 100곡으로 확정. 장르 균형(발라드 30/댄스 20/락 15/트로트 15/팝 20) + 시대 균형(90s 20/00s 25/10s 30/20s 25) default 제시(plan 사이클 7). 출처: #121
