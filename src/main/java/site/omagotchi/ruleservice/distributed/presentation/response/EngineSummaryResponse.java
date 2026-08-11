package site.omagotchi.ruleservice.distributed.presentation.response;

import site.omagotchi.ruleservice.distributed.domain.EngineInfo;
import site.omagotchi.ruleservice.distributed.domain.EngineRole;
import site.omagotchi.ruleservice.distributed.domain.PresenceStatus;

/**
 * GET /api/v1/internal/engines/self(엔진 자기소개 정보), GET /api/v1/engines 공용 응답
 * engineRole은 EngineInfo가 아니라 호출하는 쪽(EngineController)이 명시적으로 넘겨줘야 함
 * - 자기 자신 항목: EngineRoleService.getCurrentRole() (그 순간의 실제 판정값)
 * - 피어 항목: EngineInfo.engineRole() 그대로 (피어가 폴링 응답으로 보고한 값)
 */
public record EngineSummaryResponse(
        String engineId,
        String host,
        int port,
        int priority,
        long startedAt,
        PresenceStatus presenceStatus,
        EngineRole engineRole
) {
    /**
     * engineRole은 EngineInfo가 아니라 호출하는 쪽(EngineController)이 EngineRoleService.getCurrentRole()로 그 시점에 판정된 값을 직접 넘겨줘야 함
     * -> EngineInfo.engineRole()은 자기 자신 항목에서는 항상 null이라서
     */
    public static EngineSummaryResponse from(EngineInfo engineInfo, EngineRole engineRole) {
        return new EngineSummaryResponse(
                engineInfo.engineId(),
                engineInfo.host(),
                engineInfo.port(),
                engineInfo.priority(),
                engineInfo.startedAt(),
                engineInfo.presenceStatus(),
                engineRole
        );
    }
}
