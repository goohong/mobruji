package com.mobruji.song.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.mobruji.song.domain.ChosungDeriver;
import com.mobruji.song.domain.SearchRelevance;
import com.mobruji.song.domain.Song;
import com.mobruji.song.infrastructure.SongRepository;
import com.mobruji.song.infrastructure.SongSpecifications;

/**
 * 곡 검색(SongSearch) — 제목/가수 텍스트 + 다축 필터 + 관련도 정렬 + 페이지네이션 (spec
 * {@code song-search-and-filter.md} §5). 추천 알고리즘과 완전 분리된 read-only 유스케이스.
 *
 * <p>Phase 1 색인 전략 (§5-7): 완성형 keyword = 정규화 LIKE + app-side relevance tier, 초성열 =
 * {@code titleChosung LIKE 'kwc%'} prefix(index). v0.2 카탈로그 규모에서 필터 결과를 메모리로
 * 정렬·페이지한다 — FULLTEXT ngram 은 곡 수 증가 메트릭 관측 후 Phase 2.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class SongSearchService {

    private final SongRepository songRepository;

    /**
     * 검증된 {@link SongSearchCriteria} 로 검색·필터·정렬·페이지네이션을 수행한다.
     */
    public SongSearchPage search(final SongSearchCriteria criteria) {
        if (criteria.isExplicitlyEmptyKeyword()) {
            // keyword 명시 + 비/공백 → 빈 결과 (기존 정책 유지, spec §3).
            return new SongSearchPage(List.of(), criteria.page(), criteria.size(), 0L, false);
        }

        final Specification<Song> specification = buildSpecification(criteria);
        final List<Song> matched = specification == null
                ? songRepository.findAll()
                : songRepository.findAll(specification);

        matched.sort(comparatorFor(criteria));

        final long totalCount = matched.size();
        final int from = Math.min(criteria.page() * criteria.size(), matched.size());
        final int to = Math.min(from + criteria.size(), matched.size());
        final boolean hasNext = to < totalCount;
        return new SongSearchPage(
                List.copyOf(matched.subList(from, to)),
                criteria.page(), criteria.size(), totalCount, hasNext);
    }

    /**
     * 자동완성 경량 제안 — keyword prefix relevance 정렬 후 상위 {@code limit} 곡 (spec §5-2). q 가
     * 비/공백이면 빈 결과.
     */
    public List<Song> suggest(final String query, final int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        final String trimmed = query.trim();
        final boolean chosung = ChosungDeriver.isChosungQuery(trimmed);
        final Specification<Song> specification = chosung
                ? SongSpecifications.keywordChosung(trimmed)
                : SongSpecifications.keywordCompleted(trimmed);
        return songRepository.findAll(specification).stream()
                .sorted(SearchRelevance.comparator(trimmed, chosung))
                .limit(limit)
                .toList();
    }

    private static Specification<Song> buildSpecification(final SongSearchCriteria criteria) {
        final List<Specification<Song>> specs = new ArrayList<>();
        if (criteria.hasKeyword()) {
            specs.add(criteria.isChosung()
                    ? SongSpecifications.keywordChosung(criteria.keyword())
                    : SongSpecifications.keywordCompleted(criteria.keyword()));
        }
        if (!criteria.genres().isEmpty()) {
            specs.add(SongSpecifications.genreIn(criteria.genres()));
        }
        if (!criteria.difficulties().isEmpty()) {
            specs.add(SongSpecifications.difficultyIn(criteria.difficulties()));
        }
        if (!criteria.moods().isEmpty()) {
            specs.add(SongSpecifications.moodIn(criteria.moods()));
        }
        if (criteria.fitLow() != null) {
            specs.add(SongSpecifications.voiceFit(criteria.fitLow(), criteria.fitHigh()));
        }
        Specification<Song> combined = null;
        for (final Specification<Song> specification : specs) {
            combined = combined == null ? specification : combined.and(specification);
        }
        return combined;
    }

    private static Comparator<Song> comparatorFor(final SongSearchCriteria criteria) {
        return switch (criteria.sort()) {
            case RELEVANCE -> SearchRelevance.comparator(criteria.keyword(), criteria.isChosung());
            case TITLE -> Comparator.comparing(Song::getTitle);
            case RELEASE_YEAR -> Comparator
                    .comparing(Song::getReleaseYear, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(Song::getTitle);
        };
    }
}
