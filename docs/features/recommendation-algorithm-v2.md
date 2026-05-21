---
feature: 추천 알고리즘 v2 (recommendation-algorithm-v2)
slug: recommendation-algorithm-v2
status: draft
owner: "@goohong"
scope: recommendation
related_issues: [219, 222]
related_prs: [223]
last_reviewed: 2026-05-21
---

# 추천 알고리즘 v2 (recommendation-algorithm-v2)

## 1) 개요 (What / Why)
- v1(`recommendation-algorithm-v1.md`) 위에 audio features(key/tempo) 기반 매칭 신호를 활성화하는 후속 단계.
- v1 → v2 핵심 차이:
  - **`keyMatch`** — v1에서 응답 breakdown 필드는 있었으나 raw 신호 산출 로직이 비활성. be 17 backfill로 `Song.musicalKey`가 100곡 시드에 모두 채워졌으므로 v2에서 가중치 적용해 활성화.
  - **`tempoMatch`** — v2 신설 신호. be 17 backfill로 `Song.tempoBpm`이 채워졌으므로 사용자 입력(또는 mood→bpm 매핑)과 곡 BPM의 거리 기반 매칭.
- v0.3 P1. be 21 #219에서 동기적으로 구현 진행 중. 본 spec은 그 작업에 대한 plan-side 명세.

## 2) 사용자 시나리오
1. **기본** — v1과 동일한 입력(`voiceRange`, `mood`)으로 추천 요청. 결과는 audio features 매칭이 가산된 점수로 재정렬되어 v1 대비 같은 키/유사 BPM 곡이 상위로 올라온다.
2. **mood 필터 + tempo 매칭** — "신남" 선택 시 mood→preferredBpm 매핑(예: 신남=130bpm)으로 `tempoMatch` 자동 적용. 사용자가 명시적으로 `preferredBpm`을 넘기면 그 값이 우선.
3. **breakdown 펼침** — fe 14 UX에서 `keyMatch`/`tempoMatch` raw 값이 0이 아닌 실제 신호로 노출되어 "왜 이 곡인지" 설명력이 향상.

## 3) 요구사항

### 기능 요구사항
- [ ] **`keyMatch` 활성화** — `RecommendationScorer`에서 `Song.musicalKey`와 사용자 컨텍스트(현재는 `null` 기반 콜드스타트, 후속 spec에서 `preferredKey` 도입 검토) 비교 신호를 0 아닌 값으로 산출. 가중치 `w1` 기본값 (§9 결정 필요, 초안 `0.15`).
- [ ] **`tempoMatch` 신설** — `RecommendationScorer`에 신호 추가. `Song.tempoBpm`과 `request.preferredBpm`(또는 mood 매핑값) 사이 거리를 0~1로 정규화. 가중치 `w_tempo` 초안 `0.1`.
- [ ] **API 입력 확장** — `RecommendationCreateRequest.preferredBpm: Integer?` 추가 (nullable, 60~200 검증). 미입력 시 mood 매핑 적용, mood도 없으면 신호 0.5(중립).
- [ ] **응답 breakdown 확장** — `ScoreBreakdownResponse`에 `tempoMatch` 필드 추가. `keyMatch`는 기존 필드 재사용(이전 항상 0이었음).
- [ ] **mood → preferredBpm 매핑** — `RecommendationProperties.moodBpm` 맵(예: `신남=130, 잔잔=80, 감성=95`). 매핑 미정 mood는 null로 fallback. (§8 Q1)

### 비기능 요구사항
- **결정성 보존** — `SeedDeriver.derive()` 입력에 `preferredBpm`(정규화된 int, null이면 -1)을 포함해 같은 입력 → 같은 결과. 회귀 가드 테스트로 보장.
  - **단일 진실**: 결정성 룰(seed 계약 / 비결정 호출 금지 / 테스트 단정 / 관측성 로그)의 정의는 `recommendation-algorithm-v1.md §3 비기능 결정성` 절을 참조한다. v2 입력 확장(`preferredBpm`)도 그 룰에 따라 SeedDeriver 시그니처에 포함된다.
- **p95 200ms 유지** — 신호 2개 추가의 in-memory 계산 비용은 무시 가능. 카탈로그 수백 곡 가정. **임계 단일 진실: `docs/features/recommendation-p95-regression-guard.md` §5-3**. 회귀 가드는 k6 + GH Actions.
- **하위 호환** — `preferredBpm` 미입력 v1 클라이언트는 그대로 동작 (mood 매핑 또는 중립 fallback).
- **영속화는 본 spec 범위 밖** — `RecommendationRequestEntity`에 `preferredBpm` 컬럼 추가는 후속 PR. v2 한정 요청 시점 입력만 파이프라인·seed에 반영.

## 4) 범위 / 비범위

### 포함
- `keyMatch` / `tempoMatch` 두 신호의 산식·가중치·정규화.
- `preferredBpm` 입력 + mood→bpm 매핑 properties.
- breakdown 응답 확장.
- 결정성 회귀 가드.

### 제외 (Out of Scope)
- **ML 가중치 자동 조정** — feedback 신호로 가중치를 자동 튜닝하는 작업은 **v0.4+ spec**으로 분리. v2는 hand-tuned 가중치 고정.
- **`preferredKey` 입력** — 사용자 선호 키 입력은 별도 spec. v2는 키 신호만 활성화하고 사용자 컨텍스트는 추후.
- **임베딩 거리 기반 추천** — Spotify audio-features 벡터 전체(loudness/danceability 등)는 v3+.
- **재조회 경로의 breakdown 영속화** — v1과 동일하게 GET 응답의 breakdown은 `null` 유지. 영속화는 별도 PR.

## 5) 설계

### 5-1) 도메인 모델
- 변경 없음. `ScoreBreakdown` record에 `tempoMatch` 필드 추가(기존 5신호 → 6신호). `Song.musicalKey` / `Song.tempoBpm`는 be 17에서 이미 모델링됨.

### 5-2) 설정 (`RecommendationProperties`)
```yaml
recommendation:
  weights:
    voiceRangeFit: 0.5     # v1 유지
    moodMatch: 0.2         # v1 유지
    keyMatch: 0.15         # v2 신규 (활성화)
    tempoMatch: 0.1        # v2 신규
    popularityPrior: 0.05  # v1에서 0.15였으나 합 1.0 맞추려 조정 (§9 Q2)
  moodBpm:
    신남: 130
    잔잔: 80
    감성: 95
```
- 보호 영역(`application.yml`) 변경 → PR에서 `needs-human-review` 라벨 부여.

### 5-3) 스코어러 변경
- `RecommendationScorer.score(...)`는 그대로 `Scored(total, breakdown)` 반환.
- 내부에 `keyMatch(song, request)` / `tempoMatch(song, request)` 두 private 메서드 추가.
- `keyMatch`: 현재는 콜드스타트(사용자 키 입력 없음). v2 1차 구현은 **곡 키의 인기 분포 prior**(C/G/Am 등 부르기 쉬운 키 가산)로 한다. 진짜 사용자 키 매칭은 후속 spec.
- `tempoMatch`: `1 - min(|song.bpm - target.bpm| / 60, 1.0)` 형태. target은 `request.preferredBpm ?: moodBpm[request.mood] ?: null`. null이면 0.5 중립.

### 5-4) SeedDeriver
- 입력에 `preferredBpm`(정규화된 int, null→-1) 추가.
- 같은 voiceRange/sessionId/mood이라도 `preferredBpm` 다르면 seed 다름 → jitter 다름 → 결과 다름. 결정성은 유지.

### 5-5) API
- `POST /api/v1/recommendations` 요청 body에 `preferredBpm` 선택 필드 추가. 응답 변경은 `breakdown.tempoMatch` 추가뿐.

## 6) 작업 분할
- be 21 #219에서 **본 spec과 동기적으로 구현 진행 중**. 본 spec PR(#223)은 그 구현 PR과 같은 사이클에 머지된다.
- 구현 PR이 본 spec을 Read하고 §3 체크박스를 1:1로 채운다 (CLAUDE.md §7-1 자기 점검).

## 7) 테스트 전략
- **단위(`tempoMatch`)** — 동일 BPM=1.0, ±60bpm=0.0, ±30bpm=0.5 경계값.
- **단위(`keyMatch` prior)** — C/G/Am은 0.8+, F#m 같은 희귀 키는 0.3 이하 같은 분포 가드.
- **단위(properties 바인딩)** — `recommendation.weights.tempoMatch` / `recommendation.moodBpm` 바인딩.
- **결정성 회귀** — 같은 입력(`voiceRange`/`mood`/`preferredBpm`) 두 번 → 1위 score 동일.
- **결정성 분리** — `preferredBpm` 다르면 seed 다름 → 결과 셋 다름 가드.
- **E2E** — `preferredBpm` 입력 포함 요청 → 응답 `breakdown.tempoMatch` 비어있지 않음.

## 8) 오픈 질문

| #  | 질문 | 상태 |
|----|------|------|
| Q1 | mood→preferredBpm 매핑 기본값 (mood별 매핑 vs null fallback) | 미해결 |
| Q2 | `popularityPrior` 가중치 조정 (0.15 → 0.05) — v1 결정성 회귀 영향 | 미해결 |
| Q3 | `keyMatch`의 1차 산식 (곡 키 prior vs `preferredKey` 도입 대기) | 임시 결정: prior. 후속 spec에서 재검토 |

## 9) 결정 로그
> 연대기 순.

- 2026-05-21: 초안 작성 (status=draft). be 21 #219 동기 진행. 출처: #222, PR #223.
  - v1 spec의 `breakdown.keyMatch` 항상 0 문제와 be 17 audio features backfill 완료 상황을 받아 v2 분리.
  - 가중치 합 1.0 유지 위해 `popularityPrior` 0.15 → 0.05 임시 (Q2 미해결).
  - ML 자동 가중치 조정은 v0.4+ spec으로 분리.
