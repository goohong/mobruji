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
    "h-12 w-full rounded-2xl border bg-white px-4 text-base text-zinc-900 placeholder:text-zinc-400 focus:outline-none focus:ring-2 dark:bg-zinc-900 dark:text-zinc-50 dark:placeholder:text-zinc-500";
  const stateClasses = error
    ? "border-red-400 focus:border-red-500 focus:ring-red-200 dark:border-red-700 dark:focus:border-red-500 dark:focus:ring-red-900"
    : "border-zinc-200 focus:border-zinc-400 focus:ring-zinc-300 dark:border-zinc-800 dark:focus:border-zinc-600 dark:focus:ring-zinc-700";
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
              : "text-sm font-medium text-zinc-700 dark:text-zinc-300"
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
          className="text-sm text-red-600 dark:text-red-400"
        >
          {error}
        </p>
      ) : null}
    </div>
  );
});
