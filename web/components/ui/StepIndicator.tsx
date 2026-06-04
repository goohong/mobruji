/**
 * StepIndicator — 온보딩 진행 단계 표시 (음역대 입력 → 추천).
 *
 * 배경(#1722, #1709 /voice-range UX 점검):
 *   - 기존엔 헤더에 "Step 1" 텍스트만 단독으로 떠 총 단계 수·현재 위치를 알 수 없어
 *     "Step 1 of ?" 불안을 줬다. 현재/총 단계를 함께 노출하고 점(dot) 막대로 위치를
 *     시각화해 흐름을 예측 가능하게 한다.
 *
 * 표현:
 *   - "STEP n/total" 라벨(대문자 caption) + 점 막대(완료/현재 = brand, 이후 = track).
 *   - 점은 장식 → aria-hidden. 의미는 wrapper 의 aria-label 로 SR 에 전달한다.
 */

type Props = {
  /** 현재 단계(1-based). */
  current: number;
  /** 총 단계 수. */
  total: number;
};

export function StepIndicator({ current, total }: Props) {
  const steps = Array.from({ length: total }, (_, index) => index + 1);

  return (
    <div
      className="flex items-center gap-2"
      aria-label={`전체 ${total}단계 중 ${current}단계`}
    >
      <span className="text-xs font-medium uppercase tracking-widest text-[var(--text-caption)] tabular-nums">
        {current}/{total} 단계
      </span>
      <span aria-hidden="true" className="flex items-center gap-1.5">
        {steps.map((step) => (
          <span
            key={step}
            data-testid="step-indicator-dot"
            data-active={step <= current ? "true" : "false"}
            className={`h-1.5 rounded-full transition-colors duration-[var(--duration-base)] ${
              step === current ? "w-4" : "w-1.5"
            } ${
              step <= current
                ? "bg-[var(--brand-500)]"
                : "bg-[var(--meter-track-bg)]"
            }`}
          />
        ))}
      </span>
    </div>
  );
}
