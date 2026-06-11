"use client";

/**
 * 곡 검색 페이지 (`/songs`).
 *
 * 이슈 #91 #92 / PR #93:
 *   - 사용자가 제목/아티스트로 곡을 찾을 수 있게 한다.
 *   - 음역대 입력 없이도 카탈로그를 둘러볼 수 있는 진입점이다 (랜딩 CTA로도 노출).
 *   - 입력은 300ms 디바운스 → `GET /api/v1/songs?keyword=...` 호출.
 *   - 결과는 추천 결과와 동일한 카드 룩(SongCard)을 사용해 난이도/최고음/장르/키를 보여준다.
 *
 * BE 약속(`SongService.searchByKeyword`):
 *   - keyword가 비어 있으면 빈 배열을 돌려준다 → 검색어 입력 전 호출은 생략.
 *   - 부분 일치(제목/아티스트). 정렬은 BE 결정에 위임.
 *
 * 이슈 #118 / PR #119 — 장르·난이도 client-side 필터:
 *   - 응답에 있는 unique genre들을 동적으로 chip 옵션으로 노출.
 *   - 난이도는 EASY/NORMAL/HARD 고정 옵션. (응답에 difficulty 없으면 lowMidi/highMidi로 파생.)
 *   - 두 필터 모두 multi-select. 같은 그룹 내는 OR, 그룹 사이는 AND.
 *   - 활성 필터 0개 = 전체. "필터 초기화" 버튼으로 한 번에 해제.
 *   - 필터 상태는 URL query(`?keyword=&genre=POP,ROCK&difficulty=EASY,HARD`)에 동기화한다.
 *     `router.replace`라 히스토리는 더럽히지 않고, 새로고침/공유 시 그대로 복원된다.
 *   - 결과 카운트는 "필터 결과 N곡 / 전체 M곡"로 노출 (M==N이면 N곡만).
 */

import { Suspense, useCallback, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";

import { ApiError } from "@/lib/api/client";
import {
  searchSongs,
  type SongListResponse,
  type SongResponse,
} from "@/lib/api/song";
import { deriveDifficulty, type Difficulty } from "@/lib/difficulty";
import { formatSongDisplayTitle } from "@/lib/songTitle";
import { Chip, Input } from "@/components/ui";

import { SongCard } from "../recommend/components/SongCard";
import { SongDetailModal } from "../recommend/components/SongDetailModal";
import { SongDetailContent } from "../recommend/components/SongDetailContent";

const DEBOUNCE_MS = 300;
const DIFFICULTY_OPTIONS: { key: Difficulty; label: string }[] = [
  { key: "EASY", label: "Easy" },
  { key: "NORMAL", label: "Normal" },
  { key: "HARD", label: "Hard" },
];

/*
 * ADR-0018 단계 4 — /songs 페이지 토큰 swap (#1044 후속).
 *
 * swap 한 요소 (likes/bookmarks PR 7 동일 패턴):
 *  1) <main> 배경 + padding : bg-zinc-50 dark:bg-zinc-950 + px-6 py-12
 *     → --bg-subtle / --page-padding-x / --page-padding-y
 *     (SearchPageFallback + SongSearchPageInner 본문 둘 다 동일 매핑)
 *  2) h1 : text-zinc-900 dark:text-zinc-50 → --text-primary
 *  3) 부제 / 보조 텍스트 : text-zinc-600 dark:text-zinc-400 → --text-secondary
 *     (header 부제 + SearchResult empty rawCount p + FilterPanel "필터 초기화"
 *     버튼 셋이 동일 매핑)
 *  4) 검색 전 empty surface : border-dashed border-zinc-300 bg-white +
 *     dark:border-zinc-700 dark:bg-zinc-900 → --cta-secondary-border /
 *     --cta-secondary-bg (PR 11 도입 토큰 재사용 — light white→dark zinc-900
 *     매핑이 정확) + rounded-2xl → --radius-lg
 *  5) empty surface 본문 텍스트 : text-zinc-700 dark:text-zinc-300 → --text-label
 *     (Input label 토큰 재사용 — light/dark 같은 단계 매핑)
 *
 * 다크 모드: tokens.css `:where(html.dark)` selector 자동 swap. swap 한 element
 * 에서 `dark:` prefix 제거.
 *
 * 미swap (의도):
 *  - <Input /> / <Chip /> / <SongCard /> / <SongDetailModal /> 등 자식 컴포넌트
 *    는 별도 PR (PR 5/6/10 등) 에서 이미 토큰화. 본 PR 은 page-level surface 만.
 *  - SongDetailContent (recommend 공통) 잔여 zinc 는 SongCard 의 형제 후속
 *    PR 후보.
 */
export default function SongSearchPage() {
  // useSearchParams는 CSR bail-out을 유발하므로 Suspense boundary로 감싼다 (Next.js 16 요구사항).
  // fallback은 결과 영역이 비어 있는 형태로 충분히 짧게 유지.
  return (
    <Suspense fallback={<SearchPageFallback />}>
      <SongSearchPageInner />
    </Suspense>
  );
}

function SearchPageFallback() {
  return (
    <main className="flex flex-1 flex-col items-center bg-[var(--bg-subtle)] px-[var(--page-padding-x)] py-[var(--page-padding-y)]">
      <div className="w-full max-w-2xl flex flex-col gap-6">
        <header className="space-y-2">
          <p className="text-xs font-medium uppercase tracking-widest text-[var(--text-caption)]">
            Browse
          </p>
          <h1 className="text-2xl font-semibold text-[var(--text-primary)]">
            곡 검색
          </h1>
        </header>
      </div>
    </main>
  );
}

function SongSearchPageInner() {
  const router = useRouter();
  const searchParams = useSearchParams();

  // URL → 초기 상태. useState lazy initializer로 한 번만 평가.
  const [inputValue, setInputValue] = useState<string>(
    () => searchParams.get("keyword") ?? "",
  );
  const [debouncedKeyword, setDebouncedKeyword] = useState<string>(
    () => (searchParams.get("keyword") ?? "").trim(),
  );
  const [selectedGenres, setSelectedGenres] = useState<Set<string>>(
    () => parseCsvSet(searchParams.get("genre")),
  );
  const [selectedDifficulties, setSelectedDifficulties] = useState<
    Set<Difficulty>
  >(() => parseDifficultySet(searchParams.get("difficulty")));

  // 입력 → 디바운스. setTimeout 기반이 useDeferredValue보다 테스트 결정적이라 채택.
  useEffect(() => {
    const handle = setTimeout(() => {
      setDebouncedKeyword(inputValue.trim());
    }, DEBOUNCE_MS);
    return () => clearTimeout(handle);
  }, [inputValue]);

  // 상태 → URL. router.replace로 히스토리 더럽히지 않음. SSR 가드(typeof window) 생략:
  // 'use client' 페이지라 client에서만 실행됨.
  useEffect(() => {
    const params = new URLSearchParams();
    if (debouncedKeyword.length > 0) {
      params.set("keyword", debouncedKeyword);
    }
    if (selectedGenres.size > 0) {
      params.set("genre", Array.from(selectedGenres).sort().join(","));
    }
    if (selectedDifficulties.size > 0) {
      params.set(
        "difficulty",
        Array.from(selectedDifficulties)
          .sort(byDifficultyOrder)
          .join(","),
      );
    }
    const next = params.toString();
    const current = searchParams.toString();
    if (next === current) {
      return;
    }
    router.replace(next.length > 0 ? `/songs?${next}` : "/songs", {
      scroll: false,
    });
  }, [
    debouncedKeyword,
    selectedGenres,
    selectedDifficulties,
    router,
    searchParams,
  ]);

  const enabled = debouncedKeyword.length > 0;
  const query = useQuery<SongListResponse, Error>({
    queryKey: ["songs", "search", debouncedKeyword],
    queryFn: ({ signal }) => searchSongs(debouncedKeyword, signal),
    enabled,
  });

  // BE가 #1551부터 bare 배열 대신 wrapper `{items, ...}`를 반환 → items를 곡 목록으로 사용.
  const songItems = query.data?.items;

  // 응답 곡들의 unique genre들 (필터 chip 옵션). null/공백은 제외, 사전 순 고정.
  const availableGenres = useMemo<string[]>(() => {
    if (!songItems) {
      return [];
    }
    const set = new Set<string>();
    for (const song of songItems) {
      if (song.genre && song.genre.trim().length > 0) {
        set.add(song.genre);
      }
    }
    return Array.from(set).sort();
  }, [songItems]);

  const filteredSongs = useMemo<SongResponse[]>(() => {
    if (!songItems) {
      return [];
    }
    const genreActive = selectedGenres.size > 0;
    const difficultyActive = selectedDifficulties.size > 0;
    if (!genreActive && !difficultyActive) {
      return songItems;
    }
    return songItems.filter((song) => {
      if (genreActive && (!song.genre || !selectedGenres.has(song.genre))) {
        return false;
      }
      if (difficultyActive) {
        const derived = songDifficulty(song);
        if (derived === null || !selectedDifficulties.has(derived)) {
          return false;
        }
      }
      return true;
    });
  }, [songItems, selectedGenres, selectedDifficulties]);

  const toggleGenre = useCallback((genre: string) => {
    setSelectedGenres((prev) => toggleInSet(prev, genre));
  }, []);
  const toggleDifficulty = useCallback((difficulty: Difficulty) => {
    setSelectedDifficulties((prev) => toggleInSet(prev, difficulty));
  }, []);
  const resetFilters = useCallback(() => {
    setSelectedGenres(new Set());
    setSelectedDifficulties(new Set());
  }, []);

  const filtersActive =
    selectedGenres.size > 0 || selectedDifficulties.size > 0;

  return (
    <main className="flex flex-1 flex-col items-center bg-[var(--bg-subtle)] px-[var(--page-padding-x)] py-[var(--page-padding-y)]">
      <div className="w-full max-w-2xl flex flex-col gap-6">
        <header className="space-y-2">
          <p className="text-xs font-medium uppercase tracking-widest text-[var(--text-caption)]">
            Browse
          </p>
          <h1 className="text-2xl font-semibold text-[var(--text-primary)]">
            곡 검색
          </h1>
          <p className="text-sm text-[var(--text-secondary)]">
            제목이나 아티스트로 카탈로그를 찾아보세요.
          </p>
        </header>

        <div className="flex flex-col gap-3">
          <Input
            id="song-search-input"
            type="search"
            label="곡 검색"
            labelHidden
            value={inputValue}
            onChange={(event) => setInputValue(event.target.value)}
            placeholder="곡 제목이나 아티스트로 검색"
            autoComplete="off"
          />

          <FilterPanel
            availableGenres={availableGenres}
            selectedGenres={selectedGenres}
            selectedDifficulties={selectedDifficulties}
            onToggleGenre={toggleGenre}
            onToggleDifficulty={toggleDifficulty}
            onReset={resetFilters}
            filtersActive={filtersActive}
          />
        </div>

        <SearchResult
          enabled={enabled}
          isPending={query.isPending}
          isFetching={query.isFetching}
          error={query.error}
          songs={filteredSongs}
          rawCount={songItems?.length ?? 0}
          totalCount={query.data?.totalCount ?? 0}
          hasNext={query.data?.hasNext ?? false}
          filtersActive={filtersActive}
        />
      </div>
    </main>
  );
}

type FilterPanelProps = {
  availableGenres: string[];
  selectedGenres: Set<string>;
  selectedDifficulties: Set<Difficulty>;
  onToggleGenre: (genre: string) => void;
  onToggleDifficulty: (difficulty: Difficulty) => void;
  onReset: () => void;
  filtersActive: boolean;
};

function FilterPanel({
  availableGenres,
  selectedGenres,
  selectedDifficulties,
  onToggleGenre,
  onToggleDifficulty,
  onReset,
  filtersActive,
}: FilterPanelProps) {
  return (
    <div className="flex flex-col gap-3">
      <div
        role="group"
        aria-label="난이도 필터"
        className="flex flex-wrap items-center gap-2"
      >
        <span className="text-xs font-medium text-[var(--text-caption)]">
          난이도
        </span>
        {DIFFICULTY_OPTIONS.map((option) => {
          const active = selectedDifficulties.has(option.key);
          return (
            <Chip
              key={option.key}
              pressed={active}
              onClick={() => onToggleDifficulty(option.key)}
            >
              {option.label}
            </Chip>
          );
        })}
      </div>

      {availableGenres.length > 0 && (
        <div
          role="group"
          aria-label="장르 필터"
          // 모바일에서 chip이 많아지면 가로 스크롤. 데스크탑은 wrap.
          className="flex flex-wrap items-center gap-2"
        >
          <span className="text-xs font-medium text-[var(--text-caption)]">
            장르
          </span>
          {availableGenres.map((genre) => {
            const active = selectedGenres.has(genre);
            return (
              <Chip
                key={genre}
                pressed={active}
                onClick={() => onToggleGenre(genre)}
              >
                {genre}
              </Chip>
            );
          })}
        </div>
      )}

      {filtersActive && (
        <div>
          <button
            type="button"
            onClick={onReset}
            className="inline-flex h-8 items-center rounded-full px-3 text-xs font-medium text-[var(--text-secondary)] underline-offset-4 hover:underline"
          >
            필터 초기화
          </button>
        </div>
      )}
    </div>
  );
}

type SearchResultProps = {
  enabled: boolean;
  isPending: boolean;
  isFetching: boolean;
  error: Error | null;
  songs: SongResponse[];
  /** 현재 페이지(서버 응답 items) 곡 수. */
  rawCount: number;
  /** 필터 통과 전체 곡 수(서버 totalCount). 현재 페이지 곡 수와 다를 수 있다. */
  totalCount: number;
  /** 다음 페이지 존재 여부(서버 hasNext). */
  hasNext: boolean;
  filtersActive: boolean;
};

function SearchResult({
  enabled,
  isPending,
  isFetching,
  error,
  songs,
  rawCount,
  totalCount,
  hasNext,
  filtersActive,
}: SearchResultProps) {
  if (!enabled) {
    return (
      <div
        role="status"
        className="flex flex-col items-start gap-2 rounded-[var(--radius-lg)] border border-dashed border-[var(--cta-secondary-border)] bg-[var(--cta-secondary-bg)] p-5"
      >
        <p className="text-sm text-[var(--text-label)]">
          검색어를 입력해 보세요.
        </p>
        <Link
          href="/voice-range"
          className="text-xs text-[var(--text-caption)] underline-offset-4 hover:underline"
        >
          또는 음역대 입력으로 추천 받기
        </Link>
      </div>
    );
  }

  if (isPending || isFetching) {
    return (
      <p
        role="status"
        aria-busy="true"
        className="text-sm text-[var(--text-caption)]"
      >
        검색 중...
      </p>
    );
  }

  if (error) {
    // closes #470 — 검색 에러는 SR이 즉시 announce 해야 한다.
    // role="alert" + aria-live="assertive"로 polite 영역과 분리. 시각적 표현은 유지.
    return (
      <div
        role="alert"
        aria-live="assertive"
        className="flex flex-col gap-3 rounded-2xl border border-[var(--danger-border)] bg-[var(--danger-bg)] p-4"
      >
        <p className="text-sm text-[var(--danger-fg-strong)]">
          검색에 실패했습니다.{" "}
          {error instanceof ApiError
            ? `${error.status}: ${error.message}`
            : error.message}
        </p>
      </div>
    );
  }

  if (songs.length === 0) {
    const message =
      rawCount === 0
        ? "검색 결과 없음"
        : "선택한 필터에 해당하는 곡이 없어요.";
    return (
      <p role="status" className="text-sm text-[var(--text-secondary)]">
        {message}
      </p>
    );
  }

  return (
    <div className="flex flex-col gap-3">
      <p
        role="status"
        aria-live="polite"
        className="text-xs text-[var(--text-caption)]"
      >
        {filtersActive
          ? `필터 결과 ${songs.length}곡 / 전체 ${rawCount}곡`
          : hasNext
            ? `${rawCount}곡 (전체 ${totalCount}곡 중 일부)`
            : `${totalCount}곡`}
      </p>
      <SongSearchResultList songs={songs} />
    </div>
  );
}

/**
 * 검색 결과 카드 + 상세 모달 묶음 (closes #323).
 * 카드 클릭 시 페이지 이동 대신 모달이 열린다. /songs/[id] deep-link 직접 접근은
 * 기존 페이지가 그대로 처리한다.
 */
type SongSearchResultListProps = {
  songs: SongResponse[];
};

function SongSearchResultList({ songs }: SongSearchResultListProps) {
  const [selected, setSelected] = useState<SongResponse | null>(null);
  return (
    <>
      <ul aria-label="검색 결과" className="flex flex-col gap-3">
        {songs.map((song) => (
          <SongCard
            key={song.id}
            song={song}
            onShowDetail={() => setSelected(song)}
          />
        ))}
      </ul>
      <SongDetailModal
        open={selected !== null}
        onClose={() => setSelected(null)}
        titleLabel={selected ? formatSongDisplayTitle(selected) : ""}
      >
        {selected ? <SongDetailContent song={selected} /> : null}
      </SongDetailModal>
    </>
  );
}

/**
 * 응답 곡에 BE가 채워준 difficulty가 있으면 그것을, 없으면 lowMidi/highMidi로 계산,
 * 둘 다 없으면 null. 필터는 null인 곡을 제외한다.
 */
function songDifficulty(song: SongResponse): Difficulty | null {
  if (song.difficulty) {
    return song.difficulty;
  }
  if (typeof song.lowMidi === "number" && typeof song.highMidi === "number") {
    return deriveDifficulty(song.lowMidi, song.highMidi);
  }
  return null;
}

function toggleInSet<T>(prev: Set<T>, value: T): Set<T> {
  const next = new Set(prev);
  if (next.has(value)) {
    next.delete(value);
  } else {
    next.add(value);
  }
  return next;
}

function parseCsvSet(raw: string | null): Set<string> {
  if (!raw) {
    return new Set();
  }
  return new Set(
    raw
      .split(",")
      .map((value) => value.trim())
      .filter((value) => value.length > 0),
  );
}

function parseDifficultySet(raw: string | null): Set<Difficulty> {
  const set = new Set<Difficulty>();
  if (!raw) {
    return set;
  }
  for (const part of raw.split(",")) {
    const trimmed = part.trim();
    if (trimmed === "EASY" || trimmed === "NORMAL" || trimmed === "HARD") {
      set.add(trimmed);
    }
  }
  return set;
}

const DIFFICULTY_ORDER: Record<Difficulty, number> = {
  EASY: 0,
  NORMAL: 1,
  HARD: 2,
};

function byDifficultyOrder(a: Difficulty, b: Difficulty): number {
  return DIFFICULTY_ORDER[a] - DIFFICULTY_ORDER[b];
}
