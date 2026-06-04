/** RootLayout metadata/viewport 회귀 가드 (closes #731): PWA themeColor/manifest/appleWebApp. */
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

import { describe, expect, it } from "vitest";

import { metadata, viewport } from "./layout";

describe("RootLayout metadata", () => {
  it("PWA manifest와 applicationName/appleWebApp을 노출한다", () => {
    expect(metadata.manifest).toBe("/manifest.json");
    expect(metadata.applicationName).toBe("모부르지");
    expect(metadata.appleWebApp).toMatchObject({
      capable: true,
      title: "모부르지",
      statusBarStyle: "black-translucent",
    });
  });

  it("icons.icon 192/512 SVG를 노출한다 (PWA 아이콘 회귀 방지)", () => {
    const icons = (metadata.icons as { icon: { sizes: string }[] }).icon;
    const sizes = icons.map((i) => i.sizes);
    expect(sizes).toContain("192x192");
    expect(sizes).toContain("512x512");
  });
});

describe("RootLayout viewport", () => {
  it("themeColor를 prefers-color-scheme light/dark로 분기한다 (#296 #319)", () => {
    const themeColor = viewport.themeColor as { media: string; color: string }[];
    expect(themeColor).toHaveLength(2);
    const light = themeColor.find((t) => t.media.includes("light"));
    const dark = themeColor.find((t) => t.media.includes("dark"));
    expect(light?.color).toBe("#ffffff");
    expect(dark?.color).toBe("#0a0a0a");
  });

  it("모바일 PWA viewport 기본값을 노출한다", () => {
    expect(viewport.width).toBe("device-width");
    expect(viewport.initialScale).toBe(1);
    expect(viewport.viewportFit).toBe("cover");
  });
});

/**
 * RootLayout `<html>` 다크모드 hydration 회귀 가드 (이슈 #1170).
 *
 * THEME_INIT_SCRIPT 가 hydration 직전에 `<html>` 의 className 에 `dark` 클래스를
 * 동적으로 추가한다. 이때 React 가 hydration mismatch 경고를 띄우거나 일부
 * 환경에서 className 을 server 버전으로 덮어써 다크 클래스가 사라지는 회귀가
 * 있었다 (다크모드 토글 버튼 무반응 증상의 root cause 중 하나).
 *
 * `<html>` element 는 RTL 로 격리 render 하기 까다로워 — Next.js 가 server-only
 * 로 RSC 트리를 합치는 root layout 이라 ReactDOM.render 가 throw — source 텍스트
 * 검사로 회귀를 가드한다. `next-themes` 등 표준 패턴이 이 prop 을 강제한다.
 */
describe("RootLayout <html> suppressHydrationWarning", () => {
  const source = readFileSync(
    resolve(__dirname, "layout.tsx"),
    "utf-8",
  );

  it("`<html>` 에 suppressHydrationWarning prop 이 있다 (#1170)", () => {
    // `<html ... suppressHydrationWarning ...>` 패턴을 attribute 위치에 한정해 매칭.
    // 주석 안에서만 등장하지 않도록 JSX prop 형태 (앞에 공백, 뒤에 공백/줄바꿈) 강제.
    const htmlOpenMatch = source.match(/<html\b[\s\S]*?>/);
    expect(htmlOpenMatch, "`<html>` opening tag must exist").not.toBeNull();
    expect(htmlOpenMatch?.[0]).toMatch(/\bsuppressHydrationWarning\b/);
  });
});

/**
 * RootLayout 폰트 로딩 회귀 가드 (ui-ux-redesign 단계 4 PR 9, #1692).
 *
 * Pretendard 를 globals.css 수동 `@font-face` 에서 next/font/local 로 이관했다.
 * next/font 가 자체 호스팅 + size-adjust fallback(CLS↓) + display:swap(FOIT 회피)을
 * 자동 처리하고, Geist 도 next/font 변수로 token stack 에 연결된다. `<html>` 은
 * RSC root layout 이라 RTL 격리 render 가 까다로우므로 source 텍스트로 가드한다.
 */
describe("RootLayout 폰트 로딩 (PR 9, #1692)", () => {
  const source = readFileSync(resolve(__dirname, "layout.tsx"), "utf-8");

  it("Pretendard 를 next/font/local 로 로드한다", () => {
    expect(source).toMatch(/import\s+localFont\s+from\s+["']next\/font\/local["']/);
    // localFont 호출이 woff2 self-host 소스를 가리킨다.
    const localFontCall = source.match(/localFont\(\{[\s\S]*?\}\)/);
    expect(localFontCall, "localFont(...) 호출이 있어야 함").not.toBeNull();
    const call = localFontCall?.[0] ?? "";
    expect(call).toMatch(/src:\s*["'][^"']*PretendardVariable\.woff2["']/);
    // FOIT 회피 — swap 의무.
    expect(call).toMatch(/display:\s*["']swap["']/);
    // variable axis 전체 범위 — Pretendard v1.3 weight 45-920.
    expect(call).toMatch(/weight:\s*["']45 920["']/);
    // token stack 이 가리키는 CSS 변수 노출.
    expect(call).toMatch(/variable:\s*["']--font-pretendard["']/);
    // ~2MB CJK 폰트 — eager preload 로 LCP 를 해치지 않도록 끈다.
    expect(call).toMatch(/preload:\s*false/);
  });

  it("`<html>` className 에 Pretendard + Geist next/font 변수가 모두 적용된다", () => {
    const htmlOpenMatch = source.match(/<html\b[\s\S]*?>/);
    expect(htmlOpenMatch).not.toBeNull();
    const htmlTag = htmlOpenMatch?.[0] ?? "";
    expect(htmlTag).toMatch(/pretendard\.variable/);
    expect(htmlTag).toMatch(/geistSans\.variable/);
    expect(htmlTag).toMatch(/geistMono\.variable/);
  });

  it("Geist sans/mono 도 display:swap 을 명시한다 (FOIT 회피)", () => {
    const geistSans = source.match(/Geist\(\{[\s\S]*?\}\)/);
    const geistMono = source.match(/Geist_Mono\(\{[\s\S]*?\}\)/);
    expect(geistSans?.[0]).toMatch(/display:\s*["']swap["']/);
    expect(geistMono?.[0]).toMatch(/display:\s*["']swap["']/);
  });
});
