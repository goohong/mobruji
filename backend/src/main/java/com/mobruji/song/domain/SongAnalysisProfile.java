package com.mobruji.song.domain;

import java.util.Objects;

/**
 * 곡 1건의 분석 파생 속성 묶음 read-model. {@link Song} 컬럼들의 조회용 view 일 뿐 영속 엔티티가 아니다.
 *
 * <p>추천(voiceFit/mood/next-song)·연습·트렌딩 등 다운스트림 소비자(#1484/#1485/#1486/#1488/#1494)가
 * 읽는 단일 계약 표면이다 — 소비자는 {@code Song} 컬럼을 직접 읽지 않고 본 프로파일을 경유한다. 분석 필드가
 * 추가/개명되면 {@link #from(Song)} 한 지점만 갱신한다.
 *
 * <p>spec {@code song-analysis-data-and-consumers.md} §5-1 / §5-3. 도메인 용어는
 * {@code 06-domain-model.md} §4-1 등재.
 *
 * <p>{@code energy}/{@code mood}/{@code lowMidi}/{@code highMidi} 는 nullable — 소비자가 graceful
 * degrade 한다(해당 신호 가중 0). null 처리로 추천 결정성 회귀를 막는다(spec §5-3 계약 불변식).
 */
public record SongAnalysisProfile(
        Integer lowMidi,
        Integer highMidi,
        MusicalKey keyOriginal,
        Difficulty difficulty,
        Mood mood,
        Float energy,
        double metadataConfidence
) {

    public static SongAnalysisProfile from(final Song song) {
        Objects.requireNonNull(song, "song must not be null");
        return new SongAnalysisProfile(
                song.getLowMidi(),
                song.getHighMidi(),
                song.getKeyOriginal(),
                song.getDifficulty(),
                song.getMood(),
                song.getEnergy(),
                song.getMetadataConfidence());
    }
}
