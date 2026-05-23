"use client";

/**
 * 음역 발전 추적 카드 (closes #170 / #249).
 *
 * 영감:
 *   - Yousician/Duolingo 류 학습 앱이 "기록을 그래프로 보여주면 사용자가 다시 돌아온다"는
 *     관찰. mobruji 에서도 음역 측정·갱신을 여러 번 한 사용자에게 본인이 측정값을 어떻게
 *     좁히거나 넓혀왔는지를 한 화면에서 확인시킨다.
 *
 * 시각화 방식:
 *   - SVG 외부 라이브러리 없이 직접 그린다 (lib 추가 금지). 차트 lib(recharts 등)은
 *     gzipped 80KB 이상 부담 + PR #170 의도적 결정과 충돌.
 *   - 각 측정 = 세로 막대 — 막대 위쪽 = highMidi, 아래쪽 = lowMidi. 막대 길이 = 음역폭.
 *   - x축은 시간(왼쪽=과거, 오른쪽=현재)으로 일정 간격으로 배치 — 측정 시점의 간격을
 *     반영하지 않는다. v1 PoC 에선 "몇 번째 측정인가"만 보여도 충분하고, 실제 시간
 *     간격을 x좌표로 매핑하면 단일 세션 내 짧은 측정 폭으로 막대들이 한쪽으로 몰린다.
 *   - 최신 막대는 진한 색상 + 음표명 라벨 위로 강조 — 시선이 가장 최근 측정에 모이도록.
 *   - 모든 막대 양 끝에 작은 음표명 라벨 (#249) — 측정별 lowMidi/highMidi 변화를
 *     한눈에 비교 가능. 최신 막대는 강조 텍스트 색을 사용.
 *   - 좌측 Y축에 옥타브 시작음(C2/C3/C4/...) 가로 그리드 라인 + 텍스트 (#249) —
 *     MIDI 숫자만 보던 막대를 "노래방 키 감각"으로 해석 가능하게.
 *   - 음역폭 변화 헤드라인(예: "+6 반음 넓어짐")을 상단에 노출.
 *
 * 접근성:
 *   - SVG 에 role="img" + aria-label 로 차트 요약을 텍스트로 제공.
 *   - 막대 각각에는 <title> 으로 측정 시점/음역 정보 — 마우스 호버 + 스크린리더 호환.
 *   - 막대 음표명 라벨/Y축 옥타브 라벨은 시각 보조용 — 동일 정보가 헤더 텍스트와 <title>
 *     에 이미 있으므로 SVG <text> 자체는 스크린리더가 읽되 중복 안내가 되지 않도록
 *     컴포넌트 컨테이너의 aria-label 이 요약을 담당한다.
 */

import { midiToCombinedNoteName, midiToNoteName } from "@/lib/notes";
import { formatRelativeKorean } from "@/lib/relativeTime";
import type {
  VoiceRangeProgressPoint,
  VoiceRangeProgressSummary,
} from "@/lib/voiceRangeProgress";

const CHART_WIDTH = 320;
const CHART_HEIGHT = 168; // #249: Y축 라벨/막대 음표명 공간 확보로 140 → 168.
const PADDING_LEFT = 32; // #249: Y축 옥타브 라벨(C4 등 2~3자) 영역.
const PADDING_RIGHT = 16;
const PADDING_TOP = 14; // #249: 막대 상단 음표명 라벨 공간 확보로 8 → 14.
const PADDING_BOTTOM = 28; // x축 라벨 영역 + 막대 하단 음표명.
const BAR_MIN_HEIGHT = 4; // 음역폭이 0이라도 시각적으로 인지 가능하도록.
const Y_AXIS_VERTICAL_PAD = 2; // 최댓값/최솟값이 차트 경계에 딱 붙지 않도록.
const OCTAVE_LABEL_MIN_GAP_PX = 18; // 옥타브 라벨 간 최소 픽셀 간격 — 좁으면 일부 생략.

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
    lowMidiDeltaSemitones,
    highMidiDeltaSemitones,
  } = summary;

  // 방어 가드 (#567): 부모(page.tsx)가 빈 데이터를 별도 분기하지만, 회귀 시 silent
  // crash 방지 + 스크린리더 status 안내.
  if (points.length === 0) {
    return (
      <p role="status" className="text-xs text-zinc-500 dark:text-zinc-400">
        아직 표시할 측정 기록이 없어요.
      </p>
    );
  }

  const yMin = minLowMidi - Y_AXIS_VERTICAL_PAD;
  const yMax = maxHighMidi + Y_AXIS_VERTICAL_PAD;
  const yRange = Math.max(1, yMax - yMin);

  const innerHeight = CHART_HEIGHT - PADDING_TOP - PADDING_BOTTOM;
  const innerWidth = CHART_WIDTH - PADDING_LEFT - PADDING_RIGHT;

  // 막대 가로 배치: 점 개수에 따라 균등 분할.
  const slotWidth =
    points.length === 1 ? innerWidth : innerWidth / points.length;
  const barWidth = Math.max(8, Math.min(24, slotWidth * 0.55));

  function midiToY(midi: number): number {
    // 차트 좌표계: y=0 이 위쪽이므로 높은 음일수록 작은 y 값.
    const ratio = (midi - yMin) / yRange;
    return PADDING_TOP + innerHeight * (1 - ratio);
  }

  function indexToX(index: number): number {
    if (points.length === 1) {
      return PADDING_LEFT + innerWidth / 2;
    }
    return PADDING_LEFT + slotWidth * (index + 0.5);
  }

  const latest = points[points.length - 1];

  const headline = buildHeadline(spanDeltaSemitones, latestSpanSemitones);

  // #249: Y축에 표시할 옥타브 시작음(C2/C3/...) MIDI 후보 — 차트 가시 범위(yMin~yMax)
  // 내부에 있는 C-노트들. 좁은 음역(예: C4~G4)이면 1개만, 넓은 음역(C2~B5)이면 4개.
  const octaveAnchors = pickOctaveAnchors(yMin, yMax, innerHeight);

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
        {/*
         * 첫 측정 대비 lowMidi/highMidi delta — spec voice-range-progress §3.
         * 양수=음(피치)이 높아진 방향(고음 확장 / 저음 좁아짐), 음수=낮아진 방향.
         * 헤드라인(spanDelta)은 폭만 보여주므로 lower/upper bound 변화를 별도 표기.
         */}
        <p className="text-xs text-zinc-500 dark:text-zinc-400">
          첫 측정 대비 저음 {formatSemitoneDelta(lowMidiDeltaSemitones)} · 고음{" "}
          {formatSemitoneDelta(highMidiDeltaSemitones)}
        </p>
      </header>

      <svg
        role="img"
        aria-label={buildAriaLabel(points, spanDeltaSemitones)}
        viewBox={`0 0 ${CHART_WIDTH} ${CHART_HEIGHT}`}
        className="w-full max-w-md text-zinc-400 dark:text-zinc-500"
      >
        {/* #249: Y축 옥타브 그리드 — 가로 점선 + 좌측 라벨. 막대 뒤로 가도록 먼저 렌더. */}
        {octaveAnchors.map((midi) => {
          const y = midiToY(midi);
          return (
            <g key={`octave-${midi}`} aria-hidden="true">
              <line
                x1={PADDING_LEFT}
                x2={CHART_WIDTH - PADDING_RIGHT}
                y1={y}
                y2={y}
                stroke="currentColor"
                strokeWidth={1}
                strokeDasharray="1 3"
                opacity={0.35}
              />
              <text
                x={PADDING_LEFT - 4}
                y={y + 3}
                textAnchor="end"
                className="fill-zinc-400 text-[9px] dark:fill-zinc-500"
              >
                {midiToNoteName(midi)}
              </text>
            </g>
          );
        })}

        {/* baseline */}
        <line
          x1={PADDING_LEFT}
          x2={CHART_WIDTH - PADDING_RIGHT}
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
          // 차트 막대 라벨은 공간이 좁아 SPN만 사용 (#318).
          // 툴팁은 한국어 (SPN) 병기로 학습 효과 + 직관성 확보.
          const highNoteShort = midiToNoteName(point.highMidi);
          const lowNoteShort = midiToNoteName(point.lowMidi);
          const highNoteCombined = midiToCombinedNoteName(point.highMidi);
          const lowNoteCombined = midiToCombinedNoteName(point.lowMidi);

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
                  {formatRelativeKorean(point.requestedAt)}: {lowNoteCombined}{" "}
                  ~ {highNoteCombined} ({point.highMidi - point.lowMidi} 반음)
                </title>
              </rect>
              {/*
               * #249: 모든 막대 상단/하단에 음표명 — 최신 막대는 강조색, 나머지는 옅게.
               * 막대가 좁아도 textAnchor=middle 로 중앙 정렬 + 막대 위/아래 여백에 배치.
               */}
              <text
                x={x + barWidth / 2}
                y={yTop - 3}
                textAnchor="middle"
                className={
                  isLatest
                    ? "fill-zinc-900 text-[10px] font-semibold dark:fill-zinc-100"
                    : "fill-zinc-500 text-[9px] dark:fill-zinc-400"
                }
              >
                {highNoteShort}
              </text>
              <text
                x={x + barWidth / 2}
                y={yBottom + 9}
                textAnchor="middle"
                className={
                  isLatest
                    ? "fill-zinc-700 text-[9px] font-medium dark:fill-zinc-200"
                    : "fill-zinc-400 text-[9px] dark:fill-zinc-500"
                }
              >
                {lowNoteShort}
              </text>
            </g>
          );
        })}

        {/* x축 라벨: 가장 왼쪽=과거, 가장 오른쪽=최근 */}
        <text
          x={PADDING_LEFT}
          y={CHART_HEIGHT - 6}
          className="fill-zinc-500 text-[10px] dark:fill-zinc-400"
        >
          처음 측정
        </text>
        <text
          x={CHART_WIDTH - PADDING_RIGHT}
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

/**
 * 차트 가시 범위([yMin, yMax]) 내의 옥타브 시작음(C-노트) MIDI 목록을 반환한다 (#249).
 *
 * - 후보: MIDI 24(C1) ~ 108(C8) 사이의 C-노트 (12의 배수).
 * - 차트 세로 픽셀 공간(innerHeight)에 비해 라벨이 너무 빽빽하면 일부를 건너뛴다
 *   (OCTAVE_LABEL_MIN_GAP_PX 보장). 단, 가장 가까운 C-노트 1개는 항상 반환해
 *   범위가 좁아도(예: C4~G4) 라벨 1개는 보이도록 한다.
 */
function pickOctaveAnchors(
  yMin: number,
  yMax: number,
  innerHeight: number,
): number[] {
  const candidates: number[] = [];
  for (let midi = 24; midi <= 108; midi += 12) {
    if (midi >= yMin && midi <= yMax) {
      candidates.push(midi);
    }
  }
  if (candidates.length === 0) {
    // 가시 범위 내 C-노트가 0개(예: 매우 좁은 음역) → 범위 중앙에 가장 가까운 C 1개 표시.
    const center = (yMin + yMax) / 2;
    const nearestC = Math.round(center / 12) * 12;
    return [nearestC];
  }
  if (candidates.length === 1) {
    return candidates;
  }
  // 라벨 픽셀 간 거리가 임계값 이상이면 모두 표시, 아니면 격간(매 N개) 추출.
  const yRange = Math.max(1, yMax - yMin);
  const pxPerSemi = innerHeight / yRange;
  const pxPerOctave = pxPerSemi * 12;
  if (pxPerOctave >= OCTAVE_LABEL_MIN_GAP_PX) {
    return candidates;
  }
  // 너무 빽빽한 경우(현재 차트 크기로는 거의 발생 X — 약 7옥타브 이상 범위일 때)
  // 짝수 인덱스만 추출.
  return candidates.filter((_, index) => index % 2 === 0);
}

/**
 * 반음 delta 값을 부호 포함 한국어 라벨로 포맷한다.
 *   +3 → "+3 반음", -2 → "-2 반음", 0 → "변화 없음".
 *   spec voice-range-progress §3 fe 표시 항목.
 */
function formatSemitoneDelta(delta: number): string {
  if (delta === 0) {
    return "변화 없음";
  }
  const sign = delta > 0 ? "+" : "";
  return `${sign}${delta} 반음`;
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
    delta > 0
      ? `${delta}반음 넓어짐`
      : delta < 0
        ? `${Math.abs(delta)}반음 좁아짐`
        : "변동 없음";
  return `음역 발전 차트. 첫 측정 ${midiToNoteName(earliest.lowMidi)}~${midiToNoteName(earliest.highMidi)}, 최근 측정 ${midiToNoteName(latest.lowMidi)}~${midiToNoteName(latest.highMidi)}, ${direction}.`;
}
