"use client";

/**
 * 음역대 입력 페이지.
 *
 * docs/features/voice-range-input.md §3, §5-6 기반:
 *   - 사용자가 자신의 최저음/최고음을 노트 단위로 선택 (옥타브 분류 방식, Q1 결정).
 *   - 제출 시 POST /api/v1/voice-ranges 호출 후 sessionId/voiceRangeId 저장.
 *   - 성공 시 /recommend로 라우팅.
 *
 * 입력은 MIDI 노트 정수로 BE에 전달하고, 사용자에게는 노트명(C4 등)으로 보여준다.
 */

import { FormEvent, useMemo, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useMutation, useQueryClient } from "@tanstack/react-query";

import {
  createVoiceRange,
  VoiceRangeResponse,
  VoiceRangeSourceMethod,
} from "@/lib/api/voice-range";
import { ApiError } from "@/lib/api/client";
import { midiToCombinedNoteName, octaveRangeMidis } from "@/lib/notes";
import { useSessionStore } from "@/store/session";
import { Button } from "@/components/ui";

const DEFAULT_LOW_MIDI = 48; // C3
const DEFAULT_HIGH_MIDI = 69; // A4
const DEFAULT_SOURCE: VoiceRangeSourceMethod = "OCTAVE_PICK";

// (closes #464) aria-describedby 로 select ↔ 에러 메시지를 연결할 때 사용.
// 페이지 단위로 유일하므로 const 로 충분 — 컴포넌트 인스턴스가 둘이 될 일 없다.
const VALIDATION_ERROR_ID = "voice-range-validation-error";
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

  const noteOptions = useMemo(() => octaveRangeMidis(), []);

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

  const validationError = useMemo(() => {
    if (lowestNoteMidi > highestNoteMidi) {
      return "최저음은 최고음보다 같거나 낮아야 합니다.";
    }
    return null;
  }, [lowestNoteMidi, highestNoteMidi]);

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (validationError) {
      return;
    }
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

  return (
    <main className="flex flex-1 flex-col items-center bg-zinc-50 px-6 py-12 dark:bg-zinc-950">
      <div className="w-full max-w-md flex flex-col gap-8">
        <header className="space-y-2">
          <p className="text-xs font-medium uppercase tracking-widest text-zinc-500 dark:text-zinc-400">
            Step 1
          </p>
          <h1 className="text-2xl font-semibold text-zinc-900 dark:text-zinc-50">
            내 음역대를 알려주세요
          </h1>
          <p className="text-sm text-zinc-600 dark:text-zinc-400">
            부를 수 있는 가장 낮은 음과 가장 높은 음을 골라주세요. 잘 모르겠다면
            기본값(C3 ~ A4)으로 두고 진행해도 됩니다.
          </p>
        </header>

        <section
          aria-labelledby="voice-range-auto-cta-heading"
          className="flex flex-col gap-3 rounded-2xl bg-white p-6 shadow-sm ring-1 ring-zinc-200 dark:bg-zinc-900 dark:ring-zinc-800"
        >
          <h2
            id="voice-range-auto-cta-heading"
            className="text-base font-semibold text-zinc-900 dark:text-zinc-50"
          >
            🎤 마이크로 자동 측정
          </h2>
          <p className="text-sm text-zinc-600 dark:text-zinc-400">
            마이크에 직접 노래를 부르면 최저음/최고음을 자동으로 잡아드려요.
          </p>
          <Link
            href="/voice-range/auto"
            className="inline-flex h-12 w-full items-center justify-center rounded-full bg-zinc-900 px-6 text-base font-medium text-white transition-colors hover:bg-zinc-700 dark:bg-zinc-50 dark:text-zinc-900 dark:hover:bg-zinc-200"
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
              className="text-base font-semibold text-zinc-900 dark:text-zinc-50"
            >
              직접 선택
            </h2>
            <p className="text-sm text-zinc-600 dark:text-zinc-400">
              이미 음역대를 알고 있다면 아래에서 직접 골라주세요.
            </p>
          </div>

          <form
            onSubmit={handleSubmit}
            className="flex flex-col gap-6 rounded-2xl bg-white p-6 shadow-sm ring-1 ring-zinc-200 dark:bg-zinc-900 dark:ring-zinc-800"
          >
            <NoteSelect
              label="최저음"
              value={lowestNoteMidi}
              options={noteOptions}
              onChange={setLowestNoteMidi}
              invalid={validationError !== null}
              describedBy={
                validationError !== null ? VALIDATION_ERROR_ID : undefined
              }
            />
            <NoteSelect
              label="최고음"
              value={highestNoteMidi}
              options={noteOptions}
              onChange={setHighestNoteMidi}
              invalid={validationError !== null}
              describedBy={
                validationError !== null ? VALIDATION_ERROR_ID : undefined
              }
            />

            {/*
              (closes #464) validationError / submitError 영역에 role="alert" 를 부여해
              SR 사용자도 에러 등장을 즉시 announce 받게 한다.
              - validationError: 최저음 > 최고음 선택 시 즉시 등장 (assertive 의미라
                role="alert" 가 적절). select 에는 aria-describedby 로 연결.
              - submitError: mutation 실패 시 등장. 동일하게 role="alert".
              시각 표시(붉은색)는 그대로 유지 — role 만 부여한다.
            */}
            {validationError ? (
              <p
                id={VALIDATION_ERROR_ID}
                role="alert"
                className="text-sm text-red-600 dark:text-red-400"
              >
                {validationError}
              </p>
            ) : null}

            {submitError ? (
              <p
                id={SUBMIT_ERROR_ID}
                role="alert"
                className="text-sm text-red-600 dark:text-red-400"
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
              disabled={validationError !== null}
            >
              {mutation.isPending ? "저장 중..." : "추천 받기"}
            </Button>
          </form>
        </section>
      </div>
    </main>
  );
}

type NoteSelectProps = {
  label: string;
  value: number;
  options: number[];
  onChange: (next: number) => void;
  /** 폼 validation 결과. true 면 select 에 aria-invalid="true" 를 부여한다. */
  invalid?: boolean;
  /** aria-describedby 로 연결할 에러 메시지 id (없으면 attribute 자체를 생략). */
  describedBy?: string;
};

function NoteSelect({
  label,
  value,
  options,
  onChange,
  invalid,
  describedBy,
}: NoteSelectProps) {
  return (
    <label className="flex flex-col gap-2 text-sm">
      <span className="font-medium text-zinc-700 dark:text-zinc-300">
        {label}
      </span>
      <select
        value={value}
        onChange={(event) => onChange(Number(event.target.value))}
        // (closes #464) validation 에러 시 aria-invalid + aria-describedby 부여로
        // SR 사용자가 잘못된 필드와 사유를 함께 인지하도록 한다. describedBy 가
        // undefined 일 때는 속성 자체를 생략한다 (빈 문자열 ≠ 미지정).
        aria-invalid={invalid ? true : undefined}
        aria-describedby={describedBy}
        className="h-11 rounded-lg border border-zinc-300 bg-white px-3 text-base text-zinc-900 focus:border-zinc-500 focus:outline-none dark:border-zinc-700 dark:bg-zinc-950 dark:text-zinc-50"
      >
        {options.map((midi) => (
          <option key={midi} value={midi}>
            {midiToCombinedNoteName(midi)} · MIDI {midi}
          </option>
        ))}
      </select>
    </label>
  );
}
