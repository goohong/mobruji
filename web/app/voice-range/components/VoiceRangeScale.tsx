"use client";

/**
 * 단일 음역대 시각화 (voice-range-intuitive-display.md §5-6).
 *
 * 목적:
 *   - 사용자 음역대(low~high MIDI)를 절대 음계(가로 막대) 위에 강조 밴드로 그려
 *     "내가 여기서 여기까지 부르는구나" 를 한눈에 보여준다.
 *   - 평균(벤치마크) 음역 밴드를 옅게 겹쳐 상대 위치를 시각적으로도 노출한다.
 *
 * 시각화 방식 (VoiceRangeProgressCard 의 의도적 결정 차용 — PR #170):
 *   - 외부 차트 라이브러리 없이 SVG 직접 렌더 (번들 부담 회피).
 *   - 가로축 = 음높이(왼쪽 낮은음 → 오른쪽 높은음). 옥타브 기준선(C-노트) + 계이름 라벨.
 *   - 벤치마크 밴드(옅게) 위에 사용자 밴드(강조)를 겹쳐 같은 음높이 축에서 비교.
 *   - 양 끝에 사용자 최저음/최고음 음표명(한국어 음명).
 *
 * 접근성:
 *   - SVG 에 role="img" + 요약 aria-label (시각화 없이도 이해 가능).
 *   - 비유한(NaN/Infinity) MIDI 입력은 시각화 대신 a11y fallback 텍스트로 대체한다
 *     (INVALID_MIDI_A11Y_FALLBACK 재사용).
 */

import {
  INVALID_MIDI_A11Y_FALLBACK,
  midiToKoreanNoteName,
} from "@/lib/notes";
import {
  NEUTRAL_BENCHMARK,
  type VoiceRangeBenchmark,
} from "@/lib/voiceRangeBenchmark";

const CHART_WIDTH = 320;
const CHART_HEIGHT = 88;
const PADDING_X = 16;
const PADDING_TOP = 18; // 사용자 음표명 라벨 공간.
const RANGE_PAD_SEMITONES = 2; // 밴드가 차트 경계에 딱 붙지 않도록 가시 범위 여백.

const BENCH_LANE_HEIGHT = 30; // 벤치마크 밴드(옅게, 전체 레인).
const USER_LANE_INSET = 6; // 사용자 밴드를 벤치마크 레인 안쪽으로 살짝 inset.

type Props = {
  lowMidi: number;
  highMidi: number;
  benchmark?: VoiceRangeBenchmark;
  /** 시각화 위에 들어갈 짧은 제목(스크린리더 + 시각). 생략 시 라벨 없음. */
  caption?: string;
  /**
   * 톤다운(축소) 변형. 결과 페이지 헤더처럼 음역 막대가 보조 정보일 때, 막대의
   * 렌더 폭(=비례 높이)을 줄이고 사용자 밴드 대비를 낮춰 시선 비중을 절제한다
   * (recommend-page-visual-ux-audit-1708 V7). 기본 false → 기존 화면 불변.
   */
  compact?: boolean;
};

export function VoiceRangeScale({
  lowMidi,
  highMidi,
  benchmark = NEUTRAL_BENCHMARK,
  caption,
  compact = false,
}: Props) {
  // 비유한 입력 가드 (#757/#766 철학) — 의미 없는 막대 대신 안내 텍스트.
  if (!Number.isFinite(lowMidi) || !Number.isFinite(highMidi)) {
    return (
      <p role="status" className="text-xs text-[var(--text-caption)]">
        {INVALID_MIDI_A11Y_FALLBACK}
      </p>
    );
  }

  // 가시 범위 = 사용자·벤치마크 범위를 모두 포함하도록 합집합 + 여백.
  const rangeLow =
    Math.min(lowMidi, benchmark.lowMidi) - RANGE_PAD_SEMITONES;
  const rangeHigh =
    Math.max(highMidi, benchmark.highMidi) + RANGE_PAD_SEMITONES;
  const visRange = Math.max(1, rangeHigh - rangeLow);

  const innerWidth = CHART_WIDTH - PADDING_X * 2;
  const laneTop = PADDING_TOP;
  const benchLaneY = laneTop;
  const userLaneY = laneTop + USER_LANE_INSET;
  const userLaneHeight = BENCH_LANE_HEIGHT - USER_LANE_INSET * 2;

  function midiToX(midi: number): number {
    const ratio = (midi - rangeLow) / visRange;
    return PADDING_X + innerWidth * ratio;
  }

  const benchX1 = midiToX(benchmark.lowMidi);
  const benchX2 = midiToX(benchmark.highMidi);
  const userX1 = midiToX(lowMidi);
  const userX2 = midiToX(highMidi);

  const octaveAnchors = pickOctaveAnchors(rangeLow, rangeHigh);

  const lowNote = midiToKoreanNoteName(lowMidi);
  const highNote = midiToKoreanNoteName(highMidi);
  const ariaLabel = `내 음역대 ${lowNote}부터 ${highNote}까지. 평균 음역대 ${midiToKoreanNoteName(benchmark.lowMidi)}부터 ${midiToKoreanNoteName(benchmark.highMidi)}까지와 비교한 막대.`;

  return (
    <figure className="flex flex-col gap-2">
      {caption ? (
        <figcaption className="text-xs font-medium text-[var(--text-caption)]">
          {caption}
        </figcaption>
      ) : null}
      <svg
        role="img"
        aria-label={ariaLabel}
        viewBox={`0 0 ${CHART_WIDTH} ${CHART_HEIGHT}`}
        className={`w-full text-[var(--text-tertiary)] ${
          compact ? "max-w-[240px]" : "max-w-md"
        }`}
      >
        {/* 음계 backdrop(전체 트랙) */}
        <rect
          x={PADDING_X}
          y={benchLaneY}
          width={innerWidth}
          height={BENCH_LANE_HEIGHT}
          rx={6}
          className="fill-[var(--meter-track-bg)]"
        />

        {/* 옥타브 C-노트 기준선 + 계이름 라벨 */}
        {octaveAnchors.map((midi) => {
          const x = midiToX(midi);
          return (
            <g key={`octave-${midi}`} aria-hidden="true">
              <line
                x1={x}
                x2={x}
                y1={benchLaneY}
                y2={benchLaneY + BENCH_LANE_HEIGHT}
                stroke="currentColor"
                strokeWidth={1}
                strokeDasharray="1 3"
                opacity={0.4}
              />
              <text
                x={x}
                y={benchLaneY + BENCH_LANE_HEIGHT + 13}
                textAnchor="middle"
                className="fill-[var(--text-tertiary)] text-[9px]"
              >
                {midiToKoreanNoteName(midi)}
              </text>
            </g>
          );
        })}

        {/* 벤치마크(평균) 밴드 — 옅게 */}
        <rect
          data-testid="voice-range-scale-benchmark-band"
          x={benchX1}
          y={benchLaneY}
          width={Math.max(2, benchX2 - benchX1)}
          height={BENCH_LANE_HEIGHT}
          rx={6}
          className="fill-[var(--chart-bar-inactive-bg)]"
          opacity={0.45}
        >
          <title>
            평균 음역대 {midiToKoreanNoteName(benchmark.lowMidi)} ~{" "}
            {midiToKoreanNoteName(benchmark.highMidi)}
          </title>
        </rect>

        {/* 사용자 밴드 — 강조 */}
        <rect
          data-testid="voice-range-scale-user-band"
          x={userX1}
          y={userLaneY}
          width={Math.max(2, userX2 - userX1)}
          height={userLaneHeight}
          rx={5}
          className="fill-[var(--chart-bar-active-bg)]"
          opacity={compact ? 0.8 : undefined}
        >
          <title>
            내 음역대 {lowNote} ~ {highNote}
          </title>
        </rect>

        {/* 사용자 양 끝 음표명(한국어 음명) */}
        <text
          x={userX1}
          y={benchLaneY - 5}
          textAnchor="middle"
          className="fill-[var(--chart-bar-active-bg)] text-[10px] font-semibold"
        >
          {lowNote}
        </text>
        <text
          x={userX2}
          y={benchLaneY - 5}
          textAnchor="middle"
          className="fill-[var(--chart-bar-active-bg)] text-[10px] font-semibold"
        >
          {highNote}
        </text>
      </svg>
    </figure>
  );
}

/**
 * 가시 범위([rangeLow, rangeHigh]) 내 옥타브 시작음(C-노트) MIDI 목록.
 *
 * - 후보: MIDI 24(C1) ~ 108(C8) 사이 C-노트(12의 배수).
 * - 가시 범위 내 C-노트가 없으면(매우 좁은 음역) 범위 중앙에 가장 가까운 C 1개.
 *   VoiceRangeProgressCard.pickOctaveAnchors 와 동일 철학(가로축 버전).
 */
function pickOctaveAnchors(rangeLow: number, rangeHigh: number): number[] {
  const candidates: number[] = [];
  for (let midi = 24; midi <= 108; midi += 12) {
    if (midi >= rangeLow && midi <= rangeHigh) {
      candidates.push(midi);
    }
  }
  if (candidates.length === 0) {
    const center = (rangeLow + rangeHigh) / 2;
    return [Math.round(center / 12) * 12];
  }
  return candidates;
}
