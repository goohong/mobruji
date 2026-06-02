package com.mobruji.recommendation.domain;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * "부른 곡 기반 다음곡 추천"(#1486) 에서 요청한 {@code seedSongIds} 가 카탈로그에서 하나도
 * 조회되지 않을 때 던진다. 요청 형식은 유효하나 처리할 seed 가 없어 추천을 만들 수 없으므로
 * {@code 422 Unprocessable Entity} 로 매핑한다(검증 400 과 구분).
 */
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class SeedSongsNotFoundException extends RuntimeException {

    public SeedSongsNotFoundException(final List<Long> seedSongIds) {
        super("No seed songs found for ids: " + seedSongIds);
    }
}
