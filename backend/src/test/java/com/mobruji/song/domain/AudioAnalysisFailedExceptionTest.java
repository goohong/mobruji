package com.mobruji.song.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link AudioAnalysisFailedException} 회귀 가드.
 *
 * <p>Python audio analysis tool 호출 측은 exit code/timeout/JSON parse/IO 오류를 본 예외로 통일해 catch
 * 후 fallback(재시도/큐잉/`analysis_status=FAILED` 마킹)을 수행한다 — spec audio-tooling-bootstrap.md §3.
 * RuntimeException 상속이 끊기면 catch 영역이 깨지므로 가드한다.
 */
class AudioAnalysisFailedExceptionTest {

    @Test
    @DisplayName("메시지 단독 생성자가 메시지를 보존한다")
    void messageOnlyConstructor_preservesMessage() {
        final AudioAnalysisFailedException audioAnalysisFailedException = new AudioAnalysisFailedException(
                "analyzer exit 1");

        assertThat(audioAnalysisFailedException.getMessage()).isEqualTo("analyzer exit 1");
        assertThat(audioAnalysisFailedException.getCause()).isNull();
    }

    @Test
    @DisplayName("메시지 + cause 생성자가 cause를 보존한다")
    void messageAndCauseConstructor_preservesCause() {
        final IOException ioException = new IOException("pipe closed");
        final AudioAnalysisFailedException audioAnalysisFailedException = new AudioAnalysisFailedException(
                "analyzer io failure", ioException);

        assertThat(audioAnalysisFailedException.getMessage()).isEqualTo("analyzer io failure");
        assertThat(audioAnalysisFailedException.getCause()).isSameAs(ioException);
    }

    @Test
    @DisplayName("RuntimeException을 상속한다 (호출 측 unchecked catch 전제)")
    void extendsRuntimeException() {
        assertThat(new AudioAnalysisFailedException("x")).isInstanceOf(RuntimeException.class);
    }
}
