/**
 * 쇼츠식 스와이프 선곡 덱 (#1489).
 *
 * 추천 결과를 무한 리스트 대신 **한 곡씩 카드로** 보여주고, 좌/우 스와이프(또는 하단
 * 버튼)로 빠르게 좋아요/패스를 결정하게 한다. 한 손 모바일 UX 를 1차 목표로 한다.
 *
 * 재사용: 카드 본문은 기존 `SongDetailContent`(앨범 커버/메타/추천 사유/좋아요·북마크)
 * 를 그대로 사용한다 — 리스트 모드의 상세 모달과 동일한 정보 밀도를 유지한다.
 *
 * 신호 적재:
 *   - 우 스와이프(좋아요) → `useLikeToggleMutation` 으로 실제 좋아요 반영(+/likes 동기화)
 *     + `swipeReactions` store 에 `like` 기록.
 *   - 좌 스와이프(패스) → `swipeReactions` store 에 `pass` 기록.
 *   두 반응 모두 "부른곡 기반 모드" 의 입력 신호(토대)로 적재된다.
 *
 * 다음 batch 프리페치: 남은 카드가 임계 이하로 떨어지고 다음 페이지가 있으면
 * `onNeedMore`(= react-query `fetchNextPage`)를 호출해 끊김 없이 이어 본다.
 *
 * a11y / 모션:
 *   - 제스처는 포인터 전용이므로 키보드/AT 사용자를 위해 하단에 "패스 / 좋아요" 버튼을 둔다.
 *   - `prefers-reduced-motion: reduce` 면 드래그 추적·exit 애니메이션을 건너뛰고 즉시 전환한다.
 *   - 카드 진입은 `animate-scale-in` 유틸(globals.css)에 위임 — CSS 전역 가드가 모션을 끈다.
 */

"use client";

import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type PointerEvent as ReactPointerEvent,
} from "react";

import type { RecommendedSongResponse } from "@/lib/api/recommendation";
import { useLikeToggleMutation } from "@/lib/hooks/useFeedbackToggleMutation";
import type { UserVoiceRange } from "@/lib/scoreBreakdown";
import { formatSongDisplayTitle } from "@/lib/songTitle";
import { usePrefersReducedMotion } from "@/lib/usePrefersReducedMotion";
import { useLikesStore } from "@/store/likes";
import {
  useSwipeReactionsStore,
  type SwipeReaction,
} from "@/store/swipeReactions";

import { SongDetailContent } from "./SongDetailContent";

/**
 * 다음 batch 프리페치 임계. 남은 카드가 이 수 이하이고 다음 페이지가 있으면 미리 페치.
 */
const PREFETCH_THRESHOLD = 2;

/** exit 애니메이션 지속(ms). tokens.css `--duration-slow`(300ms)와 정렬. */
export const SWIPE_EXIT_DURATION_MS = 300;

/** 커밋 임계: 카드 폭의 비율 또는 최소 px 중 큰 값. */
export const SWIPE_COMMIT_RATIO = 0.25;
export const SWIPE_COMMIT_MIN_PX = 80;

/**
 * 드래그 변위(px)와 카드 폭으로 스와이프 의도를 판정한다 (순수 함수 — 단위 테스트 대상).
 *
 * - 우(+) 임계 초과 → `like`
 * - 좌(-) 임계 초과 → `pass`
 * - 임계 미만 → null (스냅백)
 */
export function resolveSwipeIntent(
  deltaX: number,
  width: number,
): SwipeReaction | null {
  const threshold = Math.max(SWIPE_COMMIT_MIN_PX, width * SWIPE_COMMIT_RATIO);
  if (deltaX >= threshold) {
    return "like";
  }
  if (deltaX <= -threshold) {
    return "pass";
  }
  return null;
}

type SwipeDeckProps = {
  recommendations: RecommendedSongResponse[];
  userVoiceRange: UserVoiceRange;
  hasMore: boolean;
  isFetchingMore: boolean;
  onNeedMore: () => void;
};

export function SwipeDeck({
  recommendations,
  userVoiceRange,
  hasMore,
  isFetchingMore,
  onNeedMore,
}: SwipeDeckProps) {
  const [index, setIndex] = useState(0);
  const [likedCount, setLikedCount] = useState(0);
  const [passedCount, setPassedCount] = useState(0);
  const recordReaction = useSwipeReactionsStore(
    (state) => state.recordReaction,
  );

  const total = recommendations.length;
  const remaining = total - index;
  const current = index < total ? recommendations[index] : null;

  // 남은 카드가 적어지면 다음 batch 를 미리 당겨온다 (무한 스크롤 sentinel 의 덱 버전).
  useEffect(() => {
    if (hasMore && !isFetchingMore && remaining <= PREFETCH_THRESHOLD) {
      onNeedMore();
    }
  }, [hasMore, isFetchingMore, remaining, onNeedMore]);

  const handleResolve = useCallback(
    (reaction: SwipeReaction, songId: number) => {
      recordReaction(songId, reaction);
      if (reaction === "like") {
        setLikedCount((prev) => prev + 1);
      } else {
        setPassedCount((prev) => prev + 1);
      }
      setIndex((prev) => prev + 1);
    },
    [recordReaction],
  );

  if (current) {
    return (
      <div className="flex flex-col gap-4">
        <DeckProgress
          position={index + 1}
          total={total}
          hasMore={hasMore}
          likedCount={likedCount}
          passedCount={passedCount}
        />
        <SwipeCard
          key={current.song.id}
          item={current}
          userVoiceRange={userVoiceRange}
          onResolve={handleResolve}
        />
      </div>
    );
  }

  // 덱 소진 — 다음 페이지를 기다리는 중이면 로딩, 아니면 요약.
  return (
    <DeckEnd
      likedCount={likedCount}
      passedCount={passedCount}
      loadingMore={hasMore || isFetchingMore}
    />
  );
}

type DeckProgressProps = {
  position: number;
  total: number;
  hasMore: boolean;
  likedCount: number;
  passedCount: number;
};

function DeckProgress({
  position,
  total,
  hasMore,
  likedCount,
  passedCount,
}: DeckProgressProps) {
  return (
    <div className="flex items-center justify-between text-xs text-[var(--text-tertiary)]">
      <span data-testid="swipe-progress" aria-live="polite">
        {position} / {total}
        {hasMore ? "+" : ""}
      </span>
      <span className="flex items-center gap-3">
        <span aria-label={`좋아요 ${likedCount}건`}>❤️ {likedCount}</span>
        <span aria-label={`패스 ${passedCount}건`}>↩️ {passedCount}</span>
      </span>
    </div>
  );
}

type SwipeCardProps = {
  item: RecommendedSongResponse;
  userVoiceRange: UserVoiceRange;
  onResolve: (reaction: SwipeReaction, songId: number) => void;
};

function SwipeCard({ item, userVoiceRange, onResolve }: SwipeCardProps) {
  const prefersReducedMotion = usePrefersReducedMotion();
  const songId = item.song.id;
  const displayTitle = formatSongDisplayTitle(item.song);

  const { toggle: toggleLike } = useLikeToggleMutation(songId);
  const liked = useLikesStore((state) => state.likedSongIds.includes(songId));

  const cardRef = useRef<HTMLDivElement | null>(null);
  const dragStartXRef = useRef<number | null>(null);
  const resolvingRef = useRef(false);

  const [dragX, setDragX] = useState(0);
  const [isDragging, setIsDragging] = useState(false);
  const [exitDirection, setExitDirection] = useState<"left" | "right" | null>(
    null,
  );

  const commit = useCallback(
    (reaction: SwipeReaction) => {
      if (resolvingRef.current) {
        return;
      }
      resolvingRef.current = true;
      // 좋아요는 실제 좋아요 mutation 도 발화 (이미 좋아요면 그대로 둔다).
      if (reaction === "like" && !liked) {
        toggleLike();
      }
      if (prefersReducedMotion) {
        onResolve(reaction, songId);
        return;
      }
      setExitDirection(reaction === "like" ? "right" : "left");
      window.setTimeout(() => {
        onResolve(reaction, songId);
      }, SWIPE_EXIT_DURATION_MS);
    },
    [liked, prefersReducedMotion, toggleLike, onResolve, songId],
  );

  const handlePointerDown = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (prefersReducedMotion || exitDirection || event.button !== 0) {
      return;
    }
    // 인터랙티브 요소(좋아요/북마크/YouTube) 위에서는 드래그를 시작하지 않는다 —
    // 탭이 그대로 해당 버튼으로 전달되게 한다.
    if (
      event.target instanceof Element &&
      event.target.closest("a, button, input")
    ) {
      return;
    }
    dragStartXRef.current = event.clientX;
    setIsDragging(true);
    event.currentTarget.setPointerCapture?.(event.pointerId);
  };

  const handlePointerMove = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (!isDragging || dragStartXRef.current === null) {
      return;
    }
    setDragX(event.clientX - dragStartXRef.current);
  };

  const endDrag = () => {
    if (!isDragging) {
      return;
    }
    setIsDragging(false);
    dragStartXRef.current = null;
    const width = cardRef.current?.offsetWidth ?? 0;
    const intent = resolveSwipeIntent(dragX, width);
    if (intent) {
      commit(intent);
    } else {
      setDragX(0);
    }
  };

  // 드래그 변위에 따른 좋아요/패스 힌트 강도 (0~1).
  const likeHintOpacity = dragX > 0 ? Math.min(1, dragX / 120) : 0;
  const passHintOpacity = dragX < 0 ? Math.min(1, -dragX / 120) : 0;

  const transform = exitDirection
    ? `translateX(${exitDirection === "right" ? "120%" : "-120%"}) rotate(${
        exitDirection === "right" ? 12 : -12
      }deg)`
    : `translateX(${dragX}px) rotate(${dragX * 0.04}deg)`;

  const transitionClass = isDragging
    ? "" // 드래그 중에는 손가락을 즉시 따라오도록 transition 제거.
    : "transition-[transform,opacity] duration-[var(--duration-slow)] ease-[var(--ease-out)]";

  return (
    <div className="flex flex-col gap-4">
      <div className="relative">
        <div
          ref={cardRef}
          role="group"
          aria-roledescription="스와이프 선곡 카드"
          aria-label={`${displayTitle} — 오른쪽으로 밀면 좋아요, 왼쪽으로 밀면 패스`}
          data-testid="swipe-card"
          onPointerDown={handlePointerDown}
          onPointerMove={handlePointerMove}
          onPointerUp={endDrag}
          onPointerCancel={endDrag}
          style={{
            transform,
            opacity: exitDirection ? 0 : 1,
            touchAction: "pan-y",
          }}
          className={`animate-scale-in select-none rounded-[var(--radius-lg)] bg-[var(--bg-base)] p-[var(--card-padding)] ring-1 ring-[var(--border)] ${transitionClass}`}
        >
          <SongDetailContent item={item} userVoiceRange={userVoiceRange} />
        </div>
        {/* 스와이프 방향 힌트 — 드래그 중에만 보인다. */}
        <SwipeHint side="like" opacity={likeHintOpacity} />
        <SwipeHint side="pass" opacity={passHintOpacity} />
      </div>

      <div className="flex items-center justify-center gap-4">
        <button
          type="button"
          onClick={() => commit("pass")}
          aria-label={`${displayTitle} 패스하고 다음 곡 보기`}
          className="inline-flex min-h-12 items-center gap-2 rounded-full border border-[var(--cta-secondary-border)] px-6 py-2.5 text-sm font-semibold text-[var(--cta-secondary-fg)] transition-colors hover:bg-[var(--cta-secondary-bg-hover)] focus:outline-none focus-visible:ring-2 focus-visible:ring-[var(--cta-secondary-ring)] active:scale-[0.97]"
        >
          <span aria-hidden="true">↩️</span>
          <span>패스</span>
        </button>
        <button
          type="button"
          onClick={() => commit("like")}
          aria-label={`${displayTitle} 좋아요하고 다음 곡 보기`}
          className="inline-flex min-h-12 items-center gap-2 rounded-full bg-[var(--brand-500)] px-6 py-2.5 text-sm font-semibold text-white transition-colors duration-[var(--duration-base)] hover:bg-[var(--brand-600)] hover:shadow-[var(--shadow-brand)] focus:outline-none focus-visible:ring-2 focus-visible:ring-[var(--brand-500)] focus-visible:ring-offset-2 active:scale-[0.97]"
        >
          <span aria-hidden="true">❤️</span>
          <span>좋아요</span>
        </button>
      </div>
    </div>
  );
}

type SwipeHintProps = {
  side: SwipeReaction;
  opacity: number;
};

function SwipeHint({ side, opacity }: SwipeHintProps) {
  const isLike = side === "like";
  return (
    <span
      aria-hidden="true"
      style={{ opacity }}
      className={`pointer-events-none absolute top-6 rounded-xl border-2 px-3 py-1 text-base font-bold uppercase tracking-wider transition-opacity ${
        isLike
          ? "right-6 rotate-12 border-emerald-500 text-emerald-500"
          : "left-6 -rotate-12 border-rose-500 text-rose-500"
      }`}
    >
      {isLike ? "좋아요" : "패스"}
    </span>
  );
}

type DeckEndProps = {
  likedCount: number;
  passedCount: number;
  loadingMore: boolean;
};

function DeckEnd({ likedCount, passedCount, loadingMore }: DeckEndProps) {
  if (loadingMore) {
    return (
      <div
        role="status"
        aria-busy="true"
        className="flex flex-col items-center gap-3 rounded-[var(--radius-lg)] border border-[var(--border)] bg-[var(--bg-base)] p-8 text-center"
      >
        <p className="text-sm text-[var(--text-secondary)]">
          다음 곡을 불러오는 중...
        </p>
      </div>
    );
  }

  return (
    <div
      role="status"
      data-testid="swipe-deck-end"
      className="flex flex-col items-center gap-3 rounded-[var(--radius-lg)] border border-[var(--border)] bg-[var(--bg-base)] p-8 text-center"
    >
      <p className="text-base font-semibold text-[var(--text-primary)]">
        오늘은 여기까지예요
      </p>
      <p className="text-sm text-[var(--text-secondary)]">
        좋아요 {likedCount}곡 · 패스 {passedCount}곡을 넘겨봤어요. 음역대나 분위기를
        바꾸면 새 곡을 더 만날 수 있어요.
      </p>
    </div>
  );
}
