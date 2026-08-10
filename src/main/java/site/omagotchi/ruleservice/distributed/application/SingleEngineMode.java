package site.omagotchi.ruleservice.distributed.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import site.omagotchi.ruleservice.flow.application.FlowManager;
import site.omagotchi.ruleservice.flow.application.port.EngineActivePort;
import site.omagotchi.ruleservice.flow.application.port.PeerFlowSyncPort;
import site.omagotchi.ruleservice.flow.domain.node.Activatable;

import java.util.Map;

/**
 * Eureka가 꺼진 환경(단일 엔진 운영, 테스트 등) 전용
 * - 역할 협상 없이 모든 Activatable 노드를 무조건 즉시 활성화함
 * - EngineRoleService(Eureka 떠 있는 환경)와 상호 배타적으로 동작
 * - 단일 엔진 모드에선 항상 ACTIVE로 취급하므로 EngineActivePort도 함께 implements
 * - PeerFlowSyncPort도 함께 implements
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "eureka.client.enabled",
        havingValue = "false"
)
public class SingleEngineMode implements EngineActivePort, PeerFlowSyncPort {

    private final FlowManager flowManager;

    // 모든 ApplicationRunner가 끝난 뒤 실행하는 ApplicationReadyEvent를 사용
    // (이미 StaticFlowLoader.run() 끝 -> 플로우 배포 완료)
    @EventListener(ApplicationReadyEvent.class)
    public void activateAll() {
        log.info("Eureka 비활성 - 단일 엔진 모드로 모든 Activatable 노드를 즉시 활성화");
        this.flowManager.getActivatableNodes().forEach(Activatable::activate);
    }

    @Override
    public boolean isSelfActive() {
        return true;
    }

    // 아래 4개 오버라이딩 -> 단일 엔진 모드이므로 전달할 파트너 없음
    @Override
    public void syncStart(String flowId) {

    }

    @Override
    public void syncStop(String flowId) {

    }

    @Override
    public void syncRestart(String flowId) {

    }

    @Override
    public void syncReconfigure(String flowId, String nodeId, Map<String, Object> config) {

    }
}
