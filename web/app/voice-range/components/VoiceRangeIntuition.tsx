"use client";

/**
 * 음역대 직관화 묶음 — 시각화(VoiceRangeScale) + 상대 설명(describeRelative).
 *
 * voice-range-intuitive-display.md §5-6 의 결과/확인 영역에서 재사용하는 묶음.
 * 추천 결과 헤더 · 자동 측정 결과 · 직접 선택 미리보기 세 화면이 동일 표현을 쓰도록
 * 시각화와 상대 설명을 하나로 묶는다(중복 제거).
 *
 * 성별 중립 기본 — 현재 성별 미수집(§8 Q1) → 성별 중립 벤치마크. 상대 설명/시각화 모두
 * 같은 벤치마크를 공유한다.
 */

import { describeRelative } from "@/lib/voiceRangeBenchmark";
import { VoiceRangeScale } from "./VoiceRangeScale";

type Props = {
  lowMidi: number;
  highMidi: number;
  /** 시각화 위 짧은 제목(생략 가능). */
  caption?: string;
};

export function VoiceRangeIntuition({ lowMidi, highMidi, caption }: Props) {
  const relative = describeRelative(lowMidi, highMidi);

  return (
    <div className="flex flex-col gap-2 pt-1">
      <VoiceRangeScale lowMidi={lowMidi} highMidi={highMidi} caption={caption} />
      <p
        data-testid="voice-range-relative-headline"
        className="text-sm font-medium text-[var(--text-secondary)]"
      >
        {relative.headline}
        {relative.detail ? (
          <span className="font-normal text-[var(--text-caption)]">
            {" · "}
            {relative.detail}
          </span>
        ) : null}
      </p>
    </div>
  );
}
