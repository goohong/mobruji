/**
 * 디자인 시스템 Input 컴포넌트 (closes #195).
 *
 * 페이지마다 다른 클래스로 작성되던 `<input>`을 한 패턴으로 모은다.
 *
 * 기능:
 *   - 옵셔널 `label` — 명시되면 `<label htmlFor>`로 연결, sr-only 옵션 지원.
 *   - 옵셔널 `error` — 텍스트 메시지. 표시되면 `aria-invalid="true"`, `aria-describedby`로 연결.
 *   - 옵셔널 `description` — 보조 설명 텍스트. `aria-describedby`로 연결.
 *   - id가 없으면 useId로 자동 생성.
 *
 * 동작 변경 없음 — 기존 `<input>` 스타일을 표준화만 한다.
 */

"use client";

import { forwardRef, useId, type InputHTMLAttributes, type ReactNode } from "react";

type InputProps = InputHTMLAttributes<HTMLInputElement> & {
  label?: ReactNode;
  /** label을 시각적으로 숨기고 스크린리더에만 노출. */
  labelHidden?: boolean;
  description?: ReactNode;
  error?: ReactNode;
};

export const Input = forwardRef<HTMLInputElement, InputProps>(function Input(
  {
    id,
    label,
    labelHidden = false,
    description,
    error,
    className,
    ...rest
  },
  ref,
) {
  const autoId = useId();
  const inputId = id ?? autoId;
  const descId = description ? `${inputId}-desc` : undefined;
  const errorId = error ? `${inputId}-err` : undefined;
  const describedBy = [descId, errorId].filter(Boolean).join(" ") || undefined;

  const baseClasses =
    "h-12 w-full rounded-2xl border bg-[var(--surface-input)] px-4 text-base text-[var(--text-primary)] placeholder:text-[var(--text-placeholder)] focus:outline-none focus:ring-2";
  const stateClasses = error
    ? "border-[var(--danger-border)] focus:border-[var(--danger-500)] focus:ring-[var(--danger-border)]"
    : "border-[var(--border-input)] focus:border-[var(--border-input-focus)] focus:ring-[var(--ring-input-focus)]";
  const merged = [baseClasses, stateClasses, className ?? ""]
    .filter((v) => v.length > 0)
    .join(" ");

  return (
    <div className="flex flex-col gap-1.5">
      {label !== undefined ? (
        <label
          htmlFor={inputId}
          className={
            labelHidden
              ? "sr-only"
              : "text-sm font-medium text-[var(--text-label)]"
          }
        >
          {label}
        </label>
      ) : null}
      <input
        ref={ref}
        id={inputId}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy}
        className={merged}
        {...rest}
      />
      {description ? (
        <p
          id={descId}
          className="text-xs text-[var(--text-caption)]"
        >
          {description}
        </p>
      ) : null}
      {error ? (
        <p
          id={errorId}
          className="text-sm text-[var(--danger-fg-soft)]"
        >
          {error}
        </p>
      ) : null}
    </div>
  );
});
