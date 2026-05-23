/** RootLayout metadata/viewport 회귀 가드 (closes #731): PWA themeColor/manifest/appleWebApp. */
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
