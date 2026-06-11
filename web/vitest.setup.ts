/**
 * Vitest 전역 셋업. @testing-library/jest-dom matcher 등록.
 *
 * next/font/google mock: layout.tsx 가 Geist/Geist_Mono 를 호출하므로 layout
 * 메타데이터를 import 만 해도 폰트 로더가 실행된다. SSR/Next 번들러 없는
 * 환경에선 그대로 실패하므로 변수 객체를 돌려주는 stub 으로 치환.
 */

import { vi } from "vitest";
import "@testing-library/jest-dom/vitest";

vi.mock("next/font/google", () => ({
  Geist: () => ({ variable: "--font-geist-sans" }),
  Geist_Mono: () => ({ variable: "--font-geist-mono" }),
}));
