package site.omagotchi.ruleservice.global.exception;

import lombok.Getter;

import java.util.Objects;

public class BusinessException extends RuntimeException {

    @Getter
    private final ErrorCode errorCode;

    @Getter
    private final String detail;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, null);
    }

    // detail을 붙일 수 있는 생성자 추가
    public BusinessException(ErrorCode errorCode, String detail) {
        super(buildMessage(errorCode, detail));
        this.errorCode = errorCode;
        this.detail = detail;
    }

    private static String buildMessage(ErrorCode errorCode, String detail) {
        Objects.requireNonNull(errorCode, "errorCode");

        return Objects.isNull(detail)
                ? errorCode.message()
                : errorCode.message() + " - " + detail;
    }
}