package site.omagotchi.ruleservice.flow.infrastructure;

import site.omagotchi.ruleservice.flow.domain.registry.NodeRegistry;
import site.omagotchi.ruleservice.flow.domain.registry.NodeProvider;
import site.omagotchi.ruleservice.flow.domain.registry.NodeDescriptor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import site.omagotchi.ruleservice.flow.domain.node.AbstractNode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * RegistryConfig의 동작 검증 - 컨테이너에 등록된 모든 NodeProvider Bean을 자동으로 모아 NodeRegistry에 등록하는지
 *
 * 전체 애플리케이션 컨텍스트(@SpringBootTest)는 MQTT/RabbitMQ 실 연결이 필요하므로 여기서는 부적합하다고 판단하였음
 * RegistryConfig와 테스트 전용 NodeProvider Bean만 로드하는 최소 슬라이스(@SpringJUnitConfig)로 검증
 */
@SpringJUnitConfig(classes = RegistryConfigTest.TestConfig.class)
class RegistryConfigTest {

    @Autowired
    private NodeRegistry nodeRegistry;

    @Test
    @DisplayName("2개의 NodeProvider Bean이 등록돼 있으면 두 쪽 타입이 모두 NodeRegistry에 등록된다")
    void collectsTypesFromAllNodeProviderBeans() {
        assertThat(nodeRegistry.isRegistered("TypeFromProviderA")).isTrue();
        assertThat(nodeRegistry.isRegistered("TypeFromProviderB")).isTrue();
        assertThat(nodeRegistry.getRegisteredTypes()).containsExactlyInAnyOrder("TypeFromProviderA", "TypeFromProviderB");
    }

    @Configuration
    @Import(RegistryConfig.class)
    static class TestConfig {

        @Bean
        NodeProvider firstTestNodeProvider() {
            // NodeProvider는 메서드 하나짜리 인터페이스라 람다로 바로 구현 () -> List.of(...)
            return () -> List.of(
                    new NodeDescriptor("TypeFromProviderA", "테스트용 A", config -> mock(AbstractNode.class))
            );
        }

        @Bean
        NodeProvider secondTestNodeProvider() {
            return () -> List.of(
                    new NodeDescriptor("TypeFromProviderB", "테스트용 B", config -> mock(AbstractNode.class))
            );
        }
    }
}