package site.omagotchi.ruleservice.distributed.presentation.response;

import site.omagotchi.ruleservice.distributed.domain.EngineInfo;
import site.omagotchi.ruleservice.distributed.domain.EngineRole;

/**
 * GET /api/v1/engines/self 응답 (엔진 자기소개 정보)
 */
public record EngineSelfResponse(
        String engineId,
        String host,
        int port,
        int priority,
        long startedAt,
        EngineRole engineRole
) {
    /**
     * engineRole은 EngineInfo가 아니라 호출하는 쪽(EngineController)이 EngineRoleService.getCurrentRole()로 그 시점에 판정된 값을 직접 넘겨줘야 함
     * -> EngineInfo.engineRole()은 자기 자신 항목에서는 항상 null이라서
     */
    public static EngineSelfResponse from(EngineInfo engineInfo, EngineRole engineRole) {
        return new EngineSelfResponse(
                engineInfo.engineId(),
                engineInfo.host(),
                engineInfo.port(),
                engineInfo.priority(),
                engineInfo.startedAt(),
                engineRole
        );
    }
}
