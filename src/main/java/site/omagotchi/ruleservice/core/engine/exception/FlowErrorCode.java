package site.omagotchi.ruleservice.core.engine.exception;

import lombok.RequiredArgsConstructor;
import site.omagotchi.ruleservice.global.exception.ErrorCode;
import site.omagotchi.ruleservice.global.exception.ErrorType;

@RequiredArgsConstructor
public enum FlowErrorCode implements ErrorCode {

    FLOW_NOT_FOUND(
            ErrorType.NOT_FOUND,
            "FLOW_NOT_FOUND",
            "등록되지 않은 플로우입니다."
    ),
    FLOW_ALREADY_DEPLOYED(
            ErrorType.CONFLICT,
            "FLOW_ALREADY_DEPLOYED",
            "이미 배포된 플로우입니다."
    ),
    NODE_NOT_FOUND(
            ErrorType.NOT_FOUND,
            "NODE_NOT_FOUND",
            "존재하지 않는 노드입니다."
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