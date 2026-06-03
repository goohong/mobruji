package com.mobruji.recommendation.domain;

import com.mobruji.song.domain.Mood;

/**
 * 분위기 메이커 모드(F3)의 분위기 프리셋 — "지금 이 자리를 살릴 곡" 이라는 상황 의도를 기존 곡 속성
 * {@link Mood} + preferredBpm 위에 입힌 사용자-페이싱 view.
 *
 * <p>spec: docs/features/mood-mode.md §5-1, §5-3. 신규 {@link Mood} enum 값이 아니라, 기존 enum
 * 으로 풀리는 프리셋이다 — 추천 알고리즘(moodMatch/tempoMatch)·가중치는 무변경.
 *
 * <p>본 enum 은 프리셋 → {@link Mood} 매핑의 BE 단일 출처다(fe {@code web/lib/moodPreset.ts} 와
 * 동기). preferredBpm 은 프리셋의 {@link Mood} 를 키로 {@code recommendation.tempo.moodDefaultBpm}
 * 에서 해석한다(별도 BPM 표 중복 없이 단일 출처 유지).
 */
public enum MoodPreset {

    PARTY("회식 띄우기", Mood.UPBEAT),
    SINGALONG("떼창", Mood.POWERFUL),
    EMOTIONAL("감성", Mood.EMOTIONAL),
    ICEBREAKER("도입", Mood.GROOVY);

    private final String displayName;
    private final Mood mood;

    MoodPreset(final String displayName, final Mood mood) {
        this.displayName = displayName;
        this.mood = mood;
    }

    public String displayName() {
        return displayName;
    }

    public Mood mood() {
        return mood;
    }
}
