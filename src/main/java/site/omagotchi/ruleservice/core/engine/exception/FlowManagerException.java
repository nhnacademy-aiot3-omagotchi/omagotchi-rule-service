package site.omagotchi.ruleservice.core.engine.exception;

import lombok.Getter;

public class FlowManagerException extends RuntimeException {

    @Getter
    private final FlowErrorCode flowErrorCode;

    public FlowManagerException(FlowErrorCode flowErrorCode, String detail) {
        super(flowErrorCode.message() + " - " + detail);
        this.flowErrorCode = flowErrorCode;
    }

    // errorCode 없이 던지는 기존 경로(예: buildFlow의 id 불일치 예외)를 위한 생성자 유지
    public FlowManagerException(String message) {
        super(message);
        this.flowErrorCode = null;
    }
}