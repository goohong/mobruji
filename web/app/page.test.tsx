/**
 * 홈 페이지 테스트 (closes #275).
 *
 * 분기 시나리오:
 *  - 측정 안 함(voiceRangeId == null): NewUserPanel — 4단계 흐름 안내 + "음역대 측정하기"
 *    primary CTA + "직접 입력으로 시작" 보조.
 *  - 측정 함(voiceRangeId != null): ReturningUserPanel — "추천 받기" primary CTA +
 *    저장된 음역대 요약(API 응답 도착 시 노트명) + "음역대 다시 측정" 보조.
 *
 * 추가 검증:
 *  - SecondaryNav는 두 상태 모두에서 곡 검색/이력/좋아요/북마크 4개 링크 노출.
 *  - a11y violation 0 (axe).
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

import Home from "./page";
import { readVoiceRange } from "@/lib/api/voice-range";
import { useSessionStore } from "@/store/session";
import { expectNoA11yViolations } from "@/lib/test-helpers/a11y";

vi.mock("@/lib/api/voice-range", async () => {
  const actual =
    await vi.importActual<typeof import("@/lib/api/voice-range")>(
      "@/lib/api/voice-range",
    );
  return {
    ...actual,
    readVoiceRange: vi.fn(),
  };
});

const readVoiceRangeMock = vi.mocked(readVoiceRange);

function renderWithQueryClient(ui: ReactNode) {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });
  function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    );
  }
  return render(ui, { wrapper: Wrapper });
}

beforeEach(() => {
  readVoiceRangeMock.mockReset();
  // 기본은 측정 안 한 상태.
  useSessionStore.setState({
    sessionId: null,
    voiceRangeId: null,
    excludedSongIds: [],
  });
  if (typeof localStorage !== "undefined") {
    localStorage.removeItem("mobruji-session");
  }
});

afterEach(() => {
  cleanup();
});

describe("Home — 공통", () => {
  it("타이틀과 카피를 노출한다", async () => {
    renderWithQueryClient(<Home />);
    expect(
      screen.getByRole("heading", { name: /오늘 노래방, 뭐 부르지/ }),
    ).toBeInTheDocument();
    expect(
      screen.getByText(/내 음역대만 알려주면, 부르기 편한 곡을 추천/),
    ).toBeInTheDocument();
  });

  it("빠른 진입 nav에 검색/이력/좋아요/북마크 4개 링크가 노출된다", async () => {
    renderWithQueryClient(<Home />);
    const nav = screen.getByRole("navigation", { name: /빠른 진입/ });
    expect(nav).toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: /곡 검색/ }),
    ).toHaveAttribute("href", "/songs");
    expect(
      screen.getByRole("link", { name: /^이력$/ }),
    ).toHaveAttribute("href", "/history");
    expect(screen.getByRole("link", { name: /좋아요/ })).toHaveAttribute(
      "href",
      "/likes",
    );
    expect(screen.getByRole("link", { name: /북마크/ })).toHaveAttribute(
      "href",
      "/bookmarks",
    );
  });
});

describe("Home — 측정 안 한 사용자 (NewUserPanel)", () => {
  it("4단계 흐름을 ordered list로 보여준다", async () => {
    renderWithQueryClient(<Home />);
    const ol = screen.getByRole("list", { name: /이용 단계/ });
    const items = within(ol).getAllByRole("listitem");
    expect(items.length).toBe(4);
    expect(items[0]).toHaveTextContent(/음역대 측정/);
    expect(items[1]).toHaveTextContent(/분위기/);
    expect(items[2]).toHaveTextContent(/맞춤 추천/);
    expect(items[3]).toHaveTextContent(/좋아요/);
  });

  it("진입 분기 카드는 측정 방식 선택 화면으로, '직접 입력으로 시작'은 /voice-range", async () => {
    renderWithQueryClient(<Home />);
    // 3 페르소나 카드·둘러보기 모두 측정 방식 선택 화면(/voice-range/method)으로 연결
    // — 진입만으로 마이크 측정을 강제하지 않는다 (directive #1511).
    expect(
      screen.getByRole("link", { name: /내 목소리부터 알아보기/ }),
    ).toHaveAttribute("href", "/voice-range/method");
    expect(
      screen.getByRole("link", { name: /발성·고음 연습할 곡 찾기/ }),
    ).toHaveAttribute("href", "/voice-range/method");
    expect(
      screen.getByRole("link", { name: /분위기 띄울 곡 찾기/ }),
    ).toHaveAttribute("href", "/voice-range/method");
    // 보조 경로.
    expect(
      screen.getByRole("link", { name: /그냥 둘러보기/ }),
    ).toHaveAttribute("href", "/voice-range/method");
    expect(
      screen.getByRole("link", { name: /직접 입력으로 시작/ }),
    ).toHaveAttribute("href", "/voice-range");
  });

  it("측정 안 한 상태에서는 readVoiceRange를 호출하지 않는다", async () => {
    renderWithQueryClient(<Home />);
    // 마이크로태스크 한 사이클 대기 후에도 호출 없음.
    await waitFor(() => {
      expect(
        screen.getByRole("heading", { name: /무엇을 도와드릴까요/ }),
      ).toBeInTheDocument();
    });
    expect(readVoiceRangeMock).not.toHaveBeenCalled();
  });

  it("측정 안 한 상태 a11y 위반 없음", async () => {
    const { container } = renderWithQueryClient(<Home />);
    await waitFor(() => {
      expect(
        screen.getByRole("heading", { name: /무엇을 도와드릴까요/ }),
      ).toBeInTheDocument();
    });
    await expectNoA11yViolations(container);
  });
});

describe("Home — 측정 한 사용자 (ReturningUserPanel)", () => {
  beforeEach(() => {
    useSessionStore.setState({
      sessionId: "00000000-0000-4000-8000-000000000001",
      voiceRangeId: 77,
      excludedSongIds: [],
    });
  });

  it("primary CTA는 /recommend, 보조 CTA는 /voice-range/auto + /voice-range", async () => {
    readVoiceRangeMock.mockResolvedValue({
      id: 77,
      sessionId: "00000000-0000-4000-8000-000000000001",
      lowestNoteMidi: 48,
      highestNoteMidi: 69,
      sourceMethod: "OCTAVE_PICK",
      createdAt: "2026-05-22T00:00:00Z",
      updatedAt: "2026-05-22T00:00:00Z",
    });

    renderWithQueryClient(<Home />);

    await waitFor(() => {
      expect(
        screen.getByRole("heading", { name: /다시 오신 걸 환영해요/ }),
      ).toBeInTheDocument();
    });
    expect(
      screen.getByRole("link", { name: /추천 받기/ }),
    ).toHaveAttribute("href", "/recommend");
    expect(
      screen.getByRole("link", { name: /음역대 다시 측정/ }),
    ).toHaveAttribute("href", "/voice-range/auto");
    // 측정 완료 사용자도 수동 재설정 경로(/voice-range)에 진입할 수 있어야 한다.
    expect(
      screen.getByRole("link", { name: /직접 다시 설정/ }),
    ).toHaveAttribute("href", "/voice-range");
  });

  it("BE 응답이 도착하면 음역대를 음표명으로 노출한다 (도3 ~ 라4)", async () => {
    readVoiceRangeMock.mockResolvedValue({
      id: 77,
      sessionId: "00000000-0000-4000-8000-000000000001",
      lowestNoteMidi: 48, // 도3
      highestNoteMidi: 69, // 라4
      sourceMethod: "OCTAVE_PICK",
      createdAt: "2026-05-22T00:00:00Z",
      updatedAt: "2026-05-22T00:00:00Z",
    });

    renderWithQueryClient(<Home />);

    await waitFor(() => {
      // 한국어 단독 표기 (#1310 사용자 정정 2026-06-03 — SPN 병기 #318 폐지).
      expect(screen.getByLabelText(/저장된 음역대/)).toHaveTextContent(
        /도3 ~ 라4/,
      );
    });
    expect(readVoiceRangeMock).toHaveBeenCalledWith("00000000-0000-4000-8000-000000000001");
  });

  it("BE 호출이 실패하면 ID fallback을 노출한다", async () => {
    readVoiceRangeMock.mockRejectedValue(new Error("network down"));

    renderWithQueryClient(<Home />);

    await waitFor(() => {
      expect(screen.getByLabelText(/저장된 음역대 ID/)).toHaveTextContent(
        /#77/,
      );
    });
    // 추천 받기 CTA는 BE 실패와 무관하게 그대로 동작해야 한다.
    expect(
      screen.getByRole("link", { name: /추천 받기/ }),
    ).toHaveAttribute("href", "/recommend");
  });

  it("측정 한 상태 a11y 위반 없음", async () => {
    readVoiceRangeMock.mockResolvedValue({
      id: 77,
      sessionId: "00000000-0000-4000-8000-000000000001",
      lowestNoteMidi: 48,
      highestNoteMidi: 69,
      sourceMethod: "OCTAVE_PICK",
      createdAt: "2026-05-22T00:00:00Z",
      updatedAt: "2026-05-22T00:00:00Z",
    });

    const { container } = renderWithQueryClient(<Home />);
    await waitFor(() => {
      // 한국어 단독 표기 (#1310 사용자 정정 2026-06-03).
      expect(screen.getByLabelText(/저장된 음역대/)).toHaveTextContent(
        /도3 ~ 라4/,
      );
    });
    await expectNoA11yViolations(container);
  });
});

