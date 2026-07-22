package site.omagotchi.ruleservice.global.exception;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum CommonErrorCode implements ErrorCode {

    // 400 Bad Request
    INVALID_REQUEST(
            ErrorType.INVALID_INPUT,
            "COMMON_INVALID_REQUEST",
            "요청값이 올바르지 않습니다."
    ),
    MALFORMED_REQUEST(
            ErrorType.INVALID_INPUT,
            "COMMON_MALFORMED_REQUEST",
            "요청 본문을 읽을 수 없습니다."
    ),
    INTERNAL_ERROR(
            ErrorType.INTERNAL,
            "COMMON_INTERNAL_ERROR",
            "서버 내부 오류가 발생했습니다."
    );

    private final ErrorType type;
    private final String code;
    private final String message;

    @Override
    public ErrorType type() {
        return type;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }
}