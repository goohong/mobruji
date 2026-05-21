package com.mobruji.recommendation;

import java.util.Map;

import com.mobruji.song.MusicalKey;

/**
 * MusicalKey → 곡의 "전형적인 보컬 음역 중심" MIDI 매핑.
 * v1 추천에서 voiceRangeFit 계산 시 사용한다.
 *
 * v1 휴리스틱: 키 root(C/D/E ...)를 옥타브 4의 해당 음(MIDI 60=C4)으로 잡고,
 * 보컬 멜로디는 보통 root±7 semitones(완전 5도) 안쪽에서 움직인다고 가정.
 * 메이저/마이너 구분은 root 위치에 영향 없음 (단순화).
 */
public final class MusicalKeyMidiResolver {

    public static final int LOW_OFFSET = -7;
    public static final int HIGH_OFFSET = 7;

    private static final Map<MusicalKey, Integer> ROOT_MIDI = Map.ofEntries(
            Map.entry(MusicalKey.C_MAJOR, 60),
            Map.entry(MusicalKey.C_MINOR, 60),
            Map.entry(MusicalKey.C_SHARP_MAJOR, 61),
            Map.entry(MusicalKey.C_SHARP_MINOR, 61),
            Map.entry(MusicalKey.D_MAJOR, 62),
            Map.entry(MusicalKey.D_MINOR, 62),
            Map.entry(MusicalKey.D_SHARP_MAJOR, 63),
            Map.entry(MusicalKey.D_SHARP_MINOR, 63),
            Map.entry(MusicalKey.E_MAJOR, 64),
            Map.entry(MusicalKey.E_MINOR, 64),
            Map.entry(MusicalKey.F_MAJOR, 65),
            Map.entry(MusicalKey.F_MINOR, 65),
            Map.entry(MusicalKey.F_SHARP_MAJOR, 66),
            Map.entry(MusicalKey.F_SHARP_MINOR, 66),
            Map.entry(MusicalKey.G_MAJOR, 67),
            Map.entry(MusicalKey.G_MINOR, 67),
            Map.entry(MusicalKey.G_SHARP_MAJOR, 68),
            Map.entry(MusicalKey.G_SHARP_MINOR, 68),
            Map.entry(MusicalKey.A_MAJOR, 69),
            Map.entry(MusicalKey.A_MINOR, 69),
            Map.entry(MusicalKey.A_SHARP_MAJOR, 70),
            Map.entry(MusicalKey.A_SHARP_MINOR, 70),
            Map.entry(MusicalKey.B_MAJOR, 71),
            Map.entry(MusicalKey.B_MINOR, 71));

    private MusicalKeyMidiResolver() {
    }

    /**
     * 곡 키의 추정 보컬 음역 중심 MIDI. UNKNOWN이면 -1 (특수 처리 용).
     */
    public static int rootMidi(final MusicalKey musicalKey) {
        if (musicalKey == MusicalKey.UNKNOWN) {
            return -1;
        }
        return ROOT_MIDI.getOrDefault(musicalKey, 60);
    }
}
