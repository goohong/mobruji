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
