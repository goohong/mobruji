/**
 * S3: 추천 페이지 smoke.
 *
 * spec: docs/features/web-e2e-playwright.md §5-3 시나리오 표 #S3
 *
 * 검증:
 *  1. `/recommend` 페이지 HTTP 200 응답
 *  2. 음역대(voice-range) 미입력 시 fallback CTA (음역대 입력하러 가기) 가 노출
 *  3. 음역대 + 추천 API mock 후 진입 시 페이지 헤더(2단계 / 추천 결과) 가 렌더
 *  4. 페이지 로드 중 console error 0건
 *
 * mock 전략 (spec §7, Q4 결정 (b)):
 *  - BE 의존 endpoint 2종 (`/api/v1/voice-ranges/{sessionId}` + `/api/v1/recommendations`)
 *    을 Playwright 네이티브 `page.route()` 로 mock. msw 의존 추가 없이 e2e self-contained.
 *  - route 매칭은 origin 무관 **pathname predicate** 로 건다 — 로컬(`localhost:8080`)·
 *    dev 배포(상대 경로가 `PLAYWRIGHT_BASE_URL` origin 으로 해소) 양쪽에서 동일하게 intercept
 *    되도록 origin 하드코딩을 제거했다 (#1756).
 *
 * 가정 (서버 기동):
 *  - `baseURL=http://localhost:3000` (playwright.config.ts). 로컬: `npm run dev` 또는
 *    `npm run build && npm run start`.
 *  - CI: 후속 impl PR 3 의 web-e2e.yml 가 자동 기동.
 *
 * 비고:
 *  - SSR snapshot 시점에 `useSessionStore` 가 hydrate 되지 않아 `RecommendPage` 가
 *    `NoSessionFallback` (StatusShell) 을 렌더한다. localStorage seed 없이는 fallback
 *    경로가 안정적으로 검증된다.
 *  - mock case 는 store 를 강제 hydrate 하기 위해 `addInitScript` 로 localStorage 에
 *    sessionId 를 미리 심는다 (voice-range mutation 흐름과 동일).
 */
import { expect, test } from "@playwright/test";

const SESSION_ID = "00000000-0000-7000-8000-000000000001";

test.describe("S3: 추천 페이지 smoke", () => {
  test("음역대 미입력 fallback — 음역대 입력 CTA + 콘솔 에러 0건", async ({
    page,
  }) => {
    const consoleErrors: string[] = [];
    page.on("console", (message) => {
      if (message.type() === "error") {
        consoleErrors.push(message.text());
      }
    });

    const response = await page.goto("/recommend");
    expect(response, "GET /recommend 응답이 존재해야 합니다.").not.toBeNull();
    expect(
      response!.status(),
      "GET /recommend status 가 200 이어야 합니다.",
    ).toBe(200);

    // 세션 / 음역대 미입력 → NoSessionFallback (StatusShell) 노출.
    // 헤더 카피와 CTA href 를 동시에 검증해 fallback 분기를 fixate.
    await expect(
      page.getByRole("heading", {
        level: 1,
        name: "음역대가 아직 등록되지 않았습니다",
      }),
    ).toBeVisible();

    const fallbackCta = page.getByRole("link", {
      name: "음역대 입력하러 가기",
    });
    await expect(fallbackCta).toBeVisible();
    await expect(fallbackCta).toHaveAttribute("href", "/voice-range");

    expect(
      consoleErrors,
      `/recommend fallback 로드 중 console error 0건이어야 합니다: ${consoleErrors.join(" | ")}`,
    ).toEqual([]);
  });

  test("음역대 + 추천 API mock 후 추천 결과 헤더 렌더", async ({ page }) => {
    const consoleErrors: string[] = [];
    page.on("console", (message) => {
      if (message.type() === "error") {
        consoleErrors.push(message.text());
      }
    });

    // BE 호출 mock — voice-range (GET) + recommendations (POST).
    // 두 endpoint 의 응답 schema 는 web/lib/api/voice-range.ts + recommendation.ts 와 동일.
    await page.route(
      (url) => url.pathname === `/api/v1/voice-ranges/${SESSION_ID}`,
      async (route) => {
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify({
            id: 1,
            sessionId: SESSION_ID,
            lowestNoteMidi: 48,
            highestNoteMidi: 69,
            sourceMethod: "OCTAVE_PICK",
            createdAt: "2026-05-28T00:00:00Z",
            updatedAt: "2026-05-28T00:00:00Z",
          }),
        });
      },
    );

    await page.route(
      (url) => url.pathname === "/api/v1/recommendations",
      async (route) => {
        // 빈 추천 응답 — page 가 "더 이상 추천할 곡이 없어요" fallback 을 렌더.
        // 곡 카드를 렌더하지 않아도 페이지 헤더/2단계 caption 가 smoke 충분.
        await route.fulfill({
          status: 201,
          contentType: "application/json",
          body: JSON.stringify({
            requestId: "01HXY00000000000000000000A",
            recommendations: [],
          }),
        });
      },
    );

    // zustand persist seed — store key 는 `mobruji-session` (web/store/session.ts SoT).
    // 실 store 구현 변경 시 본 seed 가 stale 해질 수 있어 fallback 검증과 별도 시나리오 분리.
    await page.addInitScript((sessionId) => {
      window.localStorage.setItem(
        "mobruji-session",
        JSON.stringify({
          state: { sessionId, voiceRangeId: 1, excludedSongIds: [] },
          version: 0,
        }),
      );
    }, SESSION_ID);

    const response = await page.goto("/recommend");
    expect(response, "GET /recommend 응답이 존재해야 합니다.").not.toBeNull();
    expect(response!.status()).toBe(200);

    // 2단계 caption + 헤더 "추천 결과" 가 렌더되면 RecommendContent 분기 도달.
    await expect(page.getByText("2단계", { exact: true })).toBeVisible();
    await expect(
      page.getByRole("heading", { level: 1, name: "추천 결과" }),
    ).toBeVisible();

    // 음역대 헤더 ("내 음역대: …") 가 표시 — voice-range mock 응답 사용 신호.
    await expect(page.getByText(/내 음역대:/)).toBeVisible();

    expect(
      consoleErrors,
      `/recommend mock 로드 중 console error 0건이어야 합니다: ${consoleErrors.join(" | ")}`,
    ).toEqual([]);
  });
});
