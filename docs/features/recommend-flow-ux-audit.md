---
feature: 추천 플로우 전수 UX 점검 + 프로덕션 완성도 개선안
slug: recommend-flow-ux-audit
status: draft
owner: @goohong
scope: web
related_issues: []
related_prs: []
last_reviewed: 2026-06-03
---

# 추천 플로우 전수 UX 점검 + 프로덕션 완성도 개선안

> rev 사이클 선행 점검 산출물 (directive 1511631518450323536, 2026-06-03). 구현은 fe 사이클이
> 후속 PR 단위로 받는다 — 본 문서는 점검 결과 + 우선순위·난이도별 개선안 항목화까지.

## 1) 개요 (What / Why)

기능이 하나씩 붙으며 추천 플로우(음역대 입력 → 결과)의 분기·정보 밀도가 누적됐다. 본 점검은
web/ 추천 플로우 전 화면(진입 → 음역대 입력 → 결과 → 필터/카드/스와이프/모달)을 전수 코드 점검해
프로덕션 완성도 기준(디자인 시스템 일관성·스켈레톤/로딩·마이크로 인터랙션·반응형·접근성)으로
갭을 정리하고, fe 사이클이 PR 단위로 받을 수 있게 항목화한다.

**점검 범위 화면 (코드 경로)**
- 진입: `web/app/page.tsx` (홈) + `web/app/components/OnboardingIntentPicker.tsx`
- 음역대 입력: `web/app/voice-range/page.tsx` (수동) + `web/app/voice-range/auto/page.tsx` (마이크)
- 결과: `web/app/recommend/page.tsx`
- 컴포넌트: `RecommendFilters` / `SongCard`(+Skeleton) / `SwipeDeck` / `SongDetailModal` / `SongDetailContent`
- 디자인 토큰: `web/app/tokens.css` / `web/app/globals.css`

**점검 결과 요약**: 접근성·결정성·상태 분기는 이미 상당히 성숙하다(aria-live·role·reduced-motion
가드·낙관 토글 등). 갭은 주로 **(1) 카피 ↔ 실제 입력 차원 불일치, (2) 신규 primitive(shimmer)
미채택, (3) 시맨틱 색 토큰화 누락, (4) 마이크로 인터랙션 적용 편차** 에 몰려 있다.

본 문서는 **이미 추적 중인 작업과 중복하지 않는다**:
- zinc → token 1:1 swap (SongCard 포함)은 [[design-tokens-residual-swap-matrix]] sub-PR 4 가 SoT.
- 본 문서는 그 매트릭스가 다루지 않는 **시맨틱 색(rose/amber/emerald) 토큰화 + 카피/마이크로
  인터랙션/반응형 갭** 을 다룬다.

## 2) 점검 결과 — 발견 갭 (전수)

### A. 분기 / 정보 일관성

**A-1. 입력 차원 카피 ↔ 실제 UI 불일치 (🔴 우선)**
- 홈 `FlowStep` step 2 카피: "분위기·성별·속도 선택" (`app/page.tsx:134`).
- CLAUDE.md §1 + 본 directive: "음역대·성별·분위기 기반".
- 실제 `RecommendFilters`: **분위기(mood 6종) + 나이대(ageGroup 6종)** 만. 성별·속도(tempo) 입력 UI 부재.
- BE `RecommendationCreateRequest` (`lib/api/recommendation.ts:70`): `voiceRangeLow/High` · `mood` ·
  `ageGroup` · `excludeSongIds` 만 지원 — **gender / tempo 필드 자체가 없음**.
- 갭: 사용자에게 약속한 입력 차원(성별·속도)이 UI·BE 어디에도 없다. 카피가 기능을 앞질렀다.
- 결정 필요: (a) 카피를 실제(분위기·나이대)에 맞춰 정정 / (b) 성별·tempo 필터를 BE 신호와 함께 신설.

**A-2. 페르소나 카피 ↔ 라우팅 불일치 (🔴 우선)**
- `OnboardingIntentPicker` 3 페르소나 카드(BEGINNER/PRACTICE/MOOD)의 `PATH_DESTINATION` 이
  **전부 `/voice-range/auto`** (`OnboardingIntentPicker.tsx:60-64`).
- 특히 MOOD 카드 카피 "목소리는 마지막에 가볍게"(`:50`)는 음역대 측정을 뒤로 미룬다고 약속하지만,
  실제로는 클릭 즉시 음역대 측정 화면으로 직행 → 사용자 기대 배신.
- 의도된 graceful fallback(자식 경로 미구현)이나, 카피가 미구현 경로를 단정적으로 약속하는 게 문제.
- 결정 필요: 자식 경로 구현 전까지 MOOD 카피를 측정-우선과 모순 없게 톤다운 or 분기 목적지 차등화.

**A-3. "Step" 표기 일관성 (🟡)**
- 수동 입력 = "Step 1"(`voice-range/page.tsx:134`), 추천 결과 = "Step 2"(`recommend/page.tsx:289`),
  자동 측정 = "자동 측정"(step 표기 없음). 재방문 사용자는 홈에서 바로 /recommend 진입 → "Step 2"
  부터 봄 → 단계감 혼동. 자동/수동/재방문 경로의 step 표기 규칙 통일 필요.

### B. 빈 / 로딩 / 에러 상태

**B-1. shimmer primitive 미채택 — 로딩 트리트먼트 비일관 (🔴 우선)**
- `#1493`(cb8515f)로 `.animate-shimmer` + `components/ui/Skeleton.tsx` primitive 도입.
- 그런데 **핵심 추천 플로우의 `SongCardSkeleton`**(`SongCard.tsx:661-687`)과 `/songs/[id]` skeleton은
  여전히 구식 `animate-pulse` + `bg-[var(--bg-muted)]`/`bg-[var(--meter-track-bg)]` 사용.
- 결과: 사용자가 가장 자주 보는 추천 로딩 화면이 새 디자인 언어(빛 흐름)와 따로 논다.
- 개선: SongCardSkeleton + songs/[id] skeleton 을 `Skeleton` primitive(또는 `.animate-shimmer`)로 교체.

**B-2. 첫-빈 vs 소진-빈 카피 (🟡)**
- 첫 페이지부터 빈 결과: "더 이상 추천할 곡이 없어요. 음역대를 다시 입력해 보세요."(`recommend/page.tsx:523`).
- 신규 사용자가 처음 받은 결과가 0건일 때 "더 이상"은 어색(이전에 본 적 없음). 첫-빈 케이스 전용
  카피(예: "조건에 맞는 곡을 찾지 못했어요") 분리 권장.

**B-3. voice-range 로딩 = 전체화면 텍스트 StatusShell (🟡)**
- `recommend/page.tsx:236` voice-range fetch 중 헤더·필터 골격 없이 "음역대를 불러오는 중..." 텍스트만.
- prime 캐시 히트 경로가 대부분이라 실제 노출은 짧지만, cold 진입 시 레이아웃 점프. 헤더/필터 골격을
  유지한 채 피드 자리에만 skeleton 노출이 공간 인지에 유리.

### C. 디자인 시스템 일관성

**C-1. 시맨틱 색(rose/amber/emerald) 토큰화 누락 (🟡, swap-matrix 추적 밖)**
- `tokens.css` 는 brand/neutral/badge/danger 까지 정의돼 있으나 **피드백·난이도 시맨틱 색은 미정의**:
  - `LikeButton` rose 계열(`SongCard.tsx:356-360`)
  - `BookmarkButton` amber 계열(`:411-415`)
  - `DifficultyBadge` emerald/amber/rose(`:609-618`)
  - `SwipeDeck` SwipeHint emerald-500/rose-500(`SwipeDeck.tsx:347-351`)
- swap-matrix 는 zinc 만 추적하므로 이 시맨틱 색은 어디서도 추적되지 않는 사각지대.
- 개선: `--like-*` / `--bookmark-*` / `--difficulty-{easy,normal,hard}-*` 시맨틱 토큰 신설 후 swap.
  (ADR-0018 color 카테고리 내 alias — 새 카테고리 아님)

**C-2. SongCard zinc 잔여 (참조만 — 중복 아님)**
- `hover:ring-zinc-300` / `focus-within:ring-zinc-400` 등은 [[design-tokens-residual-swap-matrix]]
  sub-PR 4 가 이미 SoT. **본 문서에서 별도 PR로 다루지 않음** — C-1(시맨틱 색)과 함께 묶을지 여부만
  fe 가 sub-PR 4 진입 시 판단.

### D. 마이크로 인터랙션

**D-1. 리스트 결과 진입 모션 부재 (🟢)**
- `globals.css` 에 `animate-fade-in` / `fade-in-up` / `scale-in` 유틸이 있고 reduced-motion 가드도 완비.
- 그러나 리스트 모드 추천 카드(`recommend/page.tsx:574-582`)는 진입 모션 없음. 스와이프 덱만 모션 충실.
- 개선: 첫 페이지 카드 stagger fade-in-up(절제), 추가 batch 도착 시 신규 카드만 fade-in.

**D-2. 좋아요/필터 토글 마이크로 피드백 (🟢)**
- 좋아요 탭 시 하트 상태만 토글(애니메이션 없음), 필터 칩 토글도 색 전환만. 절제된 bounce/scale 여지.
- (스와이프 카드 active:scale·exit transform 은 이미 양호 — 동일 톤을 다른 인터랙션에도 확장)

### E. 반응형

**E-1. 데스크탑 단일 컬럼 (🟢)**
- 결과 `max-w-2xl` 단일 컬럼(`recommend/page.tsx:287`), 입력 `max-w-md`. 데스크탑 넓은 화면에서
  추천 리스트가 2-column grid 로 정보 밀도를 높일 여지. (모바일 우선 PWA 라 우선순위 낮음)

### F. 접근성 (대체로 양호 — 큰 갭 없음)

- aria-live polite 영역(추천 도착 announce) / role="status,alert" / aria-pressed 토글 /
  reduced-motion 가드 / meter·progressbar role / sr-only 라이브 영역 — 이미 성숙.
- 경미: 필터 활성 개수 표시·초기화 버튼 없음(F-1, 🟢). 큰 결함 아님.

## 3) 개선안 항목화 (fe 사이클 PR 분할 — 우선순위 · 난이도)

| 항목 | 내용 | 우선순위 | 난이도 | 의존 | 비고 |
|---|---|---|---|---|---|
| **P1** | A-1 입력 차원 카피 정정 (성별·속도 → 분위기·나이대) **or** 필터 확장 결정 | 🔴 P0 | 카피=S / 필터확장=L | BE(필터확장 시) | 결정 게이트 필요 — §4 Q1 |
| **P2** | A-2 페르소나 MOOD 카피 ↔ 라우팅 모순 해소 | 🔴 P0 | S | — | 카피 톤다운 or 목적지 차등 |
| **P3** | B-1 SongCardSkeleton + songs/[id] → shimmer primitive 교체 | 🔴 P1 | S | #1493 | 로딩 일관성 즉효 |
| **P4** | C-1 시맨틱 색 토큰 신설(`--like/bookmark/difficulty-*`) + swap | 🟡 P1 | M | ADR-0018 | swap-matrix 사각지대 |
| **P5** | B-2 첫-빈 vs 소진-빈 카피 분리 | 🟡 P2 | S | — | 신규 사용자 혼동 완화 |
| **P6** | B-3 voice-range 로딩 헤더/필터 골격 유지 + 피드 skeleton | 🟡 P2 | S | — | cold 진입 레이아웃 점프 ↓ |
| **P7** | A-3 Step 표기 규칙 통일(자동/수동/재방문) | 🟡 P2 | S | — | 단계감 일관 |
| **P8** | D-1 리스트 카드 진입 stagger fade-in (절제) | 🟢 P3 | S | — | reduced-motion 가드 재사용 |
| **P9** | D-2 좋아요/필터 토글 마이크로 피드백 | 🟢 P3 | S | — | 톤은 스와이프 카드 기준 |
| **P10** | E-1 데스크탑 2-column grid | 🟢 P3 | M | — | 모바일 우선이라 후순위 |

**권장 착수 순서**: P1·P2(카피 결정·즉효) → P3(shimmer, 회귀 risk 낮음) → P4(토큰, ADR 동반) →
P5·P6·P7(상태/카피) → P8·P9·P10(폴리시).

## 4) 오픈 질문 (결정 게이트)

| # | 질문 | 선택지 | 담당 |
|---|---|---|---|
| Q1 | A-1 입력 차원 — 카피 정정 vs 필터 확장 | (a) 카피를 분위기·나이대로 정정 (S, 회귀 0) / (b) 성별·tempo 필터 + BE 신호 신설 (L) | @goohong |
| Q2 | A-2 페르소나 — 카피 톤다운 vs 목적지 차등 | (a) MOOD 카피를 측정-우선과 모순 없게 수정 / (b) 자식 경로(F1/F2/F3) 구현해 목적지 차등 | @goohong |
| Q3 | C-2 SongCard zinc swap 과 C-1 시맨틱 색 swap 묶을지 | (a) sub-PR 4(zinc) 와 동일 PR / (b) 별도 PR | fe (sub-PR 4 진입 시) |

### 자율 결정 default (사용자 부재 가정)
- Q1 → (a) 카피 정정 우선 — 회귀 0 + 즉효. 필터 확장은 BE 신호 합의 후 별도 사이클.
- Q2 → (a) 카피 톤다운 우선 — 자식 경로 구현은 별도 대형 작업.
- Q3 → (b) 별도 PR — zinc(무채색 1:1 swap)와 시맨틱 색(신규 토큰 결정) 은 성격이 달라 혼동 회피.

## 5) 범위 / 비범위

### 포함
- 추천 플로우 전 화면 UX 점검 결과 + 프로덕션 완성도 갭 + fe 사이클용 우선순위·난이도 항목화.

### 제외
- **구현** — fe 사이클 후속 (본 문서는 rev 점검·개선안 정리까지).
- **zinc → token 1:1 swap** — [[design-tokens-residual-swap-matrix]] SoT (중복 금지).
- **BE 신규 신호(gender/tempo) 알고리즘** — A-1 (b) 채택 시 별도 BE 사이클 + Feature Spec.
- **visual regression CI(Percy/Chromatic)** — ui-ux-redesign.md §7 범위.

## 6) 결정 로그
- **2026-06-03**: rev 사이클 전수 점검 초안(status=draft). 10개 개선 항목 우선순위·난이도 항목화.
  접근성·상태 분기는 성숙, 갭은 카피-기능 불일치(A-1/A-2)·shimmer 미채택(B-1)·시맨틱 색 토큰 누락
  (C-1)에 집중. zinc swap 은 swap-matrix 와 중복 회피(참조만).
