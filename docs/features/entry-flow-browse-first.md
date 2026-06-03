---
feature: 진입 흐름 재정의 — browse-first + measure-on-intent (음역대 강제 제거)
slug: entry-flow-browse-first
status: draft
owner: "@goohong"
scope: song
related_issues: []
related_prs: [1596, 1603, 1605]
last_reviewed: 2026-06-03
---

# 진입 흐름 재정의 — browse-first + measure-on-intent

> 본 spec 은 신규 사용자가 mobruji 홈 (`/`) 에 처음 진입했을 때의 흐름 정책을 재정의한다.
> **사용자 directive (2026-06-03)**: "음역대를 먼저 받지 말고 일단 목록 보여주면서 맞춤 추천 받고 싶으면 받도록 유도하자".
> 본 spec 은 **상위 정책 (entry policy)** 만 정의하고, 곡 목록 UI 의 세부 구성 (장르 인덱스, 정렬, 페이지네이션 등) 은
> 자식 spec `song-catalog-genre-browse.md` 가 SoT 다. 본 spec 은 "어느 화면을 첫 face 로 둘 것인가" 와
> "음역대 측정을 어느 시점·어떤 방식으로 유도할 것인가" 만 다룬다.

## 1) 개요 (What / Why)

- 현 홈 (`web/app/page.tsx`) 은 측정 안 한 사용자에게 **`<OnboardingIntentPicker>` 3 페르소나 카드** (BEGINNER / PRACTICE / MOOD) + "그냥 둘러보기" 보조 link 를 노출하고 (first-user-onboarding-flow.md §5-6 PR 2 산출), 어느 카드를 선택해도 사실상 **음역대 측정이 첫 단계로 강제**된다 (MOOD 경로조차 마지막 경량 단계로 측정 수행 — first-user-onboarding-flow.md §9 2026-06-03 결정). 측정 안 한 사용자는 "추천 받기" 보조 CTA 가 등장하지 않아 **콘텐츠 (곡) 를 보기 전에 사용자가 자기 음역을 알아야 한다** 는 진입 장벽이 발생한다.
- 사용자 directive (2026-06-03) 는 이 장벽을 명시적으로 거부한다: "일단 목록 보여주면서 맞춤 추천 받고 싶으면 받도록 유도". 즉 **콘텐츠 우선 (browse-first)** + **의도 표현 시 측정 유도 (measure-on-intent)** 로 진입 정책을 뒤집는다.
- 본 spec 의 정책 산출:
  1. **첫 face = 곡 목록** — 측정 여부 무관, 사용자 첫 시각이 곡 카드 그리드 / 인덱스 (장르 카탈로그 또는 인기 / 신규 정렬).
  2. **음역대 측정 = optional + lazy** — 명시적 사용자 의도 표현 시점 ("내 음역대로 맞춤 추천 받고 싶다", "이 곡 부를 수 있을까") 에만 prompt.
  3. **dismiss 가능 + 빈도 절제** — 비강제 banner / floating CTA / contextual prompt. dismiss 시 cookie/localStorage 로 일정 기간 silent.
  4. **재방문 (측정 완료) 사용자** = 곡 목록에 "맞춤 추천" 정렬·필터 default + "전체 곡 보기" toggle.
- 대상 액터: 첫 진입 익명 사용자 (페르소나 A/B/C/D 모두). 측정 완료 사용자 / 회원도 본 정책의 toggle 분기 대상.

### 1-1) 기존 spec 과의 관계 + 충돌·이행

- **`first-user-onboarding-flow.md` (P-A/B/C 진입 카드 + 보조 둘러보기)** — 본 spec 과 **부분 충돌**.
  - 충돌 1: 기존 spec 의 진입 분기 = 페르소나 카드 우선, 둘러보기 = 보조 link. 본 spec = 곡 목록 우선, 페르소나 의도 = 사용자가 명시할 때만 유도.
  - 충돌 2: 기존 spec §5-4 데이터 흐름의 첫 단계가 "온보딩 진입 분기 (3 카드 + 보조)". 본 spec 채택 시 첫 단계가 "곡 목록 face" 로 바뀐다.
  - 이행 경로 = §5-3 "기존 spec deprecate / merge / coexist 매트릭스" 에서 결정. 1차 권고 = **`first-user-onboarding-flow` 의 진입 분기 화면 부분만 deprecate + 페르소나별 측정 카피 / 자식 spec (F1/F2/F3) wiring 정책은 유지**. 즉 페르소나 자체는 살아 있고 (사용자가 의도 표현 시 라우팅 대상으로 재활용), "첫 face 가 카드" 라는 부분만 본 spec 으로 대체.
- **`song-catalog-genre-browse.md` (장르 카탈로그)** — 본 spec 의 **첫 face 후보 SoT**. 본 spec 은 "장르 카탈로그를 첫 face 로 둘 것인가 / 별 신규 홈 face 를 둘 것인가" 만 정책 결정하고, UI 세부는 위임.
- **`song-search-and-filter.md` (찾기)** — 본 spec 의 "browse" 와 mental model 분리 유지. "둘러보기 (정렬·인덱스)" = 본 spec, "찾기 (제목·가수 keyword)" = song-search-and-filter SoT.
- **`recommendation-algorithm-v1.md` / `recommendation-feedback-loop.md`** — 본 spec 은 추천 알고리즘 입력 / 결과에 영향 0. 측정 시점만 옮기는 정책.
- **`preference-learning-personalization.md`** — 본 spec 이 도입하는 "곡 browse 행위" 가 implicit `PreferenceSignal` (노출 / 클릭) 신호로 사용될 가능성. 본 spec scope 밖, 신호화 결정은 별 PR.
- **`voice-range-input.md` / `voice-range-auto-measurement.md` / `voice-range-measure-guided-tour.md` (F1)** — 본 spec 은 측정 entry point 위치만 옮긴다. 측정 UI / 흐름 자체는 무변경.

### 1-2) 사용자 directive 의도 분해

| 분해 항목 | directive 원문 근거 | 본 spec 산출 |
|---|---|---|
| entry barrier 제거 | "먼저 받지 말고" | §3 F1 — 첫 face = 곡 목록 |
| browsing 우선 | "일단 목록 보여주면서" | §5-2 첫 face = `/` 또는 `/songs/catalog`, 카드 그리드 |
| 측정 = optional | "받고 싶으면" | §3 F2 — measure prompt 는 명시 사용자 의도에만 |
| 유도 = 부드러움 | "유도" | §3 F3 — dismiss-able banner / CTA, 강제 modal 금지 |

## 2) 사용자 시나리오

1. **(신규 / 측정 미 — browse only)** 노래방 곡을 찾는 신규 사용자가 `/` 진입 → **곡 목록 face** 가 즉시 보임 (인기 곡 / 장르 카드 / 신규 곡 — §5 default 정렬 결정 필요). 사용자는 음역대 측정 없이 곡 카드를 훑고, 특정 곡 카드 클릭 → 상세 modal 에서 곡 정보 확인. 측정 prompt 는 첫 진입 시 화면 어딘가에 **dismiss-able banner** 로 노출되되, 메인 콘텐츠를 가리지 않는다.
2. **(신규 / 측정 미 — 맞춤 의도 표현)** browsing 중 사용자가 "내 음역대로 맞춤 추천 받고 싶다" 의도 표현 — banner CTA tap / 곡 detail 의 "이 곡 부를 수 있을까?" CTA tap / 헤더의 "맞춤 추천 받기" link tap → 음역대 측정 진입 (`/voice-range/method` — PR #1603 method picker) → 측정 완료 → `/recommend` 또는 홈 reload + "맞춤 추천" 정렬 active.
3. **(신규 / 측정 미 — 명시적 dismiss)** browsing 중 measure prompt banner 의 dismiss (X) 버튼 tap → 일정 기간 (§3 F3 결정 필요) 같은 banner 미노출. localStorage / cookie 보관. 다른 contextual prompt (곡 detail "부를 수 있을까") 는 여전히 동작.
4. **(재방문 / 측정 완료)** `/` 진입 → 곡 목록 face + 상단 정렬 default = "내 음역 적합도순" 또는 "맞춤 추천순" + "전체 곡 보기" toggle. 음역대 요약 (저음~고음 음표명) 헤더 노출. "다시 측정" / "직접 다시 설정" 보조 CTA 유지 (현 `ReturningUserPanel` 의 일부 요소 재사용).
5. **(페르소나 의도 표현 — 우회 진입)** 신규 사용자가 "발성 연습할 곡 찾기" / "분위기 띄울 곡 찾기" 의도를 명시 표현 (헤더 / 메뉴 / "맞춤" CTA 안의 sub-option) → 기존 `<OnboardingIntentPicker>` 페르소나 라우팅 재활용 → 자식 spec (F2 / F3) wiring. 즉 페르소나 카드는 **첫 face 가 아니라 사용자가 "맞춤" 의도 표현 시 등장**하는 sub-flow 로 격하.
6. **(이탈 후 복귀 / cross-device)** 다른 device 에서 `/` 첫 진입 → cookie 없음 → 시나리오 1 동일 (첫 face = 곡 목록). 익명 sessionId 가 다르므로 측정 결과 cross-device 동기화 X (anonymous-session-lifecycle.md SoT 유지). 회원 전환 시 `UserProfile` prefill 로 측정 prompt 생략.

## 3) 요구사항

### 기능 요구사항

#### F1) 첫 face = 곡 목록 (browse-first)
- [ ] 측정 안 한 사용자의 `/` 첫 face 는 **곡 카드 그리드 / 장르 인덱스** (§5-2 결정에 따라 (a) `/` 자체 변경 / (b) `/` 가 `/songs/catalog` 로 redirect / (c) `/` 에 카탈로그 embed).
- [ ] 첫 face 는 음역대 / sessionId 의존 0 — 익명 호출, `SessionAuthGuard` 미적용 (`song-catalog-genre-browse.md` SoT 와 정합).
- [ ] 측정 안 한 사용자의 "추천 받기" primary CTA 폐기 — measure-on-intent 정책에 맞춰 secondary banner / contextual CTA 로 격하.

#### F2) measure prompt = optional + 명시 의도 trigger
- [ ] 음역대 측정은 사용자가 **명시적 의도** 표현 시에만 prompt:
  - (a) 홈 sticky banner CTA tap ("내 음역대로 맞춤 추천 받기" 등 한 줄 문구 + tap)
  - (b) 곡 detail modal 의 "이 곡 부를 수 있을까?" CTA tap (deferred sub-feature, 본 spec 은 placeholder 만)
  - (c) 헤더 / 메뉴 의 "맞춤 추천" link tap (페르소나 sub-flow 진입)
- [ ] 시스템이 자동으로 measure modal 을 띄우지 않는다 (auto-popup 금지).
- [ ] 측정 완료 → `/recommend` navigation (자동 / 수동 — §5-4 결정 필요) + "맞춤 추천" 정렬 active.

#### F3) prompt 빈도 / dismiss 가시화
- [ ] measure prompt banner 는 **dismiss 가능** (X 버튼 또는 "나중에" link).
- [ ] dismiss 후 일정 기간 같은 banner 미노출 — localStorage / cookie key `entryFlow.measurePromptDismissedAt` (예: 7일 — §5-5 결정 필요).
- [ ] 사용자 직접 navigate (예: 헤더 link tap) 한 측정은 dismiss flag 무관 — 명시 의도이기 때문.
- [ ] 빈도 가드: 같은 세션 안에서 measure prompt 최대 N 회 노출 (§5-5 결정 필요 — 예: dismiss 1회 + N 곡 view 후 재노출 1회).

#### F4) 페르소나 카드 = sub-flow 로 격하 (`first-user-onboarding-flow` 부분 deprecate)
- [ ] 첫 face 에서 `<OnboardingIntentPicker>` 직접 노출 폐기 (현 `web/app/page.tsx` `NewUserPanel` 의 picker import 제거 또는 측정 안 한 사용자의 sub-flow entry 로 이동).
- [ ] 페르소나 라우팅 자체는 보존 — 사용자가 "맞춤 추천" / "발성 연습" / "분위기" 의도 표현 시 기존 `<OnboardingIntentPicker>` 또는 등가 컴포넌트로 라우팅.
- [ ] `first-user-onboarding-flow.md` 의 §2 / §5-4 / §5-6 본문에 "본 spec 의 진입 분기 화면 부분은 entry-flow-browse-first.md 로 이행" cross-ref 추가 (별 docs PR 으로 정렬 — 본 spec 머지 직후).

#### F5) 재방문 사용자 face = 맞춤 추천 우선 정렬
- [ ] 측정 완료 사용자 `/` 진입 → 곡 목록 face 의 default 정렬 = "맞춤 추천 적합도순" (음역 적합도 + 가능 시 학습 affinity).
- [ ] "전체 곡 보기" toggle 노출 — 사용자가 정렬을 인기 / 장르 / 신규로 전환 가능.
- [ ] 현 `ReturningUserPanel` 의 "음역대 요약" / "다시 측정" / "직접 다시 설정" / "좋아요·북마크·이력 빠른 진입" UI 요소는 헤더 / SecondaryNav 로 재배치 (구체 위치는 PR 5 fe).

### 비기능 요구사항

- 첫 face 응답 시간: p95 ≤ 1.5s (곡 목록 첫 페이지 size=20, 장르 인덱스 캐시 적중 시). `song-catalog-genre-browse.md` 의 p95 200ms budget 위에 fe rendering 마진.
- 첫 face 는 **익명 / public** — `SessionAuthGuard` 미적용, PII 무, 외부 API 호출 없음.
- 음역대 측정은 기존 보안 / 익명 세션 정책 유지 (`anonymous-session-lifecycle.md`, `04-security-policy.md`). 원문 음성 저장 금지.
- 접근성: measure prompt banner 는 키보드 포커스 이동 + dismiss 버튼 aria-label + status live region announce.
- 진입 장벽 KPI (관측 인프라 깔린 후 후속 spec): 첫 진입 → 첫 곡 view 까지 클릭 0, 첫 진입 → 측정 진입 비율 (사용자 의도 명시 비율 proxy).

## 4) 범위 / 비범위 (중요)

### 포함

- 첫 face 정책 = 곡 목록 (browse-first) 결정 + 라우팅 / 컴포넌트 매핑.
- measure prompt 정책 = optional + 명시 의도 trigger + dismiss + 빈도 가드.
- `<OnboardingIntentPicker>` 격하 (첫 face → sub-flow 진입).
- `first-user-onboarding-flow.md` 본문 cross-ref 정렬 (별 docs PR — 본 spec 머지 직후).
- 재방문 사용자 default 정렬 정책 (맞춤 추천 우선 + toggle).

### 제외 (Out of Scope)

- **곡 목록 UI 세부 구성** (장르 인덱스 / 정렬 옵션 / 페이지네이션 / 카드 디자인) — `song-catalog-genre-browse.md` SoT.
- **곡 검색 (keyword 찾기)** — `song-search-and-filter.md` SoT. 본 spec 은 browse 만.
- **신규 BE 엔티티 / 마이그레이션** — 본 정책 변경은 fe + 기존 endpoint 재사용. BE 변경 없음.
- **추천 알고리즘 변경** — 측정 시점만 옮긴다. `RecommendationService` / `ScoreBreakdown` 무관.
- **분석 / 이벤트 트래킹 인프라 신설** — KPI 측정용 이벤트 수집은 후속.
- **`first-user-onboarding-flow` 의 자식 spec (F1 / F2 / F3) 자체 구현** — 본 spec 은 진입 정책만 바꾸고, 사용자 의도 표현 시 페르소나 라우팅은 자식 spec 에 위임.
- **곡 detail 의 "부를 수 있을까" CTA 구현** — 본 spec 은 placeholder 만, 별 spec / PR 로 분리.
- **온보딩 A/B 테스트 / 퍼널 측정 프레임워크** — v0.4+ 후보.
- **`<OnboardingIntentPicker>` 컴포넌트 자체 삭제** — sub-flow 진입에서 재사용. 위치만 이동.

## 5) 설계

### 5-1) 도메인 모델

- 신규 BE 엔티티 / 컬럼 **없음**.
- 신규 fe 클라이언트 개념 (`06-domain-model.md §4-1` 등재 후보 — 본 PR 동반 등재 의무):
  - **`EntryFacePolicy`** (`web` scope) — 첫 진입 시 사용자에게 보여줄 face 의 정책 enum. 1차 값: `BROWSE_FIRST` (본 spec 채택), legacy 비교용 `PERSONA_PICKER_FIRST` (`first-user-onboarding-flow.md` 의 진입 화면 정책 — 부분 deprecate).
  - **`MeasurePromptTrigger`** (`web` scope) — 음역대 측정 prompt 가 등장하는 trigger 분류 enum. 값: `HOME_BANNER_TAP` / `SONG_DETAIL_CTA_TAP` / `PERSONA_SUBFLOW_ENTRY` / `HEADER_LINK_TAP`. 시스템 자동 popup 금지 — enum 에 `AUTO_POPUP` 값 없음.
  - **`MeasurePromptDismissState`** (`web` scope) — measure prompt dismiss 상태. localStorage key + dismissed 시점 + 재노출 임계 (count + 시간). 영속 엔티티 아님 (클라이언트 전용).
- 기존 용어 재사용: `VoiceRange` / `RecommendationRequest` / `AnonymousSession` / `Onboarding` / `PersonaEntryPath`. 본 spec 은 `Onboarding` 의 완료 기준을 변경 X — 여전히 "첫 추천 도달" (first-user-onboarding-flow.md §3 정의 유지).

### 5-2) 첫 face 라우팅 매트릭스

사용자 측정 상태 / 회원 상태 / 의도 표현 시점에 따른 face 매핑.

| 사용자 상태 | URL | face | 측정 prompt 위치 |
|---|---|---|---|
| 첫 진입 / 측정 미 / 익명 | `/` | 곡 목록 (인기 / 장르 / 신규 default — §5-5 결정) | 상단 sticky banner (dismiss-able) |
| browsing 중 / 측정 미 / 곡 detail | `/songs/[id]` (또는 modal) | 곡 상세 | 곡 카드 하단 "부를 수 있을까?" CTA (placeholder) |
| 측정 완료 / 익명 | `/` | 곡 목록 (맞춤 추천 정렬 default + 전체 곡 보기 toggle) | 없음 (이미 측정) |
| 회원 + 측정 완료 | `/` | 동일 + 헤더 user profile prefill | 없음 |
| 페르소나 의도 표현 (sub-flow) | `/onboarding/intent` (or `<OnboardingIntentPicker>` modal) | 페르소나 카드 (P-A/B/C 라우팅) | 카드 선택 → 기존 자식 spec 흐름 |

**1차 권고 선택지**: (a) `/` 자체를 곡 목록 face 로 변경 (현 `<NewUserPanel>` → `<BrowseFace>` 교체). 사유 = 라우팅 추가 최소화 + 기존 `useSessionStore` / `useHasHydratedSession` 분기 재사용 + URL 안정성.
**대안 (b)**: `/` 가 `/songs/catalog` 로 redirect — URL 의미가 깨끗하지만 deep link / shared URL 호환성 risk.
**대안 (c)**: `/` 에 카탈로그 컴포넌트 embed (a 변형) — 본질적으로 (a) 와 동치.
→ 결정: (a) 권고. `first-user-onboarding-flow.md §5-6` 의 "별 `/onboarding` 라우트 추가 최소화" 정책과 일치.

### 5-3) 기존 spec deprecate / merge / coexist 매트릭스

| 기존 spec | 본 spec 도입 영향 | 처리 |
|---|---|---|
| `first-user-onboarding-flow.md` | §2 / §5-4 / §5-6 의 "첫 face = 페르소나 카드" 부분 충돌 | **부분 deprecate**: 진입 분기 화면 부분만 본 spec 으로 이행. 페르소나 라우팅 자체 (P-A/B/C → F1/F2/F3 자식 spec wiring) 는 보존. cross-ref 정렬 별 docs PR. |
| `song-catalog-genre-browse.md` | 본 spec 의 첫 face 후보 SoT | **coexist**: 본 spec 이 정책, song-catalog 가 UI 세부. cross-ref 강화. |
| `song-search-and-filter.md` | mental model 분리 (browse vs 찾기) | **coexist** 무변경. |
| `recommendation-algorithm-v1.md` / `recommendation-feedback-loop.md` | 영향 없음 | 무변경. |
| `voice-range-input.md` / `voice-range-auto-measurement.md` / `voice-range-measure-guided-tour.md` | 측정 entry point 변경, 측정 UI 무변경 | **coexist** 무변경. |
| `preference-learning-personalization.md` | browse 행위 implicit signal 후보 | 본 spec scope 밖, 별 PR. cross-ref 추가 (signal source 후보 명시). |

### 5-4) 데이터 흐름 / 시퀀스

```
[/ 첫 진입]
   │
   ▼
[측정 상태 hydrate (useHasHydratedSession)]
   │
   ├─ 측정 미 → [<BrowseFace>] (곡 목록 default 정렬 + sticky banner "맞춤 추천 받기")
   │              │
   │              ├─ banner CTA tap → [/voice-range/method] → 측정 완료 → [<BrowseFace> reload + 맞춤 정렬 active]
   │              │                                                        또는 [/recommend] (§5-5 결정)
   │              ├─ 곡 카드 tap → [/songs/[id]] → "이 곡 부를 수 있을까?" CTA (placeholder) → 측정 진입
   │              ├─ 헤더 "맞춤 추천" tap → [<OnboardingIntentPicker> sub-flow] → 페르소나 선택 → 측정 → 자식 spec wiring
   │              └─ banner dismiss → localStorage `entryFlow.measurePromptDismissedAt` set → banner 일정 기간 silent
   │
   └─ 측정 완료 → [<BrowseFace>] (맞춤 추천 정렬 default + 전체 곡 보기 toggle)
                  │
                  ├─ 곡 카드 tap → /songs/[id] (음역 적합도 자동 표시)
                  ├─ "전체 곡 보기" toggle → 인기 / 장르 / 신규 정렬
                  └─ 헤더 "다시 측정" / "직접 다시 설정" → 기존 측정 흐름
```

- `<BrowseFace>` 의 실제 UI 구성은 `song-catalog-genre-browse.md` 의 `/songs/catalog` 페이지를 첫 face 로 끌어오는 형태가 권고. 별 신규 컴포넌트 신설보다 기존 카탈로그 컴포넌트 재사용.
- 측정 완료 후 "자동 navigation vs 홈 reload" 결정은 §5-5 사용자 결정 항목 (Q3).

### 5-5) 사용자 결정 필요 항목 (nmae 가 사용자에게 직접 ask)

> 본 spec 자체에 "오픈 질문" 박제 X (사용자 정정 2026-05-31 — CLAUDE.md §10).
> 다음 항목은 nmae 가 사용자에게 직접 ask 하여 본 spec 본문 §5-2 / §5-4 / §5-5 갱신.

1. **Q1 — 곡 목록 default 정렬** (§5-2 첫 face 의 정렬 default)
   - (a) 인기순 (popularity proxy — `recommendation-algorithm-v2.md` popularity 신호 또는 누적 like / 추천 노출 카운트)
   - (b) 장르 인덱스 우선 (장르 카드 grid → 장르 선택 후 곡 카드)
   - (c) 추천 (서버 측 측정 안 한 사용자 대상 onboarding feed — 별 알고리즘 결정 필요)
   - (d) 신규 등록순 / random
2. **Q2 — measure prompt 위치 + 빈도**
   - 위치: (a) 홈 sticky banner / (b) 곡 detail 하단 / (c) floating action / (d) first-view modal + dismiss (auto-popup 금지 원칙과 충돌 — 본 spec 권고 X)
   - 빈도: (a) dismiss 후 cookie 7일 silent / (b) 곡 N 회 view 후 1회 재노출 / (c) 사용자 explicit CTA tap 만 (가장 절제)
3. **Q3 — 음역대 측정 완료 후 navigation**
   - (a) `/recommend` 자동 navigation (측정 → 즉시 맞춤 추천 결과 face)
   - (b) `/` reload + "맞춤 추천" 정렬 active (측정 후 곡 목록 유지)
   - (c) 사용자에게 "지금 맞춤 추천 받기 / 둘러보기 계속" 분기 선택
4. **Q4 — 곡 detail 의 measure CTA**
   - 위치: (a) 곡 카드 하단 / (b) 곡 상세 modal 안 / (c) "이 곡 부를 수 있을까?" 별 sub-feature 로 분리 (본 spec scope 밖)
   - 문구: (a) "이 곡 부를 수 있을까?" / (b) "내 음역대로 추천 받기" / (c) 사용자 제안
5. **Q5 — `first-user-onboarding-flow` 의 부분 deprecate 범위** (§5-3 매트릭스 첫 행)
   - (a) §2 / §5-4 / §5-6 의 진입 분기 화면 부분만 deprecate, 페르소나 라우팅 자체 유지 (본 spec 권고)
   - (b) `first-user-onboarding-flow.md` 전체를 deprecate 하고 본 spec + 자식 spec (F1/F2/F3) 만 유지
   - (c) `first-user-onboarding-flow.md` 전체 유지 + 본 spec 과 coexist (두 정책 동시 존재 — drift risk)
6. **Q6 — 페르소나 sub-flow 진입점**
   - (a) 헤더 / SecondaryNav 의 "맞춤 추천 받기" link 만 (가장 절제)
   - (b) 곡 목록 face 안에 페르소나 카드 row 가 보조로 등장 (browse + sub-flow 한 화면)
   - (c) measure prompt banner CTA tap 시 페르소나 카드 sheet 노출 → 카드 선택 후 측정

### 5-6) DB 마이그레이션

- 없음.

### 5-7) 프론트엔드 화면 (해당 시)

- 신규 / 확장 컴포넌트:
  - `<BrowseFace>` — 측정 안 한 사용자의 첫 face. `song-catalog-genre-browse.md` 의 `/songs/catalog` 페이지 컴포넌트를 재사용 / wrapper. 측정 안 한 사용자 / 측정 완료 사용자 모두 사용 (정렬 default 만 다름).
  - `<MeasurePromptBanner>` — sticky banner. dismiss 버튼 + CTA. dismiss 상태는 localStorage key `entryFlow.measurePromptDismissedAt` + `entryFlow.measurePromptShownCount` (빈도 가드).
  - `<PersonaSubflowEntry>` — 페르소나 의도 표현 진입점. 기존 `<OnboardingIntentPicker>` 컴포넌트를 sheet / modal 안으로 이동.
- 기존 화면 변경:
  - `web/app/page.tsx` `<NewUserPanel>` → `<BrowseFace>` 교체. 측정 안 한 사용자에게 페르소나 카드 직접 노출 폐기.
  - `<ReturningUserPanel>` → `<BrowseFace>` 의 측정 완료 variant (정렬 default + 헤더 요약). 현 "음역대 요약" / "다시 측정" / "직접 다시 설정" / 좋아요·북마크·이력 빠른 진입 요소는 헤더 / SecondaryNav 로 재배치.
  - `<SecondaryNav>` 에 "맞춤 추천 받기" link 추가 (`<PersonaSubflowEntry>` 진입).
- 기존 컴포넌트 재사용: `<OnboardingIntentPicker>` (격하 후 sub-flow 안에서만 사용), `SongCard` / `SongDetailModal` (`song-catalog-genre-browse` SoT).

## 6) 작업 분할 (예상 PR 리스트)

- [ ] **PR 1 (docs, plan)** — 본 spec 신설 + `06-domain-model.md §4-1` 신규 용어 등재 (`EntryFacePolicy` / `MeasurePromptTrigger` / `MeasurePromptDismissState`) + README §9 song 그룹 행 추가. **본 PR**.
- [ ] **PR 2 (docs)** — `first-user-onboarding-flow.md` cross-ref 정렬 (§2 / §5-4 / §5-6 본문에 "진입 분기 화면 부분은 entry-flow-browse-first.md 이행" cross-ref 추가, status 갱신은 §5-5 Q5 결정 후).
- [ ] **PR 3 (fe)** — `<BrowseFace>` 컴포넌트 + 측정 미 사용자 default 정렬 (§5-5 Q1 결정 반영). `<NewUserPanel>` → `<BrowseFace>` 교체. `<OnboardingIntentPicker>` 직접 노출 폐기.
- [ ] **PR 4 (fe)** — `<MeasurePromptBanner>` + dismiss / 빈도 가드 localStorage (§5-5 Q2 결정 반영).
- [ ] **PR 5 (fe)** — 측정 완료 사용자 default 정렬 (`<BrowseFace>` measured variant) + 헤더 / SecondaryNav 재배치 + 측정 완료 후 navigation 정책 (§5-5 Q3).
- [ ] **PR 6 (fe)** — `<PersonaSubflowEntry>` sheet / modal + 페르소나 라우팅 보존 (§5-5 Q6 결정 반영) + `<OnboardingIntentPicker>` 재배치.
- [ ] **PR 7 (fe, optional)** — 곡 detail "이 곡 부를 수 있을까?" CTA placeholder (§5-5 Q4 — 본 spec scope 밖 가능, 별 spec 으로 분리할 수도 있음).

### 보호 영역 변경 여부 (필수 명시)

- 보호 영역 변경 여부: ☑ 없음
  - 신규 BE 엔티티 / 마이그레이션 / `application.yml` / build / Dockerfile / workflows 변경 없음. 모든 산출은 `web/app` · `web/store` · `web/components` 범위의 FE 작업과 docs.

## 7) 테스트 전략

- **fe 단위**:
  - `<BrowseFace>` 측정 미 / 측정 완료 variant 렌더 분기 (`useHasHydratedSession` + `voiceRangeId`).
  - `<MeasurePromptBanner>` dismiss → localStorage write → 재진입 시 silent 검증.
  - `<MeasurePromptBanner>` 빈도 가드 (count + cooldown) 결정 후 reducer 테스트.
  - `<PersonaSubflowEntry>` 페르소나 카드 선택 → 라우팅 매핑.
- **fe e2e (Playwright)**:
  - 시나리오 1: 측정 미 → `/` 진입 → 곡 목록 face 보임 → 측정 prompt 없이 곡 카드 클릭 → 곡 상세 도달.
  - 시나리오 2: 측정 미 → `/` 진입 → banner CTA tap → 측정 → 맞춤 정렬 active 또는 `/recommend` (Q3 결정 따라).
  - 시나리오 3: 측정 미 → banner dismiss → 재진입 → banner silent.
  - 시나리오 4: 측정 완료 → `/` 진입 → 맞춤 추천 정렬 default + 전체 곡 보기 toggle 동작.
  - 시나리오 5: "맞춤 추천 받기" link → `<PersonaSubflowEntry>` sheet → 카드 선택 → 페르소나 라우팅.
- **정합 검증**:
  - `<OnboardingIntentPicker>` 가 첫 face 에 직접 노출되지 않는지 회귀 가드.
  - measure modal 자동 popup 부재 회귀 가드.
- **be**: 신규 endpoint 없음 → be E2E 추가 없음 (기존 `voice-range` / `recommendation` / `song` E2E 재사용). 본 spec 단독으로는 rev 단계 1 no-op pass 대상.
- **visual regression (`visual-regression-ci.md`)**: 첫 face 변경 = 의도된 baseline drift. fe PR 3 body 의 `## visual baseline update` 섹션에 N 페이지 / 사유 명시.

## 8) 결정 로그

> 연대기 순. "YYYY-MM-DD: 결정 / 이유 / 출처 (PR 번호 등)"

- **2026-06-03**: 초안 작성 (status=draft). 사용자 directive 2026-06-03 ("음역대를 먼저 받지 말고 일단 목록 보여주면서 맞춤 추천 받고 싶으면 받도록 유도하자") 수신. browse-first + measure-on-intent 정책으로 진입 흐름 재정의. `first-user-onboarding-flow.md` 의 첫 face = 페르소나 카드 정책과 충돌, §5-3 매트릭스에서 진입 분기 화면 부분만 deprecate + 페르소나 라우팅 자체 보존 (1차 권고, §5-5 Q5 사용자 확인 후 확정). `06-domain-model.md §4-1` 에 `EntryFacePolicy` / `MeasurePromptTrigger` / `MeasurePromptDismissState` 용어 등재 의무 (본 PR 동반).

## 9) 다음 단계

1. nmae 가 §5-5 사용자 결정 필요 항목 Q1~Q6 사용자에게 직접 ask → 본 spec 본문 갱신 (status=draft → approved 전이).
2. Q5 (`first-user-onboarding-flow` deprecate 범위) 확정 후 PR 2 (cross-ref 정렬) 실행.
3. Q1 / Q2 / Q3 확정 후 PR 3 / PR 4 / PR 5 fe 사이클 launch.
4. Q4 결정 시 곡 detail "부를 수 있을까" CTA 가 본 spec scope 안 / 밖 결정 — scope 밖이면 별 spec 신설.

## 10) cross-ref

- 상위 정책 SoT: 본 spec.
- 곡 목록 UI 세부 SoT: `song-catalog-genre-browse.md`.
- 곡 검색 SoT: `song-search-and-filter.md`.
- 부분 deprecate 대상: `first-user-onboarding-flow.md` (진입 분기 화면 부분).
- 측정 UI / 흐름: `voice-range-input.md` / `voice-range-auto-measurement.md` / `voice-range-measure-guided-tour.md` / `voice-range-intuitive-display.md`.
- 추천 알고리즘 / 학습 신호: `recommendation-algorithm-v1.md` / `recommendation-feedback-loop.md` / `preference-learning-personalization.md` (browse 행위 implicit signal 후보).
- 익명 세션 / 보안: `anonymous-session-lifecycle.md` / `04-security-policy.md`.
- 도메인 용어: `06-domain-model.md §4-1` (`EntryFacePolicy` / `MeasurePromptTrigger` / `MeasurePromptDismissState` 등재).
- 관련 PR (history): #1596 (403 fix), #1603 (음역 method picker), #1605 (음역 한국어 단독 표기 — 본 정책의 measure flow 가시화 정합 후속 확인 권고).
