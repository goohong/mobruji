---
feature: scoreBreakdown zero-span (single-note) 정책
slug: score-breakdown-zero-span
status: draft
owner: @mobruji-maestro
scope: recommendation
related_issues: [756]
related_prs: [748]
last_reviewed: 2026-05-23
---

# scoreBreakdown zero-span (single-note) 정책

## 1) 개요 (What / Why)

**문제**: 추천 점수 분해의 `rangeFit` 신호는 곡 음역(`song.lowMidi`/`song.highMidi`)과 사용자 음역(`voiceRange.lowMidi`/`highMidi`) overlap 비율로 계산된다. 곡 음역이 **단일음(zero-span, `lowMidi == highMidi`)**일 때 division-by-zero 회피 가드가 필요하다.

**현 상태 (PR #748 lock-in)**:
- FE `web/lib/scoreBreakdown.ts` line 142: `songSpan = Math.max(1, songHigh - songLow)` 가드 → zero-span 곡의 overlap은 어차피 0(`max(0, overlapHigh - overlapLow)`이 single point에서 0)이므로 **`ratio = 0/1 = 0`**.
- BE `RecommendationScorer.voiceRangeFit` line 87-89: `songSpan <= 0` → **`return 0.0`** 명시.
- PR #748 test에서 "zero-span 곡 = 0" lock-in (`expect(zeroScore).toBe(0)`).

**왜 spec drift인가**:
1. `docs/features/recommendation-algorithm-v1.md` §3 "점수 신호 분해"는 신호 정의만 있고 zero-span 정책 명시 없음.
2. UX 관점: 사용자 음역 안에 완전히 들어가는 단일음 곡(monotone 챈트, 한 음 hook, 응원가 종류)이 추천 순위에서 **항상 0점으로 dropout**한다. 의도된 결과가 맞는지 합의 없음.
3. CLAUDE.md §3 비기능(결정성)에 영향 — 알고리즘 결과 형태가 단일음 곡 비율에 따라 편향될 수 있음.

**왜 이번 사이클에 결정하나**: rev v6 audit(이슈 #756) 발견. test가 동작을 lock-in했으므로 의도가 아니면 그 자체로 회귀. spec 합의가 선결.

## 2) 사용자 시나리오

| # | 시나리오 | 현재 결과 (옵션 A) | 사용자 기대 |
|---|---|---|---|
| S1 | 사용자가 "도 한 음(C4)만 부르면 되는 응원가"를 부르고 싶다 (사용자 음역 C3-G4) | rangeFit = 0 → 추천 dropout | 1.0 또는 그에 준하는 높은 점수 |
| S2 | 사용자 음역(C3-G4) 밖의 단일음 곡 (E5 한 음) | rangeFit = 0 → dropout | 0 (현행 OK) |
| S3 | 곡 음역이 (60, 60) 같이 데이터 오류로 zero-span 들어옴 | rangeFit = 0 | dropout 적절 (데이터 신뢰도 낮음) |

S1이 핵심 funnel. S2/S3은 현행과 같다.

## 3) 요구사항

### 기능 요구사항
- [ ] zero-span(`song.lowMidi == song.highMidi`) 곡에 대한 `rangeFit` 결과가 spec에 한 줄로 명시된다.
- [ ] FE `scoreBreakdown.ts`와 BE `RecommendationScorer.voiceRangeFit`이 **동일 분기 규칙**으로 계산한다 (BE-provided breakdown 우선이지만 FE fallback 추정도 같은 결과여야 사용자 혼란 없음).
- [ ] zero-span 분기는 단위 테스트 4종으로 잠금: (a) overlap 있음, (b) overlap 없음, (c) 데이터 오류성 zero-span(lowMidi=highMidi=0 등), (d) BE-provided null/missing fallback.

### 비기능 요구사항
- **결정성**: 동일 입력 → 동일 출력. 분기에 random/시간 의존 없음.
- **호환성**: 결정에 따라 기존 추천 결과가 바뀔 수 있음 → **결정 로그(§9)에 마이그레이션 영향 추정 곡 수 기록**.
- **관측성**: 추가 메트릭 불필요. 단, BE Scorer가 zero-span 경로 진입 시 `debug` 로그 1줄 (`recommendation.scorer.zero_span_fit=<value>`) — 데이터 품질 모니터링.

## 4) 범위 / 비범위

### 포함
- `rangeFit` 신호 한정 zero-span 규칙.
- FE/BE 동시 일관성.
- 단위 테스트 잠금.

### 제외 (Out of Scope)
- 가중치(`weights.rangeFit`) 자체 조정 — 별도 ADR.
- `keyMatch`/`genreMatch`/`moodMatch`/`popularity`/`tempoMatch` 다른 신호의 edge case.
- BE `RecommendationScorer`의 `rootMidi + LOW_OFFSET/HIGH_OFFSET` 매핑(키 기반 추정) 자체 — 현재는 키별 고정 span이라 실질 zero-span 발생 거의 없음. FE는 `song.lowMidi/highMidi` 직접 사용이라 단일음 곡 risk 더 큼.
- 데이터 파이프라인의 zero-span 데이터 유입 차단(별도 데이터 품질 이슈).

## 5) 설계 — 옵션 비교

### 5-1) 옵션 A: 현 상태 유지 (zero-span → 0.0)

- **동작**: 그대로 둠. spec에 "single-note 곡은 추천 신호상 0으로 처리한다 — 데이터 신뢰도 낮음 가정" 한 줄만 추가.
- **장점**: 코드 변경 없음. test 변경 없음. 데이터 오류 케이스(S3)에서 안전.
- **단점**: S1 funnel 손실. 단일음 응원가/챈트류 곡은 사용자 음역과 무관하게 절대 추천 안 됨.
- **추정 영향**: DB seed 30곡(현재) + 큐레이션 100곡 목표 중 단일음 곡 비율 < 5% 가정 (대중 가요 기준). 영향 적음.

### 5-2) 옵션 B: zero-span → 1.0 (만점)

- **동작**: `songSpan <= 0`일 때 사용자 음역 안에 들어가면 1.0, 밖이면 0. FE도 동일.
- **장점**: S1 funnel 회복. 단일음 곡 = "사용자 음역에 들어가면 완벽 fit"라는 직관 부합.
- **단점**:
  - 데이터 오류성 zero-span(S3)도 만점 → 노이즈 over-recommend.
  - 단일음 곡 = 가요 currentpus에서 거의 없음(주로 응원가/챈트/효과음) → over-fit으로 추천 결과 편향.

### 5-3) 옵션 C: ±1 semitone tolerance fuzzy (권장)

- **동작**: `songSpan == 0`일 때 곡 음역을 `[lowMidi - 1, highMidi + 1]`(2 semitone span)로 확장 후 정상 공식 적용.
  - 사용자 음역에 포함되면 overlap = 2 / 2 = 1.0.
  - 사용자 음역 가장자리(예: 사용자 high = 곡 음 - 1)이면 overlap = 1 / 2 = 0.5.
  - 완전히 밖이면 0.
- **장점**:
  - S1 funnel 회복하면서 가장자리 케이스 부드러움(0/1 cliff 회피).
  - 데이터 오류성 zero-span(S3)도 사용자 음역 안에 들어갈 확률이 0이거나 적어 over-recommend 위험 낮음 (lowMidi=0 같은 명백 오류는 사용자 음역 밖).
- **단점**:
  - 분기 규칙 한 줄 추가 (BE/FE 동시 변경 필요).
  - `±1 semitone`은 musically arbitrary — `±2` 또는 `±3`도 후보지만 ±1이 가장 보수적.
  - 테스트 케이스 증가 (3종 추가).

### 5-4) 권장: **옵션 C (±1 semitone fuzzy)**

**근거**:
1. S1 funnel 회복(옵션 A 단점 해소)하면서 옵션 B의 over-recommend 위험(데이터 오류) 회피.
2. fuzzy 범위(±1)는 가장 보수적 선택 — 차후 데이터 누적되면 ADR로 조정 가능.
3. 코드 변경 분량 < 10 lines (FE/BE 합산).

### 5-5) 영향 코드

| 영역 | 파일 | 변경 |
|---|---|---|
| FE | `web/lib/scoreBreakdown.ts` | `estimateRangeFit`에서 `songHigh - songLow === 0`이면 `songLow -= 1; songHigh += 1` 적용 후 기존 공식 |
| FE test | `web/lib/scoreBreakdown.test.ts` | zero-span test 4종 추가 (overlap inside/edge/outside/data-error) |
| BE | `backend/src/main/java/com/mobruji/recommendation/application/RecommendationScorer.java` line 87-89 | `songSpan == 0` 분기를 `songLow -= 1; songHigh += 1; songSpan = 2` 후 공식 적용 |
| BE test | `RecommendationScorerTest` (신규 또는 기존) | 동일 4종 |
| docs | `docs/features/recommendation-algorithm-v1.md` §3 | zero-span 정책 한 줄 추가 + 본 spec 링크 |

## 6) 작업 분할 (예상 PR 리스트)

- [ ] PR 1 (본 PR): spec docs 작성 (이 파일).
- [ ] PR 2 (be): `RecommendationScorer.voiceRangeFit` zero-span fuzzy 적용 + test. scope `recommendation`.
- [ ] PR 3 (fe): `scoreBreakdown.ts` zero-span fuzzy 적용 + test 갱신 (#756 lock-in 풀기). scope `web`.
- [ ] PR 4 (docs): `recommendation-algorithm-v1.md` §3 zero-span 정책 한 줄 추가 + 본 spec 링크.

PR 2/3은 독립 머지 가능 (FE는 BE-provided breakdown 우선, 추정 fallback만 영향).

## 7) 테스트 전략

- **단위 (FE)**: `scoreBreakdown.test.ts`에 zero-span 4종 케이스.
- **단위 (BE)**: `RecommendationScorerTest.voiceRangeFit_zeroSpan_*` 4종.
- **통합 (BE)**: 기존 `RecommendationServiceTest`에 zero-span 시드 곡 1건 추가 → 추천 결과에 포함되는지 1회 검증.
- **E2E**: 별도 추가 불필요(추천 엔드포인트 E2E 기존 커버).

## 8) 오픈 질문

| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | fuzzy tolerance ±1 semitone이 적절한가? | (a) ±1 (보수적, 권장) / (b) ±2 / (c) ±3 (musically a step) | @mobruji-maestro / spec 합의 시 |
| Q2 | DB seed에 zero-span 곡 의도적으로 추가할 것인가 (테스트 픽스처)? | (a) 추가 / (b) 미추가 (unit test 만) | @mobruji-maestro / spec 합의 시 |
| Q3 | BE Scorer는 `rootMidi + LOW/HIGH_OFFSET` 키 기반 추정이라 실질 zero-span 거의 없음. BE도 같은 분기 둘 필요 있나? | (a) 둠 (FE/BE 일관성 우선, 권장) / (b) FE만 변경 | @mobruji-maestro / spec 합의 시 |

## 9) 결정 로그

- 2026-05-23: 초안 작성 (status=draft). 권장 옵션 C(±1 semitone fuzzy). rev v6 audit(#756) 트리거.
