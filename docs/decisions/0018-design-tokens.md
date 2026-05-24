---
id: 0018
title: Design tokens — color/typography/spacing/radius/shadow/motion
status: accepted
date: 2026-05-24
deciders: [@goohong]
---

# 0018. Design tokens — color/typography/spacing/radius/shadow/motion

## Context

이슈 #1044 rev audit 결과 현 `web/**` 의 디자인 시스템이 토큰화 없이 페이지마다 hardcode 로 운영되고 있음을 확인했다 (29 findings, 13🔴 시급). 주요 문제:

- **컬러**: `zinc-*` 무채색 일변도 99회 사용, `red-*` / `rose-*` semantic 혼용 22회. 브랜드 시그니처 컬러 부재 — 노래방 = 감성/활기 도메인인데 시각 첫인상이 "회색 admin" 톤.
- **타이포**: `text-xs/sm/base/lg/xl/2xl/3xl/4xl` 가 호출자별 결정. h1 = `text-3xl sm:text-4xl` (홈) vs `text-2xl` (/recommend, /voice-range) 불일치. 한글 font 미지정 → OS 기본.
- **모션**: `transition-colors` 만 사용. easing curve / duration / spring tension 토큰 부재. micro-interaction 0건.
- **그림자/radius**: 페이지마다 `rounded-2xl` / `rounded-3xl` / `rounded-xl` 혼재. shadow 가 `ring-1 ring-zinc-200` 으로만 표현.

토스/카카오뱅크/멜론 같은 ref 서비스는 디자인 토큰을 CSS variable 로 중앙 관리하여 (1) 다크 모드 자동 적응, (2) brand 일관성, (3) refactor 비용 ↓ 을 동시 달성한다. mobruji 도 같은 패턴 도입이 필요하다.

본 ADR 은 `docs/features/ui-ux-redesign.md` 단계 4 PR 1 (design tokens 도입) 의 구체 결정값을 정의한다.

## Decision

CSS variable + Tailwind v4 `@theme` 기반으로 6 카테고리 design token 을 도입한다. 모든 페이지/컴포넌트는 hardcode 색상/사이즈 사용 중단하고 토큰 참조로 전환한다.

### 1) Color token (브랜드 + semantic)

#### Brand color (primary)
"노래방 무대 조명 / 네온 사인" 모티프 — **indigo → violet gradient** 채택.

```css
--brand-50:  #EEF0FF;
--brand-100: #E0E4FF;
--brand-200: #C2C9FF;
--brand-300: #A0A8FF;
--brand-400: #7E85FF;
--brand-500: #6366F1;  /* primary base — indigo-500 */
--brand-600: #4F46E5;  /* primary hover/active */
--brand-700: #4338CA;
--brand-800: #3730A3;
--brand-900: #312E81;

--brand-gradient: linear-gradient(135deg, #6366F1 0%, #8B5CF6 100%);  /* indigo → violet */
```

선택 사유:
- indigo = "밤 + 무대 조명" 직관 (한국 노래방 = 어두운 부스). hot pink-orange (후보) 는 식음료 도메인과 충돌 위험.
- gradient 는 hero / CTA / 측정 progress bar 같은 시각 hub 에 한정. body 텍스트/배경에는 solid `--brand-500` 사용.
- 멜론 (초록) / 지니 (파랑) / 스포티파이 (초록) 등 음악 서비스의 강한 단색 시그니처 패턴 추종.

#### Semantic color
red/rose 혼용 폐기 — **단일 semantic 토큰**.

```css
--success-500: #10B981;  /* emerald — 좋아요 hit, 측정 완료 */
--success-50:  #ECFDF5;

--warning-500: #F59E0B;  /* amber — 경고, 임시 저장 */
--warning-50:  #FFFBEB;

--danger-500:  #EF4444;  /* red — 에러, 삭제, 마이크 권한 거부 */
--danger-50:   #FEF2F2;

--info-500:    #3B82F6;  /* blue — 안내, 도움말 */
--info-50:     #EFF6FF;
```

#### Neutral (배경 + 텍스트)
zinc 유지하되 변수 표준화.

```css
/* Light mode */
--bg-base:     #FFFFFF;
--bg-subtle:   #FAFAFA;  /* zinc-50 */
--bg-muted:    #F4F4F5;  /* zinc-100 */
--border:      #E4E4E7;  /* zinc-200 */
--text-primary:    #18181B;  /* zinc-900 */
--text-secondary:  #52525B;  /* zinc-600 */
--text-tertiary:   #A1A1AA;  /* zinc-400 */

/* Dark mode (@custom-variant dark 자동 swap) */
--bg-base:     #09090B;  /* zinc-950 */
--bg-subtle:   #18181B;  /* zinc-900 */
--bg-muted:    #27272A;  /* zinc-800 */
--border:      #3F3F46;  /* zinc-700 */
--text-primary:    #FAFAFA;  /* zinc-50 */
--text-secondary:  #D4D4D8;  /* zinc-300 */
--text-tertiary:   #71717A;  /* zinc-500 */
```

### 2) Typography token

#### Font family
- **Sans (primary)**: `Pretendard Variable` (한글) → `Geist` (라틴) → `system-ui` fallback.
- **Display (브랜드 / 큰 헤더)**: `Pretendard Variable` 800 weight.
- **Mono (코드 / 음역대 Hz 표시)**: `Geist Mono` → `ui-monospace`.

```css
--font-sans:    'Pretendard Variable', 'Geist', system-ui, -apple-system, sans-serif;
--font-display: 'Pretendard Variable', 'Geist', system-ui, sans-serif;
--font-mono:    'Geist Mono', ui-monospace, monospace;
```

Pretendard 채택 사유: 한국어 서비스 한글 가독성 1순위, 무료 (OFL-1.1), 9 weight, variable font 로 단일 파일.

#### Type scale (Major Third — 1.250 ratio)

| Token | rem | px | 용도 |
|---|---|---|---|
| `--text-xs`   | 0.75rem  | 12px | caption, helper text, step indicator |
| `--text-sm`   | 0.875rem | 14px | body small, chip, table |
| `--text-base` | 1rem     | 16px | body default |
| `--text-lg`   | 1.125rem | 18px | section title (h3) |
| `--text-xl`   | 1.5rem   | 24px | page subtitle (h2) |
| `--text-2xl`  | 1.875rem | 30px | page title (h1, 모바일) |
| `--text-3xl`  | 2.25rem  | 36px | page title (h1, 데스크탑) |
| `--text-display` | 3rem  | 48px | hero, onboarding wow |

line-height 토큰:
```css
--leading-tight:   1.25;  /* 헤더 */
--leading-normal:  1.5;   /* 본문 default */
--leading-relaxed: 1.75;  /* 긴 설명 */
```

font-weight:
```css
--font-regular:  400;
--font-medium:   500;
--font-semibold: 600;
--font-bold:     700;
--font-black:    800;  /* display 용 */
```

### 3) Spacing token (4px base scale)

기존 Tailwind `gap-2/3/4/6/8` 패턴 유지하되 의미 토큰 추가.

```css
--space-1:  4px;
--space-2:  8px;
--space-3:  12px;
--space-4:  16px;
--space-6:  24px;
--space-8:  32px;
--space-12: 48px;
--space-16: 64px;

/* 의미 토큰 (alias) */
--page-padding-x: var(--space-6);     /* px-6 → 24px */
--page-padding-y: var(--space-12);    /* py-12 → 48px */
--card-padding:   var(--space-4);     /* p-4 → 16px (Card 기본) */
--section-gap:    var(--space-8);     /* section 간 32px */
```

### 4) Radius token

```css
--radius-sm:   6px;   /* Chip, Tag */
--radius-md:   12px;  /* Input, Button */
--radius-lg:   16px;  /* Card */
--radius-xl:   24px;  /* Modal, Sheet (브랜드 강조) */
--radius-full: 9999px; /* Avatar, FAB */
```

### 5) Shadow token (elevation)

토스 패턴 — 부드러운 `rgba(0,0,0,0.04~0.08)` low-opacity multi-layer.

```css
--shadow-sm:  0 1px 2px 0 rgba(0,0,0,0.04);
--shadow-md:  0 2px 8px 0 rgba(0,0,0,0.04), 0 1px 2px 0 rgba(0,0,0,0.04);
--shadow-lg:  0 4px 16px 0 rgba(0,0,0,0.06), 0 2px 4px 0 rgba(0,0,0,0.04);
--shadow-xl:  0 8px 32px 0 rgba(0,0,0,0.08), 0 4px 8px 0 rgba(0,0,0,0.04);

/* 브랜드 액센트 — primary CTA hover 시 */
--shadow-brand: 0 8px 32px 0 rgba(99,102,241,0.24);
```

다크 모드는 shadow 강도를 1.5배로 (`rgba` opacity 0.06 → 0.12).

### 6) Motion token

spring + cubic-bezier 기반.

```css
--duration-fast:    150ms;  /* hover, focus, color */
--duration-base:    200ms;  /* button press, chip toggle */
--duration-slow:    300ms;  /* modal, sheet enter/exit */
--duration-slower:  500ms;  /* page transition, hero */

/* easing — 토스 spring 채택 */
--ease-out:        cubic-bezier(0.16, 1, 0.3, 1);     /* 부드러운 도착 */
--ease-in-out:     cubic-bezier(0.4, 0, 0.2, 1);      /* 양방향 */
--ease-spring:     cubic-bezier(0.34, 1.56, 0.64, 1); /* 스프링 bounce */
--ease-emphasized: cubic-bezier(0.2, 0, 0, 1);        /* 강조 — page transition */
```

**`prefers-reduced-motion` 대응** (의무):
```css
@media (prefers-reduced-motion: reduce) {
  * { transition-duration: 0.01ms !important; animation-duration: 0.01ms !important; }
}
```

## Consequences

### 긍정적
- **브랜드 정체성 확립**: indigo/violet gradient 가 노래방 도메인 시그니처가 되어 토스 (파랑) / 카뱅 (노랑) 처럼 인지 가능한 시각.
- **다크 모드 자동 적응**: CSS variable + `@custom-variant dark` 로 컴포넌트 코드 변경 없이 모드 전환.
- **refactor 비용 ↓**: hardcode `bg-zinc-50` 67건 → `bg-bg-subtle` 1회 변경으로 전체 영향.
- **a11y prefers-reduced-motion 대응 의무화**: motion sensitive 사용자 영향 차단.
- **타이포 일관성**: 페이지마다 다른 h1 사이즈 (`text-3xl` vs `text-2xl`) → 단일 `--text-2xl` (모바일) / `--text-3xl` (데스크탑) 결정.

### 부정적
- **마이그레이션 비용**: 기존 ~6.2k LOC 의 hardcode 색상/사이즈 호출을 토큰으로 swap 필요. ui-ux-redesign 단계 4 PR 1 (tokens 도입) 이후 PR 2-9 모두 부분적 swap 동반.
- **Tailwind v4 의존성**: `@theme` directive 와 `@custom-variant dark` 는 Tailwind v4 alpha 문법. 안정성 검증 필요 (현 `app/globals.css` 가 이미 v4 사용 중이라 risk 추가 아님).
- **Pretendard font 호스팅**: 자체 호스팅 (`public/fonts/`) 또는 CDN (`cdn.jsdelivr.net/.../pretendard`) 결정 필요. CDN = 빠른 도입 but 외부 의존, 자체 호스팅 = 안정 but `public/fonts/PretendardVariable.woff2` ~2MB 추가.

### 학습 비용
- fe sub-agent 가 토큰 명명 규칙 (`--brand-*` / `--text-*` / `--space-*`) 학습 필요. 본 ADR + `docs/features/ui-ux-redesign.md` 단계 4 PR 1 prompt 에 cheat sheet 박제.

## Alternatives (considered)

### (A) hot pink-orange brand color (`#FF4D8D → #FF8744`) — 선택되지 않음
- 사유: 노래방 = "활기/에너지" 직관 매칭은 좋으나 (1) 식음료/뷰티 도메인 (배달의민족 노란색, 무신사 핑크) 과 충돌, (2) 텍스트 대비 어두운 핑크 대비 약함, (3) 한국 사용자에게 "노래방 부스 = 어두운 조명" 직관과 mismatch.

### (B) 단일 컬러 (gradient 없이) `indigo-500` solid — 선택되지 않음
- 사유: 토큰 단순성은 좋으나 hero / 음역대 측정 progress 같은 "wow" 시각 hub 에서 단조로움. gradient 는 선택적 hero 액센트로만 한정 사용 (body/배경 = solid).

### (C) Tailwind plugin 기반 (`tailwind.config.ts` extend) — 선택되지 않음
- 사유: Tailwind v4 가 `@theme` directive 로 config 를 CSS 안에서 처리 권장. `tailwind.config.ts` 외부 파일 의존성 추가는 v4 idiom 위반. `app/globals.css` 한 곳에서 토큰 + Tailwind 동시 관리가 v4 best practice.

### (D) Material Design tokens / shadcn theme 표준 채택 — 선택되지 않음
- 사유: shadcn (`--background` / `--foreground` / `--primary` 같은 추상 토큰) 은 일반화에 우수하지만 노래방 도메인 특이 토큰 (`--brand-gradient`, `--pitch-wave-color`, `--song-card-stripe`) 가 어색해진다. mobruji 자체 명명 규칙 채택.

## References

- 이슈 #1044 — rev sub-agent UI/UX 단계 1 audit (29 findings)
- `docs/features/ui-ux-redesign.md` — 단계 2-4 spec (본 ADR 의 적용 plan)
- `app/globals.css` — 현 Tailwind v4 + `@custom-variant dark` 코드 (마이그레이션 시작점)
- 토스 디자인 시스템 (https://toss.tech/article/tds-vision) — spring easing + 부드러운 shadow 인용
- 카카오뱅크 디자인 시스템 — pastel + 강한 black CTA 인용 (단계 4 PR 2 Button)
- Pretendard (https://github.com/orioncactus/pretendard) — 한글 폰트 OFL-1.1 라이선스
