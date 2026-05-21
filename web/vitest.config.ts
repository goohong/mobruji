/**
 * Vitest 설정.
 *
 * - happy-dom: React 컴포넌트 단위 테스트용 가벼운 DOM.
 * - @vitejs/plugin-react: JSX/TSX 변환 (Next.js 자체 빌드는 사용하지 않음).
 * - alias `@/*` → 프로젝트 루트(web). tsconfig.json paths와 일치.
 */

import path from "node:path";
import { fileURLToPath } from "node:url";

import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

const dirname = path.dirname(fileURLToPath(import.meta.url));

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      "@": dirname,
    },
  },
  test: {
    environment: "happy-dom",
    globals: false,
    setupFiles: ["./vitest.setup.ts"],
    include: ["**/*.test.{ts,tsx}"],
    exclude: ["node_modules/**", ".next/**", "out/**", "build/**"],
  },
});
