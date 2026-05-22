/**
 * SongCard 렌더 테스트.
 *
 * - 응답에 `difficulty`가 있으면 그 값을 라벨로 표시한다 (BE 우선).
 * - `difficulty`가 없고 `lowMidi`/`highMidi`만 있으면 client-side `deriveDifficulty`로 분류한다.
 * - 최고음/최저음 음표명, 장르 칩, score, matchReason이 노출된다.
 */

import { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import { SongCard, buildYouTubeSearchUrl } from "./SongCard";
import { expectNoA11yViolations } from "@/lib/test-helpers/a11y";
import type { RecommendedSongResponse } from "@/lib/api/recommendation";
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

function buildItem(
  overrides: Partial<RecommendedSongResponse["song"]> = {},
  itemOverrides: Partial<RecommendedSongResponse> = {},
): RecommendedSongResponse {
  return {
    rankPosition: 1,
    score: 0.91,
    matchReason: "음역 매칭",
    song: {
      id: 1,
      title: "테스트 곡",
      artist: "가수",
      releaseYear: 2024,
      keyOriginal: "C_MAJOR",
      bpm: 120,
      mood: "UPBEAT",
      language: "ko",
      genre: "POP",
      tjNumber: null,
      kyNumber: null,
      metadataSource: "MANUAL_SEED",
      ...overrides,
    },
    ...itemOverrides,
  };
}

beforeEach(() => {
  // 좋아요/북마크/세션 store 격리 — persist localStorage 영향 제거.
  useLikesStore.setState({ likedSongIds: [] });
  useBookmarksStore.setState({ bookmarkedSongIds: [] });
  useSessionStore.setState({
    sessionId: "test-session-id",
    voiceRangeId: null,
    excludedSongIds: [],
  });
  if (typeof localStorage !== "undefined") {
    localStorage.removeItem("mobruji-likes");
    localStorage.removeItem("mobruji-bookmarks");
    localStorage.removeItem("mobruji-session");
  }
  toggleLikeMock.mockReset();
  toggleBookmarkMock.mockReset();
  // 기본 응답 — 테스트별로 mockResolvedValue로 override.
  toggleLikeMock.mockResolvedValue({ liked: true, songId: 1 });
  toggleBookmarkMock.mockResolvedValue({ bookmarked: true, songId: 1 });
});

afterEach(() => {
  cleanup();
});

describe("SongCard", () => {
  it("BE 응답에 difficulty가 있으면 그 값을 라벨로 노출한다", () => {
    const item = buildItem({ difficulty: "HARD" });
    renderWithQueryClient(
      <ul>
        <SongCard item={item} />
      </ul>,
    );
    expect(
      screen.getByLabelText(/가창 난이도 Hard/),
    ).toBeInTheDocument();
    expect(screen.getByText("테스트 곡")).toBeInTheDocument();
    expect(screen.getByText("가수")).toBeInTheDocument();
    expect(screen.getByText("POP")).toBeInTheDocument();
    expect(screen.getByText(/score 0\.91/)).toBeInTheDocument();
  });

  it("difficulty가 없고 lowMidi/highMidi만 있으면 client-side 계산 라벨을 노출한다", () => {
    // highMidi=77(F5) → HARD
    const item = buildItem({ lowMidi: 55, highMidi: 77 });
    renderWithQueryClient(
      <ul>
        <SongCard item={item} />
      </ul>,
    );
    expect(
      screen.getByLabelText(/가창 난이도 Hard/),
    ).toBeInTheDocument();
    // 최고음 음표명 노출 — MIDI 77 = F5
    expect(screen.getByLabelText(/최고음 F5/)).toBeInTheDocument();
    // 최저음(작게) — MIDI 55 = G3
    expect(screen.getByText("G3")).toBeInTheDocument();
  });

  it("난이도 정보가 전혀 없으면 난이도 라벨을 숨기되 나머지는 정상 노출", () => {
    const item = buildItem();
    renderWithQueryClient(
      <ul>
        <SongCard item={item} />
      </ul>,
    );
    expect(screen.queryByLabelText(/가창 난이도/)).not.toBeInTheDocument();
    // 카드 자체는 렌더됨
    expect(screen.getByText("테스트 곡")).toBeInTheDocument();
    expect(screen.getByText("C Major")).toBeInTheDocument();
  });

  // closes #91 #92 — 검색 페이지에서 song prop으로 카드 렌더 시
  // rank/score/matchReason은 숨기고 곡 정보만 노출한다.
  it("song prop만 받으면 rank/score/matchReason은 숨기고 곡 정보만 보여준다", () => {
    const item = buildItem({ lowMidi: 48, highMidi: 78 }); // HARD
    renderWithQueryClient(
      <ul>
        <SongCard song={item.song} />
      </ul>,
    );
    expect(screen.getByText("테스트 곡")).toBeInTheDocument();
    expect(screen.getByText("가수")).toBeInTheDocument();
    expect(screen.getByLabelText(/가창 난이도 Hard/)).toBeInTheDocument();
    // 추천 컨텍스트 전용 표시는 모두 숨김.
    expect(screen.queryByText(/score/)).not.toBeInTheDocument();
    expect(screen.queryByText(/음역 매칭/)).not.toBeInTheDocument();
    expect(screen.queryByText("#1")).not.toBeInTheDocument();
  });

  // closes #100 — href가 주어지면 카드 전체가 곡 상세 페이지로 가는 링크가 된다.
  it("href가 주어지면 카드 전체를 상세 페이지 링크로 감싼다", () => {
    const item = buildItem({ difficulty: "NORMAL" });
    renderWithQueryClient(
      <ul>
        <SongCard item={item} href="/songs/1" />
      </ul>,
    );
    const link = screen.getByRole("link", { name: /테스트 곡 상세 보기/ });
    expect(link).toHaveAttribute("href", "/songs/1");
    // 카드 내용은 그대로 보여야 한다.
    expect(screen.getByText("테스트 곡")).toBeInTheDocument();
    expect(screen.getByText("#1")).toBeInTheDocument();
  });

  // closes #323 — onShowDetail이 주어지면 카드 본문 클릭이 모달 트리거(button + aria-haspopup="dialog")가 되고,
  // 표면에서 score/matchReason/breakdown 패널/YouTube 링크는 숨겨져 요약 룩이 된다.
  describe("모달 모드 (closes #323)", () => {
    it("onShowDetail이 있으면 카드 본문이 button으로 감싸지고 클릭 시 콜백이 호출된다", async () => {
      const user = userEvent.setup();
      const onShowDetail = vi.fn();
      const item = buildItem({ difficulty: "HARD" });
      renderWithQueryClient(
        <ul>
          <SongCard item={item} onShowDetail={onShowDetail} />
        </ul>,
      );
      const trigger = screen.getByRole("button", { name: /테스트 곡 상세 보기/ });
      expect(trigger).toHaveAttribute("aria-haspopup", "dialog");
      await user.click(trigger);
      expect(onShowDetail).toHaveBeenCalledTimes(1);
    });

    it("모달 모드에서는 score/matchReason/breakdown 패널/YouTube 링크가 카드 표면에 노출되지 않는다", () => {
      const onShowDetail = vi.fn();
      const item = buildItem({ difficulty: "HARD", lowMidi: 55, highMidi: 77 });
      renderWithQueryClient(
        <ul>
          <SongCard
            item={item}
            userVoiceRange={{ lowMidi: 48, highMidi: 67 }}
            onShowDetail={onShowDetail}
          />
        </ul>,
      );
      // 카드 표면 핵심 정보는 그대로 보인다.
      expect(screen.getByText("테스트 곡")).toBeInTheDocument();
      expect(screen.getByText("가수")).toBeInTheDocument();
      expect(screen.getByLabelText(/가창 난이도 Hard/)).toBeInTheDocument();
      // 상세는 모달로 위임 — 카드 표면에 없어야 한다.
      expect(screen.queryByText(/score/)).not.toBeInTheDocument();
      expect(screen.queryByText(/음역 매칭/)).not.toBeInTheDocument();
      expect(
        screen.queryByRole("button", { name: /자세히 보기/ }),
      ).not.toBeInTheDocument();
      expect(
        screen.queryByRole("link", { name: /YouTube에서 듣기/ }),
      ).not.toBeInTheDocument();
    });

    it("모달 모드에서도 좋아요/북마크 액션은 카드 footer에 유지된다", () => {
      const onShowDetail = vi.fn();
      const item = buildItem({ difficulty: "NORMAL" });
      renderWithQueryClient(
        <ul>
          <SongCard item={item} onShowDetail={onShowDetail} />
        </ul>,
      );
      expect(
        screen.getByRole("button", { name: /테스트 곡 좋아요$/ }),
      ).toBeInTheDocument();
      expect(
        screen.getByRole("button", { name: /테스트 곡 북마크$/ }),
      ).toBeInTheDocument();
    });
  });

  // closes #141 — matchReason 다중 줄 + 펼침 토글 (Spotify "Why this song?" 영감).
  describe("matchReason expander (closes #141)", () => {
    it("접힘 상태에서 '자세히 보기' 버튼이 보이고 breakdown 패널은 숨겨진다", () => {
      const item = buildItem({ difficulty: "HARD", lowMidi: 55, highMidi: 77 });
      renderWithQueryClient(
        <ul>
          <SongCard item={item} userVoiceRange={{ lowMidi: 48, highMidi: 67 }} />
        </ul>,
      );
      const toggle = screen.getByRole("button", { name: /자세히 보기/ });
      expect(toggle).toHaveAttribute("aria-expanded", "false");
      // 펼침 시 노출되는 라벨이 아직 보이지 않아야 한다
      expect(screen.queryByText("키 매칭")).not.toBeInTheDocument();
    });

    it("클릭하면 breakdown 항목(키 매칭/장르/음역 적합)이 노출된다", async () => {
      const user = userEvent.setup();
      const item = buildItem({ difficulty: "HARD", lowMidi: 55, highMidi: 77 });
      renderWithQueryClient(
        <ul>
          <SongCard item={item} userVoiceRange={{ lowMidi: 48, highMidi: 67 }} />
        </ul>,
      );
      await user.click(screen.getByRole("button", { name: /자세히 보기/ }));

      expect(screen.getByText("키 매칭")).toBeInTheDocument();
      expect(screen.getByText("장르")).toBeInTheDocument();
      expect(screen.getByText("음역 적합")).toBeInTheDocument();
      // 음역 적합 detail에 사용자/곡 음역이 함께 표시
      expect(
        screen.getByText("사용자 C3-G4 vs 곡 G3-F5"),
      ).toBeInTheDocument();
      // 추정값 안내 footnote
      expect(
        screen.getByText(/클라이언트 추정값입니다/),
      ).toBeInTheDocument();
    });

    it("토글 클릭으로 aria-expanded가 false ↔ true 사이를 오간다", async () => {
      const user = userEvent.setup();
      const item = buildItem({ difficulty: "NORMAL" });
      renderWithQueryClient(
        <ul>
          <SongCard item={item} />
        </ul>,
      );
      const toggle = screen.getByRole("button", { name: /자세히 보기/ });
      expect(toggle).toHaveAttribute("aria-expanded", "false");

      await user.click(toggle);
      const expanded = screen.getByRole("button", { name: /접기/ });
      expect(expanded).toHaveAttribute("aria-expanded", "true");

      await user.click(expanded);
      const collapsed = screen.getByRole("button", { name: /자세히 보기/ });
      expect(collapsed).toHaveAttribute("aria-expanded", "false");
    });
  });

  // closes #176 — 좋아요 토글. closes #184 — BE 연동 mutation flow.
  describe("좋아요 토글 (closes #176 + #184)", () => {
    it("버튼 클릭 시 낙관적으로 store가 즉시 갱신되고 BE mutation이 호출된다", async () => {
      const user = userEvent.setup();
      toggleLikeMock.mockResolvedValue({ liked: true, songId: 1 });
      const item = buildItem({ difficulty: "EASY" }); // song.id = 1
      renderWithQueryClient(
        <ul>
          <SongCard item={item} />
        </ul>,
      );

      const button = screen.getByRole("button", { name: /테스트 곡 좋아요$/ });
      expect(button).toHaveAttribute("aria-pressed", "false");
      expect(useLikesStore.getState().likedSongIds).toEqual([]);

      await user.click(button);

      // 낙관적 UI — BE 응답 대기 없이 토글됨.
      const toggled = screen.getByRole("button", {
        name: /테스트 곡 좋아요 취소/,
      });
      expect(toggled).toHaveAttribute("aria-pressed", "true");
      expect(useLikesStore.getState().likedSongIds).toEqual([1]);

      // BE 호출 검증 — sessionId+songId 전달.
      await waitFor(() => {
        expect(toggleLikeMock).toHaveBeenCalledWith({
          sessionId: "test-session-id",
          songId: 1,
        });
      });

      // 두 번째 클릭은 BE가 liked=false 응답을 줘서 낙관값과 일치 — store 비어있어야.
      toggleLikeMock.mockResolvedValueOnce({ liked: false, songId: 1 });
      await user.click(toggled);
      await waitFor(() => {
        expect(useLikesStore.getState().likedSongIds).toEqual([]);
      });
    });

    it("BE mutation 실패 시 낙관적 변경을 롤백한다", async () => {
      const user = userEvent.setup();
      toggleLikeMock.mockRejectedValueOnce(new Error("network down"));
      const item = buildItem({ difficulty: "EASY" });
      renderWithQueryClient(
        <ul>
          <SongCard item={item} />
        </ul>,
      );

      const button = screen.getByRole("button", { name: /테스트 곡 좋아요$/ });
      await user.click(button);

      // 실패 후 store는 원상복귀.
      await waitFor(() => {
        expect(useLikesStore.getState().likedSongIds).toEqual([]);
      });
      // 버튼 라벨도 원상복귀.
      expect(
        screen.getByRole("button", { name: /테스트 곡 좋아요$/ }),
      ).toHaveAttribute("aria-pressed", "false");
    });

    // closes #257 — 실패 시 카드 내 인라인 안내 (role="alert") 가 노출되고,
    // 재시도(다시 클릭) 시 즉시 제거된다.
    it("BE mutation 실패 시 인라인 alert 메시지가 노출된다", async () => {
      const user = userEvent.setup();
      toggleLikeMock.mockRejectedValueOnce(new Error("network down"));
      const item = buildItem({ difficulty: "EASY" });
      renderWithQueryClient(
        <ul>
          <SongCard item={item} />
        </ul>,
      );

      const button = screen.getByRole("button", { name: /테스트 곡 좋아요$/ });
      await user.click(button);

      const alert = await screen.findByRole("alert");
      expect(alert).toHaveTextContent(/좋아요 처리에 실패했어요/);
    });

    it("재시도 클릭 시 이전 실패 안내가 즉시 사라진다", async () => {
      const user = userEvent.setup();
      toggleLikeMock.mockRejectedValueOnce(new Error("network down"));
      // 두 번째 시도는 성공으로 둬서 mutation 진행 중에도 alert가 즉시 사라지는지 검증.
      toggleLikeMock.mockResolvedValueOnce({ liked: true, songId: 1 });
      const item = buildItem({ difficulty: "EASY" });
      renderWithQueryClient(
        <ul>
          <SongCard item={item} />
        </ul>,
      );

      const button = screen.getByRole("button", { name: /테스트 곡 좋아요$/ });
      await user.click(button);
      await screen.findByRole("alert");

      // 재시도.
      await user.click(button);
      await waitFor(() => {
        expect(screen.queryByRole("alert")).not.toBeInTheDocument();
      });
    });
  });

  // closes #184 — 북마크 토글.
  describe("북마크 토글 (closes #184)", () => {
    it("버튼 클릭 시 북마크 store가 갱신되고 BE mutation이 호출된다", async () => {
      const user = userEvent.setup();
      toggleBookmarkMock.mockResolvedValue({ bookmarked: true, songId: 1 });
      const item = buildItem({ difficulty: "NORMAL" });
      renderWithQueryClient(
        <ul>
          <SongCard item={item} />
        </ul>,
      );

      const button = screen.getByRole("button", {
        name: /테스트 곡 북마크$/,
      });
      expect(button).toHaveAttribute("aria-pressed", "false");
      expect(useBookmarksStore.getState().bookmarkedSongIds).toEqual([]);

      await user.click(button);

      const toggled = screen.getByRole("button", {
        name: /테스트 곡 북마크 해제/,
      });
      expect(toggled).toHaveAttribute("aria-pressed", "true");
      expect(useBookmarksStore.getState().bookmarkedSongIds).toEqual([1]);

      await waitFor(() => {
        expect(toggleBookmarkMock).toHaveBeenCalledWith({
          sessionId: "test-session-id",
          songId: 1,
        });
      });
    });
  });

  // closes #302 — YouTube 검색 링크 (미리듣기 1단계). ADR-0006 범위 밖, BE 변경 없음.
  describe("YouTube 검색 링크 (closes #302)", () => {
    it("추천 카드에 'YouTube에서 듣기' 링크가 노출되고 새 탭으로 열린다", () => {
      const item = buildItem({ difficulty: "NORMAL" });
      renderWithQueryClient(
        <ul>
          <SongCard item={item} />
        </ul>,
      );
      const link = screen.getByRole("link", {
        name: /테스트 곡 YouTube에서 듣기 \(새 탭\)/,
      });
      expect(link).toHaveAttribute("target", "_blank");
      expect(link).toHaveAttribute("rel", "noopener noreferrer");
      // 검색 URL은 곡 제목 + 아티스트가 search_query로 인코딩되어야 한다.
      const href = link.getAttribute("href") ?? "";
      expect(href).toMatch(/^https:\/\/www\.youtube\.com\/results\?/);
      expect(href).toContain("search_query=");
    });

    it("검색 컨텍스트(song prop) 카드에도 동일하게 노출된다", () => {
      const item = buildItem({ difficulty: "EASY" });
      renderWithQueryClient(
        <ul>
          <SongCard song={item.song} />
        </ul>,
      );
      expect(
        screen.getByRole("link", {
          name: /테스트 곡 YouTube에서 듣기 \(새 탭\)/,
        }),
      ).toBeInTheDocument();
    });

    it("href 모드(상세 페이지 링크 카드)에서도 YouTube 링크가 부모 링크 외부에 있어 분리된다", () => {
      const item = buildItem({ difficulty: "HARD" });
      renderWithQueryClient(
        <ul>
          <SongCard item={item} href="/songs/1" />
        </ul>,
      );
      const detailLink = screen.getByRole("link", {
        name: /테스트 곡 상세 보기/,
      });
      const youtubeLink = screen.getByRole("link", {
        name: /테스트 곡 YouTube에서 듣기 \(새 탭\)/,
      });
      expect(detailLink).toHaveAttribute("href", "/songs/1");
      // 별도 링크여야 한다 (중첩되지 않음).
      expect(detailLink).not.toContainElement(youtubeLink);
    });

    it("buildYouTubeSearchUrl: 한글 제목/아티스트도 안전하게 인코딩한다", () => {
      const url = buildYouTubeSearchUrl("밤편지", "아이유");
      expect(url).toBe(
        "https://www.youtube.com/results?search_query=%EB%B0%A4%ED%8E%B8%EC%A7%80+%EC%95%84%EC%9D%B4%EC%9C%A0",
      );
    });

    it("buildYouTubeSearchUrl: 특수문자(앰퍼샌드 등)도 안전하게 인코딩한다", () => {
      const url = buildYouTubeSearchUrl("Me & You", "Artist?");
      expect(url).toContain("search_query=Me+%26+You+Artist%3F");
    });
  });

  // closes #107 — axe-core 자동 검사. serious/critical 위반이 없어야 한다.
  // SongCard는 다양한 prop 조합으로 렌더되므로 대표 케이스 4종을 모두 검사.
  describe("a11y", () => {
    it("추천 컨텍스트 카드는 a11y 위반이 없다 (item + difficulty)", async () => {
      const item = buildItem({ difficulty: "HARD", lowMidi: 55, highMidi: 77 });
      const { container } = renderWithQueryClient(
        <ul>
          <SongCard item={item} />
        </ul>,
      );
      await expectNoA11yViolations(container);
    });

    it("검색 컨텍스트 카드는 a11y 위반이 없다 (song prop)", async () => {
      const item = buildItem({ difficulty: "NORMAL" });
      const { container } = renderWithQueryClient(
        <ul>
          <SongCard song={item.song} />
        </ul>,
      );
      await expectNoA11yViolations(container);
    });

    it("href 링크 카드는 a11y 위반이 없다", async () => {
      const item = buildItem({ difficulty: "EASY" });
      const { container } = renderWithQueryClient(
        <ul>
          <SongCard item={item} href="/songs/1" />
        </ul>,
      );
      await expectNoA11yViolations(container);
    });

    it("난이도 정보가 없는 카드도 a11y 위반이 없다", async () => {
      const item = buildItem();
      const { container } = renderWithQueryClient(
        <ul>
          <SongCard item={item} />
        </ul>,
      );
      await expectNoA11yViolations(container);
    });
  });
});
