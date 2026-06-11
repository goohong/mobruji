/**
 * Playwright config — mobruji web smoke e2e.
 *
 * spec: docs/features/web-e2e-playwright.md §5-3
 *
 * 요지:
 *  - smoke 시나리오는 `web/e2e/` 디렉토리에 격리 (Vitest 단위 테스트 `app/**\/*.test.tsx` 와 분리).
 *  - chromium-only 우선 (firefox/webkit nightly 옵션은 후속 PR).
 *  - reporter: local `list`, CI 환경에서는 `github` annotations + `html` artifact.
 *  - retries=0 (local), CI 1회 (flaky tolerance 최소).
 *  - baseURL `http://localhost:3000` (`next start` 또는 `next dev`). 후속 PR 에서 webServer 자동 기동.
 *
 * Visual snapshot (`toHaveScreenshot`) — Q1 결정: 본 PR 은 비활성 (DOM/CSS `toHaveCSS` 직접 검증).
 */
import { defineConfig, devices } from "@playwright/test";

const isCi = !!process.env.CI;

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: true,
  forbidOnly: isCi,
  retries: isCi ? 1 : 0,
  workers: isCi ? 1 : undefined,
  reporter: isCi ? [["github"], ["html", { open: "never" }]] : [["list"]],
  use: {
    baseURL: process.env.PLAYWRIGHT_BASE_URL ?? "http://localhost:3000",
    headless: true,
    trace: "on-first-retry",
    screenshot: "only-on-failure",
  },
  projects: [
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"] },
    },
  ],
});
