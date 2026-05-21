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
}
