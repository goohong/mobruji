/**
 * 디자인 시스템 Button 컴포넌트 (closes #195).
 *
 * 페이지마다 같은 모양의 Tailwind 유틸 클래스가 반복되는 것을 한곳으로 모은다.
 *
 * variant:
 *   - primary: 가장 강한 강조 (brand-500, hover brand-600, active brand-700).
 *   - secondary: ring 기반 보조 액션.
 *   - ghost: 배경 없음, 텍스트만. 다이얼로그 닫기/취소 같은 약한 액션.
 *   - danger: 파괴적 액션. danger-500 톤 (hover 600, active 700).
 *
 * size: sm/md/lg — 높이와 패딩만 바뀐다 (`h-8`/`h-10`/`h-12`).
 *
 * loading:
 *   - `loading=true`면 클릭 비활성 + `aria-busy=true` + 로딩 스피너.
 *   - 외부에서 `disabled`를 별도로 지정하면 그대로 존중한다 (`loading || disabled`).
 *
 * a11y:
 *   - 항상 `type` prop을 받아 form 의도와 분리. 기본은 `button`.
 *   - aria-label은 children이 텍스트가 아닐 때 호출자가 명시.
 *
 * 동작 변경 없음 — 기존 페이지의 클래스 묶음을 그대로 옮긴 pure refactor.
 */

"use client";

import { forwardRef, type ButtonHTMLAttributes, type ReactNode } from "react";

export type ButtonVariant = "primary" | "secondary" | "ghost" | "danger";
export type ButtonSize = "sm" | "md" | "lg";

type ButtonProps = Omit<ButtonHTMLAttributes<HTMLButtonElement>, "type"> & {
  variant?: ButtonVariant;
  size?: ButtonSize;
  loading?: boolean;
  fullWidth?: boolean;
  type?: "button" | "submit" | "reset";
  children?: ReactNode;
};

const VARIANT_CLASSES: Record<ButtonVariant, string> = {
  primary:
    "bg-[var(--brand-500)] text-white hover:bg-[var(--brand-600)] active:bg-[var(--brand-700)] disabled:cursor-not-allowed disabled:opacity-50",
  secondary:
    "bg-[var(--cta-secondary-bg)] text-[var(--text-primary)] ring-1 ring-[var(--surface-card-ring)] hover:bg-[var(--cta-secondary-bg-hover)] disabled:cursor-not-allowed disabled:opacity-50",
  ghost:
    "bg-transparent text-[var(--text-body-strong)] hover:bg-[var(--cta-secondary-bg-hover)] disabled:cursor-not-allowed disabled:opacity-50",
  danger:
    "bg-[var(--danger-500)] text-white hover:bg-[var(--danger-600)] active:bg-[var(--danger-700)] disabled:cursor-not-allowed disabled:opacity-50",
};

const SIZE_CLASSES: Record<ButtonSize, string> = {
  sm: "h-8 px-3 text-xs",
  md: "h-10 px-4 text-sm",
  lg: "h-12 px-6 text-base",
};

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  {
    variant = "primary",
    size = "md",
    loading = false,
    fullWidth = false,
    type = "button",
    disabled,
    className,
    children,
    ...rest
  },
  ref,
) {
  const isDisabled = disabled || loading;
  // ADR-0018 단계 4 fix #1310 — focus ring 토큰 swap.
  // 기존 `focus-visible:ring-zinc-500` 는 light 전용 hardcode 로 다크 모드에서도
  // light 회색 ring 이 그대로 렌더되어 시각적 일관성이 깨졌다. 다른 컴포넌트
  // (ThemeToggle / BottomNav) 와 동일 패턴으로 `--cta-secondary-ring` 토큰으로
  // 통일한다 (tokens.css 가 light=zinc-500 / dark=zinc-500 균일 outline 으로
  // 정의해 OS 다크 환경에서도 가시성 유지).
  // 마이크로 인터랙션(#1681, ui-ux PR 2): press 시 눌리는 scale-95 피드백 +
  // spring easing(--ease-spring) + duration-base(200ms). transform/color 둘 다
  // transition 되도록 `transition` (all) 을 유지한다. disabled/loading 상태에선
  // press scale 을 끈다(눌림 착시 방지). prefers-reduced-motion 사용자는
  // tokens.css §6 전역 가드가 duration 을 10ms 로 축소하고, 추가로
  // `motion-reduce:active:scale-100` 으로 press scale 자체를 비활성화한다.
  const base =
    "inline-flex items-center justify-center gap-2 rounded-full font-medium transition duration-[var(--duration-base)] ease-[var(--ease-spring)] focus:outline-none focus-visible:ring-2 focus-visible:ring-[var(--cta-secondary-ring)] active:scale-95 disabled:active:scale-100 motion-reduce:active:scale-100";
  const widthClass = fullWidth ? "w-full" : "";
  const merged = [
    base,
    SIZE_CLASSES[size],
    VARIANT_CLASSES[variant],
    widthClass,
    className ?? "",
  ]
    .filter((value) => value.length > 0)
    .join(" ");

  return (
    <button
      ref={ref}
      type={type}
      disabled={isDisabled}
      aria-busy={loading || undefined}
      className={merged}
      {...rest}
    >
      {loading ? <Spinner /> : null}
      {children}
    </button>
  );
});

function Spinner() {
  return (
    <span
      aria-hidden="true"
      className="inline-block h-3.5 w-3.5 animate-spin rounded-full border-2 border-current border-r-transparent"
    />
  );
}
