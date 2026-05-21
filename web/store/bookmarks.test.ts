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
