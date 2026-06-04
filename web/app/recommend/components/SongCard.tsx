/**
 * 곡 카드 컴포넌트.
 *
 * 이슈 #75 / PR #76: 추천 결과 페이지에서 곡 1개를 표현. 음역대 막대 그래프 대신
 * **가창 난이도 라벨 + 최고음**을 핵심 정보로 노출한다.
 *
 * 이슈 #91 #92 / PR #93: 곡 검색 페이지(`/songs`)에서도 동일한 카드 룩앤필을 사용한다.
 * 추천 컨텍스트(`item`)와 검색 컨텍스트(`song`) 둘 다 지원하도록 props가 분기된다.
 *
 * 이슈 #100 / PR #101: `href` prop을 지원해 카드 전체를 곡 상세 페이지로 가는
 * 링크로 만든다. href가 주어지면 카드 표면 전체가 `next/link`의 `<Link>`로 감싸지며,
 * 키보드 포커스/엔터/스페이스 활성화는 next/link의 기본 동작을 사용한다.
 *
 * 이슈 #323 (2026-05-22): 카드 요약/상세 분리.
 *   - 카드 표면은 핵심 정보만(제목/아티스트/난이도/최고음/장르 chip + 좋아요/북마크) 노출.
 *   - 추가 상세(점수 breakdown, matchReason 풀텍스트, YouTube 검색 링크, 메타)는
 *     `onShowDetail` 콜백을 받은 경우 호출 측이 띄우는 모달로 위임한다.
 *   - `onShowDetail`이 주어지면 카드 본문 클릭은 페이지 이동 대신 모달 트리거이며,
 *     카드 표면의 breakdown 패널과 YouTube 검색 링크는 숨겨진다.
 *   - 기존 `href` 모드(상세 페이지 링크)는 backward-compat로 보존 — history 화면 등
 *     legacy 경로가 그대로 동작한다.
 *
 * 표시 정보 (요약):
 *   - rank position (#1, #2 ...) — 추천 컨텍스트에서만
 *   - 제목 (큰 글씨) / 아티스트
 *   - 가창 난이도 라벨 (EASY/NORMAL/HARD)
 *     · `song.difficulty`가 있으면 그 값을, 없으면 `deriveDifficulty(lowMidi, highMidi)`로 계산.
 *     · 둘 다 없으면(legacy 응답) 라벨을 숨긴다.
 *   - 최고음 음표명 (예: "라♯5") — `midiToKoreanNoteName(highMidi)` (#318)
 *   - 최저음 음표명 (작게, 부가)
 *   - 장르 칩 (있으면)
 *   - matchReason 한 줄 — 추천 컨텍스트에서만
 *   - 키(키 원본) 라벨
 *   - score — 추천 컨텍스트에서만
 *   - 좋아요/북마크 액션
 *
 * 호버/포커스 상태는 ring/shadow 변화로 표현. 모바일 우선.
 */

"use client";

import Link from "next/link";
import {
  useId,
  useState,
  type CSSProperties,
  type MouseEvent,
  type ReactNode,
} from "react";

import type {
  RecommendationPersona,
  RecommendedSongResponse,
  SongResponse,
} from "@/lib/api/recommendation";
import { resolvePersonaReason } from "@/lib/persona";
import {
  difficultyLabel,
  resolveSongDifficulty,
  type Difficulty,
} from "@/lib/difficulty";
import {
  useBookmarkToggleMutation,
  useLikeToggleMutation,
} from "@/lib/hooks/useFeedbackToggleMutation";
import { midiToKoreanNoteName } from "@/lib/notes";
import {
  buildScoreBreakdown,
  type RecommendationBreakdownItem,
  type UserVoiceRange,
} from "@/lib/scoreBreakdown";
import { formatSongDisplayTitle } from "@/lib/songTitle";
import { Chip, HeartPop } from "@/components/ui";

import { FitBadge, FitReasons } from "./FitBadge";
import { AlbumCoverThumbnail } from "./SongDetailContent";

/**
 * Props 분기:
 *   - `item: RecommendedSongResponse` — 추천 결과 카드. rank/score/matchReason 노출.
 *   - `song: SongResponse` — 검색 결과 카드. 추천 컨텍스트 필드는 모두 숨김.
 *
 * 두 모드 모두 동일한 시각 표현(난이도/최고음/장르/키)을 공유하며, 옵셔널 `href`로
 * 카드 전체를 클릭 가능한 링크로 만들 수 있다.
 */
/**
 * `userVoiceRange`는 추천 컨텍스트(`item`)에서만 의미가 있다 — 카드 펼침 시
 * "음역 적합" 항목 계산에 사용된다. 검색 컨텍스트(`song`)나 히스토리에서 voiceRange를
 * 모르는 경우에는 옵셔널로 비워두면 해당 항목이 자동 생략된다.
 */
/**
 * `onShowDetail`이 주어지면 카드 본문 클릭이 페이지 이동 대신 모달 트리거가 된다.
 * 동시에 카드 표면의 breakdown 패널/YouTube 링크는 숨겨져 "요약 카드" 룩이 된다.
 * (closes #323) 호출 측은 상태와 모달 컴포넌트(`SongDetailModal`)를 직접 관리한다.
 *
 * `onShowDetail` + `href` 가 동시에 주어지면 모달이 우선한다. 호출 측이 의도적으로
 * 두 경로를 모두 노출하고 싶을 때를 위해 빌드 에러는 띄우지 않는다 — 단, 카드 본문
 * 클릭은 모달로 흘러간다.
 */
/**
 * `index`는 리스트 내 카드 위치(0,1,2…)로, stagger 진입 애니메이션의 delay 계산에만
 * 쓰인다 — `--card-index` 인라인 변수로 흘려 `animate-card-enter` 가 50ms 씩 늦춘다.
 * 미지정이면 0(즉시 진입). 단일 카드 렌더에서는 생략해도 무방하다.
 */
type SongCardProps =
  | {
      item: RecommendedSongResponse;
      song?: never;
      href?: string;
      userVoiceRange?: UserVoiceRange | null;
      onShowDetail?: () => void;
      index?: number;
      /**
       * 사용자가 추천 화면에서 고른 의도 페르소나(P-E 안전곡 등). BE 응답에 아직
       * `persona`/`personaReason` 이 없을 때 결과 카드의 페르소나 사유 fallback 근거가
       * 된다(lib/persona.resolvePersonaReason). 미지정이면 페르소나 사유 줄을 생략한다.
       */
      activePersona?: RecommendationPersona | null;
    }
  | {
      song: SongResponse;
      item?: never;
      href?: string;
      userVoiceRange?: never;
      onShowDetail?: () => void;
      index?: number;
      activePersona?: never;
    };

export function SongCard(props: SongCardProps) {
  const song: SongResponse = "item" in props && props.item ? props.item.song : props.song!;
  const item: RecommendedSongResponse | null =
    "item" in props && props.item ? props.item : null;
  const href: string | undefined = props.href;
  const userVoiceRange: UserVoiceRange | null =
    "item" in props && props.userVoiceRange ? props.userVoiceRange : null;
  const activePersona: RecommendationPersona | null =
    "item" in props && props.activePersona ? props.activePersona : null;
  const onShowDetail: (() => void) | undefined = props.onShowDetail;
  const index: number = typeof props.index === "number" ? props.index : 0;
  // closes #1683 — stagger 진입 delay(--card-index) + 곡별 deterministic accent hue(--song-hue).
  const cardStyle = {
    "--card-index": index,
    "--song-hue": getSongHue(song.id),
  } as CSSProperties;
  // 좌측 4px accent stripe. rounded-l 로 카드 모서리를 따라가 overflow-hidden 없이도
  // 둥근 코너 밖으로 삐져나오지 않는다(내부 포커스 ring clip 회피).
  const accentStripe: ReactNode = (
    <span
      aria-hidden="true"
      className="song-accent-stripe pointer-events-none absolute inset-y-0 left-0 w-1 rounded-l-[var(--radius-lg)]"
    />
  );
  // closes #1600 — P-E 안전곡 등 페르소나 사유("안심 포인트")를 카드 표면에 노출.
  // BE personaReason 우선, 없으면 활성 페르소나 + 곡 난이도 기반 client fallback.
  const personaReason = item ? resolvePersonaReason(item, activePersona) : null;
  // 모달 모드: 카드 본문 클릭 = 모달 트리거. breakdown/YouTube 링크는 모달로 위임되어
  // 카드 표면에서 사라진다 (closes #323). href 모드와 동시 지정 시 모달이 우선.
  const isModalMode = typeof onShowDetail === "function";
  // closes #1687 (PR7) — hero morph. 카드가 실제로 /songs/[id] 로 라우트 이동하는
  // href 모드에서만 thumbnail 에 view-transition-name 을 부여해 상세 페이지의 큰
  // cover 와 morph 시킨다. 모달 모드(라우트 이동 없음)나 plain 모드에서는 부여하지
  // 않는다 — 같은 곡 cover 가 두 곳에 같은 이름으로 동시에 존재하면 전환이 무시된다.
  const albumViewTransitionName =
    href && !isModalMode ? `album-${song.id}` : undefined;
  // closes #1284 — 한국 곡의 한국어 표시 우선 (lib/songTitle.formatSongDisplayTitle SoT).
  // 카드 표면 / aria-label / 자식 컴포넌트 prop 까지 동일한 표시명을 사용해 일관성 유지.
  const displayTitle = formatSongDisplayTitle(song);
  const keyLabel = formatMusicalKey(song.keyOriginal);
  const difficulty = resolveDifficulty(song);
  const highestNoteName =
    typeof song.highMidi === "number"
      ? midiToKoreanNoteName(song.highMidi)
      : null;
  const lowestNoteName =
    typeof song.lowMidi === "number"
      ? midiToKoreanNoteName(song.lowMidi)
      : null;

  const body: ReactNode = (
    <>
      <div className="flex items-start justify-between gap-4">
        {/*
         * closes #322 — 앨범 커버 thumbnail (56px 정사각). 좌측 첫 요소로 두면 카드
         * 식별성 ↑. albumCoverUrl 가 null 이거나 로딩 실패 시 placeholder 로 fallback
         * 하므로 레이아웃 jump 없음. flex shrink-0 으로 텍스트가 줄어도 thumbnail 폭은
         * 유지.
         */}
        <div className="shrink-0">
          <AlbumCoverThumbnail
            song={song}
            viewTransitionName={albumViewTransitionName}
          />
        </div>
        <div className="flex min-w-0 flex-1 flex-col gap-1">
          {item ? (
            <p className="text-xs font-medium text-[var(--text-tertiary)]">
              #{item.rankPosition}
            </p>
          ) : null}
          <h2 className="truncate text-lg font-semibold text-[var(--text-primary)]">
            {displayTitle}
          </h2>
          <p className="truncate text-sm text-[var(--text-secondary)]">
            {song.artist}
          </p>
        </div>
        <div className="flex flex-col items-end gap-1 text-right">
          {difficulty ? <DifficultyBadge difficulty={difficulty} /> : null}
          <span className="text-xs text-[var(--text-tertiary)]">키</span>
          <span className="text-sm font-medium text-[var(--text-primary)]">
            {keyLabel}
          </span>
        </div>
      </div>


      {(highestNoteName || lowestNoteName) && (
        <div className="flex items-baseline gap-3">
          {highestNoteName ? (
            <div className="flex items-baseline gap-1.5">
              <span className="text-xs text-[var(--text-tertiary)]">
                최고음
              </span>
              <span
                aria-label={`최고음 ${highestNoteName}`}
                className="text-base font-semibold text-[var(--text-primary)]"
              >
                {highestNoteName}
              </span>
            </div>
          ) : null}
          {lowestNoteName ? (
            <div className="flex items-baseline gap-1.5">
              <span className="text-xs text-[var(--text-tertiary)]">
                최저음
              </span>
              <span className="text-xs text-[var(--text-secondary)]">
                {lowestNoteName}
              </span>
            </div>
          ) : null}
        </div>
      )}

      <div className="flex items-center justify-between gap-3">
        <div className="flex min-w-0 flex-wrap items-center gap-2">
          {/*
           * closes #1484 — 음역 적합도 배지. BE 가 voiceFit 을 내려준 추천 컨텍스트에서만
           * 노출한다. 라벨은 "내 음역 적합" — 곡 자체의 음역이 아니라 내 음역대와의
           * 적합도임을 표면에서 분명히 한다 (V8, recommend-page-visual-ux-audit-1708).
           */}
          {item && typeof item.voiceFit === "number" ? (
            <FitBadge label="내 음역 적합" fit={item.voiceFit} />
          ) : null}
          {song.genre ? <Chip tone="neutral">{song.genre}</Chip> : null}
          {/*
           * matchReason / score 는 모달 모드에서는 카드 표면이 아닌 상세 모달에서
           * 노출한다 (closes #323). 카드는 "한눈에 보이는 정보" 만 남기는 게 검수
           * 피드백의 핵심.
           */}
          {item && !isModalMode ? (
            <span className="truncate text-xs text-[var(--text-tertiary)]">
              {item.matchReason}
            </span>
          ) : null}
        </div>
        {item && !isModalMode ? (
          <span className="shrink-0 font-mono text-xs text-[var(--text-secondary)]">
            score {item.score.toFixed(2)}
          </span>
        ) : null}
      </div>

      {/*
       * closes #1600 — 페르소나 사유("안심 포인트") 한 줄. P-E 안전곡 모드처럼 의도
       * 페르소나가 활성일 때만 노출하며, 모달 모드에서도 한눈에 보이는 핵심 신호라
       * 카드 표면에 유지한다. BE personaReason 미보유 시 곡 난이도 기반 fallback.
       */}
      {personaReason ? (
        <div className="flex items-start gap-2 rounded-[var(--radius-md)] bg-[var(--badge-success-bg)] px-3 py-2">
          <span className="shrink-0 text-xs font-semibold text-[var(--badge-success-fg)]">
            {personaReason.label}
          </span>
          <span className="text-xs text-[var(--text-secondary)]">
            {personaReason.text}
          </span>
        </div>
      ) : null}
    </>
  );

  // 펼침 토글(closes #141)은 추천 컨텍스트(item)에서만 노출. matchReason을 더 깊게
  // 풀어 보여주는 "Why this song?" 패널이라 검색/스켈레톤에서는 의미가 없다.
  // 또한 href 모드에서도 <a> 내부에 button을 두는 것은 HTML 위반이므로 link 외부에
  // 별도 footer로 렌더한다.
  // 모달 모드(closes #323) 에서는 breakdown 도 상세 모달로 위임 — 카드 표면을 가볍게 유지.
  const breakdownPanel =
    item && !isModalMode ? (
      <MatchReasonExpander item={item} userVoiceRange={userVoiceRange} />
    ) : null;

  // 좋아요 + 북마크 — 추천/검색/likes/bookmarks 어디서나 카드 footer 에 유지 (사용자 자주 쓰는 액션).
  // YouTube 검색 링크(closes #302)는 모달 모드에서는 상세 모달로 위임해서 카드를 가볍게 유지한다.
  // 모달 모드가 아니면 종전대로 카드에서 직접 새 탭으로 검색.
  const feedbackPanel = (
    <div className="flex flex-wrap items-center gap-2">
      <LikeButton songId={song.id} songTitle={displayTitle} />
      <BookmarkButton songId={song.id} songTitle={displayTitle} />
      {!isModalMode ? (
        <YouTubeSearchLink songTitle={displayTitle} songArtist={song.artist} />
      ) : null}
    </div>
  );

  // 모달 모드(closes #323): 카드 본문 클릭이 페이지 이동 대신 모달 트리거.
  // 본문은 button 으로 감싸 키보드 접근(Enter/Space) + 스크린 리더(button role) 호환.
  // footer(좋아요/북마크)는 본문 button 외부에 둬서 버튼 중첩(HTML 위반) 회피.
  if (isModalMode) {
    return (
      <li
        style={cardStyle}
        className="group relative flex flex-col rounded-[var(--radius-lg)] bg-[var(--bg-base)] ring-1 ring-[var(--border)] transition animate-card-enter hover:ring-[var(--ring-soft-hover)] hover:shadow-[var(--shadow-md)] hover:-translate-y-0.5 focus-within:ring-2 focus-within:ring-[var(--ring-soft-focus-within)]"
      >
        {accentStripe}
        <button
          type="button"
          onClick={onShowDetail}
          aria-label={`${displayTitle} 상세 보기`}
          aria-haspopup="dialog"
          className="flex flex-col gap-3 rounded-[var(--radius-lg)] p-[var(--card-padding)] text-left transition active:scale-[0.98] focus:outline-none focus-visible:ring-2 focus-visible:ring-[var(--cta-secondary-ring)]"
        >
          {body}
        </button>
        <div className="flex flex-col gap-2 border-t border-[var(--border)] px-[var(--card-padding)] py-3">
          {feedbackPanel}
        </div>
      </li>
    );
  }

  // href가 있으면 본문(body)만 링크로 감싸고, footer(breakdown + 피드백)는 링크 외부에 둔다.
  // 이렇게 하면 펼침/좋아요/북마크 버튼 클릭이 페이지 이동을 트리거하지 않으면서도 본문 클릭은
  // 그대로 곡 상세로 이동한다.
  if (href) {
    return (
      <li
        style={cardStyle}
        className="group relative flex flex-col rounded-[var(--radius-lg)] bg-[var(--bg-base)] ring-1 ring-[var(--border)] transition animate-card-enter hover:ring-[var(--ring-soft-hover)] hover:shadow-[var(--shadow-md)] hover:-translate-y-0.5 focus-within:ring-2 focus-within:ring-[var(--ring-soft-focus-within)]"
      >
        {accentStripe}
        <Link
          href={href}
          aria-label={`${displayTitle} 상세 보기`}
          className="flex flex-col gap-3 rounded-[var(--radius-lg)] p-[var(--card-padding)] transition active:scale-[0.98] focus:outline-none focus-visible:ring-2 focus-visible:ring-[var(--cta-secondary-ring)]"
        >
          {body}
        </Link>
        <div className="flex flex-col gap-2 border-t border-[var(--border)] px-[var(--card-padding)] py-3">
          {breakdownPanel}
          {feedbackPanel}
        </div>
      </li>
    );
  }

  return (
    <li
      tabIndex={0}
      style={cardStyle}
      className="group relative flex flex-col gap-3 rounded-[var(--radius-lg)] bg-[var(--bg-base)] p-[var(--card-padding)] ring-1 ring-[var(--border)] transition animate-card-enter hover:ring-[var(--ring-soft-hover)] hover:shadow-[var(--shadow-md)] hover:-translate-y-0.5 focus-within:ring-2 focus-within:ring-[var(--ring-soft-focus-within)] focus:outline-none focus:ring-2 focus:ring-[var(--cta-secondary-ring)]"
    >
      {accentStripe}
      {body}
      {breakdownPanel}
      {feedbackPanel}
    </li>
  );
}

/**
 * 좋아요 토글 버튼 (closes #176, BE 연동 #184).
 *
 * UX:
 *   - aria-pressed로 토글 상태 노출 — 스크린 리더가 "눌림/안 눌림"으로 읽는다.
 *   - 텍스트 라벨도 "좋아요" / "좋아요 취소"로 바뀌어 시각 사용자에게도 명확.
 *   - mutation 진행 중에는 `aria-busy`, `disabled` 활성화 — 중복 클릭 방지.
 *
 * 동기화 흐름:
 *   1. onClick → useSessionStore.ensureSessionId() 로 sessionId 확보.
 *   2. mutation.mutate 호출 — onMutate에서 zustand store를 즉시 토글(낙관).
 *   3. BE 응답 도착 후 onSuccess: 응답의 `liked` 값으로 store를 한 번 더 보정
 *      (낙관값과 BE 응답이 다를 수 있는 race 상황 안전망).
 *   4. onError: 낙관 변경을 롤백 + safeLog.error로 PII 마스킹 후 보고.
 *   5. ['likes', sessionId] 쿼리 invalidate — `/likes` 페이지가 자동 재페치.
 *
 * 보안:
 *   - sessionId는 PII로 분류 (logging.ts §SENSITIVE_KEYS) — safeLog 사용 필수.
 */
type LikeButtonProps = {
  songId: number;
  songTitle: string;
};

function LikeButton({ songId, songTitle }: LikeButtonProps) {
  // closes #846 — mutation 라이프사이클(낙관 토글 + BE 호출 + 응답 보정 + 롤백 + safeLog +
  // 자동 dismiss 에러)을 `useLikeToggleMutation` 으로 캡슐화.
  // closes #1685 — 표면 표현(heart pop + sparkle + 햅틱)은 `HeartPop` 으로 분리.
  // 이벤트 격리(카드 Link/모달 trigger 비전파)도 HeartPop.handleClick 이 담당한다.
  const { liked, toggle, isPending, errorMessage } =
    useLikeToggleMutation(songId);

  return (
    <div className="flex flex-col gap-1">
      <HeartPop
        active={liked}
        onToggle={toggle}
        disabled={isPending}
        busy={isPending}
        label={liked ? "좋아요 취소" : "좋아요"}
        ariaLabel={liked ? `${songTitle} 좋아요 취소` : `${songTitle} 좋아요`}
      />
      {errorMessage ? (
        <p
          role="alert"
          aria-live="assertive"
          className="text-xs text-rose-700 dark:text-rose-300"
        >
          {errorMessage}
        </p>
      ) : null}
    </div>
  );
}

/**
 * 북마크 토글 버튼 (closes #184).
 *
 * 동작은 LikeButton과 동일 — 별도 store/엔드포인트만 사용.
 * 시각적으로는 🔖 + amber 톤으로 구분.
 */
type BookmarkButtonProps = {
  songId: number;
  songTitle: string;
};

function BookmarkButton({ songId, songTitle }: BookmarkButtonProps) {
  // closes #846 — useBookmarkToggleMutation 으로 mutation 전체 캡슐화 (LikeButton 과 대칭).
  const { bookmarked, toggle, isPending, errorMessage } =
    useBookmarkToggleMutation(songId);

  function handleClick(event: MouseEvent<HTMLButtonElement>) {
    event.preventDefault();
    event.stopPropagation();
    toggle();
  }

  return (
    <div className="flex flex-col gap-1">
      <button
        type="button"
        onClick={handleClick}
        disabled={isPending}
        aria-pressed={bookmarked}
        aria-busy={isPending}
        aria-label={
          bookmarked ? `${songTitle} 북마크 해제` : `${songTitle} 북마크`
        }
        className={`inline-flex min-h-11 items-center gap-1.5 self-start rounded-full px-3.5 py-2 text-sm font-medium transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-[var(--cta-secondary-ring)] disabled:cursor-progress disabled:opacity-60 ${
          bookmarked
            ? "bg-amber-100 text-amber-800 hover:bg-amber-200 dark:bg-amber-950 dark:text-amber-300 dark:hover:bg-amber-900"
            : "text-[var(--text-secondary)] hover:bg-[var(--cta-secondary-bg-hover)]"
        }`}
      >
        <span aria-hidden="true">🔖</span>
        <span>{bookmarked ? "북마크 해제" : "북마크"}</span>
      </button>
      {errorMessage ? (
        <p
          role="alert"
          aria-live="assertive"
          className="text-xs text-amber-700 dark:text-amber-300"
        >
          {errorMessage}
        </p>
      ) : null}
    </div>
  );
}

/**
 * YouTube 검색 링크 (closes #302) — 추천 카드 미리듣기 1단계.
 *
 * 정책 결정:
 *   - ADR-0006(`docs/decisions/0006-audio-source-youtube.md`)은 **BE 분석 파이프라인의 audio 출처**로
 *     YouTube를 채택한 결정일 뿐, 프론트 사용자 미리듣기 정책은 정의하지 않는다. iframe embed/lite-embed는
 *     ToS·저작권 리스크가 있어 별도 ADR + Song 엔티티 youtubeId 컬럼 추가가 필요하다.
 *   - 본 PR(fe 35)은 BE/legal 변경 없이 가능한 가장 안전한 진입점만 제공: 공식 YouTube 검색 결과 페이지를
 *     새 탭으로 연다. 사용자가 거기서 듣고 카드로 돌아와 좋아요/북마크를 결정한다.
 *   - 후속(fe 36+)에서 BE에 youtubeId 컬럼이 추가되면 lite-embed로 교체 (maestro 보고 사항).
 *
 * 접근성:
 *   - aria-label에 곡 제목 + "(새 탭)" 명시 → 스크린 리더가 새 창임을 알린다.
 *   - rel="noopener noreferrer" — window.opener leak 방지(보안).
 *   - target="_blank" 새 탭이므로 추천 흐름 유지.
 *   - href 모드의 부모 Link로 이벤트가 새 나가지 않도록 stopPropagation.
 */
export function buildYouTubeSearchUrl(title: string, artist: string): string {
  // YouTube 공식 검색 결과 URL. URLSearchParams로 인코딩 (특수문자/한글 안전).
  const query = `${title} ${artist}`.trim();
  const params = new URLSearchParams({ search_query: query });
  return `https://www.youtube.com/results?${params.toString()}`;
}

type YouTubeSearchLinkProps = {
  songTitle: string;
  songArtist: string;
};

function YouTubeSearchLink({ songTitle, songArtist }: YouTubeSearchLinkProps) {
  const href = buildYouTubeSearchUrl(songTitle, songArtist);
  return (
    <a
      href={href}
      target="_blank"
      rel="noopener noreferrer"
      onClick={(event) => {
        // href 모드 카드에서 부모 <Link>로 이벤트가 새 나가는 것 방지.
        // preventDefault는 호출하지 않음 — 링크 자체는 정상 동작해야 한다.
        event.stopPropagation();
      }}
      aria-label={`${songTitle} YouTube에서 듣기 (새 탭)`}
      className="inline-flex min-h-11 items-center gap-1.5 self-start rounded-full px-3.5 py-2 text-sm font-medium text-[var(--text-secondary)] transition-colors hover:bg-[var(--cta-secondary-bg-hover)] hover:text-[var(--text-primary)] focus:outline-none focus-visible:ring-2 focus-visible:ring-[var(--cta-secondary-ring)]"
    >
      <span aria-hidden="true">▶</span>
      <span>YouTube에서 듣기</span>
    </a>
  );
}

/**
 * matchReason 펼침 토글 (closes #141, Spotify "Why this song?" 영감).
 *
 * 접힘 상태:
 *   - "자세히 보기" 버튼 + ChevronDown 아이콘. aria-expanded=false.
 * 펼침 상태:
 *   - 버튼 라벨 "접기" + Chevron 회전. aria-expanded=true.
 *   - 본문에 점수 분해 항목들을 dl/dt/dd 의미적 구조로 표시.
 *   - client-side 추정값인 경우 안내 footnote 노출.
 */
type MatchReasonExpanderProps = {
  item: RecommendedSongResponse;
  userVoiceRange: UserVoiceRange | null;
};

function MatchReasonExpander({
  item,
  userVoiceRange,
}: MatchReasonExpanderProps) {
  const [expanded, setExpanded] = useState(false);
  const panelId = useId();
  const breakdown = buildScoreBreakdown(item, userVoiceRange);
  const hasEstimated = breakdown.some((entry) => entry.estimated);

  return (
    <div className="flex flex-col gap-2">
      <button
        type="button"
        onClick={() => setExpanded((prev) => !prev)}
        aria-expanded={expanded}
        aria-controls={panelId}
        className="inline-flex min-h-11 items-center gap-1 self-start rounded-full px-3 py-2 text-sm font-medium text-[var(--text-body-strong)] transition-colors hover:bg-[var(--cta-secondary-bg-hover)] hover:text-[var(--text-primary)] focus:outline-none focus-visible:ring-2 focus-visible:ring-[var(--cta-secondary-ring)]"
      >
        <span>{expanded ? "접기" : "자세히 보기"}</span>
        <ChevronDownIcon expanded={expanded} />
      </button>
      {expanded ? (
        <div
          id={panelId}
          className="flex flex-col gap-3 rounded-xl bg-[var(--bg-subtle)] p-3 text-xs text-[var(--text-secondary)]"
        >
          {/* closes #1484 — BE 산출 음역/분위기 적합도 + 한국어 사유를 추정 breakdown 위에 노출. */}
          <FitReasons item={item} />
          <dl className="flex flex-col gap-1.5">
            {breakdown.map((entry) => (
              <BreakdownRow key={entry.key} entry={entry} />
            ))}
          </dl>
          {hasEstimated ? (
            <p className="text-[11px] text-[var(--text-tertiary)]">
              ※ 점수 분해는 클라이언트 추정값입니다. 백엔드 산출값이 추가되면 자동으로 교체됩니다.
            </p>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}

type BreakdownRowProps = {
  entry: RecommendationBreakdownItem;
};

function BreakdownRow({ entry }: BreakdownRowProps) {
  const percent = Math.round(Math.max(0, Math.min(1, entry.score)) * 100);
  return (
    <div className="flex items-center justify-between gap-3">
      <dt className="flex min-w-0 flex-col">
        <span className="font-medium text-[var(--text-primary)]">
          {entry.label}
        </span>
        <span className="truncate text-[11px] text-[var(--text-tertiary)]">
          {entry.detail}
        </span>
      </dt>
      <dd
        aria-label={`${entry.label} 점수 ${percent}%`}
        className="shrink-0 font-mono text-xs tabular-nums text-[var(--text-secondary)]"
      >
        {percent}%
      </dd>
    </div>
  );
}

type ChevronDownIconProps = {
  expanded: boolean;
};

function ChevronDownIcon({ expanded }: ChevronDownIconProps) {
  return (
    <svg
      aria-hidden="true"
      viewBox="0 0 16 16"
      width="12"
      height="12"
      className={`transition-transform ${expanded ? "rotate-180" : ""}`}
    >
      <path
        d="M3 6l5 5 5-5"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.5"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}

type DifficultyBadgeProps = {
  difficulty: Difficulty;
};

function DifficultyBadge({ difficulty }: DifficultyBadgeProps) {
  const tone = difficultyTone(difficulty);
  return (
    <span
      aria-label={`가창 난이도 ${difficultyLabel(difficulty)}`}
      className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-semibold ${tone}`}
    >
      {difficultyLabel(difficulty)}
    </span>
  );
}

function difficultyTone(difficulty: Difficulty): string {
  switch (difficulty) {
    case "EASY":
      return "bg-emerald-100 text-emerald-700 dark:bg-emerald-950 dark:text-emerald-300";
    case "NORMAL":
      return "bg-amber-100 text-amber-700 dark:bg-amber-950 dark:text-amber-300";
    case "HARD":
      return "bg-rose-100 text-rose-700 dark:bg-rose-950 dark:text-rose-300";
  }
}

/**
 * 응답에 `difficulty`가 있으면 그것을, 없고 `lowMidi`/`highMidi`가 있으면
 * client-side 계산값을, 둘 다 없으면 null을 돌려준다.
 *
 * 회귀 가드(이슈 #512): 단위 테스트는 `SongCard.helpers.test.tsx` 참조.
 */
export function resolveDifficulty(
  song: RecommendedSongResponse["song"],
): Difficulty | null {
  return resolveSongDifficulty(song);
}

/**
 * BE `MusicalKey` enum 값(`C_SHARP_MAJOR` 등)을 UI 표시용 문자열로 변환한다.
 * `_SHARP` → `#`, 나머지 `_` → 공백, 단어 첫 글자만 대문자.
 * 특수값 `UNKNOWN`은 한국식 표기 대신 `Unknown` 으로 고정.
 *
 * 회귀 가드(이슈 #512): 단위 테스트는 `SongCard.helpers.test.tsx` 참조.
 */
export function formatMusicalKey(key: string): string {
  if (key === "UNKNOWN") {
    return "Unknown";
  }
  // ex) C_SHARP_MAJOR → C# Major
  return key
    .replace(/_SHARP/g, "#")
    .replace(/_/g, " ")
    .replace(/\b(\w)(\w*)/g, (_, head: string, tail: string) => {
      return `${head}${tail.toLowerCase()}`;
    });
}

/**
 * 곡 id → 좌측 accent stripe 의 hue(0–359). 같은 곡은 항상 같은 색이 되도록
 * id 문자열의 char code 합을 360 으로 나눈 나머지를 쓴다(외부 색 추출 없이 hash
 * 기반 분산). closes #1683.
 *
 * 회귀 가드: 단위 테스트는 `SongCard.helpers.test.tsx` 참조.
 */
export function getSongHue(songId: number): number {
  return String(songId)
    .split("")
    .reduce((accumulated, character) => accumulated + character.charCodeAt(0), 0) % 360;
}

/**
 * Skeleton 카드. 추천 결과 로딩 중에 SongCard 자리에 표시한다.
 */
export function SongCardSkeleton() {
  return (
    <li className="flex flex-col gap-3 rounded-[var(--radius-lg)] bg-[var(--bg-base)] p-[var(--card-padding)] ring-1 ring-[var(--border)]">
      <div className="flex items-start justify-between gap-4">
        {/* closes #322 — 앨범 커버 thumbnail 자리 (실제 카드와 동일한 56px). */}
        <div className="h-14 w-14 shrink-0 animate-pulse rounded-xl bg-[var(--bg-muted)]" />
        <div className="flex min-w-0 flex-1 flex-col gap-2">
          <div className="h-3 w-8 animate-pulse rounded bg-[var(--bg-muted)]" />
          <div className="h-5 w-2/3 animate-pulse rounded bg-[var(--bg-muted)]" />
          <div className="h-4 w-1/3 animate-pulse rounded bg-[var(--bg-muted)]" />
        </div>
        <div className="flex flex-col items-end gap-2">
          <div className="h-5 w-14 animate-pulse rounded-full bg-[var(--bg-muted)]" />
          <div className="h-3 w-10 animate-pulse rounded bg-[var(--bg-muted)]" />
        </div>
      </div>
      <div className="flex items-baseline gap-3">
        <div className="h-4 w-20 animate-pulse rounded bg-[var(--bg-muted)]" />
        <div className="h-3 w-14 animate-pulse rounded bg-[var(--bg-muted)]" />
      </div>
      <div className="flex items-center justify-between">
        <div className="h-4 w-1/2 animate-pulse rounded bg-[var(--bg-muted)]" />
        <div className="h-3 w-12 animate-pulse rounded bg-[var(--bg-muted)]" />
      </div>
    </li>
  );
}
