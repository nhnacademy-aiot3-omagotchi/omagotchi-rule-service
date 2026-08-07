package site.omagotchi.ruleservice.distributed.presentation.response;

import site.omagotchi.ruleservice.distributed.domain.TopologyHealth;

/**
 * GET /api/v1/flows/{flow-id}/topology 응답
 * topologyHealth: 이 엔진 클러스터의 이중화 상태 (HEALTHY = 전원 생존 / DEGRADED = 단독 운전)
 * -> EngineRole(ACTIVE/STANDBY)과는 별개의 개념
 */
public record FlowTopologyResponse(
        String flowId,
        TopologyHealth topologyHealth,
        String reason
) {
}
