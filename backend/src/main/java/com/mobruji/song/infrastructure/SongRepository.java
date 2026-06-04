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
     * 메타-only 임포트 멱등성 보조 lookup — {@code isrc} 글로벌 유일 식별자로 기존 곡을 찾는다 (spec
     * {@code song-catalog-expansion.md} §3). 외부 출처가 ISRC 를 제공하면 (title, artist) 표기 차이와
     * 무관하게 중복 import 를 막고, DB UNIQUE(isrc) 제약 위반을 사전에 회피한다.
     */
    Optional<Song> findByIsrc(String isrc);

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

    /**
     * 추천 후보 universe — 음역대 보유({@code lowMidi}/{@code highMidi} 둘 다 not-null) 곡만 반환한다 (이슈 #1744).
     *
     * <p>음역대 미보유 곡은 {@link com.mobruji.recommendation.application.RecommendationScorer} 가 voiceFit 을
     * 실측 band 로 산정할 수 없어 키 휴리스틱/중립(0.5)으로 떨어진다. 키마저 UNKNOWN 인 임포트 곡이 다수면 추천 풀이
     * voiceFit=0.5 로 오염돼 "추천 곡 전부 음역적합 50%" 사고가 났다. 후보 단계에서 음역대 보유 곡만 추려 voiceFit 이
     * 실제로 변별되는 곡만 추천하며, backfill 로 음역대가 채워진 곡은 자동으로 다시 후보에 편입된다.
     *
     * <p>{@code id} 오름차순 정렬로 jitter 배정 입력 순서를 결정적으로 유지한다(spec §3 비기능 — 결정성).
     */
    @Query("select s from Song s "
            + "where s.lowMidi is not null and s.highMidi is not null "
            + "order by s.id asc")
    List<Song> findAllWithVocalRange();

    /**
     * 음역대 미보유 곡 selective query — {@code lowMidi IS NULL OR highMidi IS NULL} 인 곡만 반환한다 (이슈 #1739).
     *
     * <p>{@link #findCandidatesForBackfill(double)} 가 신뢰도/출처 기준의 넓은 후보(이미 음역대가 있는 곡 포함)를
     * 잡는 반면, 본 query 는 추천 풀에서 빠진 "음역대 미보유 곡" 만 정밀 타겟한다. YouTube ytsearch 자동매칭 +
     * 자체분석으로 음역대를 채워 추천 진입시키는 backfill 의 대상이며, 결과가 곧 "음역대 보유 곡수 증가" 로 측정된다.
     *
     * <p>{@code id} 오름차순 정렬로 chunk(limit) 반복 실행 시 결정적 진행을 보장한다.
     */
    @Query("select s from Song s "
            + "where s.lowMidi is null or s.highMidi is null "
            + "order by s.id asc")
    List<Song> findMissingVocalRange();

    /**
     * 앨범 커버 backfill 대상 selective query — {@code albumCoverUrl IS NULL} 인 곡만 반환한다.
     *
     * <p>이슈 #322 — iTunes Search API backfill 은 selective 하게 누락된 곡만 호출해 외부 API 호출
     * 횟수를 최소화한다. 이미 채워진 곡은 큐레이터 수정/이전 backfill 결과로 간주하고 보존.
     */
    @Query("select s from Song s where s.albumCoverUrl is null order by s.id asc")
    List<Song> findMissingAlbumCover();

    /**
     * MusicBrainz backfill 대상 selective query — {@code mbId IS NULL} 인 곡만 반환한다 (spec
     * {@code musicbrainz-integration.md} §5-2). {@code metadataConfidence} 오름차순으로 정렬해 신뢰도가 낮아
     * 재검증 가치가 높은 곡을 우선 처리한다 (동률은 {@code id} 오름차순으로 결정적).
     */
    @Query("select s from Song s where s.mbId is null "
            + "order by s.metadataConfidence asc, s.id asc")
    List<Song> findMissingMbId();

    /**
     * mbId UNIQUE 충돌 사전 회피용 lookup — 같은 MusicBrainz recording 이 서로 다른 두 곡에 매칭되는 사고를
     * 막고 DB UNIQUE(mb_id) 제약 위반을 미리 회피한다 (spec {@code musicbrainz-integration.md} §5-5).
     */
    Optional<Song> findByMbId(String mbId);
}
