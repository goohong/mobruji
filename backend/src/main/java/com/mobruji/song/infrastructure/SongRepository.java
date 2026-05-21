package com.mobruji.song.infrastructure;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.mobruji.song.domain.Song;

public interface SongRepository extends JpaRepository<Song, Long> {

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
}
