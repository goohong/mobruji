/**
 * 음역대 입력 페이지 테스트.
 *
 * 보강 범위 (closes #60, #65):
 *  - 기존 렌더/옵션/기본값 검증은 유지.
 *  - user-event를 사용한 제출 흐름:
 *      1) 폼 작성 → submit → createVoiceRange mock 호출 검증.
 *      2) onSuccess 시 setVoiceRangeId 호출 + router.push("/recommend") 검증.
 *      3) API 에러 mock → 에러 UI 노출 검증.
 *  - zustand store mock은 `web/lib/test-helpers/mock-session-store.ts` 공통 헬퍼를 사용한다.
 *  - react-query는 실제 QueryClient를 띄워 mutation 흐름을 그대로 검증한다.
 */

import { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  cleanup,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import VoiceRangePage from "./page";
import { ApiError } from "@/lib/api/client";
import { createVoiceRange } from "@/lib/api/voice-range";
import { expectNoA11yViolations } from "@/lib/test-helpers/a11y";

// vi.hoisted: vi.mock factory가 hoisting되므로 mock이 참조하는 식별자도 hoisting되어야 한다.
// async hoisted + dynamic import로 vite alias("@/...")를 그대로 사용한다.
const { sessionMock, pushMock } = await vi.hoisted(async () => {
  const helper = await import("@/lib/test-helpers/mock-session-store");
  return {
    sessionMock: helper.buildSessionStoreMock(),
    pushMock: vi.fn(),
  };
});

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock }),
}));

vi.mock("@/store/session", () => ({
  useSessionStore: sessionMock.useSessionStore,
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

const createVoiceRangeMock = vi.mocked(createVoiceRange);

/**
 * 테스트마다 새 QueryClient를 만들어 mutation 상태가 격리되도록 한다.
 * 재시도는 false로 두어 에러 케이스가 즉시 반영되게 한다.
 */
function renderWithQueryClient(ui: ReactNode) {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });
  return render(<QueryClientProvider client={client}>{ui}</QueryClientProvider>);
}

beforeEach(() => {
  sessionMock.reset();
  pushMock.mockReset();
  createVoiceRangeMock.mockReset();
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe("VoiceRangePage 렌더", () => {
  it("페이지 헤더와 제출 버튼을 렌더한다", () => {
    renderWithQueryClient(<VoiceRangePage />);
    expect(
      screen.getByRole("heading", { name: /내 음역대를 알려주세요/ }),
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /추천 받기/ })).toBeEnabled();
  });

  it("최저음 / 최고음 select에 C2~C6 옵션이 모두 렌더된다", () => {
    renderWithQueryClient(<VoiceRangePage />);
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
    renderWithQueryClient(<VoiceRangePage />);
    const [lowSelect, highSelect] = screen.getAllByRole(
      "combobox",
    ) as HTMLSelectElement[];
    expect(lowSelect.value).toBe("48");
    expect(highSelect.value).toBe("69");
  });
});

describe("VoiceRangePage 제출 흐름", () => {
  it("성공 시 createVoiceRange 호출 → setVoiceRangeId(id) → /recommend로 라우팅한다", async () => {
    const user = userEvent.setup();
    createVoiceRangeMock.mockResolvedValueOnce({
      id: 77,
      sessionId: "test-session-id",
      lowestNoteMidi: 50,
      highestNoteMidi: 65,
      sourceMethod: "OCTAVE_PICK",
      createdAt: "2026-05-21T00:00:00Z",
      updatedAt: "2026-05-21T00:00:00Z",
    });

    renderWithQueryClient(<VoiceRangePage />);
    const [lowSelect, highSelect] = screen.getAllByRole(
      "combobox",
    ) as HTMLSelectElement[];

    // 최저음 D3(MIDI 50), 최고음 F4(MIDI 65)로 변경.
    await user.selectOptions(lowSelect, "50");
    await user.selectOptions(highSelect, "65");

    await user.click(screen.getByRole("button", { name: /추천 받기/ }));

    await waitFor(() => {
      expect(createVoiceRangeMock).toHaveBeenCalledTimes(1);
    });
    expect(createVoiceRangeMock).toHaveBeenCalledWith({
      sessionId: "test-session-id",
      lowestNoteMidi: 50,
      highestNoteMidi: 65,
      sourceMethod: "OCTAVE_PICK",
    });

    await waitFor(() => {
      expect(sessionMock.state().setVoiceRangeId).toHaveBeenCalledWith(77);
    });
    expect(pushMock).toHaveBeenCalledWith("/recommend");
  });

  it("API가 실패하면 에러 메시지를 노출하고 라우팅하지 않는다", async () => {
    const user = userEvent.setup();
    createVoiceRangeMock.mockRejectedValueOnce(
      new ApiError(500, "internal server boom", {
        message: "internal server boom",
      }),
    );

    renderWithQueryClient(<VoiceRangePage />);

    await user.click(screen.getByRole("button", { name: /추천 받기/ }));

    await waitFor(() => {
      expect(screen.getByText(/저장에 실패했습니다/)).toBeInTheDocument();
    });
    expect(screen.getByText(/500: internal server boom/)).toBeInTheDocument();
    expect(pushMock).not.toHaveBeenCalled();
    expect(sessionMock.state().setVoiceRangeId).not.toHaveBeenCalled();
  });

  it("최저음이 최고음보다 높으면 validation 메시지를 보여주고 제출하지 않는다", async () => {
    const user = userEvent.setup();
    renderWithQueryClient(<VoiceRangePage />);
    const [lowSelect, highSelect] = screen.getAllByRole(
      "combobox",
    ) as HTMLSelectElement[];

    await user.selectOptions(highSelect, "40");
    await user.selectOptions(lowSelect, "60");

    expect(
      screen.getByText(/최저음은 최고음보다 같거나 낮아야 합니다/),
    ).toBeInTheDocument();

    // 버튼이 disabled여서 click 자체가 mutation을 트리거하지 않는다.
    const submit = screen.getByRole("button", { name: /추천 받기/ });
    expect(submit).toBeDisabled();
    await user.click(submit);
    expect(createVoiceRangeMock).not.toHaveBeenCalled();
  });
});

// closes #107 — 음역대 입력 페이지는 49개 옵션 select 2개 + 폼 라벨 + submit 버튼이
// 핵심 a11y 위험 영역. 정상 상태와 validation 에러 상태 둘 다 검사한다.
describe("VoiceRangePage a11y", () => {
  it("초기 렌더 상태에 a11y 위반이 없다", async () => {
    const { container } = renderWithQueryClient(<VoiceRangePage />);
    await expectNoA11yViolations(container);
  });

  it("validation 에러 메시지 노출 상태에도 a11y 위반이 없다", async () => {
    const user = userEvent.setup();
    const { container } = renderWithQueryClient(<VoiceRangePage />);
    const [lowSelect, highSelect] = screen.getAllByRole(
      "combobox",
    ) as HTMLSelectElement[];

    await user.selectOptions(highSelect, "40");
    await user.selectOptions(lowSelect, "60");

    expect(
      screen.getByText(/최저음은 최고음보다 같거나 낮아야 합니다/),
    ).toBeInTheDocument();

    await expectNoA11yViolations(container);
  });
});
