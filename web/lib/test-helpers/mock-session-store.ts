/**
 * 테스트용 zustand 세션 스토어 mock 헬퍼.
 *
 * 배경:
 *  - `useSessionStore`는 `selector(state) => slice` 패턴으로 호출되므로
 *    단순 객체 mock으로는 컴포넌트가 selector를 못 풀어 실패한다.
 *  - vi.mock factory는 파일 최상단으로 hoisting되기 때문에, factory 내부에서
 *    외부 모듈의 import를 직접 참조할 수 없다 (TDZ ReferenceError).
 *    그래서 본 헬퍼는 `vi.hoisted` 안에서 사용할 "공유 상태 컨테이너 빌더"를
 *    제공한다. 빌더 자체는 외부 모듈 의존이 없는 순수 함수다.
 *
 * 사용 패턴:
 *
 *   import { vi } from "vitest";
 *   import { buildSessionStoreMock } from "@/lib/test-helpers/mock-session-store";
 *
 *   const sessionMock = vi.hoisted(() => buildSessionStoreMock());
 *
 *   vi.mock("@/store/session", () => ({
 *     useSessionStore: sessionMock.useSessionStore,
 *   }));
 *
 *   beforeEach(() => sessionMock.reset());
 *
 *   // 상태 변경:  sessionMock.set({ sessionId: "abc" });
 *   // 호출 검증:  expect(sessionMock.state().setVoiceRangeId).toHaveBeenCalledWith(42);
 */

import { vi } from "vitest";

/**
 * 세션 스토어 상태 모양. `web/store/session.ts`의 SessionState와 동일해야 한다.
 * 외부 타입 import는 vi.mock factory 호이스팅과 충돌해서 명시적으로 다시 적는다.
 */
export type MockSessionState = {
  sessionId: string | null;
  voiceRangeId: number | null;
  excludedSongIds: number[];
  ensureSessionId: () => string;
  setVoiceRangeId: (id: number) => void;
  appendExcluded: (ids: number[]) => void;
  clearExcluded: () => void;
  reset: () => void;
};

type Selector<TResult> = (state: MockSessionState) => TResult;

/**
 * `useSessionStore`의 호출 시그니처 + zustand store가 노출하는 `.getState`까지
 * 흉내내는 콜러블 객체. 컴포넌트가 `useSessionStore.getState()`로 selector 바깥에서
 * 최신 상태를 읽는 경우(예: mutate 핸들러)에도 동작하도록 한다.
 */
export type MockUseSessionStore = (<TResult = MockSessionState>(
  selector?: Selector<TResult>,
) => TResult) & {
  getState: () => MockSessionState;
};

export type SessionStoreMock = {
  /** 컴포넌트가 호출하는 훅 함수. selector(state)를 그대로 흉내낸다. */
  useSessionStore: MockUseSessionStore;
  /** 현재 mock 상태 스냅샷 반환. spy 호출 검증에 쓴다. */
  state: () => MockSessionState;
  /** 상태 일부를 갈아끼움. spy 함수 ref는 유지된다. */
  set: (partial: Partial<MockSessionState>) => void;
  /** 기본 상태로 되돌리고 spy를 새로 만들어 호출 카운트를 0으로 리셋한다. */
  reset: () => void;
};

function freshDefaults(): MockSessionState {
  return {
    sessionId: null,
    voiceRangeId: null,
    excludedSongIds: [],
    ensureSessionId: vi.fn(() => "00000000-0000-4000-8000-000000000001"),
    setVoiceRangeId: vi.fn(),
    appendExcluded: vi.fn(),
    clearExcluded: vi.fn(),
    reset: vi.fn(),
  };
}

/**
 * vi.hoisted 안에서 호출해 공유 mock 컨테이너를 만든다.
 *
 * 반환되는 객체는 두 가지 역할을 한다:
 *  - vi.mock factory에 그대로 useSessionStore를 넘기는 채널
 *  - 테스트에서 상태를 set/reset하고 spy를 검증하는 핸들
 */
export function buildSessionStoreMock(
  initialState: Partial<MockSessionState> = {},
): SessionStoreMock {
  let current: MockSessionState = { ...freshDefaults(), ...initialState };

  function useSessionStore<TResult = MockSessionState>(
    selector?: Selector<TResult>,
  ): TResult {
    if (selector) {
      return selector(current);
    }
    return current as unknown as TResult;
  }
  // zustand store의 정적 `getState` 흉내 — 컴포넌트가 selector 바깥에서
  // `useSessionStore.getState()`로 최신 상태를 읽는 경우를 지원한다.
  (useSessionStore as MockUseSessionStore).getState = () => current;

  return {
    useSessionStore: useSessionStore as MockUseSessionStore,
    state: () => current,
    set: (partial) => {
      current = { ...current, ...partial };
    },
    reset: () => {
      current = freshDefaults();
    },
  };
}
