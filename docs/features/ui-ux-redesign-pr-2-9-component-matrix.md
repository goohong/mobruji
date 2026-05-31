---
feature: UI/UX redesign 단계 4 PR 2-9 컴포넌트 매트릭스 (Bottom Sheet / PitchWave 제외)
slug: ui-ux-redesign-pr-2-9-component-matrix
status: approved
owner: @goohong
scope: web
related_issues: [1044]
related_prs: []
last_reviewed: 2026-05-29
---

# UI/UX redesign 단계 4 PR 2-9 컴포넌트 매트릭스 (Bottom Sheet / PitchWave 제외)

## 1) 개요 (What / Why)

`docs/features/ui-ux-redesign.md` §6 의 PR 2-9 (9개 컴포넌트 redesign goal) 골자를 PR 별 spec 으로 분할하는 plan 라운드의 **묶음 (matrix) SoT**. 본 spec 은 비교적 작은 LOC (~30-80) + 단일 컴포넌트 영향 + CSS-only 구현 가능한 **6 PR (PR 2 Button / PR 3 SongCard / PR 6 LikeButton / PR 7 Page transitions / PR 8 Wordmark / PR 9 Font)** 를 한 SoT 에서 추적한다.

분리 PR 2개 — **PR 4 Bottom Sheet** (`docs/features/ui-ux-redesign-pr-4-bottom-sheet.md`) 와 **PR 5 PitchWave** (`docs/features/ui-ux-redesign-pr-5-pitch-wave.md`) — 는 (1) drag handle / swipe gesture 회귀 가드 + focus trap 회귀 risk 가 크고 (2) Web Audio API + 실시간 SVG 렌더링 의존성 + Math 알고리즘 결정 깊이가 있어 별도 spec SoT 로 박제.

대상 액터: fe sub-agent (구현) / rev sub-agent (audit) / plan sub-agent (본 spec 갱신 + ADR-0018 cross-link) / nmae (백로그 launch).

### 본 spec 의 역할 vs 기존 SoT

| SoT | 역할 |
|---|---|
| `docs/features/ui-ux-redesign.md` | **상위 SoT** — 4 단계 흐름 + 페르소나 시나리오 + 9 PR 전체 backlog 골자 (link only) |
| `docs/decisions/0018-design-tokens.md` | **token 결정값 SoT** — 색/타이포/spacing/radius/shadow/motion 30+ token 정의 |
| `docs/features/design-tokens-residual-swap-matrix.md` | **swap 진행도 SoT** — 단계 4 swap 완결 게이트 (본 spec 의 선행 게이트) |
| **본 spec** | **PR 2/3/6/7/8/9 세부 SoT** — 각 PR 의 변경 / 게이트 / 회귀 risk / 의존성 / 자율 결정 default |
| `ui-ux-redesign-pr-4-bottom-sheet.md` | PR 4 단독 SoT (별도 분리) |
| `ui-ux-redesign-pr-5-pitch-wave.md` | PR 5 단독 SoT (별도 분리) |

## 2) 사용자 시나리오

본 spec 의 사용자 시나리오는 `ui-ux-redesign.md §2` (시나리오 A/B/C) SoT — 본 spec 은 agent 운영 시나리오만 별도 정의.

### 시나리오 A: fe sub-agent 가 PR 2 (Button) 사이클 launch 받음
"nmae 백로그 → fe sub-agent 가 본 spec §5-1 PR 2 섹션 즉시 read → (1) 변경 파일 `web/components/ui/Button.tsx` (2) 추가할 클래스 `active:scale-95 transition-transform duration-200 ease-spring` (3) 테스트 회귀 `Button.test.tsx` 의 `.toHaveClass()` assertion 갱신 (4) 게이트 `lint+typecheck+test+build` (5) 검증 `npm run dev` → 홈 CTA 클릭 — 5분 안에 PR 생성 가능."

### 시나리오 B: rev sub-agent 가 PR 3 (SongCard) audit
"rev 가 본 spec §5-2 PR 3 회귀 가드 표 ↔ PR diff 비교 → (1) 좌측 4px gradient stripe deterministic 여부 (곡 hash → hue rotation) (2) stagger animation-delay 인덱스 기반 (3) `active:scale-98` 부착 (4) `prefers-reduced-motion` block 추가 (5) 기존 SongCard.test 회귀 0 — 5 체크포인트 즉시 분류."

### 시나리오 C: plan sub-agent 가 단계 4 PR 2-9 진행도 추적
"plan 사이클 시 본 spec §6 PR 진행도 표 ↔ 머지된 PR 비교 → 누락 / 보호 영역 risk / 자율 결정 default 적용 여부 확인 → drift 발견 시 본 spec `related_prs` frontmatter 갱신 + PR 본문 자기 점검 (CLAUDE.md §7) 호환."

## 3) 요구사항

### 기능 요구사항

#### 사전 게이트 (필수 선행)
- [ ] **`design-tokens-residual-swap-matrix.md §5-3` 단계 4 swap 완결 게이트 통과 후 진입** — token swap 미완 상태로 component redesign 시 "어떤 토큰 swap 이고 어떤 redesign 인지" 혼동 사고 (사용자 박제 의도) 회피.

#### 본 spec 추적 항목 (PR 별)
- [ ] 변경 파일 + 추가 클래스 / 신규 컴포넌트 명세
- [ ] 게이트 (lint / typecheck / test / build)
- [ ] 회귀 risk 분류 (낮/중/높)
- [ ] 의존성 (PR 1 token 의존 + 다른 PR 의존)
- [ ] 자율 결정 default (사용자 부재 시 fe sub-agent 가 채택)
- [ ] 보호 영역 변경 여부 명시
- [ ] LOC 예상

#### 단계 4 PR 2-9 완결 정의
- [ ] PR 2/3/6/7/8/9 (본 spec) + PR 4 (별도 spec) + PR 5 (별도 spec) 모두 머지 = `ui-ux-redesign.md` 단계 4 완결 → ADR-0018 status `implemented` → `superseded` 후보 (motion / view-transition-api / Web Audio API 등 추가 결정 시 별도 ADR).

### 비기능 요구사항

- **추적 비용 ↓**: 각 PR 작업 시 본 spec §5 의 해당 PR 섹션만 read → 매번 grep / inventory 반복 X.
- **drift 가드**: 본 spec `related_prs` frontmatter 가 단계 4 PR 2-9 누적 PR 목록 SoT. PR 머지 시 누락 → rev 단계 1 audit 경고.
- **a11y 의무 (모든 PR 공통)**: `prefers-reduced-motion` block 추가 의무. 누락 시 rev 단계 1 fail.
- **다크 모드**: 모든 PR 의 token 참조 = light/dark 자동 swap 동작 의무.
- **번들 사이즈**: CSS-only 우선. animation 라이브러리 (framer-motion / react-spring) 도입 시 PR 본문 `## 자율 결정 (사유)` 명시 + gzipped < 30KB 임계.

## 4) 범위 / 비범위 (중요)

### 포함
- PR 2 Button press scale + brand color + spring transition
- PR 3 SongCard gradient stripe + stagger fade-in + active scale
- PR 6 LikeButton heart pop + sparkle particle
- PR 7 Page transitions (view-transition-api + hero morph)
- PR 8 Brand wordmark + logo SVG icon set
- PR 9 Font (Pretendard + Geist 통합)
- 각 PR 의 회귀 가드 / 자율 결정 default / 보호 영역 영향

### 제외 (Out of Scope)
- **PR 4 Bottom Sheet** — `ui-ux-redesign-pr-4-bottom-sheet.md` SoT 분리. swipe gesture / drag handle / focus trap 회귀 가드가 본 spec 평균보다 깊음.
- **PR 5 PitchWave** — `ui-ux-redesign-pr-5-pitch-wave.md` SoT 분리. Web Audio API + 실시간 SVG ring + Math 알고리즘 + 마이크 권한 회귀 risk.
- **token swap 진행** — `design-tokens-residual-swap-matrix.md` SoT. 본 spec 은 token 사용자 (consumer) 만.
- **데스크탑 글로벌 nav** — `ui-ux-redesign.md §4` 범위 외와 동일.
- **animation 라이브러리 도입 결정** — 본 spec 의 PR 6/7 자율 결정 default 는 CSS-only. 라이브러리 도입 = 별도 ADR + 본 spec 갱신.
- **visual regression CI (Percy / Chromatic)** — `ui-ux-redesign.md §7` 와 동일 범위 외.

## 5) 설계

### 5-1) PR 별 세부 spec

#### PR 2 — Button press scale + brand color + spring transition

| 항목 | 값 |
|---|---|
| 브랜치 | `feat/button-press-scale-#1044` |
| 영향 파일 | `web/components/ui/Button.tsx` + `web/components/ui/Button.test.tsx` |
| 추가 클래스 | `active:scale-95 transition-transform duration-200 ease-[var(--ease-spring)]` |
| Primary variant | `bg-[var(--brand-500)]` + hover `bg-[var(--brand-600)]` + active `bg-[var(--brand-700)]` |
| Danger variant | `bg-[var(--danger-500)]` (기존 rose/red 혼용 swap) |
| 회귀 risk | **중** — 모든 페이지 CTA 영향. `design-tokens-residual-swap-matrix.md sub-PR 2` (primitive 일괄) 머지 후 진입 권장. |
| 게이트 | `cd /home/mobruji/mobruji-fe/web && npm run lint && npm run typecheck && npm test && npm run build` |
| 검증 | (1) Button.test.tsx assertion 갱신 (`.toHaveClass('active:scale-95')`) (2) `npm run dev` 홈 CTA 클릭 시 press feedback 시각 (3) `prefers-reduced-motion` block override 확인 |
| LOC 예상 | ~30 추가/수정 + ~10 (테스트) |
| 의존 | PR 1 (tokens) + `sub-PR 2` (primitive swap) 권장 — token 적용 시점 충돌 회피 |
| 자율 결정 default | (a) CSS-only spring easing (`--ease-spring`) — framer-motion 도입 X |
| 보호 영역 | 없음 |
| Visual ref | 토스 button press = `scale-0.97` + opacity 0.9 (mobruji 는 `scale-0.95` 채택 — 노래방 도메인 = 강한 press 체감) |

#### PR 3 — SongCard gradient stripe + stagger fade-in + active scale

| 항목 | 값 |
|---|---|
| 브랜치 | `feat/song-card-gradient-stagger-#1044` |
| 영향 파일 | `web/app/recommend/components/SongCard.tsx` + `web/app/recommend/components/SongCard.test.tsx` + (helpers) `SongCard.helpers.test.tsx` |
| 좌측 stripe | 4px width gradient — 곡 hash (예: `songId.charCodeAt() % 360`) → hue rotation 으로 deterministic. `background: linear-gradient(180deg, hsl(var(--song-hue) 70% 60%), hsl(var(--song-hue) 70% 40%))` |
| Stagger fade-in | `animation: fade-in-up 300ms ease-out backwards; animation-delay: calc(var(--card-index) * 50ms)` — `style={{ '--card-index': index }}` 으로 인덱스 전달 |
| Active scale | `active:scale-98 transition-transform duration-150` |
| 회귀 risk | **중** — `/recommend` `/likes` `/bookmarks` `/history` 모두 SongCard import. `design-tokens-residual-swap-matrix.md sub-PR 4` (recommend container) 머지 후 진입 |
| 게이트 | lint + typecheck + test + build |
| 검증 | (1) SongCard.test.tsx 회귀 0 (2) helpers 테스트 (`getSongHue(songId)` 결정성 — 같은 songId → 같은 hue) (3) `/recommend` 진입 시 카드 stagger 시각 (4) `prefers-reduced-motion` 시 stagger 0ms |
| LOC 예상 | ~60 추가 + ~30 (테스트 — helper 결정성 + stagger 부착 verify) |
| 의존 | PR 1 (tokens) + `sub-PR 4` (recommend container swap) — F6 SongCard 11 비활성 페어 정렬 후 |
| 자율 결정 default | (a) CSS-only stagger (animation-delay) — IntersectionObserver / framer-motion 도입 X (b) 곡 hash 결정성 — `songId.split('').reduce((acc, c) => acc + c.charCodeAt(0), 0) % 360` (간단 sum, 충분히 분산) |
| 보호 영역 | 없음 |
| Visual ref | 스포티파이 카드 hover sparkline (옵션 — 데스크탑 한정), 멜론 앨범 dominant color (현 PR 은 hash 기반 — 외부 색 추출 X) |

#### PR 6 — LikeButton heart pop + sparkle particle

| 항목 | 값 |
|---|---|
| 브랜치 | `feat/like-button-heart-pop-#1044` |
| 영향 파일 | `web/app/recommend/components/SongCard.tsx` (LikeButton 분리) + 신규 `web/components/ui/HeartPop.tsx` + `web/components/ui/HeartPop.test.tsx` |
| Heart pop | tap 시 scale 1 → 1.3 → 1 spring (`@keyframes heart-pop`) — duration 400ms `var(--ease-emphasized)` |
| Sparkle particle | 3건 CSS-only — SVG path 또는 `::before/::after/::after-2` (단 ::after-2 불가 → 3 div span) 으로 translate + opacity fade out (duration 600ms) |
| Haptic | 지원 시 `navigator.vibrate(10)` — 지원 안 함 / iOS 정책 거부 graceful |
| 회귀 risk | **낮음** — LikeButton 단일 컴포넌트, 페이지 동작 영향 0 |
| 게이트 | lint + typecheck + test + build |
| 검증 | (1) HeartPop.test.tsx — tap 이벤트 시 `data-active="true"` toggle verify (2) `/recommend` 좋아요 토글 시 애니메이션 시각 (3) `prefers-reduced-motion` 시 scale/sparkle 모두 0ms (4) `navigator.vibrate` 미지원 환경 (testing-library jsdom) graceful — error throw 0 |
| LOC 예상 | ~80 (HeartPop 신설 50 + SongCard 분리 20 + 테스트 10) |
| 의존 | PR 1 (tokens) + PR 3 (SongCard refactor 완료 후 — LikeButton 분리 시점 일관) |
| 자율 결정 default | (a) CSS-only `@keyframes` — framer-motion / react-spring 도입 X (b) sparkle = 3 div span CSS animation (SVG path 보다 간결) (c) haptic = `navigator.vibrate(10)` optional chain |
| 보호 영역 | 없음 |
| Visual ref | iOS Health 좋아요 / 카카오뱅크 폼 입력 즉시 ✅ check icon |

#### PR 7 — Page transitions (view-transition-api + hero morph)

| 항목 | 값 |
|---|---|
| 브랜치 | `feat/page-transitions-#1044` |
| 영향 파일 | `web/app/layout.tsx` + 페이지별 `view-transition-name` 속성 + `web/next.config.ts` (Next 16 flag 또는 polyfill import) |
| API | `document.startViewTransition()` native (Chrome 111+ / Safari 18+) — 미지원 브라우저 fallback = 즉시 전환 (graceful) |
| Hero morph | 앨범 thumb (`/recommend` SongCard) → 상세 cover (`/songs/[id]`) — `view-transition-name: album-{songId}` (인스턴스 unique 식별자) |
| Next.js 16 | 현 web 버전 확인 후 분기. Next < 16 → polyfill (`react-view-transitions` 등) 또는 native API 직접 호출 (Next router event 후 `startViewTransition`) |
| 회귀 risk | **중-높** — router transition 시점 동작 변경. SSR/CSR 전환 시 hydration race. `next.config.ts` 변경 = **정보성 보호 영역** (라벨 의무 폐지 — `CLAUDE.md §4` SoT). |
| 게이트 | lint + typecheck + test + build + (`npm run dev` 검증 시 deep-link 회귀 0) |
| 검증 | (1) `/recommend` → `/songs/[id]` deep-link 시 hero morph 시각 (2) 브라우저 미지원 환경 (Firefox 현재) → graceful 즉시 전환 (3) `prefers-reduced-motion` 시 transition 0ms |
| LOC 예상 | ~100 (layout + 페이지 attr 분산 + next.config) |
| 의존 | PR 1 (tokens — easing) + PR 3 (SongCard 의 album thumb 가 morph 시작점) + PR 4 (Bottom Sheet — sheet 진입도 view-transition 후보) |
| 자율 결정 default | (a) **native view-transition-api 우선** + 미지원 브라우저 graceful (b) Next 16 upgrade X — polyfill 도 X — native API 직접 호출 (router event 후 `startViewTransition`) (c) hero morph 대상 = album thumb 1건 (확장은 별도 PR) |
| 보호 영역 | `web/next.config.ts` (config 변경 시) — rev 신중도 가중, 라벨 의무 없음 |
| Risk 명시 | Next.js 버전 확인 후 native API 작동 안 하면 PR 본문 `## 자율 결정 (사유)` + `## 사용자 확인 필요` 섹션에 "polyfill 도입 vs PR 보류" 사실 진술 (질문 X) |
| Visual ref | iOS native 앨범 진입 morph / Apple Music 앨범 cover expansion |

#### PR 8 — Brand wordmark + logo SVG icon set

| 항목 | 값 |
|---|---|
| 브랜치 | `feat/brand-wordmark-#1044` |
| 영향 파일 | `web/public/icons/` 신설 (`logo.svg` / `wordmark-ko.svg` / `wordmark-en.svg` / `favicon-512.svg`) + `web/components/brand/BrandWordmark.tsx` + `web/components/brand/BrandWordmark.test.tsx` + `web/app/layout.tsx` (favicon link) |
| 컴포넌트 API | `<BrandWordmark lang="ko"|"en" size="sm"|"md"|"lg" theme="auto"|"light"|"dark" />` — `auto` = CSS variable `--brand-500` 사용 (다크 모드 자동 swap) |
| 회귀 risk | **낮음** — 신규 컴포넌트 + 헤더 1 곳 사용. PR 본문에 placeholder svg 명시 (외부 디자인 산출물 도착 시 별도 PR 로 swap). |
| 게이트 | lint + typecheck + test + build |
| 검증 | (1) BrandWordmark.test.tsx 렌더 verify (lang prop 별 wordmark-ko.svg / wordmark-en.svg 선택) (2) 홈 헤더 wordmark 시각 (3) favicon 적용 (브라우저 탭 아이콘) (4) 다크 모드 토글 시 wordmark 색 자동 swap |
| LOC 예상 | ~50 (component) + ~30 (테스트) + svg 파일 (외부 산출물 또는 placeholder) |
| 의존 | PR 1 (tokens — brand color) |
| 자율 결정 default | (a) **placeholder svg 진행** — 사용자 외부 디자인 의뢰 결과물 도착 시 별도 PR `feat/brand-wordmark-swap-#1044` 로 산출물 swap (b) 한글 wordmark = "모부르지" 굵은 디스플레이 (Pretendard Black 또는 SVG path 박제) (c) 마이크 + neon glow icon = SVG `<filter>` blur 또는 CSS box-shadow |
| 보호 영역 | 없음 (단 `public/` 하위 추가 — 사이즈 cap 확인) |
| 사용자 확인 필요 | PR 본문 `## 사용자 확인 필요` 섹션 — "외부 디자이너 산출물 도착 후 swap PR launch 권고. 현 placeholder = AI 생성 또는 텍스트 기반." (사실 진술, 질문 X) |
| Visual ref | 멜론 wordmark (한글 굵은 디스플레이) / 스포티파이 logo (단순 geometric) |

#### PR 9 — Font (Pretendard + Geist 통합)

| 항목 | 값 |
|---|---|
| 브랜치 | `feat/font-pretendard-geist-#1044` |
| 영향 파일 | `web/app/tokens.css` (font-family swap) + `web/app/layout.tsx` (font preload link) + `web/public/fonts/PretendardVariable.woff2` (자체 호스팅 채택 시) 또는 jsdelivr CDN link |
| Font stack | `font-family: 'Pretendard Variable', Pretendard, -apple-system, BlinkMacSystemFont, 'Geist', sans-serif` |
| Preload | `<link rel="preload" href="/fonts/PretendardVariable.woff2" as="font" type="font/woff2" crossorigin>` (자체 호스팅) 또는 `<link rel="preconnect" href="https://cdn.jsdelivr.net">` (CDN) |
| Font-display | `swap` (FOUT 허용 — 첫 진입 시 시스템 폰트로 즉시 렌더링 후 Pretendard swap) |
| 회귀 risk | **중** — 모든 페이지 글자 영향. lighthouse First Paint regression < 100ms 임계. CDN 채택 시 외부 의존 추가 (보안 정책 검토 필요). |
| 게이트 | lint + typecheck + test + build + lighthouse mobile 측정 (LCP < 2.5s 유지 — `ui-ux-redesign.md §3 비기능` SoT) |
| 검증 | (1) `/` 페이지 글자 렌더링 Pretendard 적용 시각 (2) 네트워크 throttle Slow 3G 환경 FOUT (Flash of Unstyled Text) 허용 — FOIT (invisible text) 발생 X (3) Geist 영문 fallback 동작 (영문 텍스트 영역 — 토큰 명 / 코드 stamp) |
| LOC 예상 | ~30 (CSS) + ~20 (layout.tsx) + font 파일 (자체 호스팅 ~2MB) 또는 CDN link 1줄 |
| 의존 | PR 1 (tokens — typography font-family token) |
| 자율 결정 default | (a) **CDN (jsdelivr) 채택** — 자체 호스팅 marginal benefit + 운영 cost ↑ (자율 결정 default — `ui-ux-redesign.md §8 Q2`) (b) Geist 는 next/font 로 이미 도입돼 있으면 유지. 미도입 시 본 PR 에서 동시 추가 |
| 보호 영역 | 자체 호스팍 채택 시 `web/public/fonts/` 추가 (보호 영역 외). CDN 채택 시 외부 의존 추가 — 보안 정책 검토. **`web/package.json` 변경 0 의무** (next/font 의존성 추가 시 = 정보성 보호 영역, `web/**` 의존성 변경 = fe 금지 — `actors/sub-agent.md §2-fe`) |
| Visual ref | 토스 = Pretendard 본문 + SF Pro Display 영문 / 카카오뱅크 = Pretendard 단독 / 멜론 = system-ui |

### 5-2) PR 의존성 그래프

```
PR 1 (tokens, #1131 shipped)
  │
  ├─→ design-tokens-residual-swap-matrix.md sub-PR 1~5 (swap 완결 게이트)
  │      │
  │      └─→ 본 spec PR 2/3/6/7/8/9 진입 가능
  │
  ├─→ PR 2 (Button)  ──┐
  │                     │
  ├─→ PR 3 (SongCard) ─┼─→ PR 6 (LikeButton — SongCard refactor 후)
  │                     │
  ├─→ PR 7 (Page transitions — PR 3 album thumb 의존 + PR 4 sheet 의존)
  │
  ├─→ PR 8 (Wordmark — 독립, 외부 산출물 의존)
  │
  └─→ PR 9 (Font — 독립)

PR 4 (Bottom Sheet) ─ ui-ux-redesign-pr-4-bottom-sheet.md SoT (별도)
PR 5 (PitchWave)   ─ ui-ux-redesign-pr-5-pitch-wave.md SoT (별도)
```

#### 추천 작업 순서
1. **design-tokens-residual-swap-matrix.md sub-PR 0~5 모두 머지** (swap 완결 게이트 통과)
2. **PR 2** (Button — primitive 영향 큼, 1순위)
3. **PR 3** (SongCard — recommend 페이지 영향 큼, 2순위) + **PR 9** (Font — 독립, 병렬) + **PR 8** (Wordmark — 독립, 병렬)
4. **PR 6** (LikeButton — PR 3 머지 후)
5. **PR 4** (Bottom Sheet — `ui-ux-redesign-pr-4-bottom-sheet.md` 별도 사이클)
6. **PR 5** (PitchWave — `ui-ux-redesign-pr-5-pitch-wave.md` 별도 사이클)
7. **PR 7** (Page transitions — 마지막. PR 3 + PR 4 머지 후 hero morph 시작점/끝점 확보)

### 5-3) 회귀 가드 매트릭스

| PR | 회귀 risk | 회귀 가드 (rev 단계 1 체크) | 회귀 가드 (rev 단계 2 e2e) |
|---|---|---|---|
| PR 2 Button | 중 | `Button.test.tsx` assertion 동기 갱신 / `prefers-reduced-motion` block / primary+danger variant 모두 swap | 모든 페이지 CTA 시각 회귀 / 다크 모드 자동 swap |
| PR 3 SongCard | 중 | `SongCard.test.tsx` 회귀 0 / helpers 결정성 (같은 songId → 같은 hue) / stagger animation-delay 인덱스 기반 | `/recommend` `/likes` `/bookmarks` `/history` 카드 진입 시 stagger 시각 |
| PR 6 LikeButton | 낮 | `HeartPop.test.tsx` tap → data-active toggle / `navigator.vibrate` jsdom graceful | `/recommend` 좋아요 토글 시 애니메이션 + sparkle 시각 |
| PR 7 Transitions | 중-높 | `next.config.ts` 변경 시 rev 신중도 ↑ / native API 미지원 graceful / `prefers-reduced-motion` 시 0ms | 모든 router 전환 시 hydration race 0 / deep-link 회귀 0 / 미지원 브라우저 (Firefox) 즉시 전환 graceful |
| PR 8 Wordmark | 낮 | `BrandWordmark.test.tsx` lang prop 렌더 verify / favicon 적용 / 다크 모드 색 swap | 헤더 wordmark 시각 / 브라우저 탭 favicon 시각 |
| PR 9 Font | 중 | lighthouse First Paint regression < 100ms / FOIT 발생 0 / Geist fallback 동작 | LCP < 2.5s 유지 (모바일) / 한/영 폰트 모두 적용 시각 |

### 5-4) 자율 결정 default 종합 표

`ui-ux-redesign.md §8` Q1-Q5 자율 결정 default 와 일치 + 본 spec 세부.

| 결정 항목 | default | 사유 |
|---|---|---|
| brand color | (a) indigo-violet (ADR-0018 결정) | 노래방 무대 조명 모티프 |
| 폰트 호스팅 | (a) jsdelivr CDN | marginal benefit + 운영 cost ↓ |
| logo 산출물 | placeholder svg 진행 → 외부 산출물 도착 시 swap PR | 진행 차단 회피 |
| view-transition-api | native + 미지원 graceful | Next 16 upgrade / polyfill 모두 회피 |
| animation 라이브러리 | CSS-only 우선 | 번들 사이즈 + 단순성 |
| PR 4 sheet 라이브러리 | CSS-only 우선 → 복잡 시 vaul / react-spring 평가 (별도 spec) | `ui-ux-redesign-pr-4-bottom-sheet.md` SoT |
| PR 5 PitchWave 알고리즘 | autocorrelation (현 구현 유지) → SVG ring CSS-only | `ui-ux-redesign-pr-5-pitch-wave.md` SoT |

### 5-5) DB 마이그레이션
해당 없음 (web 디자인 전용).

### 5-6) 프론트엔드 화면

| 라우트 | PR 2 | PR 3 | PR 6 | PR 7 | PR 8 | PR 9 |
|---|:-:|:-:|:-:|:-:|:-:|:-:|
| `/` (홈) | ✅ | — | — | ✅ | ✅ | ✅ |
| `/voice-range` | ✅ | — | — | ✅ | — | ✅ |
| `/voice-range/auto` | ✅ | — | — | ✅ | — | ✅ |
| `/recommend` | ✅ | ✅ | ✅ | ✅ | — | ✅ |
| `/songs` | ✅ | ✅ | — | ✅ | — | ✅ |
| `/songs/[id]` | — | — | — | ✅ | — | ✅ |
| `/history` | ✅ | ✅ | — | ✅ | — | ✅ |
| `/likes` `/bookmarks` | ✅ | ✅ | ✅ | ✅ | — | ✅ |

PR 9 (Font) = 전 페이지 영향 1순위 — lighthouse 회귀 측정 필수.
PR 8 (Wordmark) = 홈 헤더 단일.
PR 7 (Transitions) = router 전환 시 전 페이지.

## 6) 작업 분할 (예상 PR 리스트)

### 본 spec 추적 6 PR

| PR | 이름 | LOC | 의존 | risk | 우선순위 |
|---|---|---:|---|---|---|
| 2 | Button press scale + brand + spring | ~40 | swap-matrix sub-PR 2 권장 | 중 | P1 |
| 3 | SongCard gradient stripe + stagger + active | ~90 | swap-matrix sub-PR 4 권장 | 중 | P1 |
| 6 | LikeButton heart pop + sparkle | ~80 | PR 3 | 낮 | P2 |
| 7 | Page transitions (view-transition-api) | ~100 | PR 3 + PR 4 | 중-높 | P3 |
| 8 | Brand wordmark + logo SVG | ~80 + svg | 독립 | 낮 | P2 (병렬) |
| 9 | Font Pretendard + Geist | ~50 + font | 독립 | 중 | P2 (병렬) |

### 분리 PR 2 (별도 spec)

| PR | 이름 | spec |
|---|---|---|
| 4 | SongDetailModal → Bottom Sheet (drag handle + swipe-to-close) | `docs/features/ui-ux-redesign-pr-4-bottom-sheet.md` |
| 5 | VoiceRangeAuto pitch wave 시각화 | `docs/features/ui-ux-redesign-pr-5-pitch-wave.md` |

### 보호 영역 변경 여부 (필수 명시)

- 본 spec 자체: 없음 (`docs/features/*` 만 신설)
- PR 2/3/6/8: 없음
- PR 7: `web/next.config.ts` (정보성 — 라벨 의무 폐지)
- PR 9: 자체 호스팅 채택 시 `web/public/fonts/` 추가 (보호 영역 외). CDN 채택 시 외부 의존 추가 — 보안 정책 검토

## 7) 테스트 전략

본 spec 자체는 docs (테스트 N/A — frontmatter spec-status-check.yml 통과만).

### PR 별 테스트 룰 (요약)

- **PR 2 Button**: `Button.test.tsx` assertion 동기 갱신 + `prefers-reduced-motion` block verify
- **PR 3 SongCard**: `SongCard.test.tsx` 회귀 0 + `SongCard.helpers.test.tsx` 결정성 (`getSongHue` 같은 songId → 같은 hue) + stagger 부착 verify
- **PR 6 LikeButton**: `HeartPop.test.tsx` tap → data-active toggle + `navigator.vibrate` jsdom graceful
- **PR 7 Transitions**: 회귀 테스트만 (시각 효과 수동) + native API 미지원 fallback verify
- **PR 8 Wordmark**: `BrandWordmark.test.tsx` lang/size/theme prop 렌더 verify
- **PR 9 Font**: lighthouse First Paint regression 수동 측정 + FOIT 발생 0 verify

### E2E (rev 단계 1 e2e — 공통)
- 다크 모드 토글 — 모든 PR 토큰 자동 swap
- `prefers-reduced-motion: reduce` — 모든 PR motion 0.01ms
- 모바일 viewport (Chrome DevTools 390x844 — iPhone 14)
- 다크 모드 + reduced-motion 조합

### 외부 연동 mock 전략
해당 없음 (web 디자인 전용, 백엔드 의존 없음).

### 시각 회귀
- 자동 (Percy / Chromatic) 미적용 — `ui-ux-redesign.md §7` 와 동일 정책
- 수동: 홈 / `/recommend` / `/voice-range/auto` / `/songs/[id]` deep-link / 다크 모드 토글 6 viewport (모바일 / 태블릿 / 데스크탑)

## 8) 오픈 질문

| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | PR 7 view-transition-api 미지원 브라우저 비율 — 즉시 진행 vs 보류 | (a) 즉시 진행 + graceful fallback (자율 default) / (b) Next 16 upgrade 후 진행 / (c) PR 7 6개월 보류 | fe sub-agent / PR 7 launch 시 (자율 default 채택 가능) |
| Q2 | PR 8 logo 산출물 — 외부 디자이너 의뢰 시점 | (a) PR 8 placeholder 머지 후 산출물 도착 시 별도 PR (자율 default) / (b) 산출물 대기 후 PR 8 launch | @goohong / PR 8 launch 시 |
| Q3 | PR 9 font - CDN vs 자체 호스팅 vs next/font | (a) jsdelivr CDN (자율 default — ui-ux-redesign Q2 일치) / (b) 자체 호스팅 `web/public/fonts/` / (c) next/font + Google Fonts | @goohong / PR 9 launch 시 |
| Q4 | PR 3 곡 hash → hue 알고리즘 | (a) `songId.charCodeAt() sum % 360` (자율 default) / (b) crypto.subtle.digest (deterministic but heavier) / (c) songId 끝 글자 + 곡 장르 매핑 (semantic but 도메인 의존) | fe sub-agent / PR 3 launch 시 (자율 default 채택 가능) |
| Q5 | PR 6 sparkle 방식 — CSS divs vs SVG path | (a) 3 div span CSS animation (자율 default) / (b) SVG path + `@keyframes` / (c) Canvas particle | fe sub-agent / PR 6 launch 시 (자율 default 채택 가능) |
| Q6 | PR 7 hero morph 대상 확장 — album thumb 외 추가 | (a) album thumb 1건만 (자율 default — 첫 PR 단순) / (b) wordmark / CTA 모두 포함 (시각 정밀도 ↑ but 구현 복잡 ↑) | fe sub-agent / PR 7 launch 후 별도 PR 후보 |

### 자율 결정 default (질문 미해소 시 사용자 부재 가정 자율 결정)
- Q1 → (a) native + graceful fallback
- Q2 → (a) placeholder 진행 → 산출물 도착 시 swap PR
- Q3 → (a) jsdelivr CDN
- Q4 → (a) charCodeAt sum % 360
- Q5 → (a) 3 div span CSS animation
- Q6 → (a) album thumb 1건만

## 9) 결정 로그

- **2026-05-29**: 초안 작성 (status=approved). `ui-ux-redesign.md §6` PR 2-9 골자를 PR 별 세부 spec 으로 분할 — 본 spec (6 PR matrix) + `ui-ux-redesign-pr-4-bottom-sheet.md` (PR 4 단독) + `ui-ux-redesign-pr-5-pitch-wave.md` (PR 5 단독) 3 분할 결정.
- **2026-05-29**: 사전 게이트 = `design-tokens-residual-swap-matrix.md §5-3` swap 완결 게이트 통과 후 진입 명시 — token swap 미완 + redesign 동시 진행 시 혼동 사고 회피.
- **2026-05-29**: 자율 결정 default 6건 박제 (Q1-Q6) — `ui-ux-redesign.md §8` Q1-Q5 default 와 일치 + 본 spec 세부.
- **2026-05-29**: PR 의존성 그래프 + 추천 작업 순서 박제 — swap 게이트 → PR 2 → PR 3 + 8 + 9 병렬 → PR 6 → PR 4 → PR 5 → PR 7.
