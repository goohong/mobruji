package com.mobruji.song.domain;

import java.util.Map;
import java.util.Optional;

/**
 * keyOriginal/genre 메타만으로 곡 보컬 음역대(lowMidi/highMidi)를 합리 추정한다 — 오디오 자체분석 인프라 부재
 * (#1778) 동안의 interim 추정기. 오디오 불필요·즉시 산출이라 추천 풀에서 빠진 음역대 미보유 곡을 곧장 진입시킨다.
 *
 * <p>휴리스틱:
 * <ul>
 * <li>키 root 를 옥타브 4 대역의 해당 음(MIDI 60=C4 ~ 71=B4)으로 잡아 멜로디 중심으로 본다 — 추천
 * {@code voiceRangeFit} 의 키 휴리스틱과 동일 가정.</li>
 * <li>중심 아래 완전5도(−7), 위 장6도(+9) 를 기본 음역폭(16 semitone)으로 둔다 — 노래방 가창 멜로디의 전형 음폭.</li>
 * <li>genre 별 최고음 오프셋으로 tessitura 차이를 보정한다 (발라드/락은 고음 climax 가 높음 → +3, 댄스/팝 +1,
 * 트로트/미상 0). 성별은 곡 메타에 없어 미사용.</li>
 * </ul>
 *
 * <p>입력 키가 {@link MusicalKey#UNKNOWN} 이면 중심을 잡을 수 없어 {@link Optional#empty()} (추정 불가 → skip).
 * 산출 음역은 항상 {@link VocalRangeEstimate#isVocalRangePlausible()} 합리성 가드(#1737)를 통과하도록 설계됐으며,
 * 만에 하나 통과하지 못하면 보수적으로 {@link Optional#empty()} 를 반환한다.
 */
public final class VocalRangeEstimator {

    /** 키 중심 아래 완전5도 — 추정 최저음 오프셋. */
    static final int LOW_OFFSET = -7;

    /** 키 중심 위 장6도 — 추정 최고음 기본 오프셋(genre 보정 전). */
    static final int HIGH_BASE_OFFSET = 9;

    /**
     * 추정값에 부여하는 낮은 신뢰도. 자체분석 임계(기본 0.6) 미만이라 추후
     * {@link Song#backfillFromAudioAnalysis(AudioAnalysisResult, double)} 가 그대로 덮어쓴다 — 자체분석 권위 우선.
     */
    static final double ESTIMATED_CONFIDENCE = 0.3;

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

    private VocalRangeEstimator() {
    }

    /**
     * 곡의 키/장르로 음역대를 추정한다. 추정 불가(키 UNKNOWN / 비합리 산출)면 {@link Optional#empty()}.
     *
     * @param keyOriginal 곡 원곡 키 (필수)
     * @param genre       곡 장르 (null 허용 — 미상 시 오프셋 0)
     * @return 합리성 가드를 통과한 추정 음역, 추정 불가면 empty
     */
    public static Optional<VocalRangeEstimate> estimate(final MusicalKey keyOriginal, final String genre) {
        if (keyOriginal == null || keyOriginal == MusicalKey.UNKNOWN) {
            return Optional.empty();
        }
        final int root = ROOT_MIDI.getOrDefault(keyOriginal, 60);
        final int lowMidi = root + LOW_OFFSET;
        final int highMidi = root + HIGH_BASE_OFFSET + highOffsetForGenre(genre);
        final VocalRangeEstimate estimate = new VocalRangeEstimate(lowMidi, highMidi, ESTIMATED_CONFIDENCE);
        if (!estimate.isVocalRangePlausible()) {
            return Optional.empty();
        }
        return Optional.of(estimate);
    }

    /**
     * genre 별 최고음 오프셋 — 고음 tessitura 가 높은 장르일수록 climax 가 높다는 가정. 미상/null 은 0(기본 음역폭).
     */
    private static int highOffsetForGenre(final String genre) {
        if (genre == null) {
            return 0;
        }
        return switch (genre.trim()) {
            case "발라드", "락", "록" -> 3;
            case "댄스", "팝", "R&B", "알앤비" -> 1;
            default -> 0;
        };
    }
}
