/**
 * S4: 곡 검색 페이지 smoke.
 *
 * spec: docs/features/web-e2e-playwright.md §5-3 시나리오 표 #S4
 *
 * 검증:
 *  1. `/songs` 페이지 HTTP 200 응답
 *  2. 헤더 "곡 검색" + Browse caption 가 렌더
 *  3. 검색 입력 placeholder ("곡 제목이나 아티스트로 검색") 가 노출
 *  4. 검색어 미입력 시 안내 ("검색어를 입력해 보세요.") 가 렌더 — BE 호출 0건
 *  5. 검색어 입력 + mock 응답 후 곡 카드 카운트 텍스트 ("1곡") 가 노출
 *  6. 페이지 로드 중 console error 0건
 *
 * mock 전략 (spec §7, Q4 결정 (b)):
 *  - `/api/v1/songs?keyword=…` 만 Playwright 네이티브 `page.route()` 로 mock.
 *  - 빈 검색어일 때는 page 가 호출 자체를 생략하므로 mock 가 fire 되지 않음.
 *
 * 비고:
 *  - SongSearchPageInner 는 Suspense + useSearchParams 사용 → 첫 마운트 시
 *    fallback (h1 = "곡 검색") 이 잠깐 보이고 inner 가 즉시 hydrate. 본 smoke 는
 *    fallback 과 inner 가 같은 h1 카피를 사용하므로 race 없이 단일 selector 검증 가능.
 */
import { expect, test } from "@playwright/test";

const API_BASE = "http://localhost:8080";

test.describe("S4: 곡 검색 페이지 smoke", () => {
  test("페이지 로드 + 검색 입력 + 빈 키워드 안내 + 콘솔 에러 0건", async ({
    page,
  }) => {
    const consoleErrors: string[] = [];
    page.on("console", (message) => {
      if (message.type() === "error") {
        consoleErrors.push(message.text());
      }
    });

    const response = await page.goto("/songs");
    expect(response, "GET /songs 응답이 존재해야 합니다.").not.toBeNull();
    expect(response!.status(), "GET /songs status 가 200 이어야 합니다.").toBe(
      200,
    );

    // 헤더 카피 — Browse caption + h1 "곡 검색". h1 은 fallback / inner 둘 다
    // 같은 텍스트라 hydration race 없이 검증 가능.
    await expect(
      page.getByRole("heading", { level: 1, name: "곡 검색" }),
    ).toBeVisible();

    // 검색 입력 placeholder. 라벨이 숨겨져 있어 placeholder selector 가 가장 안정.
    await expect(
      page.getByPlaceholder("곡 제목이나 아티스트로 검색"),
    ).toBeVisible();

    // 검색어 미입력 안내 — BE 호출 0 상태에서 가시화.
    await expect(page.getByText("검색어를 입력해 보세요.")).toBeVisible();

    expect(
      consoleErrors,
      `/songs 로드 중 console error 0건이어야 합니다: ${consoleErrors.join(" | ")}`,
    ).toEqual([]);
  });

  test("검색어 입력 + mock 응답 후 곡 카운트 표시", async ({ page }) => {
    const consoleErrors: string[] = [];
    page.on("console", (message) => {
      if (message.type() === "error") {
        consoleErrors.push(message.text());
      }
    });

    // /api/v1/songs?keyword=... 를 mock. searchSongs 가 keyword trim 후
    // encodeURIComponent → URL 패턴은 prefix glob 으로 매칭.
    await page.route(
      (url) =>
        url.origin === API_BASE && url.pathname === "/api/v1/songs",
      async (route) => {
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify([
            {
              id: 9001,
              title: "테스트 곡",
              artist: "테스트 아티스트",
              releaseYear: 2024,
              keyOriginal: "C_MAJOR",
              bpm: 120,
              mood: "UPBEAT",
              language: "KOR",
              genre: "POP",
              tjNumber: "12345",
              kyNumber: null,
              metadataSource: "MANUAL_SEED",
              lowMidi: 60,
              highMidi: 72,
              difficulty: "EASY",
              albumCoverUrl: null,
            },
          ]),
        });
      },
    );

    await page.goto("/songs");

    // 검색 input fill — 300ms 디바운스 후 BE 호출.
    await page
      .getByPlaceholder("곡 제목이나 아티스트로 검색")
      .fill("테스트");

    // 결과 카운트 텍스트 = `1곡` (필터 비활성, rawCount=1). expect.toHaveText 가
    // 디바운스 + react-query 응답을 자동 대기.
    await expect(page.getByText("1곡", { exact: true })).toBeVisible();

    // 결과 리스트 (aria-label="검색 결과") 가 렌더.
    await expect(
      page.getByRole("list", { name: "검색 결과" }),
    ).toBeVisible();

    expect(
      consoleErrors,
      `/songs 검색 후 console error 0건이어야 합니다: ${consoleErrors.join(" | ")}`,
    ).toEqual([]);
  });
});
