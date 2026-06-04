/**
 * 추천 점수 분해(score breakdown) 헬퍼 (closes #141).
 *
 * 배경:
 *   - 백엔드 응답은 추천 카드마다 `matchReason: string`(한 줄)과 `score: number`만 준다.
 *   - Spotify "Why this song?"처럼 카드 클릭 시 "어떤 기준이 얼마나 기여했는지"를
 *     다중 줄로 보여주려면 점수를 항목별로 쪼개야 한다.
 *   - 백엔드가 향후 `breakdown` 필드를 추가하면 우선 사용하고, 없으면 본 헬퍼가
 *     client-side 추정값을 만든다. 추정 룰은 추천 알고리즘 v1 명세
 *     (docs/features/recommendation-algorithm-v1.md §3)와 동일한 직관에 기반:
 *       - keyMatch    : 곡 키가 표준 장조/단조(UNKNOWN 아님)면 만점
 *       - rangeFit    : 사용자 voiceRange와 곡 음역의 겹침 비율
 *       - genreMatch  : 장르 정보가 존재하는지(있으면 만점) — v1은 장르 가중치만 boolean
 *       - popularity  : `popularity` 필드(미래 확장)가 있으면 0~1 정규화
 *
 *   사용자 voiceRange(low/high)는 옵셔널이다 — 히스토리 화면처럼 voiceRange를 모를 때는
 *   rangeFit 항목을 생략한다.
 *
 * 출력은 카드에서 그대로 `RecommendationBreakdownItem[]`로 그리도록 설계되어 있다.
 * 각 항목은 한글 라벨, 부연 텍스트, 0~1 점수, 비-임시(=BE provided) 여부를 갖는다.
 */

import type {
  RecommendedSongResponse,
  SongResponse,
} from "@/lib/api/recommendation";
import { midiToKoreanNoteName } from "@/lib/notes";

/**
 * 점수 분해 단일 항목.
 *
 * - `score`는 0~1 정규화. UI는 막대 길이 또는 %로 노출한다.
 * - `detail`은 카드에서 점수 옆에 보여줄 한 줄 부연 — 예: "C# Major" 또는
 *   "사용자 도3-솔4 vs 곡 솔3-파5" (한국어 단독, #1310 사용자 정정 2026-06-03).
 * - `estimated`가 true이면 client-side 추정값임을 카드에서 명시(자세히 보기 안내)할 수 있다.
 */
export type RecommendationBreakdownItem = {
  key: "keyMatch" | "rangeFit" | "genreMatch" | "popularity";
  label: string;
  score: number;
  detail: string;
  estimated: boolean;
};

/**
 * 사용자 음역 정보(옵션). 둘 다 있을 때만 rangeFit 항목이 계산된다.
 */
export type UserVoiceRange = {
  lowMidi: number;
  highMidi: number;
};

/**
 * 백엔드가 미래에 추가할 수 있는 breakdown 필드 형태(forward-compat).
 *
 * 이 파일은 응답 타입 자체는 건드리지 않는다 — backend가 실제로 추가하기 전까지는
 * `RecommendedSongResponse`에 옵셔널 `breakdown`이 없다. 호출 측에서 캐스팅 없이
 * 안전하게 통과시키기 위해 본 헬퍼 입력은 `RecommendedSongResponse`로 받고,
 * 응답에 `breakdown`이 들어 있는지는 in 체크로만 확인한다.
 */
type ResponseWithBreakdown = RecommendedSongResponse & {
  breakdown?: RecommendationBreakdownItem[] | null;
};

/**
 * 메인 엔트리.
 *
 * 1) 응답에 `breakdown` 배열이 있으면 그대로 사용(estimated=false 강제).
 * 2) 없으면 client-side 추정.
 */
export function buildScoreBreakdown(
  item: RecommendedSongResponse,
  userVoiceRange: UserVoiceRange | null = null,
): RecommendationBreakdownItem[] {
  const withMaybe: ResponseWithBreakdown = item;
  if (
    Array.isArray(withMaybe.breakdown) &&
    withMaybe.breakdown.length > 0
  ) {
    return withMaybe.breakdown.map((entry) => ({
      ...entry,
      estimated: false,
    }));
  }
  return estimateBreakdown(item, userVoiceRange);
}

/**
 * 4가지 카테고리에 대해 client-side 추정값을 만든다.
 *
 * 출력은 점수 내림차순으로 정렬해 "가장 영향 큰 기준"이 먼저 보이도록 한다.
 * 점수가 0인 항목도 결과에 포함된다 — "장르 정보 없음" 같이 0인 사실 자체가
 * 사용자에게 의미 있는 신호이기 때문.
 */
function estimateBreakdown(
  item: RecommendedSongResponse,
  userVoiceRange: UserVoiceRange | null,
): RecommendationBreakdownItem[] {
  const items: RecommendationBreakdownItem[] = [];

  items.push(estimateKeyMatch(item.song));
  const range = estimateRangeFit(item.song, userVoiceRange);
  if (range) {
    items.push(range);
  }
  items.push(estimateGenreMatch(item.song));

  // 점수 내림차순. 동점은 입력 순서 유지(stable sort).
  return items.sort((a, b) => b.score - a.score);
}

function estimateKeyMatch(song: SongResponse): RecommendationBreakdownItem {
  const isKnown = song.keyOriginal !== "UNKNOWN";
  return {
    key: "keyMatch",
    label: "키 매칭",
    score: isKnown ? 1 : 0,
    detail: isKnown ? formatMusicalKey(song.keyOriginal) : "키 정보 없음",
    estimated: true,
  };
}

/**
 * 사용자 음역대와 곡 음역의 겹침 비율(0~1)을 계산한다.
 *
 * `overlap / songSpan` — 곡 음역 중 내 음역대 안에 들어오는 구간 비율. /songs 검색 카드의
 * "내 음역 적합" 배지(#1721)와 추천 점수 분해의 음역 적합 항목이 같은 직관을 공유하도록
 * 단일 함수로 둔다. 음역 정보가 없으면 `null` 을 돌려준다.
 */
export function computeVoiceFitRatio(
  userVoiceRange: UserVoiceRange,
  songLow: number,
  songHigh: number,
): number {
  const overlapLow = Math.max(songLow, userVoiceRange.lowMidi);
  const overlapHigh = Math.min(songHigh, userVoiceRange.highMidi);
  const overlapSemitones = Math.max(0, overlapHigh - overlapLow);
  const songSpan = Math.max(1, songHigh - songLow);
  return Math.min(1, overlapSemitones / songSpan);
}

function estimateRangeFit(
  song: SongResponse,
  userVoiceRange: UserVoiceRange | null,
): RecommendationBreakdownItem | null {
  if (
    userVoiceRange === null ||
    typeof song.lowMidi !== "number" ||
    typeof song.highMidi !== "number"
  ) {
    return null;
  }
  const songLow = song.lowMidi;
  const songHigh = song.highMidi;
  const userLow = userVoiceRange.lowMidi;
  const userHigh = userVoiceRange.highMidi;

  const ratio = roundTo(
    computeVoiceFitRatio(userVoiceRange, songLow, songHigh),
    2,
  );

  const songLowName = midiToKoreanNoteName(songLow);
  const songHighName = midiToKoreanNoteName(songHigh);
  const userLowName = midiToKoreanNoteName(userLow);
  const userHighName = midiToKoreanNoteName(userHigh);

  return {
    key: "rangeFit",
    label: "음역 적합",
    score: ratio,
    detail: `사용자 ${userLowName}-${userHighName} vs 곡 ${songLowName}-${songHighName}`,
    estimated: true,
  };
}

function estimateGenreMatch(song: SongResponse): RecommendationBreakdownItem {
  const hasGenre = typeof song.genre === "string" && song.genre.length > 0;
  return {
    key: "genreMatch",
    label: "장르",
    score: hasGenre ? 1 : 0,
    detail: hasGenre ? (song.genre as string) : "장르 정보 없음",
    estimated: true,
  };
}

function roundTo(value: number, digits: number): number {
  const factor = Math.pow(10, digits);
  return Math.round(value * factor) / factor;
}

/**
 * `MusicalKey` enum 문자열을 사람 읽기 좋은 표기로 변환.
 *
 * SongCard와 동일한 변환 로직 — duplicate를 두는 대신 본 헬퍼가 독립적이게
 * 유지하기 위해 의도적으로 내부 함수로 둔다(컴포넌트 의존성 역전 방지).
 */
function formatMusicalKey(key: string): string {
  if (key === "UNKNOWN") {
    return "Unknown";
  }
  return key
    .replace(/_SHARP/g, "#")
    .replace(/_/g, " ")
    .replace(/\b(\w)(\w*)/g, (_, head: string, tail: string) => {
      return `${head}${tail.toLowerCase()}`;
    });
}
