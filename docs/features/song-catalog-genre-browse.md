---
feature: 장르 카탈로그 — 노래방 책 형태 곡 열람 (song-catalog-genre-browse)
slug: song-catalog-genre-browse
status: draft
owner: "@goohong"
scope: song
related_issues: [1283]
related_prs: []
last_reviewed: 2026-05-29
---

# 장르 카탈로그 — 노래방 책 형태 곡 열람 (song-catalog-genre-browse)

## 1) 개요 (What / Why)

- 사용자 directive (2026-05-29): "내부적으로 장르 구분은 있는데 곡들을 꼭 음역대가 아니더라도 노래방 책보듯 열람할 수 있으면 좋겠다".
- 현재 mobruji 의 모든 곡 접근 경로는 (a) 음역대 입력 → 추천 (`POST /api/v1/recommendations`) 또는 (b) keyword 검색 (`/songs`, `GET /api/v1/songs?keyword=`) 두 가지 뿐. **둘 다 "사용자가 무엇을 원하는지 알고 있는" 전제**.
- 노래방 책 (TJ / 금영 책자) UX 는 **"오늘 분위기에 뭐 부르지"** 의 핵심 패턴 — 장르 인덱스를 펼쳐서 한 페이지씩 곡 카드를 훑는 형태. 음역대 / keyword 입력 없이 "둘러보기" 진입점이 필요하다.
- 본 spec 은 음역대 무관 + keyword 무관 **장르 인덱스 → 곡 카탈로그** UX 와 그 뒷받침 도메인 / API 를 정의한다.
- 대상 액터: (a) 추천 의존 없이 곡 풀을 둘러보고 싶은 사용자, (b) 특정 장르 (예: 발라드만) 의 곡 인벤토리를 빠르게 스캔하고 싶은 사용자.
- 추천 알고리즘 / `SessionAuthGuard` / `VoiceRange` 와 **완전 분리** — 결정성 회귀 없음, 익명 호출.

## 2) 사용자 시나리오

1. **장르 인덱스 진입** — 랜딩에서 "장르별 둘러보기" CTA → `/songs/catalog` 진입. 화면 상단에 6~7개 장르 카드 (발라드 / 댄스 / 락 / 트로트 / 팝 / 힙합 / 기타) + 각 카드에 곡 수 (예: "발라드 32곡") 노출.
2. **장르 선택** — 카드 클릭 → `/songs/catalog?genre=BALLAD` 로 이동. 해당 장르 곡 카드 그리드. 정렬 옵션 (인기순 / 가나다순 / 발매연도 desc) + 페이지네이션 (또는 무한 스크롤).
3. **다중 필터 보강** — 사이드/탭에 보조 필터: 발매 연도 decade (90s / 00s / 10s / 20s), 언어 (ko / en / ja / 기타). 필터는 URL query 동기화 (공유 / 새로고침 복원).
4. **곡 카드 클릭** — 기존 `SongDetailModal` 재사용 — 곡 상세 + (음역대 알면) 음역 적합도 표시. 음역대 없으면 곡 메타데이터만.
5. **keyword 검색 진입 유지** — 헤더 검색 bar 는 `/songs` (기존) 로 라우팅. "둘러보기" 와 "찾기" 두 mental model 분리.

## 3) 요구사항

### 기능 요구사항

- [ ] **신규 페이지** `/songs/catalog` — 장르 인덱스 + 장르별 곡 그리드.
- [ ] **장르 enum promote** — `Song.genre String(32)` → `Genre` enum (BALLAD / DANCE / ROCK / TROT / POP / HIPHOP / OTHER 등). 시드 / DB 데이터 backfill 마이그레이션.
- [ ] **신규 endpoint** `GET /api/v1/songs/genres` — 장르 인덱스 응답 (장르 + 곡 수). 캐시 가능.
- [ ] **`GET /api/v1/songs` 진화** — 서버측 필터 (`genre` / `decade` / `language`) + 정렬 (`sort=popularity|title|releaseYear`) + 페이지네이션 (`page` / `size`). 기존 `keyword` 파라미터 호환 유지 (deprecation 없음).
- [ ] **음역대 무관** — 신규 API 어떤 경로도 `VoiceRange` / `sessionId` 의존 금지. 익명 호출 + `SessionAuthGuard` 미적용.
- [ ] **추천 API 영향 없음** — `RecommendationService` / `SeedDeriver` / `RecommendationScorer` 어떤 코드도 본 spec 변경에 의존하지 않는다 (결정성 회귀 가드).
- [ ] **응답 페이로드 통일** — `GET /api/v1/songs` 진화형 응답은 `SongListResponse(items, page, size, totalCount, hasNext)` wrapper (기존 `LikeListResponse` 패턴과 일치). 기존 `List<SongResponse>` 직접 반환은 deprecation 후 v2 endpoint 로 분리.
- [ ] **fe URL query 동기화** — `?genre=BALLAD&decade=10s&sort=popularity&page=2` 형태. 새로고침 / 공유 / 뒤로가기 복원.
- [ ] **장르 다중 선택 vs 단일 선택** — 인덱스 진입은 단일 (한 장르 = 한 화면), 결과 화면 내 보조 칩으로 추가 장르 토글 가능 (`?genre=BALLAD,POP`). 같은 그룹 OR.

### 비기능 요구사항

- 응답 p95 200ms 이내 (장르별 페이지 size=20 기준, DB 100~수백곡 카탈로그 가정). 기존 `recommendation-p95-regression-guard.md` 와 동일 budget 채택.
- `GET /api/v1/songs/genres` 는 **응답 캐시 가능** (장르 / 곡 수만 → 곡 추가 / 삭제 시점 외 변경 없음). HTTP `Cache-Control: max-age=60` 또는 application-level Caffeine 캐시 (TTL 5분).
- 페이지네이션 default `size=20`, max `size=100` (DoS 가드).
- 정렬 `popularity` 신호는 v0.2 단계 — 곡 신호 부재 시 (currently 모두 동일) **fallback = 가나다순** (결정성 보장). v0.3 에서 like/bookmark count 누적 시 정식 popularity 가중 (별 spec `recommendation-algorithm-v2.md` 의 popularity 신호와 통일).
- 외부 API 호출 없음.
- 신규 endpoint 모두 익명 / public. PII 무.

## 4) 범위 / 비범위 (중요)

### 포함

- 신규 `/songs/catalog` 페이지 (장르 인덱스 + 장르별 그리드).
- 신규 endpoint `GET /api/v1/songs/genres` + 기존 `GET /api/v1/songs` 진화.
- `Genre` enum promote + DB 마이그레이션 (백필).
- 시드 JSON (`songs-seed.json`) 의 `genre` 값 → 신규 enum literal 매핑 (white-list 검증 강화).
- fe URL query 동기화 + 정렬 / 페이지네이션 UI.
- 곡 카드 = 기존 `SongCard` / `SongDetailModal` **재사용** — fe 컴포넌트 신설 최소화.

### 제외 (Out of Scope)

- **추천 알고리즘 재설계** — 본 spec 은 추천 입력 / 결과에 영향 0. `RecommendationService` / `SeedDeriver` 무관.
- **음역대 적합도 표시** — `SongDetailModal` 의 음역 적합도 UI 는 sessionId 가 `VoiceRange` 보유 시만 자동 노출 (기존 동작). 본 spec 변경 없음.
- **다국어 장르명 / i18n** — 한국어 라벨만 (`발라드`, `댄스`, ...). 영어 라벨은 enum name 그대로 노출.
- **관리자 곡 등록 / 수정 UI** — 시드 JSON 직접 편집 유지 (`song-curation-seed-100.md` §4 와 동일 입장).
- **신규 장르 추가 PR 워크플로우** — v0.4 외부 큐레이션 기여 spec (`song-curation-seed-100.md §4-1`) 에서 다룬다.
- **like / bookmark popularity 정식 가중** — `recommendation-algorithm-v2.md` popularity 신호와 통합 시점에 별 spec.
- **장르 자동 분류 (ML)** — v0.3+ 후보. 본 spec 은 수기 분류 (`Song.genre` 큐레이터 입력) 만.
- **rev sub-agent 가 직접 API + 브라우저 e2e QA 수행** — 사용자 요청 1 (2026-05-29) 은 rev 역할 확장이라 scope 다름. **별 spec `docs/features/rev-direct-api-qa-and-browser-e2e.md` 신설 권고** (본 사이클 외 후속).

## 5) 설계

### 5-1) 도메인 모델

#### 신규 / 보강 용어 (`06-domain-model.md` §4 등재 의무 — 구현 PR 에서 동기 갱신)

| 한국어 | 영어 (코드) | 정의 |
|---|---|---|
| 장르 | Genre | 곡을 분류하는 폐쇄 집합 enum. 책 형태 인덱스의 1차 키. (현재 `Song.genre String` 을 promote) |
| 곡 카탈로그 | SongCatalog | 음역대 / 추천과 무관하게 곡 풀 전체를 둘러보는 read-only view. 신규 `GET /api/v1/songs` 진화형 응답 = 카탈로그 슬라이스. |
| 장르 인덱스 | GenreIndex | 장르별 곡 수 집계. `GET /api/v1/songs/genres` 응답. |
| 발매 시대 | ReleaseDecade | 발매 연도를 decade 단위로 묶은 derived 분류 (90s / 00s / 10s / 20s / OTHER). DB 컬럼 X — `releaseYear / 10 * 10` 으로 파생. |

#### `Genre` enum (신규 — `com.mobruji.song.domain.Genre`)

`song-curation-seed-100.md §5-5` 의 white-list 5종 + 확장 후보. **현재 시드 (30곡 + curation-100 plan) 와 1:1 매핑 보장**.

| enum literal | 한국어 라벨 | 비고 |
|---|---|---|
| `BALLAD` | 발라드 | curation-100 default 30곡 |
| `DANCE` | 댄스 | curation-100 default 20곡 |
| `ROCK` | 락 | curation-100 default 15곡 |
| `TROT` | 트로트 | curation-100 default 15곡 |
| `POP` | 팝 | curation-100 default 20곡 |
| `HIPHOP` | 힙합 | `song-self-analysis-pipeline.md §4-1` 분포와 통일 위해 enum 에 포함. 시드 0~10곡. |
| `OTHER` | 기타 | 신곡 / 분류 모호 / 외부 backfill 시 fallback. nullable 회피용. |

**자율 결정 — `OTHER` 도입 사유**: `Song.genre` 를 not-null + enum 으로 promote 하면 null/모호 데이터가 마이그레이션을 막는다. `OTHER` 가 escape hatch.

#### `Song` 엔티티 변경 (`docs/ai-harness/06-domain-model.md §5-2`)

| 필드 | 현재 | 변경 후 | 마이그레이션 |
|---|---|---|---|
| `genre` | `String(32)` nullable | `Genre` enum **not null** | V<n>: (1) 신규 컬럼 `genre_enum VARCHAR(16)` 추가, (2) backfill: 한글 string → enum literal 매핑, NULL/미매핑 → `OTHER`, (3) old column drop, (4) rename `genre_enum` → `genre`. **Flyway 2 step 마이그레이션 (drop 분리)** — 다운타임 0 보장. |

매핑 규칙:

| 기존 `genre` string | 신규 enum |
|---|---|
| `발라드`, `Ballad`, `BALLAD` | `BALLAD` |
| `댄스`, `Dance`, `DANCE` | `DANCE` |
| `락`, `록`, `Rock`, `ROCK` | `ROCK` |
| `트로트`, `Trot`, `TROT` | `TROT` |
| `팝`, `Pop`, `POP` | `POP` |
| `힙합`, `Hip-hop`, `HIPHOP`, `HIP_HOP` | `HIPHOP` |
| 그 외 / NULL | `OTHER` |

매핑은 Flyway SQL `CASE WHEN` 으로 결정성 보장. 시드 JSON (`songs-seed.json`) 의 `genre` 필드도 동일 마이그레이션 + `SongSeedLoader` 가 enum literal 로 적재 (호환 위해 한글 string 입력도 유지).

### 5-2) API 엔드포인트

| Method | Path | 설명 | 인증 | Req | Res |
|---|---|---|---|---|---|
| GET | `/api/v1/songs/genres` | 장르 인덱스 (장르 + 곡 수). 캐시 가능. | 익명 | - | `GenreIndexResponse(items, totalSongs)` |
| GET | `/api/v1/songs` (진화) | 곡 카탈로그 슬라이스 — 필터 + 정렬 + 페이지네이션. | 익명 | query | `SongListResponse(items, page, size, totalCount, hasNext)` |

#### `GET /api/v1/songs/genres`

```json
{
  "items": [
    { "genre": "BALLAD", "label": "발라드", "count": 32 },
    { "genre": "DANCE",  "label": "댄스",   "count": 20 },
    { "genre": "ROCK",   "label": "락",     "count": 15 },
    { "genre": "TROT",   "label": "트로트", "count": 15 },
    { "genre": "POP",    "label": "팝",     "count": 18 },
    { "genre": "HIPHOP", "label": "힙합",   "count":  0 },
    { "genre": "OTHER",  "label": "기타",   "count":  0 }
  ],
  "totalSongs": 100
}
```

- **모든 enum literal 노출** (count=0 도 포함) — fe 가 인덱스 카드 grid 를 안정적으로 렌더 (장르 0 곡 → "준비 중" 표기 가능).
- `label` 은 한국어 라벨 (i18n 후보 — v0.4).
- 캐시: application-level Caffeine TTL 5분 (시드 적재 / 큐레이션 PR 머지 빈도 < 분 단위).

#### `GET /api/v1/songs` (진화형 — wrapper response)

쿼리 파라미터:

| 파라미터 | 타입 | 기본 | 검증 | 설명 |
|---|---|---|---|---|
| `genre` | string (CSV) | (없음 = 전체) | enum literal | `BALLAD` 또는 `BALLAD,POP`. 그룹 OR. |
| `decade` | string (CSV) | (없음) | `90s`/`00s`/`10s`/`20s`/`OTHER` | `releaseYear` 기반 derive. `OTHER` = 1970s/80s/null. |
| `language` | string (CSV) | (없음) | `ko`/`en`/`ja`/`zh`/`OTHER` | |
| `keyword` | string | (없음) | 1~50자 | **기존 호환** — title/artist LIKE. 다른 필터와 AND. |
| `sort` | enum | `title` | `title`/`releaseYear`/`popularity` | popularity v0.2 단계 = `title` fallback. |
| `order` | enum | `asc` | `asc`/`desc` | `releaseYear`/`popularity` default = `desc`. |
| `page` | int | `0` | ≥ 0 | offset-based (0-indexed). |
| `size` | int | `20` | 1 ≤ size ≤ 100 | DoS 가드. |

응답:

```json
{
  "items": [ /* SongResponse[] (기존 dto 재사용) */ ],
  "page": 0,
  "size": 20,
  "totalCount": 32,
  "hasNext": true
}
```

- `keyword` 가 비/null + 다른 필터도 없으면 전체 카탈로그 (페이지네이션). 기존 "빈 keyword → 빈 배열" 정책은 **`keyword` 가 명시되었으나 비/공백일 때만** 빈 배열 (BE↔FE 계약 호환, `song-metadata-source.md` §5-2 정합).
- `items` 형태는 기존 `SongResponse` 와 동일 (앨범 커버 / 음역 / 난이도 / mood / tjNumber 등 모두 노출).

#### 기존 endpoint 호환 / deprecation

| 기존 호출 | 신규 동작 |
|---|---|
| `GET /api/v1/songs?keyword=xxx` (응답 `List<SongResponse>`) | **응답 형태 변경 = breaking** → 신규 `GET /api/v1/songs` 는 wrapper 응답. 기존 호출자 (fe `/songs` page) 는 동일 PR 에서 wrapper 응답 파싱으로 같이 변경. **별 v2 endpoint 분리 X** (consumer 가 fe 1군데뿐). |
| `GET /api/v1/songs/{id}` | 변경 없음. |
| `GET /api/v1/songs/stats` | 변경 없음. |

**자율 결정 — wrapper response breaking 채택 사유**: consumer 가 fe 한 곳뿐이라 v2 분리 비용이 wrapper 통일 이점보다 크다. 같은 PR 에서 fe `searchSongs(...)` (`web/lib/api/song.ts`) 를 wrapper 응답으로 같이 갱신 — atomic. `LikeListResponse` / `BookmarkListResponse` 패턴과 일관.

### 5-3) 외부 연동

- 없음.

### 5-4) 데이터 흐름 / 시퀀스

```
[fe] /songs/catalog 진입
   ↓
GET /api/v1/songs/genres            ← Caffeine 5분 캐시
   ↓
[fe] 장르 인덱스 카드 grid 렌더
   ↓ (사용자 장르 클릭)
[fe] /songs/catalog?genre=BALLAD&page=0
   ↓
GET /api/v1/songs?genre=BALLAD&sort=title&page=0&size=20
   ↓
[BE] SongRepository.findByFilters(...)  ← 신규 메서드 (Specification 또는 named query)
   ↓
[fe] SongCard grid + pagination footer
   ↓ (사용자 카드 클릭)
[fe] SongDetailModal (기존 컴포넌트 재사용)
```

추천 / 음역 / sessionId 흐름과 **완전 격리** — `RecommendationService` / `VoiceRangeRepository` 어떤 호출도 없음.

### 5-5) DB 마이그레이션

신규 Flyway 마이그레이션 (V<n>):

```sql
-- V<n>__promote_song_genre_enum.sql
ALTER TABLE song ADD COLUMN genre_enum VARCHAR(16) NULL;

UPDATE song SET genre_enum = CASE
    WHEN genre IN ('발라드', 'Ballad', 'BALLAD')                     THEN 'BALLAD'
    WHEN genre IN ('댄스',   'Dance',  'DANCE')                      THEN 'DANCE'
    WHEN genre IN ('락',     '록',     'Rock', 'ROCK')               THEN 'ROCK'
    WHEN genre IN ('트로트', 'Trot',   'TROT')                       THEN 'TROT'
    WHEN genre IN ('팝',     'Pop',    'POP')                        THEN 'POP'
    WHEN genre IN ('힙합',   'Hip-hop','HIPHOP', 'HIP_HOP')          THEN 'HIPHOP'
    ELSE 'OTHER'
END;

ALTER TABLE song MODIFY COLUMN genre_enum VARCHAR(16) NOT NULL;
CREATE INDEX ix_song_genre_enum ON song (genre_enum);

-- 2-step drop: 본 마이그레이션은 old column 보존 (rollback 가능).
-- 다음 release V<n+1> 에서:
--   ALTER TABLE song DROP COLUMN genre;
--   ALTER TABLE song CHANGE COLUMN genre_enum genre VARCHAR(16) NOT NULL;
--   (인덱스도 ix_song_genre_enum → ix_song_genre 로 rename)
```

- **2-step drop 사유**: rollback safety. backfill 매핑이 누락된 string 발견 시 V<n+1> 적용 전에 SQL 수동 fix 가능.
- 시드 JSON (`songs-seed.json`) 도 enum literal (`"BALLAD"`) 로 통일하는 chore PR 을 V<n+1> 진입 전 머지.
- ERD 갱신 (`06-domain-model.md §6`) — `genre` 컬럼 타입 표기 변경.

### 5-6) 프론트엔드 화면

#### 신규 경로 `/songs/catalog`

- 진입 시 `GET /api/v1/songs/genres` → 인덱스 grid (responsive: mobile 2-col / desktop 4-col).
- 각 장르 카드: 라벨 + 곡 수 + 장르별 representative emoji (예: 발라드 🎤 / 댄스 💃 / 락 🎸 / 트로트 🎶 / 팝 🎵 / 힙합 🎤 / 기타 📀) — text-only fallback 도 노출 (i18n / 접근성).
- 카드 클릭 → `/songs/catalog?genre=<G>` push.

#### 신규 경로 `/songs/catalog?genre=<G>` (또는 `genre=G1,G2`)

- 헤더: "<라벨> 곡 N곡" + "장르 인덱스로" back link.
- 보조 필터 row (chip multi-select): 발매 시대 / 언어 / 추가 장르.
- 정렬 dropdown: 인기순 / 가나다순 / 발매일 (신곡 우선).
- 결과 grid: `SongCard` 재사용. 페이지네이션 footer 또는 무한 스크롤. **자율 결정 — 페이지네이션 채택**: 무한 스크롤은 곡 위치 잃기 쉬움 + 공유 URL 안정성 ↓. 카탈로그 = "둘러보기" 라 위치 보존이 중요.
- URL query 동기화: `useSearchParams` + `router.replace` (히스토리 더럽힘 X). 기존 `/songs` 페이지 패턴 재사용.
- 곡 카드 클릭 → 기존 `SongDetailModal` 재사용. 음역대 적합도는 sessionId 가 `VoiceRange` 있을 때만 노출 (기존 동작).

#### 헤더 / 진입점

- 랜딩 (`/`) 에 "장르별 둘러보기" CTA 추가 — `/songs/catalog` 로 라우팅.
- 기존 `/songs` 페이지 헤더에 "장르 인덱스" 보조 link 추가 (mental model bridge).

## 6) 작업 분할 (예상 PR 리스트)

> 의존성: PR 1 (도메인) → PR 2 (BE API) → PR 3 (fe 페이지) 순차. PR 4 (시드 enum 통일) 는 PR 1 머지 후 병렬.

- [ ] **PR 1 (docs)** — 본 spec PR (현재). `06-domain-model.md §4` 신규 용어 등재 (Genre / SongCatalog / GenreIndex / ReleaseDecade) 는 구현 PR 에서 동시 갱신 (spec 단계에서는 후보 정의만).
- [ ] **PR 2 (feat:song)** — `Genre` enum + Flyway V<n> 마이그레이션 (2-step drop step 1) + `Song.genre` 타입 변경 + `SongSeedLoader` 한글 string → enum literal 호환 + 단위/통합 테스트. `06-domain-model.md §4/§5/§6` 동기 갱신.
- [ ] **PR 3 (feat:song)** — `GET /api/v1/songs/genres` 신규 + `GET /api/v1/songs` wrapper response 진화 (페이지네이션 / 필터 / 정렬) + `SongListResponse` / `GenreIndexResponse` DTO + Caffeine 캐시 + E2E (RestAssured).
- [ ] **PR 4 (chore:song)** — `songs-seed.json` `genre` 값을 한글 → enum literal (`"BALLAD"`) 로 통일. 시드 검증 테스트 (`song-curation-seed-100.md` PR A) white-list 갱신.
- [ ] **PR 5 (feat:web)** — `/songs/catalog` 페이지 신설 (인덱스 + 결과 화면) + `web/lib/api/song.ts` wrapper response 파싱 + 기존 `/songs` 페이지 헤더 link + 랜딩 CTA.
- [ ] **PR 6 (chore:db)** — Flyway V<n+1> 2-step drop step 2 (old `genre` column drop + rename). PR 2 머지 + 모든 환경 (dev / prod) 정상 가동 확인 후 별 PR.
- [ ] **PR 7 (docs, 후속 옵션)** — `recommendation-algorithm-v2.md` 의 `genre` 가중치 (현 0.2) 가 enum 타입 변경 후에도 영향 없음을 확인 + 해당 spec 의 "genre 입력 신호 없음" 주석을 enum literal 기반으로 정정.

### 보호 영역 변경 여부 (필수 명시)

- 보호 영역 변경 여부: ☑ **있음** — 변경 파일 (PR 단위):
  - **PR 2** — `backend/src/main/resources/db/migration/V<n>__promote_song_genre_enum.sql` (신규 Flyway 마이그레이션, `**/db/migration/**` 정보성 보호 영역).
  - **PR 6** — `backend/src/main/resources/db/migration/V<n+1>__drop_song_genre_legacy.sql` (column drop / rename, **2-step rollback safety 의무**).
  - PR 5 — `web/package.json` / lockfile **변경 없음** (`SongCard` / `SongDetailModal` / `useQuery` 모두 기존 의존성). 신규 의존성 발생 시 별 PR 분리 권고.
  - 사유: 책 형태 카탈로그 = read-only view 라 schema 변경 risk 는 enum promote 1건 (rollback 가능한 2-step).
- rev 가중도: 신중도 ↑ (마이그레이션 2건 / wrapper response breaking 1건).

## 7) 테스트 전략

### 단위

- `Genre` enum literal ↔ 한국어 라벨 매핑 round-trip (`Genre.fromLegacyString(...)` 가 backfill SQL 의 CASE WHEN 과 동등).
- `SongRepository.findByFilters(...)` (또는 Specification 빌더) — 필터 조합 (genre + decade + language + keyword) 별 SQL 검증 + 결과 ID 안정성.
- 페이지네이션 boundary — page=0 / size=20, page=마지막, size=1, size=100 (max), size=101 (`IllegalArgumentException`).
- 정렬 `popularity` v0.2 fallback = `title` 동등 결과 단정 (결정성).

### 통합 (testcontainers)

- Flyway V<n> 마이그레이션 idempotency — backfill 후 모든 row `genre` not-null + 알려진 enum literal 만.
- `SongSeedLoader` 멱등 — 한글 string seed + enum literal seed 둘 다 동일 결과 (호환 기간용).
- `Genre` 분포 회귀 — 시드 100곡 (curation-100 머지 후) 의 장르 분포가 default ±5 이내.

### E2E (RestAssured — 신규 endpoint 의무)

- `GET /api/v1/songs/genres` — 200 + 모든 enum literal 노출 + `totalSongs` == DB count.
- `GET /api/v1/songs?genre=BALLAD` — 200 + 모든 item `genre=BALLAD` + `totalCount` ≥ 1.
- `GET /api/v1/songs?genre=BALLAD,POP&decade=10s&sort=title&page=0&size=5` — 200 + items <= 5 + hasNext 정합.
- `GET /api/v1/songs?size=101` — 400 (DoS 가드).
- `GET /api/v1/songs?keyword=` (명시 + 빈) — 200 + 빈 items (기존 정책 유지).
- `GET /api/v1/songs` (모든 필터 무) — 200 + 전체 카탈로그 1 페이지.

### 결정성 / 회귀 가드

- `RecommendationDeterminismTest` **변경 없이 grren** — 본 spec 변경이 추천 결과에 영향 0 증명. rev 단계 1 에서 grep 으로 강제 (`SongCatalog` / `Genre` import 가 `recommendation` 패키지에 누락된 것 확인).
- `RecommendationP95RegressionGuard` 영향 없음 (별 endpoint).

### fe 테스트

- `/songs/catalog` page snapshot (Playwright) — 장르 인덱스 + 장르별 결과 grid.
- URL query 복원 — `?genre=BALLAD&decade=10s&page=2` 새로고침 후 같은 상태.
- 정렬 dropdown 변경 → URL `?sort=releaseYear` 반영.

## 8) 오픈 질문

> 구현 전에 답이 나와야 하는 것들. 해소되면 §9 결정 로그로 이동.

| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | `Genre` enum 초기 7종 (BALLAD/DANCE/ROCK/TROT/POP/HIPHOP/OTHER) 가 적절한가 | (a) 7종 유지 (현 spec), (b) HIPHOP 제외 6종, (c) 더 세분화 (JAZZ / RNB / INDIE 추가) | @goohong / PR 2 직전 |
| Q2 | `GET /api/v1/songs` wrapper breaking 채택 vs v2 endpoint 분리 | (a) wrapper breaking (현 spec — consumer 1군데), (b) `/api/v2/songs` 신설 + 기존 v1 유지 | @goohong / PR 3 직전 |
| Q3 | 페이지네이션 vs 무한 스크롤 | (a) offset 페이지네이션 (현 spec — 위치 보존), (b) cursor 기반 무한 스크롤 | @goohong / PR 5 직전 |
| Q4 | `popularity` 정렬 v0.2 단계 fallback | (a) title 동등 fallback (현 spec — 결정성), (b) v0.3 like/bookmark count 누적 전까지 dropdown 옵션에서 제외 | @goohong / PR 3 직전 |
| Q5 | 장르 인덱스 캐시 (`GET /api/v1/songs/genres`) | (a) Caffeine TTL 5분 (현 spec), (b) HTTP `Cache-Control: max-age=60` 만 (서버 캐시 X), (c) 둘 다 | @goohong / PR 3 직전 |
| Q6 | 시드 JSON `genre` 한글 string 호환 유지 기간 | (a) PR 2 머지와 동시에 enum literal 통일 (호환 0), (b) PR 4 까지 한글 string 호환 (현 spec — 단계 분할 안전), (c) 장기 호환 (v0.4 까지) | @goohong / PR 2 직전 |
| Q7 | 랜딩 (`/`) CTA 위치 | (a) "추천 받기" CTA 옆 보조 button, (b) hero 하단 별 section ("음역대 없이 둘러보기"), (c) header navigation link | @goohong / PR 5 직전 |

## 9) 결정 로그

> 연대기 순. "YYYY-MM-DD: 결정 / 이유 / 출처(PR 번호 등)"

- 2026-05-29: 초안 작성 (status=draft). 사용자 directive ("음역대 무관 노래방 책 형태 열람") 반영. 출처: #1283
  - **`/songs/catalog` 신설 — 기존 `/songs` 진화 X**: 검색 (`/songs`) ↔ 카탈로그 (`/songs/catalog`) 두 mental model 분리. 한 페이지에 합치면 입력 box 와 인덱스 grid 가 시각적 / 인지적 충돌.
  - **`Genre` enum promote — `String(32)` 유지 X**: 책 인덱스의 1차 키는 폐쇄 집합. nullable string 은 인덱스 신뢰도 부족 (장르 0건 / OTHER 카드를 안정적으로 못 보임). `OTHER` escape hatch 로 backfill 안전.
  - **wrapper response breaking 채택 — v2 분리 X**: consumer (fe `/songs` page) 1군데뿐. `LikeListResponse` 패턴 통일 이점이 v2 분리 비용 초과. 같은 PR atomic 갱신.
  - **2-step Flyway drop**: rollback safety. 매핑 누락 발견 시 V<n+1> 전 수동 fix.
  - **추천 알고리즘 분리 strict**: 본 spec 어떤 변경도 `RecommendationService` / `SeedDeriver` / `RecommendationScorer` 에 영향 0. 결정성 회귀 가드 명시.
  - **rev sub-agent 가 직접 API + 브라우저 e2e QA (사용자 요청 1)** 는 본 spec 에 묶지 않음. rev 역할 확장 = scope `infra` + rev e2e 2단계 (`rev-e2e-2-stages.md`) 와 통합 검토 필요 → 별 spec `docs/features/rev-direct-api-qa-and-browser-e2e.md` 신설 권고 (nmae 백로그 분배).

## 10) 관련 spec / ADR

- `docs/features/song-metadata-source.md` — `Song` 엔티티 SoT. 본 spec 의 `Genre` enum promote 가 §5-1 잠정 필드 정정.
- `docs/features/song-curation-seed-100.md` — 시드 100곡 / 장르 분포 default. 본 spec 의 enum 7종 = curation-100 + HIPHOP + OTHER.
- `docs/features/recommendation-algorithm-v1.md` / `recommendation-algorithm-v2.md` — 추천 알고리즘. 본 spec 영향 0 (결정성 회귀 가드).
- `docs/features/recommendation-p95-regression-guard.md` — p95 budget. 신규 endpoint 도 동일 200ms 채택.
- `docs/decisions/0005-package-structure.md` — Song aggregate 참조는 ID-only (본 spec 신규 endpoint 도 동일).
- `docs/decisions/0007-vocal-difficulty-classification.md` — `Difficulty` enum 패턴. 본 spec `Genre` enum promote 와 같은 root motivation (string → typed).
- `docs/decisions/0009-schema-migration-tool.md` — Flyway. 2-step drop 패턴 채택.

## 11) 자율 결정 (사유) — sub-agent 룰 §1-2

본 spec 작성 중 발생한 모호 분기 자율 결정 (`AskUserQuestion` 금지). 결정 / alternative / 사유 / follow-up 명시:

| # | 결정 | alternative | 사유 |
|---|---|---|---|
| A1 | 페이지 명명 `/songs/catalog` | `/catalog`, `/songs/browse`, `/songs?mode=catalog` | `/songs` 트리 일관성 + `browse` 보다 "노래방 책" 의미 직관. `mode` query 는 페이지 라우팅 분리가 더 깔끔. |
| A2 | `Genre` enum 7종 (BALLAD/DANCE/ROCK/TROT/POP/HIPHOP/OTHER) | 5종 (curation-100 default), 10+ 종 (장르 세분화) | curation-100 default 5종 + self-analysis-pipeline 의 HIPHOP + OTHER escape hatch = 최소 변경으로 enum promote 가능. 세분화는 v0.3+ 후보. |
| A3 | wrapper response breaking | v2 endpoint 분리 | consumer (fe) 1군데뿐. `LikeListResponse` 패턴 통일. v2 분리 비용 (route / DTO / 테스트 2배) > wrapper 통일 이점. |
| A4 | 페이지네이션 (offset) | 무한 스크롤 (cursor) | "둘러보기" = 위치 보존 / 공유 URL 안정성 중요. 무한 스크롤은 위치 잃기 쉬움. |
| A5 | rev QA 확장 분리 spec | 본 spec 에 묶음 | scope `song` (도메인) vs `infra` (rev 역할) 다름. rev e2e 2단계 (`rev-e2e-2-stages.md`) 와 통합 검토 필요. 묶으면 PR / 라벨 / 사이클 모두 부정합. |
| A6 | 2-step Flyway drop | 1-step (drop + rename 동시) | rollback safety. backfill 매핑 누락 발견 시 V<n+1> 전 SQL 수동 fix 가능. v0.2 단계 = production 데이터 보호 우선. |

follow-up 이슈 분리 가능:

- `rev-direct-api-qa-and-browser-e2e.md` spec 신설 (사용자 요청 1, scope=infra) — nmae 백로그 분배.
- `Genre` 세분화 (Q1) v0.3+ 후보 — like/bookmark count 누적 데이터로 인기 장르 통계 후 분류 재논의.
