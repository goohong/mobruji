---
feature: 음역대 표기 직관화 (voice-range-intuitive-display)
slug: voice-range-intuitive-display
status: draft
owner: "@goohong"
scope: voice
related_issues: []
related_prs: [1564]
last_reviewed: 2026-06-03
---

# 음역대 표기 직관화 (voice-range-intuitive-display)

## 1) 개요 (What / Why)
- 현재 사용자 음역대를 `C4` / `A#3` 같은 **과학적 음표 표기(SPN)** 위주로 노출한다. 음악 비전문 사용자에게 `C4` 가 "내가 어디까지 부르는가" 를 직관적으로 알려주지 못한다.
- 본 spec 은 음역대를 **(1) 계이름 병기**, **(2) 건반/막대 시각화**, **(3) 평균 대비 상대 설명** 세 축으로 직관화하는 표현 방식·도메인 표현·BE/FE 분담을 확정해, 후속 be/fe 구현이 spec 기반으로 진행되도록 한다.
- 대상 액터: 자기 음역대를 측정/입력한 직후 결과를 보는 비전문 사용자(특히 입문 페르소나 P-C, `user-persona-and-pain-points.md`).
- 이 기능은 **표현(presentation) 개선**이다. 추천 알고리즘 입력·결정성에 영향을 주지 않는다(결정성 회귀 가드).

## 2) 사용자 시나리오
1. **측정 직후 이해** — 사용자가 음역대를 입력/자동 측정한 뒤 결과 화면에서 "도2 ~ 라4 (G2 ~ A4)" 처럼 계이름 병기를 보고, 건반/막대 위에 자기 범위가 칠해진 그림으로 "내가 여기서 여기까지 부르는구나" 를 한눈에 파악한다.
2. **상대적 위치 감각** — "고음은 평균보다 약간 더 올라가고, 저음은 평균과 비슷해요" 같은 한국어 설명으로, 음표 숫자 없이도 자기 음역의 상대적 위치를 이해한다.
3. **학습 보조** — SPN(`C4`)은 정확도·학습용으로 병기하되, 1차 인지는 계이름·시각화·상대 설명이 담당한다.

## 3) 요구사항
### 기능 요구사항
- [ ] 사용자 가시 음역대 표면(측정 결과·확인 화면)에서 **계이름 + 옥타브** 를 SPN 과 병기한다 (예: `도4 (C4)`). 기존 `web/lib/notes.ts` 헬퍼를 단일 출처로 재사용한다.
- [ ] 단일 음역대를 **절대 음계(건반 또는 막대) 위에 시각화**한다 — 사용자 범위(low~high)를 강조 밴드로, 옥타브 기준선(C-노트) + 계이름 라벨을 함께 표시한다.
- [ ] 시각화에 **평균(벤치마크) 음역 밴드**를 옅게 겹쳐, 상대 위치를 시각적으로도 보여준다.
- [ ] 음역대를 평균과 비교한 **상대 설명(한국어 한두 줄)** 을 생성·노출한다 (예: "고음이 평균보다 약간 잘 올라가요").
- [ ] 상대 설명은 **성별 중립**을 기본으로 한다(현재 성별 미수집 — §5-1·§8 Q1). 성별이 확보된 경우에만 "남성/여성 평균 대비" 로 보강한다.

### 비기능 요구사항
- 시각화는 **외부 차트 라이브러리 없이 SVG 직접 렌더**한다 — 기존 `VoiceRangeProgressCard` 의 의도적 결정(번들 부담 회피, PR #170)과 일관.
- 모바일 우선. 좁은 폭에서 계이름/SPN 병기가 깨지지 않게(공간 좁은 축은 SPN 단독 허용 — 기존 차트 컨벤션).
- 접근성: 시각화 SVG 에 `role="img"` + 요약 `aria-label`, 상대 설명은 텍스트로도 완결(스크린리더가 시각화 없이도 이해 가능).
- 비유한(NaN/Infinity) MIDI 입력 가드 — 기존 `INVALID_MIDI_A11Y_FALLBACK` 재사용.

## 4) 범위 / 비범위 (중요)
### 포함
- 계이름 병기 표준화(표면 일관 적용) — 표시 정책.
- 단일 음역대 시각화 컴포넌트(건반/막대) — FE 신규.
- 평균 대비 상대 설명 — 성별 중립 기본.
- 음역 벤치마크 기준값(시드) + 음역 분류 규칙의 **문서 SoT**(본 spec §5-1 표 + 도메인 §4-1).

### 제외 (Out of Scope)
- **음역대 측정/입력 방식 자체** — `voice-range-input.md` / `voice-range-auto-measurement.md` / `voice-range-measure-guided-tour.md` 영역. 본 spec 은 측정된 값의 **표현**만.
- **시간축 발전 추적** — `voice-range-progress.md` / `VoiceRangeProgressCard` 가 담당. 본 spec 은 **단일(현재) 음역대** 표현.
- **성별 입력 신설** — 현재 `RecommendationRequest`/`VoiceRange` 에 성별 필드 없음. 성별 수집 여부는 `first-user-onboarding-flow.md §8 Q3` 결정 대상. 본 spec 은 성별이 **없는 상태에서 동작**하도록 설계하고, 있을 때의 보강만 선택 경로로 둔다.
- **추천 알고리즘 연동** — 음역 분류/상대 설명을 추천 점수·설명에 쓰는 것은 후속(§8 Q2). 본 spec 은 추천 무영향.
- **벤치마크 기준값의 정밀 검증** — 1차는 시드값(§5-1). 통계적 정밀화는 후속(§8 Q3).

## 5) 설계

### 5-1) 도메인 모델
신규 **영속 엔티티 없음**. 음역 표현은 기존 `VoiceRange`(§5-1, 06-domain-model) 값을 입력으로 하는 **파생 표현**이다. 신규 도메인 용어 3종을 `06-domain-model.md §4-1` 에 등재한다(본 PR 동시 갱신):

| 한국어 | 영어 (코드) | 정의 |
|---|---|---|
| 음역 분류 | VocalRegister | 음역대(low/high MIDI)를 절대 음역 밴드로 분류한 라벨. 비전문 사용자 친화 표현 우선(성별 명칭 회피) |
| 음역 벤치마크 | VoiceRangeBenchmark | 상대 설명·시각화 비교 기준이 되는 평균 음역 reference (성별 중립 기본 + 선택적 성별 분기). 시드값, 튜닝 대상 |
| 상대 음역 설명 | RelativeRangeDescriptor | 사용자 음역대를 벤치마크와 비교해 생성하는 짧은 한국어 설명("고음이 평균보다 약간 높아요") |

**음역 벤치마크 시드값 (PoC — 검증·튜닝 대상, §8 Q3)**

> 미숙련 성인의 *편안한 발성* 기준 근사값. 정밀 통계 아님 — 상대 설명의 기준선 용도. MIDI 표기.

| 기준 | low (MIDI) | high (MIDI) | span(반음) | 비고 |
|---|---|---|---|---|
| 일반 성인(성별 중립, 기본) | A2 (45) | C4 (60) | 15 | 남/여 평균의 중간 근사. 성별 미수집 시 사용 |
| 남성(선택, 성별 확보 시) | G2 (43) | B3 (59) | 16 | 성별 입력이 생기면 분기 |
| 여성(선택, 성별 확보 시) | G3 (55) | D5 (74) | 19 | 동상 |

**상대 설명 산출 규칙 (PoC, 성별 중립)**

- 비교 축 3개: 고음(user.high vs benchmark.high), 저음(user.low vs benchmark.low), 음역 폭(user.span vs benchmark.span).
- 차이 버킷(반음): `|Δ| ≤ 2` → "비슷", `3 ≤ |Δ| ≤ 5` → "약간", `|Δ| ≥ 6` → "훨씬".
- 방향: 고음 Δ>0 = "더 올라가요", Δ<0 = "덜 올라가요" / 저음 Δ>0(피치 높음) = "덜 내려가요", Δ<0 = "더 내려가요" / 폭 Δ>0 = "넓은 편", Δ<0 = "좁은 편".
- 출력: 고음·저음 중 차이가 큰 축을 헤드라인으로, 폭을 보조 문장으로 구성한 한두 줄. "비슷"만이면 "평균과 비슷한 음역이에요".

**음역 분류(VocalRegister) — 보조 라벨, 성별 중립**

- 1차 PoC 는 register 명(베이스/테너/소프라노 등 성별 관습 명칭)을 **전면에 쓰지 않는다** — 비전문 사용자에게 또 다른 전문 용어가 되기 때문. 상대 설명(RelativeRangeDescriptor)이 주 표현.
- register 라벨이 필요하면 "낮은 음역 / 중간 음역 / 높은 음역 / 넓은 음역" 같은 **일상어 밴드**로 둔다(중심음 = (low+high)/2 위치 기준). 정식 성악 명칭 매핑은 후속(§8 Q4).

### 5-2) API 엔드포인트
- **1차(PoC, FE-first): API 변경 없음.** 계이름 병기·시각화·상대 설명은 모두 기존 `VoiceRangeResponse`(low/high MIDI)만으로 FE 에서 파생 가능.
- **후속(선택, §8 Q2): BE enrichment.** 재사용 수요(추천 설명·계정 프로필)가 생기면 `VoiceRangeResponse` 에 nullable 파생 필드(`registerLabel`, `relativeDescriptor`)를 비파괴 추가. 신규 엔드포인트 없음.

### 5-3) 외부 연동
- 없음.

### 5-4) 데이터 흐름 / 시퀀스
```
[측정/입력] VoiceRange(low/high MIDI) 확정
   ↓ (FE-first)
[web] 파생:
   - 계이름 병기: notes.ts midiToCombinedNoteName(low/high)
   - 시각화: <VoiceRangeScale> — 절대 음계 위 user 밴드 + 벤치마크 밴드 + 옥타브 기준선
   - 상대 설명: voiceRangeBenchmark.ts describeRelative(user, benchmark)
   ↓
[화면] 결과/확인 표면 + (재사용) 추천 헤더 등
```
- 성별 확보 경로(선택): 성별 신호가 생기면 benchmark 선택을 성별 분기로 교체. 미보유 시 성별 중립 기본.

### 5-5) DB 마이그레이션
- **없음.** 신규 엔티티·컬럼 없음(파생 표현). `VoiceRange`/`VoiceRangeSnapshot` 스키마 무변경.

### 5-6) 프론트엔드 화면
- 신규 공유 컴포넌트 `web/app/voice-range/components/VoiceRangeScale.tsx`(가칭) — 단일 음역대 절대 음계 시각화.
  - SVG 직접 렌더(라이브러리 금지). `VoiceRangeProgressCard` 의 no-lib SVG·옥타브 기준선·`midiTo*` 라벨·a11y 패턴 재사용.
  - 표시 요소: 음계 backdrop(건반 스트립 또는 막대) / 사용자 범위 강조 밴드 / 벤치마크 평균 밴드(옅게) / 옥타브 C-노트 기준선 + 계이름 라벨 / 양 끝 음표명(계이름 병기, 좁으면 SPN 단독).
- 신규 표시 헬퍼 `web/lib/voiceRangeBenchmark.ts` — 벤치마크 상수(§5-1 표 mirror) + `describeRelative()` 순수 함수 + `classifyRegister()`(보조).
- 통합 지점: `/voice-range`(직접 선택 결과) · `/voice-range/auto`(자동 측정 결과) 의 결과/확인 영역. 추천 헤더 재사용은 선택.

### 5-7) BE/FE 분담 (핵심 — 후속 사이클 분기 기준)

설계 원칙: **난이도 mirror 패턴 차용.** 이 코드베이스는 이미 `Song.deriveDifficulty`(BE) ↔ `web/lib/difficulty.ts`(FE)를 1:1 룰로 두고 파리티(parity)를 유지한다. 음역 분류·벤치마크도 동일하게 *문서 SoT(§5-1) → FE 상수 → (후속) BE 분류기* 순으로 둔다.

| 영역 | 담당 | 내용 | PoC 포함? |
|---|---|---|---|
| 계이름 병기 표준화 | **fe** | `notes.ts` 재사용, 표면 일관 적용 | ✅ |
| 음역대 시각화 컴포넌트 | **fe** | `VoiceRangeScale` SVG, 벤치마크 밴드 overlay | ✅ |
| 벤치마크 상수 + 상대 설명 | **fe** | `voiceRangeBenchmark.ts`(§5-1 mirror) + `describeRelative()` | ✅ |
| 음역 분류 보조 라벨 | **fe** | `classifyRegister()` 일상어 밴드 | ✅ |
| BE 분류기 + 응답 enrichment | **be** | `VocalRegister` 분류 순수 함수 + `VoiceRangeResponse` nullable 파생 필드 + FE 파리티 단위 테스트 | ⛔ 후속(§8 Q2) |
| 성별 분기 벤치마크 | be/fe | 성별 신호 확보 후(온보딩 Q3) | ⛔ 후속(§8 Q1) |

- **PoC 1차는 fe 단독으로 닫힌다**(API/마이그레이션 무변경). be 작업은 재사용 수요가 확정될 때 분기 — 조기 BE 결합을 피한다.

## 6) 작업 분할 (예상 PR 리스트)
- [ ] PR A (docs): 본 spec + 도메인 §4-1 용어 3종 등재 — 본 PR.
- [ ] PR B (feat:web): `voiceRangeBenchmark.ts`(벤치마크 상수 + `describeRelative` + `classifyRegister`) + 단위 테스트.
- [ ] PR C (feat:web): `VoiceRangeScale` 시각화 컴포넌트(사용자 밴드 + 벤치마크 overlay + 옥타브 기준선) + 컴포넌트 테스트.
- [ ] PR D (feat:web): `/voice-range` · `/voice-range/auto` 결과 영역에 계이름 병기 표준화 + 시각화 + 상대 설명 통합.
- [ ] PR E (후속, feat:voice): (Q2 결정 시) BE `VocalRegister` 분류기 + `VoiceRangeResponse` enrichment + FE 파리티 테스트.

> PR B/C 는 독립 — 병행 가능. PR D 는 B·C 의존.

### 보호 영역 변경 여부 (필수 명시)
- 보호 영역 변경 여부: ☑ 없음
  - 신규 엔티티·마이그레이션·빌드·CI·환경설정 변경 없음. PoC 는 FE 파생 표현 + 도메인 문서 갱신만.
  - (후속 PR E 가 BE 응답 필드를 추가하더라도 비파괴 nullable 확장 — 마이그레이션 무관.)

## 7) 테스트 전략
- **단위(fe)**: `voiceRangeBenchmark.ts` — 차이 버킷 경계값(2/3/5/6 반음), 방향 문구, "비슷"만일 때 폴백, NaN/Infinity 가드. `describeRelative` 와 `classifyRegister` 각각.
- **컴포넌트(fe)**: `VoiceRangeScale` — 좁은 음역/넓은 음역/극단값에서 밴드·기준선 렌더, `role="img"` + aria-label 요약, 벤치마크 overlay 표시.
- **시각 회귀(선택)**: `visual-regression-ci.md` baseline 에 결과 화면 추가 시 의도된 baseline 갱신 절차 준수.
- **(후속) be**: `VocalRegister` 분류기 BDD given/when/then + **FE 파리티 가드**(난이도 `deriveDifficulty` ↔ `difficulty.ts` 패턴) — 동일 입력에 동일 분류.
- 외부 연동 없음 — mock 불요.

## 8) 오픈 질문
> 구현 전에 답이 나와야 하는 것들. 해소되면 §9 결정 로그로 이동.

| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | 성별 분기 벤치마크를 언제 도입하나 | (a) 성별 중립만으로 PoC 종료, 성별 보강은 온보딩 Q3 해소 후(권고) / (b) 본 spec 에서 성별 입력까지 신설 | @goohong / `first-user-onboarding-flow.md §8 Q3` 의존 |
| Q2 | 상대 설명/분류를 BE 로 올릴 트리거 | (a) 추천 설명·계정 프로필에서 재사용 수요 확정 시(권고) / (b) PoC 부터 BE enrichment | @goohong / 재사용 수요 발생 시 |
| Q3 | 벤치마크 시드값 정밀화 | (a) PoC 시드 유지 + 사용자 피드백으로 튜닝(권고) / (b) 공개 음역 통계 출처 확보 후 교정 | @goohong / 후속 |
| Q4 | 정식 성악 register 명칭(테너/소프라노 등) 매핑 노출 여부 | (a) 일상어 밴드만(권고) / (b) "학습 더보기" 토글로 정식 명칭 병기 | @goohong / 후속 |

## 9) 결정 로그
> 연대기 순. "YYYY-MM-DD: 결정 / 이유 / 출처(PR 번호 등)"

- 2026-06-03: 초안 작성 (status=draft).
  - **표현 3축 확정**: 계이름 병기 + 건반/막대 시각화 + 평균 대비 상대 설명.
  - **성별 중립 기본**: 현재 성별 미수집(`RecommendationRequest` v1 에서 gender 제거, 온보딩 Q3 미해소) → 상대 설명을 성별 중립으로 설계, 성별 보강은 의존 후속(Q1).
  - **FE-first, API/마이그레이션 무변경**: 파생 표현이라 PoC 는 fe 단독으로 닫는다. BE enrichment 는 재사용 수요 확정 시(Q2) — 난이도 mirror 패턴 차용.
  - **시각화 no-lib SVG**: `VoiceRangeProgressCard` 의 의도적 결정(PR #170)과 일관.
