package com.mobruji.song.domain;

import java.time.LocalDateTime;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "song", indexes = {
        @Index(name = "ix_song_title", columnList = "title"),
        @Index(name = "ix_song_artist", columnList = "artist"),
        @Index(name = "uk_song_isrc", columnList = "isrc", unique = true),
})
@AllArgsConstructor(access = AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Song {

    /**
     * 가창 난이도 자동 분류 임계값. fe(`web/lib/difficulty.ts`)와 1:1 일치.
     *
     * <ul>
     * <li>HARD: highMidi ≥ 76 (E5) 또는 (highMidi - lowMidi) ≥ 17 반음</li>
     * <li>NORMAL: 71 ≤ highMidi ≤ 75 (B4 ~ D#5)</li>
     * <li>EASY: highMidi < 71</li>
     * </ul>
     *
     * 기준 영속화는 ADR 0007 후보(본진 후속).
     */
    public static final int HIGH_HARD_THRESHOLD = 76;
    public static final int HIGH_NORMAL_THRESHOLD = 71;
    public static final int SPAN_HARD_THRESHOLD = 17;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 200)
    private String artist;

    @Column(name = "release_year")
    private Integer releaseYear;

    @Enumerated(EnumType.STRING)
    @Column(name = "key_original", nullable = false, length = 24)
    private MusicalKey keyOriginal;

    @Column
    private Integer bpm;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private Mood mood;

    @Column(length = 32)
    private String language;

    @Column(length = 32)
    private String genre;

    @Column(name = "tj_number", length = 16)
    private String tjNumber;

    @Column(name = "ky_number", length = 16)
    private String kyNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "metadata_source", nullable = false, length = 24)
    private MetadataSource metadataSource;

    /**
     * 외부 분석/큐레이션 시 채워지는 곡의 International Standard Recording Code (ISO 3901, 12자).
     * 수기 시드는 null. 글로벌 유일 식별자라 DB 레벨 {@code UNIQUE} 인덱스로 보호한다 — null 은
     * MySQL 8.4 UNIQUE 가 다중 허용하므로 충돌 없음.
     *
     * <p>spec {@code song-metadata-source.md} §5-1 잠정 필드를 본진 컬럼으로 promote.
     */
    @Column(length = 12)
    private String isrc;

    /**
     * 메타데이터 신뢰도 (0.0~1.0). 기본 1.0 = {@link MetadataSource#MANUAL_SEED} 수기 입력 신뢰도.
     * {@link #backfillFromAudioAnalysis} 시 {@link AudioAnalysisResult#confidence()} 가 저장된다.
     *
     * <p>fe/추천 알고리즘은 본 값을 입력으로 쓰지 않는다 — 결정성 회귀 없음. 큐레이션/UX 신호용.
     */
    @Column(name = "metadata_confidence", nullable = false)
    private double metadataConfidence;

    /**
     * 곡 보컬 멜로디의 최저음 (MIDI note number).
     * nullable — 시드/외부 출처에 따라 미보유 가능. {@link #difficulty} 자동 계산은
     * lowMidi/highMidi가 둘 다 있을 때만 수행한다.
     */
    @Column(name = "low_midi")
    private Integer lowMidi;

    /**
     * 곡 보컬 멜로디의 최고음 (MIDI note number).
     */
    @Column(name = "high_midi")
    private Integer highMidi;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private Difficulty difficulty;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    public static Song create(
            final String title,
            final String artist,
            final Integer releaseYear,
            final MusicalKey keyOriginal,
            final Integer bpm,
            final Mood mood,
            final String language,
            final String genre,
            final String tjNumber,
            final String kyNumber,
            final MetadataSource metadataSource,
            final String isrc,
            final Double metadataConfidence,
            final Integer lowMidi,
            final Integer highMidi,
            final Difficulty difficulty) {
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(artist, "artist must not be null");
        Objects.requireNonNull(keyOriginal, "keyOriginal must not be null");
        Objects.requireNonNull(metadataSource, "metadataSource must not be null");
        if (title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        if (artist.isBlank()) {
            throw new IllegalArgumentException("artist must not be blank");
        }
        if (bpm != null && (bpm < 30 || bpm > 300)) {
            throw new IllegalArgumentException("bpm out of plausible range [30, 300]: " + bpm);
        }
        if (lowMidi != null && highMidi != null && lowMidi > highMidi) {
            throw new IllegalArgumentException(
                    "lowMidi must not exceed highMidi: lowMidi=" + lowMidi + ", highMidi=" + highMidi);
        }
        if (metadataConfidence != null && (metadataConfidence < 0.0 || metadataConfidence > 1.0)) {
            throw new IllegalArgumentException(
                    "metadataConfidence out of [0.0, 1.0]: " + metadataConfidence);
        }
        // metadataConfidence 미명시 시 기본값 1.0 (MANUAL_SEED 수기 입력 신뢰도).
        final double resolvedConfidence = metadataConfidence != null ? metadataConfidence : 1.0;
        // difficulty가 명시되지 않으면 lowMidi/highMidi로 자동 분류 (둘 다 있을 때만).
        final Difficulty resolvedDifficulty = difficulty != null
                ? difficulty
                : (lowMidi != null && highMidi != null ? deriveDifficulty(lowMidi, highMidi) : null);
        final LocalDateTime now = LocalDateTime.now();
        return new Song(
                null, title, artist, releaseYear, keyOriginal, bpm, mood, language, genre,
                tjNumber, kyNumber, metadataSource, isrc, resolvedConfidence,
                lowMidi, highMidi, resolvedDifficulty, now, now);
    }

    /**
     * 곡 음역(MIDI)으로부터 가창 난이도를 분류한다. fe `web/lib/difficulty.ts`와 동일한 규칙.
     *
     * <ul>
     * <li>HARD: highMidi ≥ 76 또는 (highMidi - lowMidi) ≥ 17</li>
     * <li>NORMAL: 71 ≤ highMidi ≤ 75</li>
     * <li>EASY: highMidi < 71</li>
     * </ul>
     *
     * 잘못된 입력(예: lowMidi > highMidi)에 대한 검증은 호출 측에서. 본 메서드는 단순 분류만.
     */
    public static Difficulty deriveDifficulty(final int lowMidi, final int highMidi) {
        final int span = highMidi - lowMidi;
        if (highMidi >= HIGH_HARD_THRESHOLD || span >= SPAN_HARD_THRESHOLD) {
            return Difficulty.HARD;
        }
        if (highMidi >= HIGH_NORMAL_THRESHOLD) {
            return Difficulty.NORMAL;
        }
        return Difficulty.EASY;
    }

    /**
     * 시드 재적재용 partial update — **null인 필드만** 새 값으로 채운다. 이미 값이 있는 필드는 보존
     * (운영 중 수정된 데이터를 덮지 않기 위함).
     *
     * <p>lowMidi/highMidi가 한 번이라도 채워진 후에는 difficulty도 자동 분류한다(둘 다 값이 있고
     * difficulty가 여전히 null인 경우). difficulty 인자가 명시되면 그 값을 우선한다.
     *
     * <p>실제로 한 필드라도 변경됐을 때만 `updatedAt`을 갱신한다(불필요한 dirty checking 회피).
     *
     * @return 변경 발생 여부 — 호출 측 로깅에 사용
     */
    public boolean backfillMissingFields(
            final Integer newLowMidi,
            final Integer newHighMidi,
            final Difficulty newDifficulty) {
        boolean changed = false;
        if (this.lowMidi == null && newLowMidi != null) {
            this.lowMidi = newLowMidi;
            changed = true;
        }
        if (this.highMidi == null && newHighMidi != null) {
            this.highMidi = newHighMidi;
            changed = true;
        }
        if (this.lowMidi != null && this.highMidi != null && this.lowMidi > this.highMidi) {
            throw new IllegalArgumentException(
                    "lowMidi must not exceed highMidi: lowMidi=" + this.lowMidi
                            + ", highMidi=" + this.highMidi);
        }
        if (this.difficulty == null) {
            if (newDifficulty != null) {
                this.difficulty = newDifficulty;
                changed = true;
            } else if (this.lowMidi != null && this.highMidi != null) {
                this.difficulty = deriveDifficulty(this.lowMidi, this.highMidi);
                changed = true;
            }
        }
        if (changed) {
            this.updatedAt = LocalDateTime.now();
        }
        return changed;
    }

    /**
     * Python audio analysis tool 산출값으로 lowMidi/highMidi 를 **갱신** 한다. {@link #backfillMissingFields}
     * 가 "null 만 채움" 이라면 본 메서드는 "신뢰도 충족 시 기존 값을 덮어쓰기".
     *
     * <p>spec {@code audio-tooling-bootstrap.md} PR C — 시드 30곡의 수기 음역대를 audio 분석값으로 정확화.
     * 다음 정책:
     *
     * <ul>
     * <li>{@code result.confidence() < threshold} → no-op (수기 값 보존, false 반환).</li>
     * <li>임계 통과 시 lowMidi/highMidi 를 결과로 갱신, {@link Difficulty} 를 재계산, {@link MetadataSource}
     * 를 {@link MetadataSource#AUDIO_ANALYSIS} 로 갱신.</li>
     * </ul>
     *
     * <p>본 메서드는 추천 알고리즘(점수 계산) 입력 데이터만 바꿀 뿐, 알고리즘 코드 자체에는 영향이 없다 — 결정성 회귀 없음.
     *
     * @param result              Python tool 산출 결과
     * @param confidenceThreshold 적용 임계 (0.0~1.0). 작업 지시 기본값 0.6.
     * @return 실제로 한 필드라도 변경됐는지 여부
     */
    public boolean backfillFromAudioAnalysis(
            final AudioAnalysisResult result,
            final double confidenceThreshold) {
        Objects.requireNonNull(result, "result must not be null");
        if (confidenceThreshold < 0.0 || confidenceThreshold > 1.0) {
            throw new IllegalArgumentException(
                    "confidenceThreshold out of [0.0, 1.0]: " + confidenceThreshold);
        }
        if (result.confidence() < confidenceThreshold) {
            return false;
        }
        boolean changed = false;
        if (this.lowMidi == null || this.lowMidi != result.lowMidi()) {
            this.lowMidi = result.lowMidi();
            changed = true;
        }
        if (this.highMidi == null || this.highMidi != result.highMidi()) {
            this.highMidi = result.highMidi();
            changed = true;
        }
        final Difficulty newDifficulty = deriveDifficulty(this.lowMidi, this.highMidi);
        if (this.difficulty != newDifficulty) {
            this.difficulty = newDifficulty;
            changed = true;
        }
        if (this.metadataSource != MetadataSource.AUDIO_ANALYSIS) {
            this.metadataSource = MetadataSource.AUDIO_ANALYSIS;
            changed = true;
        }
        // 분석 결과의 confidence 를 그대로 저장 — 큐레이션/UX 신호용 (추천 알고리즘 무관).
        if (this.metadataConfidence != result.confidence()) {
            this.metadataConfidence = result.confidence();
            changed = true;
        }
        if (changed) {
            this.updatedAt = LocalDateTime.now();
        }
        return changed;
    }
}
