"use client";

/**
 * 가입 후 선택형 온보딩 (`/onboarding`, closes #1814).
 *
 * 회원가입(#1803) 성공 직후 진입한다. 나이대·성별·분위기를 **각각 선택형(스킵 가능)** 으로
 * 한 번 받아, 추천 기본값으로 영속한다:
 *  - 세 값 모두 `useOnboardingPrefsStore`(localStorage)에 저장 → `/recommend` 진입 시
 *    필터 기본값으로 pre-fill 된다(익명 흐름 포함).
 *  - 성별은 추가로 로그인 사용자의 BE 프로필(`updateProfile`)에도 best-effort 반영해 기기 간
 *    유지한다. 실패해도 온보딩 흐름을 막지 않는다(추천 pre-fill 은 로컬 store 가 보장).
 *
 * "시작하기"는 고른 값을 저장하고 `/recommend` 로, "건너뛰기"는 저장 없이 `/recommend` 로
 * 보낸다(아무것도 안 골라도 음역대만으로 추천된다 — 기존 동작).
 */

import { useState } from "react";
import { useRouter } from "next/navigation";
import { useMutation } from "@tanstack/react-query";

import {
  AgeGroup,
  Mood,
  RequestedGender,
} from "@/lib/api/recommendation";
import { updateProfile } from "@/lib/api/auth";
import { useOnboardingPrefsStore } from "@/store/onboardingPrefs";
import { Button } from "@/components/ui";

/** BE Mood enum → 한국어 라벨 (RecommendFilters 와 동일 톤). */
const MOOD_OPTIONS: ReadonlyArray<{ value: Mood; label: string }> = [
  { value: "UPBEAT", label: "신나는" },
  { value: "CALM", label: "잔잔한" },
  { value: "EMOTIONAL", label: "감성적인" },
  { value: "POWERFUL", label: "파워풀한" },
  { value: "GROOVY", label: "그루비한" },
  { value: "NOSTALGIC", label: "추억의" },
];

/** BE AgeGroup enum → 한국어 라벨. */
const AGE_GROUP_OPTIONS: ReadonlyArray<{ value: AgeGroup; label: string }> = [
  { value: "TEENS", label: "10대" },
  { value: "TWENTIES", label: "20대" },
  { value: "THIRTIES", label: "30대" },
  { value: "FORTIES", label: "40대" },
  { value: "FIFTIES", label: "50대" },
  { value: "SIXTIES_PLUS", label: "60대+" },
];

/** 추천 성별 필터 라벨 — 남자곡/여자곡 가중(genderFit, 배타 제외 아님). */
const GENDER_OPTIONS: ReadonlyArray<{ value: RequestedGender; label: string }> =
  [
    { value: "MALE", label: "남자곡" },
    { value: "FEMALE", label: "여자곡" },
  ];

export default function OnboardingPage() {
  const router = useRouter();
  const setPrefs = useOnboardingPrefsStore((state) => state.setPrefs);

  const [gender, setGender] = useState<RequestedGender | null>(null);
  const [ageGroup, setAgeGroup] = useState<AgeGroup | null>(null);
  const [mood, setMood] = useState<Mood | null>(null);

  // 성별만 BE 프로필 컬럼이 있어 best-effort 로 영속한다(나이대·분위기는 로컬 store 전용).
  // 실패해도 onSettled 에서 동일하게 진행 — 추천 pre-fill 은 로컬 store 가 이미 보장한다.
  const genderProfileMutation = useMutation({
    mutationFn: (value: RequestedGender) => updateProfile({ gender: value }),
  });

  function persistAndGo() {
    setPrefs({ gender, ageGroup, mood });
    if (gender !== null) {
      genderProfileMutation.mutate(gender, {
        onSettled: () => router.push("/recommend"),
      });
      return;
    }
    router.push("/recommend");
  }

  function skip() {
    router.push("/recommend");
  }

  const saving = genderProfileMutation.isPending;

  return (
    <main className="flex flex-1 flex-col items-center bg-[var(--bg-subtle)] px-[var(--page-padding-x)] py-[var(--page-padding-y)]">
      <div className="w-full max-w-md flex flex-col gap-8">
        <header className="space-y-2">
          <h1 className="text-2xl font-semibold text-[var(--text-primary)]">
            취향을 알려주세요
          </h1>
          <p className="text-sm text-[var(--text-secondary)]">
            고른 취향으로 추천 기본값을 맞춰 드려요. 건너뛰어도 음역대만으로 추천됩니다.
          </p>
        </header>

        <div className="flex flex-col gap-6 rounded-[var(--radius-lg)] bg-[var(--bg-base)] p-6 shadow-[var(--shadow-sm)] ring-1 ring-[var(--border)]">
          <ChoiceGroup
            label="성별"
            helpText="남자곡·여자곡 추천에 반영 (선택)"
          >
            {GENDER_OPTIONS.map(({ value, label }) => (
              <ChoiceChip
                key={value}
                label={label}
                active={gender === value}
                onToggle={() => setGender(gender === value ? null : value)}
              />
            ))}
          </ChoiceGroup>

          <ChoiceGroup label="나이대" helpText="세대별 인기곡에 가중 (선택)">
            {AGE_GROUP_OPTIONS.map(({ value, label }) => (
              <ChoiceChip
                key={value}
                label={label}
                active={ageGroup === value}
                onToggle={() => setAgeGroup(ageGroup === value ? null : value)}
              />
            ))}
          </ChoiceGroup>

          <ChoiceGroup label="분위기" helpText="원하는 분위기에 가중 (선택)">
            {MOOD_OPTIONS.map(({ value, label }) => (
              <ChoiceChip
                key={value}
                label={label}
                active={mood === value}
                onToggle={() => setMood(mood === value ? null : value)}
              />
            ))}
          </ChoiceGroup>
        </div>

        <div className="flex flex-col gap-3">
          <Button
            type="button"
            variant="primary"
            size="lg"
            fullWidth
            loading={saving}
            onClick={persistAndGo}
          >
            {saving ? "저장 중..." : "이 취향으로 시작하기"}
          </Button>
          <Button
            type="button"
            variant="ghost"
            size="md"
            fullWidth
            disabled={saving}
            onClick={skip}
          >
            건너뛰기
          </Button>
        </div>
      </div>
    </main>
  );
}

type ChoiceGroupProps = {
  label: string;
  helpText: string;
  children: React.ReactNode;
};

function ChoiceGroup({ label, helpText, children }: ChoiceGroupProps) {
  return (
    <div role="group" aria-label={label} className="flex flex-col gap-2">
      <div className="flex items-baseline gap-2">
        <span className="text-sm font-medium text-[var(--text-primary)]">
          {label}
        </span>
        <span className="text-xs text-[var(--text-caption)]">{helpText}</span>
      </div>
      <div className="flex flex-wrap gap-2">{children}</div>
    </div>
  );
}

type ChoiceChipProps = {
  label: string;
  active: boolean;
  onToggle: () => void;
};

function ChoiceChip({ label, active, onToggle }: ChoiceChipProps) {
  return (
    <button
      type="button"
      aria-pressed={active}
      onClick={onToggle}
      className={`min-h-9 rounded-full px-4 text-sm font-medium transition-colors duration-[var(--duration-base)] focus:outline-none focus-visible:ring-2 focus-visible:ring-[var(--cta-secondary-ring)] focus-visible:ring-offset-2 ${
        active
          ? "bg-[var(--brand-500)] text-white hover:bg-[var(--brand-600)]"
          : "bg-[var(--badge-neutral-bg)] text-[var(--badge-neutral-fg)] hover:bg-[var(--bg-subtle)]"
      }`}
    >
      {label}
    </button>
  );
}
