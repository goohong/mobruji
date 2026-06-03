package com.mobruji.song.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SearchRelevanceTest {

    private static Song song(final String title, final String artist) {
        return Song.builder()
                .title(title).artist(artist)
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .lowMidi(55).highMidi(67)
                .build();
    }

    @Test
    @DisplayName("tier: 제목 정확 > 제목 prefix > 제목 부분 > 가수 순")
    void tier_completedForm() {
        assertThat(SearchRelevance.tier(song("발라드", "가수"), "발라드", false))
                .isEqualTo(SearchRelevance.TIER_TITLE_EXACT);
        assertThat(SearchRelevance.tier(song("발라드 노래", "가수"), "발라드", false))
                .isEqualTo(SearchRelevance.TIER_TITLE_PREFIX);
        assertThat(SearchRelevance.tier(song("내 발라드 노래", "가수"), "발라드", false))
                .isEqualTo(SearchRelevance.TIER_TITLE_CONTAINS);
        assertThat(SearchRelevance.tier(song("다른 노래", "발라드보이즈"), "발라드", false))
                .isEqualTo(SearchRelevance.TIER_ARTIST);
    }

    @Test
    @DisplayName("comparator: tier 우선 → 동 tier 제목 가나다순 tie-break (결정성)")
    void comparator_stableTieBreak() {
        final Song titlePrefix = song("발라드 가", "x");
        final Song artistHit = song("zzz", "발라드보이즈");
        final Song titleContainsB = song("나 발라드", "y");
        final Song titleContainsA = song("가 발라드", "y");

        final List<Song> sorted = java.util.stream.Stream
                .of(artistHit, titleContainsB, titlePrefix, titleContainsA)
                .sorted(SearchRelevance.comparator("발라드", false))
                .toList();

        assertThat(sorted).extracting(Song::getTitle)
                .containsExactly("발라드 가", "가 발라드", "나 발라드", "zzz");
    }

    @Test
    @DisplayName("tier: 초성 검색은 초성열 prefix 기준")
    void tier_chosung() {
        final Song hit = song("발라드", "가수");
        // titleChosung = "ㅂㄹㄷ"
        assertThat(SearchRelevance.tier(hit, "ㅂㄹㄷ", true))
                .isEqualTo(SearchRelevance.TIER_TITLE_EXACT);
        assertThat(SearchRelevance.tier(hit, "ㅂㄹ", true))
                .isEqualTo(SearchRelevance.TIER_TITLE_PREFIX);
        assertThat(SearchRelevance.tier(hit, "ㅈㅅ", true))
                .isEqualTo(SearchRelevance.TIER_NONE);
    }
}
