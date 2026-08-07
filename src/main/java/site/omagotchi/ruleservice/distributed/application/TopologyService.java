package site.omagotchi.ruleservice.distributed.application;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import site.omagotchi.ruleservice.distributed.application.port.EngineDirectoryPort;
import site.omagotchi.ruleservice.distributed.domain.EngineInfo;
import site.omagotchi.ruleservice.distributed.domain.PresenceStatus;
import site.omagotchi.ruleservice.distributed.domain.TopologyHealth;
import site.omagotchi.ruleservice.distributed.presentation.response.FlowTopologyResponse;
import site.omagotchi.ruleservice.flow.application.FlowManager;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "eureka.client.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class TopologyService {

    private final FlowManager flowManager;
    private final EngineDirectoryPort engineDirectoryPort;

    public FlowTopologyResponse getTopology(String flowId) {

        // 내부적으로 requireEntry(flowId)를 호출하고,
        // 내부적으로 flowEntries에 flowId가 key로 존재하는지 확인하고,
        // 존재하지 않는 flowId면 BusinessException을 던짐(FlowErrorCode.FLOW_NOT_FOUND(404))
        this.flowManager.getStatus(flowId);

        List<EngineInfo> offlinePeers = this.engineDirectoryPort.listEngines().stream() // 엔진 피어들을 전부 뽑아서
                .filter(engineInfo -> engineInfo.presenceStatus() == PresenceStatus.OFFLINE) // 피어의 PresenceStatus가 OFFLINE인 것만 걸러냄
                .toList();

        // OFFLINE인 피어가 한 개도 없으면 -> HEALTHY(전원 생존)
        if (offlinePeers.isEmpty()) {
            return new FlowTopologyResponse(
                    flowId,
                    TopologyHealth.HEALTHY,
                    "모든 엔진 정상"
            );
        }

        String offlineEngineIds = offlinePeers.stream() // OFFLINE인 피어들에 스트림 걸어서
                .map(EngineInfo::engineId) // 각 피어들의 엔진 아이디들만 뽑아서
                .collect(Collectors.joining(", ")); // "아이디1, 아이디2, 아이디3, ..." 이런 식으로 뽑음

        return new FlowTopologyResponse(
                flowId,
                TopologyHealth.DEGRADED,
                "%s OFFLINE - 단독 운전 중".formatted(offlineEngineIds)
        );
    }
}
