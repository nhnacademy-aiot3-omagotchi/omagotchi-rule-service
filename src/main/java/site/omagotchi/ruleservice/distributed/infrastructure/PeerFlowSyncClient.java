package site.omagotchi.ruleservice.distributed.infrastructure;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import site.omagotchi.ruleservice.distributed.application.port.EngineDirectoryPort;
import site.omagotchi.ruleservice.distributed.domain.EngineInfo;
import site.omagotchi.ruleservice.distributed.domain.PresenceStatus;
import site.omagotchi.ruleservice.flow.application.port.PeerFlowSyncPort;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * PeerFlowSyncPort의 실 구현
 * - EngineDirectoryPort로 파트너 주소를 찾아서 파트너의 내부 전용 엔드포인트(/api/v1/internal/flows/...)로 같은 동작을 재전달
 * - 실패해도 로그만 남기고 무시 - 로컬 적용은 이미 끝난 뒤라 호출자에게 실패를 알릴 필요X
 */
@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "eureka.client.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class PeerFlowSyncClient implements PeerFlowSyncPort {

    private final EngineDirectoryPort engineDirectoryPort;
    private final RestClient engineInternalRestClient;

    @Override
    public void syncStart(String flowId) {
        for (EngineInfo peerEngineInfo : this.reachablePeers("start", flowId)) {
            try {
                this.engineInternalRestClient.post()
                        .uri("http://{host}:{port}/api/v1/internal/flows/{flow-id}/start", peerEngineInfo.host(), peerEngineInfo.port(), flowId)
                        .retrieve()
                        .toBodilessEntity();
            } catch (Exception e) {
                log.warn("[{}] start 파트너 전달 실패 - 로그만 남기고 무시 (flowId = {})", peerEngineInfo.engineId(), flowId, e);
            }
        }
    }

    @Override
    public void syncStop(String flowId) {
        for (EngineInfo peerEngineInfo : this.reachablePeers("stop", flowId)) {
            try {
                this.engineInternalRestClient.post()
                        .uri("http://{host}:{port}/api/v1/internal/flows/{flow-id}/stop", peerEngineInfo.host(), peerEngineInfo.port(), flowId)
                        .retrieve()
                        .toBodilessEntity();
            } catch (Exception e) {
                log.warn("[{}] stop 파트너 전달 실패 - 로그만 남기고 무시 (flowId = {})", peerEngineInfo.engineId(), flowId, e);
            }
        }
    }

    @Override
    public void syncRestart(String flowId) {
        for (EngineInfo peerEngineInfo : this.reachablePeers("restart", flowId)) {
            try {
                this.engineInternalRestClient.post()
                        .uri("http://{host}:{port}/api/v1/internal/flows/{flow-id}/restart", peerEngineInfo.host(), peerEngineInfo.port(), flowId)
                        .retrieve()
                        .toBodilessEntity();
            } catch (Exception e) {
                log.warn("[{}] restart 파트너 전달 실패 - 로그만 남기고 무시 (flowId = {})", peerEngineInfo.engineId(), flowId, e);
            }
        }
    }

    @Override
    public void syncReconfigure(String flowId, String nodeId, Map<String, Object> config) {
        for (EngineInfo peerEngineInfo : this.reachablePeers("config", flowId)) {
            try {
                this.engineInternalRestClient.patch()
                        .uri("http://{host}:{port}/api/v1/internal/flows/{flow-id}/nodes/{node-id}/config", peerEngineInfo.host(), peerEngineInfo.port(), flowId, nodeId)
                        .body(config)
                        .retrieve()
                        .toBodilessEntity();
            } catch (Exception e) {
                log.warn("[{}] config 파트너 전달 실패 - 로그만 남기고 무시 (flowId = {}, nodeId = {})", peerEngineInfo.engineId(), flowId, nodeId, e);
            }
        }
    }

    /**
     * 전달 대상은 ONLINE 피어만
     * OFFLINE/AUTH_FAILED 피어에 보내봐야 connect timeout만큼 블로킹되고 스택트레이스만 반복됨
     * 다만, '이 피어는 명령을 못 받았다'라는 사실 자체는 운영에 필요하므로 건너뛸 때도 경고는 남김
     */
    private List<EngineInfo> reachablePeers(String action, String flowId) {
        List<EngineInfo> reachables = new ArrayList<>();

        for (EngineInfo peerEngineInfo : this.engineDirectoryPort.listEngines()) {
            if (peerEngineInfo.presenceStatus() == PresenceStatus.ONLINE) {
                reachables.add(peerEngineInfo);
                continue;
            }

            log.warn("[{}] {} 파트너 전달 건너뜀 - 피어가 {} 상태 (flowId = {})",
                    peerEngineInfo.engineId(), action, peerEngineInfo.presenceStatus(), flowId);
        }

        return reachables;
    }
}
