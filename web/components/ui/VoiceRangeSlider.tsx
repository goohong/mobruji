"use client";

/**
 * 음역대(최저음·최고음) 입력용 두 손잡이 범위 슬라이더 (이슈 #1706).
 *
 * 영상 편집 트림 핸들처럼 가로 트랙 위 두 손잡이(최저/최고 MIDI)를 드래그해
 * 음역대를 고른다. select 드롭다운(옵션 49개)을 대체해 모든 설정 화면에서
 * 일관된 음역대 입력 경험을 제공한다.
 *
 * - pointer + touch 드래그: Pointer Events 단일 경로(setPointerCapture)로
 *   마우스/터치/펜을 모두 처리한다. 손잡이는 `touch-action: none` 으로 스크롤과
 *   드래그 충돌을 막는다.
 * - 제약: 최저음 ≤ 최고음. 각 손잡이는 상대 손잡이를 경계로 클램프한다(밀지 않음).
 * - 음이름 라벨: 각 손잡이 아래 한국어 음명(도4 등)을 노출한다.
 * - 키보드 a11y: 각 손잡이는 `role="slider"` + 화살표/Page/Home/End 로 조작.
 * - 디자인 토큰 + prefers-reduced-motion: 트랜지션은 토큰으로, 모션 선호 OFF 시 제거.
 */

import {
  KeyboardEvent as ReactKeyboardEvent,
  PointerEvent as ReactPointerEvent,
  useId,
  useRef,
} from "react";

import { midiToKoreanNoteName, midiToNoteName } from "@/lib/notes";
import { usePrefersReducedMotion } from "@/lib/usePrefersReducedMotion";

/** 두 손잡이 중 어느 쪽을 드래그 중인지. */
type ActiveThumb = "low" | "high";

export type VoiceRangeSliderProps = {
  /** 최저음 MIDI 정수. */
  lowMidi: number;
  /** 최고음 MIDI 정수. */
  highMidi: number;
  /** 손잡이 이동 시 변경된 (최저, 최고) 를 함께 전달한다. */
  onChange: (next: { lowMidi: number; highMidi: number }) => void;
  /** 트랙 최소값 MIDI (기본 G2=43). */
  minMidi?: number;
  /** 트랙 최대값 MIDI (기본 C6=84). */
  maxMidi?: number;
  /** 최저음 손잡이 라벨 (기본 "최저음"). */
  lowLabel?: string;
  /** 최고음 손잡이 라벨 (기본 "최고음"). */
  highLabel?: string;
  /** true 면 손잡이를 비활성화한다. */
  disabled?: boolean;
};

// 트랙 하한은 실제 곡 보컬 음역 분포(C3=48 ~ C6=84)에 맞춰 G2(43)로 둔다 (#1853).
// 곡이 0개인 C2~F#2(36~42) dead-zone 을 트랙에서 제거하되, 곡 최저음(C3) 아래로
// 약간의 여유(5반음)를 남겨 저음 사용자가 자신의 음역을 표현할 수 있게 한다.
const DEFAULT_MIN_MIDI = 43; // G2
const DEFAULT_MAX_MIDI = 84; // C6

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

/** 0~1 비율(트랙 좌→우)을 트랙 범위 내 MIDI 정수로 변환. */
function fractionToMidi(
  fraction: number,
  minMidi: number,
  maxMidi: number,
): number {
  const span = maxMidi - minMidi;
  return clamp(Math.round(minMidi + fraction * span), minMidi, maxMidi);
}

/** MIDI 값을 트랙 내 위치 백분율(0~100)로 변환. */
function midiToPercent(midi: number, minMidi: number, maxMidi: number): number {
  const span = maxMidi - minMidi;
  if (span <= 0) {
    return 0;
  }
  return (clamp(midi, minMidi, maxMidi) - minMidi) * (100 / span);
}

export function VoiceRangeSlider({
  lowMidi,
  highMidi,
  onChange,
  minMidi = DEFAULT_MIN_MIDI,
  maxMidi = DEFAULT_MAX_MIDI,
  lowLabel = "최저음",
  highLabel = "최고음",
  disabled = false,
}: VoiceRangeSliderProps) {
  const trackRef = useRef<HTMLDivElement | null>(null);
  const activeThumbRef = useRef<ActiveThumb | null>(null);
  const prefersReducedMotion = usePrefersReducedMotion();

  const groupLabelId = useId();

  // 손잡이 위치는 항상 정렬된 상태로 클램프해 최저음 ≤ 최고음 불변식을 보장한다.
  const safeLow = clamp(Math.min(lowMidi, highMidi), minMidi, maxMidi);
  const safeHigh = clamp(Math.max(lowMidi, highMidi), minMidi, maxMidi);

  const lowPercent = midiToPercent(safeLow, minMidi, maxMidi);
  const highPercent = midiToPercent(safeHigh, minMidi, maxMidi);

  function commit(thumb: ActiveThumb, nextMidi: number) {
    if (thumb === "low") {
      // 최저음은 최고음을 넘지 못한다 (상대 손잡이를 밀지 않고 경계로 클램프).
      const clamped = clamp(nextMidi, minMidi, safeHigh);
      if (clamped !== safeLow) {
        onChange({ lowMidi: clamped, highMidi: safeHigh });
      }
    } else {
      const clamped = clamp(nextMidi, safeLow, maxMidi);
      if (clamped !== safeHigh) {
        onChange({ lowMidi: safeLow, highMidi: clamped });
      }
    }
  }

  /** 포인터 clientX 를 트랙 범위 내 MIDI 로 변환. 트랙 폭 0 이면 null. */
  function midiAtClientX(clientX: number): number | null {
    const track = trackRef.current;
    if (!track) {
      return null;
    }
    const rect = track.getBoundingClientRect();
    if (rect.width <= 0) {
      return null;
    }
    const fraction = clamp((clientX - rect.left) / rect.width, 0, 1);
    return fractionToMidi(fraction, minMidi, maxMidi);
  }

  function resolveNearestThumb(pointerMidi: number): ActiveThumb {
    const lowDistance = Math.abs(pointerMidi - safeLow);
    const highDistance = Math.abs(pointerMidi - safeHigh);
    if (lowDistance < highDistance) {
      return "low";
    }
    if (highDistance < lowDistance) {
      return "high";
    }
    // 두 손잡이가 같은 값으로 겹친 경우: 포인터가 값보다 낮으면 최저음을 끌고,
    // 그렇지 않으면 최고음을 끌어 양방향 분리를 가능하게 한다.
    return pointerMidi < safeLow ? "low" : "high";
  }

  function handleTrackPointerDown(event: ReactPointerEvent<HTMLDivElement>) {
    if (disabled) {
      return;
    }
    const pointerMidi = midiAtClientX(event.clientX);
    if (pointerMidi === null) {
      return;
    }
    const thumb = resolveNearestThumb(pointerMidi);
    activeThumbRef.current = thumb;
    event.currentTarget.setPointerCapture?.(event.pointerId);
    commit(thumb, pointerMidi);
  }

  function handleTrackPointerMove(event: ReactPointerEvent<HTMLDivElement>) {
    const thumb = activeThumbRef.current;
    if (thumb === null) {
      return;
    }
    const pointerMidi = midiAtClientX(event.clientX);
    if (pointerMidi === null) {
      return;
    }
    commit(thumb, pointerMidi);
  }

  function endDrag(event: ReactPointerEvent<HTMLDivElement>) {
    if (activeThumbRef.current === null) {
      return;
    }
    activeThumbRef.current = null;
    event.currentTarget.releasePointerCapture?.(event.pointerId);
  }

  function handleThumbKeyDown(
    thumb: ActiveThumb,
    event: ReactKeyboardEvent<HTMLDivElement>,
  ) {
    if (disabled) {
      return;
    }
    const current = thumb === "low" ? safeLow : safeHigh;
    let next: number | null = null;
    switch (event.key) {
      case "ArrowLeft":
      case "ArrowDown":
        next = current - 1;
        break;
      case "ArrowRight":
      case "ArrowUp":
        next = current + 1;
        break;
      case "PageDown":
        next = current - 12;
        break;
      case "PageUp":
        next = current + 12;
        break;
      case "Home":
        next = thumb === "low" ? minMidi : safeLow;
        break;
      case "End":
        next = thumb === "low" ? safeHigh : maxMidi;
        break;
      default:
        return;
    }
    event.preventDefault();
    commit(thumb, next);
  }

  const transition = prefersReducedMotion
    ? "none"
    : `left var(--duration-base) var(--ease-out)`;

  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-baseline justify-between text-sm">
        <span id={groupLabelId} className="font-medium text-[var(--text-label)]">
          음역대
        </span>
        <span className="font-medium text-[var(--text-secondary)]">
          {midiToKoreanNoteName(safeLow)}{" "}
          <span aria-hidden="true" className="text-[var(--text-caption)]">
            ({midiToNoteName(safeLow)})
          </span>{" "}
          ~ {midiToKoreanNoteName(safeHigh)}{" "}
          <span aria-hidden="true" className="text-[var(--text-caption)]">
            ({midiToNoteName(safeHigh)})
          </span>
        </span>
      </div>

      {/*
        트랙 컨테이너. 손잡이 위/아래 라벨 공간을 확보하기 위해 세로 패딩을 둔다.
        포인터 핸들러는 트랙에 두고, 가장 가까운 손잡이를 끌어 트림 핸들 UX 를 낸다.
      */}
      <div
        ref={trackRef}
        data-testid="voice-range-track"
        onPointerDown={handleTrackPointerDown}
        onPointerMove={handleTrackPointerMove}
        onPointerUp={endDrag}
        onPointerCancel={endDrag}
        className={`relative h-10 select-none ${
          disabled ? "opacity-50" : "cursor-pointer"
        }`}
        style={{ touchAction: "none" }}
      >
        {/* 트랙 바탕 */}
        <div className="absolute inset-x-0 top-1/2 h-1.5 -translate-y-1/2 rounded-[var(--radius-full)] bg-[var(--border-input)]" />
        {/* 선택 구간 채움 */}
        <div
          className="absolute top-1/2 h-1.5 -translate-y-1/2 rounded-[var(--radius-full)] bg-[var(--brand-500)]"
          style={{
            left: `${lowPercent}%`,
            width: `${highPercent - lowPercent}%`,
            transition: prefersReducedMotion
              ? "none"
              : `left var(--duration-base) var(--ease-out), width var(--duration-base) var(--ease-out)`,
          }}
        />

        <Thumb
          position={lowPercent}
          value={safeLow}
          min={minMidi}
          max={safeHigh}
          label={lowLabel}
          disabled={disabled}
          transition={transition}
          onKeyDown={(event) => handleThumbKeyDown("low", event)}
        />
        <Thumb
          position={highPercent}
          value={safeHigh}
          min={safeLow}
          max={maxMidi}
          label={highLabel}
          disabled={disabled}
          transition={transition}
          onKeyDown={(event) => handleThumbKeyDown("high", event)}
        />
      </div>
    </div>
  );
}

type ThumbProps = {
  position: number;
  value: number;
  min: number;
  max: number;
  label: string;
  disabled: boolean;
  transition: string;
  onKeyDown: (event: ReactKeyboardEvent<HTMLDivElement>) => void;
};

function Thumb({
  position,
  value,
  min,
  max,
  label,
  disabled,
  transition,
  onKeyDown,
}: ThumbProps) {
  const koreanName = midiToKoreanNoteName(value);
  return (
    <div
      role="slider"
      aria-label={label}
      aria-valuemin={min}
      aria-valuemax={max}
      aria-valuenow={value}
      aria-valuetext={`${label} ${koreanName} (${midiToNoteName(value)})`}
      aria-disabled={disabled ? true : undefined}
      tabIndex={disabled ? -1 : 0}
      onKeyDown={onKeyDown}
      // 손잡이 자체의 포인터 다운은 트랙 핸들러로 버블링되게 두고(가장 가까운 손잡이
      // = 자신), 손잡이 위에서도 스크롤 대신 드래그가 되도록 touch-action 만 끈다.
      style={{ left: `${position}%`, transition, touchAction: "none" }}
      className="group absolute top-1/2 z-10 flex -translate-x-1/2 -translate-y-1/2 flex-col items-center focus:outline-none"
    >
      <span
        className="h-6 w-6 rounded-[var(--radius-full)] border-2 border-[var(--brand-500)] bg-[var(--bg-base)] shadow-[var(--shadow-sm)] group-focus-visible:ring-2 group-focus-visible:ring-[var(--brand-500)] group-focus-visible:ring-offset-2"
        aria-hidden="true"
      />
      <span className="pointer-events-none absolute top-full mt-1 whitespace-nowrap text-xs font-medium text-[var(--text-secondary)]">
        {koreanName}
      </span>
    </div>
  );
}
