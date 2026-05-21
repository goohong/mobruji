"use client";

/**
 * 음역대 자동 측정 페이지 — `/voice-range/auto`.
 *
 * docs/features/voice-range-auto-measurement.md §6 PR C 기반.
 *
 * Wizard 단계:
 *   1. PERMISSION  — 마이크 권한 안내 + "측정 시작" 버튼 (iOS Safari §8 Q5: user-gesture에서 getUserMedia + AudioContext)
 *   2. MEASURE_LOW — "가장 낮은 음을 5초간 발성" + 실시간 pitch + 카운트다운
 *   3. MEASURE_HIGH— "가장 높은 음을 5초간 발성" + 실시간 pitch + 카운트다운
 *   4. RESULT      — lowMidi/highMidi + 음표명 표시 + 수동 보정 슬라이더 + 저장 버튼
 *   5. 저장        — POST /api/v1/voice-ranges → /recommend 라우팅
 *
 * 권한 거부 fallback: `/voice-range` (수동 입력) 으로 redirect + 안내 메시지.
 * Privacy: audio 스트림은 브라우저 메모리 안에서만 다루고 서버에 전송하지 않는다.
 * 서버에는 정수 lowestNoteMidi/highestNoteMidi 만 전송한다.
 */

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { useMutation } from "@tanstack/react-query";

import {
  createVoiceRange,
  VoiceRangeResponse,
} from "@/lib/api/voice-range";
import { ApiError } from "@/lib/api/client";
import { midiToNoteName, MIN_MIDI, MAX_MIDI } from "@/lib/notes";
import { useSessionStore } from "@/store/session";
import { safeLog } from "@/lib/logging";
import { Button } from "@/components/ui";
import {
  MEASUREMENT_DURATION_MS,
  MeasurementPhase,
  MeasurementResult,
  PitchSample,
  createReadFrameFromAnalyser,
  openMicAnalyser,
  runMeasurementSession,
} from "@/lib/audio/sampler";

type WizardStep =
  | "PERMISSION"
  | "MEASURE_LOW"
  | "MEASURE_HIGH"
  | "RESULT";

/**
 * 측정 의존성 주입 — 테스트에서 Web Audio API 호출 없이 흐름만 검증하기 위함.
 * 운영 코드는 `defaultAutoMeasureDeps`를 사용한다.
 */
export interface AutoMeasureDeps {
  requestMic: () => Promise<MediaStream>;
  runPhase: (
    phase: MeasurementPhase,
    stream: MediaStream,
    onSample: (sample: PitchSample) => void,
  ) => Promise<MeasurementResult>;
}

const defaultDeps: AutoMeasureDeps = {
  requestMic: () =>
    navigator.mediaDevices.getUserMedia({ audio: true, video: false }),
  runPhase: async (phase, stream, onSample) => {
    const analyser = await openMicAnalyser(stream);
    try {
      return await runMeasurementSession({
        phase,
        onSample,
        readFrame: createReadFrameFromAnalyser(analyser),
      });
    } finally {
      await analyser.close();
    }
  },
};

interface AutoVoiceRangePageProps {
  /** 테스트 전용. 운영에서는 기본값 사용. */
  deps?: AutoMeasureDeps;
}

export default function AutoVoiceRangePage({
  deps = defaultDeps,
}: AutoVoiceRangePageProps = {}) {
  const router = useRouter();
  const ensureSessionId = useSessionStore((state) => state.ensureSessionId);
  const setVoiceRangeId = useSessionStore((state) => state.setVoiceRangeId);

  const [step, setStep] = useState<WizardStep>("PERMISSION");
  const [permissionError, setPermissionError] = useState<string | null>(null);
  const [currentSample, setCurrentSample] = useState<PitchSample | null>(null);
  const [elapsedMs, setElapsedMs] = useState<number>(0);
  const [lowResult, setLowResult] = useState<MeasurementResult | null>(null);
  const [highResult, setHighResult] = useState<MeasurementResult | null>(null);
  const [lowMidi, setLowMidi] = useState<number>(48);
  const [highMidi, setHighMidi] = useState<number>(69);
  const streamRef = useRef<MediaStream | null>(null);

  // 컴포넌트 언마운트 시 마이크 stream 정리(privacy / 권한 빨간 점 제거).
  useEffect(() => {
    return () => {
      streamRef.current?.getTracks().forEach((track) => track.stop());
      streamRef.current = null;
    };
  }, []);

  const handleStart = useCallback(async () => {
    setPermissionError(null);
    try {
      // iOS Safari §8 Q5(a): user-gesture(클릭) 안에서 getUserMedia 호출.
      const stream = await deps.requestMic();
      streamRef.current = stream;
      setStep("MEASURE_LOW");
      // 측정 phase 1 (low) 시작.
      const lowSamples: PitchSample[] = [];
      setCurrentSample(null);
      setElapsedMs(0);
      const lowOutcome = await deps.runPhase("low", stream, (sample) => {
        lowSamples.push(sample);
        setCurrentSample(sample);
        setElapsedMs(sample.elapsedMs);
      });
      setLowResult(lowOutcome);

      // 측정 phase 2 (high).
      setStep("MEASURE_HIGH");
      setCurrentSample(null);
      setElapsedMs(0);
      const highOutcome = await deps.runPhase("high", stream, (sample) => {
        setCurrentSample(sample);
        setElapsedMs(sample.elapsedMs);
      });
      setHighResult(highOutcome);

      // 결과 화면 진입 — 측정 MIDI를 슬라이더 기본값으로 미리 채운다.
      if (lowOutcome.midi !== null) {
        setLowMidi(clampMidi(lowOutcome.midi));
      }
      if (highOutcome.midi !== null) {
        setHighMidi(clampMidi(highOutcome.midi));
      }
      setStep("RESULT");

      // 측정 완료 후 마이크 stream을 즉시 stop — privacy/배터리.
      stream.getTracks().forEach((track) => track.stop());
      streamRef.current = null;
    } catch (error) {
      // 권한 거부 / 장치 미지원 → fallback 안내 후 수동 입력 페이지로.
      safeLog.error("[voice-range/auto] mic permission/measurement failed", error);
      setPermissionError(
        error instanceof Error && error.name === "NotAllowedError"
          ? "마이크 권한이 거부되었습니다. 수동 입력으로 이동합니다."
          : "마이크를 사용할 수 없습니다. 수동 입력으로 이동합니다.",
      );
      // 1초 안에 fallback (spec §3 비기능).
      setTimeout(() => router.push("/voice-range"), 1200);
    }
  }, [deps, router]);

  const mutation = useMutation<
    VoiceRangeResponse,
    Error,
    { sessionId: string; lowestNoteMidi: number; highestNoteMidi: number }
  >({
    mutationFn: (variables) =>
      createVoiceRange({
        sessionId: variables.sessionId,
        lowestNoteMidi: variables.lowestNoteMidi,
        highestNoteMidi: variables.highestNoteMidi,
        sourceMethod: "MIC_MEASURE",
      }),
    onSuccess: (data) => {
      setVoiceRangeId(data.id);
      router.push("/recommend");
    },
  });

  const handleSave = useCallback(() => {
    if (lowMidi > highMidi) {
      return;
    }
    const sessionId = ensureSessionId();
    mutation.mutate({ sessionId, lowestNoteMidi: lowMidi, highestNoteMidi: highMidi });
  }, [ensureSessionId, highMidi, lowMidi, mutation]);

  const submitError = mutation.error
    ? mutation.error instanceof ApiError
      ? `${mutation.error.status}: ${mutation.error.message}`
      : mutation.error.message
    : null;

  const validationError = useMemo(() => {
    if (lowMidi > highMidi) {
      return "최저음은 최고음보다 같거나 낮아야 합니다.";
    }
    return null;
  }, [lowMidi, highMidi]);

  return (
    <main className="flex flex-1 flex-col items-center bg-zinc-50 px-6 py-12 dark:bg-zinc-950">
      <div className="w-full max-w-md flex flex-col gap-8">
        <header className="space-y-2">
          <p className="text-xs font-medium uppercase tracking-widest text-zinc-500 dark:text-zinc-400">
            자동 측정
          </p>
          <h1 className="text-2xl font-semibold text-zinc-900 dark:text-zinc-50">
            마이크로 음역대 측정하기
          </h1>
          <p className="text-sm text-zinc-600 dark:text-zinc-400">
            마이크 권한이 필요해요. 음역 측정에만 사용하고 audio 데이터는 서버로
            업로드하지 않습니다 (브라우저 내부에서만 처리).
          </p>
        </header>

        <section
          aria-live="polite"
          className="flex flex-col gap-6 rounded-2xl bg-white p-6 shadow-sm ring-1 ring-zinc-200 dark:bg-zinc-900 dark:ring-zinc-800"
        >
          {step === "PERMISSION" ? (
            <PermissionStep
              onStart={handleStart}
              permissionError={permissionError}
            />
          ) : null}
          {step === "MEASURE_LOW" || step === "MEASURE_HIGH" ? (
            <MeasureStep
              phase={step === "MEASURE_LOW" ? "low" : "high"}
              sample={currentSample}
              elapsedMs={elapsedMs}
            />
          ) : null}
          {step === "RESULT" ? (
            <ResultStep
              lowResult={lowResult}
              highResult={highResult}
              lowMidi={lowMidi}
              highMidi={highMidi}
              onLowChange={setLowMidi}
              onHighChange={setHighMidi}
              onSave={handleSave}
              saving={mutation.isPending}
              validationError={validationError}
              submitError={submitError}
            />
          ) : null}
        </section>
      </div>
    </main>
  );
}

function clampMidi(value: number): number {
  return Math.max(MIN_MIDI, Math.min(MAX_MIDI, Math.round(value)));
}

interface PermissionStepProps {
  onStart: () => void;
  permissionError: string | null;
}

function PermissionStep({ onStart, permissionError }: PermissionStepProps) {
  return (
    <div className="flex flex-col gap-4">
      <p className="text-sm text-zinc-700 dark:text-zinc-300">
        준비되면 아래 버튼을 눌러 측정을 시작하세요. 낮은 음 5초 → 높은 음 5초
        순으로 진행됩니다.
      </p>
      <Button
        variant="primary"
        size="lg"
        fullWidth
        onClick={onStart}
      >
        측정 시작
      </Button>
      {permissionError ? (
        <p
          role="alert"
          className="text-sm text-red-600 dark:text-red-400"
        >
          {permissionError}
        </p>
      ) : null}
    </div>
  );
}

interface MeasureStepProps {
  phase: MeasurementPhase;
  sample: PitchSample | null;
  elapsedMs: number;
}

function MeasureStep({ phase, sample, elapsedMs }: MeasureStepProps) {
  const phaseLabel = phase === "low" ? "가장 낮은 음" : "가장 높은 음";
  const remainingMs = Math.max(0, MEASUREMENT_DURATION_MS - elapsedMs);
  const remainingSec = Math.ceil(remainingMs / 1000);

  return (
    <div className="flex flex-col gap-4">
      <h2 className="text-lg font-semibold text-zinc-900 dark:text-zinc-50">
        {phaseLabel}을 5초간 발성해주세요
      </h2>
      <p className="text-sm text-zinc-600 dark:text-zinc-400">
        편한 모음(예: &quot;아&quot;) 으로 길게 내주세요.
      </p>
      <div
        className="flex flex-col gap-2 rounded-lg border border-zinc-200 p-4 dark:border-zinc-700"
        aria-live="polite"
      >
        <div className="flex items-center justify-between">
          <span className="text-xs uppercase tracking-widest text-zinc-500">
            현재 음
          </span>
          <span
            data-testid="stability-badge"
            className={
              sample?.isStable
                ? "rounded-full bg-emerald-100 px-2 py-0.5 text-xs font-medium text-emerald-700 dark:bg-emerald-900/50 dark:text-emerald-200"
                : "rounded-full bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-700 dark:bg-amber-900/50 dark:text-amber-200"
            }
          >
            {sample?.isStable ? "안정" : "불안정"}
          </span>
        </div>
        <div className="flex items-baseline justify-between">
          <span className="text-2xl font-semibold tabular-nums">
            {sample?.midi !== null && sample?.midi !== undefined
              ? midiToNoteName(sample.midi)
              : "—"}
          </span>
          <span className="text-sm text-zinc-500">
            {sample && sample.frequencyHz > 0
              ? `${sample.frequencyHz.toFixed(1)} Hz`
              : "발성 대기"}
          </span>
        </div>
      </div>
      <p
        className="text-center text-sm text-zinc-600 dark:text-zinc-400"
        aria-live="polite"
      >
        남은 시간 {remainingSec}초
      </p>
    </div>
  );
}

interface ResultStepProps {
  lowResult: MeasurementResult | null;
  highResult: MeasurementResult | null;
  lowMidi: number;
  highMidi: number;
  onLowChange: (next: number) => void;
  onHighChange: (next: number) => void;
  onSave: () => void;
  saving: boolean;
  validationError: string | null;
  submitError: string | null;
}

function ResultStep({
  lowResult,
  highResult,
  lowMidi,
  highMidi,
  onLowChange,
  onHighChange,
  onSave,
  saving,
  validationError,
  submitError,
}: ResultStepProps) {
  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-col gap-2">
        <h2 className="text-lg font-semibold text-zinc-900 dark:text-zinc-50">
          측정 결과
        </h2>
        <p className="text-sm text-zinc-600 dark:text-zinc-400">
          필요하면 슬라이더로 ±보정 후 저장하세요.
        </p>
      </div>

      <ConfidenceBadge label="최저음 신뢰도" result={lowResult} />
      <RangeSlider
        label="최저음"
        value={lowMidi}
        onChange={onLowChange}
        testId="low-midi-slider"
      />

      <ConfidenceBadge label="최고음 신뢰도" result={highResult} />
      <RangeSlider
        label="최고음"
        value={highMidi}
        onChange={onHighChange}
        testId="high-midi-slider"
      />

      {validationError ? (
        <p className="text-sm text-red-600 dark:text-red-400">{validationError}</p>
      ) : null}

      {submitError ? (
        <p className="text-sm text-red-600 dark:text-red-400">
          저장에 실패했습니다. {submitError}
        </p>
      ) : null}

      <Button
        variant="primary"
        size="lg"
        fullWidth
        onClick={onSave}
        loading={saving}
        disabled={validationError !== null}
      >
        {saving ? "저장 중..." : "추천 받기"}
      </Button>
    </div>
  );
}

interface ConfidenceBadgeProps {
  label: string;
  result: MeasurementResult | null;
}

function ConfidenceBadge({ label, result }: ConfidenceBadgeProps) {
  const tone = result?.confirmed
    ? "bg-emerald-100 text-emerald-700 dark:bg-emerald-900/50 dark:text-emerald-200"
    : "bg-amber-100 text-amber-700 dark:bg-amber-900/50 dark:text-amber-200";
  const text = result?.confirmed ? "안정" : "낮음 — 재측정 권장";
  return (
    <div className="flex items-center justify-between">
      <span className="text-xs uppercase tracking-widest text-zinc-500">
        {label}
      </span>
      <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${tone}`}>
        {text}
      </span>
    </div>
  );
}

interface RangeSliderProps {
  label: string;
  value: number;
  onChange: (next: number) => void;
  testId: string;
}

function RangeSlider({ label, value, onChange, testId }: RangeSliderProps) {
  return (
    <label className="flex flex-col gap-2 text-sm">
      <div className="flex items-center justify-between">
        <span className="font-medium text-zinc-700 dark:text-zinc-300">
          {label}
        </span>
        <span className="tabular-nums text-zinc-900 dark:text-zinc-50">
          {midiToNoteName(value)} (MIDI {value})
        </span>
      </div>
      <input
        type="range"
        min={MIN_MIDI}
        max={MAX_MIDI}
        value={value}
        onChange={(event) => onChange(Number(event.target.value))}
        data-testid={testId}
        aria-label={label}
        className="h-2 w-full cursor-pointer appearance-none rounded-full bg-zinc-200 accent-zinc-900 dark:bg-zinc-700 dark:accent-zinc-50"
      />
    </label>
  );
}
