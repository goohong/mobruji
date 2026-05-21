package com.mobruji.song.domain;

/**
 * Python audio analysis tool 호출 실패 시 발생. 호출 측은 exit code, timeout, JSON parse, IO 오류를
 * 본 예외로 통일해 받는다. spec: {@code docs/features/audio-tooling-bootstrap.md} §3 fallback 정책.
 *
 * <p>운영 fallback (재시도/큐잉/`analysis_status=FAILED` 마킹)은 호출 측에서 본 예외를 catch 해 수행한다.
 *
 * <p>저작권 — message·cause에는 URL 원문/사용자 PII를 담지 않는다. 곡 메타(title/artist)만 허용.
 */
public class AudioAnalysisFailedException extends RuntimeException {

    public AudioAnalysisFailedException(final String message) {
        super(message);
    }

    public AudioAnalysisFailedException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
