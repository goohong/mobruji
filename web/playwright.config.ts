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
 *  - baseURL `http://localhost:3000` (`next start` 또는 `next dev`).
 *  - webServer 자동 기동 (#1397): `PLAYWRIGHT_BASE_URL` 외부 지정 시 (rev dev 배포 E2E)
 *    스킵, 그 외엔 `npm run dev` 로 로컬 dev 서버를 자동 기동/재사용한다.
 *
 * Visual snapshot (`toHaveScreenshot`) — Q1 결정: 본 PR 은 비활성 (DOM/CSS `toHaveCSS` 직접 검증).
 */
import { defineConfig, devices } from "@playwright/test";

const isCi = !!process.env.CI;

// 외부 base URL 이 명시되면 (rev dev 배포 E2E 검증 등) 로컬 webServer 기동을 건너뛴다.
const externalBaseUrl = process.env.PLAYWRIGHT_BASE_URL;
const baseURL = externalBaseUrl ?? "http://localhost:3000";

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: true,
  forbidOnly: isCi,
  retries: isCi ? 1 : 0,
  workers: isCi ? 1 : undefined,
  reporter: isCi ? [["github"], ["html", { open: "never" }]] : [["list"]],
  use: {
    baseURL,
    headless: true,
    trace: "on-first-retry",
    screenshot: "only-on-failure",
  },
  // 외부 base URL 지정 시 webServer 미기동 — 이미 떠 있는 배포본을 그대로 검증.
  webServer: externalBaseUrl
    ? undefined
    : {
        command: "npm run dev",
        url: "http://localhost:3000",
        reuseExistingServer: !isCi,
        timeout: 120_000,
      },
  projects: [
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"] },
    },
  ],
});
