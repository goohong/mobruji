package com.mobruji.recommendation.domain;

import com.mobruji.song.domain.Mood;

/**
 * 모임 사회자형(P-D) 시퀀스 추천의 자리 단계 — 워밍업 → 고조 → 마무리의 분위기 흐름(persona-expansion-social-emotional.md §2/§5).
 *
 * <p>각 단계는 신규 추천 알고리즘이 아니라 기존 분위기({@link Mood}) 신호의 *입력*만 단계별로 달리해 산출한다. 단계 분위기는
 * 추천 점수 함수의 {@code moodMatch} + (분위기 기반) {@code tempoMatch} default 에 그대로 반영되므로, 가중치/결정성 불변식을
 * 건드리지 않고 단계별로 다른 곡 묶음을 얻는다. 단계 순서({@link #ordinal()})는 응답에 노출하는 진행 순서다.
 *
 * <ul>
 * <li>{@link #WARMUP} — 다 같이 편하게 시작. 낮은 에너지({@link Mood#CALM}).</li>
 * <li>{@link #PEAK} — 분위기 고조. 신나는 곡({@link Mood#UPBEAT}).</li>
 * <li>{@link #CLOSING} — 감성적 마무리({@link Mood#EMOTIONAL}).</li>
 * </ul>
 */
public enum SequenceStage {

    WARMUP(Mood.CALM, "다 같이 편하게 시작할 워밍업 단계예요"),
    PEAK(Mood.UPBEAT, "분위기를 끌어올릴 고조 단계예요"),
    CLOSING(Mood.EMOTIONAL, "감성적으로 마무리하는 단계예요");

    private final Mood mood;
    private final String stageReason;

    SequenceStage(final Mood mood, final String stageReason) {
        this.mood = mood;
        this.stageReason = stageReason;
    }

    public Mood mood() {
        return mood;
    }

    public String stageReason() {
        return stageReason;
    }
}
