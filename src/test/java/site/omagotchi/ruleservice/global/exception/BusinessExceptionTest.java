package site.omagotchi.ruleservice.global.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.BDDAssertions.catchThrowable;
import static org.assertj.core.api.BDDAssertions.then;

class BusinessExceptionTest {

    private static final String DIAGNOSTIC_MESSAGE =
            "operation = update, expectedStatus = ACTIVE, actualStatus = CLOSED";

    @Test
    @DisplayName("진단 메시지 보존")
    void preservesDiagnosticMessage() {
        // Given
        ErrorCode errorCode = CommonErrorCode.INVALID_REQUEST;

        // When
        BusinessException exception = new BusinessException(
                errorCode,
                DIAGNOSTIC_MESSAGE
        );

        // Then
        then(exception.getErrorCode()).isEqualTo(errorCode);
        then(exception.getDiagnosticMessage()).isEqualTo(DIAGNOSTIC_MESSAGE);
        then(exception.getMessage()).isEqualTo(
                errorCode.message() + " - " + DIAGNOSTIC_MESSAGE
        );
        then(exception.getCause()).isNull();
    }

    @Test
    @DisplayName("원인 예외 보존")
    void preservesCause() {
        // Given
        RuntimeException cause = new RuntimeException("database failure");

        // When
        BusinessException exception = new BusinessException(
                CommonErrorCode.INVALID_REQUEST,
                cause
        );

        // Then
        then(exception.getDiagnosticMessage()).isNull();
        then(exception.getMessage()).isEqualTo(CommonErrorCode.INVALID_REQUEST.message());
        then(exception.getCause()).isSameAs(cause);
    }

    @Test
    @DisplayName("진단 메시지와 원인 예외 보존")
    void preservesDiagnosticMessageAndCause() {
        // Given
        RuntimeException cause = new RuntimeException("database failure");

        // When
        BusinessException exception = new BusinessException(
                CommonErrorCode.INVALID_REQUEST,
                DIAGNOSTIC_MESSAGE,
                cause
        );

        // Then
        then(exception.getDiagnosticMessage()).isEqualTo(DIAGNOSTIC_MESSAGE);
        then(exception.getCause()).isSameAs(cause);
    }

    @Test
    @DisplayName("INTERNAL 오류 Code 전달 거부")
    void rejectsInternalErrorCode() {
        // Given
        ErrorCode errorCode = CommonErrorCode.INTERNAL_SERVER_ERROR;

        // When
        Throwable thrown = catchThrowable(() -> new BusinessException(errorCode));

        // Then
        then(thrown)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("BusinessException은 INTERNAL 오류를 전달할 수 없습니다.");
    }
}
