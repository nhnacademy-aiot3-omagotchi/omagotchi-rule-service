package site.omagotchi.ruleservice.distributed.presentation.response;

import site.omagotchi.ruleservice.distributed.domain.EngineInfo;

/**
 * GET /api/v1/engines/self 응답 (엔진 자기소개 정보)
 * role은 EngineActiveController 구현 후 추가 예정
 */
public record EngineSelfResponse(
        String engineId,
        String host,
        int port,
        int priority,
        long startedAt
) {
    public static EngineSelfResponse from(EngineInfo engineInfo) {
        return new EngineSelfResponse(
                engineInfo.engineId(),
                engineInfo.host(),
                engineInfo.port(),
                engineInfo.priority(),
                engineInfo.startedAt()
        );
    }
}