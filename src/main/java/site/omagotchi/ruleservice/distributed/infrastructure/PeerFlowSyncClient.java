package site.omagotchi.ruleservice.distributed.infrastructure;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import site.omagotchi.ruleservice.distributed.application.port.EngineDirectoryPort;
import site.omagotchi.ruleservice.distributed.domain.EngineInfo;
import site.omagotchi.ruleservice.flow.application.port.PeerFlowSyncPort;

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
        for (EngineInfo peerEngineInfo : this.engineDirectoryPort.listEngines()) {
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
        for (EngineInfo peerEngineInfo : this.engineDirectoryPort.listEngines()) {
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
        for (EngineInfo peerEngineInfo : this.engineDirectoryPort.listEngines()) {
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
        for (EngineInfo peerEngineInfo : this.engineDirectoryPort.listEngines()) {
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
}
