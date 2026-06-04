"use client";

/**
 * PitchWaveRing — 음역대 자동 측정 시각화 (ui-ux-redesign-pr-5-pitch-wave.md §5).
 *
 * 측정 중 화면의 "wow" 시각: Apple Health activity ring 모티프의 270° SVG ring 위에
 *   - progress fill (측정 진행 0~100%)
 *   - indicator dot (현재 음정 위치 — Hz → MIDI → ring 좌표)
 *   - amplitude 에 따른 dot 색 강도 + 하단 frequency bar
 *   - 25/50/75/100% sparkle(✅) + 100% 도달 brand gradient flash
 *   - ring 내부 중앙 step indicator (N/M + 안내 텍스트)
 * 를 동시에 그린다. 외부 차트 라이브러리 없이 자체 SVG + CSS-only animation.
 *
 * 본 컴포넌트는 순수 presentational — Web Audio / 마이크 / 측정 알고리즘 의존 0.
 * 페이지가 측정 상태를 props 로 매핑한다. 알고리즘 SoT 보존(시각화 layer 만).
 *
 * 접근성:
 *   - svg role="img" + aria-label 동적 갱신(debounce 500ms — SR spam 회피, §3 C/G4).
 *   - step indicator aria-current="step" (현 wizard 부재 → 본 PR 보강, G10).
 *   - prefers-reduced-motion: dot transition / sparkle / flash 0ms. 단 dot 위치
 *     즉시 갱신은 유지(pitch 정보 본질, §3 C/G5).
 */

import { useEffect, useState } from "react";

import { midiToKoreanNoteName } from "@/lib/notes";
import { usePrefersReducedMotion } from "@/lib/usePrefersReducedMotion";
import {
  RING_RADIUS,
  RING_STROKE_WIDTH,
  SPARKLE_MILESTONES,
  arcCircumference,
  arcPath,
  hzToMidi,
  midiToRingCoord,
  midiToRingPercent,
  progressDashOffset,
} from "./PitchWaveRing.helpers";

const ARIA_DEBOUNCE_MS = 500;
const VIEWBOX = "-150 -150 300 300";

export interface PitchWaveRingProps {
  /** ring 좌표 스케일 — 표시할 음역대 하한/상한 MIDI. */
  lowMidi: number;
  highMidi: number;
  /** 현재 입력 음정 Hz. null/무효면 indicator dot 숨김. */
  currentFrequencyHz: number | null;
  /** 측정 진행률 0~100. progress fill + sparkle milestone 기준. */
  progressPercent: number;
  /** 0~1 마이크 입력 강도. dot 색 강도 + frequency bar 길이. */
  amplitude?: number;
  /** 이전 측정 음역대 [low, high]. 있으면 점선 outline overlay(G9). */
  previousRangeMidi?: readonly [number, number];
  /** ring 중앙 step indicator — 현재 단계 / 총 단계. */
  stepNumber?: number;
  totalSteps?: number;
  /** step 안내 텍스트(예: "편안한 음으로 아~ 발성해보세요"). */
  stepLabel?: string;
}

function amplitudeDotToken(amplitude: number): string {
  if (amplitude >= 0.66) {
    return "var(--brand-700)";
  }
  if (amplitude >= 0.33) {
    return "var(--brand-600)";
  }
  return "var(--brand-500)";
}

export function PitchWaveRing({
  lowMidi,
  highMidi,
  currentFrequencyHz,
  progressPercent,
  amplitude = 0,
  previousRangeMidi,
  stepNumber,
  totalSteps = 4,
  stepLabel,
}: PitchWaveRingProps) {
  const reducedMotion = usePrefersReducedMotion();

  const currentMidi =
    currentFrequencyHz !== null ? hzToMidi(currentFrequencyHz) : Number.NaN;
  const hasPitch = Number.isFinite(currentMidi);
  const currentNoteName = hasPitch
    ? midiToKoreanNoteName(Math.round(currentMidi))
    : null;

  const clampedProgress = Math.min(100, Math.max(0, progressPercent));
  const clampedAmplitude = Math.min(1, Math.max(0, amplitude));

  const circumference = arcCircumference(RING_RADIUS);
  const dashOffset = progressDashOffset(circumference, clampedProgress);
  const dot = midiToRingCoord(currentMidi, lowMidi, highMidi, RING_RADIUS);

  // aria-label 동적 갱신 — debounce 로 SR spam 회피(§3 C, G4).
  const liveLabel = hasPitch
    ? `음역대 측정 진행 ${Math.round(clampedProgress)}% — 현재 음정 ${currentNoteName}`
    : `음역대 측정 진행 ${Math.round(clampedProgress)}% — 음정 측정 대기`;
  const [debouncedLabel, setDebouncedLabel] = useState(liveLabel);
  useEffect(() => {
    const timer = setTimeout(() => {
      setDebouncedLabel(liveLabel);
    }, ARIA_DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [liveLabel]);

  const showFlash = clampedProgress >= 100;

  return (
    <div
      className="relative mx-auto flex w-full max-w-[280px] flex-col items-center gap-3"
      data-testid="pitch-wave-ring"
    >
      <div className="relative w-full">
        <svg
          role="img"
          aria-label={debouncedLabel}
          viewBox={VIEWBOX}
          className="w-full"
        >
          {/* base ring — 270° arc 트랙 */}
          <path
            data-testid="pitch-ring-base"
            d={arcPath(RING_RADIUS, 0, 1)}
            fill="none"
            stroke="var(--bg-subtle)"
            strokeWidth={RING_STROKE_WIDTH}
            strokeLinecap="round"
          />

          {/* 이전 측정 음역대 outline (점선) — 비교용(G9) */}
          {previousRangeMidi ? (
            <path
              data-testid="pitch-ring-previous"
              d={arcPath(
                RING_RADIUS,
                midiToRingPercent(previousRangeMidi[0], lowMidi, highMidi),
                midiToRingPercent(previousRangeMidi[1], lowMidi, highMidi),
              )}
              fill="none"
              stroke="var(--text-tertiary)"
              strokeWidth={2}
              strokeDasharray="4 4"
              strokeLinecap="round"
            />
          ) : null}

          {/* progress fill — dashoffset 으로 0~100% 채움(G3) */}
          <path
            data-testid="pitch-ring-progress"
            d={arcPath(RING_RADIUS, 0, 1)}
            fill="none"
            stroke="var(--brand-500)"
            strokeWidth={RING_STROKE_WIDTH}
            strokeLinecap="round"
            strokeDasharray={circumference}
            strokeDashoffset={dashOffset}
            style={
              reducedMotion
                ? undefined
                : {
                    transition: `stroke-dashoffset var(--duration-slow) var(--ease-emphasized)`,
                  }
            }
          />

          {/* sparkle milestone — 25/50/75/100% 도달 시 ✅(G12) */}
          {SPARKLE_MILESTONES.map((milestone) => {
            const reached = clampedProgress >= milestone;
            const coord = midiToRingCoord(
              lowMidi + ((highMidi - lowMidi) * milestone) / 100,
              lowMidi,
              highMidi,
              RING_RADIUS,
            );
            if (!reached) {
              return null;
            }
            return (
              // 바깥 g = 위치(translate), 안쪽 g = scale 애니메이션. CSS transform 이
              // presentation transform 속성을 덮어쓰므로 translate 와 scale 을 분리한다.
              <g
                key={milestone}
                data-testid={`pitch-sparkle-${milestone}`}
                data-active="true"
                transform={`translate(${coord.cx} ${coord.cy})`}
              >
                <g
                  className={reducedMotion ? undefined : "animate-pitch-sparkle"}
                >
                  <circle r={11} fill="var(--success-500)" />
                  <text
                    textAnchor="middle"
                    dominantBaseline="central"
                    className="fill-white text-[12px]"
                  >
                    ✓
                  </text>
                </g>
              </g>
            );
          })}

          {/* indicator dot — 현재 음정 위치(amplitude → 색 강도) */}
          {hasPitch ? (
            <circle
              data-testid="pitch-indicator-dot"
              cx={dot.cx}
              cy={dot.cy}
              r={10}
              fill={amplitudeDotToken(clampedAmplitude)}
              stroke="var(--bg-base)"
              strokeWidth={3}
              style={
                reducedMotion
                  ? undefined
                  : { transition: `cx 100ms linear, cy 100ms linear` }
              }
            />
          ) : null}
        </svg>

        {/* ring 중앙 step indicator — aria-current="step"(G10) */}
        <div
          aria-current="step"
          data-testid="pitch-step-indicator"
          className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center gap-1 text-center"
        >
          {stepNumber ? (
            <span
              className="leading-none text-[var(--text-primary)] tabular-nums"
              style={{ fontSize: "2.5rem", fontWeight: "var(--font-black)" }}
            >
              {stepNumber}
              <span className="text-[var(--text-tertiary)]"> / {totalSteps}</span>
            </span>
          ) : null}
          {stepLabel ? (
            <span className="max-w-[180px] text-sm text-[var(--text-secondary)]">
              {stepLabel}
            </span>
          ) : null}
        </div>

        {/* 100% 도달 brand gradient flash(§3) */}
        {showFlash ? (
          <div
            data-testid="pitch-brand-flash"
            aria-hidden="true"
            className={
              reducedMotion
                ? "pointer-events-none absolute inset-0 rounded-full"
                : "animate-brand-flash pointer-events-none absolute inset-0 rounded-full"
            }
            style={{ background: "var(--brand-gradient)" }}
          />
        ) : null}
      </div>

      {/* 실시간 frequency bar — amplitude 막대(Spotify wave 모티프) */}
      <div
        data-testid="pitch-frequency-bar"
        role="presentation"
        aria-hidden="true"
        className="h-2 w-full overflow-hidden rounded-full bg-[var(--meter-track-bg)]"
      >
        <div
          className="h-full rounded-full"
          style={{
            width: `${Math.round(clampedAmplitude * 100)}%`,
            background: "var(--brand-gradient)",
            transition: reducedMotion ? undefined : "width 100ms linear",
          }}
        />
      </div>
    </div>
  );
}
