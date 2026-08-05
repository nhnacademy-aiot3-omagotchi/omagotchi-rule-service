package site.omagotchi.ruleservice.distributed.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import site.omagotchi.ruleservice.flow.application.FlowManager;
import site.omagotchi.ruleservice.flow.domain.node.Activatable;

/**
 * Eureka가 꺼진 환경(단일 엔진 운영, 테스트 등) 전용
 * 역할 협상 없이 모든 Activatable 노드를 무조건 즉시 활성화함
 * EngineRoleService(Eureka 떠 있는 환경)와 상호 배타적으로 동작
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "eureka.client.enabled",
        havingValue = "false"
)
public class SingleEngineActivator {

    private final FlowManager flowManager;

    // 모든 ApplicationRunner가 끝난 뒤 실행하는 ApplicationReadyEvent를 사용 (이미 StaticFlowLoader.run() 끝 -> 플로우 배포 완료)
    @EventListener(ApplicationReadyEvent.class)
    public void activateAll() {
        log.info("Eureka 비활성 - 단일 엔진 모드로 모든 Activatable 노드를 즉시 활성화");
        this.flowManager.getActivatableNodes().forEach(Activatable::activate);
    }
}
