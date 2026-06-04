package com.mobruji.recommendation.domain;

import java.util.List;
import java.util.Objects;

import com.mobruji.song.domain.Difficulty;
import com.mobruji.song.domain.NoteName;

/**
 * 과시·킬링파트형(P-F) 추천 1건의 최종 결과 — 페르소나 식별자 + 영속 요청 ID + 과시 강편향으로 재정렬한 추천 곡 묶음.
 * application 계층이 {@code api.dto}에 의존하지 않도록 domain 레이어에 두는 결과 컨테이너 (ADR 0005 §A-7).
 *
 * <p>과시 프리셋(persona-expansion-social-emotional.md §2/§5)은 안전곡(P-E)의 반대축이며 기존 추천 신호의 *가중 프리셋 재조합*
 * 으로 표현한다 — 신규 점수 함수·가중치 변경 없이 ① 에너지 분위기 입력({@code POWERFUL})으로 {@code tempoMatch}/{@code moodMatch}
 * 를 임팩트 쪽으로 편향하고 ② 산출된 결과를 사용자 최고음 근접도(킬링파트 fallback) + {@code difficulty=HARD} 우위로 결정적으로
 * 재정렬한다. 같은 입력이면 같은 결과(결정성 보존).
 *
 * <p>킬링파트(곡 안 임팩트 구간) 메타는 미확보 상태라(§5-2) 음역 천장 근접만으로 1차 추천한다 — 사용자 최고음에 곡 천장이 닿는 곡일수록
 * "한 방 지르기" 좋은 곡으로 본다. 킬링파트 메타가 합류하면 이 결과 컨테이너에 구간 정보를 더하는 것으로 확장한다.
 *
 * <p>{@code requestId} 는 단일 추천과 같은 경로로 영속된 추천 요청 ID 다 — fe 는 곡 피드백·재조회를 단일 추천과 같은 경로로
 * 처리할 수 있다. {@code relaxedFilters} 는 0건 fallback(#1668) 으로 완화한 필터 목록(빈 화면 방지).
 */
public record ShowoffRecommendationResult(
        RecommendationPersona persona,
        Long requestId,
        List<ShowoffRecommendation> recommendations,
        List<FilterRelaxation> relaxedFilters
) {

    public ShowoffRecommendationResult {
        Objects.requireNonNull(persona, "persona must not be null");
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(recommendations, "recommendations must not be null");
        Objects.requireNonNull(relaxedFilters, "relaxedFilters must not be null");
        recommendations = List.copyOf(recommendations);
        relaxedFilters = List.copyOf(relaxedFilters);
    }

    /**
     * 필터 완화가 한 단계라도 적용됐는지 — 응답 {@code relaxed} 플래그(#1668).
     */
    public boolean relaxed() {
        return !relaxedFilters.isEmpty();
    }

    /**
     * 과시 추천 1건 — 단일 추천과 같은 곡 형상({@link ScoredRecommendation}) + 페르소나별 "킬링파트 안내" 사유.
     * {@code killingPartReason} 은 곡 난이도({@code HARD})·곡 천장(최고음)이 사용자 음역 천장에 닿는 정도에서 결정적으로 파생한
     * "한 방 지르기" 안내 한 줄이다 (persona-expansion-social-emotional.md §4 — 설명 가능성).
     */
    public record ShowoffRecommendation(
            ScoredRecommendation recommendation,
            String killingPartReason
    ) {

        public ShowoffRecommendation {
            Objects.requireNonNull(recommendation, "recommendation must not be null");
            Objects.requireNonNull(killingPartReason, "killingPartReason must not be null");
        }

        /**
         * 추천 곡에 "킬링파트 안내" 한 줄을 결정적으로 붙인다. 곡 난이도({@link Difficulty#HARD})와 곡 천장(최고음)이 사용자 음역
         * 천장에 닿는지를 결합해 사유를 고른다. 킬링파트 메타가 없어도(음역 천장 근접만으로 1차) 빈 응답 대신 graceful 한 기본 사유를
         * 받아 P-F 가 항상 "킬링파트 안내"를 노출한다.
         *
         * @param userVoiceHigh 사용자 음역 천장(MIDI). 곡 최고음이 이 값에 가까울수록 "지르기" 좋은 곡으로 본다.
         */
        public static ShowoffRecommendation of(final ScoredRecommendation recommendation, final int userVoiceHigh) {
            return new ShowoffRecommendation(recommendation, killingPartReasonOf(recommendation, userVoiceHigh));
        }

        private static String killingPartReasonOf(final ScoredRecommendation recommendation, final int userVoiceHigh) {
            final Difficulty difficulty = recommendation.song().getDifficulty();
            final Integer highMidi = recommendation.song().getHighMidi();
            final boolean reachesCeiling = highMidi != null && Math.abs(highMidi - userVoiceHigh) <= 2;
            if (highMidi != null && reachesCeiling) {
                final String topNote = NoteName.of(highMidi);
                if (difficulty == Difficulty.HARD) {
                    return "후렴 고음 " + topNote + " 가 음역 천장에 닿아 한 방 지르기 좋은 킬링파트 곡이에요";
                }
                return "후렴 고음 " + topNote + " 가 음역 천장에 닿아 시원하게 지르기 좋아요";
            }
            if (difficulty == Difficulty.HARD) {
                return "고음·넓은 음역의 도전적인 곡이라 제대로 질러 박수받기 좋아요";
            }
            return "후렴에서 시원하게 질러 분위기를 띄우기 좋은 곡이에요";
        }
    }
}
