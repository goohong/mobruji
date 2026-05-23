---
feature: 곡 메타데이터 출처 (song-metadata-source)
slug: song-metadata-source
status: shipped
owner: "@goohong"
scope: song
related_issues: [3, 17]
related_prs: [4, 18]
last_reviewed: 2026-05-23
---

# 곡 메타데이터 출처 (song-metadata-source)

## 1) 개요 (What / Why)
- 추천이 작동하려면 **추천 대상 곡 풀(pool)** 과 각 곡의 메타데이터(키, 음역, 장르, BPM, 분위기, 노래방 곡번호 등)가 필요하다.
- 본 spec은 그 데이터를 **어디서 어떻게 확보하고 갱신할지**를 정한다. `docs/ai-harness/06-domain-model.md §7 D2`를 다룬다.
- 대상 액터: 시스템(데이터 시드/배치)과 — 향후 — 관리자(큐레이션).
- 핵심 가정: 한국 노래방 사용자가 1차 타깃이므로 **노래방 곡번호(TJ/금영)** 가 곡 식별의 중요한 키 중 하나.

## 2) 사용자 시나리오
직접 사용자가 보는 화면은 없다. 다음은 시스템·운영 시나리오다.

1. **초기 시드 적재** — 첫 추천 서비스 가동 전, 50~수백 곡의 메타데이터를 DB에 적재한다.
2. **신곡 추가** — 신곡이 출시되거나 누락 곡이 발견되면 카탈로그에 추가한다.
3. **메타데이터 보정** — 키/음역/분위기 라벨이 잘못된 경우 정정한다.
4. **노래방 곡번호 매핑** — TJ/금영 곡번호를 곡과 연결한다. (사용자가 노래방 책자/태블릿에서 직접 곡번호로 검색하는 UX 연결고리)

## 3) 요구사항
### 기능 요구사항
- [x] 곡 메타데이터를 `Song` 엔티티 형태로 영속화한다.
- [x] 곡 식별자(내부 ID) + 외부 식별자(예: ISRC, Spotify ID, TJ 번호, 금영 번호)를 동시에 보관한다.
- [x] 곡 음역(`SongRange`)을 곡당 1쌍(최저, 최고 MIDI note)으로 보관한다.
- [x] 메타데이터 출처(`source`) 및 신뢰도 표기를 곡 레코드에 남긴다 (`MANUAL`, `EXTERNAL_API`, `INFERRED` 등).
- [ ] PoC 단계에선 신곡 추가/보정이 **DB 직접 또는 시드 SQL/JSON** 으로 가능하면 충분 (관리자 UI는 비범위).
- [x] (PR #96, closes #77) 곡 음역(`lowMidi`/`highMidi`)으로부터 가창 난이도(EASY/NORMAL/HARD)를 자동 분류하여 영속하고 응답으로 노출. 분류 룰은 fe `web/lib/difficulty.ts`와 1:1 일치 (HARD: high≥76 또는 span≥17, NORMAL: 71~75, EASY: <71).
- [x] (PR #96) 응답에 `lowestNoteName`/`highestNoteName` 음표명 표기 노출 (예: "C4", "E5"). fe `web/lib/notes.ts`와 동일 컨벤션 (sharp 표기).

### 비기능 요구사항
- 외부 API 호출 시 키/토큰은 환경변수로만 (`docs/ai-harness/04-security-policy.md`).
- 외부 출처 사용 시 라이선스/약관 위반 없음.
- 곡 데이터 정합성: `lowest_note <= highest_note`, 키는 표준 표기(C, C#, ...).
- 크롤링 시 robots.txt 및 약관 우선 검토 (해당 안 하기로 결정되면 제외).

## 4) 범위 / 비범위 (중요)
### 포함
- 곡 메타데이터 출처 채택 의사결정 (Q1).
- 1차 PoC 시드 데이터 수급 방식 + 규모 결정 (Q4).
- 노래방 곡번호 입수 경로 (Q2).
- 곡 음역(`SongRange`) 산정 방식 (Q3).
- `Song` 엔티티 필드 1차 컷 제안.

### 제외 (Out of Scope)
- **추천 알고리즘** — 메타데이터를 어떻게 매칭에 쓸지는 `recommendation-algorithm-v1.md`(미작성, D3).
- **관리자 UI / 큐레이션 워크플로우** — 1차 PoC에선 DB/시드 파일 직접 편집. UI는 별도 spec.
- **음원 스트리밍/미리듣기 통합** — 추천 결과 화면에 30초 미리듣기를 붙일지 등은 web 측 별도 결정.
- **AGPL 라이선스와의 호환성 심층 검토** — 외부 데이터셋(예: MusicBrainz CC0, Wikidata CC0)의 라이선스 호환은 채택 시점에 별도 검토.

## 5) 설계

### 5-1) 도메인 모델
- 신규: **`Song`** (Entity) — 카탈로그의 1행.
  - 필드(잠정): `id`, `title`, `artist`, `releaseYear`, `keyOriginal`(곡 원곡 키), `lowMidi`, `highMidi`(MIDI), `bpm`, `mood`(enum, 다중), `language`, `genre`, `tjNumber`, `kyNumber`, `spotifyId`, `isrc`, `metadataSource`, `metadataConfidence`, `difficulty`, `createdAt`, `updatedAt`.
  - PR #96에서 `lowMidi`/`highMidi`/`difficulty`(enum EASY/NORMAL/HARD) 추가. `difficulty`는 `lowMidi`/`highMidi`로부터 `Song.deriveDifficulty(...)`가 자동 분류 (fe `web/lib/difficulty.ts`와 1:1 룰).
  - **PR #204(closes #44, #203)에서 `isrc`(VARCHAR(12), nullable, UNIQUE) + `metadataConfidence`(DOUBLE, 0~1, default 1.0) 본진 컬럼으로 promote** — V3 마이그레이션. `metadataConfidence`는 `MANUAL_SEED`=1.0, `AUDIO_ANALYSIS` backfill 시 `result.confidence` 저장. 추천 알고리즘 입력 무관(결정성 회귀 없음). `spotifyId`는 외부 연동 spec까지 잠정 유지.
- **`SongRange`** (별 VO) — PR #96 시점에 도입하지 않음. `Song` 엔티티의 `lowMidi`/`highMidi` 두 필드로 단순 표현. `VoiceRange`와 동일 MIDI 표현 규약을 공유해 매칭 비용 절감 목표는 유지.
- 도메인 모델 §4 유비쿼터스 랭귀지에 이미 등재된 용어: `Song`, `SongRange`, `Key`, `Mood`, `Difficulty`(PR #96), `NoteName`(PR #96). 추가 후보: `MetadataSource`, `KaraokeNumber` (TJ/금영의 추상화).

### 5-2) API 엔드포인트
PoC 단계에선 **읽기만 노출**. 등록/수정은 시드 파일 또는 admin 도구로.

| Method | Path | 설명 | 인증 | Req | Res |
|---|---|---|---|---|---|
| GET | /api/v1/songs/{id} | 곡 상세 조회 | 익명 가능 | - | `SongResponse` |
| GET | /api/v1/songs?keyword=... | 키워드(제목/아티스트) 검색 | 익명 가능 | query | `List<SongResponse>` |
| GET | /api/v1/songs/stats | PoC 한정 admin 통계 (총 곡 수 / `metadataSource` 분포 / 평균 `metadataConfidence` / 마지막 backfill 시각) | Admin (`X-Admin-Token` 헤더 필수, `AdminTokenVerifier`) | header | `SongStatsResponse` |

> 추천 결과에서 호출되는 read API만 1차로 둔다. POST/PUT은 admin 분리 후 결정.

#### 5-2-1) Admin 통계 endpoint (`/api/v1/songs/stats`)
- 도입: **PR #228** (v0.3 P0, rev 14 후속 #208/#212). 운영 가시성 — `SongAudioBackfill` 진행 상황 확인.
- 인증: `X-Admin-Token` 헤더 + `com.mobruji.admin.AdminTokenVerifier`. Spring Security 정식 도입 시 인가 필터로 이전 예정.
- 응답(`SongStatsResponse`):
  - `total` — 전체 곡 수
  - `byMetadataSource` — `MetadataSource` enum 전체에 대해 0 건 source 도 0 으로 채움
  - `avgConfidence` — 평균 `metadataConfidence` (0.0~1.0). 곡 0 건이면 0.0
  - `lastBackfillAt` — 마지막 backfill batch 완료 시각. 미실행/재기동 후 미실행 시 `null`
- 본 endpoint 는 PoC 한정 운영 도구. fe 노출 계획 없음.

**키워드 검색 정책 (BE↔FE 계약)** — `keyword` 가 비/공백/null 이면 200 OK + 빈 배열(`[]`) 반환. 400 Bad Request 가 아니다. 이유:
1. Repository 는 `LIKE '%keyword%'` 라 빈 키워드면 전체 풀스캔. 200 OK + 빈 배열로 풀스캔을 차단한다.
2. fe(`web/app/songs/page.tsx`) 는 입력 전 호출도 안전하게 "검색 결과 없음"으로 fallback. 400 응답을 따로 분기하지 않아도 된다.

회귀 가드: `SongServiceTest#searchByKeyword_emptyKeyword_returnsEmpty`. 정책 변경 시 fe 페이지 헤더 주석("BE 약속")과 본 표를 동시 갱신.

### 5-3) 외부 연동 후보
| 출처 | 장점 | 단점/리스크 |
|------|------|-------------|
| **수기/시드 JSON** | 정확도·라이선스 안전, PoC에 즉시 가능 | 양 부족, 운영 인건비 |
| **MusicBrainz / Wikidata** | CC0, 약관 깔끔, ISRC·아티스트 풍부 | 키/BPM/음역 정보 빈약, 곡 음역 직접 못 얻음 |
| **Spotify Web API** | 곡 키(`key`), 템포(BPM), 에너지/밸런스 등 audio-features 풍부 | OAuth 필요, 약관상 데이터 캐싱 제약, **곡 음역 자체는 제공 안 함**, 한국 곡 커버리지 불균일 |
| **Apple Music API** | 한국 곡 커버리지 양호 | 약관 까다로움, 개발자 등록 비용 |
| **TJ/금영 사이트 크롤링** | 노래방 곡번호 직결 | 약관·저작권법 리스크 큼, 차단/구조 변경 취약, **AGPL 프로젝트에서 권장 비추천** |
| **사용자 기여(UGC)** | 장기적으로 풍부 | 1인 PoC 단계엔 0건, 신뢰도 검증 필요 |

### 5-4) 데이터 흐름 / 시퀀스 (PoC 권장 안)
```
[수기 시드 JSON ~100곡]
    ↓ Flyway 또는 ApplicationRunner로 DB 시드 적재
[외부 API: MusicBrainz/Spotify (선택)]
    ↓ 배치/onetime 스크립트로 audio-features 보강 (캐시 정책 약관 준수)
[Song 테이블]
    ↑ /api/v1/songs/* 조회
```
원본 호출 응답은 약관에 따라 단기 캐시만 두거나, 식별자만 저장하고 필요 시 재조회.

### 5-5) DB 마이그레이션
- 신규 테이블 `song` (필드는 §5-1 참조).
- 인덱스: `tj_number`, `ky_number`, `(title, artist)`, `metadata_source`.
- 마이그레이션 도구는 `voice-range-input.md Q3`에서 결정. (Flyway 권고 유지)

### 5-6) 프론트엔드 화면
- 본 spec 범위 아님. 추천 결과 화면에서 `SongResponse`만 소비.

## 6) 작업 분할 (예상 PR 리스트)
- [ ] PR A (docs): 본 spec 초안 PR — 본 PR.
- [ ] PR B (docs): Q1~Q4 합의 결과 spec 갱신 + `Song` 엔티티 도메인 모델 §5/§6 반영.
- [ ] PR C (feat:song): `Song` 엔티티 + Repository + read API.
- [ ] PR D (chore:song): 시드 데이터 입수·정규화 스크립트 + 시드 JSON 커밋.
- [ ] PR E (chore:song): (선택) 외부 API 보강 배치 — Q1 결정에 따라 생략 가능.
- [ ] PR F (test): 곡 검색/조회 E2E (RestAssured).

## 7) 테스트 전략
- **단위**: `SongRange` 불변식, MIDI 변환, 외부 API 응답 → 도메인 매핑.
- **통합/Repository**: testcontainers MySQL에 시드 적재 후 검색 인덱스 동작 확인.
- **E2E (필수)**: 곡 상세 조회 + 키워드 검색 성공 케이스 RestAssured.
- **외부 API mock**: WireMock 또는 응답 픽스처. 실제 토큰 사용 테스트는 CI에서 제외.
- **데이터 정합성 테스트**: 시드 JSON → DB 라운드트립에서 unique 제약, 음역 불변식.

## 8) 오픈 질문
> 모든 항목 해소. 본 spec은 `approved`. 새 질문이 생기면 본 테이블에 추가.

| #  | 질문 | 상태 |
|----|------|------|
| Q1 | 1차 메타데이터 주 출처 | **결정됨** → §9 (2026-05-20) |
| Q2 | 노래방 곡번호 입수 방법 | **결정됨** → §9 (2026-05-20) |
| Q3 | 곡 음역(`SongRange`) 산정 | **결정됨** → §9 (2026-05-20) |
| Q4 | PoC 시드 데이터 규모/장르 분포 | **결정됨** → §9 (2026-05-20) |

## 9) 결정 로그
> 연대기 순. "YYYY-MM-DD: 결정 / 이유 / 출처(PR 번호 등)"

- 2026-05-20: 초안 작성 (status=draft). D2를 본 spec Q1으로 다룸. 출처: #3
- 2026-05-20: Q1~Q4 작성자 권고안대로 확정, status=approved. 라이선스 안전성과 PoC 적정 규모 우선. 차후 재검토 가능. 출처: #4
  - **Q1 → (b) 수기 시드 + MusicBrainz 보강** — AGPL 프로젝트와 라이선스 호환(CC0)이 매끄럽고 Spotify 약관 캐싱 제약을 피할 수 있다. Spotify는 audio-features가 필요해지는 시점에 후속 PR.
  - **Q2 → (b) 수기 입력 (시드 100곡 대상)** — 크롤링은 법적 리스크 + 운영 부담으로 후순위.
  - **Q3 → (d) 본 단계 보류** — 1차 추천은 "사용자 음역대가 곡 원곡 키 ±3 semitone에 들어가는가" 규칙으로 단순화. 본격 `SongRange` 산정은 recommendation-algorithm 후속에서.
  - **Q4 → (b) 100곡, 발라드/댄스/팝 가중** — 적은 양으로도 추천 흐름 검증 가능 + 큐레이션 부담 합리적.
- 2026-05-21: 첫 구현 PR, status=implementing. 출처: #17
  - **분위기(Mood)는 단일 필드로 축소** — spec §5-1의 `mood(enum, 다중)`을 단일 `Mood` enum 1개로. v1 추천에서 단일 mood로 moodMatch가 충분히 작동하고, `@ElementCollection` 도입 복잡도 회피. 다중 분위기는 v2 spec에서 재검토.
  - **시드 30곡 (Q4의 100곡 → 30곡 축소)** — 1차 PoC는 30곡으로 추천 흐름 검증. 큐레이션 추가 작업은 별 PR로.
  - **Q1 MusicBrainz 보강은 본 PR에 없음** — 수기 시드 JSON만. 외부 API 보강은 후속.
- 2026-05-22: **MusicBrainz 보강 spec 신설** — Q1 결정의 미완 부분 (MusicBrainz 보강) 을 별 spec `docs/features/musicbrainz-integration.md` 로 분리 (#68). `Song.mbId` 컬럼 + selective backfill job + rate limit 1 req/s + User-Agent 의무. `spotify-audio-features-integration.md` (#69) 의 ISRC 매칭 선행 의존. PR A/B/C 분할.
- 2026-05-21: `Difficulty` enum + `lowMidi`/`highMidi` 도입(PR #96, closes #77, #95).
  - **`Song.lowMidi`/`highMidi`** — 곡 보컬 멜로디의 최저/최고음을 MIDI로 직접 저장 (Q3 보류 결정의 후속 진전). 별 `SongRange` VO는 도입하지 않음 — 30곡 시드 규모에선 두 필드로 충분.
  - **`Difficulty` enum (EASY/NORMAL/HARD)** — fe(`web/lib/difficulty.ts`)와 1:1 동일 룰. 분류 임계값(HARD≥76 또는 span≥17, NORMAL 71~75, EASY <71)은 `Song`의 상수에 하드코딩. ADR 0007 후보(maestro 후속).
  - **응답 노출** — `SongResponse`에 `difficulty`, `lowestNoteName`, `highestNoteName` 추가. 노트명 변환은 `song.domain.NoteName` 유틸 (sharp 표기, fe와 일치).
  - **시드 30곡 모두 `lowMidi`/`highMidi` 채움** — 합리적 추정값. 후속 큐레이션에서 정확도 향상 가능.
- 2026-05-23: §5-2 표 + §5-2-1 admin 통계 endpoint 섹션 신설 (rev drift #463). 실 코드 (`SongController#stats`, PR #228) 가 §5-2 표에 누락돼 있던 것을 동기화. 코드 변경 없음 — docs only.
