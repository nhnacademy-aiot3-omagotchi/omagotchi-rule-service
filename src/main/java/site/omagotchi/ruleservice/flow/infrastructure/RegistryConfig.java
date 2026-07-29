package site.omagotchi.ruleservice.flow.infrastructure;

import site.omagotchi.ruleservice.flow.domain.registry.NodeRegistry;
import site.omagotchi.ruleservice.flow.domain.registry.NodeProvider;
import site.omagotchi.ruleservice.flow.domain.registry.NodeDescriptor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Slf4j
@Configuration
public class RegistryConfig {

    /**
     * Spring이 이 메서드를 호출할 때, 컨테이너 안에 등록된 모든 NodeProvider 타입의 Bean을 자동으로 찾아 리스트로 모아서 넣어줌
     * 각 도메인이 자기 노드 타입을 제공하는 NodeProvider 구현체를 @Component로 추가하면, 이 RegistryConfig는 코드 변경 없이 자동으로 인식함
     * @Bean 메서드는 기동 시 한 번만 실행되고, 결과가 싱글톤으로 등록됨
     * FlowParser, FlowManager가 이 NodeRegistry를 생성자 주입으로 받아 씀
     *
     * 등록 실패 시 기동 자체가 실패함
     * registry.register(nodeDescriptor)에서 IllegalStateException이 터지면 그 예외가 그대로 @Bean 메서드 밖으로 전파되고, Spring 이 컨텍스트를 못 띄움
     */
    @Bean
    public NodeRegistry nodeRegistry(List<NodeProvider> providers) {
        NodeRegistry nodeRegistry = new NodeRegistry();

        for (NodeProvider nodeProvider : providers) {
            for (NodeDescriptor nodeDescriptor : nodeProvider.provide()) {
                nodeRegistry.register(nodeDescriptor);
            }
        }

        log.info("[RegistryConfig] 초기화 완료 - Provider {}개, 등록된 타입: {}", providers.size(), nodeRegistry.getRegisteredTypes());

        return nodeRegistry;
    }
}