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
      /:where\(html\.dark\)\s*{([\s\S]*?)}/m,
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
      tokens.match(/:where\(html\.dark\)/g)?.length ?? 0;
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
});
