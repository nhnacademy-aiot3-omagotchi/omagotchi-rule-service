package site.omagotchi.ruleservice.flow.application;

import lombok.RequiredArgsConstructor;
import site.omagotchi.ruleservice.global.exception.ErrorCode;
import site.omagotchi.ruleservice.global.exception.ErrorType;

@RequiredArgsConstructor
public enum FlowErrorCode implements ErrorCode {

    FLOW_NOT_FOUND(
            ErrorType.NOT_FOUND, // 404
            "FLOW_NOT_FOUND",
            "등록되지 않은 플로우입니다."
    ),
    FLOW_ALREADY_DEPLOYED(
            ErrorType.CONFLICT, // 409
            "FLOW_ALREADY_DEPLOYED",
            "이미 배포된 플로우입니다."
    ),
    NODE_NOT_FOUND(
            ErrorType.NOT_FOUND,
            "NODE_NOT_FOUND",
            "존재하지 않는 노드입니다."
    ),
    NODE_NOT_RECONFIGURABLE(
            ErrorType.CONFLICT,
            "NODE_NOT_RECONFIGURABLE",
            "이 노드는 무중단 설정 변경을 지원하지 않습니다. 재배포가 필요합니다."
    ),
    NODE_CONFIG_REJECTED(
            ErrorType.INVALID_INPUT, // 400
            "NODE_CONFIG_REJECTED",
            "노드가 새 config를 거부했습니다."
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