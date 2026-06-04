package com.mobruji.song.domain;

/**
 * 곡 보컬의 성별 분류 (#1767). 추천 성별 필터(남자곡/여자곡)의 곡측 신호 — 추천 요청의 {@code gender}(MALE/FEMALE)와
 * 비교해 {@code genderFit} 가산점을 산출한다.
 *
 * <p>출처 우선순위 (genderFit 산식의 입력):
 * <ul>
 * <li>큐레이션 = 속성: 시드/큐레이터가 명시한 {@code Song.vocalGender} 컬럼 값을 1순위로 쓴다.</li>
 * <li>임포트 = 추정: 컬럼이 null 인 외부 임포트 곡은 보컬 음역(highMidi)·키로 MALE/FEMALE 를 추정하며 후순위로 가산한다.</li>
 * <li>둘 다 불가하면 null — genderFit 중립값(랭킹 거의 무영향).</li>
 * </ul>
 *
 * <p>{@code MIXED} 는 듀엣/혼성처럼 한 성별로 단정할 수 없는 큐레이션 곡 — 남자곡/여자곡 어느 요청에도 부분 적합으로 가산한다.
 * 추정 경로는 MIXED 를 산출하지 않는다(음역 추정만으로 혼성 여부를 가릴 수 없음).
 */
public enum VocalGender {

    MALE,
    FEMALE,
    MIXED,
}
