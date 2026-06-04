package com.mobruji.user.domain;

/**
 * 사용자 성별(선택 입력). 성별 음역대 구분이 추천 변별력에 쓰이는 페르소나 P-A 를 위해 프로필에 영속한다.
 * 미입력 사용자를 위해 {@code UNSPECIFIED} 를 둔다 — null 대신 명시 값으로 "밝히지 않음" 을 표현.
 */
public enum UserGender {

    MALE,
    FEMALE,
    UNSPECIFIED,
}
