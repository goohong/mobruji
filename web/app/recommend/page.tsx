"use client";

/**
 * 추천 결과 페이지.
 *
 * docs/features/recommendation-algorithm-v1.md §5-2 응답 사용:
 *   - 세션에 저장된 sessionId 기반으로 voice-range를 조회해 음역 low/high를 얻고,
 *   - POST /api/v1/recommendations 호출 → 결과 카드 리스트 노출.
 *
 * 세션 없음/음역대 미등록인 경우 음역대 입력 페이지로 안내한다.
 *
 * 이슈 #75 / PR #76 (2026-05-21):
 *   - 카드 렌더는 `./components/SongCard`로 분리. 음역대 막대 그래프 시각화는 폐기.
 *   - 로딩 중에는 SongCardSkeleton 다수 노출 → 결과 자리에 대한 공간 인지 향상.
 *   - 빈 결과 시 음역대 재입력 CTA 강조.
 *
 * 이슈 #83 #84 / PR #85 (2026-05-21):
 *   - "다른 곡 추천받기" 버튼 추가. 클릭 시 현재 보여준 곡 ID들을 store
 *     `excludedSongIds`에 누적하고 다음 createRecommendation 호출에 함께 전달.
 *   - BE는 `excludeSongIds`를 SeedDeriver 입력에 포함(PR #64) + entity 영속화(PR #74)
 *     하므로 같은 voiceRange/sessionId라도 매번 다른 결과를 결정성 있게 반환한다.
 *   - 응답 곡 수가 0인 경우(이미 누적 셋이 카탈로그를 다 덮은 경우) "더 이상
 *     추천할 곡이 없어요" fallback을 노출하고 다시 버튼은 숨긴다.
 *
 * 이슈 #282 (2026-05-22):
 *   - 헤더에 voice-range source method 뱃지(MIC/OCTAVE/SELF) 노출.
 *   - MIC_MEASURE 소스일 때만 "마이크로 다시 측정" 1차 액션 링크 강조.
 *   - voice-range mutation onSuccess 가 react-query 캐시에 응답을 prime 하므로
 *     이 페이지의 useQuery 는 캐시 히트로 즉시 추천 mutation 발화.
 *
 * 이슈 #321 (2026-05-22):
 *   - "다른 곡 추천받기" 단일 액션을 폐기하고 **무한 스크롤** 패턴으로 전환.
 *   - `useInfiniteQuery` + IntersectionObserver(sentinel)로 리스트 하단 진입 시
 *     자동으로 다음 추천 batch 를 페치한다. 모바일 PWA 자연 UX 우선.
 *   - 매 페이지의 queryFn 은 호출 시점의 누적 `excludedSongIds`(store snapshot)을
 *     전달한다 → BE SeedDeriver(PR #64) + entity 영속화(PR #74) 효과로 결정성을
 *     유지하면서도 페이지마다 다른 결과를 반환받는다.
 *   - 시드 소진(빈 페이지) 감지 → `getNextPageParam`이 `undefined`를 반환해 추가
 *     페치를 멈춘다. 첫 페이지부터 빈 응답이면 음역대 재입력 fallback CTA,
 *     2페이지 이후 빈 응답이면 "더 이상 추천할 곡이 없어요" 안내 + 음역대 재입력 CTA.
 */

import {
  useCallback,
  useEffect,
  useId,
  useMemo,
  useRef,
  useState,
} from "react";
import Link from "next/link";
import { useInfiniteQuery, useQuery } from "@tanstack/react-query";

import { ApiError } from "@/lib/api/client";
import {
  AgeGroup,
  createRecommendation,
  Mood,
  RecommendationCreateRequest,
  RecommendationPersona,
  RecommendationResponse,
  RecommendedSongResponse,
} from "@/lib/api/recommendation";
import {
  readVoiceRange,
  VoiceRangeResponse,
  VoiceRangeSourceMethod,
} from "@/lib/api/voice-range";
import { midiToKoreanNoteName } from "@/lib/notes";
import { VoiceRangeIntuition } from "@/app/voice-range/components/VoiceRangeIntuition";
import { formatSongDisplayTitle } from "@/lib/songTitle";
import { useHistoryStore } from "@/store/history";
import { useSessionStore } from "@/store/session";

import { RecommendRefinePanel } from "./components/RecommendRefinePanel";
import { SongCard, SongCardSkeleton } from "./components/SongCard";
import { SongDetailSheet } from "./components/SongDetailSheet";
import { SongDetailContent } from "./components/SongDetailContent";
import { SwipeDeck } from "./components/SwipeDeck";

const SKELETON_COUNT = 4;
/**
 * IntersectionObserver sentinel 의 rootMargin.
 *
 * 사용자가 리스트 끝에 도달하기 전에 미리 다음 batch 페치를 트리거해서
 * 무한 스크롤이 "끊김 없이" 보이도록 한다. 너무 크면 첫 페이지 마운트 직후에
 * 두 번째 페이지가 즉시 페치돼서 의도와 어긋날 수 있으니 적당히 400px 만 둔다.
 */
const SENTINEL_ROOT_MARGIN = "400px";

export default function RecommendPage() {
  const sessionId = useSessionStore((state) => state.sessionId);

  if (!sessionId) {
    return <NoSessionFallback />;
  }

  return <RecommendContent sessionId={sessionId} />;
}

type RecommendContentProps = {
  sessionId: string;
};

function RecommendContent({ sessionId }: RecommendContentProps) {
  const voiceRangeQuery = useQuery<VoiceRangeResponse, Error>({
    queryKey: ["voice-range", sessionId],
    queryFn: () => readVoiceRange(sessionId),
  });

  const voiceRangeIdFromStore = useSessionStore((state) => state.voiceRangeId);
  const appendExcluded = useSessionStore((state) => state.appendExcluded);
  const appendHistory = useHistoryStore((state) => state.appendRecommendation);

  // 즉석 페르소나(P-C) 입력 — 분위기/나이대 (directive roadmap-mood-age-ui).
  // 영속하지 않는 화면 로컬 상태. 값이 바뀌면 queryKey 가 바뀌어 추천이 첫 페이지부터
  // 재발화된다. 미선택(null)은 createRecommendation 에서 필드를 생략 → 기존 동작 유지.
  const [selectedMood, setSelectedMood] = useState<Mood | null>(null);
  const [selectedAgeGroup, setSelectedAgeGroup] = useState<AgeGroup | null>(
    null,
  );
  // 추천 의도 모드(P-E 안전곡 등, 이슈 #1600) — mood/ageGroup 과 동일한 화면 로컬 상태.
  // 값이 바뀌면 queryKey 가 바뀌어 추천이 첫 페이지부터 재발화된다. null(미선택)은
  // createRecommendation 에서 persona 를 생략 → 현행 default 추천(하위호환).
  const [selectedPersona, setSelectedPersona] =
    useState<RecommendationPersona | null>(null);

  const isVoiceRangeReady =
    voiceRangeQuery.isSuccess &&
    voiceRangeQuery.data !== undefined &&
    voiceRangeQuery.data.sessionId !== undefined;
  const voiceRangeSessionId = voiceRangeQuery.data?.sessionId;
  const voiceRangeLow = voiceRangeQuery.data?.lowestNoteMidi;
  const voiceRangeHigh = voiceRangeQuery.data?.highestNoteMidi;

  /**
   * 무한 스크롤 핵심 쿼리.
   *
   * - `enabled`: voice-range 가 준비돼야 페치를 시작한다.
   * - `queryFn`: 매 호출 시점의 누적 `excludedSongIds`(store snapshot)을
   *   BE 로 전달한다. 같은 voiceRange 라도 누적이 늘어나면 SeedDeriver 입력이
   *   달라져서 다른 결과를 결정성 있게 받는다.
   * - `getNextPageParam`: 마지막 페이지 응답이 비었으면 `undefined` → hasNextPage=false.
   *   비어있지 않으면 다음 pageIndex 를 그대로 돌려준다 (페이지 카운터는 단순 증가).
   * - `initialPageParam`: 첫 페이지 인덱스 0.
   *
   * onSuccess 는 PR #85 부터 zustand store 에 결과 ID 를 누적하고 history 에
   * push 하는 역할을 했다. v5 react-query 에서 `useInfiniteQuery` 에는 onSuccess 가
   * 제거됐으므로 별도 useEffect 로 마지막 페이지 변화를 감지해 동일 효과를 낸다.
   */
  const recommendQuery = useInfiniteQuery<
    RecommendationResponse,
    Error,
    { pages: RecommendationResponse[]; pageParams: number[] },
    readonly unknown[],
    number
  >({
    queryKey: [
      "recommendations",
      sessionId,
      voiceRangeIdFromStore,
      selectedMood,
      selectedAgeGroup,
      selectedPersona,
    ],
    enabled:
      isVoiceRangeReady &&
      voiceRangeLow !== undefined &&
      voiceRangeHigh !== undefined,
    initialPageParam: 0,
    queryFn: () => {
      const request: RecommendationCreateRequest = {
        sessionId: voiceRangeSessionId!,
        voiceRangeLow: voiceRangeLow!,
        voiceRangeHigh: voiceRangeHigh!,
        // 호출 시점의 최신 누적 리스트를 BE 로 전달.
        excludeSongIds: useSessionStore.getState().excludedSongIds,
      };
      // 미선택(null)이면 필드를 생략해 기존 호출 형태/하위호환을 유지한다.
      if (selectedMood !== null) {
        request.mood = selectedMood;
      }
      if (selectedAgeGroup !== null) {
        request.ageGroup = selectedAgeGroup;
      }
      if (selectedPersona !== null) {
        request.persona = selectedPersona;
      }
      return createRecommendation(request);
    },
    getNextPageParam: (lastPage, allPages) => {
      if (lastPage.recommendations.length === 0) {
        return undefined;
      }
      return allPages.length;
    },
  });

  const pages = recommendQuery.data?.pages;
  // 누적/히스토리 push 는 "방금 새로 도착한 페이지" 1건에 대해서만 수행해야 중복이 없다.
  // pages.length 가 늘어나는 순간만 트리거되도록 ref 로 마지막 처리한 길이를 기억한다.
  const lastProcessedPageCountRef = useRef(0);
  // RecommendContent 가 새 키(예: 다른 sessionId)로 다시 마운트되거나 queryKey 가
  // 바뀌어 데이터가 새로 발급되는 경우, ref 가 stale 한 값을 들고 있으면
  // "이미 처리한 페이지" 로 오인해 누락이 발생할 수 있다. queryKey 변경 시 ref 를
  // 0 으로 리셋한다.
  useEffect(() => {
    lastProcessedPageCountRef.current = 0;
  }, [
    sessionId,
    voiceRangeIdFromStore,
    selectedMood,
    selectedAgeGroup,
    selectedPersona,
  ]);

  useEffect(() => {
    if (!pages || pages.length === 0) {
      return;
    }
    if (pages.length <= lastProcessedPageCountRef.current) {
      return;
    }
    // 새로 도착한 페이지들만 순회. 보통 한 번에 1개씩 늘지만 방어적으로 범위로 처리.
    const newPages = pages.slice(lastProcessedPageCountRef.current);
    const snapshot = voiceRangeQuery.data;
    for (const page of newPages) {
      const ids = page.recommendations.map((rec) => rec.song.id);
      if (ids.length > 0) {
        // store 에 누적 — 다음 페이지 queryFn 호출 시 BE 로 전달된다.
        appendExcluded(ids);
        // 추천 히스토리(closes #134) 에 페이지 단위로 push. 빈 페이지는 push 하지 않아
        // "다시 보기" 화면이 빈 카드로 더럽혀지지 않게 한다.
        appendHistory({
          requestId: page.requestId,
          voiceRangeId: voiceRangeIdFromStore,
          songs: page.recommendations,
          // 호출 시점 누적 스냅샷을 그대로 기록 — 이 페이지가 어떤 제외 셋과 함께
          // 발급됐는지 영구 기록.
          excludedSongIds: useSessionStore.getState().excludedSongIds,
          voiceRangeLowMidi: voiceRangeLow,
          voiceRangeHighMidi: voiceRangeHigh,
          voiceRangeSourceMethod: snapshot?.sourceMethod,
        });
      }
    }
    lastProcessedPageCountRef.current = pages.length;
  }, [
    pages,
    appendExcluded,
    appendHistory,
    voiceRangeIdFromStore,
    voiceRangeLow,
    voiceRangeHigh,
    voiceRangeQuery.data,
  ]);

  if (voiceRangeQuery.isLoading) {
    return <StatusShell title="음역대를 불러오는 중..." />;
  }

  if (voiceRangeQuery.isError) {
    const error = voiceRangeQuery.error;
    if (error instanceof ApiError && error.status === 404) {
      return <NoSessionFallback />;
    }
    return (
      <StatusShell
        title="음역대 정보를 불러오지 못했습니다"
        description={error.message}
        ctaHref="/voice-range"
        ctaLabel="다시 입력하기"
      />
    );
  }

  const voiceRange = voiceRangeQuery.data;
  if (!voiceRange) {
    return <NoSessionFallback />;
  }

  /*
   * ADR-0018 단계 4 PR 4 — /recommend 페이지 토큰 swap (#1150 secondary pages 패턴).
   *
   * swap 한 요소 (first-paint 핵심):
   *  1) <main> 배경 + padding : bg-zinc-50 dark:bg-zinc-950, px-6 py-12 → tokens
   *  2) h1 : text-zinc-900 dark:text-zinc-50 → --text-primary
   *  3) "내 음역대" 부제 p : text-zinc-600 dark:text-zinc-400 → --text-secondary
   *  4) header Link 2개 (마이크 다시 측정 / 음역대 다시 입력) : zinc text → tokens
   *  5) error 박스 (red bg + button) → --danger-* 토큰 swap (#1044 PR 9 적용)
   *  6) 빈 결과 / hasNextPage=false fallback 카드 (2건) : rounded-2xl ring-zinc-200
   *     bg-white dark:* → tokens (--radius-lg / --bg-base / --border)
   *  7) fallback CTA Link (2건) : bg-zinc-900 dark:bg-zinc-50 → --brand-500/600
   *  8) StatusShell <main> 배경/padding/h1/부제/CTA : tokens
   *
   * 미swap (후속 PR 양보):
   *  - SourceMethodBadge 내부 (zinc / emerald) — 자체 함수 컴포넌트, 별도 토큰 그룹
   *  - (resolved #1044 PR 9) error 박스 (text-red / bg-red) → --danger-bg / --danger-border / --danger-fg-strong / --danger-cta-* 토큰 swap.
   *  - SongCard / SongCardSkeleton — 별도 컴포넌트 (PR 6 #1160 진행 중)
   *
   * 본 PR 8 (#1044 단계 4 PR 8) 에서 추가:
   *  - "Step 2" caption (text-zinc-500 dark:text-zinc-400 → text-[var(--text-caption)])
   *    — tokens.css `--text-caption` 신규 정의 (zinc-500 light / zinc-400 dark).
   *
   * 다크 모드: tokens.css `:where(html.dark)` selector 자동 swap. swap 한 element 에서
   * `dark:` prefix 제거.
   */
  return (
    <main className="flex flex-1 flex-col items-center bg-[var(--bg-subtle)] px-[var(--page-padding-x)] py-[var(--page-padding-y)]">
      <div className="w-full max-w-2xl flex flex-col gap-8">
        <header className="space-y-2">
          <p className="text-xs font-medium uppercase tracking-widest text-[var(--text-caption)]">
            Step 2
          </p>
          <h1 className="text-2xl font-semibold text-[var(--text-primary)]">
            추천 결과
          </h1>
          <VoiceRangeHeaderSummary voiceRange={voiceRange} />
        </header>

        {/* (V1·V2·V5) 결과 우선 — 의도 모드 + 분위기/나이대 필터는 접이식 "추천 다듬기"
            1줄 바로 축소해 결과 카드가 헤더 직후 즉시 보이게 한다. */}
        <RecommendRefinePanel
          selectedPersona={selectedPersona}
          onPersonaChange={setSelectedPersona}
          selectedMood={selectedMood}
          selectedAgeGroup={selectedAgeGroup}
          onMoodChange={setSelectedMood}
          onAgeGroupChange={setSelectedAgeGroup}
        />

        <RecommendationFeed
          query={recommendQuery}
          userVoiceRangeLow={voiceRange.lowestNoteMidi}
          userVoiceRangeHigh={voiceRange.highestNoteMidi}
          activePersona={selectedPersona}
        />

        {/* (V3, closes #1601) 모임 사회자(P-D) 모드 진입 — "다 같이 즐길 곡" 시퀀스 화면으로
            이동한다. 결과 흐름과 시각적으로 분리된 2차 액션 링크 행으로 강등해(">" 어포던스)
            혼자 부를 사용자의 오인을 줄인다. 누르지 않으면 단일 곡 추천 흐름은 불변. */}
        <Link
          href="/recommend/host"
          className="flex items-center justify-between gap-3 self-stretch rounded-[var(--radius-md)] border border-[var(--border)] px-4 py-3 text-left text-[var(--text-secondary)] transition-colors duration-[var(--duration-base)] hover:bg-[var(--bg-subtle)] focus:outline-none focus-visible:ring-2 focus-visible:ring-[var(--cta-secondary-ring)] focus-visible:ring-offset-2"
        >
          <span className="flex flex-col gap-0.5">
            <span className="text-sm font-medium text-[var(--text-primary)]">
              여럿이 함께 부르나요? 모임 사회자 모드
            </span>
            <span className="text-xs text-[var(--text-caption)]">
              도입·고조·마무리 단계별 흐름으로 자리를 띄워 줍니다
            </span>
          </span>
          <span aria-hidden="true" className="text-[var(--text-tertiary)]">
            ›
          </span>
        </Link>
      </div>
    </main>
  );
}

type RecommendationFeedProps = {
  query: ReturnType<typeof useInfiniteQuery<
    RecommendationResponse,
    Error,
    { pages: RecommendationResponse[]; pageParams: number[] },
    readonly unknown[],
    number
  >>;
  /**
   * 사용자 음역대 — 추천 카드의 "자세히 보기" 패널에서 음역 적합 점수를 계산할 때 사용.
   * (closes #141) 추천 컨텍스트에서는 항상 알 수 있는 값이라 필수로 받는다.
   */
  userVoiceRangeLow: number;
  userVoiceRangeHigh: number;
  /**
   * 사용자가 고른 의도 페르소나(P-E 안전곡 등, 이슈 #1600). 결과 카드에 페르소나 사유
   * fallback 근거로 전달한다. 미선택(null)이면 카드는 페르소나 사유 줄을 생략한다.
   */
  activePersona: RecommendationPersona | null;
};

function RecommendationFeed({
  query,
  userVoiceRangeLow,
  userVoiceRangeHigh,
  activePersona,
}: RecommendationFeedProps) {
  const {
    data,
    error,
    isPending,
    isFetchingNextPage,
    hasNextPage,
    fetchNextPage,
    refetch,
  } = query;

  // closes #323 — 카드 클릭 시 페이지 이동 대신 상세 모달.
  // selected 는 현재 펼쳐진 카드 1개. null 이면 모달 닫힘. 같은 곡을 다시 클릭하거나
  // 다른 곡을 클릭하면 setSelected 가 갱신되어 모달이 그 곡으로 다시 렌더된다.
  // 훅 규칙 준수를 위해 early return 이전에 호출.
  const [selected, setSelected] = useState<RecommendedSongResponse | null>(null);

  // closes #1489 — 리스트(무한 스크롤) ↔ 스와이프(한 곡씩 선곡) 뷰 토글.
  // 기본은 기존 리스트 — 회귀 0. 스와이프는 같은 무한 쿼리를 한 곡씩 소비한다.
  const [viewMode, setViewMode] = useState<"list" | "swipe">("list");

  // 모든 페이지의 추천 곡을 평탄화. 페이지 경계 정보는 사용자에게 노출하지 않는다.
  const allRecommendations = useMemo(() => {
    if (!data) {
      return [];
    }
    return data.pages.flatMap((page) => page.recommendations);
  }, [data]);

  /**
   * (closes #426 / closes #443) 스크린 리더용 라이브 영역 메시지.
   *
   * 시각 사용자는 무한 스크롤로 카드가 화면에 추가되는 것을 자연스럽게 보지만,
   * 스크린 리더 사용자에게는 "내가 스크롤한 결과로 추천이 더 로드됐는지" 가
   * 불투명하다. `aria-live="polite"` 영역에 메시지를 갱신해 다음을 안내한다:
   *   - 첫 페이지 도착: "추천 N건을 불러왔습니다."
   *   - 추가 페이지 도착: "추천 M건이 더 추가되었습니다. (총 N건)"
   *
   * #443 리팩터: 이전에는 `useEffect` 안에서 `setLiveMessage` 를 호출해
   * `react-hooks/set-state-in-effect` 룰을 inline-disable 로 회피했다.
   * 이제 React 공식 권장 "Adjusting State While Rendering" 패턴
   * (https://react.dev/reference/react/useState#storing-information-from-previous-renders)
   * 으로 전환한다 — 렌더 중에 ref 와 현재 값을 비교해 변화 시점에만 setState 를
   * 호출하면 React 가 현재 렌더를 버리고 즉시 새 state 로 재렌더하므로
   * effect 가 필요 없고 추가 paint 도 발생하지 않는다. 결과적으로 inline
   * eslint-disable 3건을 제거할 수 있다.
   *
   * 동작은 종전과 동일: 페이지 수 증가 시점에만 메시지 갱신, `isFetchingNextPage`
   * 토글이 아니라 데이터 도착 시점을 기준으로 갱신해 "로딩 중" 메시지와
   * 중복되지 않는다.
   *
   * (closes #749) 이전에는 `useRef` 로 prevPageCount 를 추적했으나
   * `react-hooks/refs` 룰이 렌더 중 ref 접근 자체를 금지한다. React 공식
   * 권장 패턴은 ref 가 아니라 `useState` 로 prev value 를 보관하는 것이다
   * (https://react.dev/reference/react/useState#storing-information-from-previous-renders).
   * state 라도 렌더 중 set 호출 시 React 가 현재 렌더를 버리고 즉시
   * 재렌더하므로 추가 paint 는 발생하지 않는다.
   */
  const [previousPageCount, setPreviousPageCount] = useState(0);
  const [liveMessage, setLiveMessage] = useState("");

  const pageCount = data?.pages.length ?? 0;
  if (pageCount !== previousPageCount) {
    setPreviousPageCount(pageCount);
    if (pageCount === 0) {
      // 쿼리 reset 등으로 페이지가 사라진 경우 메시지도 초기화.
      setLiveMessage("");
    } else {
      const latestPage = data?.pages[pageCount - 1];
      const addedCount = latestPage?.recommendations.length ?? 0;
      const totalCount = allRecommendations.length;
      if (previousPageCount === 0) {
        setLiveMessage(`추천 ${totalCount}건을 불러왔습니다.`);
      } else {
        setLiveMessage(
          `추천 ${addedCount}건이 더 추가되었습니다. (총 ${totalCount}건)`,
        );
      }
    }
  }

  // IntersectionObserver 로 sentinel 진입을 감지해 다음 batch 페치.
  // ref 콜백 패턴: sentinel DOM 노드가 마운트/언마운트될 때마다 observer 를
  // 다시 연결한다. 의존성에 fetchNextPage/hasNextPage/isFetchingNextPage 가 들어가서
  // 상태가 바뀌면 콜백이 새 함수로 발급되어 observer 가 갱신된다.
  const sentinelRef = useCallback(
    (node: HTMLDivElement | null) => {
      if (!node) {
        return;
      }
      if (typeof IntersectionObserver === "undefined") {
        // SSR/구식 브라우저 safety net — 진입 감지 불가하면 무한 스크롤이 동작하지
        // 않지만 본 페이지는 client component 라 실질 영향은 거의 없다.
        return;
      }
      if (!hasNextPage || isFetchingNextPage) {
        return;
      }
      const observer = new IntersectionObserver(
        (entries) => {
          const entry = entries[0];
          if (entry?.isIntersecting) {
            fetchNextPage();
          }
        },
        { rootMargin: SENTINEL_ROOT_MARGIN },
      );
      observer.observe(node);
      return () => {
        observer.disconnect();
      };
    },
    [fetchNextPage, hasNextPage, isFetchingNextPage],
  );

  // 1차 페치(첫 페이지) 로딩 — skeleton 다수로 카드 공간 인지를 유지.
  if (isPending) {
    return (
      <ul
        aria-busy="true"
        aria-label="추천 결과 로딩 중"
        className="flex flex-col gap-3"
      >
        {Array.from({ length: SKELETON_COUNT }).map((_, idx) => (
          <SongCardSkeleton key={idx} />
        ))}
      </ul>
    );
  }

  if (error) {
    return (
      <div className="flex flex-col gap-3 rounded-2xl border border-[var(--danger-border)] bg-[var(--danger-bg)] p-4">
        <p className="text-sm text-[var(--danger-fg-strong)]">
          추천을 불러오지 못했습니다.{" "}
          {error instanceof ApiError
            ? `${error.status}: ${error.message}`
            : error.message}
        </p>
        <button
          type="button"
          onClick={() => refetch()}
          className="inline-flex h-10 w-fit items-center justify-center rounded-full bg-[var(--danger-cta-bg)] px-4 text-sm font-medium text-white hover:bg-[var(--danger-cta-bg-hover)]"
        >
          다시 시도
        </button>
      </div>
    );
  }

  // 첫 페이지부터 빈 경우 = 시작 시점에 카탈로그가 누적 제외 셋에 이미 모두 덮인 케이스.
  // 음역대 재입력 안내로 흐름을 끊는다.
  if (allRecommendations.length === 0) {
    return (
      <div
        role="status"
        className="flex flex-col items-start gap-3 rounded-[var(--radius-lg)] border border-[var(--border)] bg-[var(--bg-base)] p-5"
      >
        <p className="text-sm text-[var(--text-secondary)]">
          더 이상 추천할 곡이 없어요. 음역대를 다시 입력해 보세요.
        </p>
        <Link
          href="/voice-range"
          className="inline-flex h-10 items-center justify-center rounded-full bg-[var(--brand-500)] px-4 text-sm font-medium text-white transition-colors duration-[var(--duration-base)] hover:bg-[var(--brand-600)] hover:shadow-[var(--shadow-brand)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--brand-500)] focus-visible:ring-offset-2"
        >
          음역대 다시 입력
        </Link>
      </div>
    );
  }

  const userRange = {
    lowMidi: userVoiceRangeLow,
    highMidi: userVoiceRangeHigh,
  };

  if (viewMode === "swipe") {
    return (
      <div className="flex flex-col gap-4">
        <ViewModeToggle viewMode={viewMode} onChange={setViewMode} />
        <SwipeDeck
          recommendations={allRecommendations}
          userVoiceRange={userRange}
          hasMore={hasNextPage}
          isFetchingMore={isFetchingNextPage}
          onNeedMore={fetchNextPage}
        />
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-4">
      <ViewModeToggle viewMode={viewMode} onChange={setViewMode} />
      {/*
        (closes #426) 스크린 리더 라이브 영역 — 첫 페이지/추가 페이지 도착 시 안내.
        시각적으로는 `sr-only` 로 숨기지만 SR 은 polite 큐로 안내 메시지를 읽는다.
        `aria-atomic="true"` 로 메시지 전체를 매번 새로 읽도록 강제 — 부분 갱신
        헤더리스 announce 를 피한다.
      */}
      <div
        role="status"
        aria-live="polite"
        aria-atomic="true"
        data-testid="recommend-live-region"
        className="sr-only"
      >
        {liveMessage}
      </div>
      <ul className="flex flex-col gap-3">
        {allRecommendations.map((item, index) => (
          <SongCard
            key={item.song.id}
            item={item}
            index={index}
            userVoiceRange={userRange}
            activePersona={activePersona}
            onShowDetail={() => setSelected(item)}
          />
        ))}
      </ul>
      <SongDetailSheet
        open={selected !== null}
        onClose={() => setSelected(null)}
        titleLabel={selected ? formatSongDisplayTitle(selected.song) : ""}
      >
        {selected ? (
          <SongDetailContent item={selected} userVoiceRange={userRange} />
        ) : null}
      </SongDetailSheet>
      {/*
        Footer 영역:
          - hasNextPage 가 true 면 sentinel + skeleton(로딩 중일 때) 노출.
          - hasNextPage 가 false 면 시드 소진 안내 + 음역대 재입력 CTA 노출.
            (이미 한 페이지 이상 본 후이므로 "더 이상 없어요" 톤은 부드럽게.)
      */}
      {hasNextPage ? (
        <div className="flex flex-col gap-3">
          {isFetchingNextPage ? (
            <ul
              aria-busy="true"
              aria-label="다음 추천 결과 로딩 중"
              className="flex flex-col gap-3"
            >
              {Array.from({ length: 2 }).map((_, idx) => (
                <SongCardSkeleton key={idx} />
              ))}
            </ul>
          ) : null}
          {/*
            sentinel: 사용자가 리스트 끝에 가까워지면 IntersectionObserver 가
            진입을 감지해 fetchNextPage 를 호출한다. 시각적으로는 보이지 않지만
            테스트가 식별할 수 있도록 data-testid 를 둔다.
          */}
          <div
            ref={sentinelRef}
            data-testid="recommend-sentinel"
            aria-hidden="true"
            className="h-1 w-full"
          />
        </div>
      ) : (
        <div
          role="status"
          className="flex flex-col items-start gap-3 rounded-[var(--radius-lg)] border border-[var(--border)] bg-[var(--bg-base)] p-5"
        >
          <p className="text-sm text-[var(--text-secondary)]">
            추천할 수 있는 곡을 모두 보여드렸어요. 음역대나 분위기를 바꿔서 다시
            시도해 보세요.
          </p>
          <Link
            href="/voice-range"
            className="inline-flex h-10 items-center justify-center rounded-full bg-[var(--brand-500)] px-4 text-sm font-medium text-white transition-colors duration-[var(--duration-base)] hover:bg-[var(--brand-600)] hover:shadow-[var(--shadow-brand)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--brand-500)] focus-visible:ring-offset-2"
          >
            음역대 다시 입력
          </Link>
        </div>
      )}
    </div>
  );
}

type ViewModeToggleProps = {
  viewMode: "list" | "swipe";
  onChange: (mode: "list" | "swipe") => void;
};

/**
 * 리스트 ↔ 스와이프 뷰 전환 세그먼트 토글 (closes #1489).
 *
 * `role="radiogroup"` + 각 버튼 `aria-checked` 로 스크린 리더가 현재 뷰를 읽게 한다.
 */
function ViewModeToggle({ viewMode, onChange }: ViewModeToggleProps) {
  return (
    <div
      role="radiogroup"
      aria-label="추천 보기 방식"
      className="flex items-center gap-1 self-start rounded-full bg-[var(--bg-subtle)] p-1"
    >
      {(
        [
          { mode: "list", label: "리스트" },
          { mode: "swipe", label: "스와이프" },
        ] as const
      ).map(({ mode, label }) => {
        const active = viewMode === mode;
        return (
          <button
            key={mode}
            type="button"
            role="radio"
            aria-checked={active}
            onClick={() => onChange(mode)}
            className={`min-h-9 rounded-full px-4 text-sm font-medium transition-colors duration-[var(--duration-base)] focus:outline-none focus-visible:ring-2 focus-visible:ring-[var(--cta-secondary-ring)] ${
              active
                ? "bg-[var(--bg-base)] text-[var(--text-primary)] shadow-[var(--shadow-sm)]"
                : "text-[var(--text-secondary)] hover:text-[var(--text-primary)]"
            }`}
          >
            {label}
          </button>
        );
      })}
    </div>
  );
}

type StatusShellProps = {
  title: string;
  description?: string;
  ctaHref?: string;
  ctaLabel?: string;
};

function StatusShell({
  title,
  description,
  ctaHref,
  ctaLabel,
}: StatusShellProps) {
  return (
    <main className="flex flex-1 flex-col items-center justify-center bg-[var(--bg-subtle)] px-[var(--page-padding-x)] py-[var(--page-padding-y)] text-center">
      <div className="w-full max-w-md flex flex-col items-center gap-4">
        <h1 className="text-xl font-semibold text-[var(--text-primary)]">
          {title}
        </h1>
        {description ? (
          <p className="text-sm text-[var(--text-secondary)]">{description}</p>
        ) : null}
        {ctaHref && ctaLabel ? (
          <Link
            href={ctaHref}
            className="inline-flex h-11 items-center justify-center rounded-full bg-[var(--brand-500)] px-5 text-sm font-medium text-white transition-colors duration-[var(--duration-base)] hover:bg-[var(--brand-600)] hover:shadow-[var(--shadow-brand)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--brand-500)] focus-visible:ring-offset-2"
          >
            {ctaLabel}
          </Link>
        ) : null}
      </div>
    </main>
  );
}

function NoSessionFallback() {
  return (
    <StatusShell
      title="음역대가 아직 등록되지 않았습니다"
      description="추천을 받으려면 먼저 음역대를 입력해 주세요."
      ctaHref="/voice-range"
      ctaLabel="음역대 입력하러 가기"
    />
  );
}

type VoiceRangeHeaderSummaryProps = {
  voiceRange: VoiceRangeResponse;
};

/**
 * 헤더 음역대 요약 + 접이식 보조 정보 (recommend-page-visual-ux-audit #1711).
 *
 * 결과 우선 노출을 위해 헤더에는 "내 음역대: X ~ Y" + 소스 뱃지만 상시 노출하고,
 * 음역 직관 막대(VoiceRangeIntuition)·재측정/재입력 링크 등 보조 정보는 "음역대 자세히"
 * disclosure(기본 접힘) 안으로 내린다 → 모바일에서 첫 곡 카드가 더 위로 올라온다.
 */
function VoiceRangeHeaderSummary({ voiceRange }: VoiceRangeHeaderSummaryProps) {
  const [expanded, setExpanded] = useState(false);
  const detailId = useId();

  return (
    <div className="space-y-2">
      <div className="flex flex-wrap items-center gap-2">
        <p className="text-sm text-[var(--text-secondary)]">
          내 음역대: {midiToKoreanNoteName(voiceRange.lowestNoteMidi)} ~{" "}
          {midiToKoreanNoteName(voiceRange.highestNoteMidi)}
        </p>
        <SourceMethodBadge sourceMethod={voiceRange.sourceMethod} />
        <button
          type="button"
          aria-expanded={expanded}
          aria-controls={detailId}
          onClick={() => setExpanded((prev) => !prev)}
          className="inline-flex items-center gap-1 text-xs font-medium text-[var(--text-caption)] underline-offset-4 hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-[var(--cta-secondary-ring)] focus-visible:ring-offset-2"
        >
          음역대 자세히
          <span
            aria-hidden="true"
            className={`transition-transform duration-[var(--duration-base)] ${
              expanded ? "rotate-180" : ""
            }`}
          >
            ⌄
          </span>
        </button>
      </div>

      {expanded ? (
        <div id={detailId} className="space-y-2">
          <VoiceRangeIntuition
            lowMidi={voiceRange.lowestNoteMidi}
            highMidi={voiceRange.highestNoteMidi}
            compact
          />
          <div className="flex flex-wrap items-center gap-x-4 gap-y-1 pt-1">
            {/* (closes #282) MIC 측정 결과면 "마이크로 다시 측정" 을 1차 액션으로
                강조한다. 자동 측정 결과를 보던 사용자가 "조금 더 끝까지 내볼까?"
                할 때 한 번 클릭으로 같은 흐름에 다시 들어가게 한다. */}
            {voiceRange.sourceMethod === "MIC_MEASURE" ? (
              <Link
                href="/voice-range/auto"
                className="text-sm font-medium text-[var(--text-primary)] underline-offset-4 hover:underline"
              >
                마이크로 다시 측정
              </Link>
            ) : null}
            <Link
              href="/voice-range"
              className="text-sm font-medium text-[var(--text-secondary)] underline-offset-4 hover:underline"
            >
              음역대 다시 입력
            </Link>
          </div>
        </div>
      ) : null}
    </div>
  );
}

type SourceMethodBadgeProps = {
  sourceMethod: VoiceRangeSourceMethod;
};

/**
 * 추천 결과 헤더에 노출하는 음역대 측정 소스 뱃지 (closes #282).
 *
 * "내 음역대가 어떻게 측정된 결과인지" 를 한눈에 보여줘서 사용자가
 * 이 추천 결과를 어떤 입력값과 연결할지 추론할 수 있게 한다.
 *  - MIC_MEASURE → 마이크 측정 (emerald — 정확도 신호)
 *  - OCTAVE_PICK → 직접 선택 (zinc — 중립)
 *  - SELF_REPORT → 자가 보고 (zinc — 중립)
 */
function SourceMethodBadge({ sourceMethod }: SourceMethodBadgeProps) {
  const { label, tone } = describeSourceMethod(sourceMethod);
  return (
    <span
      data-testid="voice-range-source-badge"
      className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ${tone}`}
    >
      {label}
    </span>
  );
}

function describeSourceMethod(sourceMethod: VoiceRangeSourceMethod): {
  label: string;
  tone: string;
} {
  switch (sourceMethod) {
    case "MIC_MEASURE":
      return {
        label: "마이크 측정",
        tone:
          "bg-[var(--badge-success-bg)] text-[var(--badge-success-fg)]",
      };
    case "OCTAVE_PICK":
      return {
        label: "직접 선택",
        tone: "bg-[var(--badge-neutral-bg)] text-[var(--badge-neutral-fg)]",
      };
    case "SELF_REPORT":
      return {
        label: "자가 보고",
        tone: "bg-[var(--badge-neutral-bg)] text-[var(--badge-neutral-fg)]",
      };
  }
}
