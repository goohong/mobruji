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
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import { SongCard, buildYouTubeSearchUrl, getSongHue } from "./SongCard";
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
    sessionId: "00000000-0000-4000-8000-000000000001",
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
      screen.getByLabelText(/가창 난이도 어려움/),
    ).toBeInTheDocument();
    expect(screen.getByText("테스트 곡")).toBeInTheDocument();
    expect(screen.getByText("가수")).toBeInTheDocument();
    expect(screen.getByText("POP")).toBeInTheDocument();
    // closes #1719 — score 원값은 카드 표면에 노출하지 않는다.
    expect(screen.queryByText(/score/)).not.toBeInTheDocument();
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
      screen.getByLabelText(/가창 난이도 어려움/),
    ).toBeInTheDocument();
    // 최고음 음표명 노출 — MIDI 77 = 파5 (한국어 단독, #1310 사용자 정정 2026-06-03)
    expect(screen.getByLabelText(/최고음 파5/)).toBeInTheDocument();
    // 최저음(작게) — MIDI 55 = 솔3
    expect(screen.getByText("솔3")).toBeInTheDocument();
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
    expect(screen.getByText("C 장조")).toBeInTheDocument();
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
    expect(screen.getByLabelText(/가창 난이도 어려움/)).toBeInTheDocument();
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
      expect(screen.getByLabelText(/가창 난이도 어려움/)).toBeInTheDocument();
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

    // 회귀 가드 (closes #449) — 키보드 사용자가 trigger button에 포커스 후
    // Enter / Space 로 모달을 활성화할 수 있어야 한다. 향후 trigger 구조가
    // 비표준 wrapper (`<div onClick>` 등) 로 바뀌면 키보드 활성화가 끊겨도
    // click test 만으로는 회귀가 잡히지 않는다.
    it("회귀 가드: trigger button에 Tab 포커스 후 Enter 로 onShowDetail 호출", async () => {
      const user = userEvent.setup();
      const onShowDetail = vi.fn();
      const item = buildItem({ difficulty: "HARD" });
      renderWithQueryClient(
        <ul>
          <SongCard item={item} onShowDetail={onShowDetail} />
        </ul>,
      );
      const trigger = screen.getByRole("button", { name: /테스트 곡 상세 보기/ });
      trigger.focus();
      expect(trigger).toHaveFocus();
      await user.keyboard("{Enter}");
      expect(onShowDetail).toHaveBeenCalledTimes(1);
    });

    it("회귀 가드: trigger button에 포커스 후 Space 로 onShowDetail 호출", async () => {
      const user = userEvent.setup();
      const onShowDetail = vi.fn();
      const item = buildItem({ difficulty: "HARD" });
      renderWithQueryClient(
        <ul>
          <SongCard item={item} onShowDetail={onShowDetail} />
        </ul>,
      );
      const trigger = screen.getByRole("button", { name: /테스트 곡 상세 보기/ });
      trigger.focus();
      // userEvent Space는 keydown(Space) + keyup(Space) 로 분해되며 button 의
      // 표준 동작에 따라 click 이 발생한다.
      await user.keyboard(" ");
      expect(onShowDetail).toHaveBeenCalledTimes(1);
    });

    // 회귀 가드 (closes #449) — LikeButton onClick 의 stopPropagation 이 사라지거나
    // 향후 trigger 안에 좋아요/북마크 button 을 중첩(HTML 위반)으로 옮기면 좋아요
    // 클릭이 모달을 동시에 띄우는 회귀가 생긴다. 구조적 약속을 명문화.
    it("회귀 가드: 좋아요 버튼 클릭이 onShowDetail 을 트리거하지 않는다 (이벤트 격리)", async () => {
      const user = userEvent.setup();
      const onShowDetail = vi.fn();
      const item = buildItem({ difficulty: "EASY" });
      renderWithQueryClient(
        <ul>
          <SongCard item={item} onShowDetail={onShowDetail} />
        </ul>,
      );
      const likeButton = screen.getByRole("button", {
        name: /테스트 곡 좋아요$/,
      });
      await user.click(likeButton);
      // 좋아요 mutation 은 진행되지만 모달 trigger 콜백은 호출되지 않아야 한다.
      expect(onShowDetail).not.toHaveBeenCalled();
    });
  });

  // 회귀 가드 (closes #449) — href mode 에서 키보드로 detail Link 활성화.
  // next/link 의 <a> 는 Enter 표준 동작으로 navigate 한다 (Space 는 link 표준 아님).
  // jsdom 환경에서는 실제 navigate 가 일어나지 않으므로 click 이벤트 발생을 검증한다.
  describe("href mode 키보드 nav 회귀 가드 (closes #449)", () => {
    it("Link 에 포커스 후 Enter 로 click 이벤트가 발생한다", async () => {
      const user = userEvent.setup();
      const item = buildItem({ difficulty: "NORMAL" });
      renderWithQueryClient(
        <ul>
          <SongCard item={item} href="/songs/1" />
        </ul>,
      );
      const link = screen.getByRole("link", { name: /테스트 곡 상세 보기/ });
      const clickHandler = vi.fn((event: Event) => {
        // jsdom 에서 navigate 시도 막기 — 회귀 가드 목적은 click 발화 여부 확인.
        event.preventDefault();
      });
      link.addEventListener("click", clickHandler);
      link.focus();
      expect(link).toHaveFocus();
      await user.keyboard("{Enter}");
      expect(clickHandler).toHaveBeenCalled();
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
      // 음역 적합 detail에 사용자/곡 음역이 함께 표시 (한국어 단독, #1310 사용자 정정 2026-06-03)
      expect(
        screen.getByText(
          "사용자 도3-솔4 vs 곡 솔3-파5",
        ),
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

    // closes #542 — aria-controls 값이 펼침 패널 id와 정확히 매칭되어야 한다.
    it("토글 aria-controls가 펼침 패널 id와 일치한다 (useId 회귀 가드)", async () => {
      const user = userEvent.setup();
      const item = buildItem({ difficulty: "NORMAL" });
      renderWithQueryClient(
        <ul>
          <SongCard item={item} />
        </ul>,
      );
      const toggle = screen.getByRole("button", { name: /자세히 보기/ });
      const controlsId = toggle.getAttribute("aria-controls");
      expect(controlsId).toBeTruthy();
      await user.click(toggle);
      const panel = document.getElementById(controlsId as string);
      expect(panel).not.toBeNull();
      expect(panel).toHaveTextContent("키 매칭");
    });

    // closes #549 — Enter/Space 키보드 활성화 회귀 가드. <button> 네이티브 동작이
    // 향후 <div role="button"> 등으로 바뀌어도 키보드 토글이 깨지지 않도록 고정한다.
    it("토글 focus 후 Enter/Space는 aria-expanded를 토글하고, 다른 키는 no-op", async () => {
      const user = userEvent.setup();
      const item = buildItem({ difficulty: "NORMAL" });
      renderWithQueryClient(
        <ul>
          <SongCard item={item} />
        </ul>,
      );
      const toggle = screen.getByRole("button", { name: /자세히 보기/ });
      toggle.focus();
      expect(toggle).toHaveFocus();

      await user.keyboard("{Enter}");
      expect(
        screen.getByRole("button", { name: /접기/ }),
      ).toHaveAttribute("aria-expanded", "true");

      await user.keyboard(" ");
      expect(
        screen.getByRole("button", { name: /자세히 보기/ }),
      ).toHaveAttribute("aria-expanded", "false");

      await user.keyboard("a");
      expect(
        screen.getByRole("button", { name: /자세히 보기/ }),
      ).toHaveAttribute("aria-expanded", "false");
    });

    // closes #542 — 카드 여러 개 렌더 시 panelId가 카드 간 충돌하지 않아야 한다.
    it("카드 다수 렌더 시 각 토글 aria-controls가 unique하다", () => {
      renderWithQueryClient(
        <ul>
          <SongCard item={buildItem({}, { rankPosition: 1 })} />
          <SongCard item={buildItem({}, { rankPosition: 2 })} />
        </ul>,
      );
      const controlsIds = screen
        .getAllByRole("button", { name: /자세히 보기/ })
        .map((toggle) => toggle.getAttribute("aria-controls"));
      expect(controlsIds).toHaveLength(2);
      expect(new Set(controlsIds).size).toBe(controlsIds.length);
    });
  });

  // closes #1683 — 좌측 gradient stripe + stagger fade-in 진입.
  describe("gradient stripe + stagger 진입 (#1683)", () => {
    it("li 에 stagger 클래스와 --card-index / --song-hue 인라인 변수가 부여된다", () => {
      const item = buildItem({ difficulty: "NORMAL" });
      renderWithQueryClient(
        <ul>
          <SongCard item={item} index={3} />
        </ul>,
      );
      const card = screen.getByRole("listitem");
      expect(card).toHaveClass("animate-card-enter");
      expect(card.style.getPropertyValue("--card-index")).toBe("3");
      // hue 는 getSongHue(song.id) 결정값과 일치해야 한다.
      expect(card.style.getPropertyValue("--song-hue")).toBe(
        String(getSongHue(item.song.id)),
      );
    });

    it("index 미지정 시 --card-index 는 0 으로 떨어진다 (단일 카드 즉시 진입)", () => {
      const item = buildItem({ difficulty: "EASY" });
      renderWithQueryClient(
        <ul>
          <SongCard item={item} />
        </ul>,
      );
      const card = screen.getByRole("listitem");
      expect(card.style.getPropertyValue("--card-index")).toBe("0");
    });

    it("좌측 accent stripe(.song-accent-stripe)가 aria-hidden 으로 렌더된다", () => {
      const item = buildItem({ difficulty: "HARD" });
      const { container } = renderWithQueryClient(
        <ul>
          <SongCard item={item} index={1} />
        </ul>,
      );
      const stripe = container.querySelector(".song-accent-stripe");
      expect(stripe).not.toBeNull();
      expect(stripe).toHaveAttribute("aria-hidden", "true");
    });

    it("href / 모달 모드에서도 stripe 와 stagger 변수가 유지된다", () => {
      const item = buildItem({ difficulty: "NORMAL" });
      const { container, rerender } = renderWithQueryClient(
        <ul>
          <SongCard item={item} index={2} href="/songs/1" />
        </ul>,
      );
      let card = screen.getByRole("listitem");
      expect(card).toHaveClass("animate-card-enter");
      expect(card.style.getPropertyValue("--card-index")).toBe("2");
      expect(container.querySelector(".song-accent-stripe")).not.toBeNull();

      rerender(
        <ul>
          <SongCard item={item} index={5} onShowDetail={() => {}} />
        </ul>,
      );
      card = screen.getByRole("listitem");
      expect(card).toHaveClass("animate-card-enter");
      expect(card.style.getPropertyValue("--card-index")).toBe("5");
      expect(container.querySelector(".song-accent-stripe")).not.toBeNull();
    });
  });

  // closes #1721 — 검색 카드(/songs)에 "내 음역 적합" 배지 + 한 줄 사유 + 적합도 보더.
  describe("검색 카드 음역 적합 표시 (#1721)", () => {
    it("voiceFit 가 주어지면 '내 음역 적합' 배지와 한 줄 사유를 노출한다", () => {
      const { song } = buildItem({ lowMidi: 55, highMidi: 67 });
      renderWithQueryClient(
        <ul>
          <SongCard song={song} voiceFit={0.85} />
        </ul>,
      );
      expect(screen.getByLabelText(/내 음역 적합 85%/)).toBeInTheDocument();
      expect(
        screen.getByText("내 음역대에 잘 맞아 편하게 부를 수 있어요."),
      ).toBeInTheDocument();
    });

    it("voiceFit 미지정 검색 카드는 배지/사유 없이 hue 보더만 유지한다", () => {
      const { song } = buildItem();
      const { container } = renderWithQueryClient(
        <ul>
          <SongCard song={song} />
        </ul>,
      );
      expect(screen.queryByLabelText(/내 음역 적합/)).not.toBeInTheDocument();
      const stripe = container.querySelector(".song-accent-stripe");
      expect(stripe).not.toBeNull();
      expect(stripe).not.toHaveAttribute("data-fit");
    });

    it("적합도 레벨이 좌측 보더 data-fit 으로 매핑된다 (high=green/mid=amber/low=neutral)", () => {
      const { song } = buildItem({ lowMidi: 55, highMidi: 67 });
      const high = renderWithQueryClient(
        <ul>
          <SongCard song={song} voiceFit={0.9} />
        </ul>,
      );
      expect(
        high.container.querySelector(".song-accent-stripe"),
      ).toHaveAttribute("data-fit", "high");
      cleanup();

      const mid = renderWithQueryClient(
        <ul>
          <SongCard song={song} voiceFit={0.5} />
        </ul>,
      );
      expect(mid.container.querySelector(".song-accent-stripe")).toHaveAttribute(
        "data-fit",
        "mid",
      );
      cleanup();

      const low = renderWithQueryClient(
        <ul>
          <SongCard song={song} voiceFit={0.1} />
        </ul>,
      );
      expect(low.container.querySelector(".song-accent-stripe")).toHaveAttribute(
        "data-fit",
        "low",
      );
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
          sessionId: "00000000-0000-4000-8000-000000000001",
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

    // closes #486 — /songs, /songs/[id] 페이지의 alert 패턴과 일관성 유지.
    // role="alert" 단독은 일부 SR 환경에서 즉시 announce 되지 않을 수 있어
    // aria-live="assertive" 를 함께 명시한다. 회귀 가드.
    it("실패 안내 alert에 aria-live=\"assertive\" 가 부여된다", async () => {
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
      expect(alert).toHaveAttribute("aria-live", "assertive");
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
          sessionId: "00000000-0000-4000-8000-000000000001",
          songId: 1,
        });
      });
    });

    // closes #521 — 회귀 가드: 북마크 mutation 실패 시 낙관 변경 원복.
    // 좋아요 쪽에는 같은 가드가 있었으나 북마크에는 alert aria-live 만 검증되어 있었다.
    // onMutate/onError 양쪽이 toggle 을 짝맞춰 부르는 패턴이 깨지면 store/UI 가 잘못된 상태로 굳는다.
    it("BE mutation 실패 시 북마크 낙관적 변경을 롤백한다", async () => {
      const user = userEvent.setup();
      toggleBookmarkMock.mockRejectedValueOnce(new Error("network down"));
      const item = buildItem({ difficulty: "NORMAL" });
      renderWithQueryClient(
        <ul>
          <SongCard item={item} />
        </ul>,
      );

      const button = screen.getByRole("button", { name: /테스트 곡 북마크$/ });
      await user.click(button);

      // 실패 후 store/aria-pressed 모두 원상복귀.
      await waitFor(() => {
        expect(useBookmarksStore.getState().bookmarkedSongIds).toEqual([]);
      });
      expect(
        screen.getByRole("button", { name: /테스트 곡 북마크$/ }),
      ).toHaveAttribute("aria-pressed", "false");
    });

    // closes #521 — 회귀 가드: 이미 bookmarked=true 상태에서 토글(해제) 실패.
    it("이미 bookmarked=true 상태에서 토글 실패 시 다시 bookmarked=true 로 복원된다", async () => {
      const user = userEvent.setup();
      useBookmarksStore.setState({ bookmarkedSongIds: [1] });
      toggleBookmarkMock.mockRejectedValueOnce(new Error("network down"));
      const item = buildItem({ difficulty: "NORMAL" });
      renderWithQueryClient(
        <ul>
          <SongCard item={item} />
        </ul>,
      );

      const button = screen.getByRole("button", {
        name: /테스트 곡 북마크 해제/,
      });
      expect(button).toHaveAttribute("aria-pressed", "true");
      await user.click(button);

      await waitFor(() => {
        expect(useBookmarksStore.getState().bookmarkedSongIds).toEqual([1]);
      });
      expect(
        screen.getByRole("button", { name: /테스트 곡 북마크 해제/ }),
      ).toHaveAttribute("aria-pressed", "true");
    });

    // closes #486 — BookmarkButton 실패 안내도 LikeButton 과 동일하게
    // aria-live="assertive" 를 부여한다. 회귀 가드.
    it("BE mutation 실패 시 alert에 aria-live=\"assertive\" 가 부여된다", async () => {
      const user = userEvent.setup();
      toggleBookmarkMock.mockRejectedValueOnce(new Error("network down"));
      const item = buildItem({ difficulty: "NORMAL" });
      renderWithQueryClient(
        <ul>
          <SongCard item={item} />
        </ul>,
      );

      const button = screen.getByRole("button", {
        name: /테스트 곡 북마크$/,
      });
      await user.click(button);

      const alert = await screen.findByRole("alert");
      expect(alert).toHaveAttribute("aria-live", "assertive");
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
      // 직전 PR (#1310) 무차별 한국어 치환이 percent-encoding hex (%A4 → %라4 등)
      // 까지 깨먹은 회귀 복구. percent-encoded 바이트는 한국어 음명 치환과 무관.
      expect(url).toBe(
        "https://www.youtube.com/results?search_query=%EB%B0%A4%ED%8E%B8%EC%A7%80+%EC%95%84%EC%9D%B4%EC%9C%A0",
      );
    });

    it("buildYouTubeSearchUrl: 특수문자(앰퍼샌드 등)도 안전하게 인코딩한다", () => {
      const url = buildYouTubeSearchUrl("Me & You", "Artist?");
      expect(url).toContain("search_query=Me+%26+You+Artist%3F");
    });

    // closes #505 — 회귀 가드: 빈 입력은 search_query 자체를 비워야 한다.
    it("buildYouTubeSearchUrl: 빈 입력은 빈 search_query를 반환한다", () => {
      const url = buildYouTubeSearchUrl("", "");
      const params = new URL(url).searchParams;
      expect(params.get("search_query")).toBe("");
    });

    // closes #505 — 회귀 가드: 파라미터 round-trip이 정확해야 한다 (# 같은 fragment 문자 포함).
    it("buildYouTubeSearchUrl: search_query는 원본 문자열로 round-trip 디코딩된다", () => {
      const url = buildYouTubeSearchUrl("C#", "Song/Artist");
      const params = new URL(url).searchParams;
      expect(params.get("search_query")).toBe("C# Song/Artist");
    });

    // closes #539 — SongDetailContent 의 PR #537 (closes #535) 짝. SongCard 의
    // YouTubeSearchLink 도 새 탭으로 외부 사이트(youtube.com) 를 열기 때문에 reverse
    // tabnabbing 방지를 위해 rel="noopener noreferrer" 가 target="_blank" 와 항상
    // 함께 있어야 한다. 위 #302 케이스가 동등 검증을 포함하지만, 의도 코멘트가 있는
    // 별도 가드 it 으로 검색/짝 추적성을 명시한다.
    it("회귀 가드(#539): target=_blank 와 rel=noopener noreferrer 를 함께 가진다", () => {
      const item = buildItem({ difficulty: "NORMAL" });
      renderWithQueryClient(
        <ul>
          <SongCard item={item} />
        </ul>,
      );
      const link = screen.getByRole("link", {
        name: /테스트 곡 YouTube에서 듣기/,
      });
      expect(link).toHaveAttribute("target", "_blank");
      expect(link).toHaveAttribute("rel", "noopener noreferrer");
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

  // closes #502 — SongCard 가 AlbumCoverThumbnail 을 실제로 마운트하고 song.albumCoverUrl
  // 을 pass-through 하는지 회귀 가드. Thumbnail 단독 동작은 AlbumCover.test.tsx 가
  // 검증하지만, SongCard 내부 import/prop 배선이 끊기면 그쪽 테스트는 통과해도 카드
  // 표면에서 thumbnail 이 사라지므로 별도 통합 가드를 둔다.
  describe("AlbumCoverThumbnail 통합 (closes #502)", () => {
    it("albumCoverUrl 가 string 이면 카드 안에 <img src> 가 pass-through 된다", () => {
      const item = buildItem({ albumCoverUrl: "https://example.com/cover.jpg" });
      renderWithQueryClient(<SongCard item={item} />);
      const img = screen.getByAltText("테스트 곡 앨범 커버") as HTMLImageElement;
      expect(img.getAttribute("src")).toBe("https://example.com/cover.jpg");
      expect(img.getAttribute("loading")).toBe("lazy");
    });

    it("albumCoverUrl 가 null 이면 카드 안에서 placeholder 로 fallback", () => {
      const item = buildItem({ albumCoverUrl: null });
      renderWithQueryClient(<SongCard item={item} />);
      expect(
        screen.getByLabelText(/테스트 곡 앨범 커버 \(이미지 없음\)/),
      ).toBeInTheDocument();
    });

    it("img onError → 카드 안에서 placeholder 로 fallback (#322 + #499 통합)", () => {
      const item = buildItem({ albumCoverUrl: "https://example.com/404.jpg" });
      renderWithQueryClient(<SongCard item={item} />);
      const img = screen.getByAltText("테스트 곡 앨범 커버");
      fireEvent.error(img);
      expect(
        screen.getByLabelText(/테스트 곡 앨범 커버 \(이미지 없음\)/),
      ).toBeInTheDocument();
    });
  });

  // 이슈 #1600 — P-E 안전곡 등 페르소나 사유("안심 포인트") 노출.
  describe("페르소나 사유 (#1600)", () => {
    it("BE personaReason 이 있으면 '안심 포인트' 라벨과 함께 노출한다", () => {
      const item = buildItem(
        { difficulty: "EASY" },
        { persona: "P-E", personaReason: "느린 템포라 따라 부르기 쉬워요." },
      );
      renderWithQueryClient(
        <ul>
          <SongCard item={item} activePersona="P-E" />
        </ul>,
      );
      expect(screen.getByText("안심 포인트")).toBeInTheDocument();
      expect(
        screen.getByText("느린 템포라 따라 부르기 쉬워요."),
      ).toBeInTheDocument();
    });

    it("BE personaReason 이 없어도 활성 P-E + EASY 곡이면 client fallback 사유를 노출한다", () => {
      const item = buildItem({ difficulty: "EASY" });
      renderWithQueryClient(
        <ul>
          <SongCard item={item} activePersona="P-E" />
        </ul>,
      );
      expect(screen.getByText("안심 포인트")).toBeInTheDocument();
      expect(screen.getByText(/부담 없이/)).toBeInTheDocument();
    });

    it("의도 모드 미선택(activePersona=null)이면 페르소나 사유 줄을 그리지 않는다", () => {
      const item = buildItem({ difficulty: "EASY" });
      renderWithQueryClient(
        <ul>
          <SongCard item={item} />
        </ul>,
      );
      expect(screen.queryByText("안심 포인트")).not.toBeInTheDocument();
    });
  });

  describe("hero morph view-transition-name (closes #1687, PR7)", () => {
    it("href 모드(라우트 이동)에서는 thumbnail 에 album-{id} 이름이 붙는다", () => {
      const item = buildItem();
      renderWithQueryClient(
        <ul>
          <SongCard item={item} href="/songs/1" />
        </ul>,
      );
      const cover = screen.getByLabelText(/테스트 곡 앨범 커버 \(이미지 없음\)/);
      expect((cover as HTMLElement).style.viewTransitionName).toBe("album-1");
    });

    it("모달 모드에서는 thumbnail 에 view-transition-name 을 붙이지 않는다", () => {
      const item = buildItem();
      renderWithQueryClient(
        <ul>
          <SongCard item={item} onShowDetail={() => {}} />
        </ul>,
      );
      const cover = screen.getByLabelText(/테스트 곡 앨범 커버 \(이미지 없음\)/);
      expect((cover as HTMLElement).style.viewTransitionName).toBeFalsy();
    });

    it("plain 모드(href/모달 모두 없음)에서도 이름을 붙이지 않는다", () => {
      const item = buildItem();
      renderWithQueryClient(
        <ul>
          <SongCard item={item} />
        </ul>,
      );
      const cover = screen.getByLabelText(/테스트 곡 앨범 커버 \(이미지 없음\)/);
      expect((cover as HTMLElement).style.viewTransitionName).toBeFalsy();
    });
  });
});
