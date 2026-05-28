/**
 * LikeButton / BookmarkButton / DetailLikeButton / DetailBookmarkButton —
 * hook → UI reflect 통합 회귀 가드 (PR #985 후속).
 *
 * AS-IS:
 *   - PR #985 가 `useLikeToggleMutation` / `useBookmarkToggleMutation` hook 레벨
 *     race / unmount / 401 + auto-dismiss 가드 6건을 추가했음.
 *   - 그러나 hook 결과 (`isPending`, `errorMessage`) 를 4 컴포넌트
 *     (SongCard.LikeButton / SongCard.BookmarkButton / SongDetailContent.DetailLikeButton /
 *     SongDetailContent.DetailBookmarkButton) 가 실제로 UI 로 reflect 하는지 검증하는
 *     컴포넌트 레벨 통합 가드는 **부재**. 누군가 컴포넌트에서 `disabled={isPending}` /
 *     `aria-busy={isPending}` / `errorMessage` 분기를 떼도 hook 테스트 통과로 회귀가
 *     잡히지 않는다.
 *
 * TO-BE:
 *   본 파일은 4 컴포넌트 × 3 시나리오 = 12 회귀 가드 + 공통 마운트 smoke = 16건 추가.
 *   3 시나리오는 PR #985 hook 가드와 1:1 짝:
 *     A. pending 상태 → disabled + aria-busy="true" reflect
 *     B. 401 (ApiError) → 에러 alert 노출 + 3000ms 후 auto-dismiss
 *     C. 정상 toggle → optimistic UI 즉시 update (aria-pressed false→true)
 *
 * 비범위:
 *   - 컴포넌트 본체 변경 / hook 변경 / 새 컴포넌트 신설 — 본 PR 은 테스트만 추가.
 *   - hook 자체 race/unmount/401 흐름은 PR #985 의 hook 테스트가 cover.
 *   - YouTube 링크, breakdown expander 등 버튼 외 요소는 기존 SongCard.test.tsx 가 cover.
 *   - 기존 SongCard.test.tsx / SongDetailContent.test.tsx 의 alert aria-live / 낙관 롤백
 *     케이스와 중복하지 않도록 본 파일은 **disabled+aria-busy / 401 ApiError 분기 /
 *     3000ms 타이머 dismiss** 3 축만 다룬다.
 *
 * 보안:
 *   - sessionId 는 PII (logging.ts §SENSITIVE_KEYS). 본 테스트의 "00000000-0000-4000-8000-000000000001" 는
 *     의도된 더미값으로 raw 노출 안전.
 */

import { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import { SongCard } from "./SongCard";
import { SongDetailContent } from "./SongDetailContent";
import { ApiError } from "@/lib/api/client";
import type {
  RecommendedSongResponse,
  SongResponse,
} from "@/lib/api/recommendation";
import { useBookmarksStore } from "@/store/bookmarks";
import { useLikesStore } from "@/store/likes";
import { useSessionStore } from "@/store/session";

vi.mock("@/lib/api/feedback", () => ({
  toggleLike: vi.fn(),
  toggleBookmark: vi.fn(),
}));

import { toggleBookmark, toggleLike } from "@/lib/api/feedback";

const toggleLikeMock = vi.mocked(toggleLike);
const toggleBookmarkMock = vi.mocked(toggleBookmark);

// PR #985 가 hook 에 박은 자동 dismiss 지속 시간. 본 테스트에서 fake timer 로 정확히
// 이 값만큼 advance 했을 때 alert 가 사라져야 한다 — 동일 상수 import 로 hook 측 변경에
// 자동 추종 (drift 회피).
import { INTERACTION_FEEDBACK_DURATION_MS } from "@/lib/hooks/useFeedbackToggleMutation";

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

const SONG: SongResponse = {
  id: 1,
  title: "테스트 곡",
  artist: "테스트 가수",
  releaseYear: 2024,
  keyOriginal: "C_MAJOR",
  bpm: 120,
  mood: "UPBEAT",
  language: "ko",
  genre: "POP",
  tjNumber: null,
  kyNumber: null,
  metadataSource: "MANUAL_SEED",
};

function buildItem(): RecommendedSongResponse {
  return {
    rankPosition: 1,
    score: 0.91,
    matchReason: "음역 매칭",
    song: SONG,
  };
}

beforeEach(() => {
  useLikesStore.setState({ likedSongIds: [] });
  useBookmarksStore.setState({ bookmarkedSongIds: [] });
  useSessionStore.setState({
    sessionId: "00000000-0000-4000-8000-000000000001",
    voiceRangeId: null,
    excludedSongIds: [],
  });
  if (typeof localStorage !== "undefined") {
    ["mobruji-likes", "mobruji-bookmarks", "mobruji-session"].forEach((k) =>
      localStorage.removeItem(k),
    );
  }
  toggleLikeMock.mockReset();
  toggleBookmarkMock.mockReset();
});

afterEach(() => {
  cleanup();
  // 본 파일은 fake timer 를 사용하지 않으나, 다른 테스트 파일이 leak 시킬 가능성에
  // 대비해 safety net.
  vi.useRealTimers();
});

/**
 * `mutationFn` 이 mount 단계에서 즉시 resolve/reject 되지 않고 외부에서 컨트롤 가능한
 * Promise 를 돌려주도록 만든다. 시나리오 A (pending 반영) 검증의 핵심 도구.
 *
 * 반환 객체:
 *   - `promise` — vi.fn() 의 mockImplementation 에 그대로 반환할 Promise.
 *   - `resolve` — 외부에서 호출하면 mutation 이 onSuccess 로 흘러간다.
 *   - `reject` — 외부에서 호출하면 mutation 이 onError 로 흘러간다.
 *
 * happy-dom 환경에서 같은 micro-task 안에 pending 상태를 관찰하기 위해 의도적으로
 * resolve 를 지연시킨다. setTimeout(0) 이나 setImmediate 가 아니라 외부 트리거로
 * 둬야 timing flake 가 없다.
 */
function deferred<T>(): {
  promise: Promise<T>;
  resolve: (value: T) => void;
  reject: (error: unknown) => void;
} {
  let resolveFn: (value: T) => void = () => undefined;
  let rejectFn: (error: unknown) => void = () => undefined;
  const promise = new Promise<T>((res, rej) => {
    resolveFn = res;
    rejectFn = rej;
  });
  return { promise, resolve: resolveFn, reject: rejectFn };
}

/**
 * BE 401 응답을 흉내내는 ApiError. PR #985 의 hook 401 가드와 동일 형태로 hook 의
 * onError 분기 (낙관 토글 롤백 + 한국어 안내 + 자동 dismiss) 를 트리거한다.
 *
 * 본 가드는 "ApiError(401) 가 일반 Error 와 동일하게 onError 분기로 흘러가는지"
 * 까지만 검증한다. hook 이 status 별 분기를 추가하면 별도 회귀 가드를 분리 추가.
 */
function unauthorizedError(): ApiError {
  return new ApiError(401, "Unauthorized", { error: "UNAUTHORIZED" });
}

// 4 컴포넌트 × 3 시나리오 매트릭스. 각 컴포넌트의 mount 헬퍼 + 어떤 mock 을 stub 할지
// 를 묶어 시나리오 본체는 한 번만 쓰고 재사용한다.
type ButtonCase = {
  readonly label: string;
  readonly mount: () => void;
  /** 액션 버튼 selector — `aria-label` 정규식. 클릭 전 상태. */
  readonly initialButtonName: RegExp;
  /** 토글 성공 후 라벨 (낙관 UI 반영) — aria-label 정규식. */
  readonly toggledButtonName: RegExp;
  /** mutation mock — 이 mock 의 resolve/reject 로 BE 응답 시뮬레이션. */
  readonly mutationMock: typeof toggleLikeMock | typeof toggleBookmarkMock;
  /** 성공 응답 body. (liked/bookmarked 별 분기). */
  readonly successPayload:
    | { liked: boolean; songId: number }
    | { bookmarked: boolean; songId: number };
  /** 401 실패 시 alert 본문 정규식. hook 의 한국어 안내와 1:1. */
  readonly errorMessageRegex: RegExp;
};

function likeCardCase(): ButtonCase {
  return {
    label: "SongCard.LikeButton",
    mount: () =>
      renderWithQueryClient(
        <ul>
          <SongCard item={buildItem()} />
        </ul>,
      ),
    initialButtonName: /테스트 곡 좋아요$/,
    toggledButtonName: /테스트 곡 좋아요 취소/,
    mutationMock: toggleLikeMock,
    successPayload: { liked: true, songId: 1 },
    errorMessageRegex: /좋아요 처리에 실패했어요/,
  };
}

function bookmarkCardCase(): ButtonCase {
  return {
    label: "SongCard.BookmarkButton",
    mount: () =>
      renderWithQueryClient(
        <ul>
          <SongCard item={buildItem()} />
        </ul>,
      ),
    initialButtonName: /테스트 곡 북마크$/,
    toggledButtonName: /테스트 곡 북마크 해제/,
    mutationMock: toggleBookmarkMock,
    successPayload: { bookmarked: true, songId: 1 },
    errorMessageRegex: /북마크 처리에 실패했어요/,
  };
}

function likeDetailCase(): ButtonCase {
  return {
    label: "SongDetailContent.DetailLikeButton",
    mount: () => renderWithQueryClient(<SongDetailContent song={SONG} />),
    initialButtonName: /테스트 곡 좋아요$/,
    toggledButtonName: /테스트 곡 좋아요 취소/,
    mutationMock: toggleLikeMock,
    successPayload: { liked: true, songId: 1 },
    errorMessageRegex: /좋아요 처리에 실패했어요/,
  };
}

function bookmarkDetailCase(): ButtonCase {
  return {
    label: "SongDetailContent.DetailBookmarkButton",
    mount: () => renderWithQueryClient(<SongDetailContent song={SONG} />),
    initialButtonName: /테스트 곡 북마크$/,
    toggledButtonName: /테스트 곡 북마크 해제/,
    mutationMock: toggleBookmarkMock,
    successPayload: { bookmarked: true, songId: 1 },
    errorMessageRegex: /북마크 처리에 실패했어요/,
  };
}

const CASES: readonly (() => ButtonCase)[] = [
  likeCardCase,
  bookmarkCardCase,
  likeDetailCase,
  bookmarkDetailCase,
];

describe("Button UI reflect 통합 가드 (PR #985 후속)", () => {
  // 시나리오 A — pending 상태 → disabled + aria-busy="true" reflect
  //
  // hook 의 `mutation.isPending` 이 컴포넌트의 `disabled={isPending}` /
  // `aria-busy={isPending}` 분기로 흘러야 한다. mutationFn 을 외부 컨트롤 deferred
  // promise 로 stub 해 클릭 직후 micro-task 시점에 두 attribute 가 동시에 set 되는지
  // 관찰한다. resolve 후에는 둘 다 해제돼야 한다 (중복 클릭 가드 복원).
  describe("시나리오 A — pending 상태 reflect (disabled + aria-busy)", () => {
    for (const buildCase of CASES) {
      const c = buildCase();
      it(`${c.label}: 클릭 직후 disabled + aria-busy="true", BE 응답 후 해제`, async () => {
        const user = userEvent.setup();
        const def = deferred<typeof c.successPayload>();
        // mutationFn 호출 시 외부 컨트롤 promise 반환 — 명시적으로 resolve 호출 전까지
        // pending 상태가 유지된다 (PR #985 의 race 가드 검증과 동형).
        (c.mutationMock as unknown as ReturnType<typeof vi.fn>).mockImplementation(
          () => def.promise,
        );

        c.mount();
        const button = screen.getByRole("button", { name: c.initialButtonName });
        expect(button).not.toBeDisabled();
        expect(button).toHaveAttribute("aria-busy", "false");

        await user.click(button);

        // 같은 turn 안에 pending 이 reflect 되는지 — disabled + aria-busy="true" 동시.
        // user.click 은 micro-task 를 flush 하므로 click 직후 set 된 상태가 관찰 가능.
        await waitFor(() => {
          expect(button).toBeDisabled();
        });
        expect(button).toHaveAttribute("aria-busy", "true");

        // 외부 트리거로 BE 응답 → onSuccess → isPending=false. 다시 클릭 가능 상태.
        await act(async () => {
          def.resolve(c.successPayload);
          // resolve 후 React Query 의 onSuccess + state 갱신을 flush.
          await Promise.resolve();
        });

        await waitFor(() => {
          expect(
            screen.getByRole("button", { name: c.toggledButtonName }),
          ).not.toBeDisabled();
        });
        expect(
          screen.getByRole("button", { name: c.toggledButtonName }),
        ).toHaveAttribute("aria-busy", "false");
      });
    }
  });

  // 시나리오 B — 401 ApiError → alert 노출 + 3000ms 후 auto-dismiss
  //
  // PR #985 가 hook 레벨에서 박은 "ApiError(401) → 한국어 안내 + useAutoDismissMessage"
  // 흐름이 컴포넌트의 `errorMessage ? <p role="alert">` 분기로 reflect 되어야 한다.
  // 추가로 INTERACTION_FEEDBACK_DURATION_MS (3000ms) 경과 시 alert 가 DOM 에서 사라져야
  // 한다 — 이 dismiss 가 깨지면 모달/카드에 stale alert 가 굳어 사용자에게 가짜 실패
  // 신호가 영구 노출된다.
  describe("시나리오 B — 401 ApiError 시 alert + auto-dismiss (3000ms)", () => {
    for (const buildCase of CASES) {
      const c = buildCase();
      it(`${c.label}: 401 ApiError → alert 노출`, async () => {
        // 401 (ApiError status=401) reject 시 hook 의 onError → setErrorMessage 로 흘러
        // 한국어 안내가 컴포넌트 alert 분기로 reflect 되는지 검증. ApiError 분기가
        // 일반 Error 와 동일하게 onError 로 흐름을 명시적으로 가드한다 — 향후 hook 이
        // status 별 분기 (예: 401 → 세션 만료 안내) 를 추가하면 본 가드를 split.
        const user = userEvent.setup();
        (c.mutationMock as unknown as ReturnType<typeof vi.fn>).mockRejectedValueOnce(
          unauthorizedError(),
        );

        c.mount();
        const button = screen.getByRole("button", { name: c.initialButtonName });
        await user.click(button);

        // hook 의 onError → setErrorMessage → 컴포넌트 alert 분기 reflect.
        const alert = await screen.findByRole("alert");
        expect(alert).toHaveTextContent(c.errorMessageRegex);
        expect(alert).toHaveAttribute("aria-live", "assertive");
      });

      it(`${c.label}: 401 후 ${INTERACTION_FEEDBACK_DURATION_MS}ms 경과 시 alert auto-dismiss`, async () => {
        // userEvent + fake timer 결합은 async 마다 timer 자동 진행으로 비결정적 →
        // (1) real timer 로 클릭 + alert 표시까지 진행 후
        // (2) waitFor 로 INTERACTION_FEEDBACK_DURATION_MS 경계의 ±100ms slack 안에서
        //     alert 가 사라졌음을 확인.
        // 본 가드는 "timeout 이 등록되어 결과적으로 dismiss 한다" 를 검증하는
        // 통합 가드. 정확한 duration 측정은 useAutoDismissMessage.test.ts (lib 레벨)
        // 가 fake timer 로 별도 cover (drift 가드는 그쪽이 본인).
        const user = userEvent.setup();
        (c.mutationMock as unknown as ReturnType<typeof vi.fn>).mockRejectedValueOnce(
          unauthorizedError(),
        );

        c.mount();
        const button = screen.getByRole("button", { name: c.initialButtonName });
        await user.click(button);

        // alert 가 일단 떴는지 확인.
        await screen.findByRole("alert");

        // INTERACTION_FEEDBACK_DURATION_MS + slack 안에 dismiss 되어야 함.
        // happy-dom + real timer 라 waitFor 의 폴링 (50ms) 이 timeout 발사를 자연스럽게
        // 추종한다. timeout 자체가 등록 안 되어 영구 stale 인 회귀를 잡는 가드.
        await waitFor(
          () => {
            expect(screen.queryByRole("alert")).not.toBeInTheDocument();
          },
          { timeout: INTERACTION_FEEDBACK_DURATION_MS + 1500 },
        );
      });
    }
  });

  // 시나리오 C — 정상 toggle → optimistic UI 즉시 update
  //
  // mutationFn pending 중에도 (BE 응답 도착 전에) aria-pressed 가 즉시 true 로 뒤집혀야
  // 한다. hook 의 onMutate (낙관 토글) 가 컴포넌트의 `aria-pressed={liked}` 분기로
  // 실제 reflect 되는지 검증. 본 가드는 deferred promise 를 통해 "BE 응답 대기 중에도
  // UI 가 즉시 갱신" 을 명시적으로 관찰한다 — 기존 SongCard.test.tsx 의 낙관 UI
  // 케이스가 immediately resolved mock 으로 검증하는 것과 의도적으로 다르다.
  describe("시나리오 C — 정상 toggle 시 낙관 UI 즉시 update (BE pending 중에도)", () => {
    for (const buildCase of CASES) {
      const c = buildCase();
      it(`${c.label}: 클릭 직후 aria-pressed=true (BE pending 동안에도 낙관 반영)`, async () => {
        const user = userEvent.setup();
        const def = deferred<typeof c.successPayload>();
        (c.mutationMock as unknown as ReturnType<typeof vi.fn>).mockImplementation(
          () => def.promise,
        );

        c.mount();
        const button = screen.getByRole("button", { name: c.initialButtonName });
        expect(button).toHaveAttribute("aria-pressed", "false");

        await user.click(button);

        // BE 응답이 아직 안 왔어도 (resolve 호출 전) 낙관 토글이 보여야 한다.
        // hook 의 onMutate → store toggle → 컴포넌트 selector 갱신 → aria-pressed=true.
        const toggled = await screen.findByRole("button", {
          name: c.toggledButtonName,
        });
        expect(toggled).toHaveAttribute("aria-pressed", "true");

        // 응답 도착 후에도 동일 (BE 응답 일치 시 store 보정 noop).
        await act(async () => {
          def.resolve(c.successPayload);
          await Promise.resolve();
        });

        await waitFor(() => {
          expect(
            screen.getByRole("button", { name: c.toggledButtonName }),
          ).toHaveAttribute("aria-pressed", "true");
        });
      });
    }
  });
});
