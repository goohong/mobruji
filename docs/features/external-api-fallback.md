---
feature: 외부 API graceful fallback (Spotify / MusicBrainz / YouTube / iTunes 횡단)
slug: external-api-fallback
status: draft
owner: @goohong
scope: song
related_issues: []
related_prs: [786, 939]
last_reviewed: 2026-05-24
---

# 외부 API graceful fallback (Spotify / MusicBrainz / YouTube / iTunes 횡단)

## 1) 개요 (What / Why)
mobruji 는 4개 외부 API 에 의존한다: **Spotify** (audio features), **MusicBrainz** (metadata + ISRC), **YouTube/yt-dlp** (audio source for self-analysis), **iTunes Search** (album cover 1차). 각 API 는 자체 spec 에서 개별 graceful 처리를 정의하지만 — **사용자 facing 추천 흐름 전체의 fallback 정책 종합 spec 부재**. 추천 API 호출 시점에 Spotify down + MusicBrainz down + 캐시 miss 가 동시 발생하면 어떤 응답을 주는가? 어떤 외부 API down 이 사용자 노출 / 어떤 게 backfill 영역? — 이 횡단 결정을 한 spec 에 모은다.

대상 액터: end user (추천 요청자), ops (장애 대응자).

## 2) 사용자 시나리오
- **시나리오 A (Spotify rate limit)**: backfill job 도중 Spotify 가 429 반환 → 신규 곡 `valence`/`energy` null → 사용자는 추천을 받지만 `moodMatch` 가 중립 0.5 fallback → 추천 품질 약간 저하, 사용자 인지 못함, 응답 200.
- **시나리오 B (MusicBrainz down)**: anonymous 사용자 추천 요청 → 추천 응답에 MusicBrainz 비의존 (backfill 만 영향) → 사용자 영향 0, 응답 200.
- **시나리오 C (DB catalog empty + 모든 외부 API down)**: cold start 직후 / catastrophic — 추천 후보 0건 → **hard fail (400 NO_CANDIDATES)** 와 명시적 사용자 메시지 ("일시적으로 추천할 곡이 없어요. 잠시 후 다시 시도해 주세요.").
- **시나리오 D (YouTube 차단)**: self-analysis 파이프라인 YouTube 추출 실패 → 곡 `analysis_status=FAILED` → 해당 곡 추천 제외 → 사용자에게는 다른 곡이 노출됨, 응답 200.

## 3) 요구사항
### 기능 요구사항
- [ ] **외부 API 분류표** — 각 외부 API 에 대해 "추천 응답 동기 의존" / "backfill 비동기 의존" 여부 명시 (§4 표).
- [ ] **추천 응답 동기 의존 API 가 down 일 때 정책** — 현재는 **0건** (모든 외부 API 가 backfill 영역). 신규 동기 의존 API 도입 시 반드시 본 spec §3 옵션 C(graceful) 적용.
- [ ] **catalog 0건 hard fail** — DB 추천 후보 0건 일 때 응답: `HTTP 400 NO_CANDIDATES`, body `{ "code": "NO_CANDIDATES", "message": "일시적으로 추천할 곡이 없어요. 잠시 후 다시 시도해 주세요." }`. fe 는 이 코드 받으면 재시도 버튼 노출.
- [ ] **fallback 정책 카탈로그 단일 출처** — 각 외부 API spec 의 graceful 섹션은 본 spec 으로 링크 (drift 방지).
- [ ] **장애 메트릭 통일** — `mobruji.external.{spotify|musicbrainz|youtube|itunes}.request{outcome=success|notfound|ratelimited|error|circuit_open}` — outcome 라벨 enum 통일.

### 비기능 요구사항
- **응답 시간** — 추천 API p95 ≤ 500ms (외부 API 동기 호출 0건 보장이 전제. 새 동기 의존 도입 시 본 spec 갱신 + ADR 필수).
- **결정성** — 외부 API down 으로 인한 fallback 분기는 결정적이어야 함 (random 금지). `valence`/`energy` null → 0.5 중립은 deterministic.
- **관측성** — 외부 API 별 outcome 카운터 + duration timer. `mobruji.recommendation.fallback{reason=spotify_missing|musicbrainz_missing|youtube_unavailable|catalog_empty}` 카운터로 사용자 영향 가시화.
- **알림 임계값** — `outcome=error` rate > 5% (5분 window) 시 Discord #모부르지 push (observability-baseline.md §6 연동).
- **설정 외부화** — circuit breaker 임계값 / cache TTL / retry 횟수 — 모두 `application.yml`.

## 4) 범위 / 비범위
### 포함
- 4개 외부 API (Spotify / MusicBrainz / YouTube / iTunes) 의 fallback 정책 종합 표.
- 추천 응답 흐름에서 외부 API down 시 사용자 노출 분류.
- catalog 0건 hard fail 응답 표준화.
- 메트릭 outcome 라벨 enum 통일.

### 제외 (Out of Scope)
- 개별 외부 API client 구현 디테일 — 각 spec (`spotify-audio-features-integration.md`, `musicbrainz-integration.md`, `audio-tooling-bootstrap.md`) 에 잔존.
- Redis 캐시 도입 — 현재는 DB 영속 만으로 충분 (모든 외부 API 가 backfill). 미래 동기 의존 추가 시 별 spec.
- 분산 circuit breaker — 단일 인스턴스 가정. 다중 인스턴스 도입 시 별 spec.
- Anthropic API (maestro) fallback — `ncp-maestro-resilience.md` 소관.

## 5) 설계

### 5-1) 외부 API 분류표 (단일 출처)

| API | 추천 응답 동기 의존? | 영향 영역 | down 시 사용자 노출 | fallback 정책 |
|---|---|---|---|---|
| Spotify (audio-features) | **No** (backfill) | `valence`/`energy` 컬럼 | 없음 (점수 0.5 중립 fallback) | rate limit `Retry-After` 존중, backoff (1→2→4s), 3회 fail → job skip 다음 사이클 |
| MusicBrainz (recording search) | **No** (backfill) | `isrc`/`mbid` 컬럼 | 없음 | 1.1s throttle, 503 → backoff 3회, 그 후 job 중단 |
| YouTube (yt-dlp self-analysis) | **No** (backfill) | `analysis_status` | 없음 (해당 곡 추천 제외) | 403/410 → `analysis_status=FAILED`, 재시도 큐 최대 3회 |
| iTunes Search (album cover) | **No** (backfill) | `album_cover_url` | placeholder 이미지 | 5xx/timeout → `Optional.empty()` 다음 곡 |

**핵심 불변**: 모든 외부 API 가 backfill 영역. **추천 응답 자체는 외부 API down 에 영향받지 않음** (catalog 0건 제외). 이 불변이 깨지는 신규 동기 의존 도입 시 ADR + 본 spec 갱신 필수.

### 5-2) 추천 응답 fallback 분기 의사결정 트리

```
추천 요청 → SongRepository.findAll() 후보
  ├─ 후보 ≥ 1건 → ScoreEvaluator (Spotify null → 0.5 중립) → 응답 200
  └─ 후보 0건  → HTTP 400 NO_CANDIDATES (hard fail, 사용자 메시지 명시)
```

### 5-3) 옵션 비교 (의사결정 기록)

- **옵션 A (hard fail)**: 외부 API 1개라도 down → "추천 불가". → **기각** — 사용자 경험 최악, 4개 API 동시 down 확률 낮음.
- **옵션 B (cached 결과 fallback)**: Redis/DB 최근 캐시. → **현재 기각** — 모든 외부 API 가 이미 backfill (DB 영속) → DB 자체가 캐시. 별도 Redis 불필요.
- **옵션 C (단계 graceful)**: API 별 individual fallback + 최종 catalog 0건만 hard fail. → **채택**.

옵션 C 채택 근거: 모든 외부 API 가 backfill 패턴이라 추천 응답 동기 의존 0건. 사용자 노출 최소화 (점수 신호 일부 결측 → 중립 0.5).

### 5-4) 영향 코드 / 파일

- `backend/.../recommendation/application/RecommendationService.java` — catalog 0건 hard fail 분기 (현재 동작 검증 필요).
- `backend/.../recommendation/api/RecommendationErrorCode.java` (신규) — `NO_CANDIDATES` enum + 메시지.
- `backend/.../song/application/albumcover/AlbumCoverLookupClient.java` — 이미 graceful (Optional.empty).
- `backend/.../song/application/AudioAnalysisRunner.java` — 이미 `analysis_status=FAILED` 마킹.
- 미구현: `SpotifyClient`, `MusicBrainzClient` (각 spec 의 PR B 단계).

### 5-5) 비기능 - 관측성 메트릭 표준

| 메트릭 | 라벨 | 의미 |
|---|---|---|
| `mobruji.external.{api}.request` counter | `outcome={success,notfound,ratelimited,error,circuit_open}` | API 호출 결과 |
| `mobruji.external.{api}.request.duration` timer | (없음) | API 호출 지연 |
| `mobruji.recommendation.fallback` counter | `reason={spotify_missing,musicbrainz_missing,youtube_unavailable,catalog_empty}` | 사용자 영향 분류 |

## 6) PR 분할 계획

- [ ] **PR A** (be, scope:song): `RecommendationErrorCode.NO_CANDIDATES` enum + `RecommendationService` 0건 분기 명시화 + 단위/E2E 테스트 (catalog 0건 → 400). 현재 동작이 NPE/500 이면 회귀, 200 empty list 면 contract 변경.
- [ ] **PR B** (be, scope:song): `mobruji.recommendation.fallback{reason=...}` 카운터 도입 + 기존 0.5 중립 fallback 분기에 instrument.
- [ ] **PR C** (be, scope:infra): 메트릭 outcome 라벨 enum (`ExternalApiOutcome`) 도입, 기존 `AlbumCoverLookupClient` 등 instrument 통일.
- [ ] **PR D** (docs, scope:song): 각 외부 API spec (`spotify-...`, `musicbrainz-...`, `audio-tooling-bootstrap`) 의 graceful 섹션을 본 spec 으로 링크 (drift 방지).

순서: PR A → B → C → D. PR A 가 사용자 noticeable (400 응답 contract). PR B/C/D 는 관측성 / 문서 정리.

## 7) 테스트 계획

- **단위** — `RecommendationService` 0건 시 `NoCandidatesException` throw, error code 매핑.
- **E2E (RestAssured)** — 빈 catalog 상태에서 `POST /recommendations` → 400 + `{ code: "NO_CANDIDATES" }`. Spotify null 컬럼 곡만 있을 때 → 200 + `breakdown.moodMatch=0.5` (결정성 검증).
- **회귀** — 기존 추천 E2E 가 catalog seed 후 실행되는지 확인 (테스트 격리).

## 8) 오픈 이슈

| 번호 | 질문 | 옵션 | 결정 시점 |
|---|---|---|---|
| Q1 | catalog 0건 응답 코드: 400 vs 503 vs 200 empty? | (a) **400 NO_CANDIDATES** (도메인 에러, 권장) / (b) 503 (인프라 의미) / (c) 200 + empty list | PR A 직전 |
| Q2 | 신규 동기 의존 외부 API 도입 시 본 spec 갱신을 강제하는 게이트? | (a) ADR 필수 + 본 spec 표 갱신 PR 동봉 / (b) 사후 spec 만 | 신규 의존 도입 시 |
| Q3 | `circuit_open` outcome 은 실제 circuit breaker 도입 후 채택? | (a) 현재는 enum 만 정의, 미사용 / (b) Resilience4j 도입 spec 분리 | PR C 직전 |

## 9) 관련 문서

- `docs/features/spotify-audio-features-integration.md` §3-4 (graceful degradation)
- `docs/features/musicbrainz-integration.md` §3-4 (rate limit + graceful)
- `docs/features/audio-tooling-bootstrap.md` §실패 fallback (yt-dlp)
- `docs/features/observability-baseline.md` §5 (메트릭 카탈로그)
- `docs/features/ncp-maestro-resilience.md` (Anthropic API 별 영역)
