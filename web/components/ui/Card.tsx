/**
 * 디자인 시스템 Card 컴포넌트 (closes #195).
 *
 * 페이지에서 반복되던 카드 표면(둥근 모서리 + 배경 + ring) 스타일 묶음을 한곳에 모은다.
 *
 * 구조: `<Card>` 기본 + 옵셔널 서브 컴포넌트(`Card.Header`, `Card.Body`, `Card.Footer`).
 * 서브 컴포넌트 없이 children을 직접 넣어도 그대로 사용 가능 — 기존 마크업과 호환.
 *
 * polymorphic `as` prop으로 `li`/`section` 같은 시맨틱 태그로 바꿀 수 있다.
 *
 * 동작 변경 없음 — 시각적으로 기존 카드와 동일하다.
 */

"use client";

import {
  forwardRef,
  type ElementType,
  type HTMLAttributes,
  type ReactNode,
} from "react";

type CardProps = HTMLAttributes<HTMLElement> & {
  as?: ElementType;
  /** ring/shadow 강조를 끄고 싶을 때 (예: matchReason 펼침 패널). */
  flush?: boolean;
  children?: ReactNode;
};

export const Card = forwardRef<HTMLElement, CardProps>(function Card(
  { as, flush = false, className, children, ...rest },
  ref,
) {
  const Tag = (as ?? "div") as ElementType;
  const base = flush
    ? "rounded-2xl bg-[var(--surface-card-bg)]"
    : "rounded-2xl bg-[var(--surface-card-bg)] ring-1 ring-[var(--surface-card-ring)]";
  const merged = [base, className ?? ""].filter((v) => v.length > 0).join(" ");
  return (
    <Tag ref={ref} className={merged} {...rest}>
      {children}
    </Tag>
  );
});

type CardSectionProps = HTMLAttributes<HTMLDivElement> & {
  children?: ReactNode;
};

export function CardHeader({ className, children, ...rest }: CardSectionProps) {
  const merged = [
    "flex flex-col gap-1 border-b border-[var(--border-subtle)] px-4 py-3",
    className ?? "",
  ]
    .filter((v) => v.length > 0)
    .join(" ");
  return (
    <div className={merged} {...rest}>
      {children}
    </div>
  );
}

export function CardBody({ className, children, ...rest }: CardSectionProps) {
  const merged = ["flex flex-col gap-3 px-4 py-4", className ?? ""]
    .filter((v) => v.length > 0)
    .join(" ");
  return (
    <div className={merged} {...rest}>
      {children}
    </div>
  );
}

export function CardFooter({ className, children, ...rest }: CardSectionProps) {
  const merged = [
    "flex flex-col gap-2 border-t border-[var(--border-subtle)] px-4 py-3",
    className ?? "",
  ]
    .filter((v) => v.length > 0)
    .join(" ");
  return (
    <div className={merged} {...rest}>
      {children}
    </div>
  );
}
