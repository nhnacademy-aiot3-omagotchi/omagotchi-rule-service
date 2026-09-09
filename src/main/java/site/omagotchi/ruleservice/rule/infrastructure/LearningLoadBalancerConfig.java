package site.omagotchi.ruleservice.rule.infrastructure;

import com.netflix.appinfo.InstanceInfo;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.loadbalancer.core.DelegatingServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.cloud.netflix.eureka.EurekaServiceInstance;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import reactor.core.publisher.Flux;

import java.util.List;

/** 피어 탐색 설정을 유지하면서 Learning 요청에만 UP 대상 적용. */
// LoadBalancer의 전용 Context에서만 등록, 전체 서비스의 기본 설정 변경 제외.
public class LearningLoadBalancerConfig {

    @Bean
    ServiceInstanceListSupplier learningServiceInstanceListSupplier(ConfigurableApplicationContext context) {
        ServiceInstanceListSupplier discovery = ServiceInstanceListSupplier.builder()
                .withBlockingDiscoveryClient()
                .build(context);

        return new DelegatingServiceInstanceListSupplier(discovery) {
            @Override
            public Flux<List<ServiceInstance>> get() {
                return delegate.get().map(instances -> instances.stream()
                        .filter(instance -> instance instanceof EurekaServiceInstance eureka
                                && eureka.getInstanceInfo().getStatus() == InstanceInfo.InstanceStatus.UP)
                        .toList());
            }
        };
    }
}
