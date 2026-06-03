import { defineConfig, globalIgnores } from "eslint/config";
import nextVitals from "eslint-config-next/core-web-vitals";
import nextTs from "eslint-config-next/typescript";

import zustandPersist from "./eslint-rules/zustand-persist.mjs";

/**
 * PR #129 — fe 로그 정책:
 *   - `console.*` 직접 호출 금지. `@/lib/logging` 의 `safeLog.*` 만 사용한다.
 *   - 본 모듈(`lib/logging.ts`)은 라인 단위 `eslint-disable-next-line no-console`
 *     주석으로만 console 을 호출한다.
 * `no-console` 을 `error` 로 둬서 빌드/CI 가 자동 차단.
 */
const eslintConfig = defineConfig([
  ...nextVitals,
  ...nextTs,
  {
    rules: {
      "no-console": "error",
    },
  },
  /**
   * zustand persist 옵션 가드 (#1108, 회귀 #1105 root cause hook).
   *   - version 누락 = error (localStorage 스키마 회귀 차단)
   *   - migrate 누락 = warn (version bump 시 변환 hook 권장)
   * persist 미들웨어는 store/ 에만 등장하므로 해당 디렉토리로 범위 제한.
   */
  {
    files: ["store/**/*.{ts,tsx}"],
    plugins: { "zustand-persist": zustandPersist },
    rules: {
      "zustand-persist/persist-version-required": "error",
      "zustand-persist/persist-migrate-recommended": "warn",
    },
  },
  // Override default ignores of eslint-config-next.
  globalIgnores([
    // Default ignores of eslint-config-next:
    ".next/**",
    "out/**",
    "build/**",
    "next-env.d.ts",
    // Playwright e2e (docs/features/web-e2e-playwright.md) — Vitest 단위 테스트와 룰 분리.
    // e2e 디렉토리는 `@playwright/test` runner 컨벤션을 따르므로 next eslint 룰 (no-console 등) 적용 제외.
    "e2e/**",
    "playwright.config.ts",
    "playwright-report/**",
    "test-results/**",
  ]),
]);

export default eslintConfig;
