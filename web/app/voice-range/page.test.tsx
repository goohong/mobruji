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
  act,
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

/**
 * cache prime 검증용 — 호출자가 client 인스턴스를 직접 들고 setQueryData 가
 * 일어났는지 확인할 수 있게 한다.
 */
function renderWithExposedQueryClient(ui: ReactNode) {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });
  const rendered = render(
    <QueryClientProvider client={client}>{ui}</QueryClientProvider>,
  );
  return { ...rendered, client };
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

  // closes #166 — PR D: "자동 측정" CTA 카드를 페이지 상단에 노출하고
  // /voice-range/auto 라우팅을 제공한다.
  it("자동 측정 CTA 카드가 페이지 상단에 노출된다", () => {
    renderWithQueryClient(<VoiceRangePage />);
    expect(
      screen.getByRole("heading", { name: /마이크로 자동 측정/ }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: /자동으로 측정하기/ }),
    ).toBeInTheDocument();
  });

  it("자동 측정 CTA가 /voice-range/auto로 라우팅된다", () => {
    renderWithQueryClient(<VoiceRangePage />);
    const cta = screen.getByRole("link", { name: /자동으로 측정하기/ });
    expect(cta).toHaveAttribute("href", "/voice-range/auto");
  });

  it("직접 선택 섹션이 폼 위에 헤딩으로 분리된다", () => {
    renderWithQueryClient(<VoiceRangePage />);
    expect(
      screen.getByRole("heading", { name: /직접 선택/ }),
    ).toBeInTheDocument();
  });

  it("최저음 / 최고음 select에 C2~C6 옵션이 모두 렌더된다", () => {
    renderWithQueryClient(<VoiceRangePage />);
    const [lowSelect, highSelect] = screen.getAllByRole("combobox");

    // C2(MIDI 36) ~ C6(MIDI 84) = 49개
    const lowOptions = within(lowSelect).getAllByRole("option");
    const highOptions = within(highSelect).getAllByRole("option");
    expect(lowOptions.length).toBe(49);
    expect(highOptions.length).toBe(49);

    // 첫 옵션은 C2, 마지막은 C6 형식 확인 (#318: 한국어 (SPN) · MIDI N)
    expect(lowOptions[0]).toHaveTextContent(/도2 \(C2\) · MIDI 36/);
    expect(lowOptions[lowOptions.length - 1]).toHaveTextContent(
      /도6 \(C6\) · MIDI 84/,
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
      sessionId: "00000000-0000-4000-8000-000000000001",
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
      sessionId: "00000000-0000-4000-8000-000000000001",
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

  // (closes #282) /recommend 진입 시 voice-range GET 왕복을 제거하기 위해
  // mutation onSuccess 가 react-query 캐시에 응답을 prime 한다. 같은 sessionId
  // 키로 useQuery 가 즉시 캐시 히트하는지 검증한다.
  it("성공 시 응답을 react-query 캐시에 prime 한다 (#282)", async () => {
    const user = userEvent.setup();
    const response = {
      id: 88,
      sessionId: "00000000-0000-4000-8000-000000000001",
      lowestNoteMidi: 50,
      highestNoteMidi: 65,
      sourceMethod: "OCTAVE_PICK" as const,
      createdAt: "2026-05-22T00:00:00Z",
      updatedAt: "2026-05-22T00:00:00Z",
    };
    createVoiceRangeMock.mockResolvedValueOnce(response);

    const { client } = renderWithExposedQueryClient(<VoiceRangePage />);
    const [lowSelect, highSelect] = screen.getAllByRole(
      "combobox",
    ) as HTMLSelectElement[];
    await user.selectOptions(lowSelect, "50");
    await user.selectOptions(highSelect, "65");
    await user.click(screen.getByRole("button", { name: /추천 받기/ }));

    await waitFor(() => {
      expect(pushMock).toHaveBeenCalledWith("/recommend");
    });

    expect(client.getQueryData(["voice-range", "00000000-0000-4000-8000-000000000001"])).toEqual(
      response,
    );
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

// PR #985 (`useFeedbackToggleMutation` race/unmount/401 가드) 패턴을
// /voice-range 페이지의 raw `useMutation` 블록에도 확장한다.
//
// 검증 범위 (closes #1015 후속):
//   1) 빠른 연속 클릭 race condition
//      - submit 버튼은 mutation.isPending 동안 `loading=true` 로 disabled.
//      - 사용자가 0.1초 안에 두 번째 클릭해도 createVoiceRange 는 1회만 호출.
//      - Button 의 `loading={mutation.isPending}` 회귀 가드.
//
//   2) Button isPending 동안 UI reflect (loading / aria-busy / label 변경)
//      - mutation pending 중: aria-busy="true" + disabled + "저장 중..." 라벨.
//      - mutation 해결 직전까지 상태가 유지되는지 검증.
//      - 위 prop 중 하나라도 회귀로 빠지면 사용자가 더블 submit 가능.
//
//   3) unmount 직후 응답 도착 — React state update warning 없이 종료
//      - mutationFn pending 도중 unmount() → resolve 시 console.error 0건.
//      - React Query 가 unmounted observer 의 setState 를 무시하는지 회귀 확인.
//
// 비범위 (의도적으로 검증 안 함):
//   - voice-range 페이지 본체 / 인터페이스 / 시그니처 변경 없음 (테스트만 추가)
//   - `/voice-range/auto` 페이지는 별도 테스트 책임 (마이크 의존 흐름)
//   - createVoiceRange API 자체 동작은 별도 (api/voice-range.test.ts)
describe("VoiceRangePage mutation 경계 가드 (race/unmount/Button reflect)", () => {
  /**
   * mutationFn 이 resolve 되기 전까지 안에 머무를 수 있는 deferred 도우미.
   * race / unmount 케이스에서 "응답 도착 시점" 을 우리가 직접 통제한다.
   */
  function createDeferred<T>(): {
    promise: Promise<T>;
    resolve: (value: T) => void;
    reject: (reason: unknown) => void;
  } {
    let resolve!: (value: T) => void;
    let reject!: (reason: unknown) => void;
    const promise = new Promise<T>((res, rej) => {
      resolve = res;
      reject = rej;
    });
    return { promise, resolve, reject };
  }

  it("빠른 연속 클릭 시 createVoiceRange 는 1회만 호출된다 (Button loading 가드)", async () => {
    const user = userEvent.setup();
    const deferred = createDeferred<{
      id: number;
      sessionId: string;
      lowestNoteMidi: number;
      highestNoteMidi: number;
      sourceMethod: "OCTAVE_PICK";
      createdAt: string;
      updatedAt: string;
    }>();
    createVoiceRangeMock.mockReturnValueOnce(deferred.promise);

    renderWithQueryClient(<VoiceRangePage />);

    const submit = screen.getByRole("button", { name: /추천 받기/ });
    await user.click(submit);

    // 첫 호출 후 isPending=true 가 Button 에 반영되어 disabled.
    await waitFor(() => {
      expect(createVoiceRangeMock).toHaveBeenCalledTimes(1);
    });
    await waitFor(() => {
      expect(submit).toBeDisabled();
    });

    // 같은 turn 안에 사용자가 한 번 더 클릭 — Button disabled 로 차단되어야 한다.
    await user.click(submit);
    expect(createVoiceRangeMock).toHaveBeenCalledTimes(1);

    // 응답을 도착시켜 cleanup (router.push 가 await 안에서 호출돼야 act warning 안 뜸).
    await act(async () => {
      deferred.resolve({
        id: 1,
        sessionId: "00000000-0000-4000-8000-000000000001",
        lowestNoteMidi: 48,
        highestNoteMidi: 69,
        sourceMethod: "OCTAVE_PICK",
        createdAt: "2026-05-24T00:00:00Z",
        updatedAt: "2026-05-24T00:00:00Z",
      });
      await deferred.promise;
    });
  });

  it("mutation pending 동안 Button 이 aria-busy + disabled + '저장 중...' 라벨로 reflect 된다", async () => {
    const user = userEvent.setup();
    const deferred = createDeferred<{
      id: number;
      sessionId: string;
      lowestNoteMidi: number;
      highestNoteMidi: number;
      sourceMethod: "OCTAVE_PICK";
      createdAt: string;
      updatedAt: string;
    }>();
    createVoiceRangeMock.mockReturnValueOnce(deferred.promise);

    renderWithQueryClient(<VoiceRangePage />);

    const submit = screen.getByRole("button", { name: /추천 받기/ });
    // 클릭 전: enabled, aria-busy 없음, "추천 받기" 라벨.
    expect(submit).toBeEnabled();
    expect(submit).not.toHaveAttribute("aria-busy", "true");
    expect(submit).toHaveTextContent(/추천 받기/);

    await user.click(submit);

    // 클릭 후 mutation pending — 3 항목 동시 reflect.
    await waitFor(() => {
      expect(submit).toBeDisabled();
    });
    expect(submit).toHaveAttribute("aria-busy", "true");
    expect(submit).toHaveTextContent(/저장 중\.\.\./);

    // 응답 도착 → cleanup.
    await act(async () => {
      deferred.resolve({
        id: 2,
        sessionId: "00000000-0000-4000-8000-000000000001",
        lowestNoteMidi: 48,
        highestNoteMidi: 69,
        sourceMethod: "OCTAVE_PICK",
        createdAt: "2026-05-24T00:00:00Z",
        updatedAt: "2026-05-24T00:00:00Z",
      });
      await deferred.promise;
    });
  });

  it("unmount 직후 응답이 도착해도 React state update warning 없이 종료된다", async () => {
    const user = userEvent.setup();
    const deferred = createDeferred<{
      id: number;
      sessionId: string;
      lowestNoteMidi: number;
      highestNoteMidi: number;
      sourceMethod: "OCTAVE_PICK";
      createdAt: string;
      updatedAt: string;
    }>();
    createVoiceRangeMock.mockReturnValueOnce(deferred.promise);

    const consoleErrorSpy = vi
      .spyOn(console, "error")
      .mockImplementation(() => {});

    const { unmount } = renderWithQueryClient(<VoiceRangePage />);

    const submit = screen.getByRole("button", { name: /추천 받기/ });
    await user.click(submit);

    await waitFor(() => {
      expect(createVoiceRangeMock).toHaveBeenCalledTimes(1);
    });

    // mutation pending 중 컴포넌트 unmount.
    unmount();

    // 응답 도착 — unmounted observer 의 setState 가 무시되어야 한다.
    await act(async () => {
      deferred.resolve({
        id: 3,
        sessionId: "00000000-0000-4000-8000-000000000001",
        lowestNoteMidi: 48,
        highestNoteMidi: 69,
        sourceMethod: "OCTAVE_PICK",
        createdAt: "2026-05-24T00:00:00Z",
        updatedAt: "2026-05-24T00:00:00Z",
      });
      await deferred.promise;
    });

    // React 가 "Can't perform a React state update on an unmounted component"
    // warning 을 띄우면 console.error 로 빠진다. 본 가드가 깨지면 unmount race.
    const stateUpdateWarnings = consoleErrorSpy.mock.calls.filter((call) => {
      const first = call[0];
      return (
        typeof first === "string" &&
        first.includes("unmounted") &&
        first.includes("state update")
      );
    });
    expect(stateUpdateWarnings).toEqual([]);

    // pushMock / setVoiceRangeId 가 호출됐는지 자체는 본 케이스 범위 아님 —
    // React Query 내부 구현에 따라 onSuccess 가 unmount 후에도 호출될 수 있다.
    // 본 가드 핵심은 "warning 0건" 이다.

    consoleErrorSpy.mockRestore();
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

  // closes #464 — validation 에러 시 SR announce 보장.
  // role="alert" 가 부여되어 메시지가 등장하는 즉시 SR 이 읽고,
  // select 둘 다 aria-invalid="true" + aria-describedby 로 연결되어
  // 어떤 필드가 어떤 이유로 잘못됐는지 SR 사용자도 인지한다.
  it("validation 에러 시 role=alert 메시지가 노출된다 (#464)", async () => {
    const user = userEvent.setup();
    renderWithQueryClient(<VoiceRangePage />);
    const [lowSelect, highSelect] = screen.getAllByRole(
      "combobox",
    ) as HTMLSelectElement[];

    await user.selectOptions(highSelect, "40");
    await user.selectOptions(lowSelect, "60");

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent(/최저음은 최고음보다 같거나 낮아야 합니다/);
  });

  it("validation 에러 시 select 둘 다 aria-invalid + aria-describedby 가 설정된다 (#464)", async () => {
    const user = userEvent.setup();
    renderWithQueryClient(<VoiceRangePage />);
    const [lowSelect, highSelect] = screen.getAllByRole(
      "combobox",
    ) as HTMLSelectElement[];

    await user.selectOptions(highSelect, "40");
    await user.selectOptions(lowSelect, "60");

    expect(lowSelect).toHaveAttribute("aria-invalid", "true");
    expect(highSelect).toHaveAttribute("aria-invalid", "true");
    // describedby 가 가리키는 id 가 실제로 alert element 의 id 와 일치.
    const describedById = lowSelect.getAttribute("aria-describedby");
    expect(describedById).toBeTruthy();
    const alert = screen.getByRole("alert");
    expect(alert.id).toBe(describedById);
  });

  it("submit 에러 시 role=alert 메시지가 노출된다 (#464)", async () => {
    const user = userEvent.setup();
    createVoiceRangeMock.mockRejectedValueOnce(
      new ApiError(500, "boom", { message: "boom" }),
    );

    renderWithQueryClient(<VoiceRangePage />);

    await user.click(screen.getByRole("button", { name: /추천 받기/ }));

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent(/저장에 실패했습니다/);
    expect(alert).toHaveTextContent(/500: boom/);
  });
});

// closes #552 — 폼 키보드 탐색(Tab) 자연 순서 회귀 가드.
// 폼 내부 focusable: 최저음 select → 최고음 select → "추천 받기" submit button.
// tabIndex 미지정/끼어드는 요소 회귀를 방지한다.
describe("VoiceRangePage 폼 Tab 키보드 탐색", () => {
  it("최저음 → 최고음 → 추천 받기 버튼 순으로 Tab 포커스가 이동한다", async () => {
    const user = userEvent.setup();
    renderWithQueryClient(<VoiceRangePage />);
    const [lowSelect, highSelect] = screen.getAllByRole(
      "combobox",
    ) as HTMLSelectElement[];
    const submitButton = screen.getByRole("button", { name: /추천 받기/ });

    lowSelect.focus();
    expect(lowSelect).toHaveFocus();

    await user.tab();
    expect(highSelect).toHaveFocus();

    await user.tab();
    expect(submitButton).toHaveFocus();
  });

  // closes #563 — PR #554 후속. forward Tab 순서만 가드되어 있어
  // Shift+Tab 역방향에 focusable 요소가 끼어드는 회귀를 잡지 못한다.
  // submit → 최고음 → 최저음 의 backward 순서가 자연스럽게 유지되는지 검증한다.
  it("submit → 최고음 → 최저음 순으로 Shift+Tab 포커스가 역방향 이동한다 (#563)", async () => {
    const user = userEvent.setup();
    renderWithQueryClient(<VoiceRangePage />);
    const [lowSelect, highSelect] = screen.getAllByRole(
      "combobox",
    ) as HTMLSelectElement[];
    const submitButton = screen.getByRole("button", { name: /추천 받기/ });

    submitButton.focus();
    expect(submitButton).toHaveFocus();

    await user.tab({ shift: true });
    expect(highSelect).toHaveFocus();

    await user.tab({ shift: true });
    expect(lowSelect).toHaveFocus();
  });
});
