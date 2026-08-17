package site.omagotchi.ruleservice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import site.omagotchi.ruleservice.distributed.application.EngineIdentityResolver;
import site.omagotchi.ruleservice.distributed.application.EngineRoleService;
import site.omagotchi.ruleservice.distributed.application.TopologyService;
import site.omagotchi.ruleservice.distributed.application.port.EngineDirectoryPort;
import site.omagotchi.ruleservice.distributed.infrastructure.EngineDiscoveryService;
import site.omagotchi.ruleservice.distributed.infrastructure.PeerFlowSyncClient;
import site.omagotchi.ruleservice.flow.application.port.EngineActivePort;
import site.omagotchi.ruleservice.flow.application.port.PeerFlowSyncPort;
import site.omagotchi.ruleservice.global.security.TestJwtKeyConfig;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 이중화 모드의 빈 그래프가 실제로 조립되는지 검증
 * RuleServiceApplicationTests는 eureka.client.enabled=false로만 띄우기 때문에 @ConditionalOnProperty(havingValue = "true") 대상 빈들의 배선은 어디서도 확인되지 않음
 * register-with-eureka/fetch-registry를 꺼서 discovery-service 없이도 컨텍스트만 검증
 * <p>
 * spring.cloud.discovery.enabled는 application-test.yaml에서 false로 꺼져 있어 함께 켜야 함
 * (이 값이 false면 eureka.client.enabled와 무관하게 Eureka 자동설정이 통째로 비활성화됨)
 */
@Import(TestJwtKeyConfig.class)
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "spring.cloud.discovery.enabled=true",
        "eureka.client.enabled=true",
        "eureka.client.register-with-eureka=false",
        "eureka.client.fetch-registry=false",
        "core.base-url=temp",
        "engine.id=test-engine",
        "engine.priority=1"
})
class DistributedContextTest {

    @Autowired
    private EngineRoleService engineRoleService;

    @Autowired
    private EngineDiscoveryService engineDiscoveryService;

    @Autowired
    private PeerFlowSyncClient peerFlowSyncClient;

    @Autowired
    private TopologyService topologyService;

    @Autowired
    private EngineIdentityResolver engineIdentityResolver;

    @Autowired
    private EngineActivePort engineActivePort;

    @Autowired
    private PeerFlowSyncPort peerFlowSyncPort;

    @Autowired
    private EngineDirectoryPort engineDirectoryPort;

    @Test
    @DisplayName("이중화 모드에서 이중화 관련 빈들이 모두 조립된다")
    void distributedBeansAreWired() {
        assertThat(this.engineRoleService).isNotNull();
        assertThat(this.engineDiscoveryService).isNotNull();
        assertThat(this.peerFlowSyncClient).isNotNull();
        assertThat(this.topologyService).isNotNull();
        assertThat(this.engineIdentityResolver).isNotNull();
    }

    @Test
    @DisplayName("포트 인터페이스에는 단일 엔진 모드가 아니라 이중화 모드 구현체가 주입된다")
    void distributedImplementationsAreSelectedForPorts() {
        // SingleEngineMode가 아니라 EngineRoleService/PeerFlowSyncClient가 선택돼야 함
        assertThat(this.engineActivePort).isSameAs(this.engineRoleService);
        assertThat(this.peerFlowSyncPort).isSameAs(this.peerFlowSyncClient);
        assertThat(this.engineDirectoryPort).isSameAs(this.engineDiscoveryService);
    }
}
