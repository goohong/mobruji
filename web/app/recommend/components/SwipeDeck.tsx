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
 * 카드 스택 + 치우기/올라옴 (#1763):
 *   - 윗장 뒤로 다음 1~2장을 살짝 scale·offset·그림자로 겹쳐 "덱(deck)" 깊이를 만든다.
 *   - 윗장이 커밋되면 화면 밖으로 날아가고(exit), 다음 장이 새 윗장으로 올라온다
 *     (`animate-deck-card-in` — 스택에서 위로 떠오르는 settle, Slack 정리 느낌).
 *
 * 스와이프 어포던스 (#1763):
 *   - 첫 미결정 카드는 좌우로 한 번 살짝 흔들려(`animate-swipe-wiggle`) "밀 수 있음"을 암시하고,
 *     카드 아래 좌(패스)/우(좋아요) 방향 힌트 캡션을 노출한다.
 *   - `prefers-reduced-motion: reduce` 면 wiggle/진입 모션을 끄고(CSS 가드) 정적 힌트만 남긴다.
 *
 * 무한 로드 (#1763, YouTube/쇼츠식):
 *   - 남은 카드가 임계 이하로 떨어지면 다음 batch 를 미리 당겨 끊김 없이 append 한다.
 *   - 좋아요한 곡이 1개 이상이면 그 곡들을 seed 로 `POST /api/v1/recommendations/next`
 *     (`createFromSeeds`)를 호출해 "부른 곡 기반" 다음 곡을 적응적으로 이어 붙인다.
 *     이미 본 곡은 `excludeSongIds` 로 넘겨 중복을 막는다.
 *   - 아직 좋아요가 없거나(콜드스타트) seed 가 소진되면 `onNeedMore`
 *     (= react-query `fetchNextPage`)로 폴백한다.
 *
 * a11y / 모션:
 *   - 제스처는 포인터 전용이므로 키보드/AT 사용자를 위해 하단에 "패스 / 좋아요" 버튼을 둔다.
 *   - `prefers-reduced-motion: reduce` 면 드래그 추적·exit·wiggle·진입 애니메이션을 건너뛰고
 *     즉시 전환한다(JS + CSS 전역 가드).
 */

"use client";

import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type PointerEvent as ReactPointerEvent,
} from "react";
import { useMutation } from "@tanstack/react-query";

import {
  nextRecommendation,
  type RecommendedSongResponse,
} from "@/lib/api/recommendation";
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
 * 다음 batch 프리페치 임계. 남은 카드가 이 수 이하이면 미리 페치(seed 기반 또는 폴백).
 */
const PREFETCH_THRESHOLD = 2;

/** 윗장 뒤에 겹쳐 보여줄 다음 카드 미리보기 장수(스택 깊이). */
const STACK_PEEK_COUNT = 2;

/** exit 애니메이션 기본 지속(ms). tokens.css `--duration-slow`(300ms)와 정렬. */
export const SWIPE_EXIT_DURATION_MS = 300;

/** 관성 exit 최소 지속(ms) — 빠른 플릭일수록 이 값으로 수렴(급감속). */
export const SWIPE_EXIT_MIN_DURATION_MS = 140;

/** 커밋 임계: 카드 폭의 비율 또는 최소 px 중 큰 값. */
export const SWIPE_COMMIT_RATIO = 0.25;
export const SWIPE_COMMIT_MIN_PX = 80;

/** 플릭(관성) 커밋 속도 임계(px/ms). 이 속도 이상으로 튕기면 변위가 작아도 커밋. */
export const SWIPE_FLICK_VELOCITY = 0.5;
/** 플릭 커밋에 필요한 최소 변위(px) — 정지 상태의 미세 떨림이 오발화하지 않도록. */
export const SWIPE_FLICK_MIN_PX = 24;
/** exit 지속이 최소값에 수렴하는 속도(px/ms). */
const SWIPE_EXIT_FAST_VELOCITY = 2.5;

/**
 * 드래그 변위(px)·카드 폭·릴리즈 속도로 스와이프 의도를 판정한다 (순수 함수 — 단위 테스트 대상).
 *
 * - 우(+) 변위 임계 초과 → `like`
 * - 좌(-) 변위 임계 초과 → `pass`
 * - 변위는 작지만 같은 방향으로 빠르게 튕긴(플릭) 경우 → 관성으로 커밋
 * - 그 외 → null (스냅백)
 */
export function resolveSwipeIntent(
  deltaX: number,
  width: number,
  velocityX = 0,
): SwipeReaction | null {
  const threshold = Math.max(SWIPE_COMMIT_MIN_PX, width * SWIPE_COMMIT_RATIO);
  if (deltaX >= threshold) {
    return "like";
  }
  if (deltaX <= -threshold) {
    return "pass";
  }
  // 빠른 플릭(관성): 변위가 임계 미만이어도 속도가 충분하고 변위와 같은 방향이면 커밋.
  if (
    Math.abs(velocityX) >= SWIPE_FLICK_VELOCITY &&
    Math.abs(deltaX) >= SWIPE_FLICK_MIN_PX &&
    Math.sign(velocityX) === Math.sign(deltaX)
  ) {
    return velocityX > 0 ? "like" : "pass";
  }
  return null;
}

/**
 * 릴리즈 속도로 exit 트랜지션 지속(ms)을 정한다 (순수 함수 — 단위 테스트 대상).
 * 느린 릴리즈는 기본 지속, 빠른 플릭일수록 최소 지속에 선형 수렴해 관성 감속을 표현한다.
 */
export function computeExitDurationMs(velocityX: number): number {
  const speed = Math.abs(velocityX);
  if (speed <= SWIPE_FLICK_VELOCITY) {
    return SWIPE_EXIT_DURATION_MS;
  }
  const ratio = Math.min(
    1,
    (speed - SWIPE_FLICK_VELOCITY) /
      (SWIPE_EXIT_FAST_VELOCITY - SWIPE_FLICK_VELOCITY),
  );
  return Math.round(
    SWIPE_EXIT_DURATION_MS +
      ratio * (SWIPE_EXIT_MIN_DURATION_MS - SWIPE_EXIT_DURATION_MS),
  );
}

type SwipeDeckProps = {
  recommendations: RecommendedSongResponse[];
  userVoiceRange: UserVoiceRange;
  hasMore: boolean;
  isFetchingMore: boolean;
  onNeedMore: () => void;
  /**
   * 익명 세션 ID(UUIDv4). 좋아요한 곡을 seed 로 `POST /api/v1/recommendations/next` 를
   * 호출할 때 필요하다. 미지정이면 seed 기반 로드를 건너뛰고 `onNeedMore` 폴백만 쓴다.
   */
  sessionId?: string;
};

export function SwipeDeck({
  recommendations,
  userVoiceRange,
  hasMore,
  isFetchingMore,
  onNeedMore,
  sessionId,
}: SwipeDeckProps) {
  const [index, setIndex] = useState(0);
  const [likedCount, setLikedCount] = useState(0);
  const [passedCount, setPassedCount] = useState(0);
  // 좋아요한 곡 = "부른 곡 기반" 다음 추천의 seed 신호. 순서를 유지하며 누적한다.
  const [seedSongIds, setSeedSongIds] = useState<number[]>([]);
  // seed 기반 `/next` 로 끊김 없이 이어 붙인 추가 추천. 부모 batch 와 합쳐 한 덱으로 소비한다.
  const [appended, setAppended] = useState<RecommendedSongResponse[]>([]);
  // seed 로 더 만들 곡이 없을 때 true — 같은 seed 로 반복 호출하지 않고 폴백/종료로 넘어간다.
  const [seedsExhausted, setSeedsExhausted] = useState(false);
  const recordReaction = useSwipeReactionsStore(
    (state) => state.recordReaction,
  );

  // 부모 batch + seed append 를 한 덱으로 합치되 곡 ID 중복을 제거한다(앞쪽 우선, 끝에 append).
  const deck = useMemo(() => {
    const seen = new Set<number>();
    const merged: RecommendedSongResponse[] = [];
    for (const item of [...recommendations, ...appended]) {
      if (seen.has(item.song.id)) {
        continue;
      }
      seen.add(item.song.id);
      merged.push(item);
    }
    return merged;
  }, [recommendations, appended]);

  const deckIds = useMemo(
    () => new Set(deck.map((item) => item.song.id)),
    [deck],
  );
  // `/next` onSuccess / 프리페치 effect 시점에 최신 덱 ID 셋을 참조하기 위한 ref(중복 방지).
  // 렌더 중이 아니라 effect 에서만 갱신/접근한다(react-hooks/refs 준수).
  const deckIdsRef = useRef(deckIds);
  useEffect(() => {
    deckIdsRef.current = deckIds;
  }, [deckIds]);

  const total = deck.length;
  const remaining = total - index;
  const current = index < total ? deck[index] : null;
  // 윗장 뒤에 겹칠 다음 미리보기 카드들(가까운 순). 스택 깊이를 만든다.
  const upcoming = deck.slice(index + 1, index + 1 + STACK_PEEK_COUNT);

  const nextMutation = useMutation({
    mutationFn: nextRecommendation,
    onSuccess: (data) => {
      const existing = deckIdsRef.current;
      const fresh = data.recommendations.filter(
        (rec) => !existing.has(rec.song.id),
      );
      if (fresh.length === 0) {
        // 같은 seed 로는 더 나올 게 없음 — 반복 호출 중단, 폴백/종료로 넘긴다.
        setSeedsExhausted(true);
        return;
      }
      setAppended((prev) => [...prev, ...fresh]);
    },
    // 실패해도 덱이 막히지 않도록 seed 경로를 닫고 부모 폴백으로 넘어간다.
    onError: () => setSeedsExhausted(true),
  });

  const { mutate: loadNextFromSeeds, isPending: isAppending } = nextMutation;

  // 남은 카드가 적어지면 다음 batch 를 미리 당겨온다 (무한 스크롤 sentinel 의 덱 버전).
  //  - 좋아요 seed 가 있으면 "부른 곡 기반" `/next` 로 적응적 append.
  //  - seed 가 없거나(콜드스타트) 소진되면 부모 batch(`onNeedMore`) 로 폴백.
  useEffect(() => {
    if (remaining > PREFETCH_THRESHOLD) {
      return;
    }
    if (isAppending) {
      return;
    }
    if (sessionId && seedSongIds.length > 0 && !seedsExhausted) {
      loadNextFromSeeds({
        sessionId,
        seedSongIds,
        excludeSongIds: Array.from(deckIdsRef.current),
      });
      return;
    }
    if (hasMore && !isFetchingMore) {
      onNeedMore();
    }
  }, [
    remaining,
    isAppending,
    sessionId,
    seedSongIds,
    seedsExhausted,
    hasMore,
    isFetchingMore,
    onNeedMore,
    loadNextFromSeeds,
  ]);

  const handleResolve = useCallback(
    (reaction: SwipeReaction, songId: number) => {
      recordReaction(songId, reaction);
      if (reaction === "like") {
        setLikedCount((prev) => prev + 1);
        // 좋아요 곡을 seed 에 누적(중복 방지) → 다음 `/next` 호출이 적응적으로 반영.
        setSeedSongIds((prev) =>
          prev.includes(songId) ? prev : [...prev, songId],
        );
        // 새 seed 가 생기면 이전에 닫혔던 seed 경로를 다시 연다.
        setSeedsExhausted(false);
      } else {
        setPassedCount((prev) => prev + 1);
      }
      setIndex((prev) => prev + 1);
    },
    [recordReaction],
  );

  // 첫 미결정 카드에만 어포던스(wiggle + 방향 힌트)를 노출한다.
  const showAffordance = index === 0 && likedCount === 0 && passedCount === 0;

  if (current) {
    return (
      <div className="mx-auto flex w-full max-w-md flex-col gap-4">
        <DeckProgress
          position={index + 1}
          total={total}
          hasMore={hasMore || (seedSongIds.length > 0 && !seedsExhausted)}
          likedCount={likedCount}
          passedCount={passedCount}
        />
        <SwipeCard
          key={current.song.id}
          item={current}
          upcoming={upcoming}
          userVoiceRange={userVoiceRange}
          showAffordance={showAffordance}
          onResolve={handleResolve}
        />
      </div>
    );
  }

  // 덱 소진 — 다음 batch 를 기다리는 중이면 로딩, 아니면 요약.
  return (
    <DeckEnd
      likedCount={likedCount}
      passedCount={passedCount}
      loadingMore={hasMore || isFetchingMore || isAppending}
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
  /** 윗장 뒤에 살짝 겹쳐 보여줄 다음 카드 미리보기(가까운 순). 스택 깊이를 만든다. */
  upcoming: RecommendedSongResponse[];
  userVoiceRange: UserVoiceRange;
  /** 첫 미결정 카드면 true — wiggle + 방향 힌트 캡션을 노출한다. */
  showAffordance: boolean;
  onResolve: (reaction: SwipeReaction, songId: number) => void;
};

function SwipeCard({
  item,
  upcoming,
  userVoiceRange,
  showAffordance,
  onResolve,
}: SwipeCardProps) {
  const prefersReducedMotion = usePrefersReducedMotion();
  const songId = item.song.id;
  const displayTitle = formatSongDisplayTitle(item.song);

  const { toggle: toggleLike } = useLikeToggleMutation(songId);
  const liked = useLikesStore((state) => state.likedSongIds.includes(songId));

  const cardRef = useRef<HTMLDivElement | null>(null);
  const dragStartXRef = useRef<number | null>(null);
  const resolvingRef = useRef(false);
  // 속도 추적용 — 마지막 포인터 표본 + 평활된 속도(px/ms).
  const lastSampleRef = useRef<{ x: number; t: number } | null>(null);
  const velocityRef = useRef(0);

  const [dragX, setDragX] = useState(0);
  const [isDragging, setIsDragging] = useState(false);
  const [exitDirection, setExitDirection] = useState<"left" | "right" | null>(
    null,
  );
  const [exitDurationMs, setExitDurationMs] = useState(SWIPE_EXIT_DURATION_MS);

  const commit = useCallback(
    (reaction: SwipeReaction, velocityX = 0) => {
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
      const duration = computeExitDurationMs(velocityX);
      setExitDurationMs(duration);
      setExitDirection(reaction === "like" ? "right" : "left");
      window.setTimeout(() => {
        onResolve(reaction, songId);
      }, duration);
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
    lastSampleRef.current = { x: event.clientX, t: performance.now() };
    velocityRef.current = 0;
    setIsDragging(true);
    event.currentTarget.setPointerCapture?.(event.pointerId);
  };

  const handlePointerMove = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (!isDragging || dragStartXRef.current === null) {
      return;
    }
    const now = performance.now();
    const last = lastSampleRef.current;
    if (last) {
      const dt = now - last.t;
      if (dt > 0) {
        const instantaneous = (event.clientX - last.x) / dt;
        // 지수 평활 — 최근 표본에 가중해 노이즈를 완화한다.
        velocityRef.current = velocityRef.current * 0.4 + instantaneous * 0.6;
        lastSampleRef.current = { x: event.clientX, t: now };
      }
    }
    setDragX(event.clientX - dragStartXRef.current);
  };

  const endDrag = () => {
    if (!isDragging) {
      return;
    }
    setIsDragging(false);
    dragStartXRef.current = null;
    const velocityX = velocityRef.current;
    const width = cardRef.current?.offsetWidth ?? 0;
    const intent = resolveSwipeIntent(dragX, width, velocityX);
    if (intent) {
      commit(intent, velocityX);
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

  // 트랜지션을 상태별로 인라인 구성한다 (exit 지속이 릴리즈 속도에 따라 가변).
  //  - 드래그 중: 손가락을 즉시 따라오도록 트랜지션 제거.
  //  - exit: 속도 기반 가변 지속 + ease-out 으로 관성 감속.
  //  - 스냅백/정지: spring easing 으로 자연스러운 관성 settle.
  const transition = isDragging
    ? "none"
    : exitDirection
      ? `transform ${exitDurationMs}ms var(--ease-out), opacity ${exitDurationMs}ms var(--ease-out)`
      : "transform var(--duration-slow) var(--ease-spring), opacity var(--duration-slow) var(--ease-out)";

  // wiggle 어포던스: 첫 미결정 카드만, 드래그/exit 전, 모션 허용 시.
  const wiggle =
    showAffordance && !isDragging && dragX === 0 && !exitDirection
      ? "animate-swipe-wiggle"
      : "";

  return (
    <div className="flex flex-col gap-4">
      {/* z-10 + isolate: exit 카드가 translateX(120%)+rotate 로 날아갈 때 아래 패스/좋아요
          버튼(뒤 형제)이 위로 덮어 카드를 가리지 않도록 카드면을 버튼보다 위에 쌓는다(#1799). */}
      <div className="relative isolate z-10">
        {/* 뒷장 스택 — 다음 카드들을 살짝 scale·offset·그림자로 겹쳐 덱 깊이를 만든다.
            가장 뒤(depth 큰 것)부터 렌더해 가까운 카드가 위로 오게 한다. */}
        {upcoming
          .map((peek, i) => ({ peek, depth: i + 1 }))
          .reverse()
          .map(({ peek, depth }) => (
            <DeckPeekCard key={peek.song.id} depth={depth} />
          ))}
        {/* 윗장 진입 래퍼 — 스택에서 위로 올라오는 settle(드래그 transform 과 분리). */}
        <div
          className={`relative z-10 ${
            prefersReducedMotion ? "" : "animate-deck-card-in"
          }`}
        >
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
              transition,
              touchAction: "pan-y",
            }}
            className={`select-none rounded-[var(--radius-lg)] bg-[var(--bg-base)] p-[var(--card-padding)] shadow-[var(--shadow-md)] ring-1 ring-[var(--border)] ${wiggle}`}
          >
            <SongDetailContent item={item} userVoiceRange={userVoiceRange} />
          </div>
        </div>
        {/* 스와이프 방향 힌트 — 드래그 중에만 보인다. */}
        <SwipeHint side="like" opacity={likeHintOpacity} />
        <SwipeHint side="pass" opacity={passHintOpacity} />
      </div>

      {/* 첫 카드 정적 어포던스 — 좌(패스)/우(좋아요) 방향을 글자로도 안내.
          버튼·카드 aria-label 이 AT 에 의미를 전달하므로 장식으로 둔다. */}
      {showAffordance ? (
        <div
          aria-hidden="true"
          data-testid="swipe-affordance"
          className="flex items-center justify-between px-2 text-xs text-[var(--text-tertiary)]"
        >
          <span>← 패스</span>
          <span className="font-medium">좌우로 밀어 선곡</span>
          <span>좋아요 →</span>
        </div>
      ) : null}

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

type DeckPeekCardProps = {
  /** 윗장 기준 깊이(1 = 바로 다음 장). 클수록 더 작고·아래로·옅게. */
  depth: number;
};

/**
 * 윗장 뒤에 겹쳐 보이는 다음 카드의 빈 표면 (#1763). 본문 없이 카드 면(둥근 모서리 +
 * 보더 + 그림자)만 그려 스택 깊이를 암시한다 — 본문을 복제하지 않아 가볍고 가독성을 해치지
 * 않는다. 윗장(`z-10`)보다 항상 뒤에 깔린다. 장식이라 `aria-hidden`.
 */
function DeckPeekCard({ depth }: DeckPeekCardProps) {
  const scale = 1 - depth * 0.04;
  const offsetY = depth * 10;
  const opacity = depth === 1 ? 0.85 : 0.6;
  return (
    <div
      aria-hidden="true"
      data-testid="swipe-peek-card"
      className="pointer-events-none absolute inset-0 rounded-[var(--radius-lg)] bg-[var(--bg-base)] shadow-[var(--shadow-md)] ring-1 ring-[var(--border)]"
      style={{
        transform: `translateY(${offsetY}px) scale(${scale})`,
        opacity,
        transition:
          "transform var(--duration-slow) var(--ease-spring), opacity var(--duration-slow) var(--ease-out)",
      }}
    />
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
        className="mx-auto flex w-full max-w-md flex-col items-center gap-3 rounded-[var(--radius-lg)] border border-[var(--border)] bg-[var(--bg-base)] p-8 text-center"
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
      className="mx-auto flex w-full max-w-md flex-col items-center gap-3 rounded-[var(--radius-lg)] border border-[var(--border)] bg-[var(--bg-base)] p-8 text-center"
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
