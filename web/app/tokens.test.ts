/**
 * design tokens (ADR-0018) sanity test — 토큰 정의가 누락/오타되면 빌드 후 페이지
 * 가 토큰 미정의를 silent 로 무시 (브라우저 unknown variable = unset). 텍스트
 * level 단언으로 핵심 토큰 7 카테고리가 모두 정의됐는지 확인한다.
 *
 * 본 PR 시점엔 토큰 사용처가 0건이라 실제 페이지 회귀 위험은 없지만, 후속 PR 의
 * 토큰 swap 이 시작될 때 누락된 토큰을 미리 감지하는 회귀 가드.
 */

import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { join } from "node:path";

const TOKENS_PATH = join(__dirname, "tokens.css");
const GLOBALS_PATH = join(__dirname, "globals.css");

describe("design tokens (ADR-0018)", () => {
  const tokens = readFileSync(TOKENS_PATH, "utf-8");
  const globals = readFileSync(GLOBALS_PATH, "utf-8");

  it("globals.css 가 tokens.css 를 import 한다", () => {
    expect(globals).toMatch(/@import\s+["']\.\/tokens\.css["']/);
  });

  it("color brand scale (50~900) + gradient 가 모두 정의된다", () => {
    for (const step of [50, 100, 200, 300, 400, 500, 600, 700, 800, 900]) {
      expect(tokens).toMatch(new RegExp(`--brand-${step}:`));
    }
    expect(tokens).toMatch(/--brand-gradient:\s*linear-gradient/);
  });

  it("semantic color 4종 (success/warning/danger/info) 50/500 scale 정의", () => {
    for (const name of ["success", "warning", "danger", "info"]) {
      expect(tokens).toMatch(new RegExp(`--${name}-50:`));
      expect(tokens).toMatch(new RegExp(`--${name}-500:`));
    }
  });

  /*
   * #1044 단계 4 PR 9 — danger 의미 토큰 (alert 박스 swap) 회귀 가드.
   *
   * 사용처 (recommend / songs / voice-range / Input.tsx) 의 `text-red` /
   * `bg-red` / `border-red` 가 본 토큰 6종 (bg/border/fg-strong/fg-soft/cta-bg/
   * cta-bg-hover) 으로 일괄 swap. 토큰 정의가 누락되면 빌드 후 alert 박스가
   * unset 으로 paint → 본 테스트가 사전 차단.
   *
   * dark mode swap 도 필수 — 사용처에서 `dark:` prefix 제거했으므로
   * tokens.css 의 `:where(html.dark)` 안에 같은 토큰들이 재정의돼야 한다.
   */
  it("danger 의미 토큰 (alert 박스) light + dark 정의 + 600/700 scale", () => {
    // scale 확장 — text-red-700 / bg-red-600 hardcode 매핑용.
    expect(tokens).toMatch(/--danger-600:/);
    expect(tokens).toMatch(/--danger-700:/);

    const dangerSemanticTokens = [
      "--danger-bg",
      "--danger-border",
      "--danger-fg-strong",
      "--danger-fg-soft",
      "--danger-cta-bg",
      "--danger-cta-bg-hover",
    ];
    for (const token of dangerSemanticTokens) {
      expect(tokens).toMatch(new RegExp(`${token}:`));
    }

    // dark mode swap — 사용처에서 `dark:` prefix 제거했으므로 토큰 자체가 swap 책임.
    // tokens.css 의 dark mode block 은 2개 (color + shadow) 이상 — `--danger-bg`
    // / `--danger-border` / `--danger-fg-*` 가 그 중 color block 안에 있어야 함.
    const allDarkBlocks = [
      ...tokens.matchAll(/html\.dark\s*{([\s\S]*?)}/g),
    ].map((m) => m[1]);
    expect(allDarkBlocks.length).toBeGreaterThanOrEqual(1);
    const allDarkContent = allDarkBlocks.join("\n");
    for (const token of dangerSemanticTokens) {
      expect(allDarkContent).toMatch(new RegExp(`${token}:`));
    }
  });

  it("neutral token (bg/border/text) 가 light + dark 모두 정의된다", () => {
    const requiredTokens = [
      "--bg-base",
      "--bg-subtle",
      "--bg-muted",
      "--border",
      "--text-primary",
      "--text-secondary",
      "--text-tertiary",
    ];
    for (const token of requiredTokens) {
      expect(tokens).toMatch(new RegExp(`${token}:`));
    }
    // dark mode swap block 안에 같은 토큰들이 재정의됨.
    const darkBlockMatch = tokens.match(
      /html\.dark\s*{([\s\S]*?)}/m,
    );
    expect(darkBlockMatch).not.toBeNull();
    const darkBlock = darkBlockMatch?.[1] ?? "";
    for (const token of requiredTokens) {
      expect(darkBlock).toMatch(new RegExp(`${token}:`));
    }
  });

  it("typography — font-family 3종 + scale 8단계 + weight 5단계", () => {
    expect(tokens).toMatch(/--font-sans:/);
    expect(tokens).toMatch(/--font-display:/);
    expect(tokens).toMatch(/--font-mono:/);

    for (const size of ["xs", "sm", "base", "lg", "xl", "2xl", "3xl", "display"]) {
      expect(tokens).toMatch(new RegExp(`--text-${size}:`));
    }
    for (const weight of ["regular", "medium", "semibold", "bold", "black"]) {
      expect(tokens).toMatch(new RegExp(`--font-${weight}:`));
    }
  });

  it("spacing — 4px base scale 8 step + 의미 alias 4종", () => {
    for (const step of [1, 2, 3, 4, 6, 8, 12, 16]) {
      expect(tokens).toMatch(new RegExp(`--space-${step}:`));
    }
    for (const alias of [
      "page-padding-x",
      "page-padding-y",
      "card-padding",
      "section-gap",
    ]) {
      expect(tokens).toMatch(new RegExp(`--${alias}:`));
    }
  });

  it("radius 5단계 (sm/md/lg/xl/full)", () => {
    for (const size of ["sm", "md", "lg", "xl", "full"]) {
      expect(tokens).toMatch(new RegExp(`--radius-${size}:`));
    }
  });

  it("shadow — elevation 4단계 + brand accent + dark 강도 swap", () => {
    for (const size of ["sm", "md", "lg", "xl"]) {
      expect(tokens).toMatch(new RegExp(`--shadow-${size}:`));
    }
    expect(tokens).toMatch(/--shadow-brand:\s*0 8px 32px/);
    // dark mode 가 shadow 도 강도 swap.
    const darkBlockOccurrences =
      tokens.match(/html\.dark/g)?.length ?? 0;
    expect(darkBlockOccurrences).toBeGreaterThanOrEqual(2);
  });

  it("motion — duration 4단계 + easing 4종", () => {
    for (const speed of ["fast", "base", "slow", "slower"]) {
      expect(tokens).toMatch(new RegExp(`--duration-${speed}:`));
    }
    for (const easing of ["out", "in-out", "spring", "emphasized"]) {
      expect(tokens).toMatch(new RegExp(`--ease-${easing}:\\s*cubic-bezier`));
    }
  });

  it("prefers-reduced-motion 가드가 토큰 도입과 함께 포함된다 (a11y 의무)", () => {
    expect(tokens).toMatch(/@media\s*\(\s*prefers-reduced-motion:\s*reduce\s*\)/);
    // duration / animation 모두 단축 + scroll-behavior 도 보정.
    expect(tokens).toMatch(/transition-duration:\s*10ms\s*!important/);
    expect(tokens).toMatch(/animation-duration:\s*10ms\s*!important/);
    expect(tokens).toMatch(/scroll-behavior:\s*auto\s*!important/);
  });

  /*
   * ADR-0018 단계 4 PR 2 — Pretendard Variable 자체 호스팅 회귀 가드.
   * 본 PR 부터 폰트 가 `web/public/fonts/PretendardVariable.woff2` 에 위치 +
   * globals.css 가 `@font-face` 로 등록 + body / Tailwind utility 가 Pretendard
   * 우선 stack 을 사용하도록 swap. 토큰 측 SoT (`--font-family-sans`) 가 별칭
   * 으로 살아있는지 검증해 자기참조 무한 fallback 회귀를 차단한다.
   */
  it("Pretendard Variable @font-face 가 globals.css 에 등록된다 (PR 2)", () => {
    // `/s` (dotAll) flag 는 ES2018+ 필요. tsconfig target 호환 위해 `[\s\S]` 사용.
    expect(globals).toMatch(
      /@font-face\s*{[\s\S]*?font-family:\s*["']Pretendard Variable["'][\s\S]*?}/,
    );
    expect(globals).toMatch(
      /src:\s*url\(["']\/fonts\/PretendardVariable\.woff2["']\)\s*format\(["']woff2-variations["']\)/,
    );
    // FOIT 회피 — swap 의무.
    expect(globals).toMatch(/font-display:\s*swap/);
    // variable axis 전체 범위 — Pretendard v1.3 weight 45-920.
    expect(globals).toMatch(/font-weight:\s*45\s+920/);
  });

  it("font-family 별칭 (`--font-family-*`) 이 SoT 로 정의된다 (자기참조 회귀 가드)", () => {
    // Tailwind v4 `@theme inline { --font-sans: var(--font-sans) }` 자기참조 무한
    // fallback 방지: SoT 가 별 이름 (`--font-family-*`) 을 가져야 한다.
    expect(tokens).toMatch(
      /--font-family-sans:\s*"Pretendard Variable"/,
    );
    expect(tokens).toMatch(/--font-family-display:/);
    expect(tokens).toMatch(/--font-family-mono:/);
    // `--font-sans` alias 가 별칭을 가리켜야 한다.
    expect(tokens).toMatch(/--font-sans:\s*var\(--font-family-sans\)/);
  });

  it("globals.css `@theme inline` 의 `--font-sans` 가 SoT 별칭을 가리킨다", () => {
    // 자기참조 방지: globals.css `@theme inline` 안의 `--font-sans` 가
    // `var(--font-family-sans)` 를 가리켜야 한다 (`var(--font-sans)` 면 무한 fallback).
    const themeInlineMatch = globals.match(/@theme inline\s*{([\s\S]*?)}/);
    expect(themeInlineMatch).not.toBeNull();
    const themeInline = themeInlineMatch?.[1] ?? "";
    expect(themeInline).toMatch(
      /--font-sans:\s*var\(--font-family-sans\)/,
    );
    expect(themeInline).toMatch(
      /--font-mono:\s*var\(--font-family-mono\)/,
    );
  });

  it("body font-family 가 토큰 (`var(--font-sans)`) 으로 swap 된다 (Arial hardcode 폐기)", () => {
    // 기존 `font-family: Arial, Helvetica, sans-serif` → `var(--font-sans)`.
    const bodyMatch = globals.match(/body\s*{([\s\S]*?)}/);
    expect(bodyMatch).not.toBeNull();
    const bodyBlock = bodyMatch?.[1] ?? "";
    expect(bodyBlock).toMatch(/font-family:\s*var\(--font-sans\)/);
    // Arial hardcode 잔존 0 확인.
    expect(bodyBlock).not.toMatch(/Arial/);
  });

  /*
   * ADR-0018 단계 4 PR 10 — form / input + badge 의미 토큰 회귀 가드.
   *
   * `components/ui/Input.tsx` 의 zinc hardcode 6 페어 + `recommend/page.tsx`
   * SourceMethodBadge 의 zinc/emerald 2 톤이 의미 토큰으로 swap 됐다. 다크 모드
   * swap 도 토큰 자체 (`:where(html.dark)`) 에서 처리해 사용처는 `dark:` prefix
   * 가 사라진다.
   */
  it("form / input 의미 토큰 6종이 light + dark 모두 정의된다 (PR 10)", () => {
    const requiredTokens = [
      "--surface-input",
      "--text-placeholder",
      "--text-label",
      "--border-input",
      "--border-input-focus",
      "--ring-input-focus",
    ];
    for (const token of requiredTokens) {
      expect(tokens).toMatch(new RegExp(`${token}:`));
    }
    // dark swap block 안에 같은 토큰들이 재정의됨.
    const darkBlocks = tokens.match(/html\.dark\s*{([\s\S]*?)}/g) ?? [];
    const allDark = darkBlocks.join("\n");
    for (const token of requiredTokens) {
      expect(allDark).toMatch(new RegExp(`${token}:`));
    }
  });

  it("badge 의미 토큰 4종 (neutral + success) light + dark 모두 정의된다 (PR 10)", () => {
    const requiredTokens = [
      "--badge-neutral-bg",
      "--badge-neutral-fg",
      "--badge-success-bg",
      "--badge-success-fg",
    ];
    for (const token of requiredTokens) {
      expect(tokens).toMatch(new RegExp(`${token}:`));
    }
    const darkBlocks = tokens.match(/html\.dark\s*{([\s\S]*?)}/g) ?? [];
    const allDark = darkBlocks.join("\n");
    for (const token of requiredTokens) {
      expect(allDark).toMatch(new RegExp(`${token}:`));
    }
  });

  /*
   * ADR-0018 단계 4 PR 11 — 홈 + 에러 페이지 잔여 zinc hardcode 의미 토큰화.
   *
   * `app/page.tsx` (홈 보조 CTA + SecondaryNav + VoiceRangeSummary + FlowStep) +
   * `app/error.tsx` (배경 + primary CTA + 보조 CTA + digest 식별자) 의 zinc
   * hardcode 를 4 그룹 토큰 (disclaimer / secondary-cta / neutral-cta /
   * surface-step) 으로 swap. 다크 모드 swap 은 토큰 자체 (`:where(html.dark)`)
   * 가 책임 → 사용처는 `dark:` prefix 제거.
   */
  it("disclaimer 텍스트 토큰이 light + dark 모두 정의된다 (PR 11)", () => {
    expect(tokens).toMatch(/--text-disclaimer:/);
    const darkBlocks = tokens.match(/html\.dark\s*{([\s\S]*?)}/g) ?? [];
    const allDark = darkBlocks.join("\n");
    expect(allDark).toMatch(/--text-disclaimer:/);
  });

  it("secondary CTA 의미 토큰 5종이 light + dark 모두 정의된다 (PR 11)", () => {
    const requiredTokens = [
      "--cta-secondary-bg",
      "--cta-secondary-bg-hover",
      "--cta-secondary-border",
      "--cta-secondary-fg",
      "--cta-secondary-ring",
    ];
    for (const token of requiredTokens) {
      expect(tokens).toMatch(new RegExp(`${token}:`));
    }
    const darkBlocks = tokens.match(/html\.dark\s*{([\s\S]*?)}/g) ?? [];
    const allDark = darkBlocks.join("\n");
    for (const token of requiredTokens) {
      expect(allDark).toMatch(new RegExp(`${token}:`));
    }
  });

  it("neutral CTA (검정/흰 invert) 의미 토큰 3종이 light + dark 모두 정의된다 (PR 11)", () => {
    const requiredTokens = [
      "--cta-neutral-bg",
      "--cta-neutral-bg-hover",
      "--cta-neutral-fg",
    ];
    for (const token of requiredTokens) {
      expect(tokens).toMatch(new RegExp(`${token}:`));
    }
    const darkBlocks = tokens.match(/html\.dark\s*{([\s\S]*?)}/g) ?? [];
    const allDark = darkBlocks.join("\n");
    for (const token of requiredTokens) {
      expect(allDark).toMatch(new RegExp(`${token}:`));
    }
  });

  it("step indicator surface 의미 토큰 2종이 light + dark 모두 정의된다 (PR 11)", () => {
    const requiredTokens = ["--surface-step-bg", "--surface-step-fg"];
    for (const token of requiredTokens) {
      expect(tokens).toMatch(new RegExp(`${token}:`));
    }
    const darkBlocks = tokens.match(/html\.dark\s*{([\s\S]*?)}/g) ?? [];
    const allDark = darkBlocks.join("\n");
    for (const token of requiredTokens) {
      expect(allDark).toMatch(new RegExp(`${token}:`));
    }
  });

  /*
   * ADR-0018 단계 4 PR 12 — voice-range/auto wizard 의 잔여 zinc/emerald/amber
   * hardcode 의미 토큰화 회귀 가드.
   *
   * `voice-range/auto/page.tsx` 에서 "안정/불안정" badge, mic level meter, progress
   * track, "감지 중" signal 텍스트가 토큰으로 swap. `badge-success` (PR 10) 와
   * `badge-warning` 페어를 함께 — dark 매핑이 한 단계 다른 `--text-signal-active`
   * (emerald-700 / emerald-300) 와 `--meter-track-bg` (zinc-200 / zinc-800) 도 새
   * 정의. 다크 모드 swap 은 토큰 자체에서 처리 → 사용처는 `dark:` prefix 제거.
   */
  it("badge warning + meter + signal 의미 토큰 4종이 light + dark 모두 정의된다 (PR 12)", () => {
    const requiredTokens = [
      "--badge-warning-bg",
      "--badge-warning-fg",
      "--meter-track-bg",
      "--text-signal-active",
    ];
    for (const token of requiredTokens) {
      expect(tokens).toMatch(new RegExp(`${token}:`));
    }
    const darkBlocks = tokens.match(/html\.dark\s*{([\s\S]*?)}/g) ?? [];
    const allDark = darkBlocks.join("\n");
    for (const token of requiredTokens) {
      expect(allDark).toMatch(new RegExp(`${token}:`));
    }
  });

  /*
   * ADR-0018 단계 4 PR 14 (PR #1219) — recommend/components 의 잔여 zinc hardcode
   * 의미 토큰화 회귀 가드.
   *
   * `SongCard.tsx` + `SongDetailModal.tsx` + `SongDetailContent.tsx` 에서 추천
   * 상세 모달 surface (zinc-50 / zinc-950), 본문 강조 텍스트 (zinc-700/300,
   * zinc-800/200, zinc-600/400), ring/border 한 단계 깊은 페어 (zinc-200/zinc-800),
   * 앨범 커버 placeholder 그라데이션 페어가 토큰으로 swap. 다크 모드 swap 은
   * 토큰 자체에서 처리 → 사용처는 `dark:` prefix 제거.
   */
  it("recommend 상세 모달 의미 토큰 7종이 light + dark 모두 정의된다 (PR 14)", () => {
    const requiredTokens = [
      "--surface-detail-section",
      "--text-body-strong",
      "--text-body-emphasis",
      "--text-detail-meta",
      "--ring-soft-detail",
      "--surface-cover-from",
      "--surface-cover-to",
    ];
    for (const token of requiredTokens) {
      expect(tokens).toMatch(new RegExp(`${token}:`));
    }
    const darkBlocks = tokens.match(/html\.dark\s*{([\s\S]*?)}/g) ?? [];
    const allDark = darkBlocks.join("\n");
    for (const token of requiredTokens) {
      expect(allDark).toMatch(new RegExp(`${token}:`));
    }
  });

  /*
   * ADR-0018 단계 4 PR 13 — history `VoiceRangeProgressCard.tsx` SVG fill-zinc +
   * card surface 잔여 zinc 의미 토큰화 회귀 가드.
   *
   * chart 막대 강조/비강조 fill 매핑 + card surface bg/ring 매핑이 본 PR 의 신규
   * 4 토큰. `--text-primary` (zinc-900/zinc-50) 와 `--chart-bar-active-bg`
   * (zinc-900/zinc-100) 는 dark 한 단계 차이 (chart 강조는 본문보다 부드러운
   * dark 톤) — 별 토큰 신설 사유. `--surface-card-{bg,ring}` 은 form input
   * (PR 10 `--surface-input` / `--border-input`) 과 같은 매핑이지만 의미 분리.
   *
   * 다크 모드 swap 은 토큰 자체에서 처리 → 사용처는 `dark:` prefix 제거.
   */
  it("chart bar + card surface 의미 토큰 4종이 light + dark 모두 정의된다 (PR 13)", () => {
    const requiredTokens = [
      "--chart-bar-active-bg",
      "--chart-bar-inactive-bg",
      "--surface-card-bg",
      "--surface-card-ring",
    ];
    for (const token of requiredTokens) {
      expect(tokens).toMatch(new RegExp(`${token}:`));
    }
    const darkBlocks = tokens.match(/html\.dark\s*{([\s\S]*?)}/g) ?? [];
    const allDark = darkBlocks.join("\n");
    for (const token of requiredTokens) {
      expect(allDark).toMatch(new RegExp(`${token}:`));
    }
  });

  /*
   * ADR-0018 §4-2 (PR #1263 매트릭스 sub-PR 1) — `SongDetailModal.tsx` 의
   * backdrop + 모달 표면 hardcode 의미 토큰화 회귀 가드.
   *
   * backdrop (zinc-900/60, light/dark 동일) + 모달 표면 (white / dark zinc-900).
   * 다크 모드 swap 은 토큰 자체에서 처리 → 사용처는 `dark:` prefix 제거.
   */
  it("modal 의미 토큰 2종이 light + dark 모두 정의된다 (PR #1263 sub-PR 1)", () => {
    const requiredTokens = ["--modal-backdrop", "--surface-modal"];
    for (const token of requiredTokens) {
      expect(tokens).toMatch(new RegExp(`${token}:`));
    }
    const darkBlocks = tokens.match(/html\.dark\s*{([\s\S]*?)}/g) ?? [];
    const allDark = darkBlocks.join("\n");
    for (const token of requiredTokens) {
      expect(allDark).toMatch(new RegExp(`${token}:`));
    }
  });

  /*
   * ADR-0018 단계 4 게이트 마감 (PR #1659) — 잔존 zinc hardcode 의미 토큰화 회귀
   * 가드. 매트릭스 활성 카운트 0 마감용 신규 7종.
   *
   *  - soft ring (SongCard hover/focus-within) : `--ring-soft-hover` /
   *    `--ring-soft-focus-within`
   *  - floating 표면 (ThemeToggle/HomeLink, baked-alpha rgba) :
   *    `--surface-floating` / `--surface-floating-hover`
   *  - nav 표면 (BottomNav, baked-alpha rgba) : `--surface-nav` /
   *    `--surface-nav-blur`
   *  - subtle border (Card header/footer divider) : `--border-subtle`
   *
   * 다크 모드 swap 은 토큰 자체에서 처리 → 사용처는 `dark:` prefix 제거.
   */
  it("잔존 swap 완결 의미 토큰 7종이 light + dark 모두 정의된다 (PR #1659)", () => {
    const requiredTokens = [
      "--ring-soft-hover",
      "--ring-soft-focus-within",
      "--surface-floating",
      "--surface-floating-hover",
      "--surface-nav",
      "--surface-nav-blur",
      "--border-subtle",
    ];
    for (const token of requiredTokens) {
      expect(tokens).toMatch(new RegExp(`${token}:`));
    }
    const darkBlocks = tokens.match(/html\.dark\s*{([\s\S]*?)}/g) ?? [];
    const allDark = darkBlocks.join("\n");
    for (const token of requiredTokens) {
      expect(allDark).toMatch(new RegExp(`${token}:`));
    }
  });
});
