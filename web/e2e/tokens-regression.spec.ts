/**
 * S6/S7: 다크 / 라이트 모드 design tokens 회귀 가드.
 *
 * spec: docs/features/web-e2e-playwright.md §5-3 시나리오 표 #S6 #S7
 *
 * 목적:
 *  - ADR-0018 단계 4 마이그레이션 PR 다수가 page hardcode (`bg-zinc-50` / `text-3xl` /
 *    `rounded-2xl`) 를 토큰 (`bg-[var(--bg-subtle)]` / `text-[var(--text-primary)]` /
 *    `rounded-[var(--radius-lg)]`) 으로 swap 한다. `tokens.test.ts` 단위 테스트는 변수
 *    값/명칭만 검증하므로 실 page 의 body 적용을 검증하지 못한다.
 *  - 본 spec 은 홈 페이지 `<main>` 의 background-color + 헤더 h1 의 color 를
 *    light/dark 양쪽에서 실측해 token swap 회귀를 detect.
 *
 * 검증 대상 (홈 `/` 페이지):
 *  - `<main>` background-color :
 *    - light : `--bg-subtle` = `#fafafa` (zinc-50) = `rgb(250, 250, 250)`
 *    - dark  : `--bg-subtle` = `#18181b` (zinc-900) = `rgb(24, 24, 27)`
 *  - 페이지 h1 ("오늘 노래방, 뭐 부르지?") color :
 *    - light : `--text-primary` = `#18181b` (zinc-900) = `rgb(24, 24, 27)`
 *    - dark  : `--text-primary` = `#fafafa` (zinc-50)  = `rgb(250, 250, 250)`
 *
 * 모드 토글 (web/lib/theme.ts SoT):
 *  - `<html>` 의 `dark` class 가 진짜 source — `localStorage.mobruji-theme` 가 `"dark"`
 *    면 layout `<head>` 의 `THEME_INIT_SCRIPT` (hydration 직전) 가 class 를 토글.
 *  - 본 spec 은 `addInitScript` 로 localStorage seed 만 심으면 hydration 직전 모드가
 *    결정. `page.emulateMedia` 보다 결정적.
 *  - `system` 모드는 OS prefers 의존이라 e2e 결정성 떨어짐 → "light" / "dark" 명시 seed.
 *
 * Visual snapshot 비활성 (spec Q1 결정 (b)): DOM/CSS `toHaveCSS` 직접 비교.
 * device/font 렌더링 차이로 인한 flaky 회피.
 */
import { expect, test } from "@playwright/test";

type Mode = "light" | "dark";

const TOKEN_VALUES: Record<
  Mode,
  { bgSubtle: string; textPrimary: string }
> = {
  light: {
    // `--bg-subtle` (#fafafa) / `--text-primary` (#18181b)
    bgSubtle: "rgb(250, 250, 250)",
    textPrimary: "rgb(24, 24, 27)",
  },
  dark: {
    // `--bg-subtle` (#18181b) / `--text-primary` (#fafafa)
    bgSubtle: "rgb(24, 24, 27)",
    textPrimary: "rgb(250, 250, 250)",
  },
};

async function seedTheme(
  page: import("@playwright/test").Page,
  mode: Mode,
): Promise<void> {
  // localStorage.mobruji-theme seed + matchMedia mock — system 자동 추종 시점도 결정적.
  // `THEME_INIT_SCRIPT` (layout head) 는 hydration 전 동작하므로, init script 가
  // 첫 페이지 navigation 직전에 적용되어야 한다.
  await page.addInitScript((seedMode) => {
    try {
      window.localStorage.setItem("mobruji-theme", seedMode);
    } catch {
      // privacy mode — graceful no-op.
    }
  }, mode);
  await page.emulateMedia({ colorScheme: mode });
}

for (const mode of ["light", "dark"] as const) {
  test.describe(`${mode === "dark" ? "S6" : "S7"}: ${mode} 모드 토큰 회귀 가드`, () => {
    test(`홈 페이지 <main> 배경 + h1 색상이 ${mode} 토큰값과 일치`, async ({
      page,
    }) => {
      await seedTheme(page, mode);

      const response = await page.goto("/");
      expect(response, "GET / 응답이 존재해야 합니다.").not.toBeNull();
      expect(response!.status()).toBe(200);

      // `<html class="dark">` 토글이 init script 로 hydration 직전 적용됐는지 검증.
      // light 모드라면 dark class 가 없어야 함.
      if (mode === "dark") {
        await expect(page.locator("html")).toHaveClass(/(^|\s)dark(\s|$)/);
      } else {
        await expect(page.locator("html")).not.toHaveClass(/(^|\s)dark(\s|$)/);
      }

      // <main> background-color — page.tsx `bg-[var(--bg-subtle)]` 적용 element.
      // 홈 페이지에 <main> 은 1개라 role/locator 단순.
      const main = page.locator("main").first();
      await expect(main).toHaveCSS(
        "background-color",
        TOKEN_VALUES[mode].bgSubtle,
      );

      // h1 color — `text-[var(--text-primary)]` 적용 element.
      const heading = page.getByRole("heading", {
        level: 1,
        name: "오늘 노래방, 뭐 부르지?",
      });
      await expect(heading).toHaveCSS("color", TOKEN_VALUES[mode].textPrimary);
    });
  });
}
