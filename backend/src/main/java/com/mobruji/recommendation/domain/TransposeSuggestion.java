package com.mobruji.recommendation.domain;

/**
 * 키 조옮김(transpose) 제안 (#1544). 원곡 키가 사용자 음역대에 부담스러운(voiceFit 낮은) 곡에 대해,
 * 몇 반음 올리거나({@code +}) 내려({@code -}) 부르면 음역대에 맞는지를 담는다.
 *
 * <ul>
 * <li>{@code semitones} — 권장 조옮김량(반음 수). 양수=올림, 음수=내림. 의미 없는 0 은 허용하지 않는다
 * (개선이 없으면 제안 자체를 만들지 않으므로 {@code null} 로 표현).</li>
 * <li>{@code transposedVoiceFit} — 조옮김을 적용해 재계산한 음역 적합도(0~1). 기존 voiceFit(rangeFit)과
 * 동일한 산식으로 산출돼 직접 비교 가능하다.</li>
 * </ul>
 *
 * <p>곡 키가 UNKNOWN(적합도 산정 근거 없음)이거나 원곡 그대로도 음역대에 무난한 곡은 제안을 만들지 않으며,
 * 해당 경로에서는 응답 필드가 {@code null} 로 노출된다.
 */
public record TransposeSuggestion(
        int semitones,
        double transposedVoiceFit
) {

    public TransposeSuggestion {
        if (semitones == 0) {
            throw new IllegalArgumentException("semitones must not be 0 (no transpose to suggest)");
        }
    }
}
