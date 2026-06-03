package com.mobruji.song.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mobruji.admin.AdminTokenVerifier;
import com.mobruji.song.api.dto.SongResponse;
import com.mobruji.song.api.dto.SongStatsResponse;

import lombok.RequiredArgsConstructor;

import com.mobruji.song.application.SongService;
import com.mobruji.song.application.SongStatsService;

@RestController
@RequestMapping("/api/v1/songs")
@RequiredArgsConstructor
public class SongController {

    private final SongService songService;
    private final SongStatsService songStatsService;
    private final AdminTokenVerifier adminTokenVerifier;

    /**
     * Admin 통계 endpoint — {@code X-Admin-Token} 헤더 필수 (v0.3 P0, #224 #228).
     * 후속 PR에서 Spring Security 정식 도입 시 헤더 검증을 인가 필터로 옮긴다.
     */
    @GetMapping("/stats")
    public SongStatsResponse stats(
            @RequestHeader(value = "X-Admin-Token", required = false) final String adminToken) {
        adminTokenVerifier.verify(adminToken);
        return SongStatsResponse.from(songStatsService.getStats());
    }

    @GetMapping("/{id}")
    public SongResponse read(@PathVariable final Long id) {
        return SongResponse.from(songService.readById(id));
    }

    /**
     * 키워드 검색 — {@code keyword}가 비/공백/null이면 200 OK + 빈 배열 반환 (의도된 정책).
     * 상세 근거는 {@link SongService#searchByKeyword(String)} Javadoc.
     */
    @GetMapping
    public List<SongResponse> search(@RequestParam(name = "keyword", required = false) final String keyword) {
        return songService.searchByKeyword(keyword).stream()
                .map(SongResponse::from)
                .toList();
    }
}
