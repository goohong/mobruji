/**
 * manifest.json 파싱 + 핵심 필드 검증.
 *
 * - PWA 설치 가능 여부에 직결되는 필드(name/start_url/display/icons)는
 *   회귀 방지를 위해 단위 테스트로 잠가둔다.
 * - 아이콘 src는 실제 파일 존재 여부까지는 검증하지 않는다(런타임 자산은 별도 E2E).
 */

import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";

import { describe, expect, it } from "vitest";

const dirname = path.dirname(fileURLToPath(import.meta.url));
const manifestPath = path.join(dirname, "manifest.json");

type ManifestIcon = {
  src: string;
  sizes: string;
  type: string;
  purpose?: string;
};

type Manifest = {
  name: string;
  short_name: string;
  description: string;
  start_url: string;
  display: string;
  theme_color: string;
  background_color: string;
  icons: ManifestIcon[];
};

describe("public/manifest.json", () => {
  const manifestRaw = readFileSync(manifestPath, "utf-8");
  const manifest = JSON.parse(manifestRaw) as Manifest;

  it("필수 PWA 필드를 가진다", () => {
    expect(manifest.name).toBe("모부르지");
    expect(manifest.short_name).toBe("모부르지");
    expect(manifest.start_url).toBe("/");
    expect(manifest.display).toBe("standalone");
    expect(manifest.theme_color).toBe("#000000");
    expect(manifest.background_color).toBe("#ffffff");
  });

  it("192/512 아이콘을 모두 포함하고 512는 maskable이다", () => {
    const sizes = manifest.icons.map((icon) => icon.sizes);
    expect(sizes).toContain("192x192");
    expect(sizes).toContain("512x512");

    const large = manifest.icons.find((icon) => icon.sizes === "512x512");
    expect(large).toBeDefined();
    expect(large?.purpose ?? "").toMatch(/maskable/);
  });
});
