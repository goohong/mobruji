package com.mobruji.song.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ChosungDeriverTest {

    @Test
    @DisplayName("of: 한글 음절을 초성열로 파생한다")
    void of_hangulSyllables() {
        assertThat(ChosungDeriver.of("발라드")).isEqualTo("ㅂㄹㄷ");
        assertThat(ChosungDeriver.of("벚꽃 엔딩")).isEqualTo("ㅂㄲ ㅇㄷ");
    }

    @Test
    @DisplayName("of: 영문/숫자는 소문자로 보존, 한글과 혼합도 결정적")
    void of_mixedAndAscii() {
        assertThat(ChosungDeriver.of("Day 6")).isEqualTo("day 6");
        assertThat(ChosungDeriver.of("가나다 ABC")).isEqualTo("ㄱㄴㄷ abc");
    }

    @Test
    @DisplayName("of: null 은 null 을 반환한다 (nullable 컬럼 파생)")
    void of_null() {
        assertThat(ChosungDeriver.of(null)).isNull();
    }

    @Test
    @DisplayName("isChosungQuery: 자모만(공백 허용)이면 true, 완성형/영문 섞이면 false")
    void isChosungQuery() {
        assertThat(ChosungDeriver.isChosungQuery("ㅂㄹㄷ")).isTrue();
        assertThat(ChosungDeriver.isChosungQuery("ㅂㄲ ㅇㄷ")).isTrue();
        assertThat(ChosungDeriver.isChosungQuery("발라")).isFalse();
        assertThat(ChosungDeriver.isChosungQuery("ㅂ라드")).isFalse();
        assertThat(ChosungDeriver.isChosungQuery("abc")).isFalse();
        assertThat(ChosungDeriver.isChosungQuery("")).isFalse();
        assertThat(ChosungDeriver.isChosungQuery(null)).isFalse();
    }
}
