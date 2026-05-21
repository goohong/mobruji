package com.mobruji.song.domain;

/**
 * 곡의 1차 분위기 라벨. 추천 매칭의 moodMatch 입력.
 * v1은 단일 mood (다중 분위기는 후속에서 @ElementCollection 도입 시).
 */
public enum Mood {

    UPBEAT,
    CALM,
    EMOTIONAL,
    POWERFUL,
    GROOVY,
    NOSTALGIC,
}
