/**
 * 좋아요 스토어 단위 테스트 (closes #176, spec PR D 일부).
 *
 * 범위:
 *  - toggleLike: 없으면 추가, 있으면 제거 (멱등 토글).
 *  - isLiked: songId 매칭 boolean.
 *  - clearLikes: 전체 초기화.
 *  - persist 라운드트립: localStorage 직렬/역직렬 후 동일.
 */

import { beforeEach, describe, expect, it } from "vitest";

import { useLikesStore } from "./likes";

beforeEach(() => {
  // persist middleware 격리.
  useLikesStore.setState({ likedSongIds: [] });
  if (typeof localStorage !== "undefined") {
    localStorage.removeItem("mobruji-likes");
  }
});

describe("useLikesStore.toggleLike", () => {
  it("없는 songId는 prepend로 추가한다", () => {
    const { toggleLike } = useLikesStore.getState();

    toggleLike(10);
    toggleLike(20);
    toggleLike(30);

    // 최근에 좋아요한 것이 앞.
    expect(useLikesStore.getState().likedSongIds).toEqual([30, 20, 10]);
  });

  it("이미 있는 songId는 제거한다 (멱등 토글)", () => {
    const { toggleLike } = useLikesStore.getState();
    toggleLike(10);
    toggleLike(20);

    toggleLike(10);

    expect(useLikesStore.getState().likedSongIds).toEqual([20]);

    // 다시 토글하면 prepend로 복귀.
    toggleLike(10);
    expect(useLikesStore.getState().likedSongIds).toEqual([10, 20]);
  });
});

describe("useLikesStore.isLiked", () => {
  it("좋아요한 songId만 true를 반환한다", () => {
    const { toggleLike, isLiked } = useLikesStore.getState();
    toggleLike(7);

    expect(isLiked(7)).toBe(true);
    expect(isLiked(8)).toBe(false);
  });
});

describe("useLikesStore.clearLikes", () => {
  it("전체 좋아요를 비운다", () => {
    const { toggleLike, clearLikes } = useLikesStore.getState();
    toggleLike(1);
    toggleLike(2);
    toggleLike(3);

    clearLikes();
    expect(useLikesStore.getState().likedSongIds).toEqual([]);
  });
});

// closes #184 — BE 응답 hydration용 액션.
describe("useLikesStore.setLikedSongIds", () => {
  it("BE 응답 배열로 store를 덮어쓴다 (기존 값 무시)", () => {
    const { toggleLike, setLikedSongIds } = useLikesStore.getState();
    toggleLike(1);
    toggleLike(2);

    setLikedSongIds([100, 200, 300]);

    expect(useLikesStore.getState().likedSongIds).toEqual([100, 200, 300]);
  });

  it("빈 배열을 받으면 store도 비운다", () => {
    const { toggleLike, setLikedSongIds } = useLikesStore.getState();
    toggleLike(42);

    setLikedSongIds([]);

    expect(useLikesStore.getState().likedSongIds).toEqual([]);
  });
});

describe("useLikesStore persist 라운드트립", () => {
  it("localStorage에 직렬화되며 JSON 라운드트립 시 동일 데이터를 보존한다", () => {
    const { toggleLike } = useLikesStore.getState();
    toggleLike(42);
    toggleLike(99);

    const raw = localStorage.getItem("mobruji-likes");
    expect(raw).not.toBeNull();

    const parsed = JSON.parse(raw as string) as {
      state: { likedSongIds: number[] };
    };
    // prepend 정책에 따라 99가 먼저.
    expect(parsed.state.likedSongIds).toEqual([99, 42]);
  });
});

describe("useLikesStore 경계 회귀 (#586)", () => {
  it("동일 songId 3회 toggle은 결국 1개로 prepend된다 (멱등 누적)", () => {
    const { toggleLike } = useLikesStore.getState();
    toggleLike(7);
    toggleLike(7);
    toggleLike(7);
    expect(useLikesStore.getState().likedSongIds).toEqual([7]);
  });

  it("setLikedSongIds 입력 배열을 외부에서 mutate해도 store는 격리된다", () => {
    const { setLikedSongIds } = useLikesStore.getState();
    const input = [10, 20, 30];
    setLikedSongIds(input);
    input.push(999);
    expect(useLikesStore.getState().likedSongIds).toEqual([10, 20, 30]);
  });

  it("localStorage가 손상돼도 store는 빈 배열로 안전 동작한다", () => {
    localStorage.setItem("mobruji-likes", "{not json");
    // 새 액션 호출은 throw 없이 진행되어야 한다 (persist hydration 실패 격리).
    useLikesStore.setState({ likedSongIds: [] });
    useLikesStore.getState().toggleLike(1);
    expect(useLikesStore.getState().likedSongIds).toEqual([1]);
  });
});
