import { defineConfig, globalIgnores } from "eslint/config";
import nextVitals from "eslint-config-next/core-web-vitals";
import nextTs from "eslint-config-next/typescript";

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
  // Override default ignores of eslint-config-next.
  globalIgnores([
    // Default ignores of eslint-config-next:
    ".next/**",
    "out/**",
    "build/**",
    "next-env.d.ts",
  ]),
]);

export default eslintConfig;
