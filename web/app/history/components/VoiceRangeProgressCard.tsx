"use client";

/**
 * 음역 발전 추적 카드 (closes #170).
 *
 * 영감:
 *   - Yousician/Duolingo 류 학습 앱이 "기록을 그래프로 보여주면 사용자가 다시 돌아온다"는
 *     관찰. mobruji 에서도 음역 측정·갱신을 여러 번 한 사용자에게 본인이 측정값을 어떻게
 *     좁히거나 넓혀왔는지를 한 화면에서 확인시킨다.
 *
 * 시각화 방식:
 *   - SVG 외부 라이브러리 없이 직접 그린다 (lib 추가 금지).
 *   - 각 측정 = 세로 막대 — 막대 위쪽 = highMidi, 아래쪽 = lowMidi. 막대 길이 = 음역폭.
 *   - x축은 시간(왼쪽=과거, 오른쪽=현재)으로 일정 간격으로 배치 — 측정 시점의 간격을
 *     반영하지 않는다. v1 PoC 에선 "몇 번째 측정인가"만 보여도 충분하고, 실제 시간
 *     간격을 x좌표로 매핑하면 단일 세션 내 짧은 측정 폭으로 막대들이 한쪽으로 몰린다.
 *   - 최신 막대만 색상을 진하게 + 음표명 라벨 — 시선이 가장 최근 측정에 모이도록.
 *   - 음역폭 변화 헤드라인(예: "+6 반음 넓어짐")을 상단에 노출.
 *
 * 접근성:
 *   - SVG 에 role="img" + aria-label 로 차트 요약을 텍스트로 제공.
 *   - 막대 각각에는 <title> 으로 측정 시점/음역 정보 — 마우스 호버 + 스크린리더 호환.
 */

import { midiToNoteName } from "@/lib/notes";
import { formatRelativeKorean } from "@/lib/relativeTime";
import type {
  VoiceRangeProgressPoint,
  VoiceRangeProgressSummary,
} from "@/lib/voiceRangeProgress";

const CHART_WIDTH = 320;
const CHART_HEIGHT = 140;
const PADDING_X = 16;
const PADDING_TOP = 8;
const PADDING_BOTTOM = 24; // x축 라벨 영역
const BAR_MIN_HEIGHT = 4; // 음역폭이 0이라도 시각적으로 인지 가능하도록.
const Y_AXIS_VERTICAL_PAD = 2; // 최댓값/최솟값이 차트 경계에 딱 붙지 않도록.

type Props = {
  summary: VoiceRangeProgressSummary;
};

export function VoiceRangeProgressCard({ summary }: Props) {
  const {
    points,
    minLowMidi,
    maxHighMidi,
    latestSpanSemitones,
    spanDeltaSemitones,
  } = summary;

  const yMin = minLowMidi - Y_AXIS_VERTICAL_PAD;
  const yMax = maxHighMidi + Y_AXIS_VERTICAL_PAD;
  const yRange = Math.max(1, yMax - yMin);

  const innerHeight = CHART_HEIGHT - PADDING_TOP - PADDING_BOTTOM;
  const innerWidth = CHART_WIDTH - PADDING_X * 2;

  // 막대 가로 배치: 점 개수에 따라 균등 분할.
  const slotWidth =
    points.length === 1 ? innerWidth : innerWidth / (points.length - 1 + 1);
  const barWidth = Math.max(8, Math.min(24, slotWidth * 0.55));

  function midiToY(midi: number): number {
    // 차트 좌표계: y=0 이 위쪽이므로 높은 음일수록 작은 y 값.
    const ratio = (midi - yMin) / yRange;
    return PADDING_TOP + innerHeight * (1 - ratio);
  }

  function indexToX(index: number): number {
    if (points.length === 1) {
      return PADDING_X + innerWidth / 2;
    }
    return PADDING_X + slotWidth * (index + 0.5);
  }

  const latest = points[points.length - 1];

  const headline = buildHeadline(spanDeltaSemitones, latestSpanSemitones);

  return (
    <section
      aria-labelledby="voice-range-progress-heading"
      className="flex flex-col gap-4 rounded-2xl bg-white p-4 ring-1 ring-zinc-200 dark:bg-zinc-900 dark:ring-zinc-800"
    >
      <header className="flex flex-col gap-1">
        <p className="text-xs font-medium uppercase tracking-widest text-zinc-500 dark:text-zinc-400">
          내 음역 발전 추적
        </p>
        <h2
          id="voice-range-progress-heading"
          className="text-base font-semibold text-zinc-900 dark:text-zinc-50"
        >
          {headline}
        </h2>
        <p className="text-xs text-zinc-500 dark:text-zinc-400">
          최근 측정 {midiToNoteName(latest.lowMidi)} ~{" "}
          {midiToNoteName(latest.highMidi)} · {points.length}회 측정 기록
        </p>
      </header>

      <svg
        role="img"
        aria-label={buildAriaLabel(points, spanDeltaSemitones)}
        viewBox={`0 0 ${CHART_WIDTH} ${CHART_HEIGHT}`}
        className="w-full max-w-md text-zinc-400 dark:text-zinc-500"
      >
        {/* baseline */}
        <line
          x1={PADDING_X}
          x2={CHART_WIDTH - PADDING_X}
          y1={CHART_HEIGHT - PADDING_BOTTOM}
          y2={CHART_HEIGHT - PADDING_BOTTOM}
          stroke="currentColor"
          strokeWidth={1}
          strokeDasharray="2 4"
          opacity={0.5}
        />

        {points.map((point, index) => {
          const x = indexToX(index) - barWidth / 2;
          const yTop = midiToY(point.highMidi);
          const yBottom = midiToY(point.lowMidi);
          const heightRaw = yBottom - yTop;
          const height = Math.max(BAR_MIN_HEIGHT, heightRaw);
          const isLatest = index === points.length - 1;

          return (
            <g key={point.id}>
              <rect
                x={x}
                y={yTop}
                width={barWidth}
                height={height}
                rx={3}
                className={
                  isLatest
                    ? "fill-zinc-900 dark:fill-zinc-100"
                    : "fill-zinc-300 dark:fill-zinc-700"
                }
              >
                <title>
                  {formatRelativeKorean(point.requestedAt)}:{" "}
                  {midiToNoteName(point.lowMidi)} ~{" "}
                  {midiToNoteName(point.highMidi)} (
                  {point.highMidi - point.lowMidi} 반음)
                </title>
              </rect>
              {isLatest ? (
                <text
                  x={x + barWidth / 2}
                  y={yTop - 4}
                  textAnchor="middle"
                  className="fill-zinc-900 text-[10px] font-medium dark:fill-zinc-100"
                >
                  {midiToNoteName(point.highMidi)}
                </text>
              ) : null}
            </g>
          );
        })}

        {/* x축 라벨: 가장 왼쪽=과거, 가장 오른쪽=최근 */}
        <text
          x={PADDING_X}
          y={CHART_HEIGHT - 6}
          className="fill-zinc-500 text-[10px] dark:fill-zinc-400"
        >
          처음 측정
        </text>
        <text
          x={CHART_WIDTH - PADDING_X}
          y={CHART_HEIGHT - 6}
          textAnchor="end"
          className="fill-zinc-500 text-[10px] dark:fill-zinc-400"
        >
          최근 측정
        </text>
      </svg>
    </section>
  );
}

function buildHeadline(delta: number, latestSpan: number): string {
  if (delta > 0) {
    return `음역이 +${delta} 반음 넓어졌어요`;
  }
  if (delta < 0) {
    return `최근 측정이 ${Math.abs(delta)} 반음 더 좁아졌어요`;
  }
  return `음역폭이 ${latestSpan} 반음으로 유지되고 있어요`;
}

function buildAriaLabel(
  points: readonly VoiceRangeProgressPoint[],
  delta: number,
): string {
  const earliest = points[0];
  const latest = points[points.length - 1];
  const direction =
    delta > 0 ? `${delta}반음 넓어짐` : delta < 0 ? `${Math.abs(delta)}반음 좁아짐` : "변동 없음";
  return `음역 발전 차트. 첫 측정 ${midiToNoteName(earliest.lowMidi)}~${midiToNoteName(earliest.highMidi)}, 최근 측정 ${midiToNoteName(latest.lowMidi)}~${midiToNoteName(latest.highMidi)}, ${direction}.`;
}
