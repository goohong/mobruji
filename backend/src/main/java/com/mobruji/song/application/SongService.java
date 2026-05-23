package com.mobruji.song.application;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.mobruji.song.domain.Song;
import com.mobruji.song.domain.SongNotFoundException;
import com.mobruji.song.infrastructure.SongRepository;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class SongService {

    private final SongRepository songRepository;

    public Song readById(final Long id) {
        return songRepository.findById(id)
                .orElseThrow(() -> new SongNotFoundException(id));
    }

    /**
     * 키워드(제목/아티스트 부분일치) 검색.
     *
     * <p><b>빈/공백/null 키워드 정책</b>: 200 OK + 빈 배열({@code []}) 반환. 의도된 BE↔FE 계약이다.
     *
     * <ul>
     * <li>이유 1 — DoS/풀스캔 방지: Repository {@code searchByKeyword}는 {@code LIKE '%keyword%'}.
     * 키워드가 비면 {@code LIKE '%%'}로 전체 테이블 풀스캔이 된다.</li>
     * <li>이유 2 — FE 호출 비용 절약: fe({@code web/app/songs/page.tsx})는 입력 전에 빈 응답을 받아
     * "검색 결과 없음" 상태로 안전하게 fallback 한다. {@code 400 Bad Request} 대신 {@code []}로
     * 예외 처리 분기를 단순화한다.</li>
     * </ul>
     *
     * <p>같은 정책의 회귀 가드는 {@code SongServiceTest#searchByKeyword_emptyKeyword_returnsEmpty}.
     * 정책 변경 시 fe 페이지({@code web/app/songs/page.tsx} 헤더 주석 "BE 약속")도 동시 갱신 필요.
     *
     * <p>spec: {@code docs/features/song-metadata-source.md §5-2}.
     */
    public List<Song> searchByKeyword(final String keyword) {
        final String normalized = keyword == null ? "" : keyword.trim();
        if (normalized.isEmpty()) {
            return List.of();
        }
        return songRepository.searchByKeyword(normalized);
    }
}
