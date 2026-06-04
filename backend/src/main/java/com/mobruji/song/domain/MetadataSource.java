package com.mobruji.song.domain;

public enum MetadataSource {

    MANUAL_SEED,
    EXTERNAL_API,
    USER_CONTRIBUTION,
    INFERRED,
    /**
     * Python audio analysis tool ({@code tools/audio-analysis/analyze.py}) 산출값으로 lowMidi/highMidi 를
     * 채운 곡. spec {@code audio-tooling-bootstrap.md} PR C — confidence 임계(기본 0.6) 통과 시에만 적용.
     */
    AUDIO_ANALYSIS,
    /**
     * 오디오 자체분석 인프라 부재(#1778) 동안의 interim 출처 — keyOriginal/genre 메타만으로 lowMidi/highMidi 를
     * 합리 추정해 채운 곡. {@code metadataConfidence} 를 낮게(0.3) 부여해 {@link AUDIO_ANALYSIS} 가 임계
     * (기본 0.6) 통과 시 그대로 덮어쓰도록 한다 — 자체분석 권위 우선 정책 유지.
     */
    ESTIMATED,
}
