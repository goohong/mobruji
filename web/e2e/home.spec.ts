/**
 * S1: 홈 페이지 신규 사용자 smoke.
 *
 * spec: docs/features/web-e2e-playwright.md §5-3 시나리오 표 #S1
 *
 * 검증:
 *  1. `/` 페이지 HTTP 200 응답
 *  2. 헤더 카피 "오늘 노래방, 뭐 부르지?" 가 화면에 렌더
 *  3. 신규(미측정) 사용자용 진입 경로 — 페르소나 카드(측정 방식 선택 `/voice-range/method`)와
 *     "직접 입력으로 시작"(`/voice-range`) 보조 경로가 노출되고 클릭 가능
 *  4. 페이지 로드 중 console error 0건
 *
 * 가정 (서버 기동):
 *  - 본 spec 은 `baseURL=http://localhost:3000` 가정 (playwright.config.ts).
 *  - 로컬: `npm run dev` 또는 `npm run build && npm run start` 후 `npm run test:e2e`.
 *  - CI: 후속 impl PR 3 의 web-e2e.yml 가 `npm run build` + `npm run start` 자동 기동.
 *
 * 비고:
 *  - SSR snapshot 시점에 zustand persist 가 hydrate 되지 않아 항상 NewUserPanel 이 렌더된다.
 *    따라서 localStorage seed 없이도 신규 사용자 진입 경로 검증이 안정적.
 *  - #1571(OnboardingIntentPicker) 이후 신규 사용자 진입은 단일 "음역대 측정하기" CTA 대신
 *    3 페르소나 카드 + "직접 입력으로 시작" 보조 경로로 대체됐다. 단언 SoT 는 단위 테스트
 *    `web/app/page.test.tsx`(NewUserPanel 분기) 와 일치한다. 페르소나 카드는 자식 경로
 *    미구현이라 전부 `/voice-range/method`(측정 방식 선택) 로 라우팅한다
 *    (OnboardingIntentPicker `PATH_DESTINATION`, directive #1511).
 *  - 후속 impl PR 2 에서 S2 (returning user — localStorage seed) 추가 예정.
 */
import { expect, test } from "@playwright/test";

test.describe("S1: 홈 페이지 신규 사용자 smoke", () => {
  test("페이지 로드 + 헤더 카피 + 진입 경로 카드 + 콘솔 에러 0건", async ({ page }) => {
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

    // 신규(미측정) 사용자 진입 경로 — 페르소나 카드(OnboardingIntentPicker).
    // 자식 경로 미구현이라 측정 방식 선택 `/voice-range/method` 로 라우팅한다.
    const beginnerCard = page.getByRole("link", { name: "내 목소리부터 알아보기" });
    await expect(beginnerCard).toBeVisible();
    await expect(beginnerCard).toHaveAttribute("href", "/voice-range/method");

    // 신규 사용자 secondary CTA — 직접 입력 진입(`/voice-range`).
    const manualCta = page.getByRole("link", { name: "직접 입력으로 시작" });
    await expect(manualCta).toBeVisible();
    await expect(manualCta).toHaveAttribute("href", "/voice-range");

    // 콘솔 에러 0건. 에러 발생 시 디버깅 용이를 위해 본문에 포함.
    expect(
      consoleErrors,
      `홈 페이지 로드 중 console error 0건이어야 합니다: ${consoleErrors.join(" | ")}`,
    ).toEqual([]);
  });
});
