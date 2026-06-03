---
feature: 종합 UX 점검 + production-grade polish — 분기처리·디자인 체계·동적 UI 매트릭스
slug: ux-production-polish-audit
status: draft
owner: "@goohong"
scope: web
related_issues: [1603, 1596, 1610]
related_prs: []
last_reviewed: 2026-06-03
---

# 종합 UX 점검 + production-grade polish — 분기처리·디자인 체계·동적 UI 매트릭스

> 본 spec 은 **상위 audit + polish 정책 SoT** 이다. 본 spec 의 산출 후속 PR 1-8 은 각 PR 자체 spec (또는 본 spec §7 의 PR 분할) 에 따라 fe 사이클로 분기한다.
> 본 spec 은 **새 컴포넌트 구현을 강제하지 않는다** — 현황 audit + 매트릭스 + 분할 정책만. 실구현 결정·디자인 산출물·라이브러리 선택은 §8 사용자 결정 항목 (nmae 가 사용자에게 직접 ask).

## 1) 개요 (What / Why)

### 1-1) 사용자 directive 전문 인용 (2026-06-03)

> "이정도 기능 분기가 된 시점에서, UX 한번 점검해줘. 하나씩 기능을 추가하다보니 좀 어려워 진거 같아서, 분기처리 부터 미적인 요소, 가독성까지. 그리고 이제는 프로토타입이 아니고 실 프로덕션 같은 느낌을 주었으면 좋겠어. 애니메이션이나 스켈레톤, 동적인 UI UX 적극 채용해보자."

### 1-2) 프로토타입 → production 전환 정의

본 spec 이 정의하는 "production-grade" 의 운영 기준:

1. **분기처리 누적 정리** — 기능 한 줄씩 추가하면서 생긴 진입·전환 분기의 중복·모순·dead-end 제거. 한 사용자가 같은 의도로 다른 경로에 도달했을 때 **결과가 일관**하고 다음 행동 CTA 가 모호하지 않다.
2. **디자인 systematization** — color / spacing / radius / typography / elevation / motion 6 카테고리 토큰 ADR-0018 의 잔존 hardcode 0 + 페이지간 일관성 (같은 의미 → 같은 토큰).
3. **동적 UI 도입** — skeleton (이미 도입 #1493) / page transition (이미 도입 #1493) / micro-interaction (Button press scale 이미 도입) 의 **적용 범위 확장** + 새 패턴 (실시간 indicator / toast / empty illustration) 도입.
4. **가독성·접근성 sweep** — line-height / max-width / contrast / focus ring 일관성. WCAG AA 자율 검증.

### 1-3) entry-flow-browse-first.md (PR #1610) 와 시너지

`entry-flow-browse-first.md` 는 "첫 face = 곡 목록" 정책을 결정한다. 본 spec 은 그 첫 face 가 사용자에게 보여지는 **시각적 첫인상** 의 production polish 결정 — browse-first 의 곡 카드 그리드가 무미건조한 admin 톤이면 정책 자체의 의미가 약해진다. 본 spec § 7 의 PR 분할은 entry-flow-browse-first 의 fe PR (PR 3 `<BrowseFace>`) 와 의존 매트릭스를 명시한다.

### 1-4) 대상 액터

web 사용자 (모바일 우선, 노래방 부스 = 어두운 환경 + 한 손 조작). 본 spec 의 산출 = fe sub-agent (구현) / plan sub-agent (본 spec + ADR 갱신) / rev sub-agent (audit).

### 1-5) 기존 spec 과의 관계

| 기존 spec | 관계 |
|---|---|
| `ui-ux-redesign.md` | **상위 backlog SoT**. 본 spec 은 그 4단계 PR 2-9 backlog 의 "현황 점검 + 잔존 작업 우선순위 재정렬" 역할. 본 spec 신설로 기존 spec deprecate 0 — 본 spec 은 audit + 매트릭스 SoT, 기존 spec 은 PR backlog SoT 로 분담. |
| `design-tokens-residual-swap-matrix.md` | **단계 4 swap 완결 게이트 SoT**. 본 spec 의 §4 디자인 systematization 평가는 매트릭스 진행도를 인용. swap 완결 전 본 spec 의 "토큰 일관성 sweep" 후속 PR 은 진입 불가 (게이트 의무). |
| `entry-flow-browse-first.md` | **첫 face 정책 SoT**. 본 spec 의 §5 동적 UI 도입 = browse-first 의 곡 카드 그리드 시각 polish. cross-ref 강화. |
| `first-user-onboarding-flow.md` | 본 spec 의 §3 분기처리 audit 에서 페르소나 sub-flow 평가 대상. 정책 변경은 entry-flow-browse-first 가 SoT — 본 spec 은 시각 polish 만. |
| `voice-range-method-picker.md` (#1603) | 음역대 측정 분기 처리 (자동/수동/method picker) 의 일관성 평가. 본 spec §3 매트릭스 1행. |
| `recommendation-algorithm-*` | 추천 결과 카드 시각 polish 영향. 알고리즘 무변경. |
| ADR-0018 design tokens | 본 spec 의 §4 systematization 평가의 토큰 정의 SoT. |

## 2) 사용자 시나리오

### 시나리오 A — "첫 진입 wow" (신규 / 측정 미 / 모바일)

신규 사용자가 친구 추천으로 `/` 첫 진입. 페이지가 fade-in (이미 도입 — RouteTransition) 으로 부드럽게 등장. 첫 face 의 곡 카드 그리드 (entry-flow-browse-first §5-2 채택 시) 가 skeleton (이미 도입 — shimmer) → 실제 카드로 stagger fade-in 으로 차례차례 진입. 상단 sticky banner "내 음역대로 맞춤 추천 받기" 는 dismiss-able + 부드러운 등장. 사용자는 카드 hover (PC) / tap (모바일) 시 살짝 lift + 카드 가장자리에 brand-gradient ring 이 잠깐 켜진다 → "살아있는 서비스" 첫 체감.

### 시나리오 B — "음역대 측정 실시간 vis"

사용자가 banner CTA tap → `/voice-range/method` (method picker — #1603 spec only, fe 미구현) → 자동 측정 wizard 진입. 측정 단계마다 실시간 pitch (Hz) 가 circular progress ring 위에 음표명으로 표시되며, 측정 5초 카운트다운이 ring fill % 로 시각화. 사용자가 "내가 지금 뭘 하는지" 한눈에 인지 → 측정 자체가 "재미 없는 form" 에서 "참여하는 시각 게임" 으로 전환.

### 시나리오 C — "추천 결과 fetch 진행 단계화"

`/recommend` 진입. fetch 가 1000ms 넘으면 skeleton + 한 줄 상태 메시지 ("음역대 분석 중", "곡 후보 검색 중", "최적 정렬 중") 가 단계별로 등장. 5000ms 넘으면 timeout fallback CTA + 재시도 link. 사용자가 "멈춘 건가? 끊긴 건가?" 의심 0.

### 시나리오 D — "재방문 / 다크 모드 가독성"

밤 노래방 부스 (어두운 환경) 에서 다크 모드 active. 곡 카드 본문 텍스트 contrast 가 AA 이상 (`--text-primary` zinc-50), 음역대 적합도 chip 의 emerald 톤이 너무 튀지 않음 (이미 `--badge-success-fg` dark 에 emerald-200). focus ring 이 키보드 사용자에게도 명확히 보임 (이미 `--cta-secondary-ring` 균일 zinc-500). 사용자가 "어두운데도 글자가 또렷하다" 체감.

### 시나리오 E — "empty state 가 dead-end 가 아니다"

사용자가 검색 → 결과 0 / 좋아요 0 / 북마크 0 / 추천 결과 0 도달. 회색 "결과 없음" 텍스트 한 줄 (현재) 대신 **상황 맞춤 illustration + 다음 행동 CTA** (popular 검색어 chip / 인기 곡 entry / 추천 받기 CTA). 사용자가 "막다른 길에 갇혔다" 체감 0 — 항상 다음 행동이 보인다.

## 3) 요구사항

### 기능 요구사항

#### F1) 페이지별 audit 매트릭스 (§4 SoT)
- [ ] 8 페이지 (`/`, `/voice-range`, `/voice-range/auto`, `/recommend`, `/songs`, `/songs/[id]`, `/history`, `/likes`+`/bookmarks`) 매트릭스 박제
- [ ] 6 카테고리 평가 (정보 계층 / CTA 우선순위 / loading / empty / transition / 가독성)
- [ ] 페이지별 점수 1-5 + 합계 / 30 (정성 평가, 본 spec audit 시점 박제)

#### F2) 분기처리 일관성 audit (§5 SoT)
- [ ] 음역대 측정 분기 (자동 `/voice-range/auto` / 수동 `/voice-range` / method picker `/voice-range/method` 미구현)
- [ ] 추천 vs browse 분기 — entry-flow-browse-first.md 와 충돌·정합
- [ ] 측정 완료 후 navigation (자동 `/recommend` push / reload / 사용자 분기 선택 — entry-flow-browse-first.md §5-5 Q3)
- [ ] onboarding 페르소나 sub-flow (`OnboardingIntentPicker`) vs browse-first 의 공존·deprecate
- [ ] empty state (`Likes/Bookmarks/History/Songs` 결과 0) → next-action CTA 일관성

#### F3) 디자인 systematization 평가 (§6 SoT)
- [ ] tokens.css 의 var 시스템 진행도 cross-ref (design-tokens-residual-swap-matrix.md SoT)
- [ ] spacing / radius / typography / elevation 6 카테고리 일관성 grep
- [ ] hardcoded 잔재 grep (zinc / rose / red 등) — 매트릭스 SoT 와 카운트 일치 확인
- [ ] ADR-0018 단계 4 완성도 % (4 게이트 통과 여부)

#### F4) 동적 UI / 마이크로 인터랙션 매트릭스 (§7 SoT)
- [ ] skeleton / shimmer 적용 페이지 매트릭스 + 미적용 페이지 후속 PR 매핑
- [ ] page transition / route transition 적용 범위 + view-transition-name 도입 평가
- [ ] 버튼·카드·focus 마이크로 인터랙션 + 음역대 실시간 visual indicator + toast / empty illustration / progress 단계화 정책

#### F5) 가독성 / 접근성 sweep (§8 SoT)
- [ ] contrast (WCAG AA / AAA) 자율 검증 후보 페어
- [ ] line-height / max-width / focus ring 일관성

#### F6) 후속 PR 분할 (§9 SoT)
- [ ] 5-8 PR 분할 매트릭스 (의존 / LOC / 우선순위 / 회귀 risk)
- [ ] 각 PR 의 trigger / 게이트 / 의존 spec cross-ref

### 비기능 요구사항

- **추적 비용**: 본 spec 이 audit SoT — 다음 사이클 fe sub-agent 가 매번 페이지 grep 반복 X. 본 spec 의 매트릭스 표를 PR 머지 시 즉시 갱신.
- **drift 가드**: 본 spec `related_prs` 가 본 audit 의 후속 PR 누적 SoT. PR 머지 시 누락 → rev 단계 1 audit 경고.
- **사용자 결정 의존 항목 분리**: 본 spec §8 은 nmae 가 사용자에게 직접 ask. 본 spec 본문에 "오픈 질문" 박제 X (사용자 정정 2026-05-31 — CLAUDE.md §10).
- **구현 코드 결정 위임**: 본 spec 은 "어느 페이지에 무엇이 부족한가" 와 "후속 PR 분할" 만. 구현 결정 (라이브러리 / 파일 위치 / API) 은 후속 fe 사이클.

## 4) §2 페이지별 audit 매트릭스 (develop @ 2026-06-03)

평가 척도: 1 = 부족 / 2 = 기본 / 3 = 보통 / 4 = 잘 됨 / 5 = production-grade.

| 페이지 | 정보 계층 | CTA 우선순위 | loading | empty | transition | 가독성 | 합계 |
|---|---:|---:|---:|---:|---:|---:|---:|
| `/` (홈, 315 LOC) | 3 | 3 | 2 | 3 | 4 | 4 | 19 / 30 |
| `/voice-range` (수동, 300 LOC) | 3 | 4 | 2 | n/a | 4 | 4 | 17 / 25 |
| `/voice-range/auto` (자동 wizard, 728 LOC) | 3 | 4 | 3 | n/a | 4 | 3 | 17 / 25 |
| `/voice-range/method` (method picker, #1603 미구현) | — | — | — | — | — | — | n/a |
| `/recommend` (추천, 782 LOC) | 3 | 3 | 4 | 4 | 4 | 4 | 22 / 30 |
| `/songs` (검색, 543 LOC) | 4 | 3 | 3 | 3 | 4 | 4 | 21 / 30 |
| `/songs/[id]` (deep-link) | 4 | 3 | 3 | n/a | 4 | 4 | 18 / 25 |
| `/history` (이력, 481 LOC) | 3 | 3 | 3 | 3 | 4 | 4 | 20 / 30 |
| `/likes` (225) + `/bookmarks` (212) | 3 | 3 | 4 | 3 | 4 | 4 | 21 / 30 |

### 4-1) 페이지별 발견 정리

**`/` (홈)** — `NewUserPanel` 의 `<OnboardingIntentPicker>` 직접 노출 (entry-flow-browse-first.md F4 에서 격하 예정). FlowStep 1-4 안내 + "직접 다시 설정" / "음역대 다시 측정" 보조 CTA 가 측정 안 한 사용자에게는 보이지 않음. SecondaryNav (검색/이력/좋아요/북마크 4 link) 가 측정 여부 무관 노출 — 사용자 의도 분기 불명확. loading state = NewUserPanel 분기 보류 → 자체 skeleton 없음 (hydrate 전 NewUserPanel 렌더).

**`/voice-range` (수동 입력)** — 옥타브 분류 + select 2개. `<VoiceRangeIntuition>` 시각 보조 있음. 옥타브 선택 후 즉시 validate. loading state = mutation pending 시 Button 자체 spinner (Button.tsx 의 `loading` prop). empty 없음 (form). transition = RouteTransition 자동.

**`/voice-range/auto` (자동 측정 wizard)** — PERMISSION / MEASURE_LOW / MEASURE_HIGH / RESULT 4 단계 wizard. status live region (aria-live) 잘 됨. 실시간 pitch indicator (`PitchSample`) 가 단순 텍스트 + `<VoiceRangeIntuition>` 보조. **production polish 후보 1순위** — 마이크 입력 실시간 visual indicator (waveform / circular progress ring) 가 시나리오 B 핵심. 사용자 directive 의 "동적인 UI UX" 의 가장 강한 시각 hub.

**`/recommend` (추천)** — `SongCardSkeleton` 잘 도입. `useInfiniteQuery` + IntersectionObserver sentinel 무한 스크롤. RecommendFilters (mood / age 분기) + SwipeDeck (별 모드) + 무한 스크롤 list 가 한 페이지에 공존 → 정보 계층 3점 (사용자 인지 부담). empty 4점 (음역대 재입력 CTA + "더 이상 추천 없어요" fallback 분리). loading 4점 (skeleton N건 + 한 줄 메시지). **progress 단계화 (음역대 분석 / 곡 검색 / 정렬)** 후보.

**`/songs` (검색)** — URL query 동기 + multi-select 필터 (genre / difficulty) + 결과 카운트 표시 잘 됨. Suspense fallback (`SearchPageFallback`) 있음 — Next.js 16 CSR bail-out 가드. 검색어 없을 때 dashed border surface + "검색어를 입력해주세요" 안내. empty 3점 → **popular 검색어 chip / 인기 곡 시드 진입** 후보.

**`/songs/[id]` (deep-link)** — 모달 fallback 페이지. SongDetailContent 재사용. 표 정보 깊지 않음.

**`/history` (추천 이력)** — BE source-of-truth + localStorage fallback. `VoiceRangeProgressCard` SVG chart 잘 됨. PREVIEW_COUNT=3 + inline expand. 정보 계층 3점 (시계열 chart + 추천 카드 list 가 한 페이지). empty 3점 → "첫 측정 / 첫 추천 시작" CTA illustration 후보.

**`/likes` + `/bookmarks`** — Skeleton 도입 잘 됨 (#1493). EmptyLikes / EmptyBookmarks 분기 있음. empty 3점 → "둘러보기" CTA + 인기 곡 시드 후보.

### 4-2) 공통 패턴 발견

- **loading state 단계화 부재**: 모든 페이지가 (a) skeleton 또는 (b) Button spinner. 1000ms+ 일 때 "분석 중 / 정렬 중" 등 상태 메시지 없음. 사용자가 fetch 길어지면 "끊긴 건가?" 의심 risk.
- **toast / notification 시스템 부재**: 좋아요 / 북마크 토글 / 측정 완료 / 추천 fetch 에러 시 inline alert 또는 button 자체 변화. 우상단 stack toast 없음.
- **empty illustration 부재**: 모든 empty state 가 "텍스트 + CTA". illustration / 아이콘 / decorative SVG 없음.
- **실시간 visual indicator 부재**: `/voice-range/auto` 의 pitch 가 텍스트 + bar 정도. waveform / circular ring 없음.

## 5) §3 분기처리 일관성 audit

### 5-1) 음역대 측정 분기

| 진입 경로 | 도달 페이지 | 측정 결과 후 navigation |
|---|---|---|
| `/voice-range/method` (#1603 spec only, fe 미구현) | method picker → 자동 / 수동 분기 | (구현 시 결정) |
| 홈 `<NewUserPanel>` → `<OnboardingIntentPicker>` | 페르소나별 카드 → 측정 진입 (현재 entry-flow-browse-first.md 격하 예정) | F1 = 자동 / F2 = 수동 / F3 = 수동 |
| 홈 `<ReturningUserPanel>` "음역대 다시 측정" | `/voice-range/auto` | `/recommend` push |
| 홈 `<ReturningUserPanel>` "직접 다시 설정" | `/voice-range` | `/recommend` push |
| `/recommend` "마이크로 다시 측정" (MIC_MEASURE 소스) | `/voice-range/auto` | `/recommend` push |
| `<MeasurePromptBanner>` (entry-flow-browse-first 도입 예정) | `/voice-range/method` | Q3 결정 의존 |

**문제**: 측정 진입점 6개. 진입점마다 (a) 어느 method 로 갈지 (b) 측정 완료 후 어디로 가는지 일관성 부재. method picker (#1603 spec) 가 머지되면 진입점 정렬 가능 — 본 spec 의 후속 PR 후보 1순위 = method picker fe 구현 (별 spec SoT 우선).

### 5-2) 추천 vs browse 분기

- 현재: `/recommend` = 추천 fetch / `/songs` = 검색. browse (목록) 페이지 부재. entry-flow-browse-first.md 가 `<BrowseFace>` 신설 정책 결정 — 본 spec 은 그 정책 채택 후의 시각 polish 만.
- 충돌 위험: entry-flow-browse-first 의 §5-5 Q1 (default 정렬) 미결정 상태에서 본 spec 의 browse face 시각 spec 진입 시 재작업 risk → **본 spec 후속 PR 진입 게이트 = entry-flow-browse-first §5-5 Q1-Q6 사용자 결정 완료 후**.

### 5-3) 페르소나 sub-flow

- entry-flow-browse-first.md F4 가 `<OnboardingIntentPicker>` 첫 face 직접 노출 폐기 + sub-flow 진입으로 격하 정책 결정. 본 spec 은 그 격하된 sub-flow 의 시각 polish 만 — 페르소나 카드 hover lift / 카드 선택 시 sheet slide / 카드 illustration 등.

### 5-4) empty state next-action 일관성

| 페이지 | 현재 empty 메시지 | next-action CTA | 일관성 |
|---|---|---|---|
| `/likes` | `EmptyLikes` 안내 | "곡 검색 / 추천 받기" link 가능 | 3점 — link 만 |
| `/bookmarks` | `EmptyBookmarks` 안내 | "곡 검색" link 가능 | 3점 |
| `/history` | "첫 측정 시작" 안내 | 음역대 입력 link | 3점 |
| `/songs` (검색어 없음) | dashed border surface "검색어 입력해주세요" | 없음 | 2점 |
| `/recommend` (시드 소진) | "더 이상 추천 곡 없어요" | 음역대 재입력 CTA | 4점 |

**개선 후보**: 모든 empty state 가 (a) illustration + (b) 다음 행동 CTA + (c) 보조 link (popular 검색어 chip / 인기 곡 시드 등) 의 3-element 일관 패턴.

## 6) §4 디자인 systematization 평가

### 6-1) tokens.css 진행도

- `tokens.css` = 527 LOC (다크 모드 + 6 카테고리). brand / semantic / neutral / form / badge / meter / skeleton / chart 등 의미 alias 풍부.
- ADR-0018 단계 4 진행도: `design-tokens-residual-swap-matrix.md §5-1` 매트릭스가 SoT. develop @ 20f3cb8 기준 활성 zinc hardcode 41건 / 10 파일. 본 spec audit 시점 (develop @ 최신) **재측정 필요** — `git --no-pager grep -nE "(ring-zinc|bg-zinc|text-zinc|border-zinc|...)" web/{app,components}/` 후속 사이클.

### 6-2) Button.tsx 잔존

`Button.tsx` 의 `VARIANT_CLASSES` 가 여전히 `bg-zinc-900` / `bg-rose-600` 등 hardcode. 이는 `design-tokens-residual-swap-matrix.md` 의 sub-PR 2 (primitive 일괄) 대상. 본 spec 은 매트릭스 cross-ref 만.

### 6-3) 일관성 grep 후보 (spec 박제 — 후속 PR 시 측정)

```bash
# 6 카테고리 일관성 grep (후속 PR 시 실측)
# spacing 일관성 — 4/8/12/16/24/32/48 외 다른 값 grep
git grep -nE "(p|m|gap)-(5|7|9|10|14|18|20)" web/{app,components}/
# radius 일관성 — sm/md/lg/xl/full 외 hardcode
git grep -nE "rounded-(2xl|3xl|\[)" web/{app,components}/
# typography 일관성 — text-sm/base/lg/xl/2xl/3xl 외
git grep -nE "text-(\[|xs|sm|base|lg|xl|2xl|3xl|4xl)" web/{app,components}/ | grep -vE "text-(xs|sm|base|lg|xl|2xl|3xl)\b"
```

### 6-4) ADR-0018 단계 4 완성도

`design-tokens-residual-swap-matrix.md §5-3` 4 조건 게이트:
1. 활성 hardcode == 0 — **미충족** (41건 / 10 파일)
2. 신규 토큰 4종 결정 — **부분 충족** (`--modal-backdrop` / `--surface-modal` 도입 완료. `--ring-soft-hover` / `--ring-soft-focus-within` 결정 대기)
3. 테스트 assertion 동기 갱신 — **미충족** (Button.test / Chip.test bg-zinc-900 잔존)
4. 주석 마커 cleanup — **미충족** (75건)

→ **단계 4 swap 완결 게이트 통과 전 본 spec 의 토큰 일관성 sweep 후속 PR 진입 금지** (게이트 의무).

## 7) §5 동적 UI / 마이크로 인터랙션 매트릭스

### 7-1) skeleton / shimmer

| 적용 페이지 | 컴포넌트 | 상태 |
|---|---|---|
| `/recommend` | `SongCardSkeleton` | 적용 4점 |
| `/likes` | `<Skeleton />` (ui) | 적용 4점 |
| `/bookmarks` | `<Skeleton />` (ui) | 적용 4점 |
| `/songs` | (없음) | 미적용 — search 결과 fetch 시 skeleton 후보 |
| `/history` | (없음 — 즉시 mount) | localStorage hydrate 이후 fetch — skeleton 후보 (chart + 카드 list) |
| `/voice-range/auto` | (없음 — wizard) | 마이크 init 직전 짧은 skeleton 후보 |
| `/` (홈) | (없음 — hydrate 분기) | hydrate 전 NewUserPanel 렌더 → skeleton 불요 (의도된 분기) |
| 추천 fetch 1000ms+ | (없음) | "분석 중 / 곡 검색 중" 등 단계 메시지 후보 |

- 라이브러리: 현재 CSS-only (`globals.css` `.animate-shimmer`). `react-content-loader` (45KB) 도입 검토 — **반대 권고** (의존성 정책 정합 + 현 CSS-only 가 production-grade 충분).
- 컴포넌트 SoT: `web/components/ui/Skeleton.tsx` (ADR-0018 / #1493).

### 7-2) page transition / route transition

- 현재: `RouteTransition.tsx` 가 pathname key 기반 fade-in. `app/layout.tsx` 에서 `<RouteTransition>{children}</RouteTransition>` 으로 wrap. **잘 도입됨**.
- view-transition-name 신규 도입 후보 — `app/recommend/components/SongCard.tsx` → `/songs/[id]` deep-link morph (album cover hero). Next.js 16 native view-transition API 지원 검토.
- 결정 후보:
  - (a) 현 `RouteTransition` fade-in 만 유지 — 가장 절제, 의존 0
  - (b) view-transition-name 추가 (Chrome / Safari 16+) — fallback CSS fade
  - (c) Framer Motion 도입 (gzipped ~40KB) — 풀 애니메이션 routes
- → 사용자 결정 (§8 Q1).

### 7-3) 마이크로 인터랙션

| 요소 | 현재 | 후보 |
|---|---|---|
| Button press | `active:scale-[0.97]` (Button.tsx 적용) | 적용 4점 |
| Card hover (PC) / tap (모바일) | `SongCard` 자체 transition 일부 | hover lift (translateY -2px) + brand-gradient ring fade-in |
| focus ring | `--cta-secondary-ring` (zinc-500 균일) | 적용 4점 |
| LikeButton tap | (즉시 swap, 마이크로 인터랙션 미적용) | scale 1 → 1.3 → 1 spring + sparkle particle (`ui-ux-redesign.md` PR 6 SoT) |
| Bookmark toggle | (즉시 swap) | 동일 LikeButton 패턴 |
| 추천 카드 expand | (인라인 expand) | height transition 300ms + caret rotate |

### 7-4) 음역대 측정 실시간 indicator (사용자 directive 핵심)

`/voice-range/auto` 의 `MeasureStep` 에서 실시간 pitch sample 을 받음 (`PitchSample`). 현재는 텍스트 + `<VoiceRangeIntuition>` 보조. **production polish 후보 1순위**:

- (a) **waveform** — Canvas 또는 SVG path. Web Audio API `AnalyserNode.getFloatTimeDomainData` 로 wave 그리기.
- (b) **circular pitch ring** — SVG circle. 측정 5초 카운트다운 = stroke-dashoffset 채움. 현재 detected pitch 가 ring 위 음표명 dot 으로 표시.
- (c) **pitch indicator bar** — 수직 bar + 현재 pitch 위치 dot. lowMidi / highMidi 사용자 입력 범위 vs 현재 측정.

`ui-ux-redesign-pr-5-pitch-wave.md` 가 PR 5 단독 spec SoT. 본 spec 은 cross-ref + 우선순위만.

### 7-5) loading 단계화

진행 단계화 매트릭스:

| 시간 | 상태 |
|---|---|
| 0 - 300ms | 즉시 응답 / no loading UI |
| 300 - 1000ms | skeleton appear |
| 1000ms - 5000ms | progress indicator + "잠시만요…" / "분석 중" / "곡 검색 중" 단계 메시지 |
| 5000ms+ | timeout fallback + 재시도 CTA |

`/recommend` 추천 fetch 단계 메시지 후보 ("음역대 분석 중", "곡 후보 검색 중", "정렬 중") — 시나리오 C 의 production polish.

### 7-6) empty state

| 페이지 | 현재 | 후보 |
|---|---|---|
| `/recommend` 0 | 음역대 재입력 CTA | (현재 4점 유지) |
| `/songs` 검색어 없음 | "검색어 입력" | popular 검색어 chip + 인기 곡 시드 |
| `/songs` 결과 0 | "검색 결과 없어요" | popular 검색어 chip + "필터 초기화" CTA |
| `/likes` 0 | "둘러보기" link | illustration + "곡 검색 / 추천 받기" 2 CTA |
| `/bookmarks` 0 | 동일 | 동일 |
| `/history` 0 | "첫 측정 시작" | illustration + "음역대 측정" primary CTA |

illustration 산출물 = 사용자 결정 (§8 Q2). line / flat / 3D / 사용자 directive 의존.

### 7-7) toast / notification 시스템

- 현재: inline alert (mutation error 등) + Button 자체 spinner. 우상단 stack toast 없음.
- 후보 도입처:
  - 추천 fetch 완료 → "10곡 추천 받았어요" 토스트 (3s)
  - 좋아요 토글 → "{곡명} 좋아요!" 토스트 (2s)
  - 측정 완료 → "음역대 저장됨" 토스트 (2s) + `/recommend` push
  - 네트워크 에러 → 빨간 토스트 + 재시도 CTA
- 라이브러리: `react-hot-toast` (gzipped ~5KB) vs custom 자체 (의존 0). 의존성 정책 정합 = 자체 — **권고**: 자체 `<ToastStack>` + zustand store (`useToastStore`) 단순 구현.

### 7-8) 추천 progress 단계

`/recommend` 의 fetch 가 1000ms+ 일 때 단계 표시. 다음 단계 박제 후보:

1. "음역대 분석 중" (요청 직후)
2. "곡 후보 검색 중" (BE search 단계 — BE 응답 없으므로 클라이언트 timer 기반)
3. "최적 정렬 중" (응답 받기 직전)

BE 응답 metadata 부재 → 클라이언트 timer 기반 의사 단계 (1000ms / 2500ms / 4000ms). 진짜 progress 가 아닌 perceived progress — 사용자 인지 부담 ↓.

## 8) §6 가독성 / 접근성 sweep

### 8-1) contrast (WCAG)

후속 sweep 후보 페어 (실측 = lighthouse / axe):

| 페어 | light | dark | 평가 |
|---|---|---|---|
| `--text-primary` / `--bg-base` | #18181b / #ffffff | #fafafa / #09090b | AAA |
| `--text-secondary` / `--bg-base` | #52525b / #ffffff | #d4d4d8 / #09090b | AA |
| `--text-caption` / `--bg-base` | #71717a / #ffffff | #a1a1aa / #09090b | AA |
| `--text-disclaimer` / `--bg-base` | #71717a / #ffffff | #71717a / #09090b | dark = 미달 risk |
| `--badge-success-fg` / `--badge-success-bg` | #047857 / #d1fae5 | #a7f3d0 / emerald-900/50 | AA |
| `--brand-500` / `--bg-base` | #6366f1 / #ffffff | #6366f1 / #09090b | AA |

→ `--text-disclaimer` dark 페어 = AAA 미달 risk (zinc-500 light/dark 균일 — 의도된 흐림). 후속 lighthouse 측정 필요.

### 8-2) line-height

- 본문 (`--leading-normal: 1.5`) — 적용 다수
- 제목 (`--leading-tight: 1.25`) — 적용 다수
- 긴 설명 (`--leading-relaxed: 1.75`) — 적용 0건 → 추천 사유 / 곡 상세 본문 후보

### 8-3) max-width

- 본문 max-width 65ch 적용 0건 — 모든 페이지가 `max-w-md` (28rem) / `max-w-2xl` (42rem) / `max-w-3xl` (48rem) 사용. 본문 가독성 (65ch ≈ 50rem) 와 미정렬.
- 결정 후보 — `max-w-prose` (Tailwind 65ch) 적용처 평가 (`/songs/[id]` deep-link 의 추천 사유 본문 등).

### 8-4) focus ring 일관성

- `--cta-secondary-ring` (zinc-500 균일) 채택 (Button / ThemeToggle / BottomNav 정렬 완료). 잔존 페어 = `design-tokens-residual-swap-matrix.md §5-1` sub-PR 3 진행 중.

### 8-5) font-weight

- regular (400) / medium (500) / semibold (600) / bold (700) / black (800) 정의 충분. 사용처 grep 후속.

## 9) §7 후속 PR 분할 (5-8 PR 매트릭스)

본 spec audit 결과 + entry-flow-browse-first.md 정합 + `ui-ux-redesign.md §6` PR backlog 정합 + `design-tokens-residual-swap-matrix.md` 게이트 정합 후 분할:

| PR | 이름 | 의존 | LOC | 우선순위 | 회귀 risk | 비고 |
|---|---|---|---:|---|---|---|
| 1 | skeleton 적용 확장 (`/songs` / `/history`) | 0 | ~80 | P1 | 낮음 | `<Skeleton />` 재사용 — 새 컴포넌트 0 |
| 2 | route transition view-transition-name 도입 평가 + 적용 | §8 Q1 결정 | ~120 | P3 | 중 | Next.js 16 native API + fallback CSS fade |
| 3 | 마이크로 인터랙션 (카드 hover lift / brand ring) | tokens 완결 후 | ~100 | P2 | 낮음 | `--shadow-brand` 재사용 |
| 4 | 음역대 측정 실시간 indicator (waveform + circular ring) | `ui-ux-redesign-pr-5-pitch-wave.md` SoT | ~250 | P1 | 중 | PR 5 단독 spec SoT 우선 |
| 5 | toast 시스템 (자체 ToastStack + useToastStore) | 0 | ~150 | P2 | 낮음 | recommend 결과 / 측정 완료 / 에러 |
| 6 | 가독성 sweep (line-height / max-width / contrast) | tokens 완결 후 | ~100 | P3 | 낮음 | axe / lighthouse 측정 동반 |
| 7 | empty state illustration + CTA 일관 패턴 | §8 Q2 결정 | ~200 | P2 | 낮음 | 4 페이지 (likes / bookmarks / songs / history) |
| 8 | entry-flow-browse-first 곡 목록 grid + filter polish | entry-flow §5-5 Q1-Q6 결정 | ~300 | P2 | 중 | `<BrowseFace>` 시각 polish |

### 9-1) 후속 PR 진입 게이트

1. **PR 1 / PR 5** — 의존 0, 즉시 진입 가능 (사용자 결정 의존 0).
2. **PR 3 / PR 6** — `design-tokens-residual-swap-matrix.md §5-3` 4 조건 게이트 통과 후.
3. **PR 2** — §8 Q1 결정 후.
4. **PR 4** — `ui-ux-redesign-pr-5-pitch-wave.md` SoT 진입.
5. **PR 7** — §8 Q2 결정 후.
6. **PR 8** — entry-flow-browse-first.md §5-5 Q1-Q6 결정 후.

### 9-2) 보호 영역 변경 여부 (필수 명시)

- 보호 영역 변경 여부: ☑ 없음
  - 본 spec = docs/features/* 만. 후속 PR 1-8 도 `web/app/**` + `web/components/**` + 테스트 파일만 변경 (라이브러리 도입 시 `web/package.json` 변경 — PR 2 view-transition (native) / PR 4 pitch wave (Web Audio API native) / PR 5 toast (자체 권고) 가 의존 0. PR 2 Framer Motion 도입 시 `package.json` + lockfile 변경 → 보호 영역, 그 경우 별 게이트).

## 10) §8 사용자 결정 필요 항목 (nmae 가 사용자에게 직접 ask)

> 본 spec 본문에 "오픈 질문" 박제 X (사용자 정정 2026-05-31 — CLAUDE.md §10).
> 다음 항목은 nmae 가 사용자에게 직접 ask 하여 본 spec §7 / §9 갱신.

1. **Q1 — route transition 강도** (§7-2 결정)
   - (a) 현 `RouteTransition` fade-in 만 유지 — 가장 절제, 의존 0
   - (b) view-transition-name 추가 (Chrome / Safari 16+) — fallback CSS fade, Next.js 16 native API 평가
   - (c) Framer Motion 도입 (gzipped ~40KB) — 풀 애니메이션 routes, 보호 영역 `package.json` 변경
   - 결정 영향: PR 2 진입 분기

2. **Q2 — empty state illustration 스타일** (§7-6 결정)
   - (a) line illustration (단순 SVG, 의존 0, AI 생성 가능)
   - (b) flat illustration (색 채워진 SVG, 의존 0)
   - (c) 3D / 입체 (라이브러리 lottie 등 의존 ↑)
   - (d) illustration 도입 X — 텍스트 + CTA 만 일관 패턴화
   - 결정 영향: PR 7 진입 분기 + 디자인 산출물 의뢰 의무

3. **Q3 — 음역대 측정 실시간 viz 패턴** (§7-4 결정 — `ui-ux-redesign-pr-5-pitch-wave.md` SoT 와 정합)
   - (a) waveform (Canvas, Web Audio AnalyserNode getFloatTimeDomainData)
   - (b) circular pitch ring (SVG, 60fps stroke-dashoffset)
   - (c) pitch indicator bar (수직 bar + dot)
   - (d) 세 패턴 조합 — circular ring 메인 + waveform 보조
   - 결정 영향: PR 4 spec 진입 + canvas vs SVG 결정

4. **Q4 — page transition 강도** (Q1 (b) (c) 선택 시 sub-question)
   - (a) subtle fade (200ms)
   - (b) slide (300ms left-to-right)
   - (c) morph (album cover hero — view-transition-name)
   - 결정 영향: PR 2 의 시각 강도

5. **Q5 — brand 색상 보강 여부** (§4 systematization 후속)
   - 현재 `--brand-500` (indigo-500) 단일 brand. brand-gradient 도 indigo → violet 단일.
   - (a) 현 단일 brand 유지 — production-grade 충분
   - (b) 보조 색 도입 — emerald / amber / rose 등 secondary accent (mood 별 카드 색 분기 등)
   - (c) 사용자 제안 (특정 mood 카드 hot pink / 차분한 곡 emerald 등)
   - 결정 영향: ADR-0018 보강 PR + tokens.css 확장

6. **Q6 — toast 시스템 라이브러리 선택** (§7-7 결정)
   - (a) 자체 `<ToastStack>` + zustand store (의존 0) — 권고
   - (b) react-hot-toast (gzipped ~5KB) — 보호 영역 `package.json` 변경
   - (c) sonner (gzipped ~10KB)
   - 결정 영향: PR 5 진입 + 라이브러리 의존

7. **Q7 — loading 단계화 메시지 문구** (§7-5 결정)
   - (a) "음역대 분석 중 / 곡 후보 검색 중 / 최적 정렬 중" — 한국어 동작 동사
   - (b) "잠시만 기다려주세요…" 일관 — 단순화
   - (c) 단계 표시 X — skeleton 만 유지
   - 결정 영향: PR 5 toast + recommend page 본문

## 11) 작업 분할 (예상 PR 리스트)

§9 매트릭스 SoT. 본 PR 자체 = sub-PR 0 (audit + spec 박제).

- [x] **PR 0 (본 PR, docs, plan)** — 본 spec 신설.
- [ ] **PR 1 (fe)** — skeleton 적용 확장 (`/songs` / `/history`).
- [ ] **PR 2 (fe)** — route transition view-transition-name 도입 평가 + 적용 (Q1 결정 후).
- [ ] **PR 3 (fe)** — 마이크로 인터랙션 (카드 hover lift / brand ring) — tokens swap 완결 후.
- [ ] **PR 4 (fe)** — 음역대 측정 실시간 indicator — `ui-ux-redesign-pr-5-pitch-wave.md` SoT 우선 진입.
- [ ] **PR 5 (fe)** — toast 시스템 — Q6 결정 후.
- [ ] **PR 6 (fe)** — 가독성 sweep (line-height / max-width / contrast).
- [ ] **PR 7 (fe)** — empty state illustration + CTA 일관 패턴 — Q2 결정 후.
- [ ] **PR 8 (fe)** — entry-flow-browse-first 곡 목록 grid + filter polish — entry-flow §5-5 Q1-Q6 결정 후.

### 보호 영역 변경 여부 (필수 명시)

- 보호 영역 변경 여부: ☑ 없음 (본 PR). 후속 PR 별 별도 명시 (§9-2 참조).

## 12) 테스트 전략

본 spec 자체 = docs (테스트 N/A). 후속 PR 별 테스트 룰:

- **PR 1 (skeleton 확장)** — 페이지 컴포넌트 mount 시 `<Skeleton />` 렌더 verify + isPending false 후 사라짐 verify.
- **PR 2 (route transition)** — `RouteTransition` 회귀 없음 + view-transition-name 적용 시 native API call mock.
- **PR 3 (micro-interaction)** — hover 시 CSS class 변화 verify (testing-library + jsdom).
- **PR 4 (pitch wave)** — `ui-ux-redesign-pr-5-pitch-wave.md` SoT.
- **PR 5 (toast)** — `useToastStore` reducer 단위 + `<ToastStack>` mount / dismiss / queue 시뮬레이션.
- **PR 6 (가독성)** — lighthouse / axe 측정 동반 (수동) + line-height / max-width CSS class 회귀.
- **PR 7 (empty state)** — 페이지별 empty 분기 시 illustration + CTA 2-button 렌더 verify.
- **PR 8 (browse face polish)** — `entry-flow-browse-first.md §7` 시나리오 1-5 E2E + 시각 회귀.

### visual regression (`visual-regression-ci.md`)

- 모든 본 spec 후속 PR = 의도된 baseline drift. PR body 의 `## visual baseline update` 섹션에 N 페이지 / 사유 명시.

## 13) 결정 로그

- **2026-06-03**: 초안 작성 (status=draft). 사용자 directive 2026-06-03 ("UX 점검 + production polish + 동적 UI") 수신. 8 페이지 audit 매트릭스 + 분기처리 일관성 + 디자인 systematization 평가 + 동적 UI 매트릭스 + 가독성 sweep + 5-8 PR 분할 박제. `ui-ux-redesign.md §6` PR backlog + `design-tokens-residual-swap-matrix.md` 게이트 + `entry-flow-browse-first.md` 정책 의존 cross-ref 강화. 사용자 결정 항목 7건 (§10) — nmae 가 사용자에게 직접 ask 의무.

## 14) cross-ref

- 상위 PR backlog SoT: `ui-ux-redesign.md`.
- 단계 4 swap 완결 게이트 SoT: `design-tokens-residual-swap-matrix.md`.
- 첫 face 정책 SoT: `entry-flow-browse-first.md`.
- 마이크로 인터랙션 PR 단독 spec: `ui-ux-redesign-pr-5-pitch-wave.md` (Q3), `ui-ux-redesign-pr-4-bottom-sheet.md`, `ui-ux-redesign-pr-2-9-component-matrix.md`.
- 디자인 토큰 정의 SoT: `docs/decisions/0018-design-tokens.md`.
- 측정 method picker 진입점 정렬 의존: `voice-range-method-picker.md` (#1603).
- 페르소나 sub-flow 진입 결정 의존: `first-user-onboarding-flow.md`.
- 익명 세션 / 보안 무관: 본 spec scope 밖.
- 관련 PR (history): #1493 (skeleton + route transition), #1310 (Button focus ring), #1219 (recommend modal 토큰), #1131 (tokens 도입).
