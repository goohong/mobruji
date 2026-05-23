---
feature: 추천 알고리즘 v1 (recommendation-algorithm-v1)
slug: recommendation-algorithm-v1
status: shipped
owner: "@goohong"
scope: recommendation
related_issues: [5, 19]
related_prs: [6, 20]
last_reviewed: 2026-05-23
---

# 추천 알고리즘 v1 (recommendation-algorithm-v1)

## 1) 개요 (What / Why)
- mobruji의 본체 기능. 사용자 음역대 + (선택) 성별/분위기를 입력으로 받아 **"부르기 좋은 곡 N개"** 를 반환한다.
- `docs/ai-harness/06-domain-model.md §7 D3`를 다룬다.
- 대상 액터: 노래방 직전 사용자. 응답이 빠를수록(<1s) 좋다.
- 본 spec은 **v1**: 단순·해석 가능·빠른 베이스라인을 만드는 게 목적. v2+에서 임베딩/LLM 도입은 별도 spec.

## 2) 사용자 시나리오
1. **기본** — 음역대 입력 후 "추천 받기" 클릭 → 5~10곡 리스트가 곡명/아티스트/매칭 근거와 함께 노출.
2. **재추천** — 결과가 마음에 안 들면 "다시" 버튼 → 같은 입력에 대해 다른 슬라이스의 곡을 보여준다.
3. **분위기 필터** — (선택) 신남/잔잔/감성 등 한두 개 필터 적용 후 다시 추천.

## 3) 요구사항
### 기능 요구사항
- [x] 입력: `VoiceRange`(필수) + ~~`gender`(선택)~~ + `mood`(선택, v1 단일 값) + `excludeSongIds`(재추천 시). gender/moods[] 는 §9 결정 로그에 따라 v1에서 제외, `mood`는 단일 enum으로 축소.
- [x] 출력: `RecommendationResponse` — `List<RecommendedSong>` (곡 + score + matchReason 텍스트 + breakdown 5신호) + `requestId`.
- [x] 결과 곡 수: 기본 10곡(설정 가능). `recommendation.result-count` 프로퍼티.
- [x] 매칭 근거(`matchReason`)는 한 줄 한국어 문장으로 사용자에게 노출 가능한 수준이어야 한다 (예: "원곡 키가 사용자 음역대 안에 있음").
- [x] **점수 신호 분해(`breakdown`)** — Spotify "Why this song?" UX 영감(P2). 가중치 적용 전 raw 신호 5종(`keyMatch`/`rangeFit`/`genreMatch`/`moodMatch`/`popularity`, 각 0~1)을 응답에 노출해 fe 14 matchReason 펼침 UX를 backend가 정확히 채우게 한다. 영속 엔티티에는 저장되지 않으므로 `GET /recommendations/{id}` 재조회 경로의 `breakdown`은 `null`.
- [x] `RecommendationRequest`와 결과는 영속화한다(이력/분석). `excludeSongIds`는 별 join table `recommendation_request_exclude_song`에 영속(PR #74, closes #72/#73).

### 비기능 요구사항
- p95 응답 200ms / p99 400ms 이내 (DB 100~수백곡 카탈로그 가정). **임계 단일 진실: `docs/features/recommendation-p95-regression-guard.md` §5-3** (PR #471, closes #273 — 200ms/400ms 단일 진실 박제). 본 spec 은 참조만, 직접 숫자 갱신 금지. 의도된 변화 시 p95-regression-guard §6 baseline 갱신 절차로만 변경 가능. 회귀 가드는 k6 + GH Actions (`scripts/load/recommendation.k6.js` + `.github/workflows/load-test.yml`).
- **결정성 (단일 진실)** — 같은 입력 → 같은 결과 (추천 후보 ID 순서 + top score 동일). 디버깅·이슈 재현용. 단 "재추천" 흐름은 `excludeSongIds`가 입력에 포함되므로 같은 입력으로 간주되지 않는다.
  - **seed 계약**: `SeedDeriver.derive(sessionId, voiceRangeLow, voiceRangeHigh, mood, preferredBpm, excludeSongIds)` 입력에 결정성에 영향을 주는 **모든** 요청 필드가 포함되어야 한다 (PR #48 / #64 / #74 / #223). 새 입력 필드 추가 시 SeedDeriver 입력 시그니처도 같은 PR에서 확장한다. 누락 = "다시 버튼이 같은 결과 반환" 회귀.
  - **비결정 호출 금지**: 추천 파이프라인(`com.mobruji.recommendation.application.*`) 내부에서 `new Random()`(seed 없음), `Instant.now()`, `UUID.randomUUID()` 직접 호출 금지. 자세한 룰은 `08-code-conventions.md §A-8 결정성 패턴` 참조. 자동 강제는 ArchUnit으로 추진(이슈 #61).
  - **결정성 회귀 가드 (테스트 단정)**: `RecommendationDeterminismTest` 단정은 (a) 같은 입력 → 곡 ID 순서·top score 동일, (b) 입력 1비트라도 변경(sessionId/preferredBpm/excludeSongIds 등) → seed 변경 → `SeedDeriverTest`에서 long seed 값 자체가 달라짐, 두 축으로 분리한다. E2E에서 "다른 sessionId → 다른 순서" 단정은 가중치/시드 데이터 미세 변경에 flaky하므로 entropy 단정의 단일 진실은 **단위 (SeedDeriver) 레이어**가 진다. E2E는 안정 케이스(같은 입력 회귀)만 단정한다 (#59 후속).
  - **결정성 관측성**: 추천 호출당 입력 해시(SHA-256 hex 첫 16자)와 seed(long)를 INFO 로그 1줄로 남겨 운영 디버깅에서 결과 차이를 재현 가능하게 한다 (`event=recommendation.created request.input.hash=… seed=… algoVersion=v1|v2`). 입출력 PII는 hash·count로만 남기고 원문 음역대 수치도 노출하지 않는다 (관측성 baseline `10-observability.md` 정합).
- 외부 API 호출 없음(v1 한정). 내부 DB 질의만으로 완결.
- 매칭 점수 계산식이 코드 한 군데에 모여 있어야 하고, 가중치는 설정으로 빼야 한다.

## 4) 범위 / 비범위 (중요)
### 포함
- v1 알고리즘 형태 결정(Q1) — 규칙 기반 베이스라인.
- 점수 산정 공식(Q2) — 음역대 적합도 + 분위기/성별 가중치.
- 콜드스타트(음역대만 있고 다른 입력이 없을 때) 동작(Q3).
- 결과 다양성 보장(Q4) — 같은 아티스트 N곡 이상 방지 등.
- `RecommendationRequest` / `Recommendation` 엔티티 1차 필드.

### 제외 (Out of Scope)
- **임베딩 기반 추천** — Spotify audio-features 벡터 거리, 사용자 임베딩 등. v2 spec.
- **LLM 호출 추천** — Claude/OpenAI에 컨텍스트 던지고 곡 추천 받기. v2/v3 spec, 비용·지연 고려 필요.
- **개인화/협업 필터링** — 사용자 히스토리 누적 후 가능. v3+.
- **A/B 테스트 인프라** — v1에서는 단일 알고리즘만.

## 5) 설계

### 5-1) 도메인 모델
- 신규: **`RecommendationRequest`** (Entity)
  - 필드: `id`, `userOrSessionId`, `voiceRangeLow`, `voiceRangeHigh`, `gender`(nullable), `moods`(0~2개), `excludeSongIds`, `createdAt`.
- 신규: **`Recommendation`** (Entity) — request 1개 → result N행
  - 필드: `id`, `recommendationRequestId`, `songId`, `score`, `matchReason`, `rank`, `createdAt`.
- 신규: **`RecommendedSong`** (DTO 전용, 응답용) — `Song` + `score` + `matchReason`.

> `Song`은 `song-metadata-source.md` 기반. `VoiceRange`는 `voice-range-input.md` 기반.

### 5-2) API 엔드포인트
| Method | Path | 설명 | 인증 | Req | Res |
|---|---|---|---|---|---|
| POST | /api/v1/recommendations | 추천 요청 생성 + 결과 반환 | 익명 가능 (세션ID) | `RecommendationCreateRequest` | `RecommendationResponse` |
| GET | /api/v1/recommendations/{id} | 이전 추천 결과 재조회 | **익명 가능 (의도) — §9 2026-05-23 결정** | - | `RecommendationResponse` |

> **`GET /recommendations/{id}` 권한 정책 (의도)** — 추천 결과 link 공유(예: SNS, 메신저)·재방문 UX를 위해 **익명 호출 가능**하게 유지한다. SessionAuthGuard 미적용. 응답에 sessionId·voiceRange 수치·PII 미포함 (`RecommendationResponse` 페이로드는 `recommendations[]` + `requestId`만). 식별자 enumeration 방지는 §9 2026-05-23 결정 로그(UUIDv7 전환)로 보강한다.

### 5-3) v1 알고리즘 (권장 안)
**규칙 기반 베이스라인**. 다음 단계로 점수를 계산하고 상위 N곡을 반환한다.

```
score(song, request) =
    w1 * voiceRangeFit(song.keyOriginal, request.voiceRange)
  + w2 * moodMatch(song.moods, request.moods)
  + w3 * genderMatch(song.artistGender, request.gender)
  + w4 * popularityPrior(song)
  + jitter(small)        // 재추천 시 다양성용
```

- `voiceRangeFit`: 곡 원곡 키가 사용자 음역대 안에 들어가는 정도. 단순 인디케이터(`in`/`out`)에서 시작, 후속 단계에서 거리 기반으로 확장.
- `moodMatch`: 교집합/IoU. 0이면 페널티 약간.
- `genderMatch`: 동일 성별 가산(원곡 키가 보컬 음역에 더 잘 맞는다는 휴리스틱).
- `popularityPrior`: 시드 데이터에 들어 있는 인기 가중치(없으면 1.0).
- `jitter`: 재추천 시 결과 변주.
- 가중치 `w1~w4`는 `application.yml` 또는 별도 properties로.
- **다양성 보장**: 같은 아티스트 ≤ 2곡, 같은 장르 ≤ 4곡 (Q4 참고).

### 5-4) 데이터 흐름 / 시퀀스
```
[Client] POST /api/v1/recommendations { voiceRange, gender?, moods? }
   ↓
[Service] RecommendationRequest 저장
   ↓
[Service] SongRepository.findAll() (또는 필터 적용된 candidate 집합)
   ↓
[Service] score() 계산 → top-N 추출 → 다양성 후처리
   ↓
[Service] Recommendation 행 N개 저장
   ↓
[Client] RecommendationResponse { recommendations: [...] }
```
v1은 100~수백곡이므로 in-memory 정렬 가능. 카탈로그 1만곡 초과 시 색인/캐시 도입 별도 spec.

### 5-5) DB 마이그레이션
- `recommendation_request`, `recommendation` 테이블 신규.
- 인덱스: `recommendation_request(user_or_session_id, created_at)`, `recommendation(recommendation_request_id, rank)`.
- 마이그레이션 도구는 `voice-range-input.md Q3` 결정 사용.

### 5-6) 프론트엔드 화면
- 본 spec 범위 아님. 별도 `recommendation-ui.md`(미작성).
- API 계약(요청/응답)만 본 spec에서 확정.

## 6) 작업 분할 (예상 PR 리스트)
- [ ] PR A (docs): 본 spec 초안 — 본 PR.
- [ ] PR B (docs): Q1~Q4 합의 결과 spec 갱신 + 도메인 모델 §5/§6에 `Recommendation*` 반영.
- [ ] PR C (feat:recommendation): 도메인/서비스 골격 (`score()` 함수 + 단위 테스트).
- [ ] PR D (feat:recommendation): API + 영속화 + RestAssured E2E.
- [ ] PR E (chore:recommendation): 가중치 튜닝 + 다양성 후처리 (시드 데이터 위에서 hand-eval).

## 7) 테스트 전략
- **단위**: `voiceRangeFit`/`moodMatch`/`genderMatch` 각 함수의 경계값. `score()` 합산.
- **단위(다양성)**: 같은 아티스트 N곡일 때 결과에 최대 2곡만 포함되는지.
- **통합/Repository**: 시드 100곡 위에서 추천 1회 호출 → top-10 결과 검증.
- **E2E (필수)**: POST → 결과 N개 + matchReason 비어있지 않음 + GET으로 재조회 일치.
- **회귀(결정성, 단일 진실)**:
  - E2E (`RecommendationDeterminismTest`): 같은 입력 두 번 → 곡 ID 순서 + top score 동일. "다른 입력 → 다른 순서" 단정은 곡 시드/가중치에 결합돼 flaky하므로 두지 않는다.
  - 단위 (`SeedDeriverTest`): 같은 입력 → 같은 long seed. 입력 필드 하나라도 다르면 long seed 자체가 다름(필드별 회귀 케이스). entropy 단정의 단일 진실 레이어.
  - 단위 (`RecommendationScorerTest`): 점수 산식 결정성. 가중치·다양성 후처리 변경 시 같은 후보 집합에 대해 같은 점수.

## 8) 오픈 질문
> 모든 항목 해소. 본 spec은 `approved`. 새 질문이 생기면 본 테이블에 추가.

| #  | 질문 | 상태 |
|----|------|------|
| Q1 | v1 알고리즘 형태 | **결정됨** → §9 (2026-05-20) |
| Q2 | 점수 가중치 초기값 | **결정됨** → §9 (2026-05-20) |
| Q3 | 콜드스타트 동작 | **결정됨** → §9 (2026-05-20) |
| Q4 | 다양성 후처리 규칙 | **결정됨** → §9 (2026-05-20) |

## 9) 결정 로그
> 연대기 순.

- 2026-05-20: 초안 작성 (status=draft). D3를 본 spec Q1으로 다룸. 출처: #5
- 2026-05-20: Q1~Q4 작성자 권고안대로 확정, status=approved. v1은 설명 가능성·디버깅·응답 속도 우선. 차후 v2에서 재검토. 출처: #6
  - **Q1 → (a) 규칙 기반 베이스라인** — 디버깅·설명 가능성·응답 속도·비용 모두 v1에 우호적. LLM/임베딩은 v2.
  - **Q2 → (a) `w1=0.5, w2=0.2, w3=0.15, w4=0.15`** — 음역 적합이 1순위, 분위기·성별·인기는 보조.
  - **Q3 → (a) `w1`만 사용** — 단순함이 디버깅에 유리.
  - **Q4 → (c) 같은 아티스트 ≤ 2 + 같은 장르 ≤ 4 둘 다** — PoC 카탈로그가 작아 한 아티스트가 결과 도배할 위험 큼.
- 2026-05-21: 첫 구현 PR, status=implementing. 출처: #19
  - **`genderMatch` 컴포넌트 제거** — `Song`에 artistGender 필드 부재. v2에서 Song 필드 추가 시 재도입.
  - **`popularityPrior` 컴포넌트 사실상 비활성** — 모든 곡 popularity=1.0 (시드 데이터에 없음). 가중치만 보존, ranking 영향 없음.
  - **유효 점수식: `score = 0.5 * voiceRangeFit + 0.2 * moodMatch + jitter`** — Q2 가중치 중 voice/mood만 의미 있음.
  - **`voiceRangeFit` 구체 산식**: `MusicalKeyMidiResolver`로 곡 키 → root MIDI 매핑, 곡 음역 = root±7 semitones, overlap/songSpan으로 0~1 점수.
- 2026-05-21: **결정성 복원 — `SeedDeriver` 도입** (PR #48, closes #42).
  - **AS-IS**: `RecommendationService` 가 `new Random()`(seed 없음)으로 jitter 적용. 같은 입력에도 호출마다 결과 순서가 흔들려 §3 비기능 결정성 위반. rev 사이클 1 🔴 (https://github.com/goohong/mobruji/pull/20#issuecomment-4504853584).
  - **TO-BE**: `SeedDeriver.derive(...)` — 요청 입력 필드를 정규화 직렬화 → SHA-256 → 상위 8바이트 long seed. JDK 표준 `MessageDigest`만 사용, 외부 의존성 0. `RecommendationProperties.seedStrategy = derived`(기본) / `random`(디버깅용 비결정) 분기. `Random` 인스턴스는 **요청 단위로 새로 생성** (Spring DI로 빈 등록 시 결정성 깨짐).
  - **회귀 가드**: `SeedDeriverTest`(결정성/entropy/null mood) + `RecommendationDeterminismTest`(E2E — 같은 입력 두 번 → 같은 순서).
  - **누적 패턴 (영구 명문화)**: 추천 파이프라인에서 비결정 호출(`new Random()`/`Instant.now()`/`UUID.randomUUID()`)을 직접 사용하지 않는다. 결정성에 영향을 주는 모든 요청 입력은 `SeedDeriver.derive(...)` 시그니처에 포함시켜야 한다. 자동 강제는 ArchUnit 별도 이슈(#61).
- 2026-05-21: `excludeSongIds` 입력 도입. 출처: #45 #63 (PR #64)
  - **API 입력 확장**: `RecommendationCreateRequest.excludeSongIds: List<Long>` 추가 (nullable, JSON 생략 가능 → 빈 리스트로 정규화).
  - **파이프라인**: `SongRepository.findAll()` 결과에서 제외 ID를 점수 계산 전에 필터링. 다양성 후처리(아티스트≤2/장르≤4)와 fallback 모두 제외 후 카탈로그 위에서 정상 작동.
  - **🔴 누적 패턴**: `SeedDeriver.derive()` 입력에 정렬·중복 제거된 `excludeSongIds`를 포함. 같은 voiceRange/sessionId라도 제외 곡 셋이 바뀌면 다른 seed → 다른 jitter → 다른 결과. rev 사이클 3 누적 경고 ("다시 버튼이 같은 결과 반환") 회귀 가드.
  - **영속화 보류**: `RecommendationRequestEntity`에 `excludeSongIds` 컬럼 추가는 별도 PR. 본 PR은 요청 시점 입력만으로 파이프라인·seed에 반영하여 보호 영역(application.yml 스키마) 변경을 피한다. spec §5-1 도메인 모델의 `excludeSongIds` 필드는 후속 PR에서 영속화.
- 2026-05-21: `excludeSongIds` 영속화 완료. 출처: #72 #73 (PR #74)
  - **저장 위치**: 별 join table `recommendation_request_exclude_song(recommendation_request_id, song_id)`. JPA `@ElementCollection` + `@CollectionTable`.
  - **직렬화 방식 결정 근거**:
    - **선택: 별 join table** — 정규형 + 곡 ID별 row → 향후 분석 쿼리(어떤 곡이 자주 제외되는지 등) 용이.
    - JSON column 후보: MySQL 8.4는 지원하나 테스트 H2(MODE=MySQL)와의 호환·인덱싱 비용이 있어 보류.
    - comma-string 후보: 가장 단순하나 정규형 위반 + 분석 쿼리 어려움 → 기각.
  - **스키마 관리**: 본 레포는 Flyway/schema.sql 미도입 상태. 다른 엔티티들과 동일하게 JPA ddl-auto(`local`=update, `test`=create-drop)가 join table을 자동 생성한다. 운영(`validate`)에서 스키마 도구를 본격 도입하는 시점에 모든 테이블을 한꺼번에 마이그레이션으로 베이스라인화하는 별도 작업이 필요(현재 미해결 — `RecommendationRequestEntity`/`Recommendation`도 같은 상황).
  - **저장 로직**: `RecommendationService.create`에서 `RecommendationRequestEntity.create(..., excludeSongIds)`로 같이 저장. 입력 리스트는 엔티티 생성 시 방어적 복사로 캡슐화.
  - **fetch=EAGER**: 곡 ID만 담는 작은 정수 컬렉션 + 요청과 의미적으로 한 묶음 + 다른 도메인 join도 application 레벨이라 트랜잭션 분리 이점이 없어 LAZY는 `LazyInitializationException` 리스크만 증가. EAGER로 두어 `findById` 단일 호출로 풀-로드.
  - **테스트**: 단위 라운드트립 1건(`RecommendationRequestEntityPersistenceTest`) + E2E 1건(`RecommendationExcludeSongIdsTest#excludeSongIds_persistedOnRequestEntity` — API 호출 → DB 영속 검증). 결정성/seed/필터링 회귀는 기존 4건이 가드.
  - **결정성 영향 없음**: `SeedDeriver` 입력은 그대로(파이프라인 sort/dedup만 사용). 영속된 컬럼은 분석·후속 기능용.
- 2026-05-21: 추천 응답 UX 보강 — difficulty + 노트명 노출 (PR #96, closes #77, #95).
  - **응답 필드 추가**: `RecommendedSongResponse.song`이 참조하는 `SongResponse`에 `difficulty: Difficulty`(EASY/NORMAL/HARD), `lowMidi`/`highMidi`(MIDI 정수), `lowestNoteName`/`highestNoteName`(예: "E5") 추가.
  - **UX 배경**(이슈 #75/#77, 2026-05-21 사용자 결정): 추천 카드의 음역 막대 그래프를 폐기하고 "가창 난이도 라벨 + 최고음 표기"로 대체. 사람이 즉시 이해할 수 있는 표현 우선.
  - **분류 룰**: fe(`web/lib/difficulty.ts`)와 1:1 일치. HARD: high≥76(E5) 또는 span≥17, NORMAL: 71~75, EASY: <71. 임계값 영속화는 ADR 0007 후보(maestro 후속).
  - **알고리즘 영향 없음**: 본 PR은 응답 표현만 추가. score 산식·다양성 후처리·결정성 어떤 것도 변경하지 않음. 기존 가드 테스트 모두 통과.
- 2026-05-21: 추천 응답에 score breakdown 분해 노출 (PR #146, closes #145).
  - **배경**: plan 사이클 10 영감 분석 F-2 P2(Spotify "Why this song?"). 기존 응답은 `score`(가중 합산 double) + `matchReason`(한 줄)만이라 사용자가 추천 사유를 펼쳐볼 수단이 없었다. fe 사이클 14(#142)가 client-side로 breakdown을 추정 중인 상황을 backend가 정확히 채우는 방향.
  - **응답 필드 추가**: `RecommendedSongResponse`에 `breakdown: ScoreBreakdownResponse?` 추가. 5신호 raw 값(0~1): `keyMatch` / `rangeFit` / `genreMatch` / `moodMatch` / `popularity`.
  - **도메인 모델**: `recommendation.domain.ScoreBreakdown` (record, 불변, 각 필드 [0,1] 검증). `ScoredRecommendation`에 `breakdown` 필드 추가(nullable).
  - **스코어러 반환 타입**: `RecommendationScorer.score(...)` → `RecommendationScorer.Scored(double total, ScoreBreakdown breakdown)`. 가중 합산된 `total`과 raw 신호 분해를 함께 담는다. 점수 산식·가중치는 변경하지 않음 (결정성 회귀 가드 유지).
  - **matchReason 형식**: 단일 string 유지(fe 14가 client-side로 다중 줄 펼침 처리 중이라 응답 호환을 깨지 않기 위함). fe는 응답의 `breakdown`을 raw 신호로 활용해 펼침 영역을 구성한다.
  - **재조회 경로**: 영속 엔티티(`Recommendation`)에는 breakdown 컬럼을 추가하지 않았다 — DB 마이그레이션은 보호 영역이며 v1 한정 응답 표현 추가에 그치는 범위라 본 PR의 범위 외. 따라서 `GET /recommendations/{id}`의 `breakdown`은 `null`. UI는 펼침 영역을 숨기는 식으로 동작한다. 영속화는 후속 PR(`recommendation` 테이블에 5개 double 컬럼 추가) 후보.
  - **알고리즘 영향 없음**: SeedDeriver 입력·점수 산식·다양성 후처리 모두 무변경. 결정성 회귀 가드 1건 추가(같은 입력 두 번 → 1위 score 동일).
  - **테스트**: ScoreBreakdown 단위(범위/NaN), ScoreBreakdownResponse.from null 통과, RecommendationResponse from breakdown 매핑, E2E 응답에 breakdown 포함, RecommendationScorerTest 5신호 검증.
- 2026-05-21: **알고리즘 v2 — tempoMatch 신호 활성화 + preferredBpm 입력** (closes #218).
  - **배경**: AudioAnalysisRunner(#190) + backfill(#192) + Song.bpm 영속 인프라가 완비되어 BPM 신호를 추천 점수에 활성화할 수 있게 됐다. v1 의 `keyMatch` 메타 신호와 별개로, 새 가중 신호 `tempoMatch` 를 도입.
  - **API 입력 확장**: `RecommendationCreateRequest.preferredBpm: Integer?` (옵션, [30, 300]). null 이면 mood 기반 default BPM 표(`recommendation.tempo.mood-default-bpm`)에서 추론, mood 도 없으면 `recommendation.tempo.fallback-bpm` 사용. 그것마저 없으면 tempoMatch=0.5 중립.
  - **응답 확장**: `ScoreBreakdown` / `ScoreBreakdownResponse` 에 `tempoMatch` 필드 추가(5→6 신호). raw [0,1].
  - **공식**: `tempoMatch = 1.0 - min(1.0, |songBpm - target| / distanceTolerance)`.
    - 곡 BPM null 이면 0.5 중립(정보 없음). target 결정 불가도 0.5 중립.
    - 기본 `distanceTolerance = 40` BPM, 사용자 정의 가능.
  - **가중치**: `recommendation.weights.tempoMatch = 0.1` (application.yml). v2 유효 점수식:
    `score = 0.5 * voiceFit + 0.2 * mood + 0.1 * popularity + 0.1 * tempoMatch + jitter` (+ genre 0.2 비활성, key 메타).
  - **mood default BPM 표** (application.yml):
    UPBEAT=128, CALM=70, EMOTIONAL=80, POWERFUL=140, GROOVY=110, NOSTALGIC=90 / fallback=110.
    > Mood enum 에는 HAPPY/SAD/ENERGETIC 가 없어 기존 라벨로 매핑.
  - **결정성 회귀 가드 (필수)**: `SeedDeriver.derive(...)` 입력에 `preferredBpm` 포함. 같은 voiceRange/세션이라도 BPM 입력이 다르면 다른 seed → 다른 jitter → 다른 결과. null vs 정수 입력도 구분.
  - **영속화**: `recommendation_request.preferred_bpm INTEGER NULL` 컬럼 추가(V4 migration). 보호 영역 (`needs-human-review` 라벨). `RecommendationRequestEntity` 에 nullable `preferredBpm` 필드.
  - **테스트**: tempoMatch 단위 7건(정확/거리감쇠/tolerance 초과/mood default/preferredBpm 우선/null song bpm/target 없음/fallback) + ScoreBreakdown 6번째 필드 단위 + SeedDeriver preferredBpm 회귀 2건 + 영속 라운드트립 2건 + E2E breakdown.tempoMatch 노출 + E2E 결정성 회귀 2건. 기존 23 테스트 시그니처 마이그레이션.
  - **DiversityPostProcessor / matchReason 무변경**: tempoMatch 는 가중 합산 score 에만 영향, top 신호 분기는 rangeFit/moodMatch 기반 유지.
- 2026-05-22: **결정성 spec 정합화 — 로그 / 테스트 단정 / excludeSongIds seed 연계 명문화** (closes #59, 본 docs PR).
  - **배경**: PR #48 결정성 복원 사후 감사(rev 사이클 3, https://github.com/goohong/mobruji/pull/48#issuecomment-4504980667)에서 발견된 🟡 3건을 spec에 영구히 박는다. PR #48 결정 로그 라인(§9, 2026-05-21)이 본 정합화에 의해 새로 추가됐다.
  - **§3 비기능 결정성 절 신설**: (a) seed 계약(SeedDeriver 입력 시그니처가 결정성 단일 진실), (b) 비결정 호출 금지(컨벤션 §A-8 + ArchUnit #61), (c) 테스트 단정 레이어 분리, (d) 결정성 관측성 로그 1줄 — 4축으로 정리.
  - **§7 테스트 전략 정합**: "다른 입력 → 다른 순서" entropy 단정의 단일 진실을 단위 `SeedDeriverTest`로 옮기고 E2E는 안정 케이스(같은 입력 회귀)만 단정 — `RecommendationDeterminismTest.determinism_differentInput_yieldsDifferentOrder` 단정 약화 후보. 가중치/시드 데이터 미세 변경에 flaky한 단정을 회피한다.
  - **excludeSongIds seed 연계 (영구 명문화)**: §3 비기능 결정성 절에 "결정성에 영향을 주는 모든 요청 입력은 SeedDeriver 입력에 포함" 룰을 박았다. PR #74에서 이미 영속화·seed 연계 완료. 새 입력 필드 추가 시 동일 PR에서 SeedDeriver 시그니처도 확장.
  - **구현 이슈 분할** (be 세션 위임):
    - **PR A** (`feat:observability`): 결정성 관측성 로그 — 추천 호출당 `event=recommendation.created request.input.hash=<sha256-16> seed=<long> algoVersion=<v1|v2>` INFO 1줄. PII 원문 미노출.
    - **PR B** (`test:recommendation`): `RecommendationDeterminismTest.determinism_differentInput_yieldsDifferentOrder` 단정 안정화 — entropy 단정은 `SeedDeriverTest`로 이동, E2E는 같은 입력 회귀만.
    - **PR C** (`test:infra`): ArchUnit 룰 — `com.mobruji..application..` 패키지에서 `java.util.Random`/`java.time.Instant.now()`/`java.util.UUID.randomUUID()` 직접 호출 금지 (#61 묶음).
  - **결정성 영향 없음 (본 docs PR)**: 코드 변경 0. spec 정합화만.
- 2026-05-23: **결정성 관측성 로그 구현 — PR A 분리분 (closes #298)**.
  - **AS-IS**: `RecommendationService.create(...)` 가 결정성 관측 로그를 남기지 않아, 운영 중 결과 차이 원인을 입력 hash + seed 로 재현할 수 없었다.
  - **TO-BE**: 응답 직전 INFO 1줄 — `event=recommendation.created request.input.hash=<sha256-16> seed=<long> algoVersion=v2 resultCount=<n> durationMs=<ms>`. PII 원문(sessionId / voiceRange 수치 / 곡 메타) 미노출. `SeedDeriver.hashHex16(...)` 가 같은 canonical 입력에서 같은 hash 산출 (seed 와 1:1 대응).
  - **algoVersion**: 1차로 service 내부 상수 (`v2` — tempoMatch default 활성). properties 노출은 `application.yml` 보호 영역 변경을 동반하므로 v3 분기 도입 시 함께 진행. 본 PR 범위에서 제외.
  - **seedStrategy=RANDOM** 분기에서는 hash="-" 로 표기해 운영자가 비결정 분기를 즉시 식별. seed 는 새 `new Random()` 의 `nextLong()` 값을 로그·jitter 양쪽에 동일 노출.
  - **회귀 가드**: `RecommendationServiceDeterminismLogTest` 4건 (형식 · PII 미노출 · 결정성 hash/seed 동일 · RANDOM 분기) + `SeedDeriverTest.hashHex16` 2건 (결정성 · entropy).
  - **결정성 영향 없음**: 로그 추가만. `SeedDeriver.derive` / `canonicalize` / seed 산식 무변경. 응답 페이로드 무변경.
- 2026-05-23: **`GET /recommendations/{id}` IDOR 정책 — 익명 유지 + requestId UUIDv7 전환 결정** (rev 사이클 🟡 #406 M3, 본 docs PR).
  - **배경**: rev 사이클 recommendation/session audit (2026-05-23) 에서 `RecommendationController.read(Long id)` 권한 체크 부재 + `requestId` IDENTITY 순차 long → enumeration 위협 🟡 발견. spec §5-2 표 "익명 가능" 표기의 의도성 확인 필요로 plan 결정 보류 상태였음.
  - **결정 (옵션 a)**: **익명 가능 유지** + `requestId` UUIDv7 전환으로 enumeration 방지. SessionAuthGuard 적용은 채택하지 않음.
  - **근거**:
    1. **UX 의도 보존** — 추천 결과를 SNS·메신저로 link 공유하거나 다른 기기/세션에서 재방문하는 흐름이 익명 추천 서비스의 핵심 가치. SessionAuthGuard 도입 시 같은 sessionId가 아니면 404 — 공유·재방문 흐름이 깨진다.
    2. **민감도 낮음** — 응답 페이로드(`RecommendationResponse`)에 sessionId / voiceRange 수치 / 개인 식별자 미포함. 노출되는 정보는 곡 ID + score + matchReason + breakdown 으로, 곡 카탈로그는 공개 데이터. CLAUDE.md "음역대·기호 데이터 원문 노출 금지" 정신 위반 없음.
    3. **enumeration 방지로 충분** — 순차 long → UUIDv7 전환 시 추측 불가. UUIDv7 은 시간 prefix + 무작위 suffix → 정렬·인덱싱 친화 + 보안 entropy 확보. 동시 N개 요청에서도 ID 충돌·예측 모두 불가.
    4. **호환성·구현 비용 최소** — SessionAuthGuard 적용은 (a) `Recommendation` 엔티티에 sessionId FK 필요, (b) 기존 진행 중 추천 link 모두 무효화, (c) `RecommendationHistoryController` 와 권한 모델 분리 정의 필요 → 비용 대비 보안 이득 작음.
  - **권한 정책 (영구 명문화)**: `GET /api/v1/recommendations/{id}` 는 **익명 호출 가능**. 응답에 sessionId 노출 금지(현재 미노출 — 회귀 가드). 향후 spec 확장으로 voiceRange/PII가 응답에 추가되는 경우 본 결정 재평가 필요 — 그 시점에 SessionAuthGuard (옵션 b)로 전환 가능.
  - **구현 분할 (후속 이슈)**:
    - **PR A (be)** (`feat:recommendation`): `requestId` 식별자 UUIDv7 전환. `RecommendationRequestEntity.id: UUID` (DB 컬럼 `BINARY(16)` 또는 `CHAR(36)`) + `RecommendationResponse.requestId` 타입 변경. JPA `@Id` strategy 제거(애플리케이션 레벨 UUIDv7 생성). 마이그레이션(`V5__recommendation_request_uuid.sql`) — 보호 영역 `needs-human-review`.
    - **PR B (be)** (`test:recommendation`): enumeration 방지 회귀 가드 — `requestId` UUID 형식 단정 + 순차 호출 시 ID prefix 단조 증가(UUIDv7 시간 정렬성) + 충돌 없음 검증.
    - **PR C (fe)** (`feat:web`): `requestId` 타입 string(UUID) 처리. localStorage 캐시 키 마이그레이션 (필요시).
    - **별 이슈 등록**: be / fe 각각 후속 이슈(scope:recommendation / scope:web).
  - **rev 🟡 M1/M4 (#406 묶음)**: 본 plan 결정은 M3 분기만 해소. M1(fe sessionId fallback entropy `crypto.getRandomValues`) / M4(history GET sessionId echo 제거) 은 spec 영향 없는 소규모 코드 수정 → be/fe 사이클 직접 처리 (별 spec 갱신 불필요).
  - **결정성 영향 없음**: 본 docs PR. 코드 변경 0. 후속 PR A 의 UUIDv7 전환은 `SeedDeriver` 입력에 영향 없음(`sessionId` / `voiceRange` 등 입력 필드 무변경).
