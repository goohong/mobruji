package com.mobruji.song.domain;

/**
 * 가창 난이도 — 추천 카드 UI에 "Easy/Normal/Hard"로 노출.
 *
 * <p>이슈 #75/#77 사용자 결정(2026-05-21): 추천 화면의 음역대 막대 그래프 대신
 * 사람이 즉시 이해할 수 있는 난이도 라벨 + 최고음을 표시한다.
 *
 * <p>분류 기준은 fe(`web/lib/difficulty.ts`)와 1:1 일치해야 한다. 분류 함수는
 * {@link Song#deriveDifficulty(int, int)} 참고. ADR 0007(예정)에 기준 영속화 예정.
 *
 * <p>현재는 3단계만 사용한다. PRO 등 상위 단계는 후속 spec에서 추가 검토.
 */
public enum Difficulty {

    EASY,
    NORMAL,
    HARD,
}
