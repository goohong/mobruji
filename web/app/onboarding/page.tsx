"use client";

/**
 * 회원가입 온보딩 화면 (`/onboarding`, closes #1814).
 *
 * 가입 직후(계정 생성 → setSession) 진입하는 **선택형** 스텝. 나이대·성별·취향을
 * 한 번에 고르고, 각 항목은 스킵 가능(미선택 허용 — 강제하지 않는다). 제출 시:
 *   1. 고른 값을 `useRecommendDefaultsStore` 에 영속 → `/recommend` 필터 pre-fill.
 *   2. 성별을 골랐다면 `updateProfile({ gender })` 로 BE 프로필에도 반영(best-effort —
 *      실패해도 클라이언트 기본값은 이미 저장됐으므로 흐름을 막지 않는다).
 *   3. 홈("/")으로 라우팅.
 *
 * "건너뛰기"는 아무 것도 고르지 않은 것과 동일하게 모든 값을 null 로 저장하고 진행한다
 * (generationFit/genderFit=0, 하위호환). 비로그인 상태로 직접 진입하면 온보딩 대상이
 * 아니므로 홈으로 돌려보낸다(온보딩은 가입자 한정 — 익명 흐름은 종전대로).
 */

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";

import { updateProfile, type UserGender } from "@/lib/api/auth";
import type { AgeGroup, Mood } from "@/lib/api/recommendation";
import { useAuthStore } from "@/store/auth";
import { useRecommendDefaultsStore } from "@/store/recommendDefaults";

const AGE_GROUP_OPTIONS: ReadonlyArray<{ value: AgeGroup; label: string }> = [
  { value: "TEENS", label: "10대" },
  { value: "TWENTIES", label: "20대" },
  { value: "THIRTIES", label: "30대" },
  { value: "FORTIES", label: "40대" },
  { value: "FIFTIES", label: "50대" },
  { value: "SIXTIES_PLUS", label: "60대+" },
];

/** 온보딩은 남/여만 선택지로 둔다(미선택 = null = BE UNSPECIFIED 와 동치). */
const GENDER_OPTIONS: ReadonlyArray<{ value: UserGender; label: string }> = [
  { value: "MALE", label: "남성" },
  { value: "FEMALE", label: "여성" },
];

const MOOD_OPTIONS: ReadonlyArray<{ value: Mood; label: string }> = [
  { value: "UPBEAT", label: "신나는" },
  { value: "CALM", label: "잔잔한" },
  { value: "EMOTIONAL", label: "감성적인" },
  { value: "POWERFUL", label: "파워풀한" },
  { value: "GROOVY", label: "그루비한" },
  { value: "NOSTALGIC", label: "추억의" },
];

export default function OnboardingPage() {
  const router = useRouter();
  const token = useAuthStore((state) => state.token);
  const applyOnboarding = useRecommendDefaultsStore(
    (state) => state.applyOnboarding,
  );

  const [ageGroup, setAgeGroup] = useState<AgeGroup | null>(null);
  const [gender, setGender] = useState<UserGender | null>(null);
  const [mood, setMood] = useState<Mood | null>(null);
  const [submitting, setSubmitting] = useState(false);

  // 온보딩은 가입자 한정. 토큰 없이 직접 진입하면 홈으로 돌려보낸다(익명 흐름 보호).
  useEffect(() => {
    if (token === null) {
      router.replace("/");
    }
  }, [token, router]);

  async function finish(selection: {
    ageGroup: AgeGroup | null;
    gender: UserGender | null;
    mood: Mood | null;
  }) {
    setSubmitting(true);
    // 클라이언트 기본값을 먼저 영속 — BE 반영 실패와 무관하게 추천 pre-fill 은 보장한다.
    applyOnboarding(selection);
    if (selection.gender !== null) {
      // best-effort — 실패해도 흐름을 막지 않는다(다음 프로필 갱신 때 다시 반영 가능).
      try {
        await updateProfile({ gender: selection.gender });
      } catch {
        // 무시: 클라이언트 기본값은 이미 저장됨.
      }
    }
    router.push("/");
  }

  return (
    <main className="flex flex-1 flex-col items-center bg-[var(--bg-subtle)] px-[var(--page-padding-x)] py-[var(--page-padding-y)]">
      <div className="w-full max-w-md flex flex-col gap-8">
        <header className="space-y-2">
          <h1 className="text-2xl font-semibold text-[var(--text-primary)]">
            추천을 더 맞춰드릴게요
          </h1>
          <p className="text-sm text-[var(--text-secondary)]">
            나이대·성별·취향을 고르면 추천 필터의 기본값으로 채워드려요. 모두
            선택이라 건너뛰어도 됩니다.
          </p>
        </header>

        <ChoiceGroup
          label="나이대"
          helpText="세대별 인기곡에 가중 (선택)"
          options={AGE_GROUP_OPTIONS}
          selected={ageGroup}
          onSelect={(value) => setAgeGroup(value)}
        />

        <ChoiceGroup
          label="성별"
          helpText="성별 적합 가중 (선택)"
          options={GENDER_OPTIONS}
          selected={gender}
          onSelect={(value) => setGender(value)}
        />

        <ChoiceGroup
          label="취향"
          helpText="좋아하는 분위기 (선택)"
          options={MOOD_OPTIONS}
          selected={mood}
          onSelect={(value) => setMood(value)}
        />

        <div className="flex flex-col gap-3 pt-2">
          <button
            type="button"
            disabled={submitting}
            onClick={() => finish({ ageGroup, gender, mood })}
            className="inline-flex h-11 items-center justify-center rounded-full bg-[var(--brand-500)] px-5 text-sm font-medium text-white transition-colors duration-[var(--duration-base)] hover:bg-[var(--brand-600)] hover:shadow-[var(--shadow-brand)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--brand-500)] focus-visible:ring-offset-2 disabled:opacity-60"
          >
            {submitting ? "저장 중..." : "완료"}
          </button>
          <button
            type="button"
            disabled={submitting}
            onClick={() => finish({ ageGroup: null, gender: null, mood: null })}
            className="inline-flex h-11 items-center justify-center rounded-full px-5 text-sm font-medium text-[var(--text-secondary)] transition-colors duration-[var(--duration-base)] hover:bg-[var(--bg-base)] hover:text-[var(--text-primary)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--cta-secondary-ring)] focus-visible:ring-offset-2 disabled:opacity-60"
          >
            건너뛰기
          </button>
        </div>
      </div>
    </main>
  );
}

type ChoiceGroupProps<TValue extends string> = {
  label: string;
  helpText: string;
  options: ReadonlyArray<{ value: TValue; label: string }>;
  selected: TValue | null;
  onSelect: (value: TValue | null) => void;
};

/**
 * 단일 선택 칩 그룹. 선택된 칩을 다시 누르면 해제(null) → 스킵과 동일.
 * 칩은 toggle 버튼(`aria-pressed`)이라 "선택 해제 가능" 시맨틱이 정확하다.
 */
function ChoiceGroup<TValue extends string>({
  label,
  helpText,
  options,
  selected,
  onSelect,
}: ChoiceGroupProps<TValue>) {
  return (
    <div role="group" aria-label={label} className="flex flex-col gap-2">
      <div className="flex items-baseline gap-2">
        <span className="text-sm font-medium text-[var(--text-primary)]">
          {label}
        </span>
        <span className="text-xs text-[var(--text-caption)]">{helpText}</span>
      </div>
      <div className="flex flex-wrap gap-2">
        {options.map(({ value, label: optionLabel }) => {
          const active = selected === value;
          return (
            <button
              key={value}
              type="button"
              aria-pressed={active}
              onClick={() => onSelect(active ? null : value)}
              className={`min-h-9 rounded-full px-4 text-sm font-medium transition-colors duration-[var(--duration-base)] focus:outline-none focus-visible:ring-2 focus-visible:ring-[var(--cta-secondary-ring)] focus-visible:ring-offset-2 ${
                active
                  ? "bg-[var(--brand-500)] text-white hover:bg-[var(--brand-600)]"
                  : "bg-[var(--badge-neutral-bg)] text-[var(--badge-neutral-fg)] hover:bg-[var(--bg-subtle)]"
              }`}
            >
              {optionLabel}
            </button>
          );
        })}
      </div>
    </div>
  );
}
