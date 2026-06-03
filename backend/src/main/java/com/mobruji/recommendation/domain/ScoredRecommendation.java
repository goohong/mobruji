package com.mobruji.recommendation.domain;

import java.util.Objects;

import com.mobruji.song.domain.Difficulty;
import com.mobruji.song.domain.NoteName;
import com.mobruji.song.domain.Song;

/**
 * 추천 결과 1건 (한 곡 + 점수 + 매칭 사유 + 랭킹). application 계층이 api.dto에 의존하지 않도록
 * domain 레이어에 두는 값 객체. 영속 엔티티({@link Recommendation})와 응답 형태({@code RecommendedSongResponse})를
 * 잇는 중간 표현.
 *
 * <p>{@code breakdown}은 score 산정 시점의 raw 신호(0~1) 5종을 담아 응답에 그대로 노출한다. 영속 엔티티에는 저장되지 않아
 * {@code readById} 경로(과거 추천 재조회)에서는 {@code null}이 들어온다. UI는 null이면 펼침 영역을 숨기는 식으로 동작한다.
 *
 * <p>{@link #voiceFit()} / {@link #voiceFitReason()}은 "왜 이 곡?"(설명 가능성, #1484) 전면 노출용으로,
 * breakdown 의 {@code rangeFit} 신호를 곡별 음역 적합도 점수(0~1)와 짧은 한국어 사유로 풀어 준다.
 * {@link #moodFit()} / {@link #moodFitReason()}은 같은 패턴으로 {@code moodMatch} 분위기 연속 유사도(#1485)를 노출한다.
 * breakdown 이 없는(과거 추천 재조회) 경로에서는 모두 {@code null} — 응답 DTO 에서 그대로 통과시킨다.
 *
 * <p>{@link #practiceDifficulty()} / {@link #practiceDifficultyReason()}은 연습형 페르소나(P-A, #1494)용으로
 * 곡 자체의 가창 난이도·음역 범위(최저~최고음)를 노출한다. breakdown 이 아니라 곡 속성에서 파생하므로 재조회 경로에서도 동작하며,
 * 음역 미보유 곡은 graceful 하게 "정보 없음" 사유를 돌려준다.
 *
 * <p>{@link #suggestedTranspose()} / {@link #transposedVoiceFit()} / {@link #suggestedTransposeReason()}은 연습형(P-A,
 * #1544)
 * 용으로, voiceFit 낮은 곡에 권장 조옮김량(반음)과 조옮김 후 재계산 적합도를 노출한다. 조옮김 불요·산정 근거 없음·재조회 경로에서는 모두 {@code null}.
 */
public record ScoredRecommendation(
        Song song,
        double score,
        String matchReason,
        int rankPosition,
        ScoreBreakdown breakdown,
        TransposeSuggestion transposeSuggestion
) {

    public ScoredRecommendation {
        Objects.requireNonNull(song, "song must not be null");
        Objects.requireNonNull(matchReason, "matchReason must not be null");
        if (rankPosition < 1) {
            throw new IllegalArgumentException("rankPosition must be >= 1: " + rankPosition);
        }
        // breakdown / transposeSuggestion은 readById 경로 또는 조옮김 불요 곡에서 null. 응답 DTO에서 분기 처리.
    }

    /**
     * breakdown 없는 호출(과거 추천 재조회 등) 편의 생성자.
     */
    public ScoredRecommendation(final Song song, final double score, final String matchReason, final int rankPosition) {
        this(song, score, matchReason, rankPosition, null, null);
    }

    /**
     * 조옮김 제안이 없는(=대부분의 곡) 호출 편의 생성자. {@code transposeSuggestion} 만 {@code null} 로 둔다.
     */
    public ScoredRecommendation(
            final Song song,
            final double score,
            final String matchReason,
            final int rankPosition,
            final ScoreBreakdown breakdown) {
        this(song, score, matchReason, rankPosition, breakdown, null);
    }

    /**
     * 곡별 음역 적합도 점수(0~1). breakdown 의 {@code rangeFit} 신호를 전면 노출한다.
     * 과거 추천 재조회 경로(breakdown null)에서는 {@code null}.
     */
    public Double voiceFit() {
        return breakdown == null ? null : breakdown.rangeFit();
    }

    /**
     * 음역 적합도를 풀어 주는 짧은 한국어 사유. breakdown 이 없으면(과거 추천 재조회) {@code null}.
     * 곡 키가 UNKNOWN(keyMatch &lt; 1.0)이면 적합도 산정 근거가 없으므로 그 사실을 그대로 알린다.
     */
    public String voiceFitReason() {
        if (breakdown == null) {
            return null;
        }
        return describeVoiceFit(breakdown.keyMatch(), breakdown.rangeFit());
    }

    private static String describeVoiceFit(final double keyMatch, final double rangeFit) {
        if (keyMatch < 1.0) {
            return "곡 키 정보가 없어 음역대 적합도를 정확히 알기 어려워요";
        }
        if (rangeFit >= 0.7) {
            return "원곡 키가 음역대에 아주 잘 맞아요";
        }
        if (rangeFit >= 0.4) {
            return "원곡 키가 음역대에 무난하게 맞아요";
        }
        if (rangeFit > 0.0) {
            return "원곡 키가 음역대에 다소 부담될 수 있어요";
        }
        return "원곡 키가 음역대와 잘 맞지 않아요";
    }

    /**
     * 곡별 분위기 적합도 점수(0~1). breakdown 의 {@code moodMatch} 연속 유사도(#1485)를 전면 노출한다.
     * 과거 추천 재조회 경로(breakdown null)에서는 {@code null}.
     */
    public Double moodFit() {
        return breakdown == null ? null : breakdown.moodMatch();
    }

    /**
     * 분위기 적합도를 풀어 주는 짧은 한국어 사유. breakdown 이 없으면(과거 추천 재조회) {@code null}.
     * 분위기 신호가 0.0(분위기 미입력 또는 곡 mood 부재)이면 설명할 근거가 없으므로 {@code null}.
     */
    public String moodFitReason() {
        if (breakdown == null) {
            return null;
        }
        return describeMoodFit(breakdown.moodMatch());
    }

    private static String describeMoodFit(final double moodMatch) {
        if (moodMatch <= 0.0) {
            return null;
        }
        if (moodMatch >= 1.0) {
            return "요청하신 분위기와 딱 맞아요";
        }
        if (moodMatch >= 0.6) {
            return "요청하신 분위기와 잘 어울려요";
        }
        if (moodMatch >= 0.3) {
            return "요청하신 분위기와 어느 정도 비슷해요";
        }
        return "요청하신 분위기와는 결이 조금 달라요";
    }

    /**
     * 연습형 페르소나(P-A) 지원 — 곡의 가창 난이도(EASY/NORMAL/HARD)를 그대로 노출한다(#1494).
     * 곡 음역({@code lowMidi}/{@code highMidi})으로 자동 분류된 {@link Song#getDifficulty()} 값이며,
     * 음역 미보유 곡은 산출 근거가 없어 {@code null}. voiceFit 과 달리 곡 자체 속성에서 파생하므로
     * breakdown 이 없는 과거 추천 재조회 경로에서도 동작한다.
     */
    public Difficulty practiceDifficulty() {
        return song.getDifficulty();
    }

    /**
     * 연습 난이도를 풀어 주는 짧은 한국어 사유 + 음역 범위(최저~최고음) 안내(#1494). 음역대 분석 정보가 없는 곡은
     * 그 사실을 그대로 알린다(graceful) — 빈 응답 대신 "정보 없음" 을 노출해 P-A 가 항상 피드백을 받는다.
     */
    public String practiceDifficultyReason() {
        return describePracticeDifficulty(song.getDifficulty(), song.getLowMidi(), song.getHighMidi());
    }

    private static String describePracticeDifficulty(
            final Difficulty difficulty,
            final Integer lowMidi,
            final Integer highMidi) {
        if (difficulty == null || lowMidi == null || highMidi == null) {
            return "아직 음역대 분석 정보가 없어 난이도를 가늠하기 어려워요";
        }
        final String range = NoteName.of(lowMidi) + "~" + NoteName.of(highMidi);
        return switch (difficulty) {
            case EASY -> "음역 " + range + ", 음역 폭이 넓지 않아 부담 없이 연습하기 좋아요";
            case NORMAL -> "음역 " + range + ", 적당한 난이도라 연습용으로 무난해요";
            case HARD -> "음역 " + range + ", 고음·넓은 음역이라 도전적인 곡이에요";
        };
    }

    /**
     * 권장 조옮김량(반음 수, #1544). 양수=올림, 음수=내림. 원곡 키가 음역대에 부담스러운 곡에만 채워지며,
     * 조옮김이 불필요하거나(원곡 무난) 산정 근거가 없는(키 UNKNOWN) 곡, 과거 추천 재조회 경로에서는 {@code null}.
     */
    public Integer suggestedTranspose() {
        return transposeSuggestion == null ? null : transposeSuggestion.semitones();
    }

    /**
     * 권장 조옮김을 적용해 재계산한 음역 적합도(0~1, #1544). voiceFit(rangeFit)과 동일한 산식이라 직접 비교 가능하다.
     * 조옮김 제안이 없으면 {@code null}.
     */
    public Double transposedVoiceFit() {
        return transposeSuggestion == null ? null : transposeSuggestion.transposedVoiceFit();
    }

    /**
     * 조옮김 제안을 풀어 주는 짧은 한국어 사유(#1544). 제안이 없으면 {@code null}.
     */
    public String suggestedTransposeReason() {
        if (transposeSuggestion == null) {
            return null;
        }
        return describeTranspose(transposeSuggestion.semitones());
    }

    private static String describeTranspose(final int semitones) {
        final int magnitude = Math.abs(semitones);
        final String direction = semitones > 0 ? "올려" : "내려";
        return magnitude + "키 " + direction + " 부르면 음역대에 더 잘 맞아요";
    }
}
