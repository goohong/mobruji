package com.mobruji.song.infrastructure;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.Song;

public interface SongRepository extends JpaRepository<Song, Long> {

    /**
     * {@code MetadataSource} 별 곡 수 집계 row — admin 통계 API 용. JPQL group by 결과를 그대로 매핑한다.
     */
    interface MetadataSourceCount {
        MetadataSource getMetadataSource();

        long getCount();
    }

    @Query("select s.metadataSource as metadataSource, count(s) as count "
            + "from Song s group by s.metadataSource")
    List<MetadataSourceCount> countByMetadataSource();

    /**
     * 전체 곡의 평균 metadataConfidence. 곡이 0건이면 {@code null} 을 반환한다 (호출 측에서 0.0 fallback).
     */
    @Query("select avg(s.metadataConfidence) from Song s")
    Double findAverageMetadataConfidence();

    @Query("select s from Song s "
            + "where lower(s.title) like lower(concat('%', :keyword, '%')) "
            + "   or lower(s.artist) like lower(concat('%', :keyword, '%')) "
            + "order by s.title asc")
    List<Song> searchByKeyword(@Param("keyword") String keyword);

    /**
     * 시드 적재 시 row 단위 upsert를 위한 lookup. (title, artist) 조합은 시드 JSON의 자연키로 활용된다.
     * 운영 DB에 unique 제약이 걸려 있진 않지만(중복 데이터가 들어와도 검색은 동작) 시드 적재 컨텍스트에서는
     * 사실상 유일하다고 가정한다. 동명이곡(예: 다른 가수 동명) 가능성은 시드 큐레이션 단계에서 회피.
     */
    Optional<Song> findByTitleAndArtist(String title, String artist);

    /**
     * 정기 audio analysis backfill 후보 selective query (rev 15 #226).
     *
     * <p>대상 조건 (OR):
     * <ul>
     * <li>{@code metadataConfidence < threshold} — 신뢰도가 임계 미만이라 재분석 가치가 있는 곡</li>
     * <li>{@code metadataSource != AUDIO_ANALYSIS} — audio 분석으로 갱신된 적 없는 곡 (시드/외부/신규)</li>
     * </ul>
     *
     * <p>{@code findAll()} 후 in-memory 필터링 대비, 곡 수가 늘어나도 DB 측에서 미리 걸러
     * 불필요한 재분석을 막는다 (spec: {@code docs/features/audio-tooling-bootstrap.md}, ADR 0010).
     */
    @Query("select s from Song s "
            + "where s.metadataConfidence < :threshold "
            + "   or s.metadataSource <> com.mobruji.song.domain.MetadataSource.AUDIO_ANALYSIS "
            + "order by s.id asc")
    List<Song> findCandidatesForBackfill(@Param("threshold") double threshold);
}
