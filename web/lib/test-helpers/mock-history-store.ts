/**
 * 테스트용 히스토리 스토어 mock 헬퍼.
 *
 * 배경:
 *  - `useHistoryStore`는 selector 패턴이라 단순 객체 mock 으론 selector 가 풀리지 않는다.
 *  - vi.mock factory 호이스팅 제약 때문에 vi.hoisted 안에서 호출할 빌더가 필요하다.
 *  - 세션 스토어 mock(`mock-session-store.ts`)과 같은 형태로 통일한다.
 */

import { vi } from "vitest";

import type {
  RecommendationHistoryEntry,
  RecommendationHistoryInput,
} from "@/store/history";

export type MockHistoryState = {
  recommendations: RecommendationHistoryEntry[];
  appendRecommendation: (input: RecommendationHistoryInput) => void;
  removeRecommendation: (id: string) => void;
  clearHistory: () => void;
};

type Selector<TResult> = (state: MockHistoryState) => TResult;

export type MockUseHistoryStore = (<TResult = MockHistoryState>(
  selector?: Selector<TResult>,
) => TResult) & {
  getState: () => MockHistoryState;
};

export type HistoryStoreMock = {
  useHistoryStore: MockUseHistoryStore;
  state: () => MockHistoryState;
  set: (partial: Partial<MockHistoryState>) => void;
  reset: () => void;
};

function freshDefaults(): MockHistoryState {
  return {
    recommendations: [],
    appendRecommendation: vi.fn(),
    removeRecommendation: vi.fn(),
    clearHistory: vi.fn(),
  };
}

export function buildHistoryStoreMock(
  initialState: Partial<MockHistoryState> = {},
): HistoryStoreMock {
  let current: MockHistoryState = { ...freshDefaults(), ...initialState };

  function useHistoryStore<TResult = MockHistoryState>(
    selector?: Selector<TResult>,
  ): TResult {
    if (selector) {
      return selector(current);
    }
    return current as unknown as TResult;
  }
  (useHistoryStore as MockUseHistoryStore).getState = () => current;

  return {
    useHistoryStore: useHistoryStore as MockUseHistoryStore,
    state: () => current,
    set: (partial) => {
      current = { ...current, ...partial };
    },
    reset: () => {
      current = freshDefaults();
    },
  };
}
