"use client";

/**
 * 시퀀스 뷰 (이슈 #1601, spec §2 P-D / §3-1 / §5-6).
 *
 * 모임 사회자 모드의 단계별 곡 묶음(도입 → 고조 → 마무리)을 한 단계씩 보여준다. 현재 단계의
 * 곡 카드를 노출하고, "한 곡 부른 뒤 다음(고조)로 흐름 진행"(이슈 요구사항)을 위한 진행
 * 버튼을 둔다. 단계 점프는 상단 SeatStageToggle 로도 가능하다.
 *
 * 카드 상호작용은 기존 추천 화면과 동일하게 요약 카드 + 상세 모달(SongDetailModal) 패턴을
 * 재사용한다.
 */

import { useState } from "react";

import type {
  RecommendedSongResponse,
  SequenceStage,
  SequenceStageBundle,
} from "@/lib/api/recommendation";
import { nextStage, stageMeta } from "@/lib/sequence";
import { formatSongDisplayTitle } from "@/lib/songTitle";
import type { UserVoiceRange } from "@/lib/scoreBreakdown";

import { SongCard } from "../../components/SongCard";
import { SongDetailModal } from "../../components/SongDetailModal";
import { SongDetailContent } from "../../components/SongDetailContent";
import { SeatStageToggle } from "./SeatStageToggle";

type SequenceViewProps = {
  stages: SequenceStageBundle[];
  userVoiceRange: UserVoiceRange;
};

export function SequenceView({ stages, userVoiceRange }: SequenceViewProps) {
  const [currentStage, setCurrentStage] = useState<SequenceStage>("INTRO");
  const [selected, setSelected] = useState<RecommendedSongResponse | null>(null);

  const currentBundle = stages.find((bundle) => bundle.stage === currentStage);
  const currentSongs = currentBundle?.songs ?? [];
  const { label, description } = stageMeta(currentStage);
  const upcoming = nextStage(currentStage);

  return (
    <div className="flex flex-col gap-4">
      <SeatStageToggle
        stages={stages}
        currentStage={currentStage}
        onSelect={setCurrentStage}
      />

      <div className="flex flex-col gap-1">
        <h2 className="text-lg font-semibold text-[var(--text-primary)]">
          {label}
        </h2>
        <p className="text-sm text-[var(--text-secondary)]">{description}</p>
      </div>

      {currentSongs.length === 0 ? (
        <div
          role="status"
          className="rounded-[var(--radius-lg)] border border-[var(--border)] bg-[var(--bg-base)] p-5 text-sm text-[var(--text-secondary)]"
        >
          이 단계에 추천할 곡이 없어요. 다른 단계를 골라보세요.
        </div>
      ) : (
        <ul className="flex flex-col gap-3">
          {currentSongs.map((item) => (
            <SongCard
              key={item.song.id}
              item={item}
              userVoiceRange={userVoiceRange}
              onShowDetail={() => setSelected(item)}
            />
          ))}
        </ul>
      )}

      {upcoming ? (
        <button
          type="button"
          onClick={() => setCurrentStage(upcoming)}
          className="inline-flex h-11 w-fit items-center justify-center self-start rounded-full bg-[var(--brand-500)] px-5 text-sm font-medium text-white transition-colors duration-[var(--duration-base)] hover:bg-[var(--brand-600)] hover:shadow-[var(--shadow-brand)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--brand-500)] focus-visible:ring-offset-2"
        >
          다음 ({stageMeta(upcoming).label})으로
        </button>
      ) : (
        <p
          role="status"
          className="text-sm text-[var(--text-secondary)]"
        >
          마무리까지 흐름이 끝났어요. 자리를 잘 이끄셨어요.
        </p>
      )}

      <SongDetailModal
        open={selected !== null}
        onClose={() => setSelected(null)}
        titleLabel={selected ? formatSongDisplayTitle(selected.song) : ""}
      >
        {selected ? (
          <SongDetailContent item={selected} userVoiceRange={userVoiceRange} />
        ) : null}
      </SongDetailModal>
    </div>
  );
}
