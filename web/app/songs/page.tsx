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
import { searchSongs, type SongResponse } from "@/lib/api/song";
import { deriveDifficulty, type Difficulty } from "@/lib/difficulty";

import { SongCard } from "../recommend/components/SongCard";

const DEBOUNCE_MS = 300;
const DIFFICULTY_OPTIONS: { key: Difficulty; label: string }[] = [
  { key: "EASY", label: "Easy" },
  { key: "NORMAL", label: "Normal" },
  { key: "HARD", label: "Hard" },
];

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
    <main className="flex flex-1 flex-col items-center bg-zinc-50 px-6 py-12 dark:bg-zinc-950">
      <div className="w-full max-w-2xl flex flex-col gap-6">
        <header className="space-y-2">
          <p className="text-xs font-medium uppercase tracking-widest text-zinc-500 dark:text-zinc-400">
            Browse
          </p>
          <h1 className="text-2xl font-semibold text-zinc-900 dark:text-zinc-50">
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
  const query = useQuery<SongResponse[], Error>({
    queryKey: ["songs", "search", debouncedKeyword],
    queryFn: ({ signal }) => searchSongs(debouncedKeyword, signal),
    enabled,
  });

  // 응답 곡들의 unique genre들 (필터 chip 옵션). null/공백은 제외, 사전 순 고정.
  const availableGenres = useMemo<string[]>(() => {
    if (!query.data) {
      return [];
    }
    const set = new Set<string>();
    for (const song of query.data) {
      if (song.genre && song.genre.trim().length > 0) {
        set.add(song.genre);
      }
    }
    return Array.from(set).sort();
  }, [query.data]);

  const filteredSongs = useMemo<SongResponse[]>(() => {
    if (!query.data) {
      return [];
    }
    const genreActive = selectedGenres.size > 0;
    const difficultyActive = selectedDifficulties.size > 0;
    if (!genreActive && !difficultyActive) {
      return query.data;
    }
    return query.data.filter((song) => {
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
  }, [query.data, selectedGenres, selectedDifficulties]);

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
    <main className="flex flex-1 flex-col items-center bg-zinc-50 px-6 py-12 dark:bg-zinc-950">
      <div className="w-full max-w-2xl flex flex-col gap-6">
        <header className="space-y-2">
          <p className="text-xs font-medium uppercase tracking-widest text-zinc-500 dark:text-zinc-400">
            Browse
          </p>
          <h1 className="text-2xl font-semibold text-zinc-900 dark:text-zinc-50">
            곡 검색
          </h1>
          <p className="text-sm text-zinc-600 dark:text-zinc-400">
            제목이나 아티스트로 카탈로그를 찾아보세요.
          </p>
        </header>

        <div className="flex flex-col gap-3">
          <label htmlFor="song-search-input" className="sr-only">
            곡 검색
          </label>
          <input
            id="song-search-input"
            type="search"
            value={inputValue}
            onChange={(event) => setInputValue(event.target.value)}
            placeholder="곡 제목이나 아티스트로 검색"
            autoComplete="off"
            className="h-12 w-full rounded-2xl border border-zinc-200 bg-white px-4 text-base text-zinc-900 placeholder:text-zinc-400 focus:border-zinc-400 focus:outline-none focus:ring-2 focus:ring-zinc-300 dark:border-zinc-800 dark:bg-zinc-900 dark:text-zinc-50 dark:placeholder:text-zinc-500 dark:focus:border-zinc-600 dark:focus:ring-zinc-700"
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
          rawCount={query.data?.length ?? 0}
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
        <span className="text-xs font-medium text-zinc-500 dark:text-zinc-400">
          난이도
        </span>
        {DIFFICULTY_OPTIONS.map((option) => {
          const active = selectedDifficulties.has(option.key);
          return (
            <FilterChip
              key={option.key}
              label={option.label}
              active={active}
              onClick={() => onToggleDifficulty(option.key)}
            />
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
          <span className="text-xs font-medium text-zinc-500 dark:text-zinc-400">
            장르
          </span>
          {availableGenres.map((genre) => {
            const active = selectedGenres.has(genre);
            return (
              <FilterChip
                key={genre}
                label={genre}
                active={active}
                onClick={() => onToggleGenre(genre)}
              />
            );
          })}
        </div>
      )}

      {filtersActive && (
        <div>
          <button
            type="button"
            onClick={onReset}
            className="inline-flex h-8 items-center rounded-full px-3 text-xs font-medium text-zinc-600 underline-offset-4 hover:underline dark:text-zinc-400"
          >
            필터 초기화
          </button>
        </div>
      )}
    </div>
  );
}

type FilterChipProps = {
  label: string;
  active: boolean;
  onClick: () => void;
};

function FilterChip({ label, active, onClick }: FilterChipProps) {
  return (
    <button
      type="button"
      aria-pressed={active}
      onClick={onClick}
      className={`inline-flex h-8 items-center rounded-full px-3 text-xs font-medium transition ${
        active
          ? "bg-zinc-900 text-white dark:bg-zinc-50 dark:text-zinc-900"
          : "bg-white text-zinc-700 ring-1 ring-zinc-200 hover:bg-zinc-100 dark:bg-zinc-900 dark:text-zinc-300 dark:ring-zinc-800 dark:hover:bg-zinc-800"
      }`}
    >
      {label}
    </button>
  );
}

type SearchResultProps = {
  enabled: boolean;
  isPending: boolean;
  isFetching: boolean;
  error: Error | null;
  songs: SongResponse[];
  rawCount: number;
  filtersActive: boolean;
};

function SearchResult({
  enabled,
  isPending,
  isFetching,
  error,
  songs,
  rawCount,
  filtersActive,
}: SearchResultProps) {
  if (!enabled) {
    return (
      <div
        role="status"
        className="flex flex-col items-start gap-2 rounded-2xl border border-dashed border-zinc-300 bg-white p-5 dark:border-zinc-700 dark:bg-zinc-900"
      >
        <p className="text-sm text-zinc-700 dark:text-zinc-300">
          검색어를 입력해 보세요.
        </p>
        <Link
          href="/voice-range"
          className="text-xs text-zinc-500 underline-offset-4 hover:underline dark:text-zinc-400"
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
        className="text-sm text-zinc-500 dark:text-zinc-400"
      >
        검색 중...
      </p>
    );
  }

  if (error) {
    return (
      <div className="flex flex-col gap-3 rounded-2xl border border-red-200 bg-red-50 p-4 dark:border-red-900 dark:bg-red-950">
        <p className="text-sm text-red-700 dark:text-red-200">
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
      <p role="status" className="text-sm text-zinc-600 dark:text-zinc-400">
        {message}
      </p>
    );
  }

  return (
    <div className="flex flex-col gap-3">
      <p
        role="status"
        aria-live="polite"
        className="text-xs text-zinc-500 dark:text-zinc-400"
      >
        {filtersActive
          ? `필터 결과 ${songs.length}곡 / 전체 ${rawCount}곡`
          : `${rawCount}곡`}
      </p>
      <ul aria-label="검색 결과" className="flex flex-col gap-3">
        {songs.map((song) => (
          <SongCard key={song.id} song={song} href={`/songs/${song.id}`} />
        ))}
      </ul>
    </div>
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
