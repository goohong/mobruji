package com.mobruji.song;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SongRepository extends JpaRepository<Song, Long> {

    @Query("select s from Song s "
            + "where lower(s.title) like lower(concat('%', :keyword, '%')) "
            + "   or lower(s.artist) like lower(concat('%', :keyword, '%')) "
            + "order by s.title asc")
    List<Song> searchByKeyword(@Param("keyword") String keyword);
}
