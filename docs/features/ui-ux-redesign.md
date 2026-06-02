---
feature: UI/UX 디자인 4단계 부활 (audit → ref → spec → 구현)
slug: ui-ux-redesign
status: approved
owner: @goohong
scope: web
related_issues: [1044]
related_prs: []
last_reviewed: 2026-05-29
---

# UI/UX 디자인 4단계 부활 (audit → ref → spec → 구현)

## 1) 개요 (What / Why)

사용자 P0 요구 (2026-05-24 인계): **"디자인과 UI/UX 도 이제는 더 쫀득하게 바꿀 때가 되지 않았나 싶어. 토스나 유명 서비스들처럼 좋은 디자인과 전환을 차용해서, 더 쓰고 싶은 서비스를 만들어 줘."**

현 `web/**` 은 기능 위주로 구현되어 디자인 시스템이 hardcode 로 운영 중 — issue #1044 rev audit 에서 29 findings (13🔴 + 11🟡 + 5🟢) 적출. 본 spec 은 4단계 진행 계획을 박제하고 단계 2-4 의 후속 작업을 정의한다.

대상 액터: web 사용자 (모바일 우선, 노래방 부스 = 어두운 환경 + 한 손 조작).
해결할 문제:
- 시각 첫인상이 "회색 admin" 톤 → 노래방 도메인 정체성 부재
- micro-interaction 0건 → "살아있는 서비스" 체감 약함
- 음역대 측정 시각화 부재 → 측정 자체가 "재미 없는 form"
- 데스크탑/태블릿 nav 부재 → 사용자 이탈 risk

## 2) 사용자 시나리오

### 시나리오 A: K-pop 좋아하는 20대 여성 — "첫 진입 → 측정 → 추천 wow"
"노래방 가기 전 친구에게 추천받아 mobruji 첫 진입. 홈 화면에서 'mobruji' wordmark + 마이크/네온 모티프 hero 가 즉시 '아 음악 서비스구나' 인지. '음역대 측정 시작' CTA 가 indigo gradient + spring scale 로 강한 시각 hook. 측정 wizard 4 step 마다 progress ring 이 채워지면서 본인 pitch 가 실시간 wave 시각화. 측정 완료 시 ✅ + sparkle. 추천 결과는 stagger fade-in 으로 카드가 차례차례 등장."

### 시나리오 B: 음정 약한 30대 남성 — "재방문 → 좋아요 collection"
"이전에 측정한 음역대로 받은 추천 곡 중 마음에 들었던 곡들을 좋아요 갤러리에서 다시 본다. 좋아요 카드는 앨범 그라데이션 stripe 로 시각 hub. 곡을 탭하면 bottom sheet 가 drag handle + slide-from-bottom 으로 진입 — 토스 패턴. 노래방에서 부르고 싶은 순서대로 북마크. 다음 방문 시 북마크 탭으로 1탭 진입."

### 시나리오 C: 데스크탑 사용자 — "곡 검색 + 비교"
"PC 에서 mobruji 접속. 데스크탑 글로벌 nav (현재 부재) 로 페이지 간 이동. 검색 결과는 grid 2-column 활용으로 한 번에 더 많은 곡 확인. 카드 hover 시 곡 score breakdown preview."

## 3) 요구사항

### 기능 요구사항

#### 단계 1 — audit (rev sub-agent, 완료)
- [x] 현 `web/**` 컴포넌트 inventory (~22건 / ~6.2k LOC)
- [x] 6 카테고리 audit (시각 계층 / 인터랙션 / 전환 / 반응성 / a11y / 브랜드)
- [x] 29 findings 분류 (13🔴 + 11🟡 + 5🟢)
- [x] 단계 2-4 후속 매핑

#### 단계 2 — ref 분석 + 디자인 시스템 결정 (plan sub-agent, 본 spec + ADR-0018)
- [x] 토스 / 카카오뱅크 / 멜론·스포티파이 visual ref 분석 (텍스트 기반)
- [x] design tokens 결정 (`docs/decisions/0018-design-tokens.md`):
  - color (brand indigo-violet gradient + semantic 4 + neutral)
  - typography (Pretendard + Major Third scale 8 step)
  - spacing (4px base)
  - radius (5 단계)
  - shadow (4 elevation + brand 강조)
  - motion (4 duration + 4 easing curve + reduced-motion 대응)

#### 단계 3 — 페이지별 redesign spec (plan sub-agent, 본 spec §5-6)
- [x] 페르소나 task 매핑 (시나리오 A/B/C — §2)
- [x] 컴포넌트별 redesign goal (§5-1) + PR backlog (§6)
- [x] visual ref 인용표 (§5-3)

#### 단계 4 — 컴포넌트별 PR 분할 구현 (fe sub-agent, 9 PR 순차 — §6)
- [x] PR 1: design tokens 도입 (`app/globals.css` + `lib/theme/tokens.ts`) — **shipped (PR #1131 2026-05-26)**
- **잔존 zinc swap 추적**: `docs/features/design-tokens-residual-swap-matrix.md` SoT — 현 활성 41 / 10 파일 / 4 신규 토큰 결정 대기 (2026-05-29). 단계 4 PR 2-9 (Button/SongCard redesign goal) 진입 전 swap 완결 게이트 통과 의무.
- **PR 2-9 세부 spec 분할 (2026-05-29)** — 본 spec §6 의 PR 별 골자는 다음 3 SoT 로 분할 (per-PR 세부 — 변경 / 회귀 가드 / 자율 결정 default):
  - `docs/features/ui-ux-redesign-pr-2-9-component-matrix.md` — PR 2 / 3 / 6 / 7 / 8 / 9 묶음 matrix (작은 LOC + 단일 컴포넌트 영향)
  - `docs/features/ui-ux-redesign-pr-4-bottom-sheet.md` — PR 4 단독 (drag handle / swipe-to-close / focus trap / scroll lock 5 회귀 가드)
  - `docs/features/ui-ux-redesign-pr-5-pitch-wave.md` — PR 5 단독 (Web Audio 회귀 / 60fps SVG ring / 알고리즘 결정성)
- [ ] PR 2: Button — press scale + brand color + spring transition (세부: `pr-2-9-component-matrix.md §5-1 PR 2`)
- [ ] PR 3: SongCard — gradient stripe + stagger fade-in + active scale (세부: `pr-2-9-component-matrix.md §5-1 PR 3`)
- [ ] PR 4: SongDetailModal → bottom sheet (drag handle + slide-from-bottom) (세부: `pr-4-bottom-sheet.md` SoT)
- [ ] PR 5: VoiceRangeAuto — pitch wave 시각화 + circular progress ring (세부: `pr-5-pitch-wave.md` SoT)
- [ ] PR 6: LikeButton — heart pop + sparkle + spring (세부: `pr-2-9-component-matrix.md §5-1 PR 6`)
- [ ] PR 7: page transitions (view-transition-api + hero morph) (세부: `pr-2-9-component-matrix.md §5-1 PR 7`)
- [ ] PR 8: brand wordmark + logo SVG icon set (세부: `pr-2-9-component-matrix.md §5-1 PR 8`)
- [ ] PR 9: 폰트 (Pretendard + Geist 통합) (세부: `pr-2-9-component-matrix.md §5-1 PR 9`)

### 비기능 요구사항

- **성능**: 모바일 LCP < 2.5s 유지. 폰트 추가 (Pretendard ~2MB) 후 First Paint regression 없어야 함 (font-display: swap + preload).
- **a11y**: WCAG 2.1 AA 유지 (현 색 대비 검증 부재 → 본 4단계 안에 lighthouse / axe 측정 실측 보강). `prefers-reduced-motion` 대응 의무 (motion sensitive 사용자 보호).
- **접근성**: focus state / aria attribute 기존 수준 유지 + wizard `aria-current="step"` 보강 (현 부재).
- **다크 모드**: 기존 `@custom-variant dark` 유지 + 토큰 자동 swap. 사용자별 토글 (`ThemeToggle`) 정상 동작.
- **국제화 준비**: 한글 + 영문 폰트 통합 (Pretendard + Geist). 향후 일본어/중국어 확장 시 fallback chain 변경만으로 대응 가능.
- **번들 사이즈**: motion 라이브러리 (framer-motion 등) 도입 시 gzipped < 30KB 임계. 가능하면 CSS-only / view-transition-api native 우선.

## 4) 범위 / 비범위 (중요)

### 포함
- design tokens (color/typography/spacing/radius/shadow/motion) CSS variable 도입
- 9 PR 분할 — 의존성 순서대로 fe sub-agent 가 순차 진행
- 토스/카카오뱅크 visual ref 인용 (텍스트 기반 분석 — 외부 fetch 없이 일반 지식 + 패턴 정리)
- 노래방 도메인 모티프 (indigo/violet gradient, pitch wave, brand wordmark)
- a11y `prefers-reduced-motion` 대응 의무화
- 다크 모드 토큰 자동 swap
- 한글 폰트 (Pretendard) 통합

### 제외 (Out of Scope)
- **데스크탑 글로벌 nav 신설** — 본 4단계 범위 외, 별도 PR 후속 (현 모바일 우선 layout 유지)
- **brand wordmark / logo 디자인 결과물 (PR 8)** — 디자인 산출물 (svg 파일) 은 사용자가 외부 디자이너 의뢰 후 제공. 본 spec 은 도입 위치 + 폴더 구조만 정의
- **새 페이지 추가** — 본 4단계는 기존 페이지의 디자인 refactor 만. 신규 onboarding / 가이드 페이지 등은 별도 spec
- **animation 라이브러리 도입** (framer-motion 등) — CSS-only / view-transition-api 우선. 라이브러리 도입은 단계 4 PR 6~7 시 별도 결정 (사용자 인지 + 라이센스 검토)
- **a11y 자동화 (axe-core CI)** — 본 4단계는 lighthouse 수동 측정만. CI 통합은 별도 spec
- **PWA 기능 확장** — 기존 PWA manifest 유지. install prompt UX 등은 별도 spec
- **국제화 (i18n)** — 폰트 fallback chain 만 한글/영문 지원. 다국어 텍스트 분리는 별도 spec

## 5) 설계

### 5-1) 컴포넌트별 redesign goal

| 컴포넌트 | 현재 (audit) | 목표 (단계 4) | PR |
|---|---|---|---|
| `app/globals.css` | `--background`/`--foreground` 2개 + Arial fallback | 6 카테고리 token 30+ CSS variable | PR 1 |
| `Button.tsx` | `transition-colors` 만, active state 무시 | press 시 `scale-0.95` + spring 200ms + brand color | PR 2 |
| `SongCard.tsx` | 무채색 카드, stagger 없음 | 좌측 4px gradient stripe (곡 hash deterministic), 마운트 시 stagger fade-in (50ms × index), 탭 시 `scale-0.98` | PR 3 |
| `SongDetailModal.tsx` | `items-end` 로 하단 정렬만, 즉시 표시 | drag handle bar + slide-from-bottom 300ms + swipe-to-close + scroll lock | PR 4 |
| `app/voice-range/auto/page.tsx` | Hz 텍스트 + 음표명만 | circular pitch wave (lowMidi → highMidi ring), 실시간 frequency bar, 4 step progress ring | PR 5 |
| `LikeButton` (SongCard 내부) | ❤️/🤍 즉시 swap | tap 시 scale 1 → 1.3 → 1 spring + sparkle particle 3건 + haptic (지원 시) | PR 6 |
| `app/layout.tsx` + 페이지별 | Link 즉시 전환 | view-transition-api fade + hero element morph (앨범 thumb → 상세 cover) | PR 7 |
| `public/icons/` + header | text-only wordmark | "모부르지" 한글 wordmark + 마이크 + neon glow icon set | PR 8 |
| `app/globals.css` + `app/layout.tsx` | body Arial fallback | Pretendard Variable + Geist preload + font-display: swap | PR 9 |

### 5-2) 디자인 토큰 (상세 — ADR-0018)

본 spec 의 디자인 토큰 결정값은 `docs/decisions/0018-design-tokens.md` 참조. 본 절은 fe sub-agent 가 PR 1 작성 시 참조할 cheat sheet.

#### Color 사용 가이드
| 토큰 | 사용처 |
|---|---|
| `--brand-500` (#6366F1) | primary CTA, link, active state, BottomNav active icon |
| `--brand-gradient` | hero, 측정 progress ring, "wow" 시각 hub |
| `--brand-50` ~ `--brand-100` | brand 배경 (subtle), badge bg |
| `--success-500` | 좋아요 hit, 측정 완료 toast |
| `--danger-500` | 에러, 삭제, 마이크 권한 거부 |
| `--text-primary/secondary/tertiary` | 본문 텍스트 3 단계 위계 |
| `--bg-base/subtle/muted` | 페이지 / 카드 / hover 3 단계 |
| `--border` | 카드/입력 외곽선 default |

red/rose 혼용 22건 → 전부 `--danger-500` 으로 swap.

#### Typography 사용 가이드
- 페이지 h1: 모바일 `--text-2xl` / 데스크탑 `--text-3xl` (반응형 swap)
- 페이지 h2: `--text-xl`
- section 제목: `--text-lg --font-semibold`
- body: `--text-base --font-regular --leading-normal`
- step indicator: `--text-xs --font-medium tracking-widest uppercase` (현 패턴 유지)
- hero: `--text-display --font-black` (PR 8 brand wordmark 부근)

#### Motion 사용 가이드
- Button press: `transition: transform 200ms var(--ease-spring)`
- Card stagger: `animation-delay: calc(var(--index) * 50ms)`
- Modal/Sheet enter: `transition: transform 300ms var(--ease-emphasized), opacity 200ms var(--ease-out)`
- Page transition: `view-transition-name` + `300ms var(--ease-emphasized)`
- 모든 motion: `@media (prefers-reduced-motion: reduce)` block 에서 0.01ms override

### 5-3) Visual ref 인용표

| ref | 인용 패턴 | mobruji 적용 위치 |
|---|---|---|
| **토스 (Toss)** | spring easing + 부드러운 shadow (`rgba(0,0,0,0.04)`) | PR 1 (`--shadow-md`), PR 2 (Button press) |
| 토스 | bottom sheet drag handle + 4-step snap + slide-from-bottom | PR 4 (SongDetailModal) |
| 토스 | button press = `scale-0.97` + opacity 0.9 | PR 2 (Button) |
| **카카오뱅크 (KakaoBank)** | 강한 black CTA + pastel 배경 대비 | PR 2 (Button danger variant) |
| 카뱅 | stepper 위쪽 progress bar + "Step N: ..." 큰 라벨 | PR 5 (VoiceRangeAuto wizard) |
| 카뱅 | 폼 입력 즉시 ✅ check icon inline | PR 6 (LikeButton 변형 — input feedback 적용 시) |
| **멜론 / 스포티파이** | 앨범 dominant color 배경 + blurred backdrop | PR 3 (SongCard gradient stripe) |
| 스포티파이 | 곡 카드 hover 시 sparkline preview | PR 3 (옵션 — 데스크탑 한정) |
| **Apple Music** | 큰 circular play button + ripple | PR 5 (측정 시작 CTA) |
| **iOS HIG** | 44px 최소 터치 타겟 | PR 1 (`--space-11` = 44px 토큰), 전체 PR 검증 |

### 5-4) 데이터 흐름 / 컴포넌트 의존성

```
PR 1 (tokens) ─┬─→ PR 2 (Button)
               ├─→ PR 3 (SongCard) ──→ PR 6 (LikeButton)
               ├─→ PR 4 (Modal → Sheet)
               ├─→ PR 5 (VoiceRangeAuto)
               ├─→ PR 7 (page transitions)
               └─→ PR 9 (font)

PR 8 (logo/wordmark) — 외부 디자인 산출물 의존, 독립 진행 가능
```

순차 진행 룰: **PR 1 머지 완료 후** PR 2-9 진행. PR 2-9 는 token 의존만 있어서 머지 순서 자유 (단 같은 파일 충돌 회피).

### 5-5) DB 마이그레이션
해당 없음 (web 디자인 전용).

### 5-6) 프론트엔드 화면 (페이지별)

| 라우트 | 영향받는 PR | 변경 |
|---|---|---|
| `/` (홈) | 1, 2, 7, 8, 9 | tokens + Button + transition + wordmark + font |
| `/voice-range` (수동 측정) | 1, 2, 9 | tokens + Button + font |
| `/voice-range/auto` (자동 측정 wizard) | 1, 2, 5, 9 | tokens + Button + pitch wave + font |
| `/recommend` (추천 결과) | 1, 2, 3, 4, 6, 7, 9 | tokens + Button + SongCard + Sheet + LikeButton + transition + font |
| `/songs` (검색) | 1, 2, 3, 7, 9 | tokens + Button + SongCard + transition + font |
| `/history` (이력) | 1, 2, 3, 7, 9 | tokens + Button + SongCard + transition + font |
| `/likes`, `/bookmarks` | 1, 2, 3, 6, 7, 9 | tokens + Button + SongCard + LikeButton + transition + font |
| `/songs/[id]` (deep-link) | 1, 4, 7 | tokens + Sheet + transition |

## 6) 작업 분할 (예상 PR 리스트)

### 단계 4 PR backlog (의존성 순서)

#### PR 1: design tokens 도입 (필수 기반)
- 브랜치: `feat/design-tokens-#1044`
- 변경: `app/globals.css` 30+ CSS variable + `lib/theme/tokens.ts` (TypeScript 타입 export — 컴포넌트가 토큰 명 IDE 자동완성용)
- 게이트: `npm run lint && typecheck && build` 통과
- 검증: Storybook 미사용 — `npm run dev` 후 홈 페이지 색상 swap 확인 (`bg-zinc-50` → `bg-bg-subtle` 1개 컴포넌트만 시범 swap)
- LOC 예상: ~200 (CSS) + ~50 (TS)
- 의존: 없음

#### PR 2: Button — press scale + brand color + spring
- 브랜치: `feat/button-press-scale-#1044`
- 변경: `components/ui/Button.tsx` — `active:scale-95` + `transition-transform duration-200 ease-spring` + `bg-brand-500` primary variant
- 게이트: 단위 테스트 + npm test
- 검증: `npm run dev` 후 홈 CTA 버튼 클릭 시 press feedback 시각/체감
- LOC 예상: ~30 추가/수정
- 의존: PR 1

#### PR 3: SongCard — gradient stripe + stagger + active scale
- 브랜치: `feat/song-card-gradient-stagger-#1044`
- 변경: `app/recommend/components/SongCard.tsx` — 좌측 4px gradient stripe (deterministic hash → hue rotation), `animation-delay` 인덱스 기반, `active:scale-98`
- 게이트: lint + typecheck + test + build
- 검증: `/recommend` 페이지 진입 시 카드 등장 stagger 시각
- LOC 예상: ~60 추가
- 의존: PR 1

#### PR 4: SongDetailModal → Bottom Sheet
- 브랜치: `feat/song-detail-bottom-sheet-#1044`
- 변경: `app/recommend/components/SongDetailModal.tsx` — drag handle bar + slide-from-bottom 300ms + swipe-to-close (`useTouchEvent` 기본 구현 또는 CSS-only swipe)
- 게이트: lint + typecheck + test + build, focus trap / ESC / backdrop click 회귀 없음 확인
- 검증: 모바일 viewport (Chrome DevTools) 에서 카드 → 시트 진입 / drag down 시 close
- LOC 예상: ~120 (기존 231 + 추가)
- 의존: PR 1

#### PR 5: VoiceRangeAuto — pitch wave 시각화
- 브랜치: `feat/voice-range-pitch-wave-#1044`
- 변경: `app/voice-range/auto/page.tsx` + 새 `components/voice/PitchWaveRing.tsx` — circular SVG ring (lowMidi → highMidi 배치), 실시간 frequency = 막대 길이, 4 step progress = ring fill percentage
- 게이트: lint + typecheck + test + build
- 검증: `/voice-range/auto` 마이크 시뮬레이션 (testing-library 또는 수동 마이크 입력) — 음정 변경 시 ring 업데이트
- LOC 예상: ~250 (PitchWaveRing 신설) + ~50 (page.tsx 통합)
- 의존: PR 1

#### PR 6: LikeButton — heart pop + sparkle
- 브랜치: `feat/like-button-heart-pop-#1044`
- 변경: `app/recommend/components/SongCard.tsx` LikeButton 분리 후 `components/ui/HeartPop.tsx` — scale 1 → 1.3 → 1 spring, sparkle particle 3건 (CSS-only `@keyframes` 또는 SVG path)
- 게이트: lint + typecheck + test + build
- 검증: `/recommend` 좋아요 토글 시 애니메이션 시각
- LOC 예상: ~80
- 의존: PR 1, PR 3 (SongCard refactor 후)

#### PR 7: Page transitions (view-transition-api)
- 브랜치: `feat/page-transitions-#1044`
- 변경: `app/layout.tsx` + 페이지별 `view-transition-name` 속성 + `next.config.ts` 의 view-transition flag (Next.js 16 dependent — 현 버전 확인 필수)
- 게이트: lint + typecheck + test + build
- 검증: `/recommend` → `/songs/[id]` deep-link 시 앨범 thumb morph
- LOC 예상: ~100
- 의존: PR 1
- **risk**: Next.js 버전 < 16 이면 polyfill 필요. fe sub-agent 가 `web/package.json` Next 버전 확인 후 분기 (`needs-human-review` 가능)

#### PR 8: Brand wordmark + logo SVG icon set
- 브랜치: `feat/brand-wordmark-#1044`
- 변경: `public/icons/` 하위 신설 (`logo.svg` / `wordmark-ko.svg` / `wordmark-en.svg` / `favicon-512.svg`) + `components/brand/BrandWordmark.tsx`
- 게이트: lint + typecheck + test + build
- 검증: 홈 헤더에서 wordmark 표시 / favicon 적용
- LOC 예상: ~50 (component) + svg 파일
- 의존: **외부 디자인 산출물 의존** — 사용자/디자이너가 svg 제공 후 진행. 임시 placeholder svg 로 PR 가능 (PR 본문 `## 사용자 확인 필요` 명시).

#### PR 9: Font (Pretendard + Geist 통합)
- 브랜치: `feat/font-pretendard-geist-#1044`
- 변경: `app/globals.css` body font-family swap + `app/layout.tsx` font preload + `public/fonts/PretendardVariable.woff2` 추가 (또는 CDN)
- 게이트: lint + typecheck + test + build + lighthouse First Paint regression 없음 확인
- 검증: `/` 페이지 글자 렌더링 — Pretendard 적용 여부
- LOC 예상: ~30 (CSS) + ~20 (layout.tsx) + font 파일
- 의존: PR 1
- **보호 영역 risk**: `web/package.json` 안 건드리면 보호 영역 외 (CDN 채택 시). 자체 호스팅 채택 시 `public/fonts/` 추가만 — 보호 영역 외.

### 다음 사이클 후보 (본 PR 머지 후)

- **fe (1순위)**: PR 1 (design tokens 도입) — 본 spec § 6 PR 1 작성. ADR-0018 cheat sheet 박제 후 시작.
- **fe (2순위)**: PR 2 (Button press scale) — PR 1 머지 후 즉시.
- **rev**: 본 spec + ADR-0018 머지 후 단계 2-3 의 spec 정합성 audit (페르소나 task ↔ PR backlog 매핑 검증).
- **plan**: 사용자가 brand color 후보 (indigo-violet vs hot pink-orange) 또는 폰트 (Pretendard vs 자체 호스팅 vs CDN) 결정 시 본 spec / ADR-0018 갱신.

## 7) 테스트 전략

### 단위 / 통합 테스트
- **PR 1 (tokens)**: 단위 테스트 미적용 (CSS variable). `npm run build` 통과만으로 OK.
- **PR 2 (Button)**: 기존 `__tests__/Button.test.tsx` 회귀 없음 확인 + active state CSS class 부착 verify (testing-library `.toHaveClass('active:scale-95')`).
- **PR 3 (SongCard)**: 기존 SongCard 테스트 회귀 + stagger animation 부착 verify.
- **PR 4 (Sheet)**: focus trap / ESC / backdrop click 기존 테스트 회귀 없음 필수. swipe-to-close 는 testing-library 시뮬레이션.
- **PR 5 (pitch wave)**: 새 `PitchWaveRing.test.tsx` — frequency input → ring path 변화 verify.
- **PR 6 (HeartPop)**: tap 이벤트 → CSS class toggle verify.
- **PR 7 (transitions)**: 회귀 테스트만 (시각 효과는 수동 검증).
- **PR 8 (wordmark)**: 컴포넌트 렌더 verify.
- **PR 9 (font)**: First Paint regression 없음 — lighthouse 측정.

### E2E (rev 단계 1 e2e — 모든 PR)
- `/` 진입 시각 변화 확인
- `/voice-range/auto` 4 step wizard 완주 시뮬레이션
- `/recommend` 추천 결과 카드 stagger 진입
- `/recommend` 곡 카드 → bottom sheet 진입
- 좋아요 / 북마크 동작
- 다크 모드 토글 — 토큰 자동 swap 확인
- `prefers-reduced-motion: reduce` 시 motion 0.01ms

### 외부 연동 mock 전략
해당 없음 (web 디자인 전용, 백엔드 의존 없음).

### 시각 회귀 (visual regression)
- 본 4단계는 자동 시각 회귀 테스트 (Percy / Chromatic) 미적용. 수동 lighthouse / Chrome DevTools mobile preview 만.
- 후속 spec 후보: visual regression CI 통합 (별도 ADR).

## 8) 오픈 질문

> 구현 전에 답이 나와야 하는 것들. 해소되면 §9 결정 로그로 이동.

| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | brand color 최종 — indigo-violet 그라데이션 vs hot pink-orange | (a) indigo-violet (ADR-0018 현재) / (b) hot pink-orange / (c) 멜론 초록 단색 | @goohong / 단계 4 PR 1 시작 전 |
| Q2 | Pretendard 호스팅 — CDN vs 자체 호스팅 | (a) jsdelivr CDN / (b) 자체 `public/fonts/` / (c) Google Fonts | @goohong / 단계 4 PR 9 시작 전 |
| Q3 | logo / wordmark 디자인 산출물 — 외부 디자이너 의뢰? | (a) 사용자가 svg 제공 / (b) AI 생성 placeholder / (c) 텍스트 only wordmark 유지 | @goohong / 단계 4 PR 8 시작 전 |
| Q4 | view-transition-api 사용 — Next.js 16 upgrade 필요? | (a) Next 16 upgrade / (b) polyfill 사용 / (c) PR 7 보류 | fe sub-agent / 단계 4 PR 7 시작 전 |
| Q5 | animation 라이브러리 — framer-motion 도입? | (a) CSS-only 유지 / (b) framer-motion (gzipped ~30KB) / (c) react-spring | fe sub-agent / 단계 4 PR 4 또는 PR 6 시작 전 |

**자율 결정 default** (질문 미해소 시 사용자 부재 가정 자율 결정):
- Q1 → (a) indigo-violet (ADR-0018 결정)
- Q2 → (a) CDN — 자체 호스팅 marginal benefit + 운영 cost ↑
- Q3 → (c) 텍스트 wordmark 유지 → 추후 디자인 산출물 도착 시 별도 PR
- Q4 → (b) polyfill 또는 (c) 보류 — fe sub-agent 가 Next 버전 확인 후 결정
- Q5 → (a) CSS-only 우선 — 라이브러리 도입 시 PR 본문 `## 자율 결정 (사유)` 명시 후 진행

## 9) 결정 로그

- 2026-05-24: 초안 작성 (status=approved). 단계 1 audit 완료 (rev #1044), 단계 2-3 본 spec + ADR-0018 작성. 단계 4 fe 진행 대기.
- 2026-05-24: design tokens ADR-0018 결정 (color brand indigo-violet, Pretendard + Geist, Major Third typography scale).
- 2026-05-24: 9 PR 분할 의존성 순서 확정 (PR 1 = tokens 기반, PR 2-9 = token 의존만).
- 2026-05-24: 단계 4 진행 트리거 — Q1-Q5 의 자율 결정 default 또는 사용자 결정 후 PR 1 launch.
- 2026-05-29: 단계 4 PR 2-9 세부 spec 3 분할 — `ui-ux-redesign-pr-2-9-component-matrix.md` (PR 2/3/6/7/8/9 묶음) + `ui-ux-redesign-pr-4-bottom-sheet.md` (PR 4 단독) + `ui-ux-redesign-pr-5-pitch-wave.md` (PR 5 단독). 분리 사유: PR 4 = drag/swipe/focus-trap/scroll-lock 5 회귀 가드 깊이, PR 5 = Web Audio 회귀 + 60fps SVG 알고리즘 결정성. 본 spec §6 은 상위 backlog SoT + 분리 spec link 만 잔존.
