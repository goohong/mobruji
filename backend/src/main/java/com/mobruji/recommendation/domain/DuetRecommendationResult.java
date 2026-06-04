package com.mobruji.recommendation.domain;

import java.util.List;
import java.util.Objects;

import com.mobruji.song.domain.NoteName;
import com.mobruji.song.domain.VocalGender;

/**
 * 듀엣·함께 부르기형(P-G) 추천 1건의 최종 결과 — 페르소나 식별자 + 영속 요청 ID + 듀엣 강편향으로 재정렬한 추천 곡 묶음.
 * application 계층이 {@code api.dto}에 의존하지 않도록 domain 레이어에 두는 결과 컨테이너 (ADR 0005 §A-7).
 *
 * <p>듀엣 프리셋(persona-expansion-social-emotional.md §2/§5)은 기존 추천 신호의 *가중 프리셋 재조합* 으로 표현한다 —
 * 신규 점수 함수·가중치 변경 없이 ① 두 사람 음역의 합집합을 단일 추천 입력으로 넣어 후보를 만들고 ② 산출된 결과를
 * {@code VocalGender.MIXED}(큐레이션 듀엣/혼성 곡) 우위 + 두 음역 동시 충족도로 결정적으로 재정렬한다. 같은 입력이면 같은 결과(결정성 보존).
 *
 * <p>전용 "듀엣곡 분류" 데이터셋은 미확보 상태라(§5-2) 기존 {@link VocalGender#MIXED} 큐레이션 신호를 1차 듀엣 분류 신호로 재사용하고,
 * MIXED 가 부족하면 두 음역 동시 충족도(파트 분리 적합)로 graceful 하게 채운다 — 사용자 최고음 근접만으로 1차 추천한 과시(P-F)와 같은 패턴이다.
 *
 * <p>{@code requestId} 는 단일 추천과 같은 경로로 영속된 추천 요청 ID 다 — fe 는 곡 피드백·재조회를 단일 추천과 같은 경로로
 * 처리할 수 있다. {@code relaxedFilters} 는 0건 fallback(#1668) 으로 완화한 필터 목록(빈 화면 방지).
 */
public record DuetRecommendationResult(
        RecommendationPersona persona,
        Long requestId,
        List<DuetRecommendation> recommendations,
        List<FilterRelaxation> relaxedFilters
) {

    public DuetRecommendationResult {
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
     * 듀엣 추천 1건 — 단일 추천과 같은 곡 형상({@link ScoredRecommendation}) + 페르소나별 "파트 분담" 안내.
     * {@code partAssignmentReason} 은 곡 음역을 중간음에서 둘로 나눠 낮은 파트는 저음 담당이, 높은 파트는 고음 담당이 부르도록
     * 결정적으로 파생한 한 줄이다 (persona-expansion-social-emotional.md §4 — 설명 가능성).
     */
    public record DuetRecommendation(
            ScoredRecommendation recommendation,
            String partAssignmentReason
    ) {

        public DuetRecommendation {
            Objects.requireNonNull(recommendation, "recommendation must not be null");
            Objects.requireNonNull(partAssignmentReason, "partAssignmentReason must not be null");
        }

        /**
         * 추천 곡에 "파트 분담" 안내 한 줄을 결정적으로 붙인다. 곡 음역({@code lowMidi}~{@code highMidi})을 중간음에서 둘로 나눠
         * 낮은 파트는 저음 담당이, 높은 파트는 고음 담당이 부르도록 안내한다. 큐레이션 듀엣곡({@link VocalGender#MIXED})이면 그 사실을
         * 함께 알리고, 두 사람 성별이 주어지면 "남성/여성 파트"로 라벨을 붙인다. 음역 정보가 없는 곡도 빈 응답 대신 graceful 한 기본
         * 안내를 받아 P-G 가 항상 "파트 분담"을 노출한다.
         *
         * @param lowerPartGender  낮은 파트(저음 담당) 가수의 성별. null이면 파트 라벨 없이 음높이만 안내.
         * @param higherPartGender 높은 파트(고음 담당) 가수의 성별. null이면 파트 라벨 없이 음높이만 안내.
         */
        public static DuetRecommendation of(
                final ScoredRecommendation recommendation,
                final VocalGender lowerPartGender,
                final VocalGender higherPartGender) {
            return new DuetRecommendation(
                    recommendation, partAssignmentReasonOf(recommendation, lowerPartGender, higherPartGender));
        }

        private static String partAssignmentReasonOf(
                final ScoredRecommendation recommendation,
                final VocalGender lowerPartGender,
                final VocalGender higherPartGender) {
            final Integer lowMidi = recommendation.song().getLowMidi();
            final Integer highMidi = recommendation.song().getHighMidi();
            final boolean curatedDuet = recommendation.song().getVocalGender() == VocalGender.MIXED;
            if (lowMidi == null || highMidi == null) {
                return curatedDuet
                        ? "큐레이션 듀엣곡이라 둘이 파트를 나눠 부르기 좋아요"
                        : "둘이 파트를 나눠 부르기 좋은 곡이에요";
            }
            final int splitMidi = (lowMidi + highMidi) / 2;
            final String lowerLabel = partLabel(lowerPartGender, "낮은 파트");
            final String higherLabel = partLabel(higherPartGender, "높은 파트");
            final String split = lowerLabel + " " + NoteName.of(lowMidi) + "~" + NoteName.of(splitMidi)
                    + " · " + higherLabel + " " + NoteName.of(splitMidi) + "~" + NoteName.of(highMidi)
                    + " 로 나눠 부르기 좋아요";
            return curatedDuet ? "큐레이션 듀엣곡 — " + split : split;
        }

        /**
         * 파트 라벨을 성별로 붙인다 — MALE="남성 파트" / FEMALE="여성 파트". MIXED·미입력이면 음높이 기준 기본 라벨({@code fallback}).
         */
        private static String partLabel(final VocalGender gender, final String fallback) {
            if (gender == VocalGender.MALE) {
                return "남성 파트";
            }
            if (gender == VocalGender.FEMALE) {
                return "여성 파트";
            }
            return fallback;
        }
    }
}
