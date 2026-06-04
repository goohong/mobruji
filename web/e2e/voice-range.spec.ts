/**
 * S5: 음역대 입력/측정 진입 smoke.
 *
 * spec: docs/features/web-e2e-playwright.md §5-3 시나리오 표 #S5
 *
 * 본 spec 은 두 진입점을 한 번씩 smoke 한다:
 *  - `/voice-range`      → 수동 입력 (OCTAVE_PICK) 페이지. "1/2 단계" caption + 자동/직접 두 섹션.
 *  - `/voice-range/auto` → 자동 측정 wizard. PERMISSION 단계 "측정 시작" CTA + 마이크 권한 안내.
 *
 * 검증:
 *  1. 두 페이지 모두 HTTP 200 응답
 *  2. `/voice-range`: "1/2 단계" caption + h1 "내 음역대를 알려주세요" + 자동 측정 CTA
 *  3. `/voice-range/auto`: h1 "마이크로 음역대 측정하기" + "측정 시작" 버튼 + 마이크 안내 카피
 *  4. 두 페이지 모두 console error 0건
 *
 * 비고:
 *  - `/voice-range/auto` 는 user gesture 안 getUserMedia 를 호출하므로, headless
 *    chromium 의 default 거부 권한이 click 후에만 트리거된다. 본 smoke 는 click
 *    이전 단계 (PERMISSION 화면) 만 검증 — 권한 거부 fallback 흐름은 별도 e2e 분리.
 */
import { expect, test } from "@playwright/test";

test.describe("S5: 음역대 입력/측정 진입 smoke", () => {
  test("/voice-range — 수동 입력 페이지 + 자동 측정 CTA + 콘솔 에러 0건", async ({
    page,
  }) => {
    const consoleErrors: string[] = [];
    page.on("console", (message) => {
      if (message.type() === "error") {
        consoleErrors.push(message.text());
      }
    });

    const response = await page.goto("/voice-range");
    expect(response, "GET /voice-range 응답이 존재해야 합니다.").not.toBeNull();
    expect(response!.status()).toBe(200);

    // StepIndicator caption ("1/2 단계") + h1.
    await expect(page.getByText("1/2 단계", { exact: true })).toBeVisible();
    await expect(
      page.getByRole("heading", { level: 1, name: "내 음역대를 알려주세요" }),
    ).toBeVisible();

    // 자동 측정 진입 CTA (Link 컴포넌트, href=/voice-range/auto).
    const autoCta = page.getByRole("link", { name: "자동으로 측정하기" });
    await expect(autoCta).toBeVisible();
    await expect(autoCta).toHaveAttribute("href", "/voice-range/auto");

    // 직접 선택 submit 버튼 ("추천 받기") — Button 컴포넌트, type=submit.
    await expect(
      page.getByRole("button", { name: "추천 받기" }),
    ).toBeVisible();

    expect(
      consoleErrors,
      `/voice-range 로드 중 console error 0건이어야 합니다: ${consoleErrors.join(" | ")}`,
    ).toEqual([]);
  });

  test("/voice-range/auto — PERMISSION 단계 + 측정 시작 CTA + 콘솔 에러 0건", async ({
    page,
  }) => {
    const consoleErrors: string[] = [];
    page.on("console", (message) => {
      if (message.type() === "error") {
        consoleErrors.push(message.text());
      }
    });

    const response = await page.goto("/voice-range/auto");
    expect(
      response,
      "GET /voice-range/auto 응답이 존재해야 합니다.",
    ).not.toBeNull();
    expect(response!.status()).toBe(200);

    // 헤더 카피.
    await expect(page.getByText("자동 측정", { exact: true })).toBeVisible();
    await expect(
      page.getByRole("heading", {
        level: 1,
        name: "마이크로 음역대 측정하기",
      }),
    ).toBeVisible();

    // 마이크 권한 안내 카피 — privacy 시그널 ("audio 데이터는 서버로 업로드하지 않습니다").
    await expect(
      page.getByText(/audio 데이터는 서버로 업로드하지 않습니다/),
    ).toBeVisible();

    // PERMISSION 단계 primary CTA "측정 시작".
    await expect(
      page.getByRole("button", { name: "측정 시작" }),
    ).toBeVisible();

    expect(
      consoleErrors,
      `/voice-range/auto 로드 중 console error 0건이어야 합니다: ${consoleErrors.join(" | ")}`,
    ).toEqual([]);
  });
});
