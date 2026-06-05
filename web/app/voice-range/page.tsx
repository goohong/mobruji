"use client";

/**
 * 음역대 입력 페이지.
 *
 * docs/features/voice-range-input.md §3, §5-6 기반:
 *   - 사용자가 자신의 최저음/최고음을 노트 단위로 선택 (옥타브 분류 방식, Q1 결정).
 *   - 제출 시 POST /api/v1/voice-ranges 호출 후 sessionId/voiceRangeId 저장.
 *   - 성공 시 /recommend로 라우팅.
 *
 * 입력은 MIDI 노트 정수로 BE에 전달하고, 사용자에게는 한국어 음명(2옥도 등, 노래방 통념 옥타브 #1856)으로 보여준다.
 */

import { FormEvent, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useMutation, useQueryClient } from "@tanstack/react-query";

import {
  createVoiceRange,
  VoiceRangeResponse,
  VoiceRangeSourceMethod,
} from "@/lib/api/voice-range";
import { ApiError } from "@/lib/api/client";
import { useSessionStore } from "@/store/session";
import { Button, StepIndicator, VoiceRangeSlider } from "@/components/ui";
import { VoiceRangeIntuition } from "./components/VoiceRangeIntuition";

const DEFAULT_LOW_MIDI = 48; // C3
const DEFAULT_HIGH_MIDI = 69; // A4
const DEFAULT_SOURCE: VoiceRangeSourceMethod = "OCTAVE_PICK";

const SUBMIT_ERROR_ID = "voice-range-submit-error";

export default function VoiceRangePage() {
  const router = useRouter();
  const queryClient = useQueryClient();
  const ensureSessionId = useSessionStore((state) => state.ensureSessionId);
  const setVoiceRangeId = useSessionStore((state) => state.setVoiceRangeId);

  const [lowestNoteMidi, setLowestNoteMidi] = useState<number>(DEFAULT_LOW_MIDI);
  const [highestNoteMidi, setHighestNoteMidi] = useState<number>(
    DEFAULT_HIGH_MIDI,
  );

  const mutation = useMutation<
    VoiceRangeResponse,
    Error,
    {
      sessionId: string;
      lowestNoteMidi: number;
      highestNoteMidi: number;
      sourceMethod: VoiceRangeSourceMethod;
    }
  >({
    mutationFn: (variables) => createVoiceRange(variables),
    onSuccess: (data) => {
      setVoiceRangeId(data.id);
      // (closes #282) /recommend 진입 시 voice-range GET 왕복을 제거하기 위해
      // 방금 저장한 응답을 react-query 캐시에 prime 해 둔다. /recommend 의
      // useQuery(["voice-range", sessionId]) 가 즉시 캐시 히트 → 추천 mutation
      // 이 GET 왕복 없이 곧바로 발화한다.
      queryClient.setQueryData<VoiceRangeResponse>(
        ["voice-range", data.sessionId],
        data,
      );
      router.push("/recommend");
    },
  });

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const sessionId = ensureSessionId();
    mutation.mutate({
      sessionId,
      lowestNoteMidi,
      highestNoteMidi,
      sourceMethod: DEFAULT_SOURCE,
    });
  }

  const submitError = mutation.error
    ? mutation.error instanceof ApiError
      ? `${mutation.error.status}: ${mutation.error.message}`
      : mutation.error.message
    : null;

  /*
   * ADR-0018 단계 4 PR 3 — /voice-range 페이지 토큰 swap (homepage PR #1137 패턴).
   *
   * swap 한 요소 (first-paint 핵심):
   *  1) <main> 배경 + padding : bg-zinc-50 dark:bg-zinc-950, px-6 py-12 → tokens
   *  2) h1 / 부제 : text-zinc-900 dark:text-zinc-50, text-zinc-600 dark:text-zinc-400 → tokens
   *  3) auto / manual 카드 2개 : bg-white ring-zinc-200 dark:bg-zinc-900 dark:ring-zinc-800
   *     rounded-2xl shadow-sm → tokens (--bg-base / --border / --radius-lg / --shadow-sm)
   *  4) auto CTA Link : bg-zinc-900 ... dark:bg-zinc-50 → brand-500/600 + shadow-brand
   *  5) section heading h2 (각 1건) : text-zinc-900 dark:text-zinc-50 → --text-primary
   *
   * #1044 단계 4 PR 12 — NoteSelect 내부 잔여 zinc 일괄 swap:
   *  6) label <span> : text-zinc-700 dark:text-zinc-300 → --text-label (PR 10 토큰 재사용)
   *  7) <select> : border-zinc-300 / bg-white / text-zinc-900 / focus:border-zinc-500
   *     + dark:border-zinc-700 / dark:bg-zinc-950 / dark:text-zinc-50
   *     → --border-input / --bg-base / --text-primary / --border-input-focus
   *     (input PR 10 토큰 + --bg-base 재사용 — select dark bg-zinc-950 가 input
   *     dark zinc-900 과 다르므로 별도 토큰 대신 --bg-base 매핑이 정확)
   *
   * 미swap (후속 PR 양보):
   *  - <Button /> 컴포넌트 — 별도 컴포넌트라 본 페이지 범위 밖.
   *
   * 다크 모드: tokens.css `:where(html.dark)` selector 자동 swap. swap 한 element 에서
   * `dark:` prefix 제거.
   */
  return (
    <main className="flex flex-1 flex-col items-center bg-[var(--bg-subtle)] px-[var(--page-padding-x)] py-[var(--page-padding-y)]">
      <div className="w-full max-w-md flex flex-col gap-8">
        <header className="space-y-2">
          <StepIndicator current={1} total={2} />
          <h1 className="text-2xl font-semibold text-[var(--text-primary)]">
            내 음역대를 알려주세요
          </h1>
          <p className="text-sm text-[var(--text-secondary)]">
            부를 수 있는 가장 낮은 음과 가장 높은 음을 골라주세요. 잘 모르겠다면
            기본값(C3 ~ A4)으로 두고 진행해도 됩니다.
          </p>
        </header>

        <section
          aria-labelledby="voice-range-auto-cta-heading"
          className="flex flex-col gap-3 rounded-[var(--radius-lg)] bg-[var(--bg-base)] p-6 shadow-[var(--shadow-sm)] ring-1 ring-[var(--border)]"
        >
          <h2
            id="voice-range-auto-cta-heading"
            className="text-base font-semibold text-[var(--text-primary)]"
          >
            🎤 마이크로 자동 측정
          </h2>
          <p className="text-sm text-[var(--text-secondary)]">
            마이크에 직접 노래를 부르면 최저음/최고음을 자동으로 잡아드려요.
          </p>
          <Link
            href="/voice-range/auto"
            className="inline-flex h-12 w-full items-center justify-center rounded-full bg-[var(--brand-500)] px-6 text-base font-medium text-white transition-colors duration-[var(--duration-base)] hover:bg-[var(--brand-600)] hover:shadow-[var(--shadow-brand)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--brand-500)] focus-visible:ring-offset-2"
          >
            자동으로 측정하기
          </Link>
        </section>

        <section
          aria-labelledby="voice-range-manual-heading"
          className="flex flex-col gap-4"
        >
          <div className="space-y-1">
            <h2
              id="voice-range-manual-heading"
              className="text-base font-semibold text-[var(--text-primary)]"
            >
              직접 선택
            </h2>
            <p className="text-sm text-[var(--text-secondary)]">
              이미 음역대를 알고 있다면 아래에서 직접 골라주세요.
            </p>
          </div>

          <form
            onSubmit={handleSubmit}
            className="flex flex-col gap-6 rounded-[var(--radius-lg)] bg-[var(--bg-base)] p-6 shadow-[var(--shadow-sm)] ring-1 ring-[var(--border)]"
          >
            <VoiceRangeSlider
              lowMidi={lowestNoteMidi}
              highMidi={highestNoteMidi}
              onChange={({ lowMidi, highMidi }) => {
                setLowestNoteMidi(lowMidi);
                setHighestNoteMidi(highMidi);
              }}
            />

            <VoiceRangeIntuition
              lowMidi={lowestNoteMidi}
              highMidi={highestNoteMidi}
              caption="고른 음역대를 평균과 비교하면"
            />

            {/*
              (closes #464) submitError 영역에 role="alert" 를 부여해 SR 사용자도
              mutation 실패 등장을 즉시 announce 받게 한다. 시각 표시(붉은색)는 유지.
              최저음>최고음 crossover 는 슬라이더가 클램프로 원천 차단하므로 별도
              validation 메시지는 두지 않는다 (#1706).
            */}
            {submitError ? (
              <p
                id={SUBMIT_ERROR_ID}
                role="alert"
                className="text-sm text-[var(--danger-fg-soft)]"
              >
                저장에 실패했습니다. {submitError}
              </p>
            ) : null}

            <Button
              type="submit"
              variant="primary"
              size="lg"
              fullWidth
              loading={mutation.isPending}
            >
              {mutation.isPending ? "저장 중..." : "추천 받기"}
            </Button>
          </form>
        </section>
      </div>
    </main>
  );
}
