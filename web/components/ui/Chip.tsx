/**
 * 디자인 시스템 Chip 컴포넌트 (closes #195).
 *
 * SongCard에 inline으로 있던 장르 chip과 `/songs` 페이지의 FilterChip을 한 컴포넌트로 통합.
 *
 * 두 가지 형태:
 *   - `<Chip>` — `<span>` 정적 chip (장르 라벨 같은 비대화형 표식).
 *   - `<Chip onClick>` 또는 `<Chip pressed>` — `<button>`으로 자동 전환되며 `aria-pressed`를 노출.
 *
 * tone:
 *   - neutral — 기본 zinc 톤.
 *   - primary — pressed/active 강조 톤 (검은 배경).
 *   - success/warning/danger — difficulty 라벨 같은 의미 톤 (호출자가 선택).
 */

"use client";

import {
  forwardRef,
  type ButtonHTMLAttributes,
  type HTMLAttributes,
  type ReactNode,
} from "react";

export type ChipTone = "neutral" | "primary" | "success" | "warning" | "danger";

type CommonChipProps = {
  tone?: ChipTone;
  /** 버튼 모드에서 active/pressed 상태. */
  pressed?: boolean;
  children?: ReactNode;
  className?: string;
};

type SpanChipProps = CommonChipProps &
  Omit<HTMLAttributes<HTMLSpanElement>, "className" | "children"> & {
    onClick?: never;
  };

type ButtonChipProps = CommonChipProps &
  Omit<
    ButtonHTMLAttributes<HTMLButtonElement>,
    "className" | "children" | "type"
  > & {
    onClick: ButtonHTMLAttributes<HTMLButtonElement>["onClick"];
  };

type ChipProps = SpanChipProps | ButtonChipProps;

const TONE_STATIC: Record<ChipTone, string> = {
  neutral:
    "bg-[var(--badge-neutral-bg)] text-[var(--badge-neutral-fg)]",
  primary:
    "bg-[var(--cta-neutral-bg)] text-[var(--cta-neutral-fg)]",
  success:
    "bg-emerald-100 text-emerald-700 dark:bg-emerald-950 dark:text-emerald-300",
  warning:
    "bg-amber-100 text-amber-700 dark:bg-amber-950 dark:text-amber-300",
  danger:
    "bg-rose-100 text-rose-700 dark:bg-rose-950 dark:text-rose-300",
};

const TONE_BUTTON_INACTIVE =
  "bg-[var(--cta-secondary-bg)] text-[var(--badge-neutral-fg)] ring-1 ring-[var(--surface-card-ring)] hover:bg-[var(--cta-secondary-bg-hover)]";

export const Chip = forwardRef<HTMLElement, ChipProps>(function Chip(
  props,
  ref,
) {
  const { tone = "neutral", pressed, children, className } = props;
  // 버튼 모드 chip 도 press 피드백(#1493). 정적 span chip 은 비대화형이라 제외.
  const base =
    "inline-flex h-8 items-center rounded-full px-3 text-xs font-medium transition active:scale-[0.96]";

  // 버튼 모드: onClick이 있거나 pressed가 명시되면 button으로 렌더.
  if ("onClick" in props && props.onClick !== undefined) {
    const { onClick, ...rest } = props as ButtonChipProps;
    const buttonRest = rest as Omit<
      ButtonChipProps,
      "tone" | "pressed" | "children" | "className" | "onClick"
    >;
    const active = pressed === true;
    const stateClass = active ? TONE_STATIC[tone === "neutral" ? "primary" : tone] : TONE_BUTTON_INACTIVE;
    const merged = [base, stateClass, className ?? ""]
      .filter((v) => v.length > 0)
      .join(" ");
    return (
      <button
        ref={ref as React.Ref<HTMLButtonElement>}
        type="button"
        aria-pressed={pressed}
        onClick={onClick}
        className={merged}
        {...buttonRest}
      >
        {children}
      </button>
    );
  }

  // 정적 span 모드.
  const staticBase =
    "inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium";
  const merged = [staticBase, TONE_STATIC[tone], className ?? ""]
    .filter((v) => v.length > 0)
    .join(" ");
  const { tone: _tone, pressed: _pressed, children: _ch, className: _cn, ...rest } =
    props as SpanChipProps & { tone?: ChipTone; pressed?: boolean };
  void _tone;
  void _pressed;
  void _ch;
  void _cn;
  return (
    <span ref={ref as React.Ref<HTMLSpanElement>} className={merged} {...rest}>
      {children}
    </span>
  );
});
