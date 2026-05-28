/**
 * S1: 홈 페이지 신규 사용자 smoke.
 *
 * spec: docs/features/web-e2e-playwright.md §5-3 시나리오 표 #S1
 *
 * 검증:
 *  1. `/` 페이지 HTTP 200 응답
 *  2. 헤더 카피 "오늘 노래방, 뭐 부르지?" 가 화면에 렌더
 *  3. 신규(미측정) 사용자용 primary CTA "음역대 측정하기" 가 화면에 노출되고 클릭 가능
 *  4. 페이지 로드 중 console error 0건
 *
 * 가정 (서버 기동):
 *  - 본 spec 은 `baseURL=http://localhost:3000` 가정 (playwright.config.ts).
 *  - 로컬: `npm run dev` 또는 `npm run build && npm run start` 후 `npm run test:e2e`.
 *  - CI: 후속 impl PR 3 의 web-e2e.yml 가 `npm run build` + `npm run start` 자동 기동.
 *
 * 비고:
 *  - SSR snapshot 시점에 zustand persist 가 hydrate 되지 않아 항상 NewUserPanel 이 렌더된다.
 *    따라서 localStorage seed 없이도 신규 사용자 CTA 검증이 안정적.
 *  - 후속 impl PR 2 에서 S2 (returning user — localStorage seed) 추가 예정.
 */
import { expect, test } from "@playwright/test";

test.describe("S1: 홈 페이지 신규 사용자 smoke", () => {
  test("페이지 로드 + 헤더 카피 + 음역대 측정 CTA + 콘솔 에러 0건", async ({ page }) => {
    // 콘솔 에러를 수집 — Next.js dev / build 모드에서 hydration mismatch, network 4xx 등 잡기 위함.
    const consoleErrors: string[] = [];
    page.on("console", (message) => {
      if (message.type() === "error") {
        consoleErrors.push(message.text());
      }
    });

    const response = await page.goto("/");
    expect(response, "GET / 응답이 존재해야 합니다.").not.toBeNull();
    expect(response!.status(), "GET / status 가 200 이어야 합니다.").toBe(200);

    // 페이지 헤더 카피 — 홈 페이지가 실제로 렌더된 SoT.
    await expect(
      page.getByRole("heading", { level: 1, name: "오늘 노래방, 뭐 부르지?" }),
    ).toBeVisible();

    // 신규(미측정) 사용자 primary CTA — `<Link href="/voice-range/auto">음역대 측정하기</Link>`.
    const measureCta = page.getByRole("link", { name: "음역대 측정하기" });
    await expect(measureCta).toBeVisible();
    await expect(measureCta).toHaveAttribute("href", "/voice-range/auto");

    // 신규 사용자 secondary CTA — 직접 입력 진입.
    await expect(
      page.getByRole("link", { name: "직접 입력으로 시작" }),
    ).toBeVisible();

    // 콘솔 에러 0건. 에러 발생 시 디버깅 용이를 위해 본문에 포함.
    expect(
      consoleErrors,
      `홈 페이지 로드 중 console error 0건이어야 합니다: ${consoleErrors.join(" | ")}`,
    ).toEqual([]);
  });
});
