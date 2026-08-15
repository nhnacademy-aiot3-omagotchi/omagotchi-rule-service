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
    private final EngineProperties engineProperties;

    public FlowTopologyResponse getTopology(String flowId) {

        // 내부적으로 requireEntry(flowId)를 호출하고,
        // 내부적으로 flowEntries에 flowId가 key로 존재하는지 확인하고,
        // 존재하지 않는 flowId면 BusinessException을 던짐(FlowErrorCode.FLOW_NOT_FOUND(404))
        this.flowManager.getStatus(flowId);

        List<EngineInfo> peers = this.engineDirectoryPort.listEngines();

        List<EngineInfo> offlinePeers = peers.stream() // 엔진 피어들을 전부 뽑아서
                .filter(engineInfo -> engineInfo.presenceStatus() == PresenceStatus.OFFLINE) // 피어의 PresenceStatus가 OFFLINE인 것만 걸러냄
                .toList();

        List<EngineInfo> authFailedPeers = peers.stream()
                .filter(engineInfo -> engineInfo.presenceStatus() == PresenceStatus.AUTH_FAILED)
                .toList();

        // 피어가 아예 안 보이는 것도 정상이 아님 - 이중화가 확보되지 않은 단독 운전이므로
        // (의도적으로 단일 엔진으로 운영할 때는 expected-peer-count를 0으로 내려서 이 검사를 끔)
        long onlinePeerCount = peers.stream()
                .filter(engineInfo -> engineInfo.presenceStatus() == PresenceStatus.ONLINE)
                .count();
        int expectedPeerCount = this.engineProperties.expectedPeerCount();
        boolean redundancyShortFall = onlinePeerCount < expectedPeerCount;

        if (offlinePeers.isEmpty() && authFailedPeers.isEmpty() && !redundancyShortFall) {
            return new FlowTopologyResponse(
                    flowId,
                    TopologyHealth.HEALTHY,
                    "모든 엔진 정상"
            );
        }

        StringBuilder message = new StringBuilder();

        if (!offlinePeers.isEmpty()) {
            message.append("%s OFFLINE - 단독 운전 중".formatted(
                    offlinePeers.stream()
                            .map(EngineInfo::engineId)
                            .collect(Collectors.joining(", "))
            ));
        }

        if (!authFailedPeers.isEmpty()) {
            if (!message.isEmpty()) {
                message.append(" / ");
            }

            message.append("%s 인증 실패 - INTERNAL_SHARED_SECRET 설정 확인 필요".formatted(
                    authFailedPeers.stream()
                            .map(EngineInfo::engineId)
                            .collect(Collectors.joining(", "))
            ));
        }

        // OFFLINE/AUTH_FAILED 목록이 이미 부족분을 설명하고 있으면 중복해서 덧붙이지 않음
        if (redundancyShortFall && message.isEmpty()) {
            message.append("이중화 미확보 - 발견된 ONLINE 피어 %d개 (기대 %d개)".formatted(onlinePeerCount, expectedPeerCount));
        }

        return new FlowTopologyResponse(
                flowId,
                TopologyHealth.DEGRADED,
                message.toString()
        );
    }
}
