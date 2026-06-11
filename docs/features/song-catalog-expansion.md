---
feature: 곡 카탈로그 확장 방안 (song-catalog-expansion)
slug: song-catalog-expansion
status: draft
owner: "@goohong"
scope: song
related_issues: [1496, 1490, 71, 68]
related_prs: []
last_reviewed: 2026-06-03
---

# 곡 카탈로그 확장 방안 (song-catalog-expansion)

## 1) 개요 (What / Why)

- 사용자 directive (2026-06-02, #1496): 현 보유 곡 수가 **턱없이 부족** (시드 30곡) — 크롤링/임포트로 더 확보하고 싶다.
- 본 spec 은 (1) 합법적 곡 확보 출처 조사, (2) 임포트 스캐폴드 설계, (3) **자체 분석 pivot** ([[project-self-analysis-pivot]], 2026-05-21 "방대한 외부 catalog 폐기 → 큐레이션 + 자체 분석") 과의 긴장을 재검토해 **결정 + 단계화** 한다.
- 본 spec 은 새 도메인/외부 연동/다중 PR 을 동반하므로 Feature Spec 선작성 대상 (CLAUDE.md §4).
- 대상 액터: 시스템(임포트 배치) + 운영자(큐레이션·검수). 사용자 직접 화면 없음 — 더 많은 곡이 기존 추천/검색/카탈로그 경로로 노출되는 간접 효과만.
- **본 spec 은 출처·합법성·임포트 파이프라인의 single SoT**. 구체 큐레이션 작업은 [[song-curation-seed-100]], 음역대 추출은 [[song-self-analysis-pipeline]], 메타 보강은 [[musicbrainz-integration]] 가 책임 — 본 spec 은 그 세 spec 을 **하나의 확장 전략으로 조율**한다.

## 2) 사용자 시나리오 (시스템 / 운영)

사용자 직접 화면 없음. 운영·시스템 시나리오:

1. **후보 풀 수급** — 운영자가 한국 노래방 인기곡 목록(차트 수기 참고)을 (title, artist) 쌍으로 정리한다. AI 가 1차 후보를 제안할 수 있다.
2. **메타-only 임포트** — 배치가 각 (title, artist) 를 MusicBrainz(CC0) 에 질의해 `mbId`/`isrc`/`releaseYear`/장르 태그 등 **메타데이터만** 채워 `Song` 으로 upsert (음역대/key/tempo 는 채우지 않음).
3. **음역대 자체 분석** — 임포트된 곡은 음역대가 비어 있으므로 [[song-self-analysis-pipeline]] (#1490) 의 selective backfill 큐에 자동 진입 → 자체 분석으로 `lowMidi`/`highMidi` 추정.
4. **운영자 검수** — 자동 추정 confidence 가 낮은 곡은 운영자 검토 큐로. 시드 1st-class 큐레이션 곡과 동일한 검증 가이드([[song-curation-seed-100]] §5-8) 적용.
5. **단계적 규모 확대** — 30 → 100(수기 큐레이션) → 수백(메타-only 임포트 + 자체 분석) 으로 단계 상향. 각 단계 진입 전 §6 게이트 충족 확인.

## 3) 요구사항

### 기능 요구사항

- [ ] 합법 출처별 라이선스/약관/robots.txt 조사 결과를 §5-3 에 박제한다 (채택/비채택 근거 포함).
- [ ] 메타-only 임포트 스캐폴드 설계 — (title, artist) 입력 → MusicBrainz 질의 → `Song` upsert (메타 필드만). 음역대/key/tempo 미설정.
- [ ] 임포트된 곡은 `metadataSource = EXTERNAL_API` + 낮은 `metadataConfidence` 표기 → [[song-self-analysis-pipeline]] selective backfill 진입.
- [ ] 임포트 멱등성 — 동일 (title, artist) 또는 `mbId`/`isrc` 재실행 시 중복 row 생성 금지. 운영자 수정값 보존(`backfillMissingFields` 의미).
- [ ] 단계화 — §6 의 stage 1/2/3 게이트와 PR 분할 정의.
- [ ] pivot 과의 긴장 재검토 결론을 §9 결정 로그 + §5-0 에 명문화.

### 비기능 요구사항

- **합법성 우선** — robots.txt / 약관 위반 출처는 자동 수집 대상에서 제외. 차트 사이트(Melon/지니/TJ/금영)는 **수기 참고용** 만, 자동 크롤링 금지.
- **라이선스 안전** — 영구 저장하는 메타는 CC0(MusicBrainz/Wikidata) 출처만. ToS 캐싱 제약이 있는 출처(Spotify/Apple)는 식별자/단기 캐시만.
- **가사/원본 audio 무단 복제 금지** — 메타데이터(제목/아티스트/연도/장르/식별자)만 영속. 가사·원본 음원은 저장하지 않는다 (CLAUDE.md §4 보안 + [[song-self-analysis-pipeline]] §3 임시 캐시 룰).
- **외부 호출 rate limit 준수** — MusicBrainz anonymous 1 req/s + 의무 User-Agent (`musicbrainz-integration.md` §5-3 와 동일 정책).
- **음역대 정확도 비타협** — 외부 API 는 곡 vocal range 를 제공하지 못한다. 음역대는 항상 자체 분석 또는 수기 큐레이션이 권위 (§5-0).
- **관측성** — 임포트 단계별 성공/실패/스킵 카운트 로깅. 곡 메타 외 PII 없음.

## 4) 범위 / 비범위 (중요)

### 포함

- 곡 확보 출처 조사 + 합법성 판정 (§5-3).
- 메타-only 임포트 스캐폴드 설계 (도메인/배치/멱등성/실패 처리).
- pivot 긴장 재검토 결론 + 확장 단계화 (§5-0, §6).
- 본 spec 이 조율하는 3 spec 과의 경계/cross-ref.

### 제외 (Out of Scope)

- **자동 크롤링 (TJ/금영/Melon 사이트 스크래핑)** — 약관·저작권 리스크. **명시적으로 채택하지 않는다** (§5-3 결론).
- **방대한(수만+) 외부 catalog 일괄 ingest** — pivot 폐기 대상. 본 spec 의 규모 목표는 "수백" (§5-0).
- **음역대/key/tempo 의 외부 API 수급** — 제공 불가 + pivot 폐기. 자체 분석([[song-self-analysis-pipeline]]) 책임.
- **Spotify/Apple Music 대량 ingest** — ToS 캐싱 제약 + pivot 보류. mood 신호(valence/energy) 한정 활용은 [[spotify-audio-features-integration]] 별 결정.
- **수기 큐레이션 100곡 작업 자체** — [[song-curation-seed-100]] 책임. 본 spec 은 그 위에 임포트 단계를 얹는다.
- **MusicBrainz backfill 구현 세부** — [[musicbrainz-integration]] 책임. 본 spec 은 그것을 "후보 풀 확장" 맥락으로 재사용.
- **사용자 업로드/기여 곡** — v0.4 외부 큐레이션 기여([[song-curation-seed-100]] §4-1) 후보.
- **관리자 곡 등록 UI** — 시드/임포트 JSON 직접 편집 유지.

## 5) 설계

### 5-0) pivot 긴장 재검토 — 결론 (핵심)

**긴장 명시**:
- pivot ([[project-self-analysis-pivot]], 2026-05-21): "꼭 이미 있는 메타데이터를 가져와서 방대할 필요는 없어. 적절한 곡 선별 + 자체 DB 에 분류해서 주기적으로 직접 곡분석". → 방대 외부 catalog **폐기**.
- 신호 (#1496, 2026-06-02): 현 30곡은 **턱없이 부족** → 곡 수 확대.

**재검토 결론 — 두 신호는 충돌이 아니라 다른 축이다**:
- pivot 이 폐기한 것은 ① **방대한(수만+) 저품질 일괄 ingest** 와 ② **외부 API 에 음역대(추천 핵심 신호)를 의존**하는 것. 둘 다 노래방 추천의 본질(vocal range 정밀도)을 평준화·훼손한다.
- #1496 이 요구하는 것은 **추천이 작동할 만큼의 후보 다양성(수백 곡)**. 30곡은 음역대·장르·시대 조합에서 매칭 후보가 너무 적다.
- 따라서 **(a) 큐레이션 권위 유지 + (b) 자체 분석 결합** 을 **단계화한 하이브리드** 로 채택한다. 둘 중 택일이 아니다.

**채택안 (a)+(b) 하이브리드**:
1. **권위 유지** — 음역대/품질의 1st-class 권위는 항상 수기 큐레이션([[song-curation-seed-100]]) + 자체 분석([[song-self-analysis-pipeline]]). 충돌 해소 규칙 [[song-curation-seed-100]] §5-7 (`MANUAL_SEED` > `AUDIO_ANALYSIS` > `INFERRED`) 그대로.
2. **메타-only 임포트로 후보 풀만 확장** — 외부 출처(MusicBrainz CC0)는 **제목/아티스트/연도/장르/식별자 메타만** 채운다. 음역대는 절대 외부에서 가져오지 않는다 (제공도 안 되고, pivot 위반).
3. **임포트 곡 = 자체 분석 입력** — 임포트로 들어온 곡은 음역대가 비어 있으므로 #1490 자체 분석 큐에 자동 진입. 즉 임포트는 "자체 분석할 곡 후보를 합법적으로 늘리는" 역할.

**규모 목표 (pivot 의 "방대함보다 품질" 존중)**: 수만+ 아님. **단계 상향**:
- Stage 1: 30 → 100 (수기 큐레이션, [[song-curation-seed-100]]).
- Stage 2: 100 → ~300 (메타-only 임포트 + 자체 분석 backfill).
- Stage 3 (조건부): ~300 → 수백 후반. §6 게이트(자체 분석 정확도 회귀 가드 통과) 충족 시에만.

### 5-1) 도메인 모델

- 기존 `Song` 엔티티 재사용 — **신규 엔티티/마이그레이션 없음** (메타 필드는 이미 존재).
- `metadataSource = EXTERNAL_API` (기존 `MetadataSource` enum 값, 도메인 모델 §4 등재됨) — 임포트 곡 표기. 자체 분석 완료 시 `AUDIO_ANALYSIS` 로 전이(충돌 해소 규칙 따름).
- `mbId`/`isrc` — [[musicbrainz-integration]] 가 도입하는 식별자 컬럼 재사용.
- **신규 도메인 용어 후보** (구현 PR 에서 `06-domain-model.md §4` 등재 의무):

| 한국어 | 영어 (코드) | 정의 |
|---|---|---|
| 곡 후보 풀 | SongCandidatePool | 추천/카탈로그가 매칭 대상으로 삼는 곡 집합. 큐레이션 곡 + 임포트 곡 합집합. 규모 확장의 단위. |
| 메타-only 임포트 | MetadataOnlyImport | 외부 CC0 출처에서 메타데이터(제목/아티스트/연도/장르/식별자)만 가져와 `Song` 으로 upsert 하는 배치. 음역대/key/tempo 미설정 — 자체 분석이 후속. |

> 새 영속 엔티티는 만들지 않는다. 위 용어는 개념 라벨 — `Song` + 기존 enum 으로 표현된다.

### 5-2) API 엔드포인트

- 본 spec 단계에서 신규 사용자 endpoint 없음. 임포트는 배치/운영자 트리거.
- 운영 통계는 기존 `GET /api/v1/songs/stats` ([[song-metadata-source]] §5-2-1) 가 `metadataSource` 분포로 임포트 진척을 노출 — 본 spec 은 그 endpoint 의 소비자.

### 5-3) 외부 연동 — 출처 조사 + 합법성 판정 (핵심)

| 출처 | 데이터 | 라이선스 / 약관 | robots.txt / rate limit | 판정 |
|---|---|---|---|---|
| **MusicBrainz** | mbid, ISRC, 제목/아티스트, 발매연도, 장르 태그 | **CC0** (퍼블릭 도메인) — 영구 저장·재배포 OK, attribution 친화 | anonymous 1 req/s, 의무 User-Agent (앱명+연락처) | ✅ **채택 (1차 메타 출처)** — [[musicbrainz-integration]] 가 구현 |
| **Wikidata** | 곡/아티스트 식별자 교차, 발매정보 | **CC0** | SPARQL endpoint, fair use rate | ✅ 보조 채택 (식별자 교차 보강, 선택) |
| **Spotify Web API** | audio-features(valence/energy/tempo/key) | ToS: **데이터 영구 캐싱 제약**, OAuth 필요 | API rate limit | ⚠️ 조건부 — mood(valence/energy) 한정, [[spotify-audio-features-integration]] 별 결정. 메타 영구 저장 X |
| **Apple Music API** | 한국 곡 커버리지 양호 | 약관 까다로움, 개발자 등록 비용 | — | ⛔ 비채택 (비용 + 약관, pivot 보류) |
| **Melon / 지니 / Bugs 차트** | 인기곡 순위(=큐레이션 참고) | 저작권 콘텐츠, ToS 상 스크래핑 제한 | robots.txt 통상 크롤 disallow | ⛔ 자동 크롤링 비채택 — **수기 참고만** |
| **TJ / 금영 사이트** | 노래방 곡번호 | 약관·저작권 리스크, 차단/구조 변경 취약 | robots.txt 제약 | ⛔ 자동 크롤링 비채택 — 곡번호는 _가능 시_ 수기 ([[song-curation-seed-100]] §4) |
| **사용자 기여(UGC)** | 장기 풍부 | 신뢰도 검증 필요 | — | ⏸ v0.4 후보 ([[song-curation-seed-100]] §4-1) |

**핵심 판정**:
- 자동 수집은 **CC0 출처(MusicBrainz 1차, Wikidata 보조)만**. 라이선스·약관·robots.txt 가 모두 깨끗.
- 차트/노래방 사이트는 **수기 참고만** — 자동 스크래핑은 약관/robots.txt 위반 리스크로 명시적 비채택. 인기곡 선정은 운영자가 차트를 _읽고_ (title, artist) 를 손으로 정리하는 합법 경로.
- 음역대는 어떤 외부 출처도 제공하지 않음 → 자체 분석 권위 (§5-0).

### 5-4) 데이터 흐름 / 시퀀스

```
[운영자: 차트 수기 참고 → (title, artist) 후보 목록]
        ↓ (AI 1차 후보 제안 가능)
[MetadataOnlyImport 배치]
        ↓ MusicBrainz /ws/2/recording?query=  (1 req/s, User-Agent)
[mbId / isrc / releaseYear / 장르 태그]
        ↓ Song upsert (metadataSource=EXTERNAL_API, 음역대 NULL, confidence 낮음)
[song 테이블 — 후보 풀 확장]
        ↓ (음역대 NULL → selective backfill 큐 진입)
[song-self-analysis-pipeline (#1490)]
        ↓ 자체 분석 → lowMidi/highMidi/key (metadataSource=AUDIO_ANALYSIS)
        ↓ confidence 낮으면 운영자 검토 큐
[추천/검색/카탈로그가 더 많은 후보로 동작]
```

- 임포트는 자체 분석보다 **시간상 선행** — 임포트가 후보를 넣고, 자체 분석 cron 이 음역대를 채운다.
- 수기 큐레이션 곡(`MANUAL_SEED`)은 항상 임포트/자체 분석을 우선 ([[song-curation-seed-100]] §5-7).

### 5-5) DB 마이그레이션

- **본 spec 자체는 신규 마이그레이션 없음**. 메타 필드(`releaseYear`/`genre`/`mbId`/`isrc`/`metadataSource`/`metadataConfidence`)는 이미 존재하거나 [[musicbrainz-integration]] (`mbId`) / [[song-metadata-source]] (`isrc`) 가 도입.
- 단, [[song-catalog-genre-browse]] 의 `Genre` enum promote 가 머지되면, 임포트의 장르 태그 → `Genre` enum 매핑은 그 spec 의 `Genre.fromLegacyString(...)` 규칙을 따른다 (외부 태그 미매핑 → `OTHER`).

### 5-6) 프론트엔드 화면

- 범위 외. 동일 API 가 더 많은 곡을 반환할 뿐.

## 6) 작업 분할 (예상 PR 리스트)

> 본 spec 은 조율 spec — 실제 구현 상당 부분은 기존 3 spec 으로 위임. 본 spec 고유 산출은 PR 1(본 spec) + PR 2(임포트 스캐폴드 spec→구현 가교).

- [ ] **PR 1 (docs)** — 본 spec 초안 (현재). pivot 긴장 결론 + 출처 조사 + 단계화 박제.
- [ ] **PR 2 (docs/feat:song)** — `MetadataOnlyImport` 배치 스캐폴드: (title, artist) 입력 → MusicBrainz 질의 → `Song` upsert(메타만) + 멱등성 + 실패/skip 처리 + `06-domain-model.md §4` 신규 용어 등재. **[[musicbrainz-integration]] 구현에 의존** — 그 spec 의 backfill job 을 "신규 곡 import" 모드로 확장하는 형태 권고 (중복 구현 회피).
- [ ] **PR 3 (chore:song)** — Stage 2 후보 목록 작성(AI 1차 제안 → 운영자 검수) + 임포트 실행 → ~300곡. [[song-self-analysis-pipeline]] backfill 가동 확인.
- [ ] **PR 4 (test:song)** — 임포트 멱등성 + `metadataSource` 전이(EXTERNAL_API → AUDIO_ANALYSIS) 회귀 테스트 + 후보 풀 규모 통계 테스트.

**단계 게이트** (각 stage 진입 전 충족 필수):

| Stage | 규모 | 진입 게이트 |
|---|---|---|
| 1 | 30 → 100 | [[song-curation-seed-100]] 스키마 검증 테스트(PR A) 통과 |
| 2 | 100 → ~300 | PR 2 임포트 스캐폴드 머지 + [[song-self-analysis-pipeline]] §10-4 ground truth 회귀 가드 green |
| 3 (조건부) | ~300 → 수백 후반 | 자체 분석 MAE ≤ 2 semitone 유지(§10-4) + 운영자 검수 적체 < 50곡 |

### 보호 영역 변경 여부 (필수 명시)

- 보호 영역 변경 여부: ☐ **없음 (본 spec PR 1)** / ☑ **있음 (후속 PR)** —
  - **PR 2** — 외부 호출 배치 추가. `**/application*.yml`(MusicBrainz User-Agent / rate limit / import enable 플래그) 변경 가능 (정보성 보호 영역). 신규 DB 마이그레이션은 없음(기존 컬럼 재사용) — 단, [[song-catalog-genre-browse]] `Genre` enum 머지 순서에 따라 매핑 의존.
  - 사유: 메타-only 임포트는 read-mostly 외부 연동. 음역대 미설정이라 추천 결정성 회귀 없음.
- rev 가중도: 외부 API 호출 약관/rate limit 준수 + 멱등성이 핵심 검증 포인트.

## 7) 테스트 전략

- **단위**: (title, artist) → MusicBrainz 질의 파라미터 빌드, 응답 top-hit → `Song` 메타 매핑, 장르 태그 → `Genre` 매핑(미매핑 → OTHER).
- **통합 (testcontainers + WireMock)**: 임포트 멱등성 — 동일 (title, artist)/`mbId`/`isrc` 재실행 시 row 수 불변, 운영자 수정값 보존. MusicBrainz 응답은 WireMock fixture (실 토큰/호출 CI 제외).
- **회귀**: `metadataSource` 전이 — 임포트(EXTERNAL_API) 곡이 자체 분석 후 AUDIO_ANALYSIS 로 전이하되 `MANUAL_SEED` 곡은 절대 덮이지 않음([[song-curation-seed-100]] §5-7).
- **E2E (RestAssured)**: 임포트 후 `GET /api/v1/songs/stats` 의 `byMetadataSource` 분포에 EXTERNAL_API 증가 반영.
- **외부 호출 mock**: MusicBrainz = WireMock JSON fixture. rate limit(1 req/s) 준수는 호출 간격 단위 테스트.

## 8) 오픈 질문

> 구현 전에 답이 나와야 하는 것들. 해소되면 §9 결정 로그로 이동.

| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | 메타-only 임포트를 [[musicbrainz-integration]] backfill job 에 "신규 곡 import" 모드로 합칠지, 별 배치로 둘지 | (a) 기존 job 확장(중복 구현 회피, 권고) / (b) 별 `MetadataImportJob` (관심사 분리) | @goohong / PR 2 직전 |
| Q2 | Stage 2 규모 ~300 의 절대값이 적절한가 | (a) ~300 (자체 분석 cron 처리량/검수 부담 합리적) / (b) 200 (보수적) / (c) 자체 분석 backlog 처리량에 연동(상대값) | @goohong / Stage 2 진입 시 |
| Q3 | Wikidata 보조 출처를 1차 도입할지 v0.4 로 미룰지 | (a) MusicBrainz 단독으로 시작(단순) / (b) Wikidata 식별자 교차 동시 도입 | @goohong / PR 2 직전 |
| Q4 | 임포트 곡 초기 `metadataConfidence` 값 | (a) 0.3 (메타만, 음역대 미상 → 낮게) / (b) NULL (자체 분석 전까지 미정) | @goohong / PR 2 직전 |
| Q5 | 차트 수기 참고 시 인기 순위를 `popularity` 신호로 저장할지 | [[song-catalog-genre-browse]] §5-2 `sort=popularity` 와 연동 — v0.3 like/bookmark 누적 전까지 보류 | @goohong / v0.3 |

## 9) 결정 로그

> 연대기 순. "YYYY-MM-DD: 결정 / 이유 / 출처(PR 번호 등)"

- 2026-06-03: 초안 작성 (status=draft). 출처: #1496
  - **pivot 긴장 재검토 결론 (§5-0)**: pivot 이 폐기한 것은 "방대 저품질 ingest + 외부 API 음역대 의존" 이고, #1496 은 "추천이 작동할 후보 다양성(수백 곡)" 요구 — **다른 축이라 충돌 아님**. (a) 큐레이션/자체분석 권위 유지 + (b) 메타-only 합법 임포트로 후보 풀만 확장하는 **하이브리드 단계화** 채택.
  - **출처 합법성 판정 (§5-3)**: 자동 수집 = CC0(MusicBrainz 1차, Wikidata 보조)만. 차트/노래방 사이트 자동 크롤링은 약관·robots.txt 리스크로 **명시적 비채택** — 수기 참고만. 음역대는 외부 미제공 → 자체 분석 권위.
  - **규모 목표**: 수만+ 아닌 "수백". Stage 1(100, 큐레이션) → 2(~300, 임포트+자체분석) → 3(조건부) 단계화 + 게이트(§6).
  - **중복 구현 회피**: 임포트 스캐폴드는 [[musicbrainz-integration]] backfill 을 "신규 곡 import" 모드로 확장 권고 (Q1). 본 spec 은 3 spec(큐레이션/자체분석/MusicBrainz)을 하나의 확장 전략으로 조율하는 SoT.

## 10) 관련 spec / ADR

- [[project-self-analysis-pivot]] (메모리) — v0.2 메타 전략 pivot. 본 spec §5-0 이 그 긴장을 재검토·정합.
- [[song-curation-seed-100]] — 수기 큐레이션 100곡 (Stage 1). 권위·충돌 해소 규칙 §5-7 SoT.
- [[song-self-analysis-pipeline]] — 자체 분석으로 음역대 추출 (#1490). 임포트 곡의 음역대 backfill.
- [[musicbrainz-integration]] — MusicBrainz CC0 메타 backfill. 임포트 스캐폴드의 구현 기반.
- [[song-metadata-source]] — `Song` 엔티티 SoT + `metadataSource`/`isrc`/stats endpoint.
- [[song-catalog-genre-browse]] — `Genre` enum promote. 임포트 장르 태그 매핑 의존.
- [[spotify-audio-features-integration]] — mood(valence/energy) 한정 활용 (메타 영구 저장 X).
