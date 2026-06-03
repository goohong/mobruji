"use client";

/**
 * 음역대 측정 방식 선택 화면 — `/voice-range/method`.
 *
 * 진입 시 곧바로 마이크 측정을 강제하던 흐름을, 사용자가 측정 방식을 직접 고르는
 * 분기 화면으로 바꾼다 (directive #1511). 두 경로를 동등한 카드로 노출한다:
 *   - 자동(마이크) 측정 → `/voice-range/auto` (기존 측정 wizard).
 *   - 직접 입력        → `/voice-range`     (기존 수동 입력 폼).
 *
 * 마이크 권한/측정은 사용자가 '자동' 을 고른 뒤 측정 화면의 "측정 시작" user-gesture
 * 에서만 요청된다. 이 화면 진입만으로는 `getUserMedia` 를 호출하지 않는다.
 */

import Link from "next/link";

type MethodCard = {
  href: string;
  emoji: string;
  title: string;
  description: string;
};

const METHOD_CARDS: readonly MethodCard[] = [
  {
    href: "/voice-range/auto",
    emoji: "🎤",
    title: "자동(마이크) 측정",
    description:
      "마이크에 직접 노래를 부르면 최저음·최고음을 자동으로 잡아드려요. 약 1분.",
  },
  {
    href: "/voice-range",
    emoji: "✍️",
    title: "직접 입력",
    description:
      "이미 음역대를 알고 있다면 최저음·최고음을 직접 골라주세요. 마이크 없이 진행.",
  },
];

export default function VoiceRangeMethodPage() {
  return (
    <main className="flex flex-1 flex-col items-center bg-[var(--bg-subtle)] px-[var(--page-padding-x)] py-[var(--page-padding-y)]">
      <div className="w-full max-w-md flex flex-col gap-8">
        <header className="space-y-2">
          <p className="text-xs font-medium uppercase tracking-widest text-[var(--text-caption)]">
            Step 1
          </p>
          <h1 className="text-2xl font-semibold text-[var(--text-primary)]">
            음역대를 어떻게 알려주실래요?
          </h1>
          <p className="text-sm text-[var(--text-secondary)]">
            측정 방식을 직접 골라주세요. 마이크는 자동 측정을 선택한 뒤에만 켜져요 —
            이 화면에서는 권한을 요청하지 않습니다.
          </p>
        </header>

        <ul
          aria-label="측정 방식 선택"
          className="flex flex-col gap-3"
        >
          {METHOD_CARDS.map((card) => (
            <li key={card.href}>
              <Link
                href={card.href}
                className="flex items-start gap-3 rounded-[var(--radius-lg)] bg-[var(--bg-base)] p-5 shadow-[var(--shadow-sm)] ring-1 ring-[var(--border)] transition-colors hover:bg-[var(--cta-secondary-bg-hover)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--brand-500)] focus-visible:ring-offset-2"
              >
                <span aria-hidden="true" className="text-2xl leading-none">
                  {card.emoji}
                </span>
                <span className="flex flex-col gap-1">
                  <span className="text-base font-semibold text-[var(--text-primary)]">
                    {card.title}
                  </span>
                  <span className="text-sm text-[var(--text-secondary)]">
                    {card.description}
                  </span>
                </span>
              </Link>
            </li>
          ))}
        </ul>
      </div>
    </main>
  );
}
