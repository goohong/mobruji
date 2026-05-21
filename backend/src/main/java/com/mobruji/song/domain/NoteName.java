package com.mobruji.song.domain;

/**
 * MIDI note number → scientific pitch notation 표기 변환.
 *
 * <p>fe(`web/lib/notes.ts`)와 표기 컨벤션을 1:1 일치시킨다 (샵 사용, 플랫 없음).
 * 예: MIDI 60 → "C4", 61 → "C#4", 73 → "C#5".
 *
 * <p>표기 규약:
 * <ul>
 * <li>옥타브: MIDI 60 = C4, MIDI 12 = C0.</li>
 * <li>샤프 기호는 ASCII "#" — Unicode "♯" 미사용 (fe와 일치).</li>
 * </ul>
 *
 * <p>책임: 변환만 수행. 검증은 호출 측에서. 음수/유효 범위 밖 MIDI도 그대로 변환한다.
 *
 * <p>본 클래스를 {@code song.domain}에 두는 이유: 외부 의존 없는 순수 자바 헬퍼이며
 * 곡 음역의 도메인적 표현(노트명)을 다루므로 ADR 0005 §domain 책임에 부합.
 */
public final class NoteName {

    private static final String[] PITCH_CLASSES = {
            "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"
    };

    private NoteName() {
    }

    /**
     * MIDI note number를 "C4" / "F#5" 같은 과학적 음표 표기로 변환한다.
     *
     * @param midi MIDI note number (예: 60 = C4)
     * @return scientific pitch notation 문자열
     */
    public static String of(final int midi) {
        final int pitchClassIndex = ((midi % 12) + 12) % 12;
        final int octave = Math.floorDiv(midi, 12) - 1;
        return PITCH_CLASSES[pitchClassIndex] + octave;
    }
}
