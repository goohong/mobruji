"use client";

/**
 * Next.js App Router 전역 에러 boundary.
 *
 * - 추천/입력 페이지에서 throw된 에러를 잡아 친절한 한국어 안내로 대체한다.
 * - `reset()`은 같은 라우트를 다시 마운트해 retry. 추가로 홈으로 돌아가는 보조 동선 제공.
 * - Sentry 등 외부 보고는 본 PR 범위 외 — `useEffect`로 콘솔 로깅만.
 *
 * 참고: app/error.tsx는 자식 라우트에서 던진 에러를 잡는다. 루트 layout 자체의 에러는
 * 별도 global-error.tsx가 필요하지만 현재는 단일 boundary로 충분.
 */

import { useEffect } from "react";
import Link from "next/link";

import { safeLog } from "@/lib/logging";

type ErrorPageProps = {
  error: Error & { digest?: string };
  reset: () => void;
};

export default function ErrorPage({ error, reset }: ErrorPageProps) {
  useEffect(() => {
    // 운영에서는 외부 로거로 보낼 자리. 본 단계에서는 콘솔로만 남긴다.
    // digest는 서버 사이드 에러 추적용 식별자.
    // safeLog 가 PII 마스킹 + Error 평탄화를 처리한다 (PR #129).
    safeLog.error("[recommend] unhandled error", error);
  }, [error]);

  return (
    <main className="flex flex-1 flex-col items-center justify-center bg-[var(--bg-subtle)] px-6 py-12 text-center">
      <div className="w-full max-w-md flex flex-col items-center gap-4">
        <h1 className="text-xl font-semibold text-[var(--text-primary)]">
          문제가 발생했습니다
        </h1>
        <p className="text-sm text-[var(--text-secondary)]">
          잠시 후 다시 시도해 주세요. 문제가 계속되면 음역대 입력부터 다시
          진행하면 도움이 됩니다.
        </p>
        {error.digest ? (
          <p
            aria-label="에러 식별자"
            className="font-mono text-xs text-[var(--text-disclaimer)]"
          >
            ref: {error.digest}
          </p>
        ) : null}
        <div className="flex flex-wrap items-center justify-center gap-3 pt-2">
          <button
            type="button"
            onClick={reset}
            className="inline-flex h-11 items-center justify-center rounded-full bg-[var(--cta-neutral-bg)] px-5 text-sm font-medium text-[var(--cta-neutral-fg)] hover:bg-[var(--cta-neutral-bg-hover)]"
          >
            다시 시도
          </button>
          <Link
            href="/"
            className="inline-flex h-11 items-center justify-center rounded-full border border-[var(--cta-secondary-border)] px-5 text-sm font-medium text-[var(--cta-secondary-fg)] hover:bg-[var(--cta-secondary-bg-hover)]"
          >
            홈으로
          </Link>
        </div>
      </div>
    </main>
  );
}
