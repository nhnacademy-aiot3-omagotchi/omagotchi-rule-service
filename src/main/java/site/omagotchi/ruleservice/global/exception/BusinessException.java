package site.omagotchi.ruleservice.global.exception;

import lombok.Getter;

import java.util.Objects;

/**
 * Service가 실패 의미와 외부에 공개할 {@link ErrorCode}를 확정한 예상 가능한 실패.
 * Bug, 호출 계약 위반, 내부 불변식 위반과 예상하지 못한 기술 실패를 감싸는 용도로 사용하지 않는다.
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode; // 외부 응답 계약
    private final String diagnosticMessage; // 내부 진단 정보

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, null, null);
    }

    public BusinessException(ErrorCode errorCode, Throwable cause) {
        this(errorCode, null, cause);
    }

    public BusinessException(ErrorCode errorCode, String diagnosticMessage) {
        this(errorCode, diagnosticMessage, null);
    }

    public BusinessException(
            ErrorCode errorCode,
            String diagnosticMessage,
            Throwable cause // 원본 예외
    ) {
        super(buildMessage(errorCode, diagnosticMessage), cause);
        this.errorCode = errorCode;
        this.diagnosticMessage = diagnosticMessage;
    }

    private static String buildMessage(ErrorCode errorCode, String diagnosticMessage) {
        ErrorCode requiredErrorCode = requirePublicErrorCode(errorCode);
        return diagnosticMessage == null || diagnosticMessage.isBlank()
                ? requiredErrorCode.message()
                : requiredErrorCode.message() + " - " + diagnosticMessage;
    }

    private static ErrorCode requirePublicErrorCode(ErrorCode errorCode) {
        ErrorCode requiredErrorCode = Objects.requireNonNull(errorCode, "errorCode");
        if (requiredErrorCode.type() == ErrorType.INTERNAL) {
            throw new IllegalArgumentException(
                    "BusinessException은 INTERNAL 오류를 전달할 수 없습니다."
            );
        }
        return requiredErrorCode;
    }
}
