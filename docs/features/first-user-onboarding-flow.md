---
feature: 첫 사용자 온보딩 플로우 (3 페르소나 진입 경로)
slug: first-user-onboarding-flow
status: draft
owner: @goohong
scope: user
related_issues: []
related_prs: []
last_reviewed: 2026-06-03
---

# 첫 사용자 온보딩 플로우 (3 페르소나 진입 경로)

> 본 spec 은 `user-persona-and-pain-points.md` 에서 정의한 페르소나 A·B·C 를 **첫 진입 ~ 첫 추천 도달** 까지의 한 흐름으로 묶는 **상위 오케스트레이션 spec** 이다. 각 경로의 세부 산출(가이드 측정 UI, 고음 뚫기 모드, 분위기 모드)은 자식 spec(F1 `voice-range-measure-guided-tour.md` / F2 `high-note-training-mode.md` / F3 `mood-mode.md`)에서 다룬다. 본 spec 은 "어떤 사용자가 어느 문으로 들어와 어떻게 첫 추천에 도달하는가" 만 설계한다.

## 1) 개요 (What / Why)

- mobruji 의 첫 진입 화면은 현재 페르소나 구분 없이 단일 패널을 보여 주고, 모든 신규 사용자를 동일하게 "음역대 측정 → 추천" 단선 흐름으로 밀어 넣는다. 이 때문에 입문자(P-C)는 진입 즉시 `D3?` 표기 충격(P1, #318)에 노출되고, 연습러(P-A)·분위기 메이커(P-B)는 자기 동기에 부합하는 진입로가 없다.
- 본 spec 은 신규 사용자가 **자기 의도를 선택(또는 추론)** 한 뒤 페르소나별로 매끄럽게 첫 추천까지 이어지도록 온보딩을 재설계한다. 측정 단계의 두려움을 의도별로 다르게 옷 입히고, "측정만 하고 끝" 이 아니라 **첫 추천 도달** 을 온보딩의 완료 기준으로 명시한다.
- 대상 액터: 첫 진입 익명 사용자(페르소나 A·B·C). 재방문 사용자는 본 흐름을 건너뛴다(이미 측정 완료).

## 2) 사용자 시나리오

1. **(P-C 입문자)** 노래방이 처음인 사용자가 진입 → "내 목소리부터 알아보기" 카드 선택 → 가이드 측정(F1, 자세/거리/한국어 병기 안내) → 측정 결과를 `레3(D3)` 처럼 친화 표기로 확인 → 첫 추천 도달. 추천 카드에 "왜 이 곡인지(음역 적합)" 가 강조된다.
2. **(P-A 연습러)** 보컬 연습이 목적인 사용자가 진입 → "발성·고음 연습할 곡 찾기" 카드 선택 → 음역대 측정 → 연습 모드(F2) 추천(고음 뚫기 / 발성 입문). 첫 추천이 곧바로 연습 가치로 연결된다.
3. **(P-B 분위기 메이커)** 회식 자리를 살릴 곡이 필요한 사용자가 진입 → "분위기 띄울 곡 찾기" 카드 선택 → 분위기·청중 선택(F3) → (음역 입력은 마지막 가벼운 한 단계로 프레이밍) → 분위기 추천 도달.
4. **(의도 불명/탐색)** 무엇을 원하는지 모르는 사용자가 진입 → "그냥 둘러보기" 보조 경로 → 기존 단선 흐름(측정 → 일반 추천)로 진입. 의도 선택을 강제하지 않는다.
5. **(이탈 후 복귀)** 의도 카드만 고르고 측정 중 이탈한 사용자가 재진입 → 직전 선택한 경로의 다음 단계로 이어서 안내(중간 재진입), 처음부터 다시 시키지 않는다.

## 3) 요구사항

### 기능 요구사항
- [ ] 첫 진입 사용자에게 **3 페르소나 진입 경로 카드**(입문 / 연습 / 분위기)를 노출하고, 각 카드가 해당 페르소나 흐름의 첫 단계로 라우팅한다.
- [ ] 의도 선택을 강제하지 않는 **"그냥 둘러보기"** 보조 경로(기존 단선 흐름)를 함께 둔다.
- [ ] 선택한 진입 경로(`PersonaEntryPath`)와 진행 단계를 **클라이언트 온보딩 상태**로 보관해 중간 재진입 시 이어서 안내한다.
- [ ] 온보딩 완료 기준을 **첫 추천 도달**로 정의하고, 도달 시점에 상태를 `완료`로 전이한다(이전: 음역대 ID 존재로만 암묵 추론).
- [ ] 홈 4단계 안내의 "분위기·성별·속도 선택" 약속을 실제 흐름과 정합한다 — 첫 추천 전 경량 **추천 조건 단계**(분위기·연령대)를 두거나, 제공하지 않는 항목(성별)을 카피에서 정정한다(§8 Q3).
- [ ] 측정 단계 진입 카피를 **경로별로 다르게** 옷 입힌다(입문=안심/가이드, 연습=목표, 분위기=가벼운 마지막 단계).
- [ ] 재방문 사용자(측정 완료)는 온보딩 진입 분기를 건너뛰고 기존 "추천 받기" 화면으로 직행한다.

### 비기능 요구사항
- 첫 진입 ~ 첫 추천 도달까지 **막다른 길 없음** — 어느 카드를 골라도 첫 추천(또는 명확한 다음 액션)에 도달한다.
- 의도 선택은 **0~1 클릭** 안에 끝나야 한다(노래방 직전 사용 맥락, 측정 1분 + 조건 선택을 합쳐도 P95 ≤ 3분).
- 신규 사용자 데이터(의도/음역/선호)는 **익명 세션** 범위 안에서만 보관하며 원문 음성은 저장하지 않는다(`04-security-policy.md` §3, anonymous-session-lifecycle.md 재사용).
- 접근성: 진입 카드/단계 전환은 키보드 포커스 이동 + status live region announce(기존 `/voice-range/auto` 패턴 준수).

## 4) 범위 / 비범위 (중요)

### 포함
- 온보딩 진입 분기 화면(3 `PersonaEntryPath` 카드 + 보조 둘러보기) 설계
- 페르소나별 첫 추천까지의 라우팅 흐름 설계(각 자식 spec 으로의 핸드오프 지점 명시)
- 클라이언트 온보딩 상태 모델(`PersonaEntryPath` + 진행 단계 + 완료 시점)
- 홈 "분위기·성별·속도" 약속 ↔ 실제 흐름 정합(추천 조건 경량 단계 또는 카피 정정)
- 첫 추천 도달 핸드오프(설명 강조 + 경로별 다음 액션)

### 제외 (Out of Scope)
- **F1/F2/F3 자체 구현** — 가이드 측정 UI, 고음 뚫기 모드, 분위기 모드의 세부 산출은 각 자식 spec. 본 spec 은 진입·라우팅·핸드오프만.
- **성별 기반 추천 신호 신설** — 현재 `RecommendationRequest` 에 성별 입력이 없다. 신호 추가 여부는 별도 결정(§8 Q3).
- **계정 기반 온보딩 / 프로필 prefill** — v0.4 계정 시스템(`user-authentication-and-profile.md`) 영역. 본 spec 은 익명 세션 한정.
- **분석/이벤트 트래킹 인프라 신설** — 온보딩 퍼널 측정용 이벤트 수집은 후속(관측 인프라가 깔리면). 본 spec 은 완료 상태 전이까지만.
- **온보딩 A/B 테스트 프레임워크** — v0.4+ 후보.
- **신규 BE 엔티티 / 마이그레이션** — 온보딩 상태는 클라이언트 전용. BE 변경 없음(PoC).

## 5) 설계

### 5-1) 도메인 모델
- 신규 BE 엔티티 **없음**. 기존 도메인 재사용:
  - `VoiceRange`(§5-1) — 측정 결과.
  - `RecommendationRequest`(§5-3, `mood`/`ageGroup`/`excludeSongIds`/`preferredBpm`) — 첫 추천 조건. 본 흐름은 `mood`/`ageGroup` 만 온보딩에서 채운다.
  - `AnonymousSession`(§5-6) — 익명 식별/라이프사이클.
- 신규 용어는 `06-domain-model.md §4-1` 에 등재 완료(본 PR 동반): **`Onboarding`**, **`PersonaEntryPath`**(`BEGINNER`/`PRACTICE`/`MOOD`). 페르소나 정의 SoT 는 `user-persona-and-pain-points.md §2`.

### 5-2) API 엔드포인트
| Method | Path | 설명 | 인증 | Req | Res |
|---|---|---|---|---|---|
| — | — | **신규 endpoint 없음** — 기존 `POST /api/v1/voice-ranges`, `POST /api/v1/recommendations` 재사용 | 익명 | (기존) | (기존) |

- 온보딩 상태는 클라이언트(localStorage)에만 보관하므로 BE endpoint 가 필요 없다. 온보딩 퍼널 관측 카운터(BE) 도입 여부는 §8 Q5(후속).

### 5-3) 외부 연동
- 없음. 마이크 측정은 기존 클라이언트 WebAudio 경로(`web/lib/audio/sampler.ts`) 재사용.

### 5-4) 데이터 흐름 / 시퀀스

```
[첫 진입] /  (측정 여부 분기)
   │  voiceRangeId == null (신규)
   ▼
[온보딩 진입 분기]  ── 3 PersonaEntryPath 카드 + "그냥 둘러보기"
   │
   ├─ BEGINNER(P-C) → 가이드 측정(F1) ──┐
   ├─ PRACTICE(P-A) → 측정 → 연습모드(F2)┤
   ├─ MOOD(P-B)     → 분위기·청중(F3) → (음역 한 단계) ┤
   └─ 둘러보기       → 기존 측정 → 일반 추천 ┘
   │
   ▼
[추천 조건 경량 단계]  ── 분위기·연령대 (선택, §8 Q3 정합)
   │
   ▼
[첫 추천 도달]  ── 설명(voiceFit/moodFit 사유) 강조 + 경로별 다음 액션
   │
   ▼
온보딩 상태 = 완료
```

- 각 경로의 "측정" 단계는 자식 spec 의 화면으로 위임한다. 본 spec 은 진입 카드 → 자식 화면 → 추천 호출의 **연결 지점과 상태 전이** 만 정의한다.
- P-B(분위기) 경로의 측정 위치(선행/후행/생략 가능)는 추천 알고리즘이 음역 없이 동작 가능한지에 달려 있어 §8 Q4 로 분리한다.

### 5-5) DB 마이그레이션
- 없음(온보딩 상태는 클라이언트 전용).

### 5-6) 프론트엔드 화면 (해당 시)
- **진입 분기 위치**(§8 Q1): (a) 홈 `NewUserPanel` 확장 또는 (b) 별도 `/onboarding` 라우트. 1차 권고 = (a) — 라우트 추가 최소화 + 기존 측정-상태 분기(`useHasHydratedSession`) 재사용.
- 신규/확장 컴포넌트(설계):
  - `<OnboardingIntentPicker>` — 3 `PersonaEntryPath` 카드 + "그냥 둘러보기" 보조 link. 카드별 동기 부합 카피 + 목적지 라우팅.
  - `<RecommendationContextStep>` — 첫 추천 전 경량 조건(분위기/연령대) 선택(§8 Q3 결정 따라 노출/생략).
  - 온보딩 상태 store(`web/store/onboarding.ts`) — `entryPath`/`step`/`completedAt`. 기존 `useSessionStore`(voiceRangeId) 와 별도, persist(localStorage).
- 기존 화면 재사용: `/voice-range/auto`(측정 wizard), `/voice-range`(수동), `/recommend`(추천 결과). 측정 진입 카피만 경로별로 분기.
- 첫 추천 도달 시 `/recommend` 상단에 "내 음역 C3~A4 기준" + 설명 강조(BE 가 이미 제공하는 `voiceFitReason`/`moodFitReason` 소비), 빈/소진 시 경로별 다음 액션 CTA(P5 #320 완화).

## 6) 작업 분할 (예상 PR 리스트)
- [ ] PR 1 (docs, plan): 본 spec + `06-domain-model.md §4-1` 용어 등재(`Onboarding`/`PersonaEntryPath`). **본 PR**.
- [ ] PR 2 (fe): `<OnboardingIntentPicker>` + 온보딩 상태 store + 홈 진입 분기 확장(둘러보기 보조 경로 포함). 자식 경로 미연결 시 둘러보기/기존 측정으로 graceful fallback.
- [ ] PR 3 (fe): BEGINNER(P-C) 경로 wiring — 가이드 측정(F1) 진입 + 측정 진입 카피 분기. F1 spec 진척 의존.
- [ ] PR 4 (fe): PRACTICE(P-A) / MOOD(P-B) 경로 wiring — F2/F3 진입 핸드오프. 자식 spec 진척 의존.
- [ ] PR 5 (fe): 첫 추천 전 추천 조건 경량 단계(`<RecommendationContextStep>`) + 홈 "분위기·성별·속도" 카피 정합(§8 Q3 결정 반영).
- [ ] PR 6 (fe): 첫 추천 도달 핸드오프(설명 강조 + 경로별 empty/소진 CTA) + 온보딩 완료 상태 전이.

### 보호 영역 변경 여부 (필수 명시)

- 보호 영역 변경 여부: ☑ 없음
  - 신규 BE 엔티티/마이그레이션/`application.yml`/build 파일 변경 없음. 모든 산출은 `web/app`·`web/store`·`web/components` 범위의 FE 작업과 docs.

## 7) 테스트 전략
- **fe 단위**: `<OnboardingIntentPicker>` 카드 선택 → 라우팅 목적지 매핑, 온보딩 상태 store 전이(선택/단계/완료), 재방문 분기(measured → 진입 스킵).
- **fe e2e (Playwright)**: 3 경로 각각 "진입 카드 선택 → (자식 화면 stub) → 첫 추천 도달" 1건씩, "둘러보기 → 기존 단선 흐름" 1건, "중간 이탈 후 재진입 → 다음 단계 이어서" 1건.
- **정합 검증**: 홈 안내 단계 라벨 ↔ 실제 흐름 단계 일치(끊긴 약속 회귀 가드).
- **be**: 신규 endpoint 없음 → be E2E 추가 없음(기존 voice-range/recommendation E2E 재사용). 본 spec 단독으로는 단계 1 no-op pass 대상.
- **외부 연동 mock**: 측정 WebAudio 는 기존 `AutoMeasureDeps` 주입 패턴으로 stub.

## 8) 오픈 질문
> 구현 전에 답이 나와야 하는 것들. 해소되면 §9 결정 로그로 이동.

| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | 진입 분기 위치 | (a) 홈 `NewUserPanel` 확장(권고) / (b) 별도 `/onboarding` 라우트 | @goohong / PR 2 |
| Q2 | 의도 선택 방식 | (a) 명시 카드 선택(권고, PoC) / (b) 이전 행동 추론 / (c) 혼합 | @goohong / PR 2 |
| Q3 | 홈 "성별" 약속 처리 (`RecommendationRequest` 에 성별 입력 없음) | (a) 카피에서 성별 제거 → "분위기·연령대"(권고) / (b) 성별 추천 신호 추가 ADR | @goohong / PR 5 |
| ~~Q4~~ | ~~P-B(분위기) 경로의 측정 위치~~ | **해소 → §9(2026-06-03): (a) 측정을 마지막 경량 단계로 유지.** 근거: 추천 요청이 음역(`voiceRangeLow/High`)을 `not null` 로 요구 + `voice-fit 0.5` 1순위 가중치. `mood-mode.md §9` SoT | — |
| Q5 | 온보딩 퍼널 관측 | (a) 클라이언트 상태만(권고, PoC) / (b) BE 카운터(`mobruji.onboarding.*`) 신설 — observability-baseline 표 갱신 동반 | @goohong / 후속 |

## 9) 결정 로그
> 연대기 순. "YYYY-MM-DD: 결정 / 이유 / 출처(PR 번호 등)"

- **2026-06-03 (plan, mood-mode 동반)**: **§8 Q4(P-B 측정 위치) 해소 = (a) 음역 측정을 마지막 경량 단계로 유지.** 근거: `06-domain-model.md §5-3` 에서 `RecommendationRequestEntity.voiceRangeLow/voiceRangeHigh` 가 `not null`(추천 구조상 음역 필수) + `application.yml` `voice-fit: 0.5` 1순위 가중치. (b)광역 default 는 voiceFit 을 포화시켜 dominant 신호를 노이즈화, (c)음역 optional 추천 분기는 dominant 신호+정규화+결정성 가드를 건드리는 별 규모 결정이라 본 흐름 밖. P-B 경로는 음역을 "마지막으로 목소리만" 으로 가볍게 프레이밍해 마지막에 받는다(추천 알고리즘 무변경). SoT = `mood-mode.md §9`. 출처: F3 자식 spec PR.
- **2026-06-03**: 초안 작성 (status=draft). `user-persona-and-pain-points.md` 페르소나 A·B·C 를 첫 진입~첫 추천 한 흐름으로 묶는 상위 오케스트레이션 spec 으로 신설. 현재 단일 진입(persona 무분기) + 끊긴 "분위기·성별·속도" 약속 + 온보딩 완료 암묵 추론을 AS-IS 갭으로 박제. 3 `PersonaEntryPath`(BEGINNER/PRACTICE/MOOD) 진입 경로 설계, 각 경로를 F1/F2/F3 자식 spec 으로 핸드오프. 신규 BE 엔티티 없이 클라이언트 온보딩 상태로 진행(PoC). `06-domain-model.md §4-1` 에 `Onboarding`/`PersonaEntryPath` 용어 등재 동반.

## 10) 다음 단계
1. 본 PR 머지 후 F1(`voice-range-measure-guided-tour.md`) stub 을 본격 spec 으로 확장하면 PR 3(BEGINNER 경로 wiring) 착수 가능.
2. ~~F3(`mood-mode.md`) 작성 시 §8 Q4 확정~~ → **완료(2026-06-03)**. `mood-mode.md` 작성 + Q4=(a) 확정. PR 4(MOOD 경로 wiring) 착수 가능.
3. Q3(성별 약속) 은 PR 5 진입 전 (a) 카피 정정으로 빠르게 닫는 것을 권고.
