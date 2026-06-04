/**
 * Vitest 전역 셋업. @testing-library/jest-dom matcher 등록.
 *
 * next/font mock: layout.tsx 가 Geist/Geist_Mono (google) + Pretendard
 * (local) 로더를 모듈 최상단에서 호출하므로 layout 을 import 만 해도 로더가
 * 실행된다. SSR/Next 번들러(woff2 파일 파싱 포함) 없는 환경에선 그대로 실패하므로
 * 변수 객체를 돌려주는 stub 으로 치환한다.
 */

import { vi } from "vitest";
import "@testing-library/jest-dom/vitest";

vi.mock("next/font/google", () => ({
  Geist: () => ({ variable: "--font-geist-sans" }),
  Geist_Mono: () => ({ variable: "--font-geist-mono" }),
}));

vi.mock("next/font/local", () => ({
  default: () => ({ variable: "--font-pretendard" }),
}));
