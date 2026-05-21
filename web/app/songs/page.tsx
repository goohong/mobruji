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
 * 난이도 필터(EASY/NORMAL/HARD)는 클라이언트 사이드에서 응답을 필터링한다.
 * `song.difficulty`가 없으면 `deriveDifficulty(lowMidi, highMidi)`로 계산해서 필터링하고,
 * 둘 다 없는 곡(legacy 응답)은 필터 적용 시 제외된다.
 */

import { useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";

import { ApiError } from "@/lib/api/client";
import { searchSongs, type SongResponse } from "@/lib/api/song";
import { deriveDifficulty, type Difficulty } from "@/lib/difficulty";

import { SongCard } from "../recommend/components/SongCard";

const DEBOUNCE_MS = 300;

type DifficultyFilter = Difficulty | "ALL";

export default function SongSearchPage() {
  const [inputValue, setInputValue] = useState<string>("");
  const [debouncedKeyword, setDebouncedKeyword] = useState<string>("");
  const [difficultyFilter, setDifficultyFilter] =
    useState<DifficultyFilter>("ALL");

  // 입력 → 디바운스. setTimeout 기반이 useDeferredValue보다 테스트 결정적이라 채택.
  useEffect(() => {
    const handle = setTimeout(() => {
      setDebouncedKeyword(inputValue.trim());
    }, DEBOUNCE_MS);
    return () => clearTimeout(handle);
  }, [inputValue]);

  const enabled = debouncedKeyword.length > 0;
  const query = useQuery<SongResponse[], Error>({
    queryKey: ["songs", "search", debouncedKeyword],
    queryFn: ({ signal }) => searchSongs(debouncedKeyword, signal),
    enabled,
  });

  const filteredSongs = useMemo<SongResponse[]>(() => {
    if (!query.data) {
      return [];
    }
    if (difficultyFilter === "ALL") {
      return query.data;
    }
    return query.data.filter((song) => songDifficulty(song) === difficultyFilter);
  }, [query.data, difficultyFilter]);

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

          <DifficultyFilterChips
            value={difficultyFilter}
            onChange={setDifficultyFilter}
          />
        </div>

        <SearchResult
          enabled={enabled}
          isPending={query.isPending}
          isFetching={query.isFetching}
          error={query.error}
          songs={filteredSongs}
          rawCount={query.data?.length ?? 0}
          difficultyFilter={difficultyFilter}
        />
      </div>
    </main>
  );
}

type DifficultyFilterChipsProps = {
  value: DifficultyFilter;
  onChange: (value: DifficultyFilter) => void;
};

function DifficultyFilterChips({ value, onChange }: DifficultyFilterChipsProps) {
  const options: { key: DifficultyFilter; label: string }[] = [
    { key: "ALL", label: "전체" },
    { key: "EASY", label: "Easy" },
    { key: "NORMAL", label: "Normal" },
    { key: "HARD", label: "Hard" },
  ];
  return (
    <div
      role="group"
      aria-label="난이도 필터"
      className="flex flex-wrap items-center gap-2"
    >
      {options.map((option) => {
        const active = option.key === value;
        return (
          <button
            key={option.key}
            type="button"
            aria-pressed={active}
            onClick={() => onChange(option.key)}
            className={`inline-flex h-8 items-center rounded-full px-3 text-xs font-medium transition ${
              active
                ? "bg-zinc-900 text-white dark:bg-zinc-50 dark:text-zinc-900"
                : "bg-white text-zinc-700 ring-1 ring-zinc-200 hover:bg-zinc-100 dark:bg-zinc-900 dark:text-zinc-300 dark:ring-zinc-800 dark:hover:bg-zinc-800"
            }`}
          >
            {option.label}
          </button>
        );
      })}
    </div>
  );
}

type SearchResultProps = {
  enabled: boolean;
  isPending: boolean;
  isFetching: boolean;
  error: Error | null;
  songs: SongResponse[];
  rawCount: number;
  difficultyFilter: DifficultyFilter;
};

function SearchResult({
  enabled,
  isPending,
  isFetching,
  error,
  songs,
  rawCount,
  difficultyFilter,
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
        : `'${difficultyFilterLabel(difficultyFilter)}' 난이도에 해당하는 곡이 없어요.`;
    return (
      <p role="status" className="text-sm text-zinc-600 dark:text-zinc-400">
        {message}
      </p>
    );
  }

  return (
    <ul aria-label="검색 결과" className="flex flex-col gap-3">
      {songs.map((song) => (
        <SongCard key={song.id} song={song} href={`/songs/${song.id}`} />
      ))}
    </ul>
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

function difficultyFilterLabel(value: DifficultyFilter): string {
  switch (value) {
    case "ALL":
      return "전체";
    case "EASY":
      return "Easy";
    case "NORMAL":
      return "Normal";
    case "HARD":
      return "Hard";
  }
}
