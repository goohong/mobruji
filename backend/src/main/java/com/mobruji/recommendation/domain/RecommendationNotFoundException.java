package com.mobruji.recommendation.domain;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class RecommendationNotFoundException extends RuntimeException {

    public RecommendationNotFoundException(final Long id) {
        super("Recommendation request not found for id: " + id);
    }
}
