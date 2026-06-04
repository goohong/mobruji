package com.mobruji.song.application.albumcover;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * {@link AlbumCoverSearchTerms} 단위 테스트. ADR 0029 가 fuzzy 매칭 약점으로 지목한 괄호 주석 / {@code feat.}
 * 표기 / 대괄호 부가정보를 검색 전에 보수적으로 제거하는지 검증한다.
 */
class AlbumCoverSearchTermsTest {

    @ParameterizedTest
    @DisplayName("normalize: 괄호류 부가정보 segment 를 제거하고 공백을 정돈한다")
    @CsvSource(delimiter = '|', value = {
            "벚꽃 엔딩 (Cherry Blossom Ending)|벚꽃 엔딩",
            "Dynamite [Official Audio]|Dynamite",
            "좋은 날 (Inst.)|좋은 날",
            "Spring Day <Live>|Spring Day",
            "어떤날 【MV】|어떤날",
            "Title {Remastered}|Title",
    })
    void normalize_stripsBracketedNoise(final String raw, final String expected) {
        assertThat(AlbumCoverSearchTerms.normalize(raw)).isEqualTo(expected);
    }

    @ParameterizedTest
    @DisplayName("normalize: 공백으로 분리된 feat./ft./featuring 협연 절을 제거한다")
    @CsvSource(delimiter = '|', value = {
            "Heart feat. Crush|Heart",
            "Song ft. IU|Song",
            "Track featuring Zico|Track",
            "Hello FEAT. World|Hello",
    })
    void normalize_stripsFeatClause(final String raw, final String expected) {
        assertThat(AlbumCoverSearchTerms.normalize(raw)).isEqualTo(expected);
    }

    @Test
    @DisplayName("normalize: 괄호와 feat 절이 함께 있어도 모두 제거된다")
    void normalize_stripsBracketAndFeatTogether() {
        assertThat(AlbumCoverSearchTerms.normalize("좋은 날 (Acoustic) feat. Crush"))
                .isEqualTo("좋은 날");
    }

    @Test
    @DisplayName("normalize: 노이즈 없는 원문은 trim 만 적용하고 그대로 둔다")
    void normalize_cleanInput_unchanged() {
        assertThat(AlbumCoverSearchTerms.normalize("  벚꽃 엔딩  ")).isEqualTo("벚꽃 엔딩");
        assertThat(AlbumCoverSearchTerms.normalize("Dynamite")).isEqualTo("Dynamite");
    }

    @Test
    @DisplayName("normalize: 'feature' 처럼 단어 일부는 feat 절로 오인하지 않는다")
    void normalize_doesNotStripWordContainingFeat() {
        assertThat(AlbumCoverSearchTerms.normalize("Feature Presentation"))
                .isEqualTo("Feature Presentation");
    }

    @Test
    @DisplayName("normalize: 정규화 결과가 blank 면 원문(trim) 으로 fallback 한다")
    void normalize_allNoise_fallsBackToOriginal() {
        assertThat(AlbumCoverSearchTerms.normalize("(Inst.)")).isEqualTo("(Inst.)");
    }

    @Test
    @DisplayName("normalize: null 은 null 그대로 반환한다 (호출 측 blank 검사 보존)")
    void normalize_null_returnsNull() {
        assertThat(AlbumCoverSearchTerms.normalize(null)).isNull();
    }
}
