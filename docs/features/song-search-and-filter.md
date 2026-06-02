---
feature: 곡 검색 / 필터 — 제목·가수 검색 + 난이도·음역대·분위기 좁히기 (song-search-and-filter)
slug: song-search-and-filter
status: draft
owner: "@goohong"
scope: song
related_issues: []
related_prs: []
last_reviewed: 2026-06-03
---

# 곡 검색 / 필터 — 제목·가수 검색 + 난이도·음역대·분위기 좁히기 (song-search-and-filter)

## 1) 개요 (What / Why)

- 사용자 directive (roadmap-search-plan): "사용자가 직접 곡을 검색(제목/가수)하고 필터(장르/난이도/음역대/분위기)로 좁혀 **아는 곡을 바로 찾는** 흐름 — 추천 기능과 별개."
- 현재 mobruji 의 직접 검색은 `GET /api/v1/songs?keyword=` 단 하나 — `LIKE '%keyword%'` 로 title/artist 부분 일치 후 `List<SongResponse>` 평면 반환 (`SongRepository.searchByKeyword`). **leading wildcard 라 인덱스 미사용 (full table scan)** + 필터 0개 + 정렬 = 제목 가나다순 고정 + 페이지네이션 없음.
- 노래방 사용자는 **이미 부를 곡을 알고 있는 경우가 많다** ("그 발라드 뭐였지", "○○○ 노래 중에 쉬운 거"). 이때 필요한 건 추천이 아니라 **빠르고 관대한 검색 + 좁히기 필터**다.
- 본 spec 은 **"찾기"(검색)** mental model 을 책임진다 — `song-catalog-genre-browse.md` 의 **"둘러보기"(장르 인덱스)** 와 한 쌍을 이루는 반대편 진입점. 둘러보기 spec 이 명시적으로 "keyword 검색 진입 유지 — 헤더 검색 bar 는 `/songs` 로 라우팅. '둘러보기' 와 '찾기' 두 mental model 분리" 라고 본 spec 으로 핸드오프했다 (`song-catalog-genre-browse.md §2-5`).
- 대상 액터: (a) 제목/가수 일부만 기억하는 사용자, (b) "쉬운 발라드", "내 음역대에 맞는 신나는 곡" 처럼 **검색어 + 다축 필터 조합** 으로 좁히려는 사용자.
- 추천 알고리즘 / `RecommendationService` / `SeedDeriver` / `RecommendationScorer` 와 **완전 분리** — 결정성 회귀 없음. `SessionAuthGuard` 미적용 (익명).

## 2) 사용자 시나리오

1. **제목/가수 검색** — `/songs` 검색 page 진입 → 검색 box 에 "발라" 입력 → 디바운스 후 자동완성 제안 (`발라드`, `발라드` 포함 곡 / 가수) 노출 + 엔터 시 결과 그리드.
2. **초성 검색** — "ㅂㄹㄷ" 입력 → "발라드" 매칭. 모바일 사용자가 정확한 글자 없이도 빠르게 좁히는 한국어 앱 관습 지원.
3. **검색 + 필터 조합** — "임재범" 검색 후 좌측/상단 필터 패널에서 **난이도 = EASY** 칩 토글 → 임재범 곡 중 EASY 만. 추가로 **음역대 적합** 토글 → 내 음역으로 부를 수 있는 곡만.
4. **필터만 좁히기 (검색어 없이)** — 검색 box 비움 + **장르=발라드 + 분위기=잔잔함 + 난이도=NORMAL** → 조건에 맞는 전체 곡을 관련도 무관 가나다순으로.
5. **결과 → 곡 상세** — 결과 카드 클릭 → 기존 `SongDetailModal` 재사용 (음역 적합도는 sessionId 가 `VoiceRange` 보유 시만 노출 — 기존 동작).
6. **URL 공유 / 복원** — `?keyword=임재범&difficulty=EASY&fitLow=48&fitHigh=64&page=1` 형태로 새로고침 / 공유 / 뒤로가기 복원.

## 3) 요구사항

### 기능 요구사항

- [ ] **`GET /api/v1/songs` 검색·필터 진화** — 기존 `keyword` 단일 파라미터를 **다축 필터 + 관련도 정렬 + 페이지네이션** wrapper 응답으로 확장. (`song-catalog-genre-browse.md` 가 정의한 `SongListResponse` wrapper + `genre`/`decade`/`language`/`sort`/`page`/`size` 골격을 **공유**하고, 본 spec 은 `difficulty`/`mood`/`fitLow`/`fitHigh` 필터 + `sort=relevance` 를 **가산**한다.)
- [ ] **관련도(relevance) 정렬** — `keyword` 가 있을 때 default `sort=relevance`: 제목 정확 일치 > 제목 prefix 일치 > 제목 부분 일치 > 가수 일치 순 tier + 동 tier 가나다순. `keyword` 없으면 relevance 무의미 → `title` fallback.
- [ ] **초성 검색** — 검색어가 한글 초성으로만 구성되면 (`[ㄱ-ㅎ]+`) title/artist 초성 파생값과 prefix 매칭. 혼합 입력 (초성 + 완성형) 은 완성형 경로 우선.
- [ ] **자동완성 제안 endpoint** — `GET /api/v1/songs/suggest?q=&limit=` — 경량 top-N 제안 (`{id, title, artist}`). 검색 box debounce 소비.
- [ ] **난이도 필터** — `difficulty` (CSV, `EASY`/`NORMAL`/`HARD`). 그룹 OR. null-난이도 곡은 필터 활성 시 제외.
- [ ] **분위기 필터** — `mood` (CSV, `Mood` enum). 그룹 OR. null-mood 곡 제외.
- [ ] **음역 적합 필터** — `fitLow`/`fitHigh` (MIDI [12,119], both-or-neither). 곡이 `lowMidi >= fitLow AND highMidi <= fitHigh` (= 부를 수 있는 곡) 일 때 매칭. **sessionId 비의존** — fe 가 사용자 `VoiceRange` 를 읽어 explicit 파라미터로 전달 (endpoint 의 익명·무상태 유지).
- [ ] **검색 색인** — `LIKE '%kw%'` full scan 을 인덱스 활용 가능한 구조로 전환 (MySQL 8.4 FULLTEXT + ngram parser 또는 정규화 prefix 색인). 단계적 도입 (§5-7).
- [ ] **빈 keyword 정책 정합** — `keyword` 가 **명시되었으나 비/공백** 일 때만 빈 결과 (기존 회귀 가드 `SongServiceTest#searchByKeyword_emptyKeyword_returnsEmpty` 유지). `keyword` 미명시 + 필터만 있으면 필터 결과 반환.
- [ ] **fe URL query 동기화** — 검색어 + 모든 필터 + 정렬 + 페이지 = URL query. 새로고침 / 공유 / 뒤로가기 복원.

### 비기능 요구사항

- 검색 응답 p95 200ms 이내 (size=20, DB 100~수백곡 가정). `recommendation-p95-regression-guard.md` 와 동일 budget.
- 자동완성 제안 p95 100ms 이내 (debounce 소비 — 더 엄격). `limit` default 8, max 20.
- 페이지네이션 default `size=20`, max `size=100` (DoS 가드, 둘러보기 spec 과 동일 상수).
- 검색 색인은 곡 추가/수정/삭제 시점에만 갱신 — 읽기 경로 무잠금.
- 외부 API 호출 없음. 신규 endpoint 모두 익명 / public. PII 무 (사용자 음역대 값이 `fitLow`/`fitHigh` 로 들어오나 **로그 원문 노출 금지** — `04-security-policy.md`, 집계 메트릭만).
- 추천 결정성 회귀 0 — `RecommendationDeterminismTest` 변경 없이 green.

## 4) 범위 / 비범위 (중요)

### 포함

- `GET /api/v1/songs` 의 **검색·필터 진화** (관련도 정렬 + `difficulty`/`mood`/`fitLow`/`fitHigh` 필터 + 초성).
- 신규 `GET /api/v1/songs/suggest` 자동완성 endpoint.
- 검색 색인 전략 (FULLTEXT ngram + 초성 파생 컬럼) + 단계적 마이그레이션.
- fe `/songs` 검색 page 의 검색 box + 자동완성 드롭다운 + 필터 패널 + URL query 동기화.
- 곡 카드 = 기존 `SongCard` / `SongDetailModal` **재사용**.

### 제외 (Out of Scope)

- **장르 인덱스 / 둘러보기 page** (`/songs/catalog`) — `song-catalog-genre-browse.md` 소유. 본 spec 은 그 spec 의 `genre`/`decade`/`language` 필터 + `SongListResponse` wrapper + `Genre` enum promote 를 **소비/공유** 만 (재정의 X).
- **`Genre` enum promote 마이그레이션** — 둘러보기 spec 의 PR 2. 본 spec 은 enum 존재를 전제하되 직접 정의하지 않는다. (둘러보기 PR 2 와 본 spec BE PR 의 머지 순서는 §6 에 명시.)
- **추천 알고리즘 / 음역 적합도 점수** — `RecommendationService` / `ScoreBreakdown` 무관. 본 spec 의 `fitLow`/`fitHigh` 는 **boolean 필터** (부를 수 있나 없나) 이지 점수가 아니다. 추천의 `rangeFit` (0~1 연속) 과 다른 차원.
- **검색어 기반 추천** ("이 곡 비슷한 곡") — 트렌딩/추천 spec 영역.
- **검색 로그 분석 / 인기 검색어** — v0.3+ 후보 (검색 히스토리 영속 없음).
- **오타 교정 / 유사어 (fuzzy / Levenshtein)** — v0.3+ 후보. v0.2 는 prefix + ngram + 초성까지만.
- **다국어 검색 (영문 음차 ↔ 한글)** — "ed sheeran" ↔ "에드 시런" 매핑은 v0.4 메타데이터 보강 후 후보.
- **관리자 곡 등록 / 수정 UI** — 시드 JSON 직접 편집 유지.

## 5) 설계

### 5-1) 도메인 모델

#### 신규 / 보강 용어 (후보 — `06-domain-model.md §4-1` 등재는 **구현 PR 에서 동기 갱신**)

> `song-catalog-genre-browse.md §5-1` 과 동일 컨벤션: spec 단계에서는 후보 정의만 두고, 실제 §4 등재는 코드 도입 PR 에서 같은 PR 으로 수행 (용어가 리뷰 중 변할 수 있어 draft 단계 §4 오염 방지). 신규 용어가 코드에 들어가기 전 §4 등재 의무는 유지.

| 한국어 | 영어 (코드) | 패키지 | 정의 |
|---|---|---|---|
| 곡 검색 | SongSearch | `song` | 제목/가수 텍스트 + 다축 필터로 곡을 찾는 read-only 유스케이스. `GET /api/v1/songs` 진화형의 검색 모드. |
| 검색 적합도 | SearchRelevance | `song` | 검색 결과 정렬 신호. tier (제목 정확 > 제목 prefix > 제목 부분 > 가수) + 동 tier 가나다순. **추천 score 와 무관** — 조회 전용. |
| 검색 색인 | SongSearchIndex | `song` | 검색 성능 인프라 — FULLTEXT(ngram) 인덱스 + 초성 파생 컬럼. 영속 엔티티 아님 (`Song` 컬럼/인덱스). |
| 초성 검색 | ChosungSearch | `song` | 한글 초성열 (`ㅂㄹㄷ`) 로 title/artist 초성 파생값과 prefix 매칭하는 검색 모드. |
| 검색 제안 | SearchSuggestion | `song` | 자동완성 경량 결과 1건 (`id`/`title`/`artist`). `GET /api/v1/songs/suggest` 응답 item. |
| 음역 적합 필터 | VoiceFitFilter | `song` | `fitLow`/`fitHigh` MIDI 구간으로 "부를 수 있는 곡" 만 거르는 boolean 필터. **sessionId 비의존** (explicit param) — 추천의 `rangeFit` 연속 점수와 다른 차원. |

#### `Song` 엔티티 변경 (`06-domain-model.md §5-2`)

| 필드 | 현재 | 변경 후 | 비고 |
|---|---|---|---|
| `titleChosung` | (없음) | `String(200)` nullable, index | title 의 초성 파생 (`발라드` → `ㅂㄹㄷ`). insert/update 시 `Song` 도메인에서 파생. 초성 검색용. |
| `artistChosung` | (없음) | `String(200)` nullable, index | artist 의 초성 파생. |

- 신규 컬럼은 **파생값** — 사용자 입력 아님. `Song.create()` / 시드 적재 / 마이그레이션 backfill 에서 `ChosungDeriver.of(title)` 로 채운다.
- `lowMidi`/`highMidi`/`difficulty`/`mood`/`genre`/`language` 등 **기존 필터 대상 컬럼은 변경 없음** — 본 spec 은 검색·필터 *읽기* 추가이지 필터 대상 메타데이터 스키마 변경이 아니다 (`genre` enum promote 는 둘러보기 spec 소유).

### 5-2) API 엔드포인트

| Method | Path | 설명 | 인증 | Req | Res |
|---|---|---|---|---|---|
| GET | `/api/v1/songs` (진화) | 검색 + 다축 필터 + 관련도 정렬 + 페이지네이션. | 익명 | query | `SongListResponse(items, page, size, totalCount, hasNext)` |
| GET | `/api/v1/songs/suggest` | 자동완성 경량 제안 top-N. | 익명 | `q`, `limit` | `SearchSuggestResponse(items)` — `items: {id,title,artist}[]` |

#### `GET /api/v1/songs` 쿼리 파라미터 (본 spec 가산분 **굵게**)

| 파라미터 | 타입 | 기본 | 검증 | 설명 |
|---|---|---|---|---|
| `keyword` | string | (없음) | 1~50자 | title/artist 검색. 초성열이면 초성 경로. (기존 호환 — 명시+비/공백 = 빈 결과) |
| `genre` | string (CSV) | (없음) | enum literal | 둘러보기 spec 공유. 그룹 OR. |
| `decade` | string (CSV) | (없음) | `90s`/`00s`/`10s`/`20s`/`OTHER` | 둘러보기 spec 공유. |
| `language` | string (CSV) | (없음) | `ko`/`en`/`ja`/`zh`/`OTHER` | 둘러보기 spec 공유. |
| **`difficulty`** | **string (CSV)** | **(없음)** | **`EASY`/`NORMAL`/`HARD`** | **그룹 OR. null-난이도 곡 제외.** |
| **`mood`** | **string (CSV)** | **(없음)** | **`Mood` enum** | **그룹 OR. null-mood 곡 제외.** |
| **`fitLow`** | **int** | **(없음)** | **12~119, `fitHigh` 와 both-or-neither** | **음역 적합 하한 (MIDI).** |
| **`fitHigh`** | **int** | **(없음)** | **12~119, ≥ `fitLow`** | **음역 적합 상한. 곡이 `lowMidi>=fitLow AND highMidi<=fitHigh` 일 때 매칭. null-range 곡 제외.** |
| `sort` | enum | `keyword` 있으면 `relevance`, 없으면 `title` | **`relevance`**/`title`/`releaseYear`/`popularity` | **`relevance` 는 `keyword` 필수 (없으면 400 또는 `title` fallback — Q4).** |
| `order` | enum | `asc` | `asc`/`desc` | `relevance` 는 항상 desc (높은 적합도 우선) — `order` 무시. |
| `page` | int | `0` | ≥ 0 | offset-based. |
| `size` | int | `20` | 1 ≤ size ≤ 100 | DoS 가드. |

- **모든 필터는 AND 결합, 같은 그룹 내 CSV 는 OR** (예: `difficulty=EASY,NORMAL&mood=CALM` = (EASY OR NORMAL) AND CALM).
- 응답은 둘러보기 spec 의 `SongListResponse` wrapper 와 **동일 형태** (`items` = 기존 `SongResponse[]`, `page`/`size`/`totalCount`/`hasNext`).

#### `GET /api/v1/songs/suggest`

```json
// GET /api/v1/songs/suggest?q=발라&limit=8
{
  "items": [
    { "id": 12, "title": "발라드 가사 없는 노래", "artist": "..." },
    { "id": 34, "title": "...",                  "artist": "발라드보이즈" }
  ]
}
```

- `q` 1~50자 (필수 — 없거나 공백이면 200 + 빈 items). `limit` 1~20 (default 8).
- 정렬 = `SearchRelevance` (제목 prefix 우선). 초성열 `q` 면 초성 prefix.
- **경량** — `SongResponse` 전체 (앨범커버/음역/난이도) 가 아닌 `{id,title,artist}` 만. 카드 상세는 결과 클릭 시 `/songs/{id}` 또는 결과 그리드 item 으로.

### 5-3) 외부 연동

- 없음.

### 5-4) 데이터 흐름 / 시퀀스

```
[fe] /songs 검색 box 입력 "발라" (debounce 250ms)
   ↓
GET /api/v1/songs/suggest?q=발라&limit=8     ← 경량, p95 100ms
   ↓
[fe] 자동완성 드롭다운
   ↓ (엔터 또는 제안 클릭)
[fe] /songs?keyword=발라&difficulty=EASY&sort=relevance&page=0
   ↓
GET /api/v1/songs?keyword=발라&difficulty=EASY&sort=relevance&page=0&size=20
   ↓
[BE] SongSearchService
      ├ keyword 초성열? → ChosungSearch (titleChosung/artistChosung prefix)
      │  아니면 → FULLTEXT MATCH(title,artist) (또는 정규화 LIKE — §5-7 단계)
      ├ 필터 AND: genre / decade / language / difficulty / mood / fitLow~fitHigh
      └ SearchRelevance tier 정렬 + 페이지네이션
   ↓
[fe] SongCard grid + pagination footer
   ↓ (카드 클릭)
[fe] SongDetailModal (기존 재사용)
```

추천 / sessionId 흐름과 **완전 격리** — `RecommendationService` / `VoiceRangeRepository` 호출 0. `fitLow`/`fitHigh` 는 fe 가 로컬 `VoiceRange` 를 explicit param 으로 전달 (BE 는 sessionId 모름).

### 5-5) DB 마이그레이션

> 본 spec 의 마이그레이션은 **검색 색인 + 초성 파생 컬럼** 2종. `genre` enum promote 마이그레이션은 둘러보기 spec 소유 (중복 정의 X).

```sql
-- V<n>__add_song_chosung_columns.sql
ALTER TABLE song ADD COLUMN title_chosung  VARCHAR(200) NULL;
ALTER TABLE song ADD COLUMN artist_chosung VARCHAR(200) NULL;
-- backfill: 애플리케이션 1회성 backfill (한글 초성 추출 로직 = Java ChosungDeriver,
--   SQL CASE 로 표현 불가 → @PostConstruct 또는 Flyway Java migration 으로 채운다).
CREATE INDEX ix_song_title_chosung  ON song (title_chosung);
CREATE INDEX ix_song_artist_chosung ON song (artist_chosung);

-- V<n+1>__add_song_fulltext_index.sql  (FULLTEXT 채택 시 — §5-7 Phase 2)
CREATE FULLTEXT INDEX ft_song_title_artist ON song (title, artist) WITH PARSER ngram;
```

- **초성 backfill 은 Java migration** — 한글 자모 분해는 SQL CASE 로 불가하므로 Flyway Java-based migration (`V<n>__Backfill...java`) 또는 부팅 1회성 `ChosungBackfillRunner` (`@Profile("!test")`, 멱등). `ChosungDeriver` 가 SoT.
- **FULLTEXT 는 Phase 2 분리** (§5-7) — v0.2 카탈로그 규모(~수백곡)에서는 정규화 LIKE + app-side relevance tier 로도 p95 budget 충족 가능. FULLTEXT 인덱스는 곡 수 증가 시점 또는 ngram 필요성 확정 후 별 PR.
- ERD 갱신 (`06-domain-model.md §6`) — `song` 에 `title_chosung`/`artist_chosung` 컬럼 추가.

### 5-6) 프론트엔드 화면

#### 기존 경로 `/songs` (검색 page 진화)

- **검색 box (상단 고정)** — 입력 시 250ms debounce → `GET /songs/suggest` → 드롭다운. ↑/↓ 키 네비 + 엔터/클릭 선택. 초성 입력도 그대로 전달 (BE 가 초성열 판별).
- **필터 패널** (모바일 = 하단 bottom-sheet / desktop = 좌측 sidebar) — chip multi-select:
  - 장르 (둘러보기 enum 공유) / 난이도 (EASY/NORMAL/HARD) / 분위기 (`Mood`) / 발매 시대 / 언어.
  - **음역 적합 토글** — "내 음역대에 맞는 곡만". 켜면 fe 가 로컬 `VoiceRange` 를 `fitLow`/`fitHigh` 로 주입. `VoiceRange` 미보유 시 토글 disabled + "음역대를 먼저 입력하세요" 안내 (음역 입력 page 링크).
- **정렬 dropdown** — 관련도순 (keyword 있을 때만 노출) / 가나다순 / 발매일 / 인기순.
- **결과 grid** — `SongCard` 재사용 + 페이지네이션 footer. 결과 0건 시 빈 상태 ("'<keyword>' 결과가 없어요" + 필터 초기화 CTA).
- **URL query 동기화** — `useSearchParams` + `router.replace` (히스토리 더럽힘 X). 둘러보기 page 와 동일 패턴.
- 곡 카드 클릭 → 기존 `SongDetailModal` 재사용.

#### 진입점 / mental model bridge

- 헤더 검색 bar = `/songs` (찾기). 둘러보기 page (`/songs/catalog`) 헤더에 "검색으로" 링크 (둘러보기 spec §5-6 의 역방향 링크와 쌍).
- 랜딩 (`/`) 의 기존 검색 진입 유지 — "곡 검색" + "장르별 둘러보기" 두 CTA 병치.

### 5-7) 검색 색인 전략 (핵심)

현재 `LIKE '%kw%'` 는 leading wildcard 라 인덱스를 못 타고 full table scan 이다. 곡 수가 늘면 검색·자동완성이 선형 악화된다. **단계적 도입**:

| Phase | 색인 | 검색 방식 | 트리거 |
|---|---|---|---|
| **Phase 0 (현재)** | 없음 | `LIKE '%kw%'` 풀스캔 | — |
| **Phase 1 (본 spec BE PR)** | 초성 파생 컬럼 + index | 완성형 = 정규화 LIKE (lower/trim) + **app-side relevance tier 정렬**, 초성열 = `titleChosung LIKE 'ㅂㄹㄷ%'` (prefix → index 활용) | v0.2 (~수백곡) |
| **Phase 2 (후속 PR — 곡 수 ↑ 또는 ngram 필요 확정 시)** | `FULLTEXT ... WITH PARSER ngram` | `MATCH(title,artist) AGAINST(:q IN BOOLEAN MODE)` + FULLTEXT relevance score | 카탈로그 N곡 초과 또는 부분어 검색 품질 이슈 |

설계 근거:

- **초성은 prefix 검색** — `titleChosung LIKE 'ㅂㄹㄷ%'` 는 leading wildcard 가 없어 B-tree index 를 탄다. `%ㅂㄹㄷ%` (중간 초성) 는 v0.2 제외 (Q5).
- **완성형 Phase 1 은 LIKE 유지** — v0.2 규모에서 풀스캔 비용이 p95 200ms 안. **단, relevance tier 정렬을 app-side 로 추가** (정확>prefix>부분>가수). 둘러보기 spec 의 `title` 가나다순과 달리 검색은 적합도 우선.
- **FULLTEXT ngram 은 Phase 2** — MySQL 8.4 한글 word-boundary 부재 → ngram(token_size 기본 2) 필요. 그러나 ngram 은 1글자 검색 / 짧은 토큰에서 noise + min token 제약이 있어, v0.2 소규모에서는 LIKE 가 오히려 예측가능. **곡 수 증가 곡선을 메트릭으로 관측 후 도입** (`10-observability.md` 검색 latency 메트릭).
- **`SearchRelevance` 결정성** — 동 tier 내 가나다순 tie-break 으로 동점 곡 순서 안정 (페이지네이션 중복/누락 방지).

### 5-8) `SongSearchService` 책임 분리

- `SongService.searchByKeyword` (현 평면 List 반환) 는 **deprecated 경로** 로 두고, 신규 `SongSearchService` 가 wrapper 응답 + 다축 필터 + relevance 를 담당. (consumer = fe `/songs` 1군데뿐 → 같은 PR atomic 전환, 둘러보기 spec 의 wrapper breaking 결정과 동일 입장.)
- 필터 조합은 **JPA Specification 빌더** 또는 QueryDSL — `genre/decade/language/difficulty/mood/fitLow~fitHigh` 동적 조합. (둘러보기 spec 의 `SongRepository.findByFilters` 와 **같은 빌더 공유** — 두 spec 이 한 필터 엔진을 쓴다. 머지 순서상 먼저 가는 spec 이 빌더 신설, 나중 spec 이 필터 축 가산.)

## 6) 작업 분할 (예상 PR 리스트)

> **둘러보기 spec 과의 의존**: 본 spec 의 BE PR 은 둘러보기 spec 의 **PR 2(Genre enum promote) + PR 3(`SongListResponse` wrapper + 필터 엔진)** 머지 후 진행이 깔끔하다 (wrapper/enum/Specification 빌더 재사용). 둘러보기가 먼저 머지되지 않으면 본 spec PR 이 wrapper + 빌더를 신설하고 둘러보기가 가산하는 역순도 가능 — **먼저 가는 쪽이 골격 신설** (§5-8). nmae 가 두 spec 머지 순서 조율.

- [ ] **PR 1 (docs)** — 본 spec PR (현재).
- [ ] **PR 2 (feat:song)** — `titleChosung`/`artistChosung` 컬럼 + `ChosungDeriver` + Flyway 마이그레이션(컬럼 추가) + Java backfill + `Song.create()`/시드 적재 파생. `06-domain-model.md §4/§5/§6` 동기 갱신. 단위 테스트 (초성 추출 round-trip).
- [ ] **PR 3 (feat:song)** — `SongSearchService` + `GET /api/v1/songs` 검색·필터 진화 (`difficulty`/`mood`/`fitLow`/`fitHigh` + `sort=relevance` + 초성 분기) + Specification 빌더 (둘러보기와 공유) + `SongListResponse` 응답 + E2E (RestAssured).
- [ ] **PR 4 (feat:song)** — `GET /api/v1/songs/suggest` 자동완성 endpoint + `SearchSuggestResponse` DTO + relevance prefix 정렬 + E2E.
- [ ] **PR 5 (feat:web)** — `/songs` 검색 page 진화 (검색 box + 자동완성 드롭다운 + 필터 패널 + 음역 적합 토글 + 정렬 + URL query 동기화) + `web/lib/api/song.ts` wrapper/suggest 파싱.
- [ ] **PR 6 (perf:song, 후속 옵션 — Phase 2)** — `FULLTEXT ... WITH PARSER ngram` 인덱스 + `MATCH ... AGAINST` 검색 전환. 곡 수 증가 또는 검색 latency 메트릭 임계 초과 후 trigger.

### 보호 영역 변경 여부 (필수 명시)

- 보호 영역 변경 여부: ☑ **있음** — 변경 파일 (PR 단위):
  - **PR 2** — `backend/src/main/resources/db/migration/V<n>__add_song_chosung_columns.sql` (+ Java backfill migration). `**/db/migration/**` 정보성 보호 영역.
  - **PR 6 (후속)** — `backend/src/main/resources/db/migration/V<n+1>__add_song_fulltext_index.sql` (FULLTEXT 인덱스, DDL 비용 ↑ — 대용량 시 online DDL 가드 권고).
  - PR 5 — `web/package.json` / lockfile **변경 없음** 목표 (검색 box / 드롭다운 / debounce 는 기존 의존성으로 구현). 신규 의존성 발생 시 별 PR 분리.
  - 사유: 검색 = read-only. 스키마 변경은 초성 파생 컬럼 추가 (nullable, backfill 가능) + 후속 FULLTEXT 인덱스뿐.
- rev 가중도: 신중도 ↑ (Java backfill migration 1건 / FULLTEXT DDL 비용 1건 / wrapper breaking 1건 — 둘러보기와 공유).

## 7) 테스트 전략

### 단위

- `ChosungDeriver.of("발라드")` == `"ㅂㄹㄷ"`, 영문/숫자/공백 처리 (`"Day 6"` → 영문 그대로 또는 skip — Q6), 복합 (`"가나다 ABC"`).
- `SearchRelevance` tier 정렬 — 동일 keyword 에 대해 제목정확 > 제목prefix > 제목부분 > 가수 순 + 동 tier 가나다 tie-break 안정성.
- Specification 빌더 — 필터 조합 (genre+difficulty+mood+fit) 별 predicate + null-필터 무시.
- `fitLow`/`fitHigh` 경계 — `lowMidi==fitLow`/`highMidi==fitHigh` 포함, null-range 곡 제외, both-or-neither 검증 (한쪽만 → 400).
- 페이지네이션 boundary — page=0/size=20, size=1, size=100, size=101 (`IllegalArgumentException`).

### 통합 (testcontainers)

- 초성 컬럼 backfill 멱등 — 모든 row `title_chosung` 채워짐 + `ChosungDeriver` 결과와 일치.
- 초성 prefix 쿼리 index 사용 — `EXPLAIN` 에 `ix_song_title_chosung` range scan (Phase 1 회귀 가드).
- (Phase 2) FULLTEXT MATCH 결과가 LIKE 결과 superset/정합 — 동일 keyword 결과 ID 집합 비교.

### E2E (RestAssured — 신규 endpoint 의무)

- `GET /api/v1/songs?keyword=발라&sort=relevance` — 200 + items 모두 발라 포함 (title 또는 artist) + 제목 일치가 가수 일치보다 상위.
- `GET /api/v1/songs?keyword=ㅂㄹㄷ` (초성) — 200 + 초성 매칭 곡.
- `GET /api/v1/songs?difficulty=EASY&mood=CALM` (keyword 없이 필터만) — 200 + 모든 item difficulty=EASY AND mood=CALM.
- `GET /api/v1/songs?fitLow=48&fitHigh=64` — 200 + 모든 item `lowMidi>=48 AND highMidi<=64`, null-range 곡 미포함.
- `GET /api/v1/songs?fitLow=48` (fitHigh 누락) — 400 (both-or-neither).
- `GET /api/v1/songs?keyword=` (명시 + 빈) — 200 + 빈 items (기존 정책 유지).
- `GET /api/v1/songs/suggest?q=발라&limit=5` — 200 + items ≤ 5 + `{id,title,artist}` 만.
- `GET /api/v1/songs?size=101` — 400 (DoS 가드).

### 결정성 / 회귀 가드

- `RecommendationDeterminismTest` 변경 없이 green — 본 spec 변경이 추천 결과에 영향 0. rev 단계 1 grep 강제 (`SongSearch`/`Chosung` import 가 `recommendation` 패키지에 누락).
- 기존 `SongServiceTest#searchByKeyword_emptyKeyword_returnsEmpty` 정책 유지 (빈 keyword → 빈 결과).
- `RecommendationP95RegressionGuard` 영향 없음 (별 endpoint).

### fe 테스트

- `/songs` 검색 page snapshot (Playwright) — 검색 box + 필터 패널 + 결과 grid.
- 자동완성 debounce — 입력 후 250ms 내 1회 호출 (과다 호출 가드).
- URL query 복원 — `?keyword=발라&difficulty=EASY&page=1` 새로고침 후 동일 상태.
- 음역 적합 토글 — `VoiceRange` 미보유 시 disabled + 안내.

## 8) 오픈 질문

> 구현 전에 답이 나와야 하는 것들. 해소되면 §9 결정 로그로 이동.

| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | 검색 색인 Phase 1(LIKE+app relevance) 로 시작 vs 처음부터 FULLTEXT ngram | (a) Phase 1 시작, 메트릭 후 Phase 2 (현 spec), (b) 처음부터 FULLTEXT | @goohong / PR 3 직전 |
| Q2 | 초성 검색 범위 | (a) prefix 만 (`ㅂㄹㄷ%`, index 활용 — 현 spec), (b) 부분 초성 (`%ㅂㄹㄷ%`, full scan), (c) 초성 검색 v0.3 으로 보류 | @goohong / PR 2 직전 |
| Q3 | `fitLow`/`fitHigh` 매칭 의미 | (a) 곡 range 가 사용자 range 안에 완전 포함 (`lowMidi>=fitLow AND highMidi<=fitHigh`, 현 spec — "부를 수 있는"), (b) overlap 기반 (일부 겹침), (c) 상한만 (`highMidi<=fitHigh`, 최고음만 가드) | @goohong / PR 3 직전 |
| Q4 | `sort=relevance` + keyword 없을 때 | (a) `title` fallback (현 spec), (b) 400 reject, (c) relevance 옵션 자체를 keyword 있을 때만 노출 | @goohong / PR 3 직전 |
| Q5 | 부분 초성 (중간 매칭) | (a) v0.2 제외 (현 spec), (b) 포함 (full scan 감수) | @goohong / PR 2 직전 |
| Q6 | 초성 파생 시 영문/숫자 처리 | (a) 영문/숫자 원문 보존 (`"Day6"` → `"Day6"` 또는 초성열에 섞음), (b) 한글 자모만 추출 + 영문 skip, (c) 영문은 별 소문자 정규화 컬럼 | @goohong / PR 2 직전 |
| Q7 | 자동완성 제안 정렬 가중 | (a) 제목 prefix 최우선 (현 spec), (b) 인기도(popularity) 혼합, (c) 최근 검색 개인화 (v0.3) | @goohong / PR 4 직전 |
| Q8 | 둘러보기 spec 과 필터 엔진 / wrapper 머지 순서 | (a) 둘러보기 먼저(골격) → 본 spec 가산 (현 spec 권장), (b) 본 spec 먼저(골격) → 둘러보기 가산, (c) 공통 골격을 별 선행 PR 로 분리 | nmae / 두 spec PR 3 직전 |

## 9) 결정 로그

> 연대기 순. "YYYY-MM-DD: 결정 / 이유 / 출처(PR 번호 등)"

- 2026-06-03: 초안 작성 (status=draft). 사용자 directive (roadmap-search-plan, "직접 검색 + 필터로 아는 곡 찾기") 반영.
  - **"찾기"(검색) ↔ "둘러보기"(장르 인덱스) mental model 분리 유지**: 본 spec 은 `/songs` 검색 page 를, `song-catalog-genre-browse.md` 는 `/songs/catalog` 둘러보기를 소유. 두 spec 이 `SongListResponse` wrapper + 필터 엔진 + `Genre` enum 을 **공유**하되 정의는 둘러보기가, 검색 축(relevance/difficulty/mood/fit/초성)은 본 spec 이 소유.
  - **`fitLow`/`fitHigh` = boolean 필터, sessionId 비의존**: 추천의 `rangeFit` 연속 점수와 다른 차원. fe 가 explicit param 으로 음역 주입 → endpoint 익명·무상태 유지 (둘러보기 spec 의 "sessionId 의존 금지" 와 정합).
  - **검색 색인 단계적 도입 (Phase 1 LIKE+app relevance → Phase 2 FULLTEXT ngram)**: v0.2 소규모(~수백곡)에서 풀스캔이 p95 안. ngram 은 짧은 토큰 noise + min token 제약 → 곡 수 메트릭 관측 후 도입이 안전. 초성만 prefix index 로 선제 도입.
  - **초성 검색 prefix 한정**: `LIKE 'ㅂㄹㄷ%'` 는 index 활용. 중간 초성(`%ㅂㄹㄷ%`)은 v0.2 제외.
  - **wrapper response breaking 채택**: consumer (fe `/songs`) 1군데뿐 → 둘러보기 spec 의 wrapper breaking 결정과 동일 입장 (atomic 전환).
  - **추천 알고리즘 분리 strict**: 본 spec 어떤 변경도 `RecommendationService`/`SeedDeriver`/`RecommendationScorer` 영향 0. 결정성 회귀 가드 명시.

## 10) 관련 spec / ADR

- `docs/features/song-catalog-genre-browse.md` — **"둘러보기" 짝 spec**. `SongListResponse` wrapper / `Genre` enum promote / 필터 엔진 (`findByFilters`) / `genre`·`decade`·`language` 필터의 SoT. 본 spec 은 이를 공유하고 검색 축을 가산. 머지 순서 = Q8.
- `docs/features/song-metadata-source.md` — `Song` 엔티티 SoT. 본 spec 의 `titleChosung`/`artistChosung` 파생 컬럼 추가.
- `docs/features/song-curation-seed-100.md` — 시드 적재. 신규 초성 컬럼 시드 적재 시 동기 파생.
- `docs/features/recommendation-algorithm-v1.md` / `recommendation-algorithm-v2.md` — 추천 알고리즘. 본 spec 영향 0 (결정성 회귀 가드). `fitLow`/`fitHigh` boolean 필터 ≠ `rangeFit` 연속 점수.
- `docs/features/recommendation-p95-regression-guard.md` — p95 budget. 검색 200ms / 자동완성 100ms 채택.
- `docs/decisions/0005-package-structure.md` — Song aggregate ID-only 참조.
- `docs/decisions/0007-vocal-difficulty-classification.md` — `Difficulty` enum. 본 spec 의 `difficulty` 필터 대상.
- `docs/decisions/0009-schema-migration-tool.md` — Flyway. 초성 컬럼 + Java backfill migration + FULLTEXT 인덱스 패턴.

## 11) 자율 결정 (사유) — sub-agent 룰 §1-2

본 spec 작성 중 발생한 모호 분기 자율 결정 (`AskUserQuestion` 금지). 결정 / alternative / 사유 명시:

| # | 결정 | alternative | 사유 |
|---|---|---|---|
| A1 | `/songs` 검색 page 진화 (신규 page X) | `/songs/search` 신설 | `/songs` 가 이미 검색 진입점. 둘러보기(`/songs/catalog`)와 mental model 이 이미 분리됨 — 추가 라우트 불필요. |
| A2 | `fitLow`/`fitHigh` explicit param (sessionId 비의존) | `fitSessionId` 로 BE 가 `VoiceRange` 조회 | 둘러보기 spec 의 "endpoint sessionId 의존 금지" 정합 + endpoint 무상태/익명 유지 + 추천과의 결합 회피. fe 가 로컬 음역 주입. |
| A3 | 검색 색인 Phase 1(LIKE)→Phase 2(FULLTEXT) | 처음부터 FULLTEXT ngram | v0.2 소규모에서 풀스캔이 budget 안 + ngram 짧은 토큰 noise. 메트릭 관측 후 도입이 over-engineering 회피. |
| A4 | 초성 prefix 한정 + 파생 컬럼 | app-side 자모 분해 매 쿼리 / 중간 초성 | prefix LIKE 는 index 활용 (성능). 매 쿼리 분해는 풀스캔. 중간 초성은 v0.2 가치 < 비용. |
| A5 | 필터 엔진 둘러보기와 공유 | 검색 전용 별 빌더 | 같은 `Song` 다축 동적 필터 → 빌더 2개는 drift 위험. 먼저 가는 spec 이 신설, 나중이 가산 (Q8). |
| A6 | `fitLow`/`fitHigh` = 완전 포함 (부를 수 있는) | overlap / 상한만 | "내 음역대에 맞는 곡" = 끝까지 부를 수 있어야 직관적. overlap 은 못 부르는 고음 곡 포함 → 사용자 기대 위배. (Q3 으로 재확인 여지.) |

follow-up:

- Phase 2 FULLTEXT ngram 전환은 검색 latency 메트릭 (`10-observability.md`) 임계 초과 후 별 PR — 곡 수 증가 곡선 관측 필요.
- 오타 교정 / fuzzy / 다국어 음차 검색은 v0.3+ 후보 (§4 비범위).
