package site.omagotchi.ruleservice.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
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
    /**
     * 예상하지 못한 실패를 최종 외부 경계에서 숨겨 응답할 때 사용하는 공통 오류.
     * 마지막 Handler의 응답 전용이며 {@link BusinessException}에 전달하면 안됨.
     */
    INTERNAL_SERVER_ERROR(
            ErrorType.INTERNAL,
            "COMMON_INTERNAL_ERROR",
            "서버 내부 오류가 발생했습니다."
    );

    private final ErrorType type;
    private final String code;
    private final String message;
}
