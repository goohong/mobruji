/**
 * 북마크 스토어 단위 테스트 (closes #184).
 *
 * 범위:
 *  - toggleBookmark: 없으면 prepend 추가, 있으면 제거.
 *  - isBookmarked: songId 매칭.
 *  - setBookmarkedSongIds: BE 응답 hydration.
 */

import { beforeEach, describe, expect, it } from "vitest";

import { useBookmarksStore } from "./bookmarks";

beforeEach(() => {
  useBookmarksStore.setState({ bookmarkedSongIds: [] });
  if (typeof localStorage !== "undefined") {
    localStorage.removeItem("mobruji-bookmarks");
  }
});

describe("useBookmarksStore.toggleBookmark", () => {
  it("없는 songId는 prepend로 추가하고, 있는 songId는 제거한다 (멱등)", () => {
    const { toggleBookmark } = useBookmarksStore.getState();

    toggleBookmark(10);
    toggleBookmark(20);
    expect(useBookmarksStore.getState().bookmarkedSongIds).toEqual([20, 10]);

    toggleBookmark(10);
    expect(useBookmarksStore.getState().bookmarkedSongIds).toEqual([20]);
  });
});

describe("useBookmarksStore.isBookmarked", () => {
  it("북마크한 songId만 true를 반환한다", () => {
    const { toggleBookmark, isBookmarked } = useBookmarksStore.getState();
    toggleBookmark(7);

    expect(isBookmarked(7)).toBe(true);
    expect(isBookmarked(8)).toBe(false);
  });
});

describe("useBookmarksStore.setBookmarkedSongIds", () => {
  it("BE 응답으로 store를 덮어쓴다", () => {
    const { toggleBookmark, setBookmarkedSongIds } =
      useBookmarksStore.getState();
    toggleBookmark(1);

    setBookmarkedSongIds([42, 99]);

    expect(useBookmarksStore.getState().bookmarkedSongIds).toEqual([42, 99]);
  });
});

describe("useBookmarksStore 경계 회귀 (#583)", () => {
  it("동일 songId 3회 toggle은 결국 비어있다 (멱등 누적)", () => {
    const { toggleBookmark } = useBookmarksStore.getState();
    toggleBookmark(5);
    toggleBookmark(5);
    toggleBookmark(5);
    expect(useBookmarksStore.getState().bookmarkedSongIds).toEqual([5]);
  });

  it("setBookmarkedSongIds([])는 store를 비운다", () => {
    const { toggleBookmark, setBookmarkedSongIds } =
      useBookmarksStore.getState();
    toggleBookmark(1);
    setBookmarkedSongIds([]);
    expect(useBookmarksStore.getState().bookmarkedSongIds).toEqual([]);
  });

  it("clearBookmarks는 전체를 비운다", () => {
    const { toggleBookmark, clearBookmarks } = useBookmarksStore.getState();
    toggleBookmark(1);
    toggleBookmark(2);
    clearBookmarks();
    expect(useBookmarksStore.getState().bookmarkedSongIds).toEqual([]);
  });

  it("setBookmarkedSongIds 입력 배열을 외부에서 mutate해도 store는 격리된다", () => {
    const { setBookmarkedSongIds } = useBookmarksStore.getState();
    const input = [1, 2, 3];
    setBookmarkedSongIds(input);
    (input as number[]).push(999);
    expect(useBookmarksStore.getState().bookmarkedSongIds).toEqual([1, 2, 3]);
  });
});
