/**
 * S8: 다크/라이트/시스템 모드 토글 플로우 smoke (이슈 #1397).
 *
 * spec: docs/features/web-e2e-playwright.md §5-3
 *
 * 목적:
 *  - `tokens-regression.spec.ts` (S6/S7) 는 localStorage seed 후 reload 한 정적 상태의
 *    토큰값만 검증한다 — 실제 사용자가 ThemeToggle 버튼을 눌렀을 때 런타임에
 *    `<html.dark>` 토글 + CSS 변수 swap + 색이 즉시 바뀌는 인터랙션은 검증하지 못한다.
 *  - 본 spec 은 ThemeToggle 버튼을 실제로 클릭해 system → light → dark → system
 *    3모드 순환(web/lib/theme.ts `toggleMode`)을 reload 없이 검증한다.
 *
 * 검증 (홈 `/` 페이지):
 *  1. 초기 (system, OS=light emulate): `<html>` 에 dark class 없음 + 라이트 토큰.
 *  2. 1회 클릭 → light: dark class 여전히 없음, data-theme-mode=light, localStorage=light.
 *  3. 2회 클릭 → dark: `<html class="dark">` 적용 + `<main>` 배경 / 버튼 색이 다크
 *     토큰값으로 실제 변경 + localStorage=dark.
 *  4. 3회 클릭 → system: OS=light emulate 이므로 dark class 다시 제거 (순환 복귀).
 *  5. 전 과정 console error 0건.
 *
 * 토큰값 (web/app/tokens.css SoT):
 *  - `--bg-subtle`     light #fafafa = rgb(250, 250, 250) / dark #18181b = rgb(24, 24, 27)
 *  - `--text-secondary` light #52525b = rgb(82, 82, 91) / dark #d4d4d8 = rgb(212, 212, 216)
 *    (ThemeToggle 버튼 text color)
 *
 * 결정성 메모:
 *  - `page.emulateMedia({ colorScheme: "light" })` 로 system 모드의 OS prefers 를 고정 →
 *    system 단계에서 dark class 가 항상 제거됨이 결정적.
 *  - Visual snapshot 비활성 (spec Q1): DOM/CSS `toHaveCSS` 직접 비교.
 */
import { expect, test } from "@playwright/test";

const BG_SUBTLE_LIGHT = "rgb(250, 250, 250)";
const BG_SUBTLE_DARK = "rgb(24, 24, 27)";
const TEXT_SECONDARY_LIGHT = "rgb(82, 82, 91)";
const TEXT_SECONDARY_DARK = "rgb(212, 212, 216)";

const DARK_CLASS_RE = /(^|\s)dark(\s|$)/;

test.describe("S8: 다크모드 토글 플로우 smoke", () => {
  test("ThemeToggle 클릭으로 system→light→dark→system 순환 + 색/클래스/영속 변경", async ({
    page,
  }) => {
    const consoleErrors: string[] = [];
    page.on("console", (message) => {
      if (message.type() === "error") {
        consoleErrors.push(message.text());
      }
    });

    // system 모드의 OS prefers 를 light 로 고정 → system 단계 결정성 확보.
    await page.emulateMedia({ colorScheme: "light" });

    const response = await page.goto("/");
    expect(response, "GET / 응답이 존재해야 합니다.").not.toBeNull();
    expect(response!.status()).toBe(200);

    // ThemeToggle 은 layout 우상단 floating 버튼 — `data-theme-mode` 속성이 유일 식별자.
    const toggle = page.locator("button[data-theme-mode]");
    const html = page.locator("html");
    const main = page.locator("main").first();

    // 클릭 후 마우스가 버튼 위에 남으면 `:hover` (text-primary) 가 적용돼 색 검증이
    // 오염된다. 매 클릭 후 마우스를 좌상단(버튼=우상단 fixed)으로 옮겨 hover 해제.
    const clickAndUnhover = async (): Promise<void> => {
      await toggle.click();
      await page.mouse.move(0, 0);
    };

    // 1) 초기 = system (SSR snapshot → hydrate 후 readStoredMode 가 system).
    //    OS=light emulate 이므로 dark class 없음 + 라이트 토큰.
    await expect(toggle).toHaveAttribute("data-theme-mode", "system");
    await expect(html).not.toHaveClass(DARK_CLASS_RE);
    await expect(main).toHaveCSS("background-color", BG_SUBTLE_LIGHT);
    await expect(toggle).toHaveCSS("color", TEXT_SECONDARY_LIGHT);

    // 2) 1회 클릭 → light. 명시 light 이므로 dark class 여전히 없음.
    await clickAndUnhover();
    await expect(toggle).toHaveAttribute("data-theme-mode", "light");
    await expect(html).not.toHaveClass(DARK_CLASS_RE);
    await expect(main).toHaveCSS("background-color", BG_SUBTLE_LIGHT);
    await expect
      .poll(() => page.evaluate(() => window.localStorage.getItem("mobruji-theme")))
      .toBe("light");

    // 3) 2회 클릭 → dark. `<html class="dark">` + 배경/버튼 색이 다크 토큰으로 실제 변경.
    await clickAndUnhover();
    await expect(toggle).toHaveAttribute("data-theme-mode", "dark");
    await expect(html).toHaveClass(DARK_CLASS_RE);
    await expect(main).toHaveCSS("background-color", BG_SUBTLE_DARK);
    await expect(toggle).toHaveCSS("color", TEXT_SECONDARY_DARK);
    await expect
      .poll(() => page.evaluate(() => window.localStorage.getItem("mobruji-theme")))
      .toBe("dark");

    // 4) 3회 클릭 → system. OS=light emulate → dark class 다시 제거 (순환 복귀).
    await clickAndUnhover();
    await expect(toggle).toHaveAttribute("data-theme-mode", "system");
    await expect(html).not.toHaveClass(DARK_CLASS_RE);
    await expect(main).toHaveCSS("background-color", BG_SUBTLE_LIGHT);
    await expect(toggle).toHaveCSS("color", TEXT_SECONDARY_LIGHT);
    await expect
      .poll(() => page.evaluate(() => window.localStorage.getItem("mobruji-theme")))
      .toBe("system");

    expect(
      consoleErrors,
      `다크모드 토글 플로우 중 console error 0건이어야 합니다: ${consoleErrors.join(" | ")}`,
    ).toEqual([]);
  });
});
