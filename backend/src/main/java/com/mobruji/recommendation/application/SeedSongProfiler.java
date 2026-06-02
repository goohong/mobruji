package com.mobruji.recommendation.application;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.mobruji.recommendation.infrastructure.MusicalKeyMidiResolver;
import com.mobruji.song.domain.Mood;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;

/**
 * "부른 곡 기반 다음곡 추천"(#1486) 의 핵심 도출기. 사용자가 방금/과거에 부른 곡(seed)들로부터
 * 기존 추천 파이프라인이 요구하는 입력(음역대·대표 분위기·평균 BPM)을 추정한다.
 *
 * <p>설계 의도: 신규 점수 함수를 만들지 않고 seed 곡 → {@link CreateRecommendationCommand} 입력으로
 * 환산해 {@link RecommendationService#create} 의 스코어링·다양성·영속을 그대로 재사용한다.
 * "듣는 음악 ≠ 부르는 음악" 이라 평소 알고리즘이 안 통하는 노래방 맥락에서, 실제 부른 곡들이
 * 사용자의 가창 가능 음역과 취향을 가장 잘 대변한다는 가정(roadmap P-B).
 *
 * <p>도출 규칙:
 * <ul>
 * <li>음역대: 각 곡의 보컬 음역을 평균. {@code lowMidi/highMidi} 가 둘 다 있으면 그 값을, 없으면
 * {@code keyOriginal} root±7({@link MusicalKeyMidiResolver}) 로 추정. {@code UNKNOWN} 키 + midi 부재 곡은
 * 음역 평균에서 제외. 도출 가능한 곡이 하나도 없으면 아마추어 표준 음역
 * {@code [NEUTRAL_VOICE_LOW, NEUTRAL_VOICE_HIGH]} 로 폴백.</li>
 * <li>분위기: seed 들의 최빈 mood. 동률이면 enum 정의 순서(ordinal) 가 앞선 것 — 결정성 유지. 곡 mood 가
 * 전부 부재면 null(mood 신호 0).</li>
 * <li>BPM: seed 들의 비-null BPM 평균(반올림). 전부 부재면 null(mood 기반 default BPM 으로 폴백).</li>
 * </ul>
 *
 * <p>결정성: 같은 seed 집합은 항상 같은 프로필을 산출한다(평균·최빈 모두 순서 무관).
 */
@Component
public class SeedSongProfiler {

    /**
     * seed 곡에서 음역을 하나도 도출하지 못했을 때(전부 UNKNOWN 키 + midi 부재) 사용하는 표준 음역.
     * 아마추어 성인 보컬이 무리 없이 닿는 대략적 중역대 — C3(48) ~ C5(72).
     */
    static final int NEUTRAL_VOICE_LOW = 48;
    static final int NEUTRAL_VOICE_HIGH = 72;

    /**
     * seed 곡들로부터 추천 입력 프로필을 도출한다.
     *
     * @param sessionId      세션 식별자(필수).
     * @param seedSongs      사용자가 부른 곡(최소 1곡). 비어 있으면 안 된다 — 호출 측이 보장.
     * @param excludeSongIds 추가로 결과에서 제외할 곡(이미 부른 seed 는 자동 제외되므로 그 외 명시 제외분).
     * @return 기존 추천 파이프라인에 그대로 투입 가능한 커맨드.
     */
    public CreateRecommendationCommand profile(
            final String sessionId,
            final List<Song> seedSongs,
            final List<Long> excludeSongIds) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(seedSongs, "seedSongs must not be null");
        Objects.requireNonNull(excludeSongIds, "excludeSongIds must not be null");
        if (seedSongs.isEmpty()) {
            throw new IllegalArgumentException("seedSongs must not be empty");
        }

        final int[] voiceRange = deriveVoiceRange(seedSongs);
        final Mood mood = deriveDominantMood(seedSongs);
        final Integer preferredBpm = deriveAverageBpm(seedSongs);

        return new CreateRecommendationCommand(
                sessionId,
                voiceRange[0],
                voiceRange[1],
                mood,
                preferredBpm,
                excludeSongIds);
    }

    private static int[] deriveVoiceRange(final List<Song> seedSongs) {
        long lowSum = 0;
        long highSum = 0;
        int count = 0;
        for (final Song song : seedSongs) {
            final int[] songRange = songVoiceRange(song);
            if (songRange == null) {
                continue;
            }
            lowSum += songRange[0];
            highSum += songRange[1];
            count++;
        }
        if (count == 0) {
            return new int[]{NEUTRAL_VOICE_LOW, NEUTRAL_VOICE_HIGH};
        }
        final int low = Math.round((float) lowSum / count);
        final int high = Math.round((float) highSum / count);
        return new int[]{low, high};
    }

    /**
     * 곡 1개의 추정 보컬 음역 [low, high]. 측정 음역({@code lowMidi/highMidi}) 이 둘 다 있으면 그 값,
     * 없으면 키 root±7. 음역을 추정할 수 없으면(UNKNOWN 키 + midi 부재) null.
     */
    private static int[] songVoiceRange(final Song song) {
        final Integer lowMidi = song.getLowMidi();
        final Integer highMidi = song.getHighMidi();
        if (lowMidi != null && highMidi != null) {
            return new int[]{lowMidi, highMidi};
        }
        final MusicalKey keyOriginal = song.getKeyOriginal();
        final int rootMidi = MusicalKeyMidiResolver.rootMidi(keyOriginal);
        if (rootMidi < 0) {
            return null;
        }
        return new int[]{
                rootMidi + MusicalKeyMidiResolver.LOW_OFFSET,
                rootMidi + MusicalKeyMidiResolver.HIGH_OFFSET,
        };
    }

    /**
     * seed 들의 최빈 mood. 동률 시 enum ordinal 이 앞선 것을 선택해 결정성을 유지한다.
     * 곡 mood 가 전부 부재면 null.
     */
    private static Mood deriveDominantMood(final List<Song> seedSongs) {
        final Map<Mood, Integer> counts = new EnumMap<>(Mood.class);
        for (final Song song : seedSongs) {
            final Mood mood = song.getMood();
            if (mood != null) {
                counts.merge(mood, 1, Integer::sum);
            }
        }
        Mood dominant = null;
        int dominantCount = 0;
        for (final Mood mood : Mood.values()) {
            final int count = counts.getOrDefault(mood, 0);
            if (count > dominantCount) {
                dominant = mood;
                dominantCount = count;
            }
        }
        return dominant;
    }

    /**
     * seed 들의 비-null BPM 평균(반올림). 전부 부재면 null.
     */
    private static Integer deriveAverageBpm(final List<Song> seedSongs) {
        long sum = 0;
        int count = 0;
        for (final Song song : seedSongs) {
            final Integer bpm = song.getBpm();
            if (bpm != null) {
                sum += bpm;
                count++;
            }
        }
        if (count == 0) {
            return null;
        }
        return Math.round((float) sum / count);
    }
}
