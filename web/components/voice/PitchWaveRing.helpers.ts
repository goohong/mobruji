/**
 * PitchWaveRing 순수 기하/변환 헬퍼 (ui-ux-redesign-pr-5-pitch-wave.md §5-2 / §5-3).
 *
 * 컴포넌트에서 분리해 결정성 테스트(G1~G3)를 React 렌더 없이 검증한다. 모든 함수는
 * 부수효과 없는 순수 함수 — 같은 입력 → 같은 출력(60fps requestAnimationFrame
 * 루프에서 매 프레임 호출돼도 안전).
 */

import { frequencyToMidi } from "@/lib/audio/midiConvert";

/** Apple Health activity ring 모티프: 270° arc(-135° ~ 135°, 하단 90° 공백). */
export const RING_RADIUS = 120;
export const RING_STROKE_WIDTH = 12;
const ARC_DEGREES = 270;
const ARC_START_DEG = -135;

/** progress ring milestone — 25/50/75/100 도달 시 sparkle(✅) 부착(§3 G12). */
export const SPARKLE_MILESTONES = [25, 50, 75, 100] as const;

/**
 * Hz → (연속) MIDI. `12 * log2(hz / 440) + 69` 고정 공식(§5-2).
 *
 * 자체 공식 재구현 대신 `lib/audio/midiConvert.frequencyToMidi` 를 재노출한다 —
 * 자동 측정 알고리즘 SoT(voice-range-auto-measurement.md)와 단일 진실 원천 유지.
 * 무효 입력(hz<=0/비유한)은 `NaN` → 호출자가 indicator dot 을 숨긴다.
 */
export function hzToMidi(hz: number): number {
  return frequencyToMidi(hz);
}

/**
 * MIDI 를 [lowMidi, highMidi] 구간 내 0~1 비율로 선형 보간(§5-2).
 * 구간이 비정상(highMidi<=lowMidi)이면 0 반환.
 */
export function midiToRingPercent(
  midi: number,
  lowMidi: number,
  highMidi: number,
): number {
  if (highMidi <= lowMidi) {
    return 0;
  }
  return (midi - lowMidi) / (highMidi - lowMidi);
}

function clamp01(value: number): number {
  return Math.min(1, Math.max(0, value));
}

/** ring 비율(0~1)을 SVG 좌표로. percent 는 arc 범위로 clamp(dot 이 arc 밖으로 안 나감). */
export function ringPercentToCoord(
  percent: number,
  radius: number,
): { cx: number; cy: number } {
  const angleDeg = ARC_START_DEG + clamp01(percent) * ARC_DEGREES;
  const angleRad = (angleDeg * Math.PI) / 180;
  return {
    cx: radius * Math.sin(angleRad),
    cy: -radius * Math.cos(angleRad),
  };
}

/**
 * MIDI → ring 좌표(§5-2). 중간값은 12시(위쪽) — `midiToRingCoord(60, 48, 72, 120)`
 * = `{ cx: 0, cy: -120 }`.
 */
export function midiToRingCoord(
  midi: number,
  lowMidi: number,
  highMidi: number,
  radius: number,
): { cx: number; cy: number } {
  return ringPercentToCoord(midiToRingPercent(midi, lowMidi, highMidi), radius);
}

/** 270° arc 길이(stroke-dasharray 기준값). */
export function arcCircumference(radius: number): number {
  return 2 * Math.PI * radius * (ARC_DEGREES / 360);
}

/**
 * progress ring stroke-dashoffset(§5-2 G3).
 * 0% → circumference(보이지 않음), 100% → 0(꽉 참).
 */
export function progressDashOffset(
  circumference: number,
  progressPercent: number,
): number {
  const clamped = Math.min(100, Math.max(0, progressPercent));
  return circumference * (1 - clamped / 100);
}

function round(value: number): number {
  return Math.round(value * 1000) / 1000;
}

/**
 * [startPercent, endPercent] 구간(0~1)을 그리는 SVG arc path `d`.
 * base ring(0~1) / previousRange outline(이전 음역대 구간)에 공용.
 */
export function arcPath(
  radius: number,
  startPercent: number,
  endPercent: number,
): string {
  const start = ringPercentToCoord(startPercent, radius);
  const end = ringPercentToCoord(endPercent, radius);
  const sweepDeg = (clamp01(endPercent) - clamp01(startPercent)) * ARC_DEGREES;
  const largeArcFlag = Math.abs(sweepDeg) > 180 ? 1 : 0;
  return `M ${round(start.cx)} ${round(start.cy)} A ${radius} ${radius} 0 ${largeArcFlag} 1 ${round(end.cx)} ${round(end.cy)}`;
}
