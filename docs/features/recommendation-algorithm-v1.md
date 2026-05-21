---
feature: 추천 알고리즘 v1 (recommendation-algorithm-v1)
slug: recommendation-algorithm-v1
status: shipped
owner: "@goohong"
scope: recommendation
related_issues: [5, 19]
related_prs: [6, 20]
last_reviewed: 2026-05-21
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
- [x] 출력: `RecommendationResponse` — `List<RecommendedSong>` (곡 + score + matchReason 텍스트) + `requestId`.
- [x] 결과 곡 수: 기본 10곡(설정 가능). `recommendation.result-count` 프로퍼티.
- [x] 매칭 근거(`matchReason`)는 한 줄 한국어 문장으로 사용자에게 노출 가능한 수준이어야 한다 (예: "원곡 키가 사용자 음역대 안에 있음").
- [x] `RecommendationRequest`와 결과는 영속화한다(이력/분석). `excludeSongIds` 영속화는 후속 PR에서 처리 (본 PR은 요청 시점 입력만 받아 파이프라인·seed에 반영).

### 비기능 요구사항
- p95 응답 200ms 이내 (DB 100~수백곡 카탈로그 가정).
- 결정 가능성: 같은 입력 → 같은 결과 (재추천 제외). 디버깅·이슈 재현용.
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
| GET | /api/v1/recommendations/{id} | 이전 추천 결과 재조회 | 익명 가능 | - | `RecommendationResponse` |

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
- **회귀**: "같은 요청 → 같은 결과" 결정성 테스트(jitter는 seed 고정).

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
- 2026-05-21: `excludeSongIds` 입력 도입. 출처: #45 #63 (PR #64)
  - **API 입력 확장**: `RecommendationCreateRequest.excludeSongIds: List<Long>` 추가 (nullable, JSON 생략 가능 → 빈 리스트로 정규화).
  - **파이프라인**: `SongRepository.findAll()` 결과에서 제외 ID를 점수 계산 전에 필터링. 다양성 후처리(아티스트≤2/장르≤4)와 fallback 모두 제외 후 카탈로그 위에서 정상 작동.
  - **🔴 누적 패턴**: `SeedDeriver.derive()` 입력에 정렬·중복 제거된 `excludeSongIds`를 포함. 같은 voiceRange/sessionId라도 제외 곡 셋이 바뀌면 다른 seed → 다른 jitter → 다른 결과. rev 사이클 3 누적 경고 ("다시 버튼이 같은 결과 반환") 회귀 가드.
  - **영속화 보류**: `RecommendationRequestEntity`에 `excludeSongIds` 컬럼 추가는 별도 PR. 본 PR은 요청 시점 입력만으로 파이프라인·seed에 반영하여 보호 영역(application.yml 스키마) 변경을 피한다. spec §5-1 도메인 모델의 `excludeSongIds` 필드는 후속 PR에서 영속화.
