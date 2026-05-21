package com.mobruji.song;

/**
 * 곡의 원곡 키 (조성). 메이저/마이너 12개씩 24개 + UNKNOWN.
 * MIDI semitone 변환은 후속(추천 알고리즘에서 voiceRangeFit 계산 시 필요).
 */
public enum MusicalKey {

    C_MAJOR,
    C_SHARP_MAJOR,
    D_MAJOR,
    D_SHARP_MAJOR,
    E_MAJOR,
    F_MAJOR,
    F_SHARP_MAJOR,
    G_MAJOR,
    G_SHARP_MAJOR,
    A_MAJOR,
    A_SHARP_MAJOR,
    B_MAJOR,
    C_MINOR,
    C_SHARP_MINOR,
    D_MINOR,
    D_SHARP_MINOR,
    E_MINOR,
    F_MINOR,
    F_SHARP_MINOR,
    G_MINOR,
    G_SHARP_MINOR,
    A_MINOR,
    A_SHARP_MINOR,
    B_MINOR,
    UNKNOWN,
}
