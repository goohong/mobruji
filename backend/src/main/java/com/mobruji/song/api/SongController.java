package com.mobruji.song.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mobruji.admin.AdminTokenVerifier;
import com.mobruji.song.api.dto.SearchSuggestResponse;
import com.mobruji.song.api.dto.SongListResponse;
import com.mobruji.song.api.dto.SongResponse;
import com.mobruji.song.api.dto.SongStatsResponse;

import lombok.RequiredArgsConstructor;

import com.mobruji.song.application.SongSearchCriteria;
import com.mobruji.song.application.SongSearchService;
import com.mobruji.song.application.SongService;
import com.mobruji.song.application.SongStatsService;

@RestController
@RequestMapping("/api/v1/songs")
@RequiredArgsConstructor
public class SongController {

    private static final int SUGGEST_MAX_LIMIT = 20;

    private final SongService songService;
    private final SongSearchService songSearchService;
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
     * 곡 검색·필터 — 제목/가수 검색(완성형·초성) + 다축 필터(genre/difficulty/mood/음역 적합) +
     * 관련도 정렬 + 페이지네이션 (spec {@code song-search-and-filter.md} §5-2).
     *
     * <p>{@code keyword} 가 명시+비/공백이면 빈 {@code items} (기존 정책 유지). 검증 위반(size 초과,
     * fit both-or-neither, 미정의 enum 등)은 400 ({@link SongSearchCriteria}).
     */
    @GetMapping
    public SongListResponse search(
            @RequestParam(name = "keyword", required = false) final String keyword,
            @RequestParam(name = "genre", required = false) final String genre,
            @RequestParam(name = "difficulty", required = false) final String difficulty,
            @RequestParam(name = "mood", required = false) final String mood,
            @RequestParam(name = "fitLow", required = false) final Integer fitLow,
            @RequestParam(name = "fitHigh", required = false) final Integer fitHigh,
            @RequestParam(name = "sort", required = false) final String sort,
            @RequestParam(name = "page", defaultValue = "0") final int page,
            @RequestParam(name = "size", defaultValue = "20") final int size) {
        final SongSearchCriteria criteria = SongSearchCriteria.of(
                keyword, genre, difficulty, mood, fitLow, fitHigh, sort, page, size);
        return SongListResponse.from(songSearchService.search(criteria));
    }

    /**
     * 자동완성 경량 제안 — {@code q} prefix relevance 정렬 상위 N {@code {id,title,artist}}. q 가
     * 비/공백이면 빈 items. {@code limit} 은 [1, {@value #SUGGEST_MAX_LIMIT}] 로 클램프.
     */
    @GetMapping("/suggest")
    public SearchSuggestResponse suggest(
            @RequestParam(name = "q", required = false) final String query,
            @RequestParam(name = "limit", defaultValue = "8") final int limit) {
        final int clampedLimit = Math.max(1, Math.min(limit, SUGGEST_MAX_LIMIT));
        return SearchSuggestResponse.from(songSearchService.suggest(query, clampedLimit));
    }
}
