/**
 * 음역대 입력 페이지 렌더 테스트.
 *
 * - next/navigation, @tanstack/react-query, zustand store, API 호출을 mock.
 * - 노트 옵션이 octaveRangeMidis() 범위(C2~C6)로 렌더되는지 확인.
 * - 기본값(최저음 C3, 최고음 A4) 표기 확인.
 */

import { describe, expect, it, vi } from "vitest";
import { render, screen, within } from "@testing-library/react";

import VoiceRangePage from "./page";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn() }),
}));

vi.mock("@tanstack/react-query", () => ({
  useMutation: () => ({
    mutate: vi.fn(),
    isPending: false,
    error: null,
  }),
}));

vi.mock("@/store/session", () => ({
  useSessionStore: (selector: (state: unknown) => unknown) =>
    selector({
      ensureSessionId: () => "test-session-id",
      setVoiceRangeId: () => undefined,
    }),
}));

vi.mock("@/lib/api/voice-range", async () => {
  const actual =
    await vi.importActual<typeof import("@/lib/api/voice-range")>(
      "@/lib/api/voice-range",
    );
  return {
    ...actual,
    createVoiceRange: vi.fn(),
  };
});

describe("VoiceRangePage", () => {
  it("페이지 헤더와 제출 버튼을 렌더한다", () => {
    render(<VoiceRangePage />);
    expect(
      screen.getByRole("heading", { name: /내 음역대를 알려주세요/ }),
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /추천 받기/ })).toBeEnabled();
  });

  it("최저음 / 최고음 select에 C2~C6 옵션이 모두 렌더된다", () => {
    render(<VoiceRangePage />);
    const [lowSelect, highSelect] = screen.getAllByRole("combobox");

    // C2(MIDI 36) ~ C6(MIDI 84) = 49개
    const lowOptions = within(lowSelect).getAllByRole("option");
    const highOptions = within(highSelect).getAllByRole("option");
    expect(lowOptions.length).toBe(49);
    expect(highOptions.length).toBe(49);

    // 첫 옵션은 C2, 마지막은 C6 형식 확인
    expect(lowOptions[0]).toHaveTextContent(/C2 \(MIDI 36\)/);
    expect(lowOptions[lowOptions.length - 1]).toHaveTextContent(
      /C6 \(MIDI 84\)/,
    );
  });

  it("기본값으로 최저음 C3(MIDI 48), 최고음 A4(MIDI 69)가 선택된다", () => {
    render(<VoiceRangePage />);
    const [lowSelect, highSelect] = screen.getAllByRole(
      "combobox",
    ) as HTMLSelectElement[];
    expect(lowSelect.value).toBe("48");
    expect(highSelect.value).toBe("69");
  });
});
